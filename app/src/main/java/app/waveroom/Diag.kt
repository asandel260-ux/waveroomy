package app.waveroom

import android.app.Application
import android.os.Build
import java.io.File

/** Crash and diagnostics reports, so a problem on a real phone can be sent back and fixed precisely. */
object Diag {
    private var file: File? = null

    fun install(app: Application) {
        file = File(app.filesDir, "crash.txt")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                file?.writeText(header() + "CRASH on thread ${t.name}\n" + android.util.Log.getStackTraceString(e) + "\n\n--- recent log ---\n" + logcat(250))
            } catch (x: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    private fun header(): String {
        val v = try { AppCore.app.packageManager.getPackageInfo(AppCore.app.packageName, 0).versionName } catch (e: Throwable) { "?" }
        return "HoMit $v · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n"
    }

    private fun logcat(lines: Int): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", lines.toString(), "-v", "time"))
        val out = p.inputStream.bufferedReader().readText()
        p.destroy()
        out.takeLast(30_000)
    } catch (e: Throwable) {
        "(log unavailable)"
    }

    /** A crash report left by the previous run, if any. */
    fun pending(): String? = try {
        file?.takeIf { it.exists() && it.length() > 0 }?.readText()
    } catch (e: Throwable) {
        null
    }

    fun clear() {
        try { file?.delete() } catch (e: Throwable) {}
    }

    /** Everything useful about the current state, for the Settings → Send diagnostics button. */
    fun report(): String {
        val sb = StringBuilder(header())
        try {
            sb.append("Reconnect helper: ").append(Rendezvous.status.value).append('\n')
            sb.append("Connected friends: ").append(AppCore.connected.value.size).append('\n')
            sb.append("Contacts: ").append(AppCore.social.contacts.value.size).append(" (paired ")
                .append(AppCore.social.contacts.value.values.count { it.pair.isNotEmpty() }).append(")\n")
            sb.append("Room phase: ").append(AppCore.session.phase.value).append('\n')
            sb.append("Last connection report:\n").append(AppCore.mesh.report.value.ifBlank { "(none)" }).append('\n')
        } catch (e: Throwable) {
        }
        sb.append("\n--- recent log ---\n").append(logcat(300))
        return sb.toString()
    }
}
