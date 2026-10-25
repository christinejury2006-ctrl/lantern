package com.lantern.library.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.DiscoveryBook
import com.lantern.library.data.LibraryAppearance
import com.lantern.library.data.LibraryBook
import com.lantern.library.data.ReaderTheme
import com.lantern.library.data.Shelf
import com.lantern.library.ui.components.AppearanceLayer
import com.lantern.library.ui.components.BookLiftOverlay
import com.lantern.library.ui.components.EmptyShelf
import com.lantern.library.ui.components.ShelfRoom
import com.lantern.library.ui.components.ShelfRow
import com.lantern.library.ui.components.Spine
import com.lantern.library.ui.components.TitledShelf
import com.lantern.library.ui.theme.Ink
import com.lantern.library.ui.theme.NightText
import com.lantern.library.ui.theme.Playfair

private enum class LibFilter { ALL, CURRENT, TO_READ, WANT, FINISHED }

private const val SPINES_PER_SHELF = 12
private val SHELF_HEIGHT = 150.dp

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
    val mute = if (dark) Color(0xFFC9BCA9) else Color(0xFF6B5641)
    var filter by remember { mutableStateOf(LibFilter.ALL) }
    var pendingRemove by remember { mutableStateOf<LibraryBook?>(null) }
    var lifted by remember { mutableStateOf<LibraryBook?>(null) }

    val shown = when (filter) {
        LibFilter.ALL -> books
        LibFilter.CURRENT -> books.filter { it.shelf == Shelf.CURRENT }
        LibFilter.TO_READ -> books.filter { it.shelf == Shelf.TO_READ }
        LibFilter.FINISHED -> books.filter { it.shelf == Shelf.FINISHED }
        LibFilter.WANT -> emptyList()
    }
    val continuing = recentlyRead(books)
    val wallAlpha = if (appearance.look.resolvedFor(dark).hasWallpaper) 0.42f else 1f

    AppearanceLayer(appearance.look, dark) {
        ShelfRoom(dark, wallAlpha = wallAlpha) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .then(if (lifted != null) Modifier.blur(18.dp) else Modifier)
                    .padding(bottom = 108.dp)
            ) {
                // Header
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Library", color = ink, fontFamily = Playfair, fontSize = 32.sp)
                        Text(
                            "${books.size} books  ·  ${books.count { it.shelf == Shelf.CURRENT }} reading  ·  ${books.count { it.shelf == Shelf.TO_READ }} to read",
                            color = mute, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF8E5B44))
                            .combinedClickable(onClick = onImport),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Add, "Import", tint = Color(0xFFF6ECDC))
                    }
                }

                // Shelf filters
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        LibFilter.ALL to "All Books",
                        LibFilter.CURRENT to "Reading",
                        LibFilter.TO_READ to "To Read",
                        LibFilter.FINISHED to "Read",
                        LibFilter.WANT to "Want to Read"
                    ).forEach { (s, label) ->
                        val on = filter == s
                        Text(
                            label,
                            Modifier
                                .clip(RoundedCornerShape(19.dp))
                                .background(
                                    when {
                                        on -> Color(0xFFC8A96B)
                                        dark -> Color(0x3D4A3A2A)
                                        else -> Color(0xB3FFF7EA)
                                    }
                                )
                                .combinedClickable(onClick = { filter = s })
                                .padding(horizontal = 15.dp, vertical = 8.dp),
                            color = if (on) Color(0xFF2A1B10) else ink,
                            fontSize = 12.sp,
                            fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Recommendations on their own shelf
                when {
                    forYouBusy -> EmptyShelf(dark, label = "Gathering today's books…")
                    forYou.isNotEmpty() -> TitledShelf(
                        title = "For You",
                        subtitle = "Fresh picks for today",
                        spines = forYou.map { it.toSpine() },
                        dark = dark,
                        shelfHeight = SHELF_HEIGHT,
                        trailing = {
                            Text(
                                "Refresh",
                                Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (dark) Color(0x3DFFE9C8) else Color(0xB3FFF7EA))
                                    .combinedClickable(onClick = onRefreshForYou)
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                color = ink, fontSize = 12.sp
                            )
                        },
                        emptyLabel = "No picks yet",
                        onOpen = { spine -> forYou.firstOrNull { it.volumeId == spine.id }?.let(onOpenDiscovery) },
                        onLongPress = { spine -> forYou.firstOrNull { it.volumeId == spine.id }?.let(onSaveWant) }
                    )
                    forYouFailed -> EmptyShelf(dark, label = "Couldn't load recommendations. Tap Refresh.")
                    else -> EmptyShelf(dark, label = "Gathering today's books…")
                }

                // The library itself, laid out as a bookcase
                if (books.isNotEmpty()) {
                    Text(
                        "On the shelf",
                        Modifier.padding(start = 20.dp, top = 18.dp, bottom = 2.dp),
                        color = ink, fontFamily = Playfair, fontSize = 17.sp
                    )
                    val rows = shown.chunked(SPINES_PER_SHELF)
                    if (rows.isEmpty()) {
                        EmptyShelf(dark, label = "No books in this view")
                    } else {
                        rows.forEach { row ->
                            ShelfRow(
                                spines = row.map { it.toSpine() },
                                dark = dark,
                                shelfHeight = SHELF_HEIGHT,
                                emptyLabel = "Nothing here yet",
                                onOpen = { spine -> books.firstOrNull { it.id == spine.id }?.let { lifted = it } },
                                onLongPress = { spine ->
                                    books.firstOrNull { it.id == spine.id }?.let { pendingRemove = it }
                                }
                            )
                        }
                    }
                } else {
                    EmptyShelf(dark, label = "Your shelf is empty. Tap + to import a book.")
                }

                if (continuing.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    TitledShelf(
                        title = "Continue reading",
                        subtitle = "Back to where you left off",
                        spines = continuing.map { it.toSpine() },
                        dark = dark,
                        shelfHeight = 132.dp,
                        emptyLabel = "Nothing in progress",
                        onOpen = { spine -> books.firstOrNull { it.id == spine.id }?.let { lifted = it } },
                        onLongPress = { spine ->
                            books.firstOrNull { it.id == spine.id }?.let { pendingRemove = it }
                        }
                    )
                }

                if (filter == LibFilter.WANT) {
                    TitledShelf(
                        title = "Want to Read",
                        subtitle = "Saved discoveries",
                        spines = wantToRead.map { it.toSpine() },
                        dark = dark,
                        shelfHeight = 132.dp,
                        emptyLabel = "Long-press a For You spine to save one",
                        onOpen = { spine -> wantToRead.firstOrNull { it.volumeId == spine.id }?.let(onOpenDiscovery) }
                    )
                }

                Text(
                    "Hold a book to remove it",
                    Modifier.padding(start = 20.dp, top = 18.dp),
                    color = mute, fontSize = 11.sp
                )
            }

            val open = lifted
            if (open != null) {
                BookLiftOverlay(
                    book = open,
                    dark = dark,
                    onRead = {
                        lifted = null
                        onOpen(open)
                    },
                    onDismiss = { lifted = null }
                )
            }
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
                    onRemove(doomed.id)
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("Cancel") }
            }
        )
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

private fun recentlyRead(books: List<LibraryBook>): List<LibraryBook> {
    val current = books.filter { it.shelf == Shelf.CURRENT }.sortedByDescending { it.lastReadAt }
    val rest = books.filter { it.shelf != Shelf.CURRENT }
        .sortedWith(compareByDescending<LibraryBook> { it.lastReadAt }.thenByDescending { it.addedAt })
    return (current + rest).distinctBy { it.id }.take(12)
}
