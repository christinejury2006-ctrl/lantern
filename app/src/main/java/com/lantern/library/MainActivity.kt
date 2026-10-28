package com.lantern.library

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.lantern.library.data.StartupTrace

class MainActivity : ComponentActivity() {
    @Volatile
    private var composeStarted = false

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

        val handoff = View(this)
        handoff.setBackgroundColor(Color.parseColor("#FFF7F1"))
        setContentView(handoff)
        StartupTrace.mark("native setContentView")

        val listener = object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                handoff.post {
                    runCatching { handoff.viewTreeObserver.removeOnDrawListener(this) }
                    startCompose()
                }
            }
        }
        handoff.post {
            if (composeStarted) return@post
            handoff.viewTreeObserver.addOnDrawListener(listener)
            handoff.invalidate()
        }
    }

    private fun startCompose() {
        if (composeStarted) return
        composeStarted = true
        StartupTrace.mark("native first frame")
        setContent { LaunchGate() }
        StartupTrace.mark("setContent returned")
    }
}
