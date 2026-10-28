package com.lantern.library.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.LibraryBook
import com.lantern.library.data.ReaderTheme
import com.lantern.library.ui.components.CoverFace
import com.lantern.library.ui.components.GlassSurface
import com.lantern.library.ui.components.NookBackdrop
import com.lantern.library.ui.components.NookProgress
import com.lantern.library.ui.theme.NookPalette
import com.lantern.library.ui.theme.Playfair
import com.lantern.library.ui.theme.nookPalette

/**
 * The library: every book the reader owns, as covers.
 *
 * This is the whole collection rather than the nook's five-item snapshot: a grid of jackets,
 * three across, with the reader's place under each one. Long-press a cover to remove it, which
 * asks first — and the account's copy is not touched unless the reader chooses that, so removing
 * a book here can never quietly delete the only other copy.
 *
 * There is no filter row. Filtering a shelf of covers by "Currently Reading" hides exactly the
 * books the reader is looking for, and the progress bars already say which is which.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    books: List<LibraryBook>,
    theme: ReaderTheme,
    onOpen: (LibraryBook) -> Unit,
    onRemove: (String) -> Unit,
    onImport: () -> Unit = {}
) {
    val palette = nookPalette(theme)
    var pendingRemove by remember { mutableStateOf<LibraryBook?>(null) }

    NookBackdrop(palette) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 130.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(span = { GridItemSpan(3) }) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Library", color = palette.ink, fontFamily = Playfair, fontSize = 30.sp)
                            Text(
                                when (books.size) {
                                    0 -> "Nothing here yet"
                                    1 -> "1 book  ·  ${books.count { it.finished }} finished"
                                    else -> "${books.size} books  ·  ${books.count { it.finished }} finished"
                                },
                                color = palette.mute,
                                fontSize = 12.sp
                            )
                        }
                        GlassSurface(
                            palette,
                            Modifier,
                            shape = RoundedCornerShape(16.dp),
                            onClick = onImport
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Add, "Import", tint = palette.ink, modifier = Modifier.height(16.dp))
                                Text(" Import", color = palette.ink, fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            items(books, key = { it.id }) { book ->
                BookTile(
                    book = book,
                    palette = palette,
                    onClick = { onOpen(book) },
                    onLongClick = { pendingRemove = book }
                )
            }
        }
    }

    val doomed = pendingRemove
    if (doomed != null) {
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Remove book") },
            text = {
                Text(
                    "Remove \u201C${doomed.title}\u201D from this phone? " +
                        if (doomed.driveFileId.isNullOrBlank())
                            "There is no copy in your account, so this book will be gone."
                        else "Your account keeps a copy, so you can restore it later."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemove = null
                    onRemove(doomed.id)
                }) { Text("Remove", color = palette.accent) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("Keep") }
            }
        )
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
                Text(
                    "☁",
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(androidx.compose.ui.graphics.Color(0x66000000))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
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
            if (book.finished) "Finished"
            else if (book.progress > 0f) "${(book.progress * 100).toInt()}% read"
            else book.author.ifBlank { "Not started" },
            color = palette.mute,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
