package com.lantern.library.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.DiscoveryBook
import com.lantern.library.data.LibraryBook
import com.lantern.library.data.LibraryCategory
import com.lantern.library.data.ReaderTheme
import com.lantern.library.ui.components.CoverFace
import com.lantern.library.ui.components.GlassSurface
import com.lantern.library.ui.components.NookBackdrop
import com.lantern.library.ui.components.NookLamp
import com.lantern.library.ui.components.NookProgress
import com.lantern.library.ui.theme.NookPalette
import com.lantern.library.ui.theme.Playfair
import com.lantern.library.ui.theme.nookPalette

/**
 * The library: every book the reader owns, filed the way they think about books.
 *
 * The sections are [LibraryCategory]: what they are reading, what they mean to read, what they
 * finished, and what they put down. Completed is latched in the store — reaching the end files a
 * book there and re-reading does not take it out again — and Did not finish is a decision the
 * reader makes from the long-press menu, not something the app infers from progress.
 *
 * Long-press is the whole management surface: Remove book, and Not for me (did not finish). Both
 * ask a yes/no question first, because both are the sort of thing a finger does by accident.
 *
 * Search is local and instant: it filters titles and authors on this shelf, which is what "find a
 * particular book on that shelf" means. Searching the internet is Explore's job.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    books: List<LibraryBook>,
    theme: ReaderTheme,
    onOpen: (LibraryBook) -> Unit,
    onRemove: (String) -> Unit,
    onImport: () -> Unit = {},
    savedForLater: List<DiscoveryBook> = emptyList(),
    onOpenSaved: (DiscoveryBook) -> Unit = {},
    onRemoveSaved: (DiscoveryBook) -> Unit = {},
    onDidNotFinish: (String) -> Unit = {},
    onBackOnShelf: (String) -> Unit = {}
) {
    val palette = nookPalette(theme)
    var category by remember { mutableStateOf(LibraryCategory.ALL) }
    var query by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<LibraryBook?>(null) }
    var confirmRemove by remember { mutableStateOf<LibraryBook?>(null) }
    var confirmDnf by remember { mutableStateOf<LibraryBook?>(null) }
    var confirmSavedRemoval by remember { mutableStateOf<DiscoveryBook?>(null) }

    val trimmed = query.trim()
    // Computed on each recomposition rather than remembered: `books` is a mutable snapshot list
    // whose identity never changes, so a `remember(books)` would keep filtering a stale copy after
    // a book was added or removed. Two hundred books are cheap to filter.
    val visible = books
        .filter { category == LibraryCategory.ALL || it.section == category }
        .filter { book ->
            trimmed.isEmpty() ||
                book.title.contains(trimmed, ignoreCase = true) ||
                book.author.contains(trimmed, ignoreCase = true)
        }
    // Books saved from discovery: the "want to read" pile that is not on the phone yet.
    val showSaved = (category == LibraryCategory.ALL || category == LibraryCategory.WANT_TO_READ) &&
        trimmed.isEmpty()
    val saved = if (showSaved) savedForLater else emptyList()

    NookBackdrop(palette) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 130.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(span = { GridItemSpan(3) }) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // The nook's lamp, in the corner where the room is: a small, slow piece of
                        // life so the shelf does not feel like a spreadsheet.
                        NookLamp(palette, Modifier.padding(end = 10.dp), size = 44.dp)
                        Column(Modifier.weight(1f)) {
                            Text("Library", color = palette.ink, fontFamily = Playfair, fontSize = 28.sp)
                            Text(
                                when (books.size) {
                                    0 -> "Nothing here yet"
                                    1 -> "1 book  ·  ${books.count { it.section == LibraryCategory.COMPLETED }} finished"
                                    else -> "${books.size} books  ·  ${books.count { it.section == LibraryCategory.COMPLETED }} finished"
                                },
                                color = palette.mute,
                                fontSize = 12.sp
                            )
                        }
                        GlassSurface(palette, Modifier, shape = RoundedCornerShape(16.dp), onClick = onImport) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Add, "Import", tint = palette.ink, modifier = Modifier.size(16.dp))
                                Text(" Import", color = palette.ink, fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    SearchField(palette, query) { query = it }
                    Spacer(Modifier.height(10.dp))
                }
            }

            item(span = { GridItemSpan(3) }) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 2.dp)
                ) {
                    rowItems(LibraryCategory.values().toList(), key = { it.name }) { entry ->
                        val on = entry == category
                        val count = when (entry) {
                            LibraryCategory.ALL -> books.size
                            else -> books.count { it.section == entry }
                        } + if (entry == LibraryCategory.WANT_TO_READ) savedForLater.size else 0
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (on) palette.accent else palette.glass)
                                .clickable { category = entry }
                                .padding(horizontal = 13.dp, vertical = 7.dp)
                        ) {
                            Text(
                                "${entry.label}  $count",
                                color = if (on) palette.accentInk else palette.mute,
                                fontSize = 11.sp,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            if (saved.isNotEmpty()) {
                item(span = { GridItemSpan(3) }) {
                    Column {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "SAVED TO READ",
                            color = palette.mute,
                            fontSize = 10.sp,
                            letterSpacing = 2.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                items(saved, key = { "saved-${it.volumeId}" }, span = { GridItemSpan(1) }) { pick ->
                    SavedTile(pick, palette, onClick = { onOpenSaved(pick) }, onLongClick = { confirmSavedRemoval = pick })
                }
                item(span = { GridItemSpan(3) }) { Spacer(Modifier.height(4.dp)) }
            }

            if (visible.isEmpty() && saved.isEmpty()) {
                item(span = { GridItemSpan(3) }) {
                    EmptyShelfHint(palette, category, trimmed.isNotEmpty(), onImport)
                }
            } else {
                items(visible, key = { it.id }) { book ->
                    BookTile(
                        book = book,
                        palette = palette,
                        onClick = { onOpen(book) },
                        onLongClick = { menuFor = book }
                    )
                }
            }
        }
    }

    // ---- long-press menu: the two actions a reader actually wants, nothing else.
    val target = menuFor
    if (target != null) {
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(target.title, fontFamily = Playfair) },
            text = { Text("What would you like to do with this book?") },
            confirmButton = {
                Column(Modifier.fillMaxWidth()) {
                    TextButton(onClick = {
                        menuFor = null
                        if (target.section == LibraryCategory.DNF) onBackOnShelf(target.id)
                        else confirmDnf = target
                    }) {
                        Text(
                            if (target.section == LibraryCategory.DNF) "Back on the shelf" else "Did not finish",
                            color = palette.ink
                        )
                    }
                    TextButton(onClick = { menuFor = null; confirmRemove = target }) {
                        Text("Remove book", color = palette.accent)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { menuFor = null }) { Text("Cancel", color = palette.mute) }
            }
        )
    }

    val doomed = confirmRemove
    if (doomed != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove book?") },
            text = {
                Text(
                    "Remove \u201C${doomed.title}\u201D from this phone? " +
                        if (doomed.driveFileId.isNullOrBlank())
                            "There is no copy in your account, so this book will be gone."
                        else "Your account keeps a copy, so you can restore it later."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmRemove = null; onRemove(doomed.id) }) {
                    Text("Yes, remove", color = palette.accent)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("No") } }
        )
    }

    val dnf = confirmDnf
    if (dnf != null) {
        AlertDialog(
            onDismissRequest = { confirmDnf = null },
            title = { Text("Did not finish?") },
            text = {
                Text(
                    "\u201C${dnf.title}\u201D moves to Did not finish. Your place, bookmarks and file " +
                        "are kept, and you can put it back on the shelf at any time."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDnf = null; onDidNotFinish(dnf.id) }) {
                    Text("Yes, move it", color = palette.accent)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDnf = null }) { Text("No") } }
        )
    }

    val savedDoomed = confirmSavedRemoval
    if (savedDoomed != null) {
        AlertDialog(
            onDismissRequest = { confirmSavedRemoval = null },
            title = { Text("Remove from saved?") },
            text = { Text("\u201C${savedDoomed.title}\u201D will be taken out of Want to read.") },
            confirmButton = {
                TextButton(onClick = { confirmSavedRemoval = null; onRemoveSaved(savedDoomed) }) {
                    Text("Yes, remove", color = palette.accent)
                }
            },
            dismissButton = { TextButton(onClick = { confirmSavedRemoval = null }) { Text("No") } }
        )
    }
}

@Composable
private fun SearchField(palette: NookPalette, value: String, onValue: (String) -> Unit) {
    GlassSurface(palette, Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Search, "Search", tint = palette.mute, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text("Find a book on this shelf", color = palette.faint, fontSize = 13.sp)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValue,
                    singleLine = true,
                    textStyle = TextStyle(color = palette.ink, fontSize = 13.sp),
                    cursorBrush = SolidColor(palette.accent),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (value.isNotEmpty()) {
                Icon(
                    Icons.Filled.Close,
                    "Clear",
                    tint = palette.mute,
                    modifier = Modifier.size(16.dp).clickable { onValue("") }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(
    book: LibraryBook,
    palette: NookPalette,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(12.dp))
                .background(palette.coverFallback)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        ) {
            CoverFace(book.title, book.localCover, book.remoteCover, Modifier.fillMaxSize(), palette.dark)
            if (!book.localFileAvailable) {
                Badge("☁", palette, Alignment.TopEnd)
            }
            when (book.section) {
                LibraryCategory.DNF -> Badge("DNF", palette, Alignment.BottomStart)
                LibraryCategory.COMPLETED -> Badge("✓", palette, Alignment.BottomStart)
                else -> Unit
            }
        }
        Spacer(Modifier.height(6.dp))
        NookProgress(book.progress, palette)
        Spacer(Modifier.height(4.dp))
        Text(
            book.title,
            color = palette.ink,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            when (book.section) {
                LibraryCategory.COMPLETED -> "Finished"
                LibraryCategory.DNF -> "Did not finish"
                LibraryCategory.READING -> "${(book.progress * 100).toInt()}% read"
                else -> book.author.ifBlank { "Not started" }
            },
            color = palette.mute,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BoxScope.Badge(text: String, palette: NookPalette, align: Alignment) {
    Text(
        text,
        color = Color.White,
        fontSize = 9.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .align(align)
            .padding(5.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (text == "DNF") palette.accent.copy(alpha = 0.85f) else Color(0x66000000))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedTile(
    pick: DiscoveryBook,
    palette: NookPalette,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(12.dp))
                .background(palette.coverFallback)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        ) {
            CoverFace(pick.title, null, pick.coverUrl, Modifier.fillMaxSize(), palette.dark, pick.coverUrls)
            Badge("WANT", palette, Alignment.BottomStart)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            pick.title,
            color = palette.ink,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "Not on the shelf yet",
            color = palette.mute,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EmptyShelfHint(
    palette: NookPalette,
    category: LibraryCategory,
    searching: Boolean,
    onImport: () -> Unit
) {
    GlassSurface(
        palette,
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        onClick = onImport
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                when {
                    searching -> "No book matches that"
                    category == LibraryCategory.COMPLETED -> "Nothing finished yet"
                    category == LibraryCategory.DNF -> "Nothing set aside"
                    category == LibraryCategory.WANT_TO_READ -> "Nothing waiting to be read"
                    else -> "An empty shelf"
                },
                color = palette.ink,
                fontFamily = Playfair,
                fontSize = 17.sp
            )
            Text(
                when {
                    searching -> "Try part of the title or the author's name."
                    category == LibraryCategory.COMPLETED -> "A book files itself here the moment you reach the last page."
                    category == LibraryCategory.DNF -> "Long-press a book to move it here."
                    category == LibraryCategory.WANT_TO_READ -> "Save a book from Explore and it will wait here."
                    else -> "Import an EPUB or PDF and it will stand here, with your place kept."
                },
                color = palette.mute,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                "Import a book",
                color = palette.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Start,
                modifier = Modifier.padding(top = 14.dp)
            )
        }
    }
}
