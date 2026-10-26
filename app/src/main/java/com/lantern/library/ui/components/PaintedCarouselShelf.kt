package com.lantern.library.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.lantern.library.R
import com.lantern.library.data.LibraryBook
import com.lantern.library.shelf3d.SpineAtlas
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The reader's shelf: a painted revolving case, its bays filled with their own books.
 *
 * The furniture is artwork — carved boards, vines, a lantern, a mushroom hollow — and this file
 * owns the part that has to be real: the books standing *inside* those bays, on a curved run that
 * turns with the finger.
 *
 * Three things make it read as furniture rather than a strip of stickers:
 *
 *  - **Containment.** Each bay is a measured rectangle inside the carved frame, and every book is
 *    clipped to it. Nothing is ever drawn over a board, an arch or a divider.
 *  - **Curve.** A book's size, tilt and brightness follow its distance from the middle of its bay:
 *    full size and face-on at the centre, smaller, tilted and dimmer towards the edges, so the
 *    run of spines bows away around a cylinder and the bay reads as a solid volume.
 *  - **No repetition.** Each book appears at most once in a bay. A small library leaves visible
 *    wood at the end of the shelf, which is what a real half-filled shelf looks like; a large one
 *    fills every bay, and turning the case brings the rest round.
 */

/** A recess in the painted case, as fractions of the artwork's height and width. */
private class Bay(val top: Float, val bottom: Float)

/**
 * The four book bays, measured off `shelf_art` itself by sampling its brightness down the middle:
 * the dark bands are the recesses, the bright bands are the boards. The fifth recess is the
 * diorama, which stays clear.
 */
private val BAYS = listOf(
    Bay(top = 0.146f, bottom = 0.296f), // the tall arched shelf under the cornice
    Bay(top = 0.352f, bottom = 0.484f), // the shelf with the hanging lantern
    Bay(top = 0.720f, bottom = 0.802f), // the shelf below the diorama
    Bay(top = 0.866f, bottom = 0.928f) // the bottom shelf
)

/** Where the bays start and end horizontally: inside the carved columns either side. */
private const val BAY_LEFT = 0.105f
private const val BAY_RIGHT = 0.865f

/** How much of a bay's width a book's spine covers before variation, as a fraction. */
private const val SPINES_PER_BAY = 8f

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
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()

    val art = remember {
        android.graphics.BitmapFactory
            .decodeResource(context.resources, R.drawable.shelf_art)
            .asImageBitmap()
    }
    val sheet = remember(atlas) { atlas?.bitmap?.asImageBitmap() }

    // How far the case has been turned, in pixels of shelf. Pixels rather than books, because
    // each bay has its own spine width: a drag has to move them all at the same speed.
    val scroll = remember { Animatable(0f) }
    val decay = remember { exponentialDecay<Float>(frictionMultiplier = 1.6f) }
    val throwVelocity = remember { FloatArray(1) }

    var caseSize by remember { mutableStateOf(Size.Zero) }
    val layout = remember(caseSize, books.size) { CaseLayout(caseSize, art, books.size) }

    // The tallest bay's spine width: the case is driven by that one, and the others follow it.
    val spinePx = layout.bays.firstOrNull()?.spine ?: 0f

    // Measured titles, kept between frames: measuring text is the one expensive thing in the draw.
    val titleCache = remember { HashMap<Long, TextLayoutResult>() }

    // Searching slides the book asked for to the middle of the case.
    LaunchedEffect(focusBook, focusNonce, spinePx) {
        val target = focusBook ?: return@LaunchedEffect
        if (spinePx <= 0f) return@LaunchedEffect
        val bay = layout.bays.firstOrNull() ?: return@LaunchedEffect
        val ideal = (target - bay.shift) * spinePx - bay.spine * bay.shown / 2f
        val ring = books.size * spinePx
        if (ring <= 0f) return@LaunchedEffect
        val turns = ((scroll.value - ideal) / ring).roundToInt()
        scroll.animateTo(ideal + turns * ring, spring(dampingRatio = 0.9f, stiffness = 220f))
    }

    Canvas(
        modifier
            .onSizeChanged { caseSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(books.size, spinePx) {
                if (spinePx <= 0f) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val thrown = throwVelocity[0]
                        throwVelocity[0] = 0f
                        // Books per second; the case coasts and slows like something heavy.
                        if (abs(thrown) > 0.4f) {
                            scope.launch { scroll.animateDecay(thrown, decay) }
                        }
                    }
                ) { change, dragAmount ->
                    change.consume()
                    // Dragging right moves the shelf right, with the finger: the scroll position
                    // decreases as the content slides right.
                    throwVelocity[0] = -dragAmount * 60f
                    scope.launch { scroll.snapTo(scroll.value - dragAmount) }
                }
            }
            .pointerInput(books.size, spinePx, sheet) {
                detectTapGestures(
                    onTap = { at -> layout.bookAt(at, scroll.value)?.let(onTapBook) },
                    onLongPress = { at -> layout.bookAt(at, scroll.value)?.let(onLongPressBook) }
                )
            }
    ) {
        drawCase(art, sheet, books, atlas, colours, scroll.value, layout, measurer, titleCache, density)
    }

    // The book in the middle of the case, for the caption under it.
    if (spinePx > 0f) {
        val middle = scroll.value + spinePx * (layout.bays.firstOrNull()?.shown ?: 0) / 2f
        val index = ((floor(middle / spinePx).toInt() % books.size) + books.size) % books.size
        LaunchedEffect(index) { onFrontBook(index) }
    }
}

