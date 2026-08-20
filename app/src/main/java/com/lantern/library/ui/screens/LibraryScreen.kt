package com.lantern.library.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.DiscoveryBook
import com.lantern.library.data.LibraryBook
import com.lantern.library.data.ReaderTheme
import com.lantern.library.data.Recommendations
import com.lantern.library.data.Shelf
import com.lantern.library.ui.components.AuroraBackdrop
import com.lantern.library.ui.components.BookCover
import com.lantern.library.ui.components.CoverFace
import com.lantern.library.ui.components.GlassCard
import com.lantern.library.ui.theme.Coral
import com.lantern.library.ui.theme.Ink
import com.lantern.library.ui.theme.InkSoft
import com.lantern.library.ui.theme.NightText
import com.lantern.library.ui.theme.Playfair
import kotlinx.coroutines.delay

private enum class LibFilter { ALL, CURRENT, TO_READ, WANT, FINISHED }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    books: List<LibraryBook>,
    forYou: List<DiscoveryBook>,
    wantToRead: List<DiscoveryBook>,
    theme: ReaderTheme,
    onOpen: (LibraryBook) -> Unit,
    onRemove: (String) -> Unit,
    onImport: () -> Unit,
    onOpenDiscovery: (DiscoveryBook) -> Unit,
    onSaveWant: (DiscoveryBook) -> Unit
) {
    val dark = theme == ReaderTheme.DARK
    val ink = if (dark) NightText else Ink
    val mute = if (dark) Color(0xFFD0D6DE) else InkSoft
    var filter by remember { mutableStateOf(LibFilter.ALL) }
    val shown = when (filter) {
        LibFilter.ALL -> books
        LibFilter.CURRENT -> books.filter { it.shelf == Shelf.CURRENT }
        LibFilter.TO_READ -> books.filter { it.shelf == Shelf.TO_READ }
        LibFilter.FINISHED -> books.filter { it.shelf == Shelf.FINISHED }
        LibFilter.WANT -> emptyList()
    }
    AuroraBackdrop(theme) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(18.dp, 18.dp, 18.dp, 96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(span = { GridItemSpan(3) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("My Library", color = ink, fontFamily = Playfair, fontSize = 30.sp)
                        Text(
                            "${books.size} books  ·  Reading ${books.count { it.shelf == Shelf.CURRENT }}  ·  To read ${books.count { it.shelf == Shelf.TO_READ }}  ·  Want ${wantToRead.size}",
                            color = mute, fontSize = 13.sp
                        )
                    }
                    Box(
                        Modifier.size(42.dp).clip(CircleShape).background(Color(0x99FFFFFF))
                            .combinedClickable(onClick = onImport),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Add, "Import", tint = ink)
                    }
                }
            }
            item(span = { GridItemSpan(3) }) {
                ForYouShelf(
                    books = forYou,
                    wantToRead = wantToRead,
                    dark = dark,
                    ink = ink,
                    mute = mute,
                    onOpen = onOpenDiscovery,
                    onSave = onSaveWant
                )
            }
            if (books.isNotEmpty()) {
                item(span = { GridItemSpan(3) }) {
                    RecentlyReadShelf(
                        books = recentlyRead(books),
                        dark = dark,
                        ink = ink,
                        mute = mute,
                        onOpen = onOpen
                    )
                }
            }
            item(span = { GridItemSpan(3) }) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        LibFilter.ALL to "All",
                        LibFilter.CURRENT to "Currently Reading",
                        LibFilter.TO_READ to "To Read",
                        LibFilter.WANT to "Want to Read",
                        LibFilter.FINISHED to "Finished"
                    ).forEach { (s, label) ->
                        Text(
                            label,
                            Modifier.clip(RoundedCornerShape(20.dp))
                                .background(if (filter == s) Coral else Color(0x66FFFFFF))
                                .combinedClickable(onClick = { filter = s })
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            color = if (filter == s) Color.White else ink,
                            fontSize = 12.sp, maxLines = 1
                        )
                    }
                }
            }
            if (filter == LibFilter.WANT) {
                if (wantToRead.isEmpty()) {
                    item(span = { GridItemSpan(3) }) {
                        GlassCard(Modifier.fillMaxWidth(), dark, 18) {
                            Text(
                                "Saved discoveries live here. Long-press a For You cover to add one.",
                                Modifier.padding(16.dp), color = mute, fontSize = 13.sp
                            )
                        }
                    }
                } else {
                    items(wantToRead, key = { "want_${it.volumeId}" }) { book ->
                        Column {
                            Box {
                                CoverFace(
                                    book.title, null, book.coverUrl,
                                    Modifier.fillMaxWidth().aspectRatio(0.68f).combinedClickable(
                                        onClick = { onOpenDiscovery(book) },
                                        onLongClick = { onSaveWant(book) }
                                    ),
                                    dark
                                )
                                SavedBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(book.title, color = ink, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(book.authorLine, color = mute, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
            } else {
                items(shown, key = { it.id }) { book ->
                    Column {
                        BookCover(
                            book,
                            Modifier.fillMaxWidth().aspectRatio(0.68f).combinedClickable(
                                onClick = { onOpen(book) },
                                onLongClick = { onRemove(book.id) }
                            ),
                            dark
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(book.title, color = ink, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(book.author, color = mute, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ForYouShelf(
    books: List<DiscoveryBook>,
    wantToRead: List<DiscoveryBook>,
    dark: Boolean,
    ink: Color,
    mute: Color,
    onOpen: (DiscoveryBook) -> Unit,
    onSave: (DiscoveryBook) -> Unit
) {
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(books) { if (books.isNotEmpty()) waited = true }
    LaunchedEffect(Unit) {
        delay(3500)
        waited = true
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("For You", color = ink, fontFamily = Playfair, fontSize = 16.sp)
        Text("Fresh picks for today", color = mute, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
        when {
            books.isNotEmpty() -> {
                val pager = rememberPagerState(pageCount = { books.size })
                HorizontalPager(
                    state = pager,
                    contentPadding = PaddingValues(horizontal = 48.dp),
                    pageSpacing = 10.dp,
                    modifier = Modifier.fillMaxWidth().height(248.dp)
                ) { page ->
                    val book = books[page]
                    val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CoverFace(
                            book.title, null, book.coverUrl,
                            Modifier
                                .graphicsLayer {
                                    val a = kotlin.math.abs(offset).coerceAtMost(1f)
                                    scaleX = 1f - 0.1f * a
                                    scaleY = scaleX
                                    alpha = 1f - 0.22f * a
                                }
                                .width(164.dp).aspectRatio(0.68f)
                                .combinedClickable(
                                    onClick = { onOpen(book) },
                                    onLongClick = { onSave(book) }
                                ),
                            dark
                        )
                        if (wantToRead.any { Recommendations.sameWork(it, book) }) {
                            SavedBadge(Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 8.dp))
                        }
                    }
                }
                val current = books.getOrNull(pager.currentPage)
                if (current != null) {
                    Text(current.title, Modifier.padding(top = 8.dp), color = ink, fontFamily = Playfair, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(current.authorLine, color = mute, fontSize = 12.sp, maxLines = 1)
                }
            }
            !waited -> {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    repeat(3) {
                        Box(
                            Modifier.weight(1f).aspectRatio(0.68f).clip(RoundedCornerShape(10.dp))
                                .background(Color(0x33FFFFFF))
                        )
                    }
                }
                Text("Gathering today’s books…", color = mute, fontSize = 12.sp)
            }
            else -> {
                Text(
                    "No recommendations right now. Check back tomorrow, or add a Google Books API key if For You is unset.",
                    color = mute, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }
}

private fun recentlyRead(books: List<LibraryBook>): List<LibraryBook> {
    val current = books.filter { it.shelf == Shelf.CURRENT }.sortedByDescending { it.lastReadAt }
    val rest = books.filter { it.shelf != Shelf.CURRENT }
        .sortedWith(compareByDescending<LibraryBook> { it.lastReadAt }.thenByDescending { it.addedAt })
    return (current + rest).distinctBy { it.id }.take(12)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentlyReadShelf(
    books: List<LibraryBook>,
    dark: Boolean,
    ink: Color,
    mute: Color,
    onOpen: (LibraryBook) -> Unit
) {
    if (books.isEmpty()) return
    val continuing = books.any { it.shelf == Shelf.CURRENT }
    GlassCard(Modifier.fillMaxWidth(), dark, 18) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                if (continuing) "Continue Reading" else "Your Shelf",
                color = ink, fontFamily = Playfair, fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Text("Your books", color = mute, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp).padding(top = 2.dp))
            Spacer(Modifier.height(8.dp))
            val pager = rememberPagerState(pageCount = { books.size })
            HorizontalPager(
                state = pager,
                contentPadding = PaddingValues(horizontal = 72.dp),
                pageSpacing = 12.dp,
                modifier = Modifier.fillMaxWidth().height(150.dp)
            ) { page ->
                val book = books[page]
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BookCover(
                        book,
                        Modifier.width(96.dp).aspectRatio(0.68f).combinedClickable(onClick = { onOpen(book) }),
                        dark
                    )
                }
            }
            val current = books.getOrNull(pager.currentPage)
            if (current != null) {
                Text(current.title, Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp), color = ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(current.author, Modifier.padding(start = 16.dp, top = 1.dp), color = mute, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SavedBadge(modifier: Modifier) {
    Text(
        "Saved",
        modifier.clip(RoundedCornerShape(10.dp)).background(Coral).padding(horizontal = 8.dp, vertical = 3.dp),
        color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium
    )
}
