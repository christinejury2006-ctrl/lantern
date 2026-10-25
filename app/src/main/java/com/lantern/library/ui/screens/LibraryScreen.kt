package com.lantern.library.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.DiscoveryBook
import com.lantern.library.data.LibraryAppearance
import com.lantern.library.data.LibraryBook
import com.lantern.library.data.ReaderTheme
import com.lantern.library.data.Recommendations
import com.lantern.library.data.Shelf
import com.lantern.library.ui.components.AppearanceLayer
import com.lantern.library.ui.components.BookCover
import com.lantern.library.ui.components.CoverFace
import com.lantern.library.ui.components.GlassCard
import com.lantern.library.ui.components.ReadingProgressBar
import com.lantern.library.ui.theme.Coral
import com.lantern.library.ui.theme.Ink
import com.lantern.library.ui.theme.InkSoft
import com.lantern.library.ui.theme.LoreMint
import com.lantern.library.ui.theme.LoreOnMint
import com.lantern.library.ui.theme.NightText
import com.lantern.library.ui.theme.Playfair

private enum class LibFilter { ALL, CURRENT, TO_READ, WANT, FINISHED }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    books: List<LibraryBook>,
    forYou: List<DiscoveryBook>,
    wantToRead: List<DiscoveryBook>,
    theme: ReaderTheme,
    appearance: LibraryAppearance = LibraryAppearance(),
    forYouBusy: Boolean = false,
    forYouFailed: Boolean = false,
    onOpen: (LibraryBook) -> Unit,
    onRemove: (String) -> Unit,
    onImport: () -> Unit = {},
    onOpenDiscovery: (DiscoveryBook) -> Unit,
    onSaveWant: (DiscoveryBook) -> Unit,
    onRefreshForYou: () -> Unit = {}
) {
    val dark = theme == ReaderTheme.DARK
    val ink = if (dark) NightText else Ink
    val mute = if (dark) Color(0xFFD0D6DE) else InkSoft
    var filter by remember { mutableStateOf(LibFilter.ALL) }
    var pendingRemove by remember { mutableStateOf<LibraryBook?>(null) }
    var previewBook by remember { mutableStateOf<LibraryBook?>(null) }
    val shown = when (filter) {
        LibFilter.ALL -> books
        LibFilter.CURRENT -> books.filter { it.shelf == Shelf.CURRENT }
        LibFilter.TO_READ -> books.filter { it.shelf == Shelf.TO_READ }
        LibFilter.FINISHED -> books.filter { it.shelf == Shelf.FINISHED }
        LibFilter.WANT -> emptyList()
    }
    AppearanceLayer(appearance.look, dark) {
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
                    busy = forYouBusy,
                    failed = forYouFailed,
                    onOpen = onOpenDiscovery,
                    onSave = onSaveWant,
                    onRefresh = onRefreshForYou
                )
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
                                .background(if (filter == s) Coral else if (dark) Color(0x334A5568) else Color(0x66FFFFFF))
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
                                    dark,
                                    book.coverUrls
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
                item(span = { GridItemSpan(3) }) {
                    RecentlyReadShelf(
                        books = shown,
                        dark = dark,
                        ink = ink,
                        mute = mute,
                        onOpen = { previewBook = it }
                    )
                }
            }
        }
        val selected = previewBook
        if (selected != null) {
            AlertDialog(
                onDismissRequest = { previewBook = null },
                containerColor = if (dark) Color(0xFF242424) else Color(0xFFF7F5F0),
                title = { Text(selected.title, color = ink, fontFamily = Playfair) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.width(172.dp).height(238.dp).padding(start = 7.dp, bottom = 5.dp).background(Color(0xFFB8AFA1), RoundedCornerShape(3.dp)))
                            BookCover(selected, Modifier.width(164.dp).height(226.dp).graphicsLayer { rotationY = -5f; cameraDistance = 18f * density }, dark)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(selected.author, color = mute, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        ReadingProgressBar(selected.progress, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text("${selected.progress.coerceIn(0, 100)}% read", color = mute, fontSize = 12.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { previewBook = null; onOpen(selected) }) { Text(if (selected.progress > 0) "Continue reading" else "Start reading") }
                },
                dismissButton = { TextButton(onClick = { previewBook = null }) { Text("Back to shelf") } }
            )
        }
        val doomed = pendingRemove
        if (doomed != null) {
            AlertDialog(
                onDismissRequest = { pendingRemove = null },
                title = { Text("Remove book") },
                text = { Text("Remove “${doomed.title}” from your library?") },
                confirmButton = {
                    TextButton(onClick = {
                        pendingRemove = null
                        onRemove(doomed.id)
                    }) { Text("Remove") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRemove = null }) { Text("Cancel") }
                }
            )
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
    busy: Boolean,
    failed: Boolean,
    onOpen: (DiscoveryBook) -> Unit,
    onSave: (DiscoveryBook) -> Unit,
    onRefresh: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("For You", color = ink, fontFamily = Playfair, fontSize = 16.sp)
                Text("Fresh picks for today", color = mute, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Text(
                "Refresh",
                color = if (busy) mute else LoreOnMint,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (busy) Color(0x332A2A34) else LoreMint)
                    .combinedClickable(enabled = !busy, onClick = onRefresh)
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        when {
            busy -> {
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
                            dark,
                            book.coverUrls
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
            !failed -> {
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
                    "Couldn't load recommendations. Tap Refresh to try again.",
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
    return (current + rest).distinctBy { it.id }
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
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(if (dark) Color(0x55302B28) else Color(0x66E5D8C8))
            .padding(vertical = 12.dp)
    ) {
            Text(
                if (continuing) "Continue Reading" else "Your Shelf",
                color = ink, fontFamily = Playfair, fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Text("Your books", color = mute, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp).padding(top = 2.dp))
            Spacer(Modifier.height(8.dp))
            val pager = rememberPagerState(pageCount = { books.size })
            Box(Modifier.fillMaxWidth().height(190.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val shelf = androidx.compose.ui.graphics.Path().apply {
                        moveTo(0f, size.height * 0.70f)
                        cubicTo(size.width * 0.25f, size.height * 0.86f, size.width * 0.75f, size.height * 0.86f, size.width, size.height * 0.70f)
                        lineTo(size.width, size.height * 0.84f)
                        cubicTo(size.width * 0.75f, size.height * 0.96f, size.width * 0.25f, size.height * 0.96f, 0f, size.height * 0.84f)
                        close()
                    }
                    drawPath(shelf, if (dark) Color(0xFF49382F) else Color(0xFF6E4B38))
                    drawLine(if (dark) Color(0xFF7C6557) else Color(0xFFB58A68),
                        androidx.compose.ui.geometry.Offset(0f, size.height * 0.72f),
                        androidx.compose.ui.geometry.Offset(size.width, size.height * 0.72f), strokeWidth = 3.dp.toPx())
                }
                HorizontalPager(state = pager, contentPadding = PaddingValues(horizontal = 14.dp), pageSpacing = 3.dp,
                    modifier = Modifier.fillMaxSize().padding(bottom = 16.dp)) { page ->
                    val book = books[page]
                    val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                    Column(Modifier.fillMaxSize().graphicsLayer {
                        val a = kotlin.math.abs(offset).coerceAtMost(1f)
                        rotationY = offset * -10f
                        translationY = a * 9.dp.toPx()
                        cameraDistance = 20f * density
                    }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        Box(Modifier.width((54f - kotlin.math.abs(offset).coerceAtMost(1f) * 8f).dp)
                            .height((104f - kotlin.math.abs(offset).coerceAtMost(1f) * 12f).dp)
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(if (dark) Color(0xFF37302B) else Color(0xFFE7D9C6))
                            .combinedClickable(onClick = { onOpen(book) }).padding(horizontal = 3.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center) {
                            BookCover(book, Modifier.fillMaxSize(), dark)
                        }
                        Spacer(Modifier.height(5.dp))
                        ReadingProgressBar(book.progress, Modifier.width(48.dp))
                    }
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
