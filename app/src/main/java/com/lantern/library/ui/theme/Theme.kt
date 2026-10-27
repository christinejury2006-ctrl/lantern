package com.lantern.library.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.lantern.library.data.ReaderTheme

/**
 * The app's Material theme, in the Reading Nook's two voices.
 *
 * Material is only used for the few components that need it — text fields, dialogs, ripples — so
 * this maps those onto the nook palette rather than inventing a second colour system. Anything
 * that draws itself (the shelf, the nook's glass, the reader) reads [nookPalette] directly, so the
 * two can never drift: both come from the same tokens in Nook.kt.
 */
@Composable
fun LanternTheme(theme: ReaderTheme = ReaderTheme.LIGHT, content: @Composable () -> Unit) {
    val palette = nookPalette(theme)
    MaterialTheme(
        colorScheme = if (palette.dark) darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.accentInk,
            secondary = palette.aurora[0],
            tertiary = palette.aurora[2],
            background = palette.backdrop[0],
            surface = palette.backdrop[1],
            onBackground = palette.ink,
            onSurface = palette.ink,
            surfaceVariant = palette.backdrop[2],
            onSurfaceVariant = palette.mute,
            outline = palette.glassEdge
        ) else lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.accentInk,
            secondary = palette.aurora[0],
            tertiary = palette.aurora[2],
            background = palette.backdrop[0],
            surface = palette.glass,
            onBackground = palette.ink,
            onSurface = palette.ink,
            surfaceVariant = palette.backdrop[1],
            onSurfaceVariant = palette.mute,
            outline = palette.glassEdge
        ),
        typography = LanternTypography,
        content = content
    )
}
