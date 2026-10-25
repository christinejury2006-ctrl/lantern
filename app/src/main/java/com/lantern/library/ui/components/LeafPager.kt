package com.lantern.library.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A page that turns the way paper does.
 *
 * The leaf is pinned along the spine (the left edge) and rotated in real perspective, so at
 * ninety degrees it stands edge-on and then lands face-down on the left — you see the
 * mirrored back of the sheet for the second half of the turn, exactly like a thin page held
 * up to the light.
 *
 * What makes it read as paper rather than a sliding rectangle: a near camera so the
 * foreshortening is visible, a drop shadow that sweeps across the revealed page under the
 * leading edge, a curled highlight just behind that edge, the page block's thickness drawn
 * as a couple of faint leaves behind it, and the shadow that falls into the gutter.
 */
@Composable
fun LeafPager(
    pageCount: Int,
    page: Int,
    onPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Paper tone for the leaves, so the sheet matches the reader's page. */
    paper: Color = Color(0xFFF7EFDF),
    pageContent: @Composable (Int) -> Unit
) {
    if (pageCount <= 0) return

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val turn = remember { Animatable(0f) }
    var widthPx by remember { mutableStateOf(0f) }
    val safePage by rememberUpdatedState(page.coerceIn(0, pageCount - 1))
    val safeOnPage by rememberUpdatedState(onPage)
    // Near camera: Compose's default is 8 * density. Halving it makes the foreshortening
    // visible; too far and the rotation reads as a horizontal squash.
    val camera = 4f * density.density

    // A page changed from outside (chapter jump, restore) resets the leaf.
    LaunchedEffect(page) { if (turn.value != 0f) turn.snapTo(0f) }

    val settleEasing = CubicBezierEasing(0.22f, 0.68f, 0.28f, 1f)

    suspend fun settle() {
        val v = turn.value
        val forward = v >= 0f
        val target = if (forward) safePage + 1 else safePage - 1
        val canCommit = target in 0 until pageCount
        if (canCommit && abs(v) > 0.22f) {
            turn.animateTo(
                if (forward) 1f else -1f,
                tween(durationMillis = 330, easing = settleEasing)
            )
            safeOnPage(target)
            turn.snapTo(0f)
        } else {
            turn.animateTo(0f, tween(durationMillis = 260, easing = settleEasing))
        }
    }

    val dragMod = if (!enabled) Modifier else Modifier.pointerInput(enabled, pageCount) {
        detectHorizontalDragGestures(
            onDragEnd = { scope.launch { settle() } },
            onDragCancel = { scope.launch { turn.animateTo(0f, tween(200)) } },
            onHorizontalDrag = { change, dragAmount ->
                if (!turn.isRunning) {
                    change.consume()
                    val w = widthPx
                    if (w > 1f) {
                        val delta = -dragAmount / w
                        var next = (turn.value + delta).coerceIn(-1f, 1f)
                        // there is no leaf past the first or last page
                        if (next > 0f && safePage + 1 >= pageCount) next = 0f
                        if (next < 0f && safePage - 1 < 0) next = 0f
                        scope.launch { turn.snapTo(next) }
                    }
                }
            }
        )
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { widthPx = it.width.toFloat() }
            .then(dragMod)
    ) {
        val t = turn.value
        val atRest = abs(t) < 0.004f
        val forward = t >= 0f
        val progress = abs(t)
        val leafPage = if (forward) safePage else safePage - 1
        val basePage = when {
            atRest -> safePage
            forward -> (safePage + 1).coerceAtMost(pageCount - 1)
            else -> safePage
        }

        // the page underneath: stays put, catches the leaf's shadow
        Box(Modifier.fillMaxSize()) { pageContent(basePage) }

        if (!atRest) {
            val w = widthPx.coerceAtLeast(1f)
            // The leaf's leading edge sweeps from the right edge to the spine.
            val edgeX = (1f - progress) * w
            Canvas(Modifier.fillMaxSize()) {
                // shadow cast by the rising leaf, following its edge
                drawRect(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.55f to Color.Black.copy(alpha = 0.10f * progress),
                            1f to Color.Black.copy(alpha = 0.34f * progress)
                        ),
                        startX = edgeX - w * 0.45f,
                        endX = edgeX + w * 0.02f
                    )
                )
                // the gutter where the leaf is still attached
                drawRect(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.30f * progress),
                        0.12f to Color.Transparent
                    )
                )
            }
        }

        if (!atRest && leafPage in 0 until pageCount) {
            // 0 = flat on the right, -90 = standing on the spine, -180 = landed on the left
            val angle = if (forward) -180f * t else -180f * (1f + t)

            // the thickness of the page block: two faint leaves trailing behind
            repeat(2) { i ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            transformOrigin = TransformOrigin(0f, 0.5f)
                            rotationY = angle + if (forward) (i + 1) * 3.5f else -(i + 1) * 3.5f
                            cameraDistance = camera
                            alpha = 0.18f - i * 0.07f
                        }
                        .background(paper.copy(alpha = 0.5f))
                )
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        rotationY = angle
                        cameraDistance = camera
                    }
                .background(paper.copy(alpha = 0.97f))
            ) {
                Box(Modifier.fillMaxSize()) { pageContent(leafPage) }

                val backness = ((abs(angle) - 88f) / 92f).coerceIn(0f, 1f)
                Canvas(Modifier.fillMaxSize()) {
                    // paper fibres, very faint, so the sheet is not a flat block of colour
                    var y = 24f
                    while (y < size.height) {
                        drawLine(
                            Color(0xFF8A7A5E),
                            Offset(0f, y),
                            Offset(size.width, y - 1.5f),
                            strokeWidth = 1f,
                            alpha = 0.05f
                        )
                        y += 17f
                    }
                    // shading into the fold
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.26f + 0.20f * backness),
                            0.10f to Color.Black.copy(alpha = 0.08f),
                            0.55f to Color.Transparent
                        )
                    )
                    // the curl: a lit band just behind the leading edge, with a dark crease
                    val band = size.width * 0.05f
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.Transparent,
                            0.55f to Color(0xFFFFFDF6).copy(alpha = 0.55f),
                            1f to Color(0xFFD9C8A6).copy(alpha = 0.35f)
                        ),
                        topLeft = Offset(size.width - band, 0f),
                        size = Size(band, size.height)
                    )
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.16f),
                            1f to Color.Transparent
                        ),
                        topLeft = Offset(size.width - band * 1.35f, 0f),
                        size = Size(band * 0.3f, size.height)
                    )
                    // the back of the sheet is warmer and slightly see-through
                    if (backness > 0f) {
                        drawRect(paper.copy(alpha = 0.42f * backness))
                        drawRect(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xFF120C06).copy(alpha = 0.10f * backness),
                                    Color.Transparent,
                                    Color(0xFF120C06).copy(alpha = 0.14f * backness)
                                )
                            )
                        )
                    }
                }
            }
        }
    }
}
