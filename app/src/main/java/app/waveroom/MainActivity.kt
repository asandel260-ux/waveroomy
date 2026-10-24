package app.waveroom

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.waveroom.ui.AppRoot
import app.waveroom.ui.theme.WaveroomTheme
import java.io.File

class MainActivity : ComponentActivity(), Platform {

    private var micCb: ((Boolean) -> Unit)? = null
    private var camCb: ((Boolean) -> Unit)? = null
    private var projCb: ((Intent?) -> Unit)? = null
    private var imgCb: ((Uri?) -> Unit)? = null
    private var fileCb: ((Uri?) -> Unit)? = null
    private var saveCb: ((Boolean) -> Unit)? = null
    private var saveBytes: ByteArray? = null

    private val micLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> micCb?.invoke(ok); micCb = null }
    private val camLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> camCb?.invoke(ok); camCb = null }
    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val projLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        projCb?.invoke(if (r.resultCode == RESULT_OK) r.data else null); projCb = null
    }
    private val imgLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { u -> imgCb?.invoke(u); imgCb = null }
    private val fileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> fileCb?.invoke(u); fileCb = null }
    private val saveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { u ->
        val bytes = saveBytes
        saveBytes = null
        if (u == null || bytes == null) {
            saveCb?.invoke(false)
        } else {
            try {
                contentResolver.openOutputStream(u)?.use { it.write(bytes) }
                saveCb?.invoke(true)
            } catch (e: Exception) {
                saveCb?.invoke(false)
            }
        }
        saveCb = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AppCore.platform = this
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            WaveroomTheme { AppRoot() }
        }
        requestNotifications()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        try {
            app.waveroom.Rendezvous.resume()
        } catch (e: Exception) {
        }
    }

    override fun onStop() {
        try {
            AppCore.flushAll()
        } catch (e: Exception) {
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (AppCore.platform === this) AppCore.platform = null
        super.onDestroy()
    }

    private fun handleIntent(i: Intent?) {
        val d = i?.data ?: return
        if (d.scheme == "waveroom" && d.host == "add") {
            app.waveroom.Discovery.cardFromLink(d.toString())?.let { AppCore.pendingAdd.value = it }
            return
        }
        if (d.scheme == "waveroom") {
            val c = d.getQueryParameter("c")
            if (!c.isNullOrEmpty()) AppCore.pendingCode.value = c
        }
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun requestMic(cb: (Boolean) -> Unit) {
        if (has(Manifest.permission.RECORD_AUDIO)) return cb(true)
        micCb = cb
        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun requestCamera(cb: (Boolean) -> Unit) {
        if (has(Manifest.permission.CAMERA)) return cb(true)
        camCb = cb
        camLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun requestProjection(cb: (Intent?) -> Unit) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projCb = cb
        projLauncher.launch(mpm.createScreenCaptureIntent())
    }

    override fun pickImage(cb: (Uri?) -> Unit) {
        imgCb = cb
        imgLauncher.launch("image/*")
    }

    override fun pickFile(cb: (Uri?) -> Unit) {
        fileCb = cb
        fileLauncher.launch(arrayOf("*/*"))
    }

    override fun saveFile(name: String, mime: String, bytes: ByteArray, cb: (Boolean) -> Unit) {
        saveCb = cb
        saveBytes = bytes
        saveLauncher.launch(name)
    }

    override fun shareText(text: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(i, "Share"))
    }

    override fun shareFile(name: String, mime: String, bytes: ByteArray) {
        try {
            val dir = File(cacheDir, "share").apply { mkdirs() }
            val f = File(dir, name)
            f.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(i, "Share"))
        } catch (e: Exception) {
            AppCore.toast("Could not share the file")
        }
    }

    override fun copy(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        AppCore.toast("Copied")
    }

    override fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
        }
    }
}
