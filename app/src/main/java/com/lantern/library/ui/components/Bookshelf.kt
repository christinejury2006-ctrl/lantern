package com.lantern.library.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.CoverCache
import com.lantern.library.data.Shelf3D
import com.lantern.library.data.ShelfMath
import com.lantern.library.ui.theme.Ink
import com.lantern.library.ui.theme.Playfair
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos

/**
 * One book on the shelf. The cover travels with it, so the spine is painted from the book's
 * own jacket instead of a colour picked at random.
 */
data class Spine(
    val id: String,
    val title: String,
    val author: String,
    val pages: Int = 240,
    val progress: Float = 0f,
    val coverRes: Int? = null,
    val coverUrl: String? = null,
    val coverFallbacks: List<String> = emptyList()
)

/**
 * The room around the bookcase: plaster wall, a lamp glow off to one side, and a vignette
 * that keeps the eye on the books.
 */
@Composable
fun ShelfRoom(
    dark: Boolean,
    modifier: Modifier = Modifier,
    wallAlpha: Float = 1f,
    content: @Composable BoxScope.() -> Unit
) {
    val a = if (wallAlpha.isFinite()) wallAlpha.coerceIn(0f, 1f) else 1f
    val top = (if (dark) Color(0xFF231810) else Color(0xFFF0E3D0)).copy(alpha = a)
    val bottom = (if (dark) Color(0xFF120C07) else Color(0xFFD3BB9A)).copy(alpha = a)
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom)))) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.radialGradient(
                    colors = listOf(
                        (if (dark) Color(0xFF7A5836) else Color(0xFFFFF8EA)).copy(alpha = 0.45f),
                        Color.Transparent
                    ),
                    center = androidx.compose.ui.geometry.Offset(size.width * 0.15f, -size.height * 0.08f),
                    radius = size.width
                )
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color(0x4D000000)),
                    center = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.4f),
                    radius = size.maxDimension * 0.8f
                )
            )
        }
        content()
    }
}

/**
 * A shelf of books, drawn as a real 3D scene.
 *
 * The case is a cylinder wrapping around the viewer. Books are boxes standing on it: each
 * shows its textured spine, the cream page block along its top edge, and — away from the
 * centre — a side. The boards are the same cylinder, so they bow, and books at the edges
 * grow and turn away exactly as they do in a photograph of a curved shelf.
 *
 * A row shorter than the screen is centred and never repeated; a longer row wraps around
 * seamlessly, so books come back only after you have seen the rest.
 */
