package com.lantern.library.data

import android.os.SystemClock
import android.util.Log
import com.lantern.library.BuildConfig

/** Cold-start timestamps. Never logs secrets. */
object StartupTrace {
    private const val TAG = "LoreStartup"
    private val t0 = SystemClock.elapsedRealtime()

    fun mark(stage: String) {
        if (!BuildConfig.DEBUG) return
        val ms = SystemClock.elapsedRealtime() - t0
        val thread = Thread.currentThread().name
        Log.i(TAG, "T+${ms}ms thread=$thread $stage")
    }
}