/**
 * One bay's measurements: where it is, how wide its books are, and how many it holds.
 *
 * A spine's width is bounded by its bay's height, because a book is not a square: the tallest
 * shelf takes wide spines, the short bottom shelf takes narrow ones, and the row keeps the same
 * proportions on every shelf the way a real collection does.
 */
private class BayMetrics(
    bay: Bay,
    index: Int,
    artX: Float,
    artY: Float,
    artWidth: Float,
    artHeight: Float,
    count: Int
) {
    /** How far round the ring this bay's books start, so the shelves are not all in step. */
    val shift = index * 3

    val top = artY + artHeight * bay.top
    val bottom = artY + artHeight * bay.bottom
    val height = bottom - top
    val left = artX + artWidth * BAY_LEFT
    val right = artX + artWidth * BAY_RIGHT
    val width = right - left

    /** Widest a spine may be: books are taller than they are wide, always. */
    private val maxSpine = height / 3.2f

    /** How many spines this bay can hold at that proportion. */
    val capacity = max(1, (width / (height / 4.6f)).toInt())

    /** How many are actually shown, and how wide each is. */
    val shown = min(capacity, count).coerceAtLeast(0)
    val spine: Float

    /** Where the run of books starts, so a half-filled shelf is centred rather than left-hung. */
    val startX: Float

    init {
        if (shown == 0 || count <= 0) {
            spine = 0f
            startX = left
        } else {
            // Fill the bay when the library is big enough; when it is not, widen the spines to a
            // point and centre the run, leaving wood either side — which is what a shelf with
            // room to spare looks like.
            spine = min(width / shown, maxSpine)
            startX = left + (width - spine * shown) / 2f
        }
    }
}

/** Everything about how the case maps to the screen, computed once per size. */
private class CaseLayout(size: Size, art: ImageBitmap, private val count: Int) {

    /** The artwork, scaled to cover the case and anchored so the cornice sits under the heading. */
    val artX: Float
    val artY: Float
    val artWidth: Float
    val artHeight: Float

    /** One entry per bay, in the same order as [BAYS]. */
    val bays: List<BayMetrics>

    init {
        if (size.width <= 0f || size.height <= 0f || art.width <= 0 || art.height <= 0) {
            artX = 0f
            artY = 0f
            artWidth = 0f
            artHeight = 0f
            bays = emptyList()
        } else {
            val scale = max(size.width / art.width, size.height / art.height)
            artWidth = art.width * scale
            artHeight = art.height * scale
            artX = (size.width - artWidth) / 2f
            artY = (size.height - artHeight) * 0.30f
            bays = BAYS.mapIndexed { index, bay ->
                BayMetrics(bay, index, artX, artY, artWidth, artHeight, count)
            }
        }
    }

    /**
     * Which book is under [at].
     *
     * The bay is found by height, then the book by solving its slot from the x position and the
     * scroll, in that bay's own spine width — the same arithmetic the drawing uses, so a tap lands
     * on the spine that was touched.
     */
    fun bookAt(at: Offset, scroll: Float): Int? {
        if (count <= 0) return null
        val bayIndex = bays.indexOfFirst { at.y in it.top..it.bottom }
        if (bayIndex < 0) return null
        val bay = bays[bayIndex]
        if (bay.spine <= 0f || at.x !in bay.startX..(bay.startX + bay.spine * bay.shown)) return null
        val slot = ((at.x - bay.startX) / bay.spine).toInt().coerceIn(0, bay.shown - 1)
        val first = floor(scroll / bay.spine).toInt() + bay.shift
        val book = first + slot
        return ((book % count) + count) % count
    }
}

