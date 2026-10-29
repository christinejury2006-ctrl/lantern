package com.lantern.library.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.lantern.library.data.BackupAudit
import com.lantern.library.data.BackupBadge
import com.lantern.library.data.CloudAccount
import com.lantern.library.data.DiscoveryBook
import com.lantern.library.data.LibraryBook
import com.lantern.library.data.TransferPhase
import com.lantern.library.data.TransferProgress
import com.lantern.library.data.Transfers
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
    onNotifications: () -> Unit,
    /** Live upload progress, keyed by book. Read-only: the shelf never writes sync state. */
    transfers: Map<String, TransferProgress> = emptyMap(),
    /** Why a book is not backed up, when the retry queue knows — shown in the detail sheet. */
    queueReasons: Map<String, String> = emptyMap(),
    online: Boolean = true,
    /** Sends one book again, from the detail sheet. */
    onRetryBackup: (String) -> Unit = {}
) {
    val palette = nookPalette(theme)
    // Which book's backup detail is open. Local to Home: opening it must not touch the shelf.
    var detailFor by remember { mutableStateOf<String?>(null) }

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
                    val ordered = books.chunked(3)
                    items(ordered.size) { index ->
                        BookRow(
                            row = ordered[index],
                            palette = palette,
                            transfers = transfers,
                            queued = queueReasons.keys,
                            online = online,
                            onOpenBook = onOpenBook,
                            onOpenBadge = { book -> detailFor = book.id }
                        )
                    }
                }
            }
        }
        // Drawn above the shelf, not inside it: a detail sheet must not re-flow the cards.
        val detailBook = detailFor?.let { id -> books.firstOrNull { it.id == id } }
        if (detailBook != null) {
            BackupDetailSheet(
                book = detailBook,
                transfer = transfers[detailBook.id],
                queuedReason = queueReasons[detailBook.id],
                online = online,
                palette = palette,
                onRetry = { onRetryBackup(detailBook.id) },
                onDismiss = { detailFor = null }
            )
        }
    }
}

/** One row of three covers, with the progress each book has reached under it. */
@Composable
private fun BookRow(
    row: List<LibraryBook>,
    palette: NookPalette,
    transfers: Map<String, TransferProgress>,
    queued: Set<String>,
    online: Boolean,
    onOpenBook: (LibraryBook) -> Unit,
    onOpenBadge: (LibraryBook) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        row.forEach { book ->
            ShelfTile(
                book = book,
                palette = palette,
                transfer = transfers[book.id],
                queued = book.id in queued,
                online = online,
                modifier = Modifier.weight(1f),
                onBadge = { onOpenBadge(book) },
                onClick = { onOpenBook(book) }
            )
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
    transfer: TransferProgress?,
    queued: Boolean,
    online: Boolean,
    modifier: Modifier = Modifier,
    onBadge: () -> Unit,
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
            // The badge is the only thing on the cover that changes, and it is drawn in the same
            // spot for every state, so a transfer never nudges the layout underneath it.
            BackupBadgeChip(
                book = book,
                transfer = transfer,
                queued = queued,
                online = online,
                palette = palette,
                onTap = onBadge,
                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp)
            )
        }
        Spacer(Modifier.height(7.dp))
        NookProgress(book.progress, palette)
    }
}

/**
 * The corner badge: how far this book's *file* has got to the account.
 *
 * Restrained on purpose — a 9 sp label in a dark pill, the same one every state uses, sitting where
 * the old cloud used to be. It answers one question and never dresses it up:
 *
 * - **a percentage** while bytes are moving, with a hairline arc that fills as they go;
 * - **✓ cloud** only after the account has proved it holds these exact bytes;
 * - **⌛** waiting for a connection, **❙❙** paused, **!** failed, **↓** restore required;
 * - **nothing** when there is no file to back up (built-in and text books).
 */
