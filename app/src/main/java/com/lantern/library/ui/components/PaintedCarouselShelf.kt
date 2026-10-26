package com.lantern.library.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.splineBasedDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.lantern.library.R
import com.lantern.library.data.LibraryBook
import com.lantern.library.shelf3d.SpineAtlas
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * The shelf: painted bookcase art, with the reader's own spines standing in its recesses,
 * revolving horizontally as one continuous case.
 *
 * This is deliberately not a 3D render. The furniture in the art is drawn by hand — carved
 * boards, vines, a lantern, a mushroom hollow — and no engine was going to produce that from book
 * metadata. What this does add is the part that has to be real: every spine is the reader's book,
 * with its cover's artwork and its title down it, and dragging turns the whole case.
 *
 * One drawable, one bitmap, one Canvas pass: it cannot fail to compile, cannot be rejected by a
 * driver, and cannot stop the shelf from starting.
 */

/** One recess in the painted case, as fractions of the artwork's height. */
private class Tier(val bottom: Float, val height: Float)

/**
 * The recesses in `shelf_art`, read off the artwork: five painted shelves, of which the middle one
 * is a diorama that stays clear, and the other four hold books.
 */
private val TIERS = listOf(
    Tier(bottom = 0.300f, height = 0.170f), // the tall arched shelf under the cornice
    Tier(bottom = 0.487f, height = 0.125f), // the shelf with the hanging lantern
    Tier(bottom = 0.833f, height = 0.108f), // the shelf below the diorama
    Tier(bottom = 0.972f, height = 0.100f) // the bottom shelf
)

/** How wide one spine is, as a fraction of the case's width. */
private const val SPINE_FRACTION = 0.068f

@Composable
fun PaintedCarouselShelf(
    books: List<LibraryBook>,
    atlas: SpineAtlas?,
    colours: List<Int>,
    onTapBook: (Int) -> Unit,
    onLongPressBook: (Int) -> Unit = {},
    onFrontBook: (Int) -> Unit = {},
    /** A book to bring to the middle of the case, when the reader searches for one. */
    focusBook: Int? = null,
    focusNonce: Int = 0,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val art = imageResource(R.drawable.shelf_art).asImageBitmap()
    val sheet = remember(atlas) { atlas?.bitmap?.asImageBitmap() }

    // Where the case has been turned to, in pixels of shelf.
    val turn = remember { Animatable(0f) }
    val density = LocalDensity.current
    val decay = remember(density) { splineBasedDecay<Float>(density) }

    // The case's size on screen, needed by the drag and tap handlers as well as the drawing.
    var caseSize by remember { mutableStateOf(Size.Zero) }
    // Last drag velocity, so a throw can carry on after the finger lifts. A one-element array
    // rather than state, so a drag does not recompose sixty times a second.
    val velocity = remember { FloatArray(1) }

    val spineW = spineWidthPx(caseSize.width)
    val ring = books.size * spineW

    // Searching for a book slides it to the middle, so the reader sees the book they asked for.
    LaunchedEffect(focusBook, focusNonce, ring) {
        val target = focusBook ?: return@LaunchedEffect
        if (ring <= 0f || caseSize.width <= 0f) return@LaunchedEffect
        // Centre that book's slot in the case, taking the shorter way round the ring.
        val ideal = caseSize.width / 2f - (target + 0.5f) * spineW
        val turns = ((turn.value - ideal) / ring).toInt()
        turn.animateTo(ideal + turns * ring, spring(dampingRatio = 0.86f, stiffness = 240f))
    }

    Canvas(
        modifier
            .onSizeChanged { caseSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(books.size, ring) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val thrown = velocity[0]
                        velocity[0] = 0f
                        // A throw keeps the case going and slows like something heavy.
                        if (abs(thrown) > 80f) {
                            scope.launch { turn.animateDecay(thrown, decay) }
                        }
                    }
                ) { change, dragAmount ->
                    change.consume()
                    velocity[0] = dragAmount * 60f
                    scope.launch { turn.snapTo(turn.value + dragAmount) }
                }
            }
            .pointerInput(books.size, ring, sheet) {
                detectTapGestures(
                    onTap = { at ->
                        bookAt(at, caseSize, art, books.size, turn.value)?.let(onTapBook)
                    },
                    onLongPress = { at ->
                        bookAt(at, caseSize, art, books.size, turn.value)?.let(onLongPressBook)
                    }
                )
            }
    ) {
        drawCase(art, sheet, books, atlas, colours, turn.value)
    }

    // Report the book at the middle of the case, for the caption under it.
    if (ring > 0f && caseSize.width > 0f) {
        val middle = (turn.value + caseSize.width / 2f) / spineW
        val index = ((floor(middle).toInt() % books.size) + books.size) % books.size
        LaunchedEffect(index) { onFrontBook(index) }
    }
}

/** Width of one spine at a case [width] px wide. */
private fun spineWidthPx(width: Float): Float = max(16f, width * SPINE_FRACTION)

/** Where the artwork lands on screen: scaled to cover, anchored towards the top. */
private class CaseArt(val x: Float, val y: Float, val width: Float, val height: Float)

private fun caseArtOf(size: Size, art: ImageBitmap): CaseArt {
    val scale = max(size.width / art.width, size.height / art.height)
    val w = art.width * scale
    val h = art.height * scale
    return CaseArt(x = (size.width - w) / 2f, y = (size.height - h) * 0.30f, width = w, height = h)
}