@Composable
fun ShelfRow(
    spines: List<Spine>,
    dark: Boolean,
    shelfHeight: Dp,
    modifier: Modifier = Modifier,
    looping: Boolean = true,
    emptyLabel: String = "Nothing on this shelf yet",
    onOpen: (Spine) -> Unit,
    onLongPress: ((Spine) -> Unit)? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val covers = remember { mutableStateMapOf<String, Bitmap>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(spines) {
        for (spine in spines) {
            if (covers.containsKey(spine.id)) continue
            val res = spine.coverRes
            val bmp = when {
                res != null -> runCatching {
                    BitmapFactory.decodeResource(context.resources, res)
                }.getOrNull()
                !spine.coverUrl.isNullOrBlank() || spine.coverFallbacks.isNotEmpty() ->
                    CoverCache.load(spine.coverUrl, spine.coverFallbacks)
                else -> null
            }
            if (bmp != null && !bmp.isRecycled) covers[spine.id] = bmp
        }
    }

    val scroll = remember(spines) { Animatable(0f) }

    BoxWithConstraints(modifier.fillMaxWidth().height(shelfHeight)) {
        val wPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val hPx = with(density) { shelfHeight.toPx() }.coerceAtLeast(1f)

        val cam = remember(wPx, hPx) {
            val focal = wPx * 1.22f
            Shelf3D.Camera(
                focal = focal,
                centerX = wPx / 2f,
                centerY = hPx * 0.50f,
                radius = focal * 0.85f
            )
        }
        // scene units at the middle of the shelf are 1:1 with screen pixels
        val k = cam.radius / cam.focal
        val shelfY = (hPx * 0.655f - cam.centerY) * k
        val boardDepth = hPx * 0.085f * k
        val boardFace = hPx * 0.130f * k
        val caseDepth = hPx * 0.40f * k
        val bookDepth = hPx * 0.105f * k
        val gapWorld = wPx * 0.006f * k

        val widths = remember(spines, k, wPx) {
            FloatArray(spines.size) { i ->
                val sp = spines[i]
                wPx * (0.058f + (ShelfMath.spineWidthDp(sp.id, sp.pages) - 15) * 0.0045f) * k
            }
        }
        val starts = remember(widths, gapWorld) { Shelf3D.startAngles(widths, gapWorld, cam.radius) }
        val total = remember(widths, gapWorld) { Shelf3D.totalAngle(widths, gapWorld, cam.radius) }
        val halfVisible = Shelf3D.halfVisibleAngle(wPx, cam)
        val fits = total <= halfVisible * 2f * 0.98f
        val wrap = looping && !fits && total > 0f
        val baseAngle = if (fits) -total / 2f else -halfVisible
        val minScroll = if (fits) 0f else -(total - halfVisible * 2f)

        fun heightScreen(index: Int): Float =
            hPx * 0.50f * ShelfMath.spineHeightFraction(spines[index].id)

        fun hitTest(x: Float, y: Float): Int {
            if (spines.isEmpty()) return -1
            var best = -1
            var bestDepth = Float.MAX_VALUE
            val copies = if (wrap) listOf(-1, 0, 1) else listOf(0)
            for (copy in copies) {
                for (i in spines.indices) {
                    val a0 = starts[i] + baseAngle + scroll.value + copy * total
                    val a1 = a0 + widths[i] / cam.radius
                    val mid = (a0 + a1) / 2f
                    if (abs(mid) > halfVisible * 1.4f) continue
                    val quad = Shelf3D.face(
                        a0, a1, cam.radius,
                        shelfY - heightScreen(i) * k, shelfY, cam
                    )
                    if (Shelf3D.contains(quad, x, y)) {
                        val depth = cos(mid)
                        if (depth < bestDepth) {
                            bestDepth = depth
                            best = i
                        }
                    }
                }
            }
            return best
        }

        val dragState = rememberDraggableState { delta ->
            val next = scroll.value - delta / cam.focal
            val clamped = if (fits) 0f else next.coerceIn(minScroll, 0f)
            scope.launch { scroll.snapTo(clamped) }
        }

        Canvas(
            Modifier
                .fillMaxSize()
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStopped = { velocity ->
                        if (!fits) {
                            val v = -velocity / cam.focal
                            scope.launch {
                                if (abs(v) > 0.15f) {
                                    scroll.animateDecay(
                                        initialVelocity = v,
                                        animationSpec = exponentialDecay<Float>(frictionMultiplier = 2.4f)
                                    )
                                }
                                // the fling may run past the end of the row: settle back
                                val clamped = scroll.value.coerceIn(minScroll, 0f)
                                if (clamped != scroll.value) {
                                    scroll.animateTo(clamped, tween(durationMillis = 240))
                                }
                            }
                        }
                    }
                )
                .pointerInput(spines, wrap, total) {
                    detectTapGestures(
                        onTap = { pos ->
                            val i = hitTest(pos.x, pos.y)
                            if (i >= 0) onOpen(spines[i])
                        },
                        onLongPress = { pos ->
                            val i = hitTest(pos.x, pos.y)
                            if (i >= 0) onLongPress?.invoke(spines[i])
                        }
                    )
                }
        ) {
            val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
            val a0Case = -halfVisible * 1.45f
            val a1Case = halfVisible * 1.45f

            // ---- back of the case, a deep shadowed recess -------------------------
            drawCurvedPanel(
                cam = cam,
                a0 = a0Case,
                a1 = a1Case,
                radius = cam.radius - caseDepth,
                yTop = shelfY - hPx * 0.70f * k,
                yBottom = shelfY + boardFace,
                top = if (dark) Color(0xFF140C06) else Color(0xFF3A2413),
                bottom = if (dark) Color(0xFF0A0603) else Color(0xFF241408)
            )

            // ---- the board: top surface, then its front face ----------------------
            drawFlatBand(
                cam = cam,
                a0 = a0Case,
                a1 = a1Case,
                radiusFront = cam.radius + bookDepth * 0.30f,
                radiusBack = cam.radius - boardDepth,
                y = shelfY,
                color = if (dark) Color(0xFF5A3820) else Color(0xFFC08A56)
            )
            drawCurvedPanel(
                cam = cam,
                a0 = a0Case,
                a1 = a1Case,
                radius = cam.radius + bookDepth * 0.30f,
                yTop = shelfY,
                yBottom = shelfY + boardFace,
                top = if (dark) Color(0xFF6B4326) else Color(0xFFB07E4E),
                bottom = if (dark) Color(0xFF23140B) else Color(0xFF4A2C17)
            )

            // ---- books, farthest first -------------------------------------------
            if (spines.isNotEmpty()) {
                class Placed(val index: Int, val a0: Float, val a1: Float, val depth: Float)

                val placed = ArrayList<Placed>(spines.size * 3)
                val copies = if (wrap) listOf(-1, 0, 1) else listOf(0)
                for (copy in copies) {
                    for (i in spines.indices) {
                        val a0 = starts[i] + baseAngle + scroll.value + copy * total
                        val a1 = a0 + widths[i] / cam.radius
                        val mid = (a0 + a1) / 2f
                        if (mid < -halfVisible * 1.6f || mid > halfVisible * 1.6f) continue
                        placed.add(Placed(i, a0, a1, cos(mid)))
                    }
                }
                placed.sortByDescending { it.depth }

                for (book in placed) {
                    val spine = spines[book.index]
                    val h = heightScreen(book.index) * k
                    val yTop = shelfY - h
                    val facing = Shelf3D.facing(book.a0, book.a1)
                    val shade = Shelf3D.shadeVertical(facing)
                    val bmp = covers[spine.id]

                    // shadow where the book meets the board
                    fillQuad(
                        Shelf3D.flat(
                            book.a0 - 0.004f, book.a1 + 0.004f,
                            cam.radius + bookDepth * 0.26f,
                            cam.radius - bookDepth * 0.55f,
                            shelfY, cam
                        ),
                        Color.Black.copy(alpha = 0.38f)
                    )

                    // cream page block across the top of the book
                    fillQuad(
                        Shelf3D.flat(
                            book.a0, book.a1,
                            cam.radius, cam.radius - bookDepth,
                            yTop, cam
                        ),
                        Color(0xFFE9DBBC).copy(
                            alpha = (0.55f + 0.45f * Shelf3D.shadeUpward(facing)).coerceIn(0f, 1f)
                        )
                    )

                    // the side that turns away from the viewer
                    if (abs(facing) > 0.03f) {
                        val sideAngle = if (facing > 0f) book.a0 else book.a1
                        val side = Shelf3D.side(
                            sideAngle,
                            cam.radius, cam.radius - bookDepth,
                            yTop, shelfY, cam
                        )
                        fillQuad(side, Color(0xFFDDCDA9))
                        fillQuad(side, Color.Black.copy(alpha = 0.20f))
                    }

                    // the spine itself
                    val quad = Shelf3D.face(book.a0, book.a1, cam.radius, yTop, shelfY, cam)
                    if (bmp != null && !bmp.isRecycled) {
                        drawCover(bmp, quad, bitmapPaint)
                    } else {
                        fillQuad(quad, Color(ShelfMath.spineTintArgb(spine.id)))
                        drawSpineTitle(spine.title, quad)
                    }
                    // lamp light falling across the binding
                    fillQuad(quad, Color.Black.copy(alpha = ((1f - shade) * 0.60f).coerceIn(0f, 0.6f)))
                }
            }
        }

        if (spines.isEmpty() && emptyLabel.isNotBlank()) {
            Text(
                emptyLabel,
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 20.dp, bottom = shelfHeight * 0.30f),
                color = if (dark) Color(0xFFC9B79F) else Color(0xFF5E4A36),
                fontSize = 12.sp
            )
        }
    }
}

