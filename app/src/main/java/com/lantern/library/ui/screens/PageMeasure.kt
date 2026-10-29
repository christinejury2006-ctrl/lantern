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
import com.lantern.library.data.EpubMedia
import com.lantern.library.data.Paginator
import com.lantern.library.data.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Collections

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

/** A book's pages, and whether pagination has finished for the current layout. */
data class MeasuredPages(
    val pages: List<ReaderPage>,
    /** False while later chapters are still being measured; the list only ever grows. */
    val complete: Boolean
)

/**
 * Pages already laid out for a book, kept for the session.
 *
 * Reopening a book is the single most common thing a reader does, and measuring a long novel takes
 * seconds — which is what "the EPUB takes too long to load" was: every open re-measured the whole
 * book from scratch. A handful of entries is enough (the book they are reading and the one they
 * just left), and an eviction only costs a re-measure, never correctness: the key includes the
 * font, the size and the exact page box, so a stale layout can never be served for a new one.
 */
internal object PageCache {
    private const val MAX = 6
    private val entries: MutableMap<String, List<ReaderPage>> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, List<ReaderPage>>(8, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ReaderPage>>) =
                    size > MAX
            }
        )

    fun key(
        bookId: String?,
        filePath: String?,
        family: String,
        fontSizeSp: Float,
        width: Int,
        height: Int
    ): String = listOf(bookId ?: "-", filePath ?: "-", family, fontSizeSp, width, height).joinToString("|")

    fun get(key: String): List<ReaderPage>? = entries[key]

    fun put(key: String, pages: List<ReaderPage>) {
        entries[key] = pages
    }

    /** Drops a book's pages, e.g. after its file changed. */
    fun clear(bookId: String) {
        synchronized(entries) { entries.keys.removeAll { it.startsWith("$bookId|") } }
    }
}

/**
 * Pages for a book, measured against the real page box — computed off the main thread.
 *
 * Three problems are solved here, and all of them were visible to the reader as "the EPUB is slow
 * and then it janks":
 *
 * 1. **Cost.** The old paginator re-measured the whole growing page every time a paragraph was
 *    added — quadratic in the number of paragraphs. This measures every paragraph exactly once,
 *    adds up the heights, and starts a new page when the next one will not fit. Only a paragraph
 *    taller than a whole page costs more, and that one paragraph is split by binary search.
 * 2. **Where it runs.** It used to run inside composition, on the main thread, so opening a long
 *    book froze the first frame of the reader. It now runs on [Dispatchers.Default].
 * 3. **When the pages appear.** The first chapter's pages are published as soon as they are
 *    measured, so a long book starts reading immediately and the rest arrives behind it. The list
 *    only ever grows; [MeasuredPages.complete] says when it has stopped growing.
 *
 * Measuring text off the main thread is safe: `TextMeasurer` has no UI state.
 */
@Composable
fun rememberMeasuredPages(
    chapters: List<Chapter>,
    fallback: String,
    family: FontFamily,
    fontSizeSp: Float,
    pageBox: IntSize,
    cacheKey: String? = null
): State<MeasuredPages?> {
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
    return produceState<MeasuredPages?>(
        initialValue = null,
        chapters, fallback, body, heading, pageBox, density, measurer, cacheKey
    ) {
        val textWidth = pageBox.width - with(density) { (PagePadHorizontal * 2).roundToPx() }
        val textHeight = pageBox.height - with(density) { (PagePadTop + PagePadBottom).roundToPx() }
        if (textWidth < 80 || textHeight < 80) {
            value = MeasuredPages(Paginator.pages(chapters, fontSizeSp, fallback), complete = true)
            return@produceState
        }
        val gap = with(density) { PageTitleGap.roundToPx() }
        // The page draws its paragraphs inside one Text, separated by a blank line, so the space
        // between two paragraphs is exactly one line height — not an arbitrary constant. Measuring
        // it here is what keeps the last paragraph of a page from being cut off.
        val lineGap = with(density) { (fontSizeSp * 1.55f).sp.roundToPx() }
        val key = cacheKey?.let { "$it|$textWidth|$textHeight" }

        if (key != null) PageCache.get(key)?.let {
            value = MeasuredPages(it, complete = true)
            return@produceState
        }

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
                lineGapPx = lineGap,
                onPartial = { partial ->
                    // Published as it goes, so the first chapter is readable while the rest of
                    // the book is still being laid out.
                    withContext(Dispatchers.Main.immediate) { value = MeasuredPages(partial, false) }
                }
            )
        }.let { result ->
            if (key != null) PageCache.put(key, result)
            MeasuredPages(result, complete = true)
        }
    }
}

private suspend fun paginate(
    measurer: TextMeasurer,
    chapters: List<Chapter>,
    fallback: String,
    body: TextStyle,
    heading: TextStyle,
    widthPx: Int,
    heightPx: Int,
    titleGapPx: Int,
    lineGapPx: Int,
    onPartial: suspend (List<ReaderPage>) -> Unit
): List<ReaderPage> {
    val out = mutableListOf<ReaderPage>()
    val source = chapters.ifEmpty { listOf(Chapter("Book", fallback)) }

    for ((chapterIndex, chapter) in source.withIndex()) {
        val title = chapter.title.ifBlank { "Chapter ${chapterIndex + 1}" }
        val paragraphs = chapter.body
            .split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (paragraphs.isEmpty()) {
            out += ReaderPage(title, chapterIndex, emptyList(), true)
            currentCoroutineContext().ensureActive()
            continue
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
                // A picture is sized to the page it lands on; prose is measured as text.
                val picture = EpubMedia.parse(next)
                val drawn = if (picture != null) {
                    val fit = EpubMedia.fit(
                        picture.width, picture.height,
                        widthPx, (room - used - gap).coerceAtLeast(heightPx / 3)
                    )
                    EpubMedia.marker(picture.path, fit.first, fit.second)
                } else next
                val height = if (picture != null) {
                    EpubMedia.parse(drawn)?.height ?: 0
                } else {
                    measureHeight(measurer, body, next, widthPx)
                }
                if (page.isNotEmpty() && used + gap + height > room) break
                if (page.isEmpty() && height > room) break
                pending.removeFirst()
                page += if (picture != null) drawn else next
                used += gap + height
            }

            if (page.isEmpty()) {
                val paragraph = pending.first()
                if (EpubMedia.isPicture(paragraph)) {
                    // A picture on a page of its own: fit it to the whole page and move on. It is
                    // never split, so this always makes progress.
                    val drawn = EpubMedia.parse(paragraph)?.let { p ->
                        val fit = EpubMedia.fit(p.width, p.height, widthPx, room.coerceAtLeast(1))
                        EpubMedia.marker(p.path, fit.first, fit.second)
                    } ?: paragraph
                    pending.removeFirst()
                    page += drawn
                } else {
                    // A single paragraph taller than the page: take the longest prefix that fits
                    // and carry the rest over, so text is continued rather than dropped.
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
                            pending.removeFirst()
                            page += head
                            if (rest.isNotEmpty()) pending.addFirst(rest)
                        }
                    }
                }
            }

            if (page.isEmpty()) break
            out += ReaderPage(title, chapterIndex, page.toList(), isFirst)
            pageNumber++
        }
        currentCoroutineContext().ensureActive()
        if (out.isNotEmpty()) onPartial(out.toList())
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