/**
 * Which book is under [at].
 *
 * The same arithmetic the drawing uses, so a tap selects the spine under the finger rather than a
 * neighbour: find the recess by height, then solve the slot index from the x position and the
 * turn.
 */
private fun bookAt(
    at: Offset,
    size: Size,
    art: ImageBitmap,
    count: Int,
    turn: Float
): Int? {
    if (count <= 0 || size.width <= 0f) return null
    val case = caseArtOf(size, art)
    val tierIndex = TIERS.indexOfFirst { tier ->
        val bottom = case.y + case.height * tier.bottom
        val top = bottom - case.height * tier.height
        at.y in top..bottom
    }
    if (tierIndex < 0) return null

    val spineW = spineWidthPx(size.width)
    val shift = wrap(turn, count * spineW)
    val offset = tierOffset(tierIndex, spineW)
    val slot = floor((at.x + shift + offset) / spineW).toInt()
    return ((slot % count) + count) % count
}

/** Books on tier [tier] start this far round the ring, so the tiers are not all in step. */
private fun tierOffset(tier: Int, spineW: Float): Float = tier * 3.7f * spineW

private fun wrap(value: Float, span: Float): Float {
    if (span <= 0f) return 0f
    var v = value % span
    if (v < 0f) v += span
    return v
}

/**
 * Draws the case: the painted furniture, then the spines standing in its recesses.
 *
 * The books repeat every [count] spines, so the case turns for ever in both directions and every
 * book comes round again before any of them repeats.
 */
private fun DrawScope.drawCase(
    art: ImageBitmap,
    sheet: ImageBitmap?,
    books: List<LibraryBook>,
    atlas: SpineAtlas?,
    colours: List<Int>,
    turn: Float
) {
    val case = caseArtOf(size, art)
    drawImage(
        image = art,
        dstOffset = IntOffset(case.x.toInt(), case.y.toInt()),
        dstSize = IntSize(case.width.toInt(), case.height.toInt()),
        filterQuality = FilterQuality.High
    )
    if (books.isEmpty()) return

    val spineW = spineWidthPx(size.width)
    val ring = books.size * spineW
    if (ring <= 0f) return
    val shift = wrap(turn, ring)

    TIERS.forEachIndexed { tierIndex, tier ->
        val shelfBottom = case.y + case.height * tier.bottom
        val shelfTop = shelfBottom - case.height * tier.height
        val offset = tierOffset(tierIndex, spineW)

        // One extra book each side, so books entering the case are already drawn.
        val first = floor((shift - offset) / spineW).toInt() - 1
        val last = first + (size.width / spineW).toInt() + 3

        for (slot in first..last) {
            val index = ((slot % books.size) + books.size) % books.size
            val left = slot * spineW - shift + offset
            if (left > size.width || left + spineW < 0f) continue

            // The shelf is curved: a book towards the edge of the case sits a little lower and is
            // a little shorter, which is what makes the boards read as arcs rather than lines.
            val centre = (left + spineW / 2f) / size.width - 0.5f
            val bow = centre * centre
            val height = (shelfBottom - shelfTop) * (1f - bow * 0.16f)
            val bottom = shelfBottom - bow * (shelfBottom - shelfTop) * 0.10f
            val width = spineW * (1f - bow * 0.08f)

            drawSpine(
                sheet = sheet,
                atlas = atlas,
                index = index,
                colour = colours.getOrElse(index) { 0xFF6B4A32.toInt() },
                dst = Rect(left, bottom - height, left + width, bottom)
            )
        }
    }
}

/** One spine: the book's own tile out of the atlas, or a binding colour if it has no cover. */
private fun DrawScope.drawSpine(
    sheet: ImageBitmap?,
    atlas: SpineAtlas?,
    index: Int,
    colour: Int,
    dst: Rect
) {
    val tile = atlas?.tiles?.getOrNull(index)
    if (sheet != null && tile != null) {
        val srcX = (tile.u0 * sheet.width).toInt()
        val srcY = (tile.v0 * sheet.height).toInt()
        val srcW = ((tile.u1 - tile.u0) * sheet.width).toInt()
        val srcH = ((tile.v1 - tile.v0) * sheet.height).toInt()
        if (srcW > 0 && srcH > 0) {
            drawImage(
                image = sheet,
                srcOffset = IntOffset(srcX, srcY),
                srcSize = IntSize(srcW, srcH),
                dstOffset = IntOffset(dst.left.toInt(), dst.top.toInt()),
                dstSize = IntSize(dst.width.toInt(), dst.height.toInt()),
                filterQuality = FilterQuality.High
            )
            return
        }
    }
    // No cover art for this book: a plain binding, with a gilt rule so it still reads as a book.
    drawRect(color = Color(colour), topLeft = dst.topLeft, size = dst.size)
    drawLine(
        color = Color(0xFFF3DFAC).copy(alpha = 0.35f),
        start = Offset(dst.left + dst.width * 0.3f, dst.top + dst.height * 0.08f),
        end = Offset(dst.left + dst.width * 0.3f, dst.bottom - dst.height * 0.08f),
        strokeWidth = max(1f, dst.width * 0.03f)
    )
}
