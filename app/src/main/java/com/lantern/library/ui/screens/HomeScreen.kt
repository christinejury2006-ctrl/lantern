package com.lantern.library.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.BookBackupState
import com.lantern.library.data.CloudAccount
import com.lantern.library.data.DiscoveryBook
import com.lantern.library.data.LibraryBook
import com.lantern.library.ui.components.AuroraOrb
import com.lantern.library.ui.components.CoverFace
import com.lantern.library.ui.components.GlassSurface
import com.lantern.library.ui.components.NookBackdrop
import com.lantern.library.ui.components.NookProgress
import com.lantern.library.ui.components.NookSectionTitle
import com.lantern.library.ui.theme.NookPalette
import com.lantern.library.ui.theme.Playfair
import com.lantern.library.ui.theme.nookPalette

/**
 * The Reading Nook: the app's home.
 *
 * Home is the room the reader reads in: the editor's picks pinned at the top, and their own shelf
 * under it as one grid of covers. Managing the shelf — categories, search, removing a book — lives
 * on the Library tab, where the reader expects a shelf to be.
 *
 * The community card that used to sit at the bottom is gone. It was copy, not a feature.
 */
@Composable
fun ReadingNookScreen(
    books: List<LibraryBook>,
    picks: List<DiscoveryBook>,
    account: CloudAccount,
    theme: com.lantern.library.data.ReaderTheme,
    picksBusy: Boolean = false,
    picksFailed: Boolean = false,
    onOpenBook: (LibraryBook) -> Unit,
    onOpenPick: (DiscoveryBook) -> Unit,
    onSearch: () -> Unit,
    onLibrary: () -> Unit,
    onRefreshPicks: () -> Unit,
    onNotifications: () -> Unit
) {
    val palette = nookPalette(theme)

    NookBackdrop(palette) {
        Column(Modifier.fillMaxSize()) {
            NookHeader(palette, account, onNotifications)
            // The editor's picks sit above the shelf and stay there: they are the one part of the
            // page that is about books the reader does not own yet, and it should not disappear
            // the moment they start scrolling their own.
            PicksBar(palette, picks, picksBusy, picksFailed, onOpenPick, onRefreshPicks)

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 120.dp)
            ) {
                item { SearchPill(palette, onSearch, onLibrary) }

                item {
                    Spacer(Modifier.height(22.dp))
                    NookSectionTitle("My Shelf", palette) {
                        Text(
                            "${books.size}",
                            color = palette.mute,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                if (books.isEmpty()) {
                    item { EmptyShelf(palette, onLibrary) }
                } else {
                    // One shelf, in the order the reader added the books. Whether a book is in the
                    // account is shown on the cover itself (the little cloud), not by splitting
                    // the shelf into groups that read like two different libraries.
                    items(books.chunked(3).size) { index ->
                        BookRow(books.chunked(3)[index], palette, onOpenBook)
                    }
                }
            }
        }
    }
}

/** One row of three covers, with the progress each book has reached under it. */
@Composable
private fun BookRow(
    row: List<LibraryBook>,
    palette: NookPalette,
    onOpenBook: (LibraryBook) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        row.forEach { book ->
            ShelfTile(book, palette, Modifier.weight(1f)) { onOpenBook(book) }
        }
        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

/**
 * Editor's Choice, pinned above the shelf.
 *
 * A fixed strip rather than a section inside the scroll: the reader asked for recommendations that
 * keep their place, and this is the simplest honest way to give them that without a sticky header
 * fighting the list for the top of the screen.
 */
@Composable
private fun PicksBar(
    palette: NookPalette,
    picks: List<DiscoveryBook>,
    picksBusy: Boolean,
    picksFailed: Boolean,
    onOpenPick: (DiscoveryBook) -> Unit,
    onRefreshPicks: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)) {
        NookSectionTitle("Editor's Choice", palette) {
            Text(
                if (picksFailed) "Retry" else "Refresh",
                color = palette.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = !picksBusy, onClick = onRefreshPicks)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        when {
            picksBusy && picks.isEmpty() -> PicksPlaceholder(palette)
            picks.isEmpty() -> EmptyPicks(palette, picksFailed, onRefreshPicks)
            else -> LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(picks, key = { it.volumeId }) { pick -> PickCard(pick, palette) { onOpenPick(pick) } }
            }
        }
    }
}

