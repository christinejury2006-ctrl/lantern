package com.lantern.library

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        com.lantern.library.data.StartupTrace.mark("MainActivity.onCreate")
        val splash = installSplashScreen()
        splash.setOnExitAnimationListener { it.remove() }
        super.onCreate(savedInstanceState)
        com.lantern.library.data.StartupTrace.mark("setContent")
        setContent { LaunchGate() }
    }
}
