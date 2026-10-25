package com.lantern.library.shelf3d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.lantern.library.data.CoverCache
import com.lantern.library.data.LibraryBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/**
 * Every book's spine art, painted into one texture, with a rectangle for each book.
 *
 * One texture for the whole shelf matters: a texture per book would mean a hundred texture
 * binds and a hundred small allocations, and Filament would spend more time on state changes
 * than on drawing. Each book instead carries a uv offset and scale, so all of them share one
 * material and one texture and differ only by a few floats.
 *
 * The art is taken the same way the 2D shelf takes it — the middle strip of the cover, which is
 * what actually sits on a real spine — so a book looks the same in both places.
 */
class SpineAtlas(
    val bitmap: Bitmap,
    /** One rect per book, in library order. Null where no cover could be loaded. */
    val tiles: List<Tile?>
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

        /** Books beyond this get a plain leather binding rather than no texture at all. */
        const val CAPACITY = COLUMNS * MAX_ROWS

        /**
         * Decodes covers and paints them into one bitmap.
         *
         * Runs on IO: this reads image files. Returns null when not one cover could be loaded,
         * which lets the caller fall back to coloured bindings instead of a blank atlas.
         */
        suspend fun build(
            context: Context,
            books: List<LibraryBook>,
            /**
             * Ids of books picked for the reader. Their tile gets a brass band painted across it,
             * which is how a recommendation is shown on the case.
             *
             * Painted rather than shaded on purpose. A shader that will not compile takes the
             * whole shelf with it; a wrong pixel in a texture takes nothing with it.
             */
            picked: Set<String> = emptySet()
        ): SpineAtlas? =
            withContext(Dispatchers.IO) {
                if (books.isEmpty()) return@withContext null

                val used = minOf(books.size, CAPACITY)
                val rows = ceil(used / COLUMNS.toFloat()).toInt().coerceIn(1, MAX_ROWS)
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

                val tiles = ArrayList<Tile?>(books.size)
                var painted = 0

                for (index in books.indices) {
                    if (index >= used) {
                        // Too many books for one texture: keep the binding colour.
                        tiles.add(null)
                        continue
                    }
                    val book = books[index]
                    val cover = loadCover(context, book)
                    if (cover == null) {
                        tiles.add(null)
                        continue
                    }
                    val column = index % COLUMNS
                    val row = index / COLUMNS
                    val left = column * tileWidth
                    val top = row * tileHeight
                    val rect = Rect(left, top, left + tileWidth, top + tileHeight)

                    val strip = SpineArt.stripOf(cover)
                    drawCentreCrop(canvas, strip, rect, artPaint)
                    // Only the strip is ours to free: a cover may be held by CoverCache, and
                    // recycling it would leave the 2D shelf drawing a blank bitmap.
                    if (strip !== cover) runCatching { strip.recycle() }
                    canvas.drawRect(
                        rect.left.toFloat(), rect.top.toFloat(),
                        (rect.right - 1).toFloat(), (rect.bottom - 1).toFloat(),
                        edgePaint
                    )
                    if (book.id in picked) drawRibbon(canvas, rect)
                    painted++

                    val w = atlas.width.toFloat()
                    val h = atlas.height.toFloat()
                    tiles.add(
                        Tile(
                            u0 = left / w,
                            v0 = top / h,
                            u1 = (left + tileWidth) / w,
                            v1 = (top + tileHeight) / h
                        )
                    )
                }

                if (painted == 0) {
                    atlas.recycle()
                    null
                } else {
                    SpineAtlas(atlas, tiles)
                }
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

        /**
         * A brass ribbon across a spine: the band, a highlight along its top edge and a shadow
         * under it, which is what makes it read as something wrapped over the cover rather than a
         * rectangle filled with gold.
         */
        private fun drawRibbon(canvas: Canvas, rect: Rect) {
            val w = rect.width().toFloat()
            val h = rect.height().toFloat()
            val left = rect.left + w * 0.05f
            val right = rect.right - w * 0.05f
            val top = rect.top + h * 0.42f
            val bottom = rect.top + h * 0.56f

            val band = Paint().apply { color = 0xFFA8802F.toInt() }
            canvas.drawRect(left, top, right, bottom, band)
            val lit = Paint().apply { color = 0x88FFE6B4.toInt() }
            canvas.drawRect(left, top, right, top + h * 0.02f, lit)
            val shade = Paint().apply { color = 0x66000000.toInt() }
            canvas.drawRect(left, bottom - h * 0.02f, right, bottom, shade)
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
