package app.waveroom.media

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import app.waveroom.AppCore
import app.waveroom.MainActivity
import app.waveroom.R

/** Keeps the process alive (mic, mesh, screen capture) while a room is open. */
class RoomService : Service() {

    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_LEAVE) {
            try {
                AppCore.session.leave()
            } catch (e: Exception) {
            }
            stopSelf()
            return START_NOT_STICKY
        }
        val projection = intent?.getBooleanExtra(EXTRA_PROJECTION, false) == true
        ensureChannel(this)
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (projection) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            try {
                startForeground(NOTIF_ID, n, types)
            } catch (e: Exception) {
                try {
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } catch (e2: Exception) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        } else {
            startForeground(NOTIF_ID, n)
        }
        acquireLocks()
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val title = AppCore.session.room?.meta?.title ?: "Waveroom"
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val leave = PendingIntent.getService(
            this, 1, Intent(this, RoomService::class.java).setAction(ACTION_LEAVE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_stat_wave)
            .setContentTitle(title)
            .setContentText("You're in a room — tap to return")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Leave", leave).build())
            .build()
    }

    private fun acquireLocks() {
        try {
            if (wake == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "waveroom:room").also { it.setReferenceCounted(false); it.acquire(8 * 3600_000L) }
            }
            if (wifi == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifi = wm.createWifiLock(mode, "waveroom:room").also { it.setReferenceCounted(false); it.acquire() }
            }
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        try {
            wake?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
        }
        try {
            wifi?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
        }
        wake = null; wifi = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "waveroom_room"
        const val NOTIF_ID = 42
        const val ACTION_LEAVE = "app.waveroom.LEAVE"
        const val EXTRA_PROJECTION = "projection"

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CHANNEL) == null) {
                    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Active room", NotificationManager.IMPORTANCE_LOW))
                }
            }
        }

        fun start(ctx: Context, projection: Boolean) {
            val i = Intent(ctx, RoomService::class.java).putExtra(EXTRA_PROJECTION, projection)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RoomService::class.java))
        }
    }
}
