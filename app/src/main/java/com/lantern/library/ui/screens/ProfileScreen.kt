package com.lantern.library.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.CloudAccount
import com.lantern.library.data.LibraryBook
import com.lantern.library.data.ReaderTheme
import com.lantern.library.data.ReadingPrefs
import com.lantern.library.data.CloudPanel
import com.lantern.library.data.TransferProgress
import com.lantern.library.ui.components.AuroraOrb
import com.lantern.library.ui.components.GlassSurface
import com.lantern.library.ui.components.NookBackdrop
import com.lantern.library.ui.components.NookSectionTitle
import com.lantern.library.ui.theme.NookPalette
import com.lantern.library.ui.theme.Playfair
import com.lantern.library.ui.theme.nookPalette
import java.text.DateFormat
import java.util.Date

/**
 * The reader's own page: who they are, what the account holds, and every setting.
 *
 * The cloud panel is four numbers and one line of honest remainder text. It used to be a wall of
 * diagnosis — stage logs, a "test connection" button, a per-book dump — which answered questions
 * nobody asks and left the real one ("is my library safe?") buried. The functionality behind it is
 * untouched; only the shop window changed. The arithmetic itself lives in [CloudPanel], where it
 * is tested, rather than being re-derived in a composable.
 */
@Composable
fun ProfileScreen(
    books: List<LibraryBook>,
    account: CloudAccount,
    prefs: ReadingPrefs,
    onPrefs: (ReadingPrefs) -> Unit,
    onGoogleSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onConnectDrive: () -> Unit = {},
    onEditInterests: () -> Unit = {},
    onSyncNow: () -> Unit = {},
    /** Live transfers, so "still uploading" means bytes are moving, not that a queue is long. */
    transfers: Map<String, TransferProgress> = emptyMap(),
    /** Books the upload journal still owes, minus what is moving and what the reader paused. */
    queuedIds: Set<String> = emptySet(),
    /** Uploads the reader paused: reported separately, never counted as uploading. */
    pausedUploads: Set<String> = emptySet()
) {
    val palette = nookPalette(prefs.theme)
    // Every number on the panel comes from the same function, so they add up by construction.
    val metrics = CloudPanel.metrics(
        books = books,
        transfers = transfers,
        queuedIds = queuedIds,
        pausedUploads = pausedUploads,
        online = account.driveConnected
    )

    NookBackdrop(palette) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                // Same shared clearance as the other tabs: the last card must be able to clear
                // the bottom bar, not disappear under it.
                .padding(bottom = com.lantern.library.data.SystemBars.CONTENT_BOTTOM_DP.dp)
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 26.dp)) {
                Text("You", color = palette.ink, fontFamily = Playfair, fontSize = 30.sp)
                Text(
                    "${books.count { it.finished }} finished  ·  ${books.size} on the shelf",
                    color = palette.mute,
                    fontSize = 13.sp
                )
            }

            NookSectionTitle("Account", palette)
            Spacer(Modifier.height(10.dp))
            GlassSurface(
                palette,
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(Modifier.padding(18.dp)) {
                    if (account.signedIn) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AuroraOrb(palette, 34.dp, Modifier.size(34.dp))
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(
                                    account.displayName.ifBlank { "Signed in" },
                                    color = palette.ink,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(account.email, color = palette.mute, fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(14.dp))

                        if (account.provider == "google" && account.driveConnected) {
                            Text(
                                "Your library, reading progress, bookmarks and settings are in your " +
                                    "account. The panel below says what has actually arrived there.",
                                color = palette.mute,
                                fontSize = 12.sp
                            )
                        } else {
                            Text(
                                "Signing in keeps your library, reading progress, bookmarks and settings " +
                                    "in your account, and backs up the EPUB and PDF files themselves to " +
                                    "Lore's private folder in Google Drive so a new phone can restore them.",
                                color = palette.mute,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                            )
                            if (account.provider == "google") {
                                NookButton(palette, "Connect account backup", onConnectDrive, filled = true)
                            } else {
                                NookButton(palette, "Sign in with Google", onGoogleSignIn, filled = true)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        NookButton(palette, "Sign out", onSignOut, filled = false)
                    } else {
                        Text(
                            "Sign in with Google to keep your library, progress and settings in your " +
                                "account, and to back up your book files to Lore's private folder in " +
                                "Google Drive. Nothing leaves this phone until you do.",
                            color = palette.mute,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
                        )
                        NookButton(palette, "Sign in with Google", onGoogleSignIn, filled = true)
                    }
                }
            }

            // The four numbers, and nothing else. Whatever is not in the first two is named on the
            // remainder line, so the reader can add them up and get the shelf total.
            if (account.signedIn && account.driveConnected) {
                Spacer(Modifier.height(22.dp))
                NookSectionTitle("Your cloud library", palette)
                Spacer(Modifier.height(10.dp))
                GlassSurface(
                    palette,
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CloudStat(palette, metrics.total.toString(), "On the shelf", Modifier.weight(1f))
                            CloudStat(
                                palette,
                                metrics.verified.toString(),
                                "Uploaded & verified",
                                Modifier.weight(1f),
                                emphasise = metrics.verified > 0
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CloudStat(palette, metrics.failed.toString(), "Failed", Modifier.weight(1f))
                            CloudStat(
                                palette,
                                metrics.uploading.toString(),
                                if (metrics.paused > 0) "Still uploading · ${metrics.paused} paused"
                                else "Still uploading",
                                Modifier.weight(1f)
                            )
                        }
                        val rest = metrics.remainder()
                        if (rest.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            rest.forEach { (label, count) ->
                                SyncRow(palette, label, "$count book${if (count == 1) "" else "s"}")
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (metrics.verified > 0)
                                "Only a book that is uploaded and verified can be removed from this " +
                                    "phone and restored later. Removing it here never deletes the copy " +
                                    "in your account."
                            else "Nothing is verified yet. Removing a book from this phone right now " +
                                "would lose the only copy you have.",
                            color = palette.mute,
                            fontSize = 11.sp
                        )
                        if (account.lastSyncAt > 0L) {
                            Text(
                                "Last sync " + DateFormat.getDateTimeInstance(
                                    DateFormat.MEDIUM, DateFormat.SHORT
                                ).format(Date(account.lastSyncAt)),
                                color = palette.faint,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        NookButton(palette, "Sync now", onSyncNow, filled = true)
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            NookSectionTitle("Appearance", palette)
            Spacer(Modifier.height(10.dp))
            GlassSurface(
                palette,
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NookChip(palette, "Light", prefs.theme == ReaderTheme.LIGHT) {
                            onPrefs(prefs.copy(theme = ReaderTheme.LIGHT))
                        }
                        NookChip(palette, "Dark", prefs.theme == ReaderTheme.DARK) {
                            onPrefs(prefs.copy(theme = ReaderTheme.DARK))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            Spacer(Modifier.height(22.dp))
            NookSectionTitle("Recommendations", palette)
            Spacer(Modifier.height(10.dp))
            GlassSurface(
                palette,
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        "Daily picks use only the genres you chose, never your reading history.",
                        color = palette.mute,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    NookButton(palette, "Edit reading interests", onEditInterests, filled = false)
                }
            }
            Spacer(Modifier.height(120.dp))
        }
    }
}

/**
 * One of the four numbers, as a tile.
 *
 * Value on top in the display face, label underneath: at a glance the reader gets "what" and
 * "how many", without a legend or a tooltip.
 */
@Composable
private fun CloudStat(
    palette: NookPalette,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    emphasise: Boolean = false
) {
    GlassSurface(
        palette,
        modifier,
        shape = RoundedCornerShape(16.dp),
        fill = if (emphasise) palette.accent.copy(alpha = 0.16f) else palette.glass
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                value,
                color = if (emphasise) palette.accent else palette.ink,
                fontFamily = Playfair,
                fontSize = 24.sp
            )
            Text(label, color = palette.mute, fontSize = 11.sp, maxLines = 2)
        }
    }
}

/** One line of the sync report: what, and its state. */
@Composable
private fun SyncRow(palette: NookPalette, label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = palette.ink, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = palette.mute, fontSize = 11.sp)
    }
}

@Composable
private fun NookButton(palette: NookPalette, label: String, onClick: () -> Unit, filled: Boolean) {
    GlassSurface(
        palette,
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        fill = if (filled) palette.accent else androidx.compose.ui.graphics.Color.Transparent,
        onClick = onClick
    ) {
        Text(
            label,
            color = if (filled) palette.accentInk else palette.ink,
            fontSize = 13.sp,
            fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.align(Alignment.Center).padding(vertical = 12.dp)
        )
    }
}

@Composable
private fun NookChip(palette: NookPalette, label: String, on: Boolean, onClick: () -> Unit) {
    GlassSurface(
        palette,
        Modifier,
        shape = CircleShape,
        fill = if (on) palette.accent else palette.glass,
        onClick = onClick
    ) {
        Text(
            label,
            color = if (on) palette.accentInk else palette.ink,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}
