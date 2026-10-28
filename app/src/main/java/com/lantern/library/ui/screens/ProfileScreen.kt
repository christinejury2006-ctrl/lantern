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
import com.lantern.library.data.BookFormat
import com.lantern.library.data.BookOrigin
import com.lantern.library.data.CloudAccount
import com.lantern.library.data.LibraryBook
import com.lantern.library.data.ReaderTheme
import com.lantern.library.data.ReadingPrefs
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
 * The reader's own page: who they are, what sync is doing, and every setting.
 *
 * The account card describes exactly what leaves the phone and where it goes, because the
 * previous copy said "Book files stay on this phone" while the app was uploading EPUBs and PDFs
 * to Google Drive. Both halves are stated now: meaning, and file.
 *
 * The sync rows are counts of the library itself — books with a cloud copy, books still waiting
 * for one — rather than a single "Synced" tick. A tick that covers forty books and one file is
 * the kind of reassurance that turns out to be wrong.
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
    onLibraryAppearance: () -> Unit = {},
    onSyncNow: () -> Unit = {}
) {
    val palette = nookPalette(prefs.theme)
    val fileBooks = books.filter { it.origin != BookOrigin.BUNDLED && it.format != BookFormat.TEXT }
    val backedUp = fileBooks.count { !it.driveFileId.isNullOrBlank() }
    val waiting = fileBooks.count { it.driveFileId.isNullOrBlank() && it.localFileAvailable }
    val elsewhere = fileBooks.count { it.driveFileId.isNullOrBlank() && !it.localFileAvailable }

    NookBackdrop(palette) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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
                            SyncRow(
                                palette,
                                "Library, progress, bookmarks and settings",
                                "Synced to your account"
                            )
                            SyncRow(
                                palette,
                                "Book files (EPUB and PDF)",
                                if (fileBooks.isEmpty()) "No files to back up yet"
                                else "$backedUp of ${fileBooks.size} backed up"
                            )
                            if (waiting > 0) {
                                SyncRow(palette, "Waiting to upload", "$waiting book${if (waiting == 1) "" else "s"}")
                            }
                            if (elsewhere > 0) {
                                SyncRow(palette, "Not on this phone", "$elsewhere available from your account")
                            }
                            Text(
                                "Book files are stored in Lore's private application folder in your " +
                                    "Google Drive. They do not appear in My Drive, and no other app can " +
                                    "read them. Removing a book here does not delete the copy in your " +
                                    "account unless you choose to.",
                                color = palette.mute,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                            Text(
                                if (account.lastSyncAt > 0L)
                                    "Last sync " + DateFormat.getDateTimeInstance(
                                        DateFormat.MEDIUM, DateFormat.SHORT
                                    ).format(Date(account.lastSyncAt))
                                else "Not synced yet",
                                color = palette.faint,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            NookButton(palette, "Sync now", onSyncNow, filled = true)
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
                    NookButton(palette, "Library appearance", onLibraryAppearance, filled = false)
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
