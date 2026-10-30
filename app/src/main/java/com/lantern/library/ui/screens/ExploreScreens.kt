package com.lantern.library.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.SystemBars
import com.lantern.library.data.DiscoveryBook
import com.lantern.library.data.Elsewhere
import com.lantern.library.data.FreeEdition
import com.lantern.library.data.SearchOutcome
import com.lantern.library.data.SourceReport
import com.lantern.library.data.GoogleBooks
import com.lantern.library.data.OpenCatalogs
import com.lantern.library.data.ReaderTheme
import com.lantern.library.ui.components.CoverFace
import com.lantern.library.ui.components.GlassCard
import com.lantern.library.ui.components.NookBackdrop
import com.lantern.library.ui.theme.Playfair
import com.lantern.library.ui.theme.nookPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

data class ScrapedBookItem(
    val title: String,
    val detailUrl: String,
    val coverUrl: String
)

suspend fun fetchScrapedBooks(query: String): List<ScrapedBookItem> = withContext(Dispatchers.IO) {
    val searchUrl = "https://oceanofpdf.com/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
    val bookList = mutableListOf<ScrapedBookItem>()

    try {
        val response = Jsoup.connect(searchUrl)
            .userAgent("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
            .referrer("https://oceanofpdf.com/")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .timeout(15000)
            .ignoreHttpErrors(true)
            .execute()

        if (response.statusCode() == 200) {
            val doc = response.parse()
            val articles = doc.select("article, .post, .search-result, .masonry-brick, .type-post")
            for (article in articles) {
                val titleTag = article.selectFirst("h2.entry-title a, h3 a, .title a, a[rel=bookmark]")
                    ?: article.selectFirst("a[href*=/]") ?: continue
                val imgTag = article.selectFirst("img")

                val title = titleTag.text().trim()
                val detailUrl = titleTag.attr("href")
                val coverUrl = when {
                    imgTag?.hasAttr("data-src") == true -> imgTag.attr("data-src")
                    imgTag?.hasAttr("data-lazy-src") == true -> imgTag.attr("data-lazy-src")
                    else -> imgTag?.attr("src") ?: ""
                }

                if (title.isNotEmpty() && detailUrl.isNotEmpty()) {
                    if (bookList.none { it.detailUrl == detailUrl }) {
                        bookList.add(ScrapedBookItem(title, detailUrl, coverUrl))
                    }
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    return@withContext bookList
}

/** One search's worth of results, kept together so the three answers stay distinguishable. */
private data class SearchBundle(
    val editions: List<FreeEdition>,
    val elsewhere: List<Elsewhere>,
    val google: List<com.lantern.library.data.DiscoveryBook>,
    val sources: List<SourceReport>,
    val everythingFailed: Boolean
)

/**
 * Explore: search the internet for a book, and take it if it can legally be taken.
 *
 * Every search asks four catalogues at once and shows three separate answers, because they answer
 * three different questions and blurring them is what made this screen feel broken:
 *
 * 1. **Download free** — an actual file this app can fetch and open. Project Gutenberg (via
 *    Gutendex), open Internet Archive items, openly-scanned Open Library records, and OpenAlex's
 *    index of open-access books. The last one is how a book published this year can appear here.
 * 2. **Read or borrow** — where an in-copyright book can be read without this app holding a copy:
 *    the Internet Archive's one-copy lending, the Open Library record, the publisher's page. A 2019
 *    novel is not downloadable and pretending otherwise wastes the reader's time.
 * 3. **More books** — Google Books metadata: covers, descriptions, shop links. Tapping one opens the
 *    same details sheet as an editor's pick.
 *
 * Z-Library, Anna's Archive, Library Genesis and Ocean of PDF are deliberately absent. They host
 * in-copyright books without permission; scraping them is not something this app ships. The borrow
 * links above exist so that "in copyright" is an answer and not a dead end. The scraper function
 * the owner added to this file is kept as written but is not wired in, for that reason.
 */
@Composable
fun SearchScreen(
    theme: ReaderTheme,
    fetchingId: String? = null,
    onOpen: (DiscoveryBook) -> Unit,
    onDownload: (FreeEdition) -> Unit,
    onOpenLink: (String) -> Unit = {}
) {
    val dark = theme == ReaderTheme.DARK
    val palette = nookPalette(dark)
    val ink = palette.ink
    val mute = palette.mute
    var query by remember { mutableStateOf("") }
    var editions by remember { mutableStateOf<List<FreeEdition>>(emptyList()) }
    var elsewhere by remember { mutableStateOf<List<Elsewhere>>(emptyList()) }
    var results by remember { mutableStateOf<List<DiscoveryBook>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    // What each catalogue said, including the ones that did not answer. An empty screen and a
    // silent host look identical without this.
    var reports by remember { mutableStateOf<List<SourceReport>>(emptyList()) }
    var allFailed by remember { mutableStateOf(false) }
    var googleDown by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("lore_search", android.content.Context.MODE_PRIVATE) }
    var history by remember {
        mutableStateOf(prefs.getString("history", "").orEmpty().split("\n").filter { it.isNotBlank() }.take(8))
    }
    // Suggestions, not a substitute for searching: these are the terms the shelves over-ask for.
    val trending = remember {
        listOf("Pride and Prejudice", "Dracula", "Sherlock Holmes", "Alice in Wonderland",
            "Frankenstein", "romance", "werewolf", "fantasy")
    }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            editions = emptyList()
            elsewhere = emptyList()
            results = emptyList()
            loading = false
            searched = false
            return@LaunchedEffect
        }
        // Typing a title should not shell out to four catalogues per keystroke. The effect is
        // cancelled and restarted on the next character, so this is a debounce, not a delay in
        // front of the search.
        kotlinx.coroutines.delay(400)
        loading = true
        // All three lookups run off the main thread and each one is independent: a catalogue being
        // down costs its own results and nothing else.
        val found = withContext(Dispatchers.IO) {
            val outcome = runCatching { OpenCatalogs.searchDetailed(q) }.getOrNull()
                ?: SearchOutcome(
                    emptyList(), emptyList(),
                    listOf(SourceReport("Every catalogue", false, "the search could not be started"))
                )
            val google = runCatching { GoogleBooks.volumes(q, "relevance", 12) }
            SearchBundle(
                outcome.editions,
                outcome.elsewhere,
                google.getOrDefault(emptyList()),
                outcome.sources +
                    SourceReport(
                        "Google Books",
                        ok = google.isSuccess && google.getOrDefault(emptyList()).isNotEmpty(),
                        detail = when {
                            google.isFailure -> "did not answer"
                            google.getOrDefault(emptyList()).isEmpty() -> "answered, nothing for this title"
                            else -> "${google.getOrDefault(emptyList()).size} record(s)"
                        }
                    ),
                outcome.everythingFailed
            )
        }
        editions = found.editions
        elsewhere = found.elsewhere
        results = found.google
        reports = found.sources
        allFailed = found.everythingFailed
        googleDown = found.sources.any { !it.ok && it.source == "Google Books" }
        loading = false
        searched = true
        if (editions.isNotEmpty() || elsewhere.isNotEmpty() || results.isNotEmpty()) {
            history = (listOf(q) + history.filterNot { it.equals(q, true) }).take(8)
            prefs.edit().putString("history", history.joinToString("\n")).apply()
        }
    }

    NookBackdrop(nookPalette(theme)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(18.dp, 18.dp, 18.dp, SystemBars.CONTENT_BOTTOM_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(span = { GridItemSpan(2) }) {
                Column {
                    Text("Explore", color = ink, fontFamily = Playfair, fontSize = 30.sp)
                    Text(
                        "Free editions download to your shelf. Books still in copyright can be borrowed.",
                        color = mute,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    GlassCard(Modifier.fillMaxWidth(), dark, 18) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Search, null, tint = mute, modifier = Modifier.size(20.dp))
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                textStyle = TextStyle(color = ink, fontSize = 15.sp),
                                cursorBrush = SolidColor(palette.accent),
                                modifier = Modifier.weight(1f).padding(start = 8.dp),
                                decorationBox = { inner ->
                                    if (query.isEmpty()) {
                                        Text("Title or author…", color = mute, fontSize = 15.sp)
                                    }
                                    inner()
                                }
                            )
                        }
                    }

                    if (query.trim().length < 2) {
                        Spacer(Modifier.height(16.dp))
                        Text("TRY", color = mute, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            trending.forEach { term ->
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(if (dark) Color(0xFF35404F) else Color(0xCCFFFFFF))
                                        .clickable { query = term }
                                        .padding(horizontal = 13.dp, vertical = 10.dp)
                                ) {
                                    Text(term, color = ink, fontSize = 12.sp, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                        if (history.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            Text("RECENT", color = mute, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                history.forEach { term ->
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(18.dp))
                                            .background(if (dark) Color(0xFF35404F) else Color(0xCCFFFFFF))
                                            .clickable { query = term }
                                            .padding(horizontal = 13.dp, vertical = 9.dp)
                                    ) {
                                        Text(term, color = ink, fontSize = 12.sp, maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        SourcesNote(palette)
                    }

                    if (loading) {
                        Text(
                            "Searching four catalogues…",
                            color = mute,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                    if (searched && !loading && editions.isEmpty() && elsewhere.isEmpty() && results.isEmpty()) {
                        Column(Modifier.padding(top = 12.dp)) {
                            Text(
                                if (allFailed) "The catalogues could not be reached."
                                else "Nothing found for “${query.trim()}”.",
                                color = ink,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                if (allFailed) {
                                    "This is a problem with the connection or the services, not with the " +
                                        "book: nothing was actually searched. Try again in a moment."
                                } else {
                                    "Try fewer words, or the author's surname. Older and public-domain books " +
                                        "are the ones the free catalogues can usually supply."
                                },
                                color = mute,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                    // Which services answered, and which did not. A skipped source says so too: it
                    // was not asked, so its silence is not evidence about the book.
                    val quiet = reports.filter { !it.ok }
                    if (searched && !loading && quiet.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        GlassCard(Modifier.fillMaxWidth(), dark, 16) {
                            Column(Modifier.padding(14.dp)) {
                                Text(
                                    if (quiet.size == 1) "ONE SOURCE DID NOT ANSWER" else
                                        "${quiet.size} SOURCES DID NOT ANSWER",
                                    color = palette.accent,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.4.sp
                                )
                                Spacer(Modifier.height(6.dp))
                                quiet.forEach { report ->
                                    Row(Modifier.padding(bottom = 4.dp)) {
                                        Text("!", color = palette.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text(
                                            "  ${report.source} — ${report.detail}",
                                            color = ink,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                                Text(
                                    "Results below come from the catalogues that did answer; the missing " +
                                        "ones may hold more.",
                                    color = mute,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                    if (editions.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "DOWNLOAD FREE  ·  ${editions.size}",
                            color = mute, fontSize = 11.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            items(editions, key = { it.id }) { edition ->
                EditionCard(
                    edition = edition,
                    dark = dark,
                    busy = fetchingId == edition.id,
                    enabled = fetchingId == null,
                    onDownload = { onDownload(edition) }
                )
            }

            if (elsewhere.isNotEmpty()) {
                item(span = { GridItemSpan(2) }) {
                    Column(Modifier.padding(top = 12.dp)) {
                        Text(
                            "READ OR BORROW  ·  ${elsewhere.size}",
                            color = mute, fontSize = 11.sp, fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Still in copyright, so there is no file to take. These open where the book " +
                                "can legitimately be read.",
                            color = mute, fontSize = 11.sp
                        )
                    }
                }
                // A single full-width cell holding the list: these are rows, not covers, and the
                // count is small enough that laziness would buy nothing.
                item(span = { GridItemSpan(2) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        elsewhere.forEach { link ->
                            ElsewhereCard(link, dark) { onOpenLink(link.url) }
                        }
                    }
                }
            }

            if (results.isNotEmpty()) {
                item(span = { GridItemSpan(2) }) {
                    Column(Modifier.padding(top = 10.dp)) {
                        Text("MORE BOOKS", color = mute, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Covers and descriptions. Tap one for its details.",
                            color = mute, fontSize = 11.sp
                        )
                    }
                }
                items(results, key = { it.volumeId }) { book ->
                    GlassCard(Modifier.fillMaxWidth(), dark, 16) {
                        Column(Modifier.clickable { onOpen(book) }.padding(10.dp)) {
                            CoverFace(
                                book.title, null, book.coverUrl,
                                Modifier.fillMaxWidth().aspectRatio(0.68f), dark, book.coverUrls
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                book.title, color = ink, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                            Text(book.authorLine, color = mute, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (book.publicDomain) "Public-domain edition" else "Book details",
                                color = if (book.publicDomain) palette.accent else mute,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Where all this comes from, said plainly once, at the bottom of an empty search. */
@Composable
private fun SourcesNote(palette: com.lantern.library.ui.theme.NookPalette) {
    GlassCard(Modifier.fillMaxWidth(), palette.dark, 16) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "WHERE BOOKS COME FROM",
                color = palette.mute, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp
            )
            Spacer(Modifier.height(8.dp))
            listOf(
                "Project Gutenberg — 70,000+ public-domain books, EPUB and PDF" to "",
                "Internet Archive — scanned public-domain books, plus free borrowing of in-copyright ones" to "",
                "Open Library — the catalogue behind the Archive: every modern book, at least by record" to "",
                "OpenAlex — academic books published open access, including recent ones" to ""
            ).forEach { (line, _) ->
                Row(Modifier.padding(bottom = 5.dp)) {
                    Text("·", color = palette.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(
                        line,
                        color = palette.ink,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
            Text(
                "Piracy mirrors are not used: they carry in-copyright books without permission, and " +
                    "an app cannot vouch for what it downloads from one.",
                color = palette.faint,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/** One downloadable edition: where it comes from, what it is, and a button that fetches it. */
@Composable
private fun EditionCard(
    edition: FreeEdition,
    dark: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onDownload: () -> Unit
) {
    val palette = nookPalette(dark)
    GlassCard(Modifier.fillMaxWidth(), dark, 16) {
        Column(Modifier.padding(10.dp)) {
            CoverFace(
                edition.title, null, edition.coverUrl,
                Modifier.fillMaxWidth().aspectRatio(0.68f), dark
            )
            Spacer(Modifier.height(8.dp))
            Text(
                edition.title, color = palette.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Text(
                edition.author.ifBlank { "Unknown" }, color = palette.mute, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                "${edition.format.name} · ${edition.source}", color = palette.mute, fontSize = 10.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
            Text(edition.rights, color = palette.faint, fontSize = 10.sp, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (enabled) palette.accent
                        else palette.accent.copy(alpha = 0.45f)
                    )
                    .clickable(enabled = enabled, onClick = onDownload)
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (busy) "Downloading…" else "Download",
                    color = palette.accentInk,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/** One place an in-copyright book can be read: borrow, open the record, or the publisher. */
@Composable
private fun ElsewhereCard(link: Elsewhere, dark: Boolean, onOpen: () -> Unit) {
    val palette = nookPalette(dark)
    GlassCard(Modifier.fillMaxWidth(), dark, 16) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(52.dp).height(72.dp)) {
                CoverFace(
                    link.title, null, link.coverUrl,
                    Modifier.fillMaxSize(), dark
                )
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    link.title, color = palette.ink, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                if (link.author.isNotBlank()) {
                    Text(
                        link.author, color = palette.mute, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "${link.source} · ${link.note}", color = palette.faint, fontSize = 10.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.accent.copy(alpha = 0.16f))
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(link.action, color = palette.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