// ---------------------------------------------------------------------------------------
// drawing helpers
// ---------------------------------------------------------------------------------------

private fun DrawScope.fillQuad(quad: Shelf3D.Quad, color: Color) {
    val path = Path().apply {
        moveTo(quad.tl.x, quad.tl.y)
        lineTo(quad.tr.x, quad.tr.y)
        lineTo(quad.br.x, quad.br.y)
        lineTo(quad.bl.x, quad.bl.y)
        close()
    }
    drawPath(path, color)
}

/** Draws a cover into a projected quad with a true perspective transform. */
private fun DrawScope.drawCover(bmp: Bitmap, quad: Shelf3D.Quad, paint: Paint) {
    val src = floatArrayOf(
        0f, 0f,
        bmp.width.toFloat(), 0f,
        bmp.width.toFloat(), bmp.height.toFloat(),
        0f, bmp.height.toFloat()
    )
    val dst = floatArrayOf(
        quad.tl.x, quad.tl.y,
        quad.tr.x, quad.tr.y,
        quad.br.x, quad.br.y,
        quad.bl.x, quad.bl.y
    )
    val matrix = Matrix()
    if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) return
    drawIntoCanvas { canvas -> canvas.nativeCanvas.drawBitmap(bmp, matrix, paint) }
}

