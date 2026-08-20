package com.lantern.library.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.DiscoveryBook
import com.lantern.library.data.ReaderTheme
import com.lantern.library.ui.components.CoverFace
import com.lantern.library.ui.components.auroraBrush
import com.lantern.library.ui.theme.Coral
import com.lantern.library.ui.theme.Ink
import com.lantern.library.ui.theme.InkSoft
import com.lantern.library.ui.theme.NightText
import com.lantern.library.ui.theme.Playfair

@Composable
fun BookDetailsOverlay(
    book: DiscoveryBook,
    theme: ReaderTheme,
    saved: Boolean,
    onDismiss: () -> Unit,
    onWant: () -> Unit,
    onGet: () -> Unit
) {
    val dark = theme == ReaderTheme.DARK
    val ink = if (dark) NightText else Ink
    val mute = if (dark) Color(0xFFD0D6DE) else InkSoft
    val film = if (dark) Color(0x66404A5C) else Color(0x55EDE4F6)
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize().background(auroraBrush(dark))) {
        Box(Modifier.fillMaxSize().background(film))
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.ArrowBack, "Back", tint = ink,
                    modifier = Modifier.size(36.dp).clickable(onClick = onDismiss).padding(4.dp)
                )
                Text(
                    "Lore", Modifier.padding(start = 4.dp),
                    color = ink, fontFamily = Playfair, fontSize = 20.sp
                )
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CoverFace(
                    book.title, null, book.coverUrl,
                    Modifier.width(168.dp).aspectRatio(0.68f), dark
                )
                Spacer(Modifier.height(16.dp))
                Text(book.title, color = ink, fontFamily = Playfair, fontSize = 24.sp, textAlign = TextAlign.Center)
                Text(
                    book.authorLine, color = mute, fontSize = 15.sp,
                    modifier = Modifier.padding(top = 6.dp), textAlign = TextAlign.Center
                )
                val meta = buildList {
                    if (book.publishedDate.isNotBlank()) add(book.publishedDate)
                    if (book.ratingsCount > 0) add("★ ${"%.1f".format(book.averageRating)}  ·  ${book.ratingsCount}")
                    if (book.publicDomain) add("Public domain")
                }
                if (meta.isNotEmpty()) {
                    Text(
                        meta.joinToString("   "),
                        color = mute, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp), textAlign = TextAlign.Center
                    )
                }
                if (book.categories.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                    ) {
                        book.categories.take(8).forEach { cat ->
                            Text(
                                cat,
                                Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0x33FFFFFF))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                color = ink, fontSize = 11.sp, maxLines = 1
                            )
                        }
                    }
                }
                if (book.description.isNotBlank()) {
                    Text(
                        book.description,
                        color = ink, fontSize = 15.sp, lineHeight = 22.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                    )
                } else {
                    Text(
                        "No synopsis available.",
                        color = mute, fontSize = 14.sp,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
                val source = when {
                    book.publicDomain -> "Free edition when available in the public-domain catalog."
                    book.infoLink != null || book.buyLink != null || book.previewLink != null ->
                        "Preview and purchase via Google Books. Lore does not download this title."
                    else -> "Metadata from Google Books."
                }
                Text(source, color = mute, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp))
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (saved) "Saved" else "Want to Read",
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                        .background(if (saved) Color(0x66FFFFFF) else Coral)
                        .clickable(onClick = onWant)
                        .padding(vertical = 13.dp),
                    color = if (saved) ink else Color.White,
                    fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center
                )
                Text(
                    if (book.publicDomain) "Read / Get Book" else "Get Book",
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                        .background(Color(0x44FFFFFF))
                        .clickable(onClick = onGet)
                        .padding(vertical = 13.dp),
                    color = ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center
                )
            }
        }
    }
}
