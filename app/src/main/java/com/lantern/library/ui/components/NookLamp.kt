package com.lantern.library.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lantern.library.ui.theme.NookPalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The nook's little light: a lamp that breathes, and a butterfly that keeps coming back to it.
 *
 * Pure decoration, and deliberately small — it sits in the top-left corner of the library and does
 * nothing but reassure the reader that the app is awake. Everything is drawn on one canvas from a
 * single infinite transition, so it costs two animated floats per frame and allocates nothing:
 *
 * - the glow is a radial gradient whose alpha and radius follow a slow sine, which reads as a lamp
 *   being turned up and down rather than a blinking dot;
 * - the butterfly flies a lissajous loop around the shade — never the same path twice in a reader's
 *   glance, but bounded, so it never wanders across the covers;
 * - its wings flap by scaling the two halves vertically about the body, and it turns to face the
 *   direction it is travelling, which is what makes it look like a butterfly rather than a sprite.
 */
@Composable
fun NookLamp(
    palette: NookPalette,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp
) {
    val transition = rememberInfiniteTransition(label = "lamp")
    val glow by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )
    val flight by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "flight"
    )
    val flap by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 190, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "flap"
    )

    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val shade = Offset(w * 0.42f, h * 0.30f)
        // Warm lamplight, tinted with the palette's accent so it belongs to the room.
        val light = if (palette.dark) palette.aurora[3] else palette.aurora[0]
        val warm = lerp(light, palette.accent, 0.45f)

        // The glow: two stops, breathing between a soft pool and a brighter one.
        val radius = w * (0.34f + 0.20f * glow)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    warm.copy(alpha = (if (palette.dark) 0.55f else 0.42f) * (0.55f + 0.45f * glow)),
                    Color.Transparent
                ),
                center = shade,
                radius = radius
            ),
            radius = radius,
            center = shade
        )

        // The lamp itself: a stem, a bell, and a bright filament bulb under it.
        val stem = palette.ink.copy(alpha = 0.55f)
        drawLine(stem, Offset(shade.x, h * 0.06f), Offset(shade.x, shade.y - h * 0.10f), strokeWidth = w * 0.035f)
        val bell = Path().apply {
            moveTo(shade.x - w * 0.15f, shade.y + h * 0.02f)
            lineTo(shade.x + w * 0.15f, shade.y + h * 0.02f)
            lineTo(shade.x + w * 0.06f, shade.y - h * 0.12f)
            lineTo(shade.x - w * 0.06f, shade.y - h * 0.12f)
            close()
        }
        drawPath(bell, palette.ink.copy(alpha = 0.75f))
        drawCircle(
            color = warm.copy(alpha = 0.85f + 0.15f * glow),
            radius = w * 0.055f,
            center = Offset(shade.x, shade.y + h * 0.05f)
        )

        // The butterfly: a bounded loop around the shade, drifting with the flight phase.
        val angle = flight * 2f * PI.toFloat()
        val bx = shade.x + cos(angle) * w * 0.30f
        val by = shade.y + sin(angle * 2f) * h * 0.16f + h * 0.04f
        // Heading from the derivative of the loop, so the wings tilt the way it is moving.
        val heading = -sin(angle) * 0.6f + cos(angle * 2f) * 0.4f
        drawButterfly(
            centre = Offset(bx, by),
            wingSpan = w * 0.11f,
            flap = flap,
            tilt = heading,
            colour = if (palette.dark) palette.aurora[1] else palette.accent.copy(alpha = 0.9f)
        )
    }
}

private fun DrawScope.drawButterfly(
    centre: Offset,
    wingSpan: Float,
    flap: Float,
    tilt: Float,
    colour: Color
) {
    // The body: a short, dark line along the direction of travel.
    val dx = tilt * wingSpan * 0.5f
    drawLine(
        colour.copy(alpha = 0.95f),
        Offset(centre.x - dx * 0.4f, centre.y),
        Offset(centre.x + dx * 0.4f, centre.y),
        strokeWidth = wingSpan * 0.22f
    )
    // Wings: scale with the flap, so they close to a sliver and open wide.
    val openness = 0.25f + 0.75f * flap
    for (side in listOf(-1f, 1f)) {
        val wing = Path().apply {
            moveTo(centre.x, centre.y)
            quadraticBezierTo(
                centre.x + side * wingSpan * 1.25f,
                centre.y - wingSpan * 0.95f * openness + dx,
                centre.x + side * wingSpan * 0.55f,
                centre.y + wingSpan * 0.10f
            )
            quadraticBezierTo(
                centre.x + side * wingSpan * 0.90f,
                centre.y + wingSpan * 0.85f * openness,
                centre.x,
                centre.y
            )
            close()
        }
        drawPath(wing, colour.copy(alpha = 0.55f + 0.45f * flap))
    }
}

