package xyz.anye.wafce

import android.app.Application
import xyz.anye.wafce.core.AppContextHolder

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.init(this)
    }
}
