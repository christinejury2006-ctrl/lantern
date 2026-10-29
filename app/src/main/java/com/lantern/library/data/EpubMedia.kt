package com.lantern.library.data

import java.io.File

/**
 * A picture inside a book's text, and the way it travels through pagination.
 *
 * EPUB chapters are plain text once the markup is stripped, but a picture book — Diary of a Wimpy
 * Kid, a children's reader, almost anything illustrated — is mostly pictures. Stripping the images
 * leaves empty chapters, which is exactly what the reader saw: a book that opened to a few odd
 * lines of text and nothing else.
 *
 * Rather than widen `Chapter` and `ReaderPage` into a block model (which would touch every caller
 * of both), a picture is represented as one paragraph of the chapter, in the position in the text
 * where it appears. The paragraph is a marker:
 *
 * ```
 * \u0001IMG\u0001<absolute file path>\u0001<width px>\u0001<height px>
 * ```
 *
 * Everything that already works on paragraphs — pagination, page splitting, the measure cache —
 * keeps working, because a marker is just a string that happens to be shorter than a word. The
 * paginator recognises it and measures it by its *picture* height rather than its text height, and
 * the page draws it as an image. The marker carries the *display* size that pagination chose, so
 * what was measured and what is drawn are the same rectangle and a picture can never spill over the
 * bottom of its page.
 */
object EpubMedia {
    /** Start of a marker paragraph. A leading control character cannot occur in real book text. */
    const val MARK = "\u0001IMG\u0001"

    private const val SEP = "\u0001"

    data class Picture(val path: String, val width: Int, val height: Int) {
        /** The marker paragraph for this picture at the given display size. */
        fun marker(): String = MARK + path + SEP + width + SEP + height
    }

    fun marker(path: String, width: Int, height: Int): String = Picture(path, width, height).marker()

    /** True when [paragraph] is a picture rather than prose. */
    fun isPicture(paragraph: String): Boolean = paragraph.startsWith(MARK)

    /** Reads a marker paragraph back. Returns null for ordinary text. */
    fun parse(paragraph: String): Picture? {
        if (!paragraph.startsWith(MARK)) return null
        val rest = paragraph.removePrefix(MARK)
        val parts = rest.split(SEP)
        if (parts.size < 3) return null
        val width = parts[parts.size - 2].toIntOrNull() ?: return null
        val height = parts[parts.size - 1].toIntOrNull() ?: return null
        // The path itself may in principle contain the separator, so rebuild it from the middle.
        val path = parts.subList(0, parts.size - 2).joinToString(SEP)
        if (path.isBlank() || width <= 0 || height <= 0) return null
        if (!File(path).exists()) return null
        return Picture(path, width, height)
    }

    /**
     * The size a picture should be drawn at on a page of [boxWidth] × [boxHeight].
     *
     * It fills the width of the text column, and is scaled down — never up past its own pixels —
     * if that would make it taller than the page. The result is what both the paginator and the
     * page use, which is why a full-page illustration lands on a page of its own instead of being
     * chopped in half.
     */
    fun fit(
        intrinsicWidth: Int,
        intrinsicHeight: Int,
        boxWidth: Int,
        boxHeight: Int
    ): Pair<Int, Int> {
        val w = intrinsicWidth.coerceAtLeast(1)
        val h = intrinsicHeight.coerceAtLeast(1)
        var outW = boxWidth.coerceAtMost(w)
        var outH = (h.toLong() * outW / w).toInt().coerceAtLeast(1)
        if (outH > boxHeight) {
            outH = boxHeight
            outW = (w.toLong() * outH / h).toInt().coerceAtLeast(1)
        }
        return outW to outH
    }
}
