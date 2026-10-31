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
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lantern.library.ui.theme.NookPalette
import kotlin.math.sin

/**
 * The nook's floor lamp: shade, arm, joints, base, and a pool of warm light on the floor.
 *
 * This is a small piece of scenery, not an icon. It is drawn as a standing lamp seen slightly from
 * above, because a lamp lying flat on a page reads as a glyph while one standing on a floor with
 * its own light beneath it reads as a room.
 *
 * Parts, bottom to top:
 *
 * - **a pool of light** on the floor — an ellipse of warm radial gradient whose radius and
 *   brightness follow a slow breathing curve, clipped to the floor so it never floats;
 * - **the base** — a flat ellipse with a lighter top edge, which is what makes it look like it is
 *   sitting on something rather than pasted on;
 * - **the arm** — a tapered pole with two visible joints, so it reads as a mechanism instead of a
 *   line;
 * - **the shade** — a trapezoid with a vertical gradient: lit on the inside, shaded on the
 *   outside, with a rim highlight along the top edge;
 * - **the bulb** — a small, very bright disc under the shade, whose glow spills down the arm.
 *
 * Efficiency: the animated values stay as [State] objects and are read **inside** the draw lambda.
 * Reading them in the composable body would recompose the whole lamp on every frame of the
 * flicker; reading them in `Canvas`' draw scope only re-runs the drawing, which is three gradients
 * and a handful of paths. Nothing here allocates a bitmap, and there are no per-frame objects
 * beyond the gradients Compose already caches.
 */
@Composable
fun NookLamp(
    palette: NookPalette,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp
) {
    val transition = rememberInfiniteTransition(label = "lamp")
    // The filament: a slow, slight variation, like a real bulb that is warm rather than steady.
    val flicker: State<Float> = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "flicker"
    )
    // The pool on the floor breathes on a different period, so the two never beat in step and the
    // light looks alive instead of looping.
    val pool: State<Float> = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pool"
    )

    Canvas(modifier.size(size)) {
        drawLamp(palette, flicker.value, pool.value)
    }
}

