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
import com.lantern.library.data.DiscoveryBook
import com.lantern.library.data.FreeEdition
import com.lantern.library.data.GoogleBooks
import com.lantern.library.data.OpenCatalogs
import com.lantern.library.data.ReaderTheme
import com.lantern.library.ui.components.CoverFace
import com.lantern.library.ui.components.GlassCard
import com.lantern.library.ui.components.NookBackdrop
import com.lantern.library.ui.theme.Playfair
import com.lantern.library.ui.theme.nookPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Explore: search the internet for a book, and take the book if it can legally be taken.
 *
 * Two things happen on every search, and they answer different questions:
 *
 * 1. **Open catalogues** ([OpenCatalogs]) are asked whether a free file exists. Project Gutenberg
 *    and the Internet Archive both publish downloadable public-domain editions, and a hit here
 *    turns straight into a book on the shelf: the file is downloaded, validated, given a cover and
 *    a page count, and the reader can open it without ever seeing an import dialog.
 * 2. **Google Books** is asked what the book *is* — description, cover, store links — for titles
 *    that are not free. Those results open the details sheet, where the reader can save the book
 *    to Want to read or follow the store's page.
 *
 * Z-Library and the various "ocean of PDF" mirrors are deliberately absent. They host
 * in-copyright books without permission; scraping them is not something this app does, and there
 * is no hidden setting that turns it on. The label under each downloadable card says where the
 * file came from and what its rights are, because the reader deserves to know.
 */
@Composable
fun SearchScreen(
    theme: ReaderTheme,
    fetchingId: String? = null,
    onOpen: (DiscoveryBook) -> Unit,
    onDownload: (FreeEdition) -> Unit
) {
    val dark = theme == ReaderTheme.DARK
    val palette = nookPalette(dark)
    val ink = palette.ink
    val mute = palette.mute
    var query by remember { mutableStateOf("") }
    var editions by remember { mutableStateOf<List<FreeEdition>>(emptyList()) }
    var results by remember { mutableStateOf<List<DiscoveryBook>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
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
            results = emptyList()
            loading = false
            searched = false
            return@LaunchedEffect
        }
        delay(350)
        loading = true
        // Both lookups run off the main thread; the free-file lookup is the slower of the two
        // because the Archive may need a second request per item to resolve a filename.
        val free = withContext(Dispatchers.IO) { runCatching { OpenCatalogs.find(q) }.getOrDefault(emptyList()) }
        editions = free
        val google = withContext(Dispatchers.IO) {
            runCatching { GoogleBooks.volumes(q, "relevance", 24) }.getOrDefault(emptyList())
        }
        results = google
        loading = false
        searched = true
        if (free.isNotEmpty() || google.isNotEmpty()) {
            history = (listOf(q) + history.filterNot { it.equals(q, true) }).take(8)
            prefs.edit().putString("history", history.joinToString("\n")).apply()
        }
    }

    NookBackdrop(nookPalette(theme)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(18.dp, 18.dp, 18.dp, 96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(span = { GridItemSpan(2) }) {
                Column {
                    Text("Explore", color = ink, fontFamily = Playfair, fontSize = 30.sp)
                    Text(
                        "Find a book. Free editions download straight to your shelf.",
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
                        Text(
                            "Free files come from Project Gutenberg and the Internet Archive. " +
                                "Everything else links to the shop that sells it.",
                            color = mute,
                            fontSize = 11.sp
                        )
                    }

                    if (loading) {
                        Text("Searching libraries…", color = mute, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                    }
                    if (searched && editions.isEmpty() && results.isEmpty() && !loading) {
                        Text(
                            "Nothing found. Try fewer words, or the author's surname.",
                            color = mute,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                    if (searched && editions.isEmpty() && !loading) {
                        Text(
                            if (GoogleBooks.isConfigured())
                                "No free edition of this one — it is still in copyright, or nobody has scanned it yet."
                            else "No free edition found. Book details need a Google Books key in this build.",
                            color = mute,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    if (editions.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text("DOWNLOAD FREE", color = mute, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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

            if (results.isNotEmpty()) {
                item(span = { GridItemSpan(2) }) {
                    Column(Modifier.padding(top = 10.dp)) {
                        Text("MORE BOOKS", color = mute, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Details, covers and shop links. Tap one to save it or find a copy.",
                            color = mute,
                            fontSize = 11.sp
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