/**
 * Draws the case: the painted furniture, then the books standing in its bays.
 *
 * Books are drawn from the outside of each bay inwards, so the book in the middle — the one at
 * full size, face on — sits over its neighbours the way a real row of spines overlaps.
 */
private fun DrawScope.drawCase(
    art: ImageBitmap,
    sheet: ImageBitmap?,
    books: List<LibraryBook>,
    atlas: SpineAtlas?,
    colours: List<Int>,
    scroll: Float,
    layout: CaseLayout,
    measurer: TextMeasurer,
    titleCache: HashMap<Long, TextLayoutResult>,
    density: androidx.compose.ui.unit.Density
) {
    drawImage(
        image = art,
        dstOffset = IntOffset(layout.artX.toInt(), layout.artY.toInt()),
        dstSize = IntSize(layout.artWidth.toInt(), layout.artHeight.toInt()),
        filterQuality = FilterQuality.High
    )
    if (books.isEmpty() || layout.bays.isEmpty()) return

    layout.bays.forEachIndexed { bayIndex, bay ->
        if (bay.shown == 0 || bay.spine <= 0f) return@forEachIndexed
        // This bay shows a different slice of the library, so the four shelves together show the
        // whole collection rather than the same few books four times.
        val bayShift = bay.shift

        clipRect(left = bay.left, top = bay.top, right = bay.right, bottom = bay.bottom) {
            // Outside in: the ends of the run first, so the book at the middle overlaps its
            // neighbours the way a row of spines actually does.
            val order = (0 until bay.shown).sortedByDescending { abs(it - bay.shown / 2f) }
            for (slot in order) {
                val first = floor(scroll / bay.spine).toInt() + bayShift
                val book = first + slot
                val index = ((book % books.size) + books.size) % books.size

                val thickness = thicknessOf(books[index].id)
                val width = bay.spine * thickness
                val x = bay.startX + slot * bay.spine
                if (x > bay.right || x + width < bay.left) continue

                // How far this book is from the middle of its bay, -1 at the left edge to +1 at
                // the right. The whole curve is driven by this one number.
                val t = (((x + width / 2f) - (bay.left + bay.width / 2f)) / (bay.width / 2f))
                    .coerceIn(-1f, 1f)

                // Turning away at the ends: shorter, narrower, tilted, and set back into the
                // shadow, which is what makes a flat run read as a curve.
                val scale = 1f - 0.20f * t * t
                val bookHeight = bay.height * 0.94f * scale * heightOf(books[index].id)
                val restY = bay.bottom - bay.height * 0.03f - (1f - scale) * bay.height * 0.22f
                val drawnWidth = width * (1f - 0.18f * t * t)

                drawBook(
                    sheet = sheet,
                    atlas = atlas,
                    book = books[index],
                    tileIndex = index,
                    colour = colours.getOrElse(index) { 0xFF6B4A32.toInt() },
                    x = x,
                    top = restY - bookHeight,
                    width = drawnWidth,
                    height = bookHeight,
                    tilt = t * 5f,
                    dim = t * t * 0.45f,
                    measurer = measurer,
                    titleCache = titleCache,
                    density = density
                )
            }

            // Inside shadow at the head of the bay: books sit under the board, not on top of it.
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0x66000000), Color.Transparent),
                    startY = bay.top,
                    endY = bay.top + bay.height * 0.30f
                ),
                topLeft = Offset(bay.left, bay.top),
                size = Size(bay.width, bay.height)
            )
            // Shadow where the books meet the board.
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color(0x77000000)),
                    startY = bay.bottom - bay.height * 0.14f,
                    endY = bay.bottom
                ),
                topLeft = Offset(bay.left, bay.bottom - bay.height * 0.14f),
                size = Size(bay.width, bay.height * 0.14f)
            )
            // The bay curves away at both ends: darken its edges so it reads as a recess.
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color(0x66000000), 0.20f to Color.Transparent,
                    0.80f to Color.Transparent, 1f to Color(0x66000000)
                ),
                topLeft = Offset(bay.left, bay.top),
                size = Size(bay.width, bay.height)
            )
        }
    }
}