/** All of the lamp, in one pass. Kept out of the composable so nothing here touches composition. */
private fun DrawScope.drawLamp(palette: NookPalette, glow: Float, pool: Float) {
    val w = size.width
    val h = size.height
    // The floor is a little above the bottom edge, so the light has somewhere to land and the
    // base has room for its own thickness.
    val floorY = h * 0.90f
    val shadowY = h * 0.93f

    // ---- palette -------------------------------------------------------------------------
    // Warm light is always warmer than the room: the shade tints it, the palette supplies the
    // warmth, so day and night differ in what the *room* is made of, not in whether the lamp works.
    val warmBase = if (palette.dark) palette.aurora.getOrElse(3) { palette.accent } else palette.aurora.getOrElse(1) { palette.accent }
    val warm = lerp(warmBase, palette.accent, 0.55f)
    val glowStrength = 0.62f + 0.38f * glow
    val metal = palette.ink
    val metalLit = lerp(metal, warm, 0.35f)

    // ---- the pool of light on the floor ----------------------------------------------------
    val poolRadiusX = w * (0.40f + 0.10f * pool)
    val poolRadiusY = w * (0.11f + 0.03f * pool)
    val poolCentre = Offset(w * 0.50f, floorY)
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(
                warm.copy(alpha = (if (palette.dark) 0.50f else 0.34f) * glowStrength),
                warm.copy(alpha = 0.10f * glowStrength),
                Color.Transparent
            ),
            center = poolCentre,
            radius = poolRadiusX
        ),
        topLeft = Offset(poolCentre.x - poolRadiusX, poolCentre.y - poolRadiusY),
        size = Size(poolRadiusX * 2f, poolRadiusY * 2f)
    )
    // A soft contact shadow under the base, so the lamp is standing on the floor rather than
    // hovering above it.
    drawOval(
        color = Color.Black.copy(alpha = if (palette.dark) 0.32f else 0.12f),
        topLeft = Offset(w * 0.36f, shadowY - h * 0.020f),
        size = Size(w * 0.28f, h * 0.030f)
    )

    // ---- the base --------------------------------------------------------------------------
    val baseLeft = w * 0.34f
    val baseWidth = w * 0.32f
    drawOval(
        brush = Brush.verticalGradient(
            colors = listOf(metalLit.copy(alpha = 0.95f), metal.copy(alpha = 0.75f)),
            startY = floorY - h * 0.045f,
            endY = floorY + h * 0.005f
        ),
        topLeft = Offset(baseLeft, floorY - h * 0.045f),
        size = Size(baseWidth, h * 0.055f)
    )
    // The top edge catches the light: a thin, brighter ellipse on the leading half.
    drawOval(
        color = metalLit.copy(alpha = 0.55f),
        topLeft = Offset(baseLeft + baseWidth * 0.08f, floorY - h * 0.049f),
        size = Size(baseWidth * 0.70f, h * 0.022f)
    )

    // ---- the arm ---------------------------------------------------------------------------
    val poleX = w * 0.50f
    val poleBottom = floorY - h * 0.030f
    val poleTop = h * 0.30f
    // Tapered: thicker at the base than at the shade, drawn as a filled path rather than a stroked
    // line so the taper and the highlight are both possible.
    val arm = Path().apply {
        moveTo(poleX - w * 0.020f, poleBottom)
        lineTo(poleX - w * 0.012f, poleTop)
        lineTo(poleX + w * 0.012f, poleTop)
        lineTo(poleX + w * 0.020f, poleBottom)
        close()
    }
    drawPath(
        arm,
        brush = Brush.horizontalGradient(
            colors = listOf(metal.copy(alpha = 0.55f), metalLit.copy(alpha = 0.95f), metal.copy(alpha = 0.60f)),
            startX = poleX - w * 0.02f,
            endX = poleX + w * 0.02f
        )
    )
    // Two joints: small discs where a real lamp folds, each with a lit top edge.
    listOf(poleBottom - h * 0.045f, h * 0.55f).forEach { y ->
        drawCircle(metal.copy(alpha = 0.85f), radius = w * 0.026f, center = Offset(poleX, y))
        drawCircle(metalLit.copy(alpha = 0.65f), radius = w * 0.014f, center = Offset(poleX, y - h * 0.006f))
    }

    // ---- the shade -------------------------------------------------------------------------
    val shadeTop = h * 0.12f
    val shadeBottom = h * 0.34f
    val shadeTopHalf = w * 0.115f
    val shadeBottomHalf = w * 0.215f
    val shade = Path().apply {
        moveTo(poleX - shadeTopHalf, shadeTop)
        lineTo(poleX + shadeTopHalf, shadeTop)
        lineTo(poleX + shadeBottomHalf, shadeBottom)
        lineTo(poleX - shadeBottomHalf, shadeBottom)
        close()
    }
    drawPath(
        shade,
        brush = Brush.verticalGradient(
            colors = listOf(
                lerp(palette.ink, Color.Black, 0.15f).copy(alpha = 0.92f),
                warm.copy(alpha = 0.88f)
            ),
            startY = shadeTop,
            endY = shadeBottom
        )
    )
    // The inside of the shade, seen along its lower edge: a warm crescent that makes the shade a
    // vessel rather than a silhouette.
    drawOval(
        color = warm.copy(alpha = 0.55f + 0.30f * glow),
        topLeft = Offset(poleX - shadeBottomHalf, shadeBottom - h * 0.020f),
        size = Size(shadeBottomHalf * 2f, h * 0.030f)
    )
    // Rim highlight across the top.
    drawLine(
        color = metalLit.copy(alpha = 0.75f),
        start = Offset(poleX - shadeTopHalf, shadeTop),
        end = Offset(poleX + shadeTopHalf, shadeTop),
        strokeWidth = w * 0.010f
    )

    // ---- the bulb, and the light it throws --------------------------------------------------
    val bulb = Offset(poleX, shadeBottom - h * 0.004f)
    val bulbRadius = w * (0.055f + 0.012f * glow)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.92f * glowStrength),
                warm.copy(alpha = 0.55f * glowStrength),
                Color.Transparent
            ),
            center = bulb,
            radius = bulbRadius * 2.6f
        ),
        radius = bulbRadius * 2.6f,
        center = bulb
    )
    drawCircle(Color(0xFFFFF4DC), radius = bulbRadius * 0.55f, center = bulb)
    // A cone of light falling from the shade onto the pool: two soft triangles, the nearer one
    // brighter, so the light has direction.
    val cone = Path().apply {
        moveTo(poleX - shadeBottomHalf, shadeBottom)
        lineTo(poleX + shadeBottomHalf, shadeBottom)
        lineTo(poolCentre.x + poolRadiusX * 0.85f, floorY)
        lineTo(poolCentre.x - poolRadiusX * 0.85f, floorY)
        close()
    }
    drawPath(
        cone,
        brush = Brush.verticalGradient(
            colors = listOf(
                warm.copy(alpha = 0.16f * glowStrength),
                warm.copy(alpha = 0.04f * glowStrength)
            ),
            startY = shadeBottom,
            endY = floorY
        )
    )
    // A single thin filament line, drifting with the glow: the smallest honest sign of life.
    val drift = sin(glow * Math.PI).toFloat()
    drawLine(
        color = Color.White.copy(alpha = 0.55f),
        start = Offset(bulb.x - w * 0.02f, bulb.y - h * 0.012f * drift - h * 0.004f),
        end = Offset(bulb.x + w * 0.02f, bulb.y - h * 0.012f * drift - h * 0.004f),
        strokeWidth = w * 0.008f
    )
}
