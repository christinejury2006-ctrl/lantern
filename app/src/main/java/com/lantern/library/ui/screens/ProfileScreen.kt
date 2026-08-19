package com.lantern.library.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.CloudAccount
import com.lantern.library.data.LibraryBook
import com.lantern.library.data.ReaderTheme
import com.lantern.library.data.ReadingPrefs
import com.lantern.library.ui.components.AuroraBackdrop
import com.lantern.library.ui.components.GlassCard
import com.lantern.library.ui.theme.Coral
import com.lantern.library.ui.theme.Ink
import com.lantern.library.ui.theme.InkSoft
import com.lantern.library.ui.theme.NightText
import com.lantern.library.ui.theme.Playfair

@Composable
fun ProfileScreen(books: List<LibraryBook>, account: CloudAccount, prefs: ReadingPrefs, onPrefs: (ReadingPrefs) -> Unit, onSignIn: (String, String) -> Unit, onSignOut: () -> Unit) {
    val dark = prefs.theme == ReaderTheme.DARK
    val ink = if (dark) NightText else Ink
    val mute = if (dark) Color(0xFFD0D6DE) else InkSoft
    var name by remember { mutableStateOf(account.displayName) }
    var email by remember { mutableStateOf(account.email) }
    AuroraBackdrop(prefs.theme) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("You", color = ink, fontFamily = Playfair, fontSize = 30.sp)
            Text("${books.count { it.finished }} finished  ·  ${books.size} on the shelf", color = mute, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            GlassCard(Modifier.fillMaxWidth(), dark, 20) {
                Column(Modifier.padding(18.dp)) {
                    Text("Appearance", color = ink, fontSize = 16.sp)
                    Text("Light Mode", color = if (!dark) Coral else ink, modifier = Modifier.fillMaxWidth().clickable { onPrefs(prefs.copy(theme = ReaderTheme.LIGHT)) }.padding(vertical = 10.dp))
                    Text("Dark Mode", color = if (dark) Coral else ink, modifier = Modifier.fillMaxWidth().clickable { onPrefs(prefs.copy(theme = ReaderTheme.DARK)) }.padding(vertical = 10.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            GlassCard(Modifier.fillMaxWidth(), dark, 20) {
                Column(Modifier.padding(18.dp)) {
                    Text("Account", color = ink, fontSize = 16.sp)
                    if (account.signedIn) {
                        Text(account.displayName, color = ink); Text(account.email, color = mute)
                        Text("Sign out", color = Coral, modifier = Modifier.padding(top = 10.dp).clickable(onClick = onSignOut))
                    } else {
                        GlassCard(Modifier.fillMaxWidth(), radius = 14) {
                            BasicTextField(name, { name = it }, singleLine = true, textStyle = TextStyle(color = ink, fontSize = 15.sp), cursorBrush = SolidColor(ink), modifier = Modifier.fillMaxWidth().padding(12.dp), decorationBox = { inner -> if (name.isEmpty()) Text("Name", color = mute); inner() })
                        }
                        Spacer(Modifier.height(8.dp))
                        GlassCard(Modifier.fillMaxWidth(), radius = 14) {
                            BasicTextField(email, { email = it }, singleLine = true, textStyle = TextStyle(color = ink, fontSize = 15.sp), cursorBrush = SolidColor(ink), modifier = Modifier.fillMaxWidth().padding(12.dp), decorationBox = { inner -> if (email.isEmpty()) Text("Email", color = mute); inner() })
                        }
                        Text("Sign in", color = Coral, modifier = Modifier.padding(top = 12.dp).clickable { if (name.isNotBlank() && email.isNotBlank()) onSignIn(name.trim(), email.trim()) })
                    }
                }
            }
            Spacer(Modifier.height(80.dp))
        }
    }
}
