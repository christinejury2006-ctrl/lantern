package com.lantern.library.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.lantern.library.data.ReaderTheme

@Composable
fun LanternTheme(theme: ReaderTheme = ReaderTheme.LIGHT, content: @Composable () -> Unit) {
    val dark = theme == ReaderTheme.DARK
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(
            primary = Color(0xFFC4B5E0), onPrimary = Color(0xFF1E2430), secondary = Coral,
            background = Slate, surface = SlateHi, onBackground = NightText, onSurface = NightText
        ) else lightColorScheme(
            primary = Color(0xFF7A5BA8), onPrimary = Color.White, secondary = Coral,
            background = Color.Transparent, surface = Cream, onBackground = Ink, onSurface = Ink
        ),
        typography = LanternTypography,
        content = content
    )
}