/** One book: its cover strip, a gilt rule down each edge, its title, and a shadow. */
private fun DrawScope.drawBook(
    sheet: ImageBitmap?,
    atlas: SpineAtlas?,
    book: LibraryBook,
    tileIndex: Int,
    colour: Int,
    x: Float,
    top: Float,
    width: Float,
    height: Float,
    tilt: Float,
    dim: Float,
    measurer: TextMeasurer,
    titleCache: HashMap<Long, TextLayoutResult>,
    density: androidx.compose.ui.unit.Density
) {
    if (width < 2f || height < 8f) return

    rotate(tilt, pivot = Offset(x + width / 2f, top + height)) {
        // A shadow to the right of the spine, which is what makes a row read as separate books.
        drawRect(
            color = Color(0x40000000),
            topLeft = Offset(x + width - 1f, top + height * 0.02f),
            size = Size(max(2f, width * 0.10f), height * 0.98f)
        )

        val tile = atlas?.tiles?.getOrNull(tileIndex)
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
                    dstOffset = IntOffset(x.toInt(), top.toInt()),
                    dstSize = IntSize(width.toInt(), height.toInt()),
                    filterQuality = FilterQuality.High
                )
            }
        } else {
            // No cover art: a cloth binding in the book's own colour.
            drawRect(color = Color(colour), topLeft = Offset(x, top), size = Size(width, height))
        }

        // Unify the row: a wash of the book's binding colour, then a dark edge, so a cover strip
        // reads as a spine rather than as a slice of a picture.
        drawRect(
            color = Color(colour).copy(alpha = 0.22f),
            topLeft = Offset(x, top),
            size = Size(width, height)
        )
        drawRect(
            color = Color(0x55000000),
            topLeft = Offset(x, top),
            size = Size(max(1f, width * 0.06f), height)
        )
        // Gilt rules at head and tail.
        val rule = max(1f, height * 0.006f)
        drawRect(
            color = Color(0xFFE4C481).copy(alpha = 0.55f),
            topLeft = Offset(x, top + height * 0.06f),
            size = Size(width, rule)
        )
        drawRect(
            color = Color(0xFFE4C481).copy(alpha = 0.55f),
            topLeft = Offset(x, top + height * 0.94f),
            size = Size(width, rule)
        )

        // The title, down the spine, in gilt — the one thing that makes a shelf readable.
        drawSpineTitle(book.title, x, top, width, height, measurer, titleCache, density)

        // Depth: books at the ends of the run sit back in the shadow.
        if (dim > 0.01f) {
            drawRect(
                color = Color(0x33000000).copy(alpha = dim),
                topLeft = Offset(x, top),
                size = Size(width, height)
            )
        }
    }
}

/** Title in gilt, rotated to run down the spine, ellipsized to fit. */
private fun DrawScope.drawSpineTitle(
    title: String,
    x: Float,
    top: Float,
    width: Float,
    height: Float,
    measurer: TextMeasurer,
    titleCache: HashMap<Long, TextLayoutResult>,
    density: androidx.compose.ui.unit.Density
) {
    if (title.isBlank() || width < 6f) return
    // The title has to fit across the spine, and run down it: the font is a fraction of the
    // spine's width, and the layout is constrained to its height.
    val fontPx = (width * 0.66f).coerceIn(4f, 40f)
    val key = (title.hashCode().toLong() shl 20) or fontPx.toLong()
    val layout = titleCache.getOrPut(key) {
        measurer.measure(
            text = title,
            style = TextStyle(
                color = Color(0xFFF6E4B8),
                fontSize = with(density) { fontPx.toSp() },
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            constraints = androidx.compose.ui.unit.Constraints(
                maxWidth = max(1, (height * 0.86f).toInt())
            )
        )
    }
    val centreX = x + width / 2f
    val centreY = top + height / 2f
    withTransform({
        rotate(90f, pivot = Offset(centreX, centreY))
    }) {
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                centreX - layout.size.width / 2f,
                centreY - layout.size.height / 2f
            )
        )
    }
}

/** A stable thickness for a book, so a shelf looks picked rather than uniform. */
private fun thicknessOf(id: String): Float {
    val h = (id.hashCode() * 2654435761L).toInt()
    return 0.86f + (abs(h) % 100) / 100f * 0.34f
}

/** A stable height for a book, so the tops of a run are not a ruler line. */
private fun heightOf(id: String): Float {
    val h = (id.hashCode() * 40503L).toInt()
    return 0.90f + (abs(h) % 100) / 100f * 0.10f
}
