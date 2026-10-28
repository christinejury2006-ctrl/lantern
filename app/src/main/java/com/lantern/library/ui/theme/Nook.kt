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
    /**
     * The reader's page.
     *
     * Not the backdrop: a page is paper, and paper in the nook's daylight is warm white, not the
     * rose of the room behind it. At night it is not black either — a black page is a screen,
     * and the point of the reader is that it is not one — so it is a very deep ink-blue that the
     * white type sits on without the glare a pale page would give at 2 a.m.
     */
    val page: Color,
    /** Where a photo of a room would be: the shadow under the bottom bar and cards. */
    val shadow: Color
)

/** The Reading Nook, in daylight: cream, rose light, and glass with a white edge. */
private val NookLight = NookPalette(
    dark = false,
    // Daylight is where the colour has to be carried by hue rather than brightness: the room is
    // already near-white, so the gradient moves cream → peach → rose-lilac instead of getting
    // darker. Every stop still has to clear the contrast promises in `NookPaletteTest`, which is
    // why the last one stops at roughly 0.81 luminance rather than going fully rose.
    backdrop = listOf(Color(0xFFFFF8F2), Color(0xFFFDEAE4), Color(0xFFF7E4EC)),
    bokeh = listOf(
        Color(0xFFFFC9A6), Color(0xFFFFB0C4), Color(0xFFB9EBD8),
        Color(0xFFFFDFA6), Color(0xFFD8C6FF)
    ),
    // Glass in daylight is *less* opaque than it used to be, and the light still shows through it:
    // the pane reads as a sheet of glass over coloured paper rather than as a white card with a
    // wash on top. What makes it read is the rim and the specular sweep in `GlassSurface`, not the
    // fill, so the fill can stay thin.
    glass = Color(0x99FFFFFF),
    glassSheen = Color(0x8CFFFFFF),
    glassEdge = Color(0xE6FFFFFF),
    ink = Color(0xFF35272B),
    mute = Color(0xFF7C6870),
    faint = Color(0xFFAE9AA2),
    // Deep terracotta rather than the pale rose the room is lit with: this token has to work as
    // a button fill under white text *and* as link-coloured words on cream, and the pale one only
    // clears one of those. The rose still lives in `aurora` and in the bokeh.
    accent = Color(0xFF9E5349),
    accentInk = Color(0xFFFFFFFF),
    aurora = listOf(
        Color(0xFFFF9BB8), Color(0xFFB79CFF), Color(0xFF8ED9FF), Color(0xFF9AE6C8)
    ),
    coverFallback = Color(0xFFEADFD8),
    page = Color(0xFFFFFCF6),
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
    page = Color(0xFF0B1120),
    shadow = Color(0x66000000)
)

/** The palette for a theme, so no screen has to know a hex value. */
fun nookPalette(theme: ReaderTheme): NookPalette =
    if (theme == ReaderTheme.DARK) NookDark else NookLight

/** The palette for an already-decided darkness, for components that only get a Boolean. */
fun nookPalette(dark: Boolean): NookPalette = if (dark) NookDark else NookLight
