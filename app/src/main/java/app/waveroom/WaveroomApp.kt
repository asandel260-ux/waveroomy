package app.waveroom

import android.app.Application

class WaveroomApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCore.init(this)
    }
}
