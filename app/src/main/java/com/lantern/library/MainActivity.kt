package com.lantern.library

import android.graphics.Color
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.lantern.library.data.StartupTrace
import kotlin.concurrent.thread

object ExternalBookIntent {
    var pendingUri by mutableStateOf<Uri?>(null)
}

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
        intent?.let(::queueBookIntent)

        thread(name = "LorePoster", isDaemon = true) {
            LorePoster.decode(applicationContext)
        }

        val handoff = View(this)
        handoff.setBackgroundColor(Color.parseColor("#F3F4F6"))
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


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        queueBookIntent(intent)
    }

    @Suppress("DEPRECATION")
    private fun queueBookIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_SEND) {
            val uri = intent.data ?: intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (uri != null) ExternalBookIntent.pendingUri = uri
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> if (ReaderVolumeKeys.navigate(-1)) return true
                KeyEvent.KEYCODE_VOLUME_DOWN -> if (ReaderVolumeKeys.navigate(1)) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun startCompose() {
        if (composeStarted) return
        composeStarted = true
        StartupTrace.mark("native first frame")
        setContent { LaunchGate() }
        StartupTrace.mark("setContent returned")
    }
}
