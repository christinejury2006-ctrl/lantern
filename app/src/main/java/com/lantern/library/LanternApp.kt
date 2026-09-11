package com.lantern.library

import android.app.Application

class LanternApp : Application() {
    override fun onCreate() {
        com.lantern.library.data.StartupTrace.mark("Application.onCreate")
        super.onCreate()
        com.lantern.library.data.StartupTrace.mark("Application.onCreate done")
    }
}
