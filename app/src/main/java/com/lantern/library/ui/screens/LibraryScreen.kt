package com.lantern.library.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.lantern.library.data.LibraryBook

/**
 * The library is the bookcase.
 *
 * A revolving case is one continuous curved surface, so it cannot be five separate shelves: the
 * books live on a single drum that turns, and every book on it comes past the reader in turn.
 * Recommendations and wishlist rows are not part of the case and have their own tabs.
 *
 * The shelf is drawn by Filament (see [CylinderShelfScreen]); this screen only decides what the
 * shelf is allowed to do — open a book, add one, or take one away.
 */
@Composable
fun LibraryScreen(
    books: List<LibraryBook>,
    onOpen: (LibraryBook) -> Unit,
    onRemove: (String) -> Unit,
    onImport: () -> Unit = {}
) {
    var pendingRemove by remember { mutableStateOf<LibraryBook?>(null) }

    Box(Modifier.fillMaxSize().background(Color(0xFF100B07))) {
        CylinderShelfScreen(
            books = books,
            onOpenBook = onOpen,
            onBack = {},
            title = "Library",
            showBack = false,
            searchable = true,
            onImport = onImport,
            onLongPressBook = { book -> pendingRemove = book }
        )
    }

    val doomed = pendingRemove
    if (doomed != null) {
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Remove book") },
            text = { Text("Remove \u201C${doomed.title}\u201D from your library?") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemove = null
                    onRemove(doomed.id)
                }) { Text("Remove", color = Color(0xFF8C3B32), fontSize = 14.sp) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) {
                    Text("Keep", fontSize = 14.sp)
                }
            }
        )
    }
}

