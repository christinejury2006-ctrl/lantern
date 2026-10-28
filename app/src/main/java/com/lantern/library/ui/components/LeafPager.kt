package com.lantern.library.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.graphics.GenericShape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * A page that curls the way paper does.
 *
 * ## What this is modelling
 *
 * Every reader that turns pages properly models the curl the same way: as a **cylinder** rolling
 * across the sheet. The page is cut into three regions by two lines — the curl axis (the crease
 * where the paper leaves the flat page) and the far side of the roll:
 *
 * 1. ahead of the crease, outside the roll — the sheet, still lying flat, front up;
 * 2. inside the roll — the sheet curving away around the cylinder: foreshortened, shaded at the
 *    crease and lit along the crest;
 * 3. behind the crease — the sheet has gone over and lies face down, so what you see there is the
 *    *back* of the same sheet: the front mirrored, and a shade darker.
 *
 * That is the model in Harism's `android-pagecurl` (a ~60-vertex mesh in OpenGL), in Apple's
 * iBooks curl (a private CoreAnimation mesh transform), in the DirectX `page_curl` sample and in
 * Andrew Hung's shader breakdown — a cylinder with a radius and a crease, and per-fragment mapping
 * of those three regions. The physics is the same everywhere; only the renderer differs.
 *
 * ## Why this is not that, and what it is instead
 *
 * Those implementations deform a *texture*: they sample the page bitmap at a UV the shader
 * computes. Compose has no such hook — a composable can be translated, scaled, rotated and
 * clipped, and that is all; there is no mesh, and `androidx.compose.ui.graphics` exposes no way to
 * warp what a child drew. (This is the same wall that makes every SwiftUI "page curl" tutorial end
 * in a `rotation3DEffect` fold or a `UIViewController` wrapper.)
 *
 * So this builds the three regions out of the tools that do exist:
 *
 * - the flat part of the sheet: the page, clipped to the region left of the crease;
 * - the back of the sheet: a second copy of the page, mirrored about the crease and clipped to the
 *   part that has gone over — which is why its text is reversed, exactly like the back of a real
 *   page held up to the light;
 * - the roll: a strip of paper between the crease and the roll's outer edge, filled with a
 *   cross-section gradient (dark at the crease, lit at the crest), with a specular hairline and
 *   cast shadows on the page beneath and on the sheet beside it.
 *
 * Both edges are curves and the strip tapers to nothing at each end of the turn. A straight-edged
 * rectangle that rotates is exactly what this replaces.
 *
 * ## Why the layers are unconditional
 *
 * Every layer is composed on every frame, at rest included; visibility comes from the geometry
 * collapsing, not from an `if`. The previous version composed the turning leaf only while it was
 * turning, which added and removed a subtree from the slot table mid-gesture and threw
 * `IndexOutOfBoundsException` out of `ComposerImpl.exitGroup` when a drag began during a
 * recomposition. A stable slot shape makes that impossible rather than unlikely.
 *
 * ## Direction
 *
 * One code path serves both directions. [curl] is 0 when the sheet lies flat over the whole page
 * and 1 when it has gone entirely: a forward turn runs it 0 → 1 with the current page as the
 * sheet, and a backward turn runs the *same* animation 1 → 0 with the previous page as the sheet,
 * which is what the finger sees when it drags back.
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
    var widthPx by remember { mutableStateOf(0f) }
    val safeCount = pageCount.coerceAtLeast(1)
    val safePage = rememberUpdatedState(page.coerceIn(0, safeCount - 1)).value
    val safeOnPage = rememberUpdatedState(onPage).value

    // A page changed from outside (chapter jump, restore) resets the sheet.
    LaunchedEffect(page) { if (turn.value != 0f) turn.snapTo(0f) }

    val settleEasing = CubicBezierEasing(0.22f, 0.68f, 0.28f, 1f)

    suspend fun settle() {
        val v = turn.value
        val forward = v >= 0f
        val target = if (forward) safePage + 1 else safePage - 1
        val canCommit = target in 0 until safeCount
        if (canCommit && abs(v) > 0.22f) {
            turn.animateTo(if (forward) 1f else -1f, tween(durationMillis = 380, easing = settleEasing))
            safeOnPage(target)
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
                    val w = widthPx
                    if (w > 1f) {
                        var next = (turn.value + -dragAmount / w).coerceIn(-1f, 1f)
                        // There is no sheet past the first or last page.
                        if (next > 0f && safePage + 1 >= safeCount) next = 0f
                        if (next < 0f && safePage - 1 < 0) next = 0f
                        scope.launch { turn.snapTo(next) }
                    }
                }
            }
        )
    }

    val t = turn.value
    val forward = t >= 0f
    val dragged = abs(t).coerceIn(0f, 1f)
    // Forward: the current page is the sheet, and the turn runs 0 → 1.
    // Backward: the previous page is the sheet and it un-turns, so the same run goes 1 → 0.
    val curl = if (forward) dragged else 1f - dragged
    val sheetPage = (if (forward) safePage else safePage - 1).coerceIn(0, safeCount - 1)
    val basePage = (if (forward) safePage + 1 else safePage).coerceIn(0, safeCount - 1)
    val drawing = curl > 0.0005f && curl < 0.9995f

    // The sheet's shading is derived from its own colour, not painted in cream: a night page is
    // deep ink-blue, and a cream highlight on its edge would read as a strip of another material.
    val lit = lerp(paper, Color.White, 0.62f)
    val fibre = lerp(paper, Color.Black, 0.34f)
    val backTint = lerp(paper, Color.Black, 0.16f)

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { widthPx = it.width.toFloat() }
            .clipToBounds()
            .then(dragMod)
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val w = with(density) { maxWidth.toPx() }
            val h = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)

            // All curvature is multiplied by sin(π · curl), so the roll has neither width nor bend
            // at either end of the turn: it grows out of the page and shrinks back into it, which
            // is what keeps the first and last frame indistinguishable from a still page.
            val bend = sin(PI * curl).toFloat()
            val c = w * (1f - curl)                        // centre of the roll
            val rollW = 0.085f * w * bend                  // width of the strip at the crest
            val creaseBow = -0.030f * w * bend             // the crease bows back from the roll
            val outerBow = 0.055f * w * bend               // the outer edge bows out past it
            val creaseBase = c - rollW / 2f
            val outerBase = c + rollW / 2f
            // Where the folded-back sheet ends. Once the roll has passed the middle of the page this
            // is off the left edge, and the region is clamped to the screen.
            val tipBase = 2f * c - w

            fun creaseAt(y: Float) = creaseBase + creaseBow * sin(PI * y / h).toFloat()
            fun outerAt(y: Float) = outerBase + outerBow * sin(PI * y / h).toFloat()

            val sheetShape = GenericShape { _, _ -> leftOf(::creaseAt, w, h) }
            val backPath = between({ tipBase }, ::creaseAt, w, h)
            val backShape = GenericShape { _, _ -> backPath }

            // 1. The page underneath — the one being revealed (forward) or kept (backward).
            Box(Modifier.fillMaxSize()) { pageContent(basePage, false) }

            // 2. The sheet, flat, front up: everything left of the crease.
            Box(Modifier.fillMaxSize().clip(sheetShape)) { pageContent(sheetPage, true) }

            // 3. The shadow the folded-back sheet drops on the page it is covering. Drawn between
            //    the two so it lands on the page and under the sheet.
            Canvas(Modifier.fillMaxSize()) {
                if (!drawing || w <= 0f) return@Canvas
                if (tipBase > 1f) {
                    drawRect(
                        Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.22f * bend)
                            ),
                            startX = tipBase - 0.16f * w,
                            endX = tipBase
                        )
                    )
                }
            }

            // 4. The back of the sheet: the same page, mirrored about the crease, clipped to the
            //    part that has gone over. Reversed type is the honest thing to draw here — it is
            //    what the underside of a printed page looks like.
            Box(Modifier.fillMaxSize().clip(backShape)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            transformOrigin = TransformOrigin(0f, 0f)
                            scaleX = -1f
                            translationX = 2f * c
                        }
                ) { pageContent(sheetPage, false) }
            }

            // 5. The roll itself, its shadows, and the crease.
            Canvas(Modifier.fillMaxSize()) {
                if (!drawing || w <= 0f) return@Canvas
                val roll = between(::creaseAt, ::outerAt, w, h)

                // The back of the sheet is paper seen from behind: a shade darker.
                clipPath(backPath) { drawRect(backTint.copy(alpha = 0.55f)) }

                // The page revealed under the curl sits in the roll's shadow.
                drawPath(
                    rightOf(::outerAt, w, h),
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0f to Color.Black.copy(alpha = 0.30f * bend),
                            0.45f to Color.Black.copy(alpha = 0.10f * bend),
                            1f to Color.Transparent
                        ),
                        startX = outerBase,
                        endX = outerBase + 0.24f * w
                    )
                )

                // The strip: paper, turning away into shadow at the crease, lit at the crest.
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
                        startX = creaseBase,
                        endX = outerBase
                    )
                )
                // Fibres, so the strip is a piece of the sheet and not a block of colour.
                var y = 12f
                while (y < h) {
                    drawLine(
                        fibre,
                        Offset(creaseAt(y), y),
                        Offset(outerAt(y), y - 0.6f),
                        strokeWidth = 1f,
                        alpha = 0.05f
                    )
                    y += 15f
                }
                // The crest: a specular run along the outer edge, and the shadow the crease casts.
                drawPath(
                    edgePath(::outerAt, w, h),
                    lit.copy(alpha = 0.70f * bend),
                    style = Stroke(width = 2.5.dp.toPx())
                )
                drawPath(
                    edgePath(::creaseAt, w, h),
                    Color.Black.copy(alpha = 0.18f * bend),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }
    }
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
