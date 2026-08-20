package com.lantern.library.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.DiscoveryBook
import com.lantern.library.data.ReaderTheme
import com.lantern.library.ui.components.CoverFace
import com.lantern.library.ui.components.GlassCard
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
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(Color(0x66000000)).pointerInput(Unit) {
                detectTapGestures(onTap = { onDismiss() })
            }
        )
        GlassCard(
            Modifier
                .align(Alignment.Center)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp, 24.dp)
                .fillMaxWidth()
                .fillMaxSize(0.92f)
                .clickable(enabled = false, onClick = {}),
            dark,
            24
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.ArrowBack, "Back", tint = ink,
                        modifier = Modifier.size(32.dp).clickable(onClick = onDismiss).padding(4.dp)
                    )
                    Text(
                        "Book Details", Modifier.weight(1f).padding(start = 4.dp),
                        color = ink, fontFamily = Playfair, fontSize = 20.sp
                    )
                }
                Spacer(Modifier.height(12.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        CoverFace(
                            book.title, null, book.coverUrl,
                            Modifier.width(118.dp).aspectRatio(0.68f), dark
                        )
                        Column(Modifier.weight(1f)) {
                            Text(book.title, color = ink, fontFamily = Playfair, fontSize = 20.sp)
                            Text(book.authorLine, color = mute, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                            if (book.publishedDate.isNotBlank()) {
                                Text(book.publishedDate, color = mute, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                            }
                            if (book.ratingsCount > 0) {
                                Text(
                                    "★ ${"%.1f".format(book.averageRating)}  ·  ${book.ratingsCount} ratings",
                                    color = ink, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                            if (book.publicDomain) {
                                Text("Public domain", color = Coral, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                    if (book.categories.isNotEmpty()) {
                        Text(
                            book.categories.joinToString("  ·  "),
                            color = mute, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp)
                        )
                    }
                    if (book.description.isNotBlank()) {
                        Text(
                            book.description,
                            color = ink, fontSize = 14.sp, lineHeight = 21.sp,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    } else {
                        Text("No synopsis available.", color = mute, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                    }
                    val source = when {
                        book.publicDomain -> "Free edition when available in the public-domain catalog."
                        book.infoLink != null || book.buyLink != null || book.previewLink != null ->
                            "Preview and purchase via Google Books. Lantern does not download this title."
                        else -> "Metadata from Google Books."
                    }
                    Text(source, color = mute, fontSize = 11.sp, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (saved) "Saved" else "Want to Read",
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                            .background(if (saved) Color(0x66FFFFFF) else Coral)
                            .clickable(onClick = onWant)
                            .padding(vertical = 12.dp),
                        color = if (saved) ink else Color.White,
                        fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center
                    )
                    Text(
                        if (book.publicDomain) "Read / Get Book" else "Get Book",
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                            .background(Color(0x33FFFFFF))
                            .clickable(onClick = onGet)
                            .padding(vertical = 12.dp),
                        color = ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
