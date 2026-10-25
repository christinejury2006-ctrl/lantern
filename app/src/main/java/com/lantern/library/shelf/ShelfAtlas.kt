package com.lantern.library.shelf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/** What the atlas needs to know about a book to paint its tile. */
data class BookArt(
    val title: String,
    val author: String,
    val seed: Int
)

/**
 * Paints the spine art for the whole shelf into one texture.
 *
 * Each book gets a cell holding three things: the spine (title reading down it, like a real
 * book), the front cover, and the page block. The box mesh samples the region it needs for
 * each face, so one texture and one material instance per book covers the whole book.
 *
 * Painting this on the CPU with Canvas is deliberate: the type is drawn at a known size with
 * the platform's own font stack, and no text-rendering shader work ends up in the frame loop.
 */
object ShelfAtlas {

    /** Pixels across the atlas. 64 cells of 256px, enough for a legible spine per book. */
    const val SIZE = 2048

    private val cloth = intArrayOf(
        0xFF8C3B32.toInt(), // oxblood
        0xFF2F4A45.toInt(), // bottle green
        0xFF33465E.toInt(), // midnight blue
        0xFF7A5726.toInt(), // antique gold
        0xFF5B3A56.toInt(), // plum
        0xFF3E4F3A.toInt(), // fern
        0xFF8A6234.toInt(), // tan leather
        0xFF53333A.toInt(), // maroon
        0xFF2E5057.toInt(), // teal
        0xFF6B4A2F.toInt()  // walnut
    )

    private val foil = Color.parseColor("#E8D3A9")
    private val pageEdge = Color.parseColor("#EADFC6")
    private val pageLine = Color.parseColor("#CDBC9C")

    /** Paints [books] into a fresh atlas bitmap. Call on a background thread. */
    fun paint(books: List<BookArt>): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#2B1F17"))
        val cell = SIZE / ShelfLayout.ATLAS_COLS
        for (i in 0 until ShelfLayout.CELLS) {
            val col = i % ShelfLayout.ATLAS_COLS
            val row = i / ShelfLayout.ATLAS_COLS
            val art = books.getOrNull(i)
            paintCell(canvas, col * cell, row * cell, cell, art, i)
        }
        return bmp
    }

    private fun paintCell(canvas: Canvas, x: Int, y: Int, cell: Int, art: BookArt?, index: Int) {
        val seed = art?.seed ?: index
        val base = cloth[((seed % cloth.size) + cloth.size) % cloth.size]
        val spineW = (cell * ShelfLayout.SPINE_U1).toInt()
        val coverTop = y
        val coverBottom = y + (cell * ShelfLayout.COVER_V1).toInt()

        // ---- spine: full height of the cell, left column ----
        val spine = RectF(x.toFloat(), y.toFloat(), (x + spineW).toFloat(), (y + cell).toFloat())
        canvas.drawRect(spine, fill(base))
        // darker leather at the head and tail, like a bound book
        canvas.drawRect(RectF(spine.left, spine.top, spine.right, spine.top + cell * 0.07f), fill(darken(base, 0.72f)))
        canvas.drawRect(RectF(spine.left, spine.bottom - cell * 0.07f, spine.right, spine.bottom), fill(darken(base, 0.72f)))
        // gilt rules
        canvas.drawRect(RectF(spine.left + 3f, spine.top + cell * 0.10f, spine.right - 3f, spine.top + cell * 0.115f), fill(foil))
        canvas.drawRect(RectF(spine.left + 3f, spine.bottom - cell * 0.115f, spine.right - 3f, spine.bottom - cell * 0.10f), fill(foil))

        val title = art?.title?.trim().orEmpty()
        if (title.isNotEmpty()) {
            val paint = textPaint(foil, cell * 0.085f)
            canvas.save()
            canvas.translate((x + spineW / 2f), (y + cell / 2f))
            // +x now runs down the spine, so the title reads top to bottom as it should
            canvas.rotate(90f)
            val fitted = fit(paint, title, cell * 0.80f)
            val w = paint.measureText(fitted)
            val fm = paint.fontMetrics
            canvas.drawText(fitted, -w / 2f, -(fm.ascent + fm.descent) / 2f, paint)
            canvas.restore()
        }
        if (art != null && art.author.isNotBlank()) {
            val paint = textPaint(darken(foil, 0.82f), cell * 0.045f)
            canvas.save()
            canvas.translate((x + spineW * 0.82f), (y + cell * 0.52f))
            canvas.rotate(90f)
            val fitted = fit(paint, art.author, cell * 0.34f)
            canvas.drawText(fitted, 0f, 0f, paint)
            canvas.restore()
        }

        // ---- cover: right of the spine, upper part of the cell ----
        val cover = RectF((x + spineW).toFloat(), coverTop.toFloat(), (x + cell).toFloat(), coverBottom.toFloat())
        val coverColor = darken(base, 0.88f)
        canvas.drawRect(cover, fill(coverColor))
        val inset = cell * 0.03f
        canvas.drawRect(
            RectF(cover.left + inset, cover.top + inset, cover.right - inset, cover.bottom - inset),
            stroke(foil, cell * 0.006f)
        )
        if (title.isNotEmpty()) {
            val paint = textPaint(foil, cell * 0.055f)
            val maxW = cover.width() - inset * 3f
            val fitted = fit(paint, title, maxW)
            canvas.drawText(fitted, cover.left + inset * 1.6f, cover.top + cover.height() * 0.30f, paint)
            canvas.drawRect(
                RectF(cover.left + inset * 1.6f, cover.top + cover.height() * 0.36f,
                    cover.left + inset * 1.6f + cell * 0.18f, cover.top + cover.height() * 0.36f + cell * 0.006f),
                fill(foil)
            )
        }

        // ---- page block: right of the spine, lower part of the cell ----
        val pages = RectF((x + spineW).toFloat(), coverBottom.toFloat(), (x + cell).toFloat(), (y + cell).toFloat())
        canvas.drawRect(pages, fill(pageEdge))
        val line = stroke(pageLine, 1.2f)
        var gy = pages.top + 4f
        while (gy < pages.bottom) {
            canvas.drawLine(pages.left, gy, pages.right, gy, line)
            gy += 5f
        }
    }

    private fun fill(color: Int): Paint = Paint().apply {
        this.color = color
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private fun stroke(color: Int, width: Float): Paint = Paint().apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
        isAntiAlias = true
    }

    private var serif: Typeface? = null

    private fun textPaint(color: Int, size: Float): Paint = Paint().apply {
        this.color = color
        textSize = size
        isAntiAlias = true
        typeface = serif ?: Typeface.create("serif", Typeface.BOLD).also { serif = it }
        letterSpacing = 0.02f
    }

    /** Shortens [text] with an ellipsis until it measures inside [maxWidth]. */
    private fun fit(paint: Paint, text: String, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1) {
            end--
            val candidate = text.substring(0, end).trimEnd() + "\u2026"
            if (paint.measureText(candidate) <= maxWidth) return candidate
        }
        return "\u2026"
    }

    private fun darken(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255)
    )
}
