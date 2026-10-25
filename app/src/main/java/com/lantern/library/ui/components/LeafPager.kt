package com.lantern.library.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A page that actually turns.
 *
 * The revealed page lies underneath. The leaf hinges on the spine (the left edge) and
 * swings across it, flipping past edge-on so you see the back of the paper mirrored
 * through it, exactly like a thin page held up to the light. Dragging drives the angle and
 * letting go either completes the turn or settles the leaf back down.
 *
 * This replaces a plain sliding pager, which is why the motion reads as a page and not a
 * carousel.
 */
@Composable
fun LeafPager(
    pageCount: Int,
    page: Int,
    onPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pageContent: @Composable (Int) -> Unit
) {
    if (pageCount <= 0) return

    val scope = rememberCoroutineScope()
    val turn = remember { Animatable(0f) }
    var widthPx by remember { mutableStateOf(0f) }
    val safePage by rememberUpdatedState(page.coerceIn(0, pageCount - 1))
    val safeOnPage by rememberUpdatedState(onPage)

    // A page changed from outside (chapter jump, restore, bookmark) resets the leaf.
    LaunchedEffect(page) { if (turn.value != 0f) turn.snapTo(0f) }

    suspend fun settle() {
        val v = turn.value
        val forward = v >= 0f
        val target = if (forward) safePage + 1 else safePage - 1
        val canCommit = target in 0 until pageCount
        if (canCommit && abs(v) > 0.30f) {
            turn.animateTo(
                if (forward) 1f else -1f,
                tween(durationMillis = 300, easing = FastOutSlowInEasing)
            )
            safeOnPage(target)
            turn.snapTo(0f)
        } else {
            turn.animateTo(0f, tween(durationMillis = 240, easing = FastOutSlowInEasing))
        }
    }

    val dragMod = if (!enabled) Modifier else Modifier.pointerInput(enabled, pageCount) {
        detectHorizontalDragGestures(
            onDragEnd = { scope.launch { settle() } },
            onDragCancel = { scope.launch { turn.animateTo(0f, tween(180)) } },
            onHorizontalDrag = { change, dragAmount ->
                if (!turn.isRunning) {
                    change.consume()
                    val w = widthPx
                    if (w > 1f) {
                        val delta = -dragAmount / w
                        var next = (turn.value + delta).coerceIn(-1f, 1f)
                        // no leaf beyond the first or last page
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
        val leafPage = if (forward) safePage else safePage - 1
        val basePage = when {
            atRest -> safePage
            forward -> (safePage + 1).coerceAtMost(pageCount - 1)
            else -> safePage
        }

        // The page being read from — the one revealed under the leaf.
        Box(Modifier.fillMaxSize()) { pageContent(basePage) }

        if (!atRest) {
            // Gutter shadow on the revealed page while the leaf is lifted.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to Color.Black.copy(alpha = 0.26f * abs(t)),
                            0.30f to Color.Black.copy(alpha = 0.10f * abs(t)),
                            0.65f to Color.Transparent
                        )
                    )
            )
        }

        if (!atRest && leafPage in 0 until pageCount) {
            // 0 degrees = lying flat on the right, -90 = standing on the spine,
            // -180 = landed on the left with its back to us.
            val angle = if (forward) -180f * t else -180f * (1f + t)
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        rotationY = angle
                        cameraDistance = 16f * density
                    }
            ) {
                Box(Modifier.fillMaxSize()) { pageContent(leafPage) }
                // paper shading: darker into the fold, a bright edge on the lifting side
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                0f to Color(0x4A120B06),
                                0.18f to Color(0x14120B06),
                                0.72f to Color.Transparent,
                                0.94f to Color(0x24FFFFFF),
                                1f to Color(0x4D2A1B10)
                            )
                        )
                )
                // the back of the sheet is a touch warmer and more transparent than the front
                val backness = ((abs(angle) - 88f) / 92f).coerceIn(0f, 1f)
                if (backness > 0f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color(0xFFF5EAD5).copy(alpha = 0.55f * backness),
                                        Color(0xFFE7D8BC).copy(alpha = 0.62f * backness)
                                    )
                                )
                            )
                    )
                }
            }
        }
    }
}
