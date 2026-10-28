package com.lantern.library.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import com.lantern.library.data.Chapter
import com.lantern.library.data.Paginator
import com.lantern.library.data.ReaderPage

/**
 * The page's own margins, in one place.
 *
 * These are the reader's appearance, so they are defined here and read by both the page that draws
 * the text and the paginator that decides where the text ends — a paginator with its own idea of
 * the margins cuts in the wrong place, and the last line of every page is clipped.
 */
internal val PagePadHorizontal = 26.dp
internal val PagePadTop = 88.dp
internal val PagePadBottom = 80.dp

/** The gap between the chapter heading and the first paragraph. */
internal val PageTitleGap = 20.dp

/**
 * Splits a book into pages that are actually the height of the screen.
 *
 * The previous paginator counted characters: 900 of them per page at 17 sp, whatever the phone,
 * whatever the font. That is a guess, and it is wrong in both directions — on a small screen the
 * page ran out of room and the text was clipped at the bottom, on a large one half the page was
 * empty. Since the reader's whole promise is that a page *is* a page, the breaks are now found by
 * measuring the text with the same style it will be drawn with.
 *
 * The cost is bounded deliberately: the usual case measures once per paragraph as the page fills
 * up, and a paragraph taller than a whole page is the only case that binary-searches for a break
 * point (about eleven measurements, once). Nothing here runs per frame — the result is remembered
 * against the chapter, the font and the page box, so it is recomputed only when one of those
 * changes.
 */
@Composable
fun rememberMeasuredPages(
    chapters: List<Chapter>,
    fallback: String,
    family: FontFamily,
    fontSizeSp: Float,
    pageBox: IntSize
): List<ReaderPage> {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val body = remember(family, fontSizeSp) {
        TextStyle(
            fontFamily = family,
            fontSize = fontSizeSp.sp,
            lineHeight = (fontSizeSp * 1.55f).sp,
            textAlign = TextAlign.Justify
        )
    }
    val heading = remember(family, fontSizeSp) {
        TextStyle(
            fontFamily = family,
            fontSize = (fontSizeSp + 10).sp,
            textAlign = TextAlign.Center
        )
    }
    return remember(chapters, fallback, family, fontSizeSp, pageBox, body, heading, density) {
        val textWidth = pageBox.width - with(density) {
            (PagePadHorizontal * 2).roundToPx()
        }
        val textHeight = pageBox.height - with(density) { (PagePadTop + PagePadBottom).roundToPx() }
        // Before the first layout pass there is no box to measure into. Rather than show a blank
        // page, fall back to the old estimate for that one frame; the measured pass replaces it as
        // soon as the pager reports its size.
        if (textWidth < 80 || textHeight < 80) {
            Paginator.pages(chapters, fontSizeSp, fallback)
        } else {
            paginate(
                measurer = measurer,
                chapters = chapters,
                fallback = fallback,
                body = body,
                heading = heading,
                widthPx = textWidth,
                heightPx = textHeight,
                titleGapPx = with(density) { PageTitleGap.roundToPx() }
            )
        }
    }
}

private fun paginate(
    measurer: TextMeasurer,
    chapters: List<Chapter>,
    fallback: String,
    body: TextStyle,
    heading: TextStyle,
    widthPx: Int,
    heightPx: Int,
    titleGapPx: Int
): List<ReaderPage> {
    val out = mutableListOf<ReaderPage>()
    val source = chapters.ifEmpty { listOf(Chapter("Book", fallback)) }

    source.forEachIndexed { chapterIndex, chapter ->
        val title = chapter.title.ifBlank { "Chapter ${chapterIndex + 1}" }
        val paragraphs = chapter.body
            .split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // A chapter with no body still gets its heading page, so the table of contents and the
        // page indices stay in step.
        if (paragraphs.isEmpty()) {
            out += ReaderPage(title, chapterIndex, emptyList(), true)
            return@forEachIndexed
        }

        var pageNumber = 0
        val pending = ArrayDeque(paragraphs)
        while (pending.isNotEmpty()) {
            val isFirst = pageNumber == 0
            // Only the first page of a chapter carries the heading, and the heading is drawn above
            // the text, so it takes room out of that page's budget.
            val room = heightPx - if (isFirst) {
                measureHeight(measurer, heading, title, widthPx) + titleGapPx
            } else {
                0
            }
            fun fits(candidate: List<String>): Boolean =
                room > 0 && measureHeight(measurer, body, candidate.joinToString("\n\n"), widthPx) <= room

            val page = mutableListOf<String>()
            while (pending.isNotEmpty() && fits(page + pending.first())) {
                page += pending.removeFirst()
            }

            if (page.isEmpty()) {
                // Nothing fitted whole, so the paragraph is longer than the page. Find the longest
                // prefix that does fit and carry the rest onto the next page — text is never
                // dropped, only continued.
                val paragraph = pending.removeFirst()
                val best = longestFitting(paragraph, widthPx, room, measurer, body, ::fits)
                if (best <= 0) {
                    // Degenerate layout (a very large font on a very small window). Show the rest of
                    // the chapter on this page rather than looping forever or losing it.
                    page += paragraph
                    page += pending.toList()
                    pending.clear()
                } else {
                    var cut = best
                    if (cut < paragraph.length) {
                        // Prefer to break between words. Only fall back to a hard cut if the last
                        // space is so far back that it would waste most of the page.
                        val space = paragraph.lastIndexOf(' ', cut)
                        if (space > cut / 2) cut = space
                    }
                    val head = paragraph.substring(0, cut).trim()
                    val rest = paragraph.substring(cut).trim()
                    if (head.isEmpty()) {
                        // The cut landed entirely on whitespace; treat it as the degenerate case.
                        page += paragraph
                        page += pending.toList()
                        pending.clear()
                    } else {
                        page += head
                        if (rest.isNotEmpty()) pending.addFirst(rest)
                    }
                }
            }

            if (page.isNotEmpty()) {
                out += ReaderPage(title, chapterIndex, page.toList(), isFirst)
                pageNumber++
            } else {
                // Belt and braces: never spin without emitting a page.
                break
            }
        }
    }

    if (out.isEmpty()) out += ReaderPage("Book", 0, listOf(fallback), true)
    return out
}

/** Height the text will occupy when drawn at this width, measured by the same engine that draws it. */
private fun measureHeight(
    measurer: TextMeasurer,
    style: TextStyle,
    text: String,
    widthPx: Int
): Int = measurer.measure(
    text = AnnotatedString(text),
    style = style,
    constraints = Constraints(maxWidth = widthPx)
).size.height

/**
 * The largest number of characters from the front of [paragraph] that still fits, found by binary
 * search: height only grows as the prefix grows, so the predicate is monotonic.
 */
private fun longestFitting(
    paragraph: String,
    widthPx: Int,
    room: Int,
    measurer: TextMeasurer,
    body: TextStyle,
    fits: (List<String>) -> Boolean
): Int {
    if (room <= 0) return 0
    var low = 1
    var high = paragraph.length
    var best = 0
    while (low <= high) {
        val mid = (low + high) / 2
        val height = measureHeight(measurer, body, paragraph.substring(0, mid), widthPx)
        if (height <= room && fits(listOf(paragraph.substring(0, mid)))) {
            best = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return best
}
