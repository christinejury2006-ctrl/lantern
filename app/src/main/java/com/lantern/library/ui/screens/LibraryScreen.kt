package com.lantern.library.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.DiscoveryBook
import com.lantern.library.data.LibraryAppearance
import com.lantern.library.data.LibraryBook
import com.lantern.library.data.ReaderTheme
import com.lantern.library.data.Shelf
import com.lantern.library.ui.components.BookLiftOverlay
import com.lantern.library.ui.components.BookStack
import com.lantern.library.ui.components.CoverFace
import com.lantern.library.ui.components.RoomBackdrop
import com.lantern.library.ui.components.ShelfRow
import com.lantern.library.ui.components.Spine
import com.lantern.library.ui.components.TitledShelf
import com.lantern.library.ui.theme.Brass
import com.lantern.library.ui.theme.BrassHi
import com.lantern.library.ui.theme.Parchment
import com.lantern.library.ui.theme.Playfair

private const val SPINES_PER_SHELF = 12
private val SHELF_HEIGHT = 176.dp

/**
 * The library is a room: a painted bookcase interior, your books on real boards, and the
 * recommendations for today piled on the table-top shelf above them.
 *
 * Tapping a book lifts it out of the shelf. Tapping the raised book opens it, with the room
 * going soft behind. Searching brings the shelf to the book instead of the other way round.
 */
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
    val ink = Parchment
    val mute = Color(0xFFC9B79F)
    var liftedId by remember { mutableStateOf<String?>(null) }
    var pendingRemove by remember { mutableStateOf<LibraryBook?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var focusId by remember { mutableStateOf<String?>(null) }
    var focusNonce by remember { mutableIntStateOf(0) }

    val continuing = continuingBooks(books)
    val matches = if (query.isBlank()) emptyList() else books.filter { book ->
        book.title.contains(query, ignoreCase = true) ||
            book.author.contains(query, ignoreCase = true)
    }
    val open = books.firstOrNull { it.id == liftedId }

    RoomBackdrop(dark) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .then(if (open != null) Modifier.blur(20.dp) else Modifier)
                .statusBarsPadding()
                .padding(bottom = 150.dp)
        ) {
            // ---- header ----------------------------------------------------------
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 16.dp, top = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Library", color = ink, fontFamily = Playfair, fontSize = 32.sp)
                    Text(
                        "${books.size} books  ·  ${books.count { it.shelf == Shelf.CURRENT }} reading",
                        color = mute, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)
                    )
                }
                BrassButton(icon = Icons.Filled.Search, label = "Search") {
                    searching = !searching
                    if (!searching) query = ""
                }
                Spacer(Modifier.width(8.dp))
                BrassButton(icon = Icons.Filled.Add, label = "Add", filled = true, onClick = onImport)
            }

            // ---- search ----------------------------------------------------------
            if (searching) {
                Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xCC1B1109))
                            .border(1.dp, Brass.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Search, null, tint = Brass, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f)) {
                            if (query.isBlank()) {
                                Text("Find a book on your shelf", color = mute, fontSize = 14.sp)
                            }
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                textStyle = TextStyle(color = ink, fontSize = 14.sp),
                                cursorBrush = SolidColor(BrassHi),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    if (query.isNotBlank()) {
                        if (matches.isEmpty()) {
                            Text(
                                "Nothing on your shelf matches “$query”.",
                                color = mute, fontSize = 12.sp,
                                modifier = Modifier.padding(top = 10.dp, start = 4.dp)
                            )
                        } else {
                            matches.take(8).forEach { book ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            liftedId = book.id
                                            focusId = book.id
                                            focusNonce++
                                            searching = false
                                            query = ""
                                        }
                                        .padding(vertical = 6.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CoverFace(
                                        book.title, book.localCover, book.remoteCover,
                                        Modifier.width(30.dp).height(44.dp),
                                        dark
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(book.title, color = ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                        Text(book.author, color = mute, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---- recommendations, piled on the shelf top -------------------------
            TitledShelfHeader(
                title = "On the table",
                subtitle = if (forYouFailed && forYou.isEmpty()) {
                    "Couldn't reach the bookshop. Tap to try again."
                } else "Picked for you today",
                dark = dark,
                trailing = {
                    Text(
                        if (forYouBusy) "Looking…" else "Refresh",
                        Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0x33C8A96B))
                            .clickable(enabled = !forYouBusy, onClick = onRefreshForYou)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        color = BrassHi, fontSize = 12.sp
                    )
                }
            )
            BookStack(
                spines = forYou.map { it.toSpine() },
                dark = dark,
                busy = forYouBusy,
                height = 148.dp,
                onOpen = { spine -> forYou.firstOrNull { it.volumeId == spine.id }?.let(onOpenDiscovery) }
            )

            // ---- your shelf ------------------------------------------------------
            Spacer(Modifier.height(6.dp))
            TitledShelfHeader(title = "Your shelf", subtitle = null, dark = dark)

            if (books.isEmpty()) {
                ShelfRow(
                    spines = emptyList(),
                    dark = dark,
                    shelfHeight = SHELF_HEIGHT,
                    emptyLabel = "Empty. Tap + to bring a book in.",
                    onOpen = {}
                )
            } else {
                val rows = books.chunked(SPINES_PER_SHELF)
                rows.forEach { row ->
                    val rowHasFocus = row.any { it.id == focusId }
                    ShelfRow(
                        spines = row.map { it.toSpine() },
                        dark = dark,
                        shelfHeight = SHELF_HEIGHT,
                        liftedId = liftedId,
                        focusId = if (rowHasFocus) focusId else null,
                        focusNonce = focusNonce,
                        emptyLabel = "Nothing here yet",
                        onOpen = { spine ->
                            // first tap lifts the book, second tap opens it
                            if (liftedId == spine.id) onOpen(books.first { it.id == spine.id })
                            else liftedId = spine.id
                        },
                        onLongPress = { spine ->
                            books.firstOrNull { it.id == spine.id }?.let { pendingRemove = it }
                        }
                    )
                }
            }

            if (continuing.isNotEmpty() && continuing.size < books.size) {
                Spacer(Modifier.height(10.dp))
                TitledShelf(
                    title = "Continue reading",
                    subtitle = "Back to where you left off",
                    spines = continuing.map { it.toSpine() },
                    dark = dark,
                    shelfHeight = SHELF_HEIGHT,
                    emptyLabel = "Nothing in progress",
                    liftedId = liftedId,
                    onOpen = { spine ->
                        if (liftedId == spine.id) onOpen(books.first { it.id == spine.id })
                        else liftedId = spine.id
                    }
                )
            }

            if (wantToRead.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                TitledShelf(
                    title = "Want to read",
                    subtitle = "Saved from the table",
                    spines = wantToRead.map { it.toSpine() },
                    dark = dark,
                    shelfHeight = 150.dp,
                    emptyLabel = "",
                    onOpen = { spine -> wantToRead.firstOrNull { it.volumeId == spine.id }?.let(onOpenDiscovery) }
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "Tap a book to lift it out · tap it again to open · hold to remove",
                Modifier.padding(horizontal = 20.dp),
                color = mute.copy(alpha = 0.8f), fontSize = 11.sp
            )
        }

        if (open != null) {
            BookLiftOverlay(
                book = open,
                dark = dark,
                onRead = {
                    liftedId = null
                    onOpen(open)
                },
                onDismiss = { liftedId = null }
            )
        }
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
                    if (liftedId == doomed.id) liftedId = null
                    onRemove(doomed.id)
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun BrassButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    filled: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (filled) Brass else Color(0xCC1B1109))
            .border(1.dp, Brass.copy(alpha = if (filled) 0f else 0.6f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, label, tint = if (filled) Color(0xFF2A1B10) else BrassHi, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun TitledShelfHeader(
    title: String,
    subtitle: String?,
    dark: Boolean,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Parchment, fontFamily = Playfair, fontSize = 17.sp)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = Color(0xFFC9B79F), fontSize = 11.sp, modifier = Modifier.padding(top = 1.dp))
            }
        }
        trailing?.invoke()
    }
}

private fun LibraryBook.toSpine(): Spine =
    Spine(
        id = id,
        title = title,
        author = author,
        pages = pageCount,
        progress = progress,
        coverRes = localCover,
        coverUrl = remoteCover
    )

private fun DiscoveryBook.toSpine(): Spine =
    Spine(
        id = volumeId,
        title = title,
        author = authorLine,
        pages = 260,
        progress = 0f,
        coverUrl = coverUrl,
        coverFallbacks = coverUrls
    )

/**
 * Only books that are genuinely part-read. Listing everything here made this shelf a
 * duplicate of the main one, which read as the same books pinned up twice.
 */
private fun continuingBooks(books: List<LibraryBook>): List<LibraryBook> =
    books.filter { it.shelf == Shelf.CURRENT && it.progress > 0.005f }
        .sortedByDescending { it.lastReadAt }
        .take(12)
