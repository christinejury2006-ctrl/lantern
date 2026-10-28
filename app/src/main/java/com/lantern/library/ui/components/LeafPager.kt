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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
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
 * ninety degrees it stands edge-on and then lands face-down on the left — you see the mirrored
 * back of the sheet for the second half of the turn, like a thin page held up to the light.
 *
 * ## Why this is written the way it is
 *
 * The previous version composed the turning leaf only while it was turning — `if (!atRest) { … }`
 * around the leaf, the shadow and the trailing sheets. That is fine for what is drawn and fatal
 * for how Compose records it: the composition's slot table gains and loses a whole subtree as
 * the animation starts and stops, and when a drag began during a recomposition the runtime
 * unwound its group stack one entry too many and threw
 * `IndexOutOfBoundsException: Index -1 out of bounds for length 0` out of `ComposerImpl.exitGroup`.
 *
 * So every layer here is composed unconditionally, every frame, and visibility is expressed as
 * `alpha` instead of as branches. The slot table is the same shape at rest, mid-turn and after a
 * cancelled drag, which is the property that makes the crash impossible rather than unlikely.
 *
 * It is also clipped: an unclipped rotated sheet extends past its bounds and paints over the
 * neighbouring page, which is how a turn could look like two pages at once at different angles.
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
    // The sheet's shading is derived from the sheet's own colour, not painted in cream: a night
    // page is deep ink-blue, and a cream highlight along its edge reads as a strip of another
    // material.
    val fibre = lerp(paper, Color.Black, 0.34f)
    val litEdge = lerp(paper, Color.White, 0.55f)
    val shadedEdge = lerp(paper, Color.Black, 0.22f)
    val ink = lerp(paper, Color.Black, 0.85f)

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val turn = remember { Animatable(0f) }
    var widthPx by remember { mutableStateOf(0f) }
    val safeCount = pageCount.coerceAtLeast(1)
    val safePage = rememberUpdatedState(page.coerceIn(0, safeCount - 1)).value
    val safeOnPage = rememberUpdatedState(onPage).value
    // Near camera: Compose's default is 8 * density. Halving it makes the foreshortening
    // visible; much further and the rotation reads as a horizontal squash.
    val camera = 4f * density.density

    // A page changed from outside (chapter jump, restore) resets the leaf.
    LaunchedEffect(page) { if (turn.value != 0f) turn.snapTo(0f) }

    val settleEasing = CubicBezierEasing(0.22f, 0.68f, 0.28f, 1f)

    suspend fun settle() {
        val v = turn.value
        val forward = v >= 0f
        val target = if (forward) safePage + 1 else safePage - 1
        val canCommit = target in 0 until safeCount
        if (canCommit && abs(v) > 0.22f) {
            turn.animateTo(if (forward) 1f else -1f, tween(durationMillis = 330, easing = settleEasing))
            safeOnPage(target)
            turn.snapTo(0f)
        } else {
            turn.animateTo(0f, tween(durationMillis = 260, easing = settleEasing))
        }
    }

    val dragMod = if (!enabled) Modifier else Modifier.pointerInput(enabled, safeCount) {
        detectHorizontalDragGestures(
            onDragEnd = { scope.launch { settle() } },
            onDragCancel = { scope.launch { turn.animateTo(0f, tween(200)) } },
            onHorizontalDrag = { change, dragAmount ->
                if (!turn.isRunning) {
                    change.consume()
                    val w = widthPx
                    if (w > 1f) {
                        var next = (turn.value + -dragAmount / w).coerceIn(-1f, 1f)
                        // there is no leaf past the first or last page
                        if (next > 0f && safePage + 1 >= safeCount) next = 0f
                        if (next < 0f && safePage - 1 < 0) next = 0f
                        scope.launch { turn.snapTo(next) }
                    }
                }
            }
        )
    }

    val t = turn.value
    val progress = abs(t)
    val atRest = progress < 0.004f
    val forward = t >= 0f
    // Both pages are always valid: the leaf is the page being turned, the base is what is
    // underneath it. Clamped, because a chapter jump can move `page` while a turn is in flight.
    val leafPage = if (forward) safePage else safePage - 1
    val basePage = when {
        atRest -> safePage
        forward -> (safePage + 1)
        else -> safePage
    }.coerceIn(0, safeCount - 1)

    // 0 = flat on the right, -90 = standing on the spine, -180 = landed face-down on the left.
    val angle = if (forward) -180f * t else -180f * (1f + t)
    val backness = ((abs(angle) - 88f) / 92f).coerceIn(0f, 1f)

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { widthPx = it.width.toFloat() }
            .clipToBounds()
            .then(dragMod)
    ) {
        // The page underneath: stays put and takes the shadow the leaf casts.
        Box(Modifier.fillMaxSize()) { pageContent(basePage) }

        // The shadow sweeping under the leaf's leading edge, and the gutter it is hinged on.
        Canvas(Modifier.fillMaxSize()) {
            if (atRest || size.width <= 0f) return@Canvas
            val edgeX = (1f - progress) * size.width
            drawRect(
                Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.55f to Color.Black.copy(alpha = 0.10f * progress),
                        1f to Color.Black.copy(alpha = 0.30f * progress)
                    ),
                    startX = edgeX - size.width * 0.45f,
                    endX = edgeX + size.width * 0.02f
                )
            )
            drawRect(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.26f * progress),
                    0.12f to Color.Transparent
                )
            )
        }

        // The thickness of the page block: two faint sheets just behind the leaf. Composed
        // unconditionally; at rest they are transparent.
        repeat(2) { i ->
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = if (atRest) 0f else 0.18f - i * 0.07f
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        rotationY = angle + if (forward) (i + 1) * 3.5f else -(i + 1) * 3.5f
                        cameraDistance = camera
                    }
                    .background(paper)
            )
        }

        // The leaf itself.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = if (atRest) 0f else 1f
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    rotationY = angle
                    cameraDistance = camera
                }
                .background(paper)
        ) {
            Box(Modifier.fillMaxSize()) { pageContent(leafPage.coerceIn(0, safeCount - 1)) }
            Canvas(Modifier.fillMaxSize()) {
                // paper fibres, very faint, so the sheet is not a flat block of colour
                var y = 24f
                while (y < size.height) {
                    drawLine(fibre, Offset(0f, y), Offset(size.width, y - 1.5f), strokeWidth = 1f, alpha = 0.05f)
                    y += 17f
                }
                // shading into the fold
                drawRect(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.22f + 0.20f * backness),
                        0.10f to Color.Black.copy(alpha = 0.07f),
                        0.55f to Color.Transparent
                    )
                )
                // the curl: a lit band behind the leading edge, with a crease in front of it
                val band = size.width * 0.05f
                drawRect(
                    Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.55f to litEdge.copy(alpha = 0.50f),
                        1f to shadedEdge.copy(alpha = 0.32f)
                    ),
                    topLeft = Offset(size.width - band, 0f),
                    size = Size(band, size.height)
                )
                drawRect(
                    Brush.horizontalGradient(0f to ink.copy(alpha = 0.14f), 1f to Color.Transparent),
                    topLeft = Offset(size.width - band * 1.35f, 0f),
                    size = Size(band * 0.3f, size.height)
                )
                // the back of the sheet is the same paper, seen from behind and slightly darker
                if (backness > 0f && !atRest) {
                    drawRect(ink.copy(alpha = 0.18f * backness))
                }
            }
        }
    }
}