@Composable
private fun BackupBadgeChip(
    book: LibraryBook,
    transfer: TransferProgress?,
    queued: Boolean,
    online: Boolean,
    palette: NookPalette,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state = BackupAudit.badge(book, transfer, online = online, queued = queued)
    // A bundled or plain-text book has no file in the account, so it gets no badge at all.
    if (state == BackupBadge.NOT_APPLICABLE) return
    val percent = transfer?.percent
    val label = when (state) {
        // Every byte is out but the account has not proved it yet: an ellipsis, not a tick.
        BackupBadge.UPLOADING -> when {
            transfer?.phase == TransferPhase.VERIFYING -> "…"
            percent != null -> "$percent%"
            else -> "…"
        }
        BackupBadge.PAUSED -> "❙❙"
        BackupBadge.WAITING -> "⌛"
        BackupBadge.FAILED -> "!"
        BackupBadge.VERIFIED -> "☁"
        BackupBadge.RESTORE_REQUIRED -> "↓"
        BackupBadge.LOCAL_ONLY -> "·"
        BackupBadge.NOT_APPLICABLE -> ""
    }
    val tint = when (state) {
        BackupBadge.VERIFIED -> Color(0xFFEFF6FF)
        BackupBadge.FAILED -> Color(0xFFFFD9D2)
        BackupBadge.UPLOADING -> Color(0xFFFFF6E6)
        else -> Color(0xFFEDEFF6)
    }
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x66000000))
            .clickable(onClick = onTap)
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (state == BackupBadge.UPLOADING) {
            // A hairline arc: the transfer's own progress, drawn as a fraction of a circle. For a
            // file of unknown size it spins instead of pretending to be at 100%.
            MiniProgressRing(
                fraction = (percent ?: 0) / 100f,
                indeterminate = percent == null || transfer?.phase == TransferPhase.VERIFYING,
                color = tint,
                modifier = Modifier.size(9.dp)
            )
            Spacer(Modifier.width(3.dp))
        }
        Text(
            label,
            color = tint,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

/** A 9 dp ring that fills clockwise, or turns slowly when the size is unknown. */
@Composable
private fun MiniProgressRing(
    fraction: Float,
    indeterminate: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val track = color.copy(alpha = 0.28f)
    // Indeterminate: a quarter ring that rotates on its own clock, driven by the animation clock
    // rather than by a progress value, because there is no value to show.
    val spin = if (indeterminate) {
        val transition = rememberInfiniteTransition(label = "upload-spin")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1_100, easing = LinearEasing)),
            label = "upload-angle"
        ).value
    } else 0f
    Canvas(modifier) {
        val stroke = size.minDimension * 0.16f
        drawArc(
            color = track,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = stroke)
        )
        if (indeterminate) {
            drawArc(
                color = color,
                startAngle = spin,
                sweepAngle = 90f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        } else if (fraction > 0f) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = (fraction.coerceIn(0f, 1f)) * 360f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
    }
}

/**
 * The detail sheet behind a badge tap.
 *
 * What it must never do is flatter the reader: "Backed up and verified" only appears with a byte
 * count and the time of the verification, and every other state says plainly what is missing and
 * whether it will be retried. Nothing here writes to the account — the retry button calls the same
 * single-book path the sync uses.
 */
@Composable
private fun BackupDetailSheet(
    book: LibraryBook,
    transfer: TransferProgress?,
    queuedReason: String?,
    online: Boolean,
    palette: NookPalette,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val state = BackupAudit.badge(book, transfer, online = online, queued = queuedReason != null)
    val fileBytes = book.filePath?.let { java.io.File(it) }?.takeIf { it.exists() }?.length() ?: 0L
    Dialog(onDismissRequest = onDismiss) {
        GlassSurface(
            palette,
            Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "CLOUD COPY",
                    color = palette.mute,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    book.title.ifBlank { "Untitled" },
                    color = palette.ink,
                    fontFamily = Playfair,
                    fontSize = 19.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(12.dp))
                SheetRow("Status", BackupAudit.label(state), palette)
                SheetRow(
                    "Transfer",
                    when {
                        transfer == null -> if (fileBytes > 0L) "${Transfers.size(fileBytes)} on this phone" else "nothing to send"
                        else -> Transfers.transferred(transfer.sentBytes, transfer.totalBytes)
                    },
                    palette
                )
                if (transfer != null && transfer.percent != null) {
                    SheetRow("Progress", "${transfer.percent}%", palette)
                }
                SheetRow("File size", if (fileBytes > 0L) Transfers.size(fileBytes) else "not on this phone", palette)
                SheetRow(
                    "Verification",
                    when {
                        state == BackupBadge.VERIFIED ->
                            "${Transfers.size(book.backupBytes)} confirmed"
                        state == BackupBadge.RESTORE_REQUIRED -> "in the account, not on this phone"
                        state == BackupBadge.UPLOADING && transfer?.phase == TransferPhase.VERIFYING ->
                            "checking the bytes now"
                        state == BackupBadge.UPLOADING -> "waiting for the transfer to finish"
                        else -> "not verified"
                    },
                    palette
                )
                if (book.backupAt > 0L) {
                    SheetRow("Last verified", shortTime(book.backupAt), palette)
                }
                if (book.driveFileId != null) {
                    SheetRow("Account file", book.driveFileId.take(10) + "…", palette)
                }
                val reason = transfer?.reason?.takeIf { it.isNotBlank() }
                    ?: queuedReason?.takeIf { it.isNotBlank() }
                if (reason != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(reason, color = palette.accent, fontSize = 11.sp)
                }
                if (state == BackupBadge.FAILED || state == BackupBadge.PAUSED || state == BackupBadge.LOCAL_ONLY) {
                    Spacer(Modifier.height(14.dp))
                    SheetButton("Try again now", palette.accent, palette.accentInk, onRetry)
                    Spacer(Modifier.height(8.dp))
                }
                SheetButton("Close", palette.glass, palette.ink, onDismiss)
                if (state != BackupBadge.VERIFIED) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Nothing is removed from this phone by backing up or retrying.",
                        color = palette.faint,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

/** One button in the sheet, in the same glass language as the rest of the app. */
@Composable
private fun SheetButton(label: String, fill: Color, ink: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(fill)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SheetRow(label: String, value: String, palette: NookPalette) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = palette.mute, fontSize = 12.sp, modifier = Modifier.width(96.dp))
        Text(value, color = palette.ink, fontSize = 12.sp, modifier = Modifier.weight(1f))
    }
}

private fun shortTime(at: Long): String =
    android.text.format.DateFormat.format("d MMM, HH:mm", at).toString()

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

