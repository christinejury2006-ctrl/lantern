package com.lantern.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.lantern.library.data.StartupTrace

/** Matches values/colors.xml lore_launch_top / system splash. */
private val SystemSplashMatch = Color(0xFFC8A1F3)

@Composable
fun LaunchGate() {
    var firstFrame by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        StartupTrace.mark("LaunchGate first composition")
        withFrameNanos { }
        StartupTrace.mark("first Compose frame")
        firstFrame = true
    }
    if (!firstFrame) {
        Box(Modifier.fillMaxSize().background(SystemSplashMatch))
    } else {
        LoreAfterSplash()
    }
}
