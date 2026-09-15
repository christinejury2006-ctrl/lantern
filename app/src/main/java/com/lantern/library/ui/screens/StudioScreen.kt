package com.lantern.library.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.ReaderTheme
import com.lantern.library.ui.components.AuroraBackdrop
import com.lantern.library.ui.theme.Ink
import com.lantern.library.ui.theme.InkSoft
import com.lantern.library.ui.theme.NightText
import com.lantern.library.ui.theme.Playfair

@Composable
fun StudioScreen(theme: ReaderTheme) {
    val dark = theme == ReaderTheme.DARK
    val ink = if (dark) NightText else Ink
    val mute = if (dark) Color(0xFFD0D6DE) else InkSoft
    AuroraBackdrop(theme) {
        Column(Modifier.fillMaxSize().padding(18.dp, 18.dp, 18.dp, 96.dp)) {
            Text("Studio", color = ink, fontFamily = Playfair, fontSize = 30.sp)
            Text(
                "Create and process books here.",
                color = mute,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}
