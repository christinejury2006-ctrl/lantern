package com.lantern.library.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.ui.theme.NookPalette
import com.lantern.library.ui.theme.Playfair

/**
 * The room behind every screen: a soft vertical wash with coloured light hanging in it.
 *
 * The bokeh is the whole trick. A flat gradient reads as a background colour; a handful of large,
 * heavily blurred circles at low alpha reads as a room with a lamp and a window out of shot. They
 * are drawn once per recomposition of the sizes and never move, so this is cheap.
 */
@Composable
fun NookBackdrop(
    palette: NookPalette,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier.background(Brush.verticalGradient(palette.backdrop))) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // Positions are fractions of the frame so every phone gets a composed picture rather
            // than a crop of a fixed one.
            val lights = listOf(
                Triple(0.12f, 0.10f, 0.34f),
                Triple(0.86f, 0.16f, 0.28f),
                Triple(0.72f, 0.44f, 0.42f),
                Triple(0.18f, 0.62f, 0.36f),
                Triple(0.55f, 0.88f, 0.30f)
            )
            lights.forEachIndexed { i, (fx, fy, fr) ->
                val color = palette.bokeh[i % palette.bokeh.size]
                val center = Offset(w * fx, h * fy)
                val radius = w * fr
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = if (palette.dark) 0.30f else 0.62f), Color.Transparent),
                        center = center,
                        radius = radius
                    ),
                    radius = radius,
                    center = center
                )
            }
            // Daylight needs a second, wider pass. A near-white room and a few bright spots leaves
            // a lot of unbroken paper; two enormous aurora-tinted gradients across the opposite
            // diagonal give the flat middle something to be — which is what the glass panes then
            // pick up. At night the bokeh is already doing this, so it is skipped there.
            if (!palette.dark) {
                val aurora = palette.aurora
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            aurora[1].copy(alpha = 0.16f),
                            Color.Transparent,
                            aurora[2].copy(alpha = 0.14f)
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(w, h)
                    )
                )
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.Transparent, aurora[0].copy(alpha = 0.13f), Color.Transparent),
                        start = Offset(w, 0f),
                        end = Offset(0f, h)
                    )
                )
            }
        }
        content()
    }
}

/**
 * A pane of glass: tinted fill, a soft top highlight, and a hairline edge that catches the light.
 *
 * Compose cannot blur what is behind a composable, so the glass is built the way a renderer would
 * fake it — a milky fill, a white wash across the top third, and a bright 1 dp edge. On the light
 * palette that is enough to read as glass over paper; at night, over the neon bokeh, it reads as
 * smoked glass with the colour glowing through.
 */
@Composable
fun GlassSurface(
    palette: NookPalette,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    fill: Color = palette.glass,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val base = if (onClick != null) modifier.clip(shape).clickable(onClick = onClick) else modifier
    Box(
        base
            // The pane has to sit *on* the room, not be printed into it: a lift under each sheet
            // is the difference between glass and a painted rectangle. Daylight gets the stronger
            // lift because the room behind it is bright enough that a flat pane disappears.
            .shadow(
                elevation = if (palette.dark) 10.dp else 16.dp,
                shape = shape,
                clip = false,
                ambientColor = palette.shadow,
                spotColor = palette.shadow
            )
            .clip(shape)
            .background(fill)
            // Wash one: the light caught along the top edge, as before.
            .background(
                Brush.verticalGradient(
                    listOf(palette.glassSheen, Color.Transparent),
                    endY = 220f
                )
            )
            // Wash two: a diagonal specular band. Real glass has one streak across the face where
            // the window is; without it the pane is evenly lit milk, which is the tell that it is
            // not glass at all.
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        palette.glassSheen.copy(alpha = palette.glassSheen.alpha * 0.55f),
                        Color.Transparent
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(1400f, 700f)
                )
            )
            // The rim is a gradient, not a flat hairline: bright along the lit top edge, falling
            // away at the bottom where the pane turns out of the light.
            .border(
                1.dp,
                Brush.verticalGradient(
                    listOf(palette.glassEdge, palette.glassEdge.copy(alpha = 0.28f))
                ),
                shape
            ),
        content = content
    )
}

/** A hairline progress bar: a track of faint ink with a warm-to-aurora fill. */
@Composable
fun NookProgress(
    progress: Float,
    palette: NookPalette,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp
) {
    val value = progress.coerceIn(0f, 1f)
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = size.height / 2f
        drawRoundRect(
            color = palette.ink.copy(alpha = if (palette.dark) 0.16f else 0.10f),
            size = Size(size.width, size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
        )
        if (value > 0f) {
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    listOf(Color(0xFF17151C), Color(0xFF4C267A), Color(0xFF9B6DDA))
                ),
                size = Size((size.width * value).coerceAtLeast(size.height), size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
            )
        }
    }
}

/** A circle of rainbow light, used behind an editor's pick and for the nav orb. */
@Composable
fun AuroraOrb(palette: NookPalette, size: Dp, modifier: Modifier = Modifier, blur: Dp = 0.dp) {
    val colors = palette.aurora
    Box(
        modifier
            .size(size)
            .let { if (blur > 0.dp) it.blur(blur) else it }
            .clip(CircleShape)
            .background(
                Brush.sweepGradient(
                    listOf(colors[0], colors[1], colors[2], colors[3], colors[0])
                )
            )
    )
}

/** The section heading: small caps, wide tracking, in the UI face. */
@Composable
fun NookSectionTitle(
    text: String,
    palette: NookPalette,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text.uppercase(),
            color = palette.ink,
            fontFamily = Playfair,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

/** One tab in the bottom bar. */
data class NookTab(val icon: ImageVector, val label: String, val key: String)

/**
 * The glass bottom bar, with the nook's lamp hanging over the middle of it.
 *
 * Four tabs is the whole navigation, so the bar stays a single pane of glass rather than a strip
 * with a hole in it; the orb floats above the bar as the app's one piece of ornament.
 */
@Composable
fun NookBottomBar(
    palette: NookPalette,
    tabs: List<NookTab>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        GlassSurface(
            palette,
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            shape = RoundedCornerShape(30.dp),
            fill = if (palette.dark) Color(0xCC0E1424) else Color(0xC4FFFFFF)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEach { tab ->
                    val on = tab.key == selected
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { onSelect(tab.key) }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            tab.icon,
                            tab.label,
                            tint = if (on) palette.accent else palette.faint,
                            modifier = Modifier.size(21.dp)
                        )
                        Text(
                            tab.label,
                            color = if (on) palette.accent else palette.faint,
                            fontSize = 10.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }
        // The lamp: a small orb sitting on the bar, half of it above the glass.
        AuroraOrb(
            palette,
            size = 26.dp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 0.dp),
            blur = 0.dp
        )
    }
}

/** Draws a soft shadow under a shape, on a Canvas, using the palette's shadow colour. */
internal fun DrawScope.softShadow(color: Color, shape: Shape, size: Size) {
    drawRoundRect(
        color = color,
        topLeft = Offset(0f, size.height * 0.04f),
        size = size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(28f, 28f)
    )
    // shape is accepted so callers can document intent; the rect above is the only primitive
    // available without a native blur, and at these radii it is indistinguishable.
    @Suppress("UNUSED_EXPRESSION") shape
}

/** The four tabs of the nook. */
val NookTabs = listOf(
    NookTab(Icons.Filled.Home, "Home", "home"),
    NookTab(Icons.Filled.Explore, "Explore", "explore"),
    NookTab(Icons.Filled.LibraryBooks, "Library", "library"),
    NookTab(Icons.Filled.Person, "You", "you")
)
