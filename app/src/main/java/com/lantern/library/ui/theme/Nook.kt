package com.lantern.library.ui.theme

import androidx.compose.ui.graphics.Color
import com.lantern.library.data.ReaderTheme

/**
 * The Reading Nook palette: the app's own colours, in a light and a dark voice.
 *
 * The two are the same design, not one design with the lights off. Light is a warm room — cream
 * paper, rose and peach light spilling in, faintly coloured glass laid over it. Dark is the same
 * room at night — deep navy, and the colour that in daylight was only a blush becomes the thing
 * you see: neon pinks, cyan and mint glowing behind smoked glass.
 *
 * Everything here is a token. Screens ask for `palette.ink`, never for a hex value, so the two
 * voices stay in step and a colour can be corrected in one place.
 */
data class NookPalette(
    /** True for the night voice. Every screen checks this rather than testing colours. */
    val dark: Boolean,
    /** Top and bottom of the room's background gradient, in order. */
    val backdrop: List<Color>,
    /** The coloured light that hangs in the air: bokeh, in order, drawn at low alpha. */
    val bokeh: List<Color>,
    /** Glass surfaces: fill, a lighter wash for the top highlight, and the hairline edge. */
    val glass: Color,
    val glassSheen: Color,
    val glassEdge: Color,
    /** Text. */
    val ink: Color,
    val mute: Color,
    val faint: Color,
    /** The warm accent that carries selection, buttons and progress. */
    val accent: Color,
    val accentInk: Color,
    /** The rainbow used behind the editor's picks and the nav orb. */
    val aurora: List<Color>,
    /** The card face a book cover sits on when it has no artwork. */
    val coverFallback: Color,
    /** Where a photo of a room would be: the shadow under the bottom bar and cards. */
    val shadow: Color
)

/** The Reading Nook, in daylight: cream, rose light, and glass with a white edge. */
private val NookLight = NookPalette(
    dark = false,
    backdrop = listOf(Color(0xFFFFF7F1), Color(0xFFFBE9E7), Color(0xFFF6E3EA)),
    bokeh = listOf(
        Color(0xFFFFD9BE), Color(0xFFFFC7D2), Color(0xFFD9F0E4),
        Color(0xFFFFE9C7), Color(0xFFE9DDFF)
    ),
    glass = Color(0xA6FFFFFF),
    glassSheen = Color(0x66FFFFFF),
    glassEdge = Color(0xCCFFFFFF),
    ink = Color(0xFF35272B),
    mute = Color(0xFF7C6870),
    faint = Color(0xFFAE9AA2),
    accent = Color(0xFFC4796B),
    accentInk = Color(0xFFFFFFFF),
    aurora = listOf(
        Color(0xFFFF9BB8), Color(0xFFB79CFF), Color(0xFF8ED9FF), Color(0xFF9AE6C8)
    ),
    coverFallback = Color(0xFFEADFD8),
    shadow = Color(0x33513A42)
)

/** The Reading Nook at night: navy, neon behind glass, warm brass for selection. */
private val NookDark = NookPalette(
    dark = true,
    backdrop = listOf(Color(0xFF0B101F), Color(0xFF111A31), Color(0xFF16122A)),
    bokeh = listOf(
        Color(0xFFFF5FA2), Color(0xFF4FD8FF), Color(0xFF7CFFB2),
        Color(0xFFFFC65F), Color(0xFF9A6BFF)
    ),
    glass = Color(0x1FFFFFFF),
    glassSheen = Color(0x14FFFFFF),
    glassEdge = Color(0x33FFFFFF),
    ink = Color(0xFFF2F4FB),
    mute = Color(0xFFA9B4CE),
    faint = Color(0xFF6C7791),
    accent = Color(0xFFE9C27E),
    accentInk = Color(0xFF1A1408),
    aurora = listOf(
        Color(0xFFFF6FB0), Color(0xFF9C7BFF), Color(0xFF4FD8FF), Color(0xFF6EF0C0)
    ),
    coverFallback = Color(0xFF1E2740),
    shadow = Color(0x66000000)
)

/** The palette for a theme, so no screen has to know a hex value. */
fun nookPalette(theme: ReaderTheme): NookPalette =
    if (theme == ReaderTheme.DARK) NookDark else NookLight

/** The palette for an already-decided darkness, for components that only get a Boolean. */
fun nookPalette(dark: Boolean): NookPalette = if (dark) NookDark else NookLight
