package com.lantern.library.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
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
import com.lantern.library.data.Chapter
import com.lantern.library.data.Paginator
import com.lantern.library.data.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * Pages for a book, measured against the real page box — computed off the main thread.
 *
 * Two problems are solved here, and both of them were visible to the reader as "the EPUB is slow
 * and then it janks":
 *
 * 1. **Cost.** The old paginator re-measured the whole growing page every time a paragraph was
 *    added — quadratic in the number of paragraphs. This measures every paragraph exactly once,
 *    adds up the heights, and starts a new page when the next one will not fit. Only a paragraph
 *    taller than a whole page costs more, and that one paragraph is split by binary search.
 * 2. **Where it runs.** It used to run inside composition, on the main thread, so opening a long
 *    book froze the first frame of the reader. It now runs on [Dispatchers.Default] and the screen
 *    shows "Preparing…" until it lands, while the previous page list keeps being drawn if there is
 *    one. Measuring text off the main thread is safe: `TextMeasurer` has no UI state.
 *
 * The returned [State] is `null` while the first pass for the current inputs has not finished.
 */
@Composable
fun rememberMeasuredPages(
    chapters: List<Chapter>,
    fallback: String,
    family: FontFamily,
    fontSizeSp: Float,
    pageBox: IntSize
): State<List<ReaderPage>?> {
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

    // The page box arrives from the pager's own layout. Until it does, the window size stands in,
    // so the first pass is already close and the "Preparing…" flash is short.
    return produceState<List<ReaderPage>?>(
        initialValue = null,
        chapters, fallback, body, heading, pageBox, density, measurer
    ) {
        val textWidth = pageBox.width - with(density) { (PagePadHorizontal * 2).roundToPx() }
        val textHeight = pageBox.height - with(density) { (PagePadTop + PagePadBottom).roundToPx() }
        if (textWidth < 80 || textHeight < 80) {
            value = Paginator.pages(chapters, fontSizeSp, fallback)
            return@produceState
        }
        val gap = with(density) { PageTitleGap.roundToPx() }
        // The page draws its paragraphs inside one Text, separated by a blank line, so the space
        // between two paragraphs is exactly one line height — not an arbitrary constant. Measuring
        // it here is what keeps the last paragraph of a page from being cut off.
        val lineGap = with(density) { (fontSizeSp * 1.55f).sp.roundToPx() }
        value = withContext(Dispatchers.Default) {
            paginate(
                measurer = measurer,
                chapters = chapters,
                fallback = fallback,
                body = body,
                heading = heading,
                widthPx = textWidth,
                heightPx = textHeight,
                titleGapPx = gap,
                lineGapPx = lineGap
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
    titleGapPx: Int,
    lineGapPx: Int
): List<ReaderPage> {
    val out = mutableListOf<ReaderPage>()
    val source = chapters.ifEmpty { listOf(Chapter("Book", fallback)) }

    source.forEachIndexed { chapterIndex, chapter ->
        val title = chapter.title.ifBlank { "Chapter ${chapterIndex + 1}" }
        val paragraphs = chapter.body
            .split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (paragraphs.isEmpty()) {
            out += ReaderPage(title, chapterIndex, emptyList(), true)
            return@forEachIndexed
        }

        var pageNumber = 0
        val pending = ArrayDeque(paragraphs)
        while (pending.isNotEmpty()) {
            val isFirst = pageNumber == 0
            val room = heightPx - if (isFirst) {
                measureHeight(measurer, heading, title, widthPx) + titleGapPx
            } else {
                0
            }
            val page = mutableListOf<String>()
            // One measurement per paragraph, accumulated — not one per candidate page.
            var used = 0
            while (pending.isNotEmpty()) {
                val next = pending.first()
                val gap = if (page.isEmpty()) 0 else lineGapPx
                val height = measureHeight(measurer, body, next, widthPx)
                if (page.isNotEmpty() && used + gap + height > room) break
                if (page.isEmpty() && height > room) break
                page += pending.removeFirst()
                used += gap + height
            }

            if (page.isEmpty()) {
                // A single paragraph taller than the page: take the longest prefix that fits and
                // carry the rest over, so text is continued rather than dropped.
                val paragraph = pending.removeFirst()
                val best = longestFitting(paragraph, widthPx, room, measurer, body)
                if (best <= 0) {
                    // Degenerate layout (huge font, tiny window). Show the rest of the chapter
                    // rather than looping forever or losing it.
                    page += paragraph
                    page += pending.toList()
                    pending.clear()
                } else {
                    var cut = best
                    if (cut < paragraph.length) {
                        val space = paragraph.lastIndexOf(' ', cut)
                        if (space > cut / 2) cut = space
                    }
                    val head = paragraph.substring(0, cut).trim()
                    val rest = paragraph.substring(cut).trim()
                    if (head.isEmpty()) {
                        page += paragraph
                        page += pending.toList()
                        pending.clear()
                    } else {
                        page += head
                        if (rest.isNotEmpty()) pending.addFirst(rest)
                    }
                }
            }

            if (page.isEmpty()) break
            out += ReaderPage(title, chapterIndex, page.toList(), isFirst)
            pageNumber++
        }
    }

    if (out.isEmpty()) out += ReaderPage("Book", 0, listOf(fallback), true)
    return out
}

/** Height the text will occupy when drawn at this width, measured by the engine that draws it. */
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
 * The largest prefix of [paragraph] that still fits, found by binary search: height only grows as
 * the prefix grows, so the predicate is monotonic.
 */
private fun longestFitting(
    paragraph: String,
    widthPx: Int,
    room: Int,
    measurer: TextMeasurer,
    body: TextStyle
): Int {
    if (room <= 0) return 0
    var low = 1
    var high = paragraph.length
    var best = 0
    while (low <= high) {
        val mid = (low + high) / 2
        val height = measureHeight(measurer, body, paragraph.substring(0, mid), widthPx)
        if (height <= room) {
            best = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return best
}