/** Title running down a cloth spine. */
private fun DrawScope.drawSpineTitle(title: String, quad: Shelf3D.Quad) {
    val width = kotlin.math.hypot(quad.tr.x - quad.tl.x, quad.tr.y - quad.tl.y)
    if (width < 10f) return
    val cx = (quad.tl.x + quad.tr.x + quad.br.x + quad.bl.x) / 4f
    val cy = (quad.tl.y + quad.tr.y + quad.br.y + quad.bl.y) / 4f
    val length = kotlin.math.hypot(quad.bl.x - quad.tl.x, quad.bl.y - quad.tl.y)
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(215, 246, 236, 216)
            textSize = (width * 0.66f).coerceIn(6f, 15f)
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL)
        }
        native.save()
        native.rotate(-90f, cx, cy)
        native.drawText(title.uppercase().take(30), cx, cy + paint.textSize * 0.34f, paint)
        native.restore()
    }
    // a hairline under the gilt so the title reads on a busy cloth
    drawLine(
        Color.Black.copy(alpha = 0.25f),
        androidx.compose.ui.geometry.Offset(cx - length * 0.02f, cy),
        androidx.compose.ui.geometry.Offset(cx + length * 0.02f, cy),
        strokeWidth = 1f
    )
}

/** A vertical surface following the curve of the case. */
private fun DrawScope.drawCurvedPanel(
    cam: Shelf3D.Camera,
    a0: Float,
    a1: Float,
    radius: Float,
    yTop: Float,
    yBottom: Float,
    top: Color,
    bottom: Color
) {
    val steps = 28
    val band = Path()
    for (i in 0..steps) {
        val a = a0 + (a1 - a0) * i / steps
        val p = Shelf3D.project(a, radius, yTop, cam)
        if (i == 0) band.moveTo(p.x, p.y) else band.lineTo(p.x, p.y)
    }
    for (i in steps downTo 0) {
        val a = a0 + (a1 - a0) * i / steps
        val p = Shelf3D.project(a, radius, yBottom, cam)
        band.lineTo(p.x, p.y)
    }
    band.close()
    val mid = (a0 + a1) / 2f
    drawPath(
        band,
        Brush.verticalGradient(
            listOf(top, bottom),
            startY = Shelf3D.project(mid, radius, yTop, cam).y,
            endY = Shelf3D.project(mid, radius, yBottom, cam).y
        )
    )
}

