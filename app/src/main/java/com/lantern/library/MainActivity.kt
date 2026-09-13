package com.lantern.library

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.lantern.library.data.StartupTrace

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        StartupTrace.mark("MainActivity.onCreate")
        val splash = installSplashScreen()
        StartupTrace.mark("installSplashScreen")
        splash.setOnExitAnimationListener {
            StartupTrace.mark("splash exit remove")
            it.remove()
        }
        super.onCreate(savedInstanceState)
        StartupTrace.mark("super.onCreate")
        StartupTrace.mark("setContent")
        setContent { LaunchGate() }
        StartupTrace.mark("setContent returned")
    }
}
