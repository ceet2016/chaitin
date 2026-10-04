package com.uniomo.wafce

import android.app.Application
import com.uniomo.wafce.core.AppContextHolder

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.init(this)
    }
}
