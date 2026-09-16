package com.lantern.library.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.CloudAccount
import com.lantern.library.data.LibraryBook
import com.lantern.library.data.ReaderTheme
import com.lantern.library.data.ReadingPrefs
import com.lantern.library.ui.components.AuroraBackdrop
import com.lantern.library.ui.components.GlassCard
import com.lantern.library.ui.components.LoreActionButton
import com.lantern.library.ui.components.LoreGhostButton
import com.lantern.library.ui.components.LorePillButton
import com.lantern.library.ui.theme.Ink
import com.lantern.library.ui.theme.InkSoft
import com.lantern.library.ui.theme.NightText
import com.lantern.library.ui.theme.Playfair

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
    onLibraryAppearance: () -> Unit = {}
) {
    val dark = prefs.theme == ReaderTheme.DARK
    val ink = if (dark) NightText else Ink
    val mute = if (dark) Color(0xFFD0D6DE) else InkSoft
    AuroraBackdrop(prefs.theme) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("You", color = ink, fontFamily = Playfair, fontSize = 30.sp)
            Text("${books.count { it.finished }} finished  ·  ${books.size} on the shelf", color = mute, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            GlassCard(Modifier.fillMaxWidth(), dark, 20) {
                Column(Modifier.padding(18.dp)) {
                    Text("Appearance", color = ink, fontSize = 16.sp)
                    Spacer(Modifier.height(10.dp))
                    LoreGhostButton("Light Mode", selected = !dark) {
                        onPrefs(prefs.copy(theme = ReaderTheme.LIGHT))
                    }
                    Spacer(Modifier.height(8.dp))
                    LoreGhostButton("Dark Mode", selected = dark) {
                        onPrefs(prefs.copy(theme = ReaderTheme.DARK))
                    }
                    Spacer(Modifier.height(10.dp))
                    LorePillButton("Library Appearance", onClick = onLibraryAppearance)
                }
            }
            Spacer(Modifier.height(12.dp))
            GlassCard(Modifier.fillMaxWidth(), dark, 20) {
                Column(Modifier.padding(18.dp)) {
                    Text("For You", color = ink, fontSize = 16.sp)
                    Text(
                        "Recommendations use only the genres you picked, not your library.",
                        color = mute,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
                    )
                    LorePillButton("Edit reading interests", onClick = onEditInterests)
                }
            }
            Spacer(Modifier.height(12.dp))
            GlassCard(Modifier.fillMaxWidth(), dark, 20) {
                Column(Modifier.padding(18.dp)) {
                    Text("Account", color = ink, fontSize = 16.sp)
                    if (account.signedIn) {
                        Text(account.displayName, color = ink, modifier = Modifier.padding(top = 8.dp))
                        Text(account.email, color = mute)
                        if (account.provider == "google") {
                            if (account.driveConnected) {
                                Text("Library, progress, bookmarks, and settings sync to your account. EPUB and PDF files stay on this phone.", color = mute, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                            } else {
                                Text("Account sync is not connected. Everything stays on this phone.", color = mute, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
                                LorePillButton("Connect account sync", onClick = onConnectDrive)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        LoreGhostButton("Sign out", selected = false, onClick = onSignOut)
                    } else {
                        Text("Sign in with Google to keep library, progress, and settings in your account. Book files stay on this phone.", color = mute, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
                        LoreActionButton("Sign in with Google", onClick = onGoogleSignIn, mark = "G")
                    }
                }
            }
            Spacer(Modifier.height(80.dp))
        }
    }
}