/** A horizontal surface (shelf board top) following the curve. */
private fun DrawScope.drawFlatBand(
    cam: Shelf3D.Camera,
    a0: Float,
    a1: Float,
    radiusFront: Float,
    radiusBack: Float,
    y: Float,
    color: Color
) {
    val steps = 28
    val band = Path()
    for (i in 0..steps) {
        val a = a0 + (a1 - a0) * i / steps
        val p = Shelf3D.project(a, radiusFront, y, cam)
        if (i == 0) band.moveTo(p.x, p.y) else band.lineTo(p.x, p.y)
    }
    for (i in steps downTo 0) {
        val a = a0 + (a1 - a0) * i / steps
        val p = Shelf3D.project(a, radiusBack, y, cam)
        band.lineTo(p.x, p.y)
    }
    band.close()
    val mid = (a0 + a1) / 2f
    drawPath(band, color)
    drawPath(
        band,
        Brush.verticalGradient(
            listOf(Color.Black.copy(alpha = 0.05f), Color.Black.copy(alpha = 0.30f)),
            startY = Shelf3D.project(mid, radiusBack, y, cam).y,
            endY = Shelf3D.project(mid, radiusFront, y, cam).y
        )
    )
}

/** A titled shelf: heading, the 3D row, nothing else. */
@Composable
fun TitledShelf(
    title: String,
    subtitle: String?,
    spines: List<Spine>,
    dark: Boolean,
    shelfHeight: Dp,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    emptyLabel: String = "Nothing on this shelf yet",
    onOpen: (Spine) -> Unit,
    onLongPress: ((Spine) -> Unit)? = null
) {
    Column(modifier.fillMaxWidth()) {
        if (title.isNotBlank()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        color = if (dark) Color(0xFFF4EADB) else Ink,
                        fontFamily = Playfair,
                        fontSize = 17.sp
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            subtitle,
                            color = if (dark) Color(0xFFC9B79F) else Color(0xFF6B5641),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                    }
                }
                trailing?.invoke()
            }
        }
        ShelfRow(
            spines = spines,
            dark = dark,
            shelfHeight = shelfHeight,
            emptyLabel = emptyLabel,
            onOpen = onOpen,
            onLongPress = onLongPress
        )
    }
}

/** Compact case for Studio's generated books. */
@Composable
fun MiniShelf(
    spines: List<Spine>,
    dark: Boolean,
    modifier: Modifier = Modifier,
    title: String = "Your Studio shelf",
    onOpen: (Spine) -> Unit
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            title,
            Modifier.padding(start = 18.dp, top = 10.dp, bottom = 2.dp),
            color = if (dark) Color(0xFFF4EADB) else Ink,
            fontFamily = Playfair,
            fontSize = 15.sp
        )
        ShelfRow(
            spines = spines,
            dark = dark,
            shelfHeight = 104.dp,
            emptyLabel = "Books you make will stand here",
            onOpen = onOpen
        )
    }
}

/** Loading / empty state: an empty board, so the page still looks like furniture. */
@Composable
fun EmptyShelf(dark: Boolean, modifier: Modifier = Modifier, label: String = "") {
    ShelfRow(
        spines = emptyList(),
        dark = dark,
        shelfHeight = 104.dp,
        modifier = modifier,
        emptyLabel = label,
        onOpen = {}
    )
}
