package com.lantern.library.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.lantern.library.reader.LeafWindow
import com.lantern.library.reader.LeafWindowMath
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * A page that curls the way paper does.
 *
 * ## What it draws
 *
 * The turn is modelled the way every real reader models it — Harism's `android-pagecurl`, the
 * iBooks mesh transform, the DirectX `page_curl` sample: a cylinder rolling across the sheet, which
 * divides it into three regions.
 *
 * 1. left of the crease: the sheet, still flat, front up;
 * 2. between the crease and the roll's far edge: the sheet curving away, shaded at the crease and
 *    lit along the crest;
 * 3. past the roll: the same sheet, face down — the *back* of the page, which is the front
 *    mirrored, and darker.
 *
 * Compose has no mesh, so those regions are built from what exists: clipped copies of the page and
 * a drawn strip of paper between two curves. The strip tapers to nothing at both ends of the turn,
 * so no straight edge ever appears.
 *
 * ## Why the structure looks like this
 *
 * The reader crashed with `IndexOutOfBoundsException: Index -1 out of bounds for length 0` inside
 * `ComposerImpl.exitGroup`. That exception means the composition emitted an unbalanced number of
 * groups — the slot table was asked to close a group that was never opened. Three properties of
 * this file are the answer to that, and they are not stylistic choices:
 *
 * 1. **A constant number of composition slots, in a constant order, in every state.** Four boxes
 *    and a canvas are composed whether the reader is at rest, mid-drag, mid-fling, on page one of
 *    a one-page book, or looking at an empty book. Visibility comes from `alpha` and from clip
 *    geometry, never from composing a different set of children. Nothing can be added to or
 *    removed from this subtree while a gesture is in flight.
 *
 * 2. **Every index is decided before composition, by a pure function.** [LeafWindowMath] clamps the
 *    page count, the page and the turn, and returns the two pages that exist. A page list that
 *    shrinks under a drag (font size, rotation, a chapter jump) can therefore never produce an
 *    index past the end, which is what used to feed `pages.getOrNull(i)` a null and collapse the
 *    page subtree mid-turn.
 *
 * 3. **No subcomposition.** The previous version measured itself with `BoxWithConstraints`, which
 *    is a `SubcomposeLayout`: its children live in a second composition that is composed during
 *    layout, while the gesture is changing state. The size now arrives from `onSizeChanged` and is
 *    held in plain state, so this subtree is composed in one composition only.
 *
 * The page content is composed twice — once as the sheet's front, once mirrored for its back. That
 * is inherent: the back of a page is a different picture of the same page, and both are on screen
 * at once. The two calls sit in different parents, so they never collide as keys.
 */
