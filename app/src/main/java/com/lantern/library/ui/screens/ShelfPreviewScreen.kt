package com.lantern.library.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.LibraryBook
import com.lantern.library.ui.components.LorePillButton
import com.lantern.library.ui.components.RevolvingBookshelf
import com.lantern.library.ui.components.ShelfRoom
import com.lantern.library.ui.components.Spine
import com.lantern.library.ui.theme.Parchment
import com.lantern.library.ui.theme.Playfair

/**
 * The shelf as a revolving bookcase.
 *
 * Sweep sideways and the row turns and settles, one book at a time. Tapping the book the shelf
 * is offering lifts it; tapping it again opens it.
 *
 * This is built from ordinary Compose views — a snapping [RevolvingBookshelf] over a painted
 * room — so type stays sharp at any zoom, and the room can be blurred or faded behind an open
 * book like the rest of the app.
 */
@Composable
fun ShelfPreviewScreen(
    books: List<LibraryBook>,
    onOpenBook: (LibraryBook) -> Unit,
    onBack: () -> Unit
) {
    val spines = remember(books) {
        books.map {
            Spine(
                id = it.id,
                title = it.title,
                author = it.author,
                pages = it.pageCount,
                progress = it.progress,
                coverRes = it.localCover,
                coverUrl = it.remoteCover
            )
        }
    }

    var liftedId by remember { mutableStateOf<String?>(null) }
    var centred by remember { mutableStateOf<Spine?>(null) }

    // If the shelf scrolls away from the lifted book, put it back: a book only stays out of
    // the shelf while you are looking at it.
    LaunchedEffect(centred?.id) {
        if (liftedId != null && centred?.id != liftedId) liftedId = null
    }

    val lifted = spines.firstOrNull { it.id == liftedId }

    ShelfRoom(dark = true) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 22.dp, end = 18.dp, top = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Your shelf", color = Parchment, fontFamily = Playfair, fontSize = 30.sp)
                    Text(
                        if (spines.isEmpty()) "Nothing on it yet"
                        else "${spines.size} ${if (spines.size == 1) "book" else "books"} · sweep to turn the case",
                        color = Color(0xFFC9B79F),
                        fontSize = 12.sp
                    )
                }
                LorePillButton("Back", onClick = onBack)
            }

            Spacer(Modifier.height(18.dp))

            if (spines.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Add a book and it will appear on the shelf.",
                        color = Color(0xFFC9B79F),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 40.dp)
                    )
                }
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    // The painted room sits behind the row, dimmed so the spines carry the eye.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Transparent,
                                        Color(0xFF160F0A).copy(alpha = 0.55f)
                                    )
                                )
                            )
                    )
                    RevolvingBookshelf(
                        spines = spines,
                        modifier = Modifier.fillMaxWidth(),
                        liftedId = liftedId,
                        onCentred = { centred = it },
                        onTap = { spine ->
                            if (liftedId == spine.id) {
                                books.firstOrNull { it.id == spine.id }?.let(onOpenBook)
                            } else {
                                liftedId = spine.id
                            }
                        }
                    )
                }

                // Caption: what the shelf is offering, or what you have just pulled out.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val shown = lifted ?: centred
                    Text(
                        shown?.title ?: "",
                        color = Parchment,
                        fontFamily = Playfair,
                        fontSize = 18.sp,
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        when {
                            shown == null -> ""
                            lifted != null -> shown.author.ifBlank { "Tap it again to open" }
                            else -> "Tap to lift it out"
                        },
                        color = Color(0xFFC9B79F),
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
            }
            Spacer(Modifier.height(120.dp))
        }
    }
}
