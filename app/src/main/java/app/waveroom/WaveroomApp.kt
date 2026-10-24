package app.waveroom

import android.app.Application

class WaveroomApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diag.install(this)
        AppCore.init(this)
    }
}