@Composable
fun LeafPager(
    pageCount: Int,
    page: Int,
    onPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Paper tone for the sheet, so it matches the reader's page. */
    paper: Color = Color(0xFFF7EFDF),
    /**
     * Draws page [index].
     *
     * [interactive] is false for the copies that exist only to make the curl: the page underneath
     * and the mirrored back of the sheet. Those overlap the real page, and a tap handler on them
     * would fire alongside the one on the page the reader is actually looking at.
     */
    pageContent: @Composable (index: Int, interactive: Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    val turn = remember { Animatable(0f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val safeCount = if (pageCount > 0) pageCount else 1
    // Held as state, not as values: the drag block below is remembered across recompositions, so a
    // captured `page` would be the page the reader was on when the gesture began.
    val pageState = rememberUpdatedState(LeafWindowMath.clampPage(page, safeCount))
    val onPageState = rememberUpdatedState(onPage)
    val safePage = pageState.value

    // A page changed from outside (chapter jump, restore, clamping after a re-pagination) resets
    // the sheet. `snapTo` never suspends for a frame, so the reader cannot see an intermediate
    // state where the old sheet is still lifted over the new page.
    LaunchedEffect(safePage, safeCount) {
        if (turn.value != 0f) turn.snapTo(0f)
    }

    val settleEasing = CubicBezierEasing(0.22f, 0.68f, 0.28f, 1f)

    suspend fun settle() {
        val v = turn.value
        val forward = v >= 0f
        val from = pageState.value
        val target = LeafWindowMath.targetAfterTurn(safeCount, from, forward)
        if (target != null && abs(v) > 0.22f) {
            turn.animateTo(if (forward) 1f else -1f, tween(durationMillis = 380, easing = settleEasing))
            onPageState.value(target)
            turn.snapTo(0f)
        } else {
            turn.animateTo(0f, tween(durationMillis = 280, easing = settleEasing))
        }
    }

    val dragMod = if (!enabled) Modifier else Modifier.pointerInput(enabled, safeCount) {
        detectHorizontalDragGestures(
            onDragEnd = { scope.launch { settle() } },
            onDragCancel = { scope.launch { turn.animateTo(0f, tween(220)) } },
            onHorizontalDrag = { change, dragAmount ->
                if (!turn.isRunning) {
                    change.consume()
                    val w = size.width.toFloat()
                    if (w > 1f) {
                        var next = (turn.value + -dragAmount / w).coerceIn(-1f, 1f)
                        // A drag with nowhere to go is not a turn at all.
                        val current = pageState.value
                        if (next > 0f && LeafWindowMath.targetAfterTurn(safeCount, current, true) == null) next = 0f
                        if (next < 0f && LeafWindowMath.targetAfterTurn(safeCount, current, false) == null) next = 0f
                        scope.launch { turn.snapTo(next) }
                    }
                }
            }
        )
    }

    val window = LeafWindowMath.window(safeCount, safePage, turn.value)
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    val measured = w > 0f && h > 0f

    // The sheet's shading is derived from its own colour, not painted in cream: a night page is
    // deep ink-blue, and a cream highlight on its edge would read as a strip of another material.
    val lit = lerp(paper, Color.White, 0.62f)
    val fibre = lerp(paper, Color.Black, 0.34f)
    val backTint = lerp(paper, Color.Black, 0.16f)

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .clipToBounds()
            .then(dragMod)
    ) {
        // ---- Slot 1: the page underneath (revealed by a forward turn, kept by a backward one).
        Box(Modifier.fillMaxSize()) { pageContent(window.basePage, false) }

        // ---- Slot 2: the sheet itself, flat, front up, everything left of the crease.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (window.drawing) 1f else 0f }
                .then(sheetClip(window, w, h, measured))
        ) { pageContent(window.sheetPage, true) }

        // ---- Slot 3: the back of the same sheet, mirrored about the crease and lit from behind.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (window.drawing) 1f else 0f }
                .then(backClip(window, w, h, measured))
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = -1f
                        // Mirroring happens about the crease, which sits at x = c.
                        translationX = 2f * w * (1f - window.curl)
                    }
            ) { pageContent(window.sheetPage, false) }
        }

        // ---- Slot 4: the roll, its shadows and its specular crest. Always composed.
        Canvas(Modifier.fillMaxSize()) {
            if (!window.drawing || !measured) return@Canvas
            drawRoll(window, w, paper, lit, fibre, backTint)
        }
    }
}

/**
 * Clip to everything left of the (curved) crease: the flat part of the sheet.
 *
 * At rest the window is not drawing, so the sheet slot is transparent and this is never seen; when
 * the turn begins, the crease starts at the right edge and moves left.
 */
private fun sheetClip(window: LeafWindow, w: Float, h: Float, measured: Boolean): Modifier {
    if (!measured || !window.drawing) return Modifier
    val geometry = CurlGeometry(w, h, window.curl)
    return Modifier.clip(PathShape { leftOf(geometry.creaseAt, w, h) })
}

/** Clip to the part of the page that has gone over, which is where its back is visible. */
private fun backClip(window: LeafWindow, w: Float, h: Float, measured: Boolean): Modifier {
    if (!measured || !window.drawing) return Modifier
    val geometry = CurlGeometry(w, h, window.curl)
    val tip = 2f * geometry.centre - w
    return Modifier.clip(PathShape { between({ tip }, geometry.creaseAt, w, h) })
}

/**
 * The two curves and the strip between them, for one value of the turn.
 *
 * All curvature is multiplied by `sin(π · curl)`, so the roll has neither width nor bend at either
 * end of the turn: it grows out of the page and shrinks back into it, which is what keeps the first
 * and last frame of a turn indistinguishable from a still page.
 */
private class CurlGeometry(w: Float, val h: Float, curl: Float) {
    val bend = sin(PI * curl).toFloat()
    val centre = w * (1f - curl)
    private val rollWidth = 0.085f * w * bend
    private val creaseBow = -0.030f * w * bend
    private val outerBow = 0.055f * w * bend
    val creaseBase = centre - rollWidth / 2f
    val outerBase = centre + rollWidth / 2f