/** Said plainly when nothing has been confirmed in the account yet. */
@Composable
private fun NookHeader(
    palette: NookPalette,
    account: CloudAccount,
    onNotifications: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Left slot balances the two buttons on the right so the title stays centred.
            Spacer(Modifier.width(38.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "THE",
                    color = palette.mute,
                    fontSize = 11.sp,
                    letterSpacing = 4.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "READING",
                    color = palette.ink,
                    fontFamily = Playfair,
                    fontSize = 25.sp,
                    letterSpacing = 1.sp
                )
                Text(
                    "NOOK",
                    color = palette.ink,
                    fontFamily = Playfair,
                    fontSize = 25.sp,
                    letterSpacing = 6.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Studio used to sit here. Explore now finds and fetches a book in one step, so a
                // separate import workshop was a second way to do the same thing.
                GlassSurface(palette, Modifier.size(38.dp), shape = CircleShape, onClick = onNotifications) {
                    Icon(
                        Icons.Filled.Notifications,
                        "Notices",
                        tint = palette.ink,
                        modifier = Modifier.align(Alignment.Center).size(18.dp)
                    )
                    if (account.signedIn && !account.driveConnected) {
                        // A signed-in account with Drive not yet granted is the one state that
                        // needs saying out loud: sync will not run until it is.
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(palette.accent)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchPill(palette: NookPalette, onSearch: () -> Unit, onShelf: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        GlassSurface(
            palette,
            Modifier.weight(1f),
            shape = RoundedCornerShape(24.dp),
            onClick = onSearch
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Search, "Search", tint = palette.mute, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("Search", color = palette.mute, fontSize = 14.sp)
            }
        }
        // The bookcase itself: this is the button that opens the revolving case.
        GlassSurface(palette, Modifier.size(44.dp), shape = RoundedCornerShape(16.dp), onClick = onShelf) {
            ShelfGlyph(palette, Modifier.align(Alignment.Center).size(22.dp))
        }
    }
}

/** Three little books on a shelf, drawn rather than iconified so it matches the covers. */
@Composable
private fun ShelfGlyph(palette: NookPalette, modifier: Modifier = Modifier) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        listOf(13.dp, 20.dp, 10.dp).forEachIndexed { i, h ->
            Box(
                Modifier
                    .width(5.dp)
                    .height(h)
                    .clip(RoundedCornerShape(1.dp))
                    .background(palette.aurora[i % palette.aurora.size].copy(alpha = 0.9f))
            )
        }
    }
}

@Composable
private fun ShelfTile(
    book: LibraryBook,
    palette: NookPalette,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(12.dp))
                .background(palette.coverFallback)
                .clickable(onClick = onClick)
        ) {
            CoverFace(
                book.title,
                book.localCover,
                book.remoteCover,
                Modifier.fillMaxSize(),
                palette.dark
            )
            if (book.backupState == BookBackupState.BACKED_UP) {
                // Backed up, and only once the upload was actually confirmed. This is the whole of
                // "synced or not" now: a mark on the cover rather than a group of its own.
                Text(
                    "☁",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x55000000))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
            } else if (!book.localFileAvailable) {
                // Not a defect: the book lives in the account and is fetched when opened. Say so
                // quietly on the cover rather than pretending the file is on the phone.
                Text(
                    "☁",
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x66000000))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        NookProgress(book.progress, palette)

    }
}

@Composable
private fun PickCard(book: DiscoveryBook, palette: NookPalette, onClick: () -> Unit) {
    Column(Modifier.width(132.dp)) {
        Box(Modifier.fillMaxWidth().height(184.dp), contentAlignment = Alignment.Center) {
            // The glow: two blurred orbs behind the cover, pink and cyan, which is the pattern in
            // the design — a cover standing in coloured light rather than on a coloured card.
            AuroraOrb(palette, 118.dp, Modifier.align(Alignment.Center).rotate(-14f), blur = 26.dp)
            AuroraOrb(
                palette, 96.dp,
                Modifier.align(Alignment.Center).padding(start = 26.dp, top = 24.dp).rotate(18f),
                blur = 24.dp
            )
            GlassSurface(palette, Modifier.fillMaxWidth().padding(horizontal = 4.dp), shape = RoundedCornerShape(16.dp)) {
                Box(Modifier.fillMaxWidth().height(176.dp).padding(6.dp)) {
                    CoverFace(
                        book.title, null, book.coverUrl,
                        Modifier.fillMaxSize().clip(RoundedCornerShape(11.dp)).clickable(onClick = onClick),
                        palette.dark,
                        book.coverUrls
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            book.title,
            color = palette.ink,
            fontFamily = Playfair,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Text(
            book.authors.firstOrNull().orEmpty(),
            color = palette.mute,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PicksPlaceholder(palette: NookPalette) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        repeat(3) {
            Box(
                Modifier
                    .width(132.dp)
                    .height(184.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.ink.copy(alpha = 0.06f))
            )
        }
    }
}

@Composable
private fun EmptyPicks(palette: NookPalette, failed: Boolean, onRetry: () -> Unit) {
    GlassSurface(
        palette,
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        shape = RoundedCornerShape(18.dp),
        onClick = onRetry
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (failed) "Today's picks could not be fetched" else "No picks yet",
                color = palette.ink,
                fontFamily = Playfair,
                fontSize = 15.sp
            )
            Text(
                if (failed) "Tap to try again. Your shelf is unaffected."
                else "Pick a few interests in your profile and the nook will fill itself.",
                color = palette.mute,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun EmptyShelf(palette: NookPalette, onImport: () -> Unit) {
    GlassSurface(
        palette,
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        shape = RoundedCornerShape(20.dp),
        onClick = onImport
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("An empty shelf", color = palette.ink, fontFamily = Playfair, fontSize = 18.sp)
            Text(
                "Import an EPUB or PDF and it will stand here, with your place kept.",
                color = palette.mute,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                "Import a book",
                color = palette.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 14.dp)
            )
        }
    }
}

