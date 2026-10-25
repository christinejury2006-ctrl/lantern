package com.lantern.library.shelf3d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import com.lantern.library.data.CoverCache
import com.lantern.library.data.LibraryBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/**
 * Every book's spine, painted into one texture, with a rectangle for each book.
 *
 * One texture for the whole shelf matters: a texture per book would mean a hundred texture binds
 * and a hundred small allocations, and Filament would spend more time on state changes than on
 * drawing. Each book instead carries a uv offset and scale, so all of them share one material and
 * one texture and differ only by a few floats.
 *
 * A spine carries two things: the middle strip of its cover, which is what actually sits on a
 * real spine, and its title in gilt, running down the spine the way titles do. That second part
 * is what makes a case readable rather than decorative — a row of colour blocks tells the reader
 * nothing, however handsome the colours are. A book with no cover still gets a painted leather
 * spine and its title, so nothing on the case is anonymous.
 *
 * The same bitmap also carries the niche that stands in the gap between the picks and the rest of
 * the library. Painting it here rather than shading it means the worst a mistake can do is look
 * wrong, instead of stopping the shelf from starting.
 */
class SpineAtlas(
    val bitmap: Bitmap,
    /** One rect per book, in library order. Null where the book is past the atlas's capacity. */
    val tiles: List<Tile?>,
    /** The niche, for the gap in the case. Null if it could not be given a tile. */
    val nookTile: Tile? = null
) {

    /**
     * Where a book's art sits in the atlas, normalised to 0..1.
     *
     * v0 is the TOP of the tile: the spine geometry gives v = 0 at the top of a book, and the
     * material does not flip V, so v runs straight down the image.
     */
    class Tile(val u0: Float, val v0: Float, val u1: Float, val v1: Float)

    companion object {
        /** Columns of tiles in the atlas. */
        const val COLUMNS = 8

        /** Rows are capped so the atlas stays inside a 4096 texture. */
        private const val MAX_ROWS = 16

        private const val MAX_TILE_HEIGHT = 256

        /** A book's proportions: 0.115 across by 0.31 tall. */
        private const val TILE_ASPECT = 0.37f

        /** Books beyond this keep a plain binding rather than no texture at all. */
        const val CAPACITY = COLUMNS * MAX_ROWS

        /**
         * Decodes covers, paints spines and titles, and adds the niche.
         *
         * Runs on IO: this reads image files. [colours] are the leather colours the shelf would
         * use anyway, so a book without a cover is painted in the colour it is bound in rather
         * than in a default.
         */
        suspend fun build(
            context: Context,
            books: List<LibraryBook>,
            colours: List<Int> = emptyList()
        ): SpineAtlas? = withContext(Dispatchers.IO) {
            if (books.isEmpty()) return@withContext null

            val used = minOf(books.size, CAPACITY)
            // One tile beyond the books, for the niche.
            val rows = ceil((used + 1) / COLUMNS.toFloat()).toInt().coerceIn(1, MAX_ROWS)
            val tileHeight = minOf(MAX_TILE_HEIGHT, 4096 / rows)
            val tileWidth = (tileHeight * TILE_ASPECT).toInt().coerceAtLeast(8)

            val atlas = Bitmap.createBitmap(
                tileWidth * COLUMNS,
                tileHeight * rows,
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(atlas)
            // A warm dark ground, so a cover with soft edges does not float in the atlas.
            canvas.drawColor(0xFF2A1B12.toInt())

            val artPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            val edgePaint = Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                color = 0x66000000
            }
            // Under the gilt. A pale cover would otherwise swallow the title entirely.
            val scrimPaint = Paint().apply { color = 0x50000000 }

            val tiles = ArrayList<Tile?>(books.size)

            for (index in books.indices) {
                if (index >= used) {
                    // Too many books for one texture: keep the binding colour.
                    tiles.add(null)
                    continue
                }
                val book = books[index]
                val rect = tileRect(index, tileWidth, tileHeight)

                val cover = loadCover(context, book)
                if (cover != null) {
                    val strip = SpineArt.stripOf(cover)
                    drawCentreCrop(canvas, strip, rect, artPaint)
                    // Only the strip is ours to free: a cover may be held by CoverCache, and
                    // recycling it would leave the 2D shelf drawing a blank bitmap.
                    if (strip !== cover) runCatching { strip.recycle() }
                } else {
                    paintBinding(
                        canvas,
                        rect,
                        colours.getOrElse(index) { 0xFF6B4A32.toInt() }
                    )
                }

                canvas.drawRect(rect, scrimPaint)
                drawSpineTitle(canvas, rect, book.title)
                canvas.drawRect(
                    rect.left.toFloat(), rect.top.toFloat(),
                    (rect.right - 1).toFloat(), (rect.bottom - 1).toFloat(),
                    edgePaint
                )
                tiles.add(tileOf(rect, atlas.width, atlas.height))
            }

            var nook: Tile? = null
            val nookIndex = used
            if (nookIndex < COLUMNS * rows && nookIndex < CAPACITY) {
                val rect = tileRect(nookIndex, tileWidth, tileHeight)
                NookArt.paint(canvas, rect)
                nook = tileOf(rect, atlas.width, atlas.height)
            }

            SpineAtlas(atlas, tiles, nook)
        }

        /** Where tile [index] sits in the grid. */
        private fun tileRect(index: Int, tileWidth: Int, tileHeight: Int): Rect {
            val column = index % COLUMNS
            val row = index / COLUMNS
            val left = column * tileWidth
            val top = row * tileHeight
            return Rect(left, top, left + tileWidth, top + tileHeight)
        }

        /** [rect] as normalised texture coordinates. */
        private fun tileOf(rect: Rect, atlasWidth: Int, atlasHeight: Int): Tile = Tile(
            u0 = rect.left / atlasWidth.toFloat(),
            v0 = rect.top / atlasHeight.toFloat(),
            u1 = rect.right / atlasWidth.toFloat(),
            v1 = rect.bottom / atlasHeight.toFloat()
        )

        /** A bound spine: the leather colour, ribs, and wear at the head and tail. */
        private fun paintBinding(canvas: Canvas, rect: Rect, colour: Int) {
            canvas.drawRect(rect, Paint().apply { color = colour })

            val rib = Paint().apply { color = 0x33000000 }
            val bands = 5
            for (band in 1 until bands) {
                val y = rect.top + rect.height() * band / bands.toFloat()
                canvas.drawRect(
                    rect.left.toFloat(), y,
                    rect.right.toFloat(), y + rect.height() * 0.012f,
                    rib
                )
            }

            val cap = Paint().apply { color = 0x55000000 }
            val capHeight = rect.height() * 0.06f
            canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.top + capHeight, cap)
            canvas.drawRect(rect.left.toFloat(), rect.bottom - capHeight, rect.right.toFloat(), rect.bottom.toFloat(), cap)
        }

        /**
         * The title, in gilt, running down the spine.
         *
         * The canvas is turned a quarter turn about the tile's centre and the title is drawn
         * along what was the tile's height, which is how a real spine reads: head at the top,
         * title down the spine, and legible from a metre away.
         */
        private fun drawSpineTitle(canvas: Canvas, rect: Rect, title: String) {
            if (title.isBlank() || rect.width() < 12) return
            val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFF3DFAC.toInt()
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                // Sized to the spine's width, so the title fits across it at any atlas resolution.
                textSize = rect.width() * 0.62f
                setShadowLayer(3f, 0f, 1f, 0xCC000000.toInt())
            }
            val room = rect.height() * 0.86f
            val text = TextUtils.ellipsize(title, paint, room, TextUtils.TruncateAt.END).toString()

            canvas.save()
            canvas.rotate(90f, rect.exactCenterX(), rect.exactCenterY())
            val baseline = rect.exactCenterY() - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(text, rect.exactCenterX(), baseline, paint)
            canvas.restore()
        }

        private suspend fun loadCover(context: Context, book: LibraryBook): Bitmap? {
            val res = book.localCover
            if (res != null) {
                runCatching { BitmapFactory.decodeResource(context.resources, res) }
                    .getOrNull()
                    ?.let { if (!it.isRecycled) return it }
            }
            // Covers saved by an import or a download are files on disk. CoverCache reuses what
            // the 2D shelf already decoded; it only reaches the network for a cover that has
            // never been fetched, and remembers failures so a dead URL costs nothing twice.
            if (book.remoteCover.isNullOrBlank()) return null
            return runCatching { CoverCache.load(book.remoteCover) }.getOrNull()
        }

        /** Draws [src] into [dst], scaling to fill and cropping the overhang. */
        private fun drawCentreCrop(canvas: Canvas, src: Bitmap, dst: Rect, paint: Paint) {
            if (src.width <= 0 || src.height <= 0) return
            val targetAspect = dst.width().toFloat() / dst.height().coerceAtLeast(1)
            val srcAspect = src.width.toFloat() / src.height.toFloat()
            val w: Int
            val h: Int
            if (srcAspect > targetAspect) {
                h = src.height
                w = (h * targetAspect).toInt().coerceIn(1, src.width)
            } else {
                w = src.width
                h = (w / targetAspect).toInt().coerceIn(1, src.height)
            }
            val x = (src.width - w) / 2
            val y = (src.height - h) / 2
            canvas.drawBitmap(src, Rect(x, y, x + w, y + h), dst, paint)
        }
    }
}

/**
 * The slice of a cover that belongs on a spine.
 *
 * Kept separate from the atlas so the crop can be tested without a Canvas.
 */
object SpineArt {

    /** Fraction of the cover's width that goes on the spine. */
    const val STRIP_FRACTION = 0.34f

    /** The middle vertical strip of [cover]. Returns [cover] itself if the strip would be silly. */
    fun stripOf(cover: Bitmap): Bitmap {
        val width = (cover.width * STRIP_FRACTION).toInt()
        if (width < 24 || cover.height < 24) return cover
        val left = ((cover.width - width) / 2).coerceAtLeast(0)
        return runCatching {
            Bitmap.createBitmap(cover, left, 0, width, cover.height)
        }.getOrDefault(cover)
    }
}