    fun creaseAt(y: Float): Float = creaseBase + creaseBow * sin(PI * y / h).toFloat()
    fun outerAt(y: Float): Float = outerBase + outerBow * sin(PI * y / h).toFloat()
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoll(
    window: LeafWindow,
    w: Float,
    paper: Color,
    lit: Color,
    fibre: Color,
    backTint: Color
) {
    val h = size.height
    if (h <= 0f) return
    val geometry = CurlGeometry(w, h, window.curl)
    val bend = geometry.bend
    val crease = geometry::creaseAt
    val outer = geometry::outerAt
    val tip = 2f * geometry.centre - w

    // The back of the sheet is paper seen from behind: a shade darker.
    clipPath(between({ tip }, crease, w, h)) { drawRect(backTint.copy(alpha = 0.55f)) }

    // The page revealed under the curl sits in the roll's shadow.
    drawPath(
        rightOf(outer, w, h),
        Brush.horizontalGradient(
            colorStops = arrayOf(
                0f to Color.Black.copy(alpha = 0.30f * bend),
                0.45f to Color.Black.copy(alpha = 0.10f * bend),
                1f to Color.Transparent
            ),
            startX = geometry.outerBase,
            endX = geometry.outerBase + 0.24f * w
        )
    )

    // The strip: paper, turning away into shadow at the crease, lit at the crest.
    val roll = between(crease, outer, w, h)
    drawPath(roll, paper)
    drawPath(
        roll,
        Brush.horizontalGradient(
            colorStops = arrayOf(
                0f to Color.Black.copy(alpha = 0.30f * bend),
                0.30f to Color.Black.copy(alpha = 0.08f * bend),
                0.62f to Color.Transparent,
                0.86f to lit.copy(alpha = 0.85f * bend),
                1f to Color.Black.copy(alpha = 0.16f * bend)
            ),
            startX = geometry.creaseBase,
            endX = geometry.outerBase
        )
    )
    // Fibres, so the strip is a piece of the sheet and not a block of colour.
    var y = 12f
    while (y < h) {
        drawLine(fibre, Offset(crease(y), y), Offset(outer(y), y - 0.6f), strokeWidth = 1f, alpha = 0.05f)
        y += 15f
    }
    // The crest: a specular run along the outer edge, and the shadow the crease casts.
    drawPath(edgePath(outer, w, h), lit.copy(alpha = 0.70f * bend), style = Stroke(width = 2.5.dp.toPx()))
    drawPath(edgePath(crease, w, h), Color.Black.copy(alpha = 0.18f * bend), style = Stroke(width = 1.5.dp.toPx()))
}

/** A clip region built from the curl geometry. */
private class PathShape(private val build: () -> Path) : Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(build())
}

/** Segments the curved edges are sampled into. Cheap at 24, and smooth at any page size. */
private const val EDGE_STEPS = 24

/** Region from the left edge of the screen up to [edge]. */
private fun leftOf(edge: (Float) -> Float, w: Float, h: Float): Path {
    val path = Path()
    path.moveTo(0f, 0f)
    for (i in 0..EDGE_STEPS) {
        val y = h * i / EDGE_STEPS
        path.lineTo(edge(y).coerceIn(0f, w), y)
    }
    path.lineTo(0f, h)
    path.close()
    return path
}

/** Region between [from] and [to], both running top to bottom. */
private fun between(from: (Float) -> Float, to: (Float) -> Float, w: Float, h: Float): Path {
    val path = Path()
    path.moveTo(from(0f).coerceIn(0f, w), 0f)
    for (i in 0..EDGE_STEPS) {
        val y = h * i / EDGE_STEPS
        path.lineTo(from(y).coerceIn(0f, w), y)
    }
    for (i in EDGE_STEPS downTo 0) {
        val y = h * i / EDGE_STEPS
        path.lineTo(to(y).coerceIn(0f, w), y)
    }
    path.close()
    return path
}

/** Region from [edge] to the right edge of the screen. */
private fun rightOf(edge: (Float) -> Float, w: Float, h: Float): Path {
    val path = Path()
    path.moveTo(edge(0f).coerceIn(0f, w), 0f)
    for (i in 0..EDGE_STEPS) {
        val y = h * i / EDGE_STEPS
        path.lineTo(edge(y).coerceIn(0f, w), y)
    }
    path.lineTo(w, h)
    path.lineTo(w, 0f)
    path.close()
    return path
}

/** Just [edge] itself, for a stroke along it. */
private fun edgePath(edge: (Float) -> Float, w: Float, h: Float): Path {
    val path = Path()
    path.moveTo(edge(0f).coerceIn(0f, w), 0f)
    for (i in 1..EDGE_STEPS) {
        val y = h * i / EDGE_STEPS
        path.lineTo(edge(y).coerceIn(0f, w), y)
    }
    return path
}
