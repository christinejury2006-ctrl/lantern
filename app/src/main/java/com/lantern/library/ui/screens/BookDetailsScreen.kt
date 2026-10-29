package com.lantern.library.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.lantern.library.ui.components.GlassSurface
import com.lantern.library.ui.theme.Playfair
import com.lantern.library.ui.theme.nookPalette

@Composable
fun BookDetailsOverlay(
    book: DiscoveryBook,
    theme: ReaderTheme,
    saved: Boolean,
    onDismiss: () -> Unit,
    onWant: () -> Unit,
    onNotInterested: () -> Unit
) {
    val palette = nookPalette(theme)
    BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (palette.dark) 0.66f else 0.48f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp)
                .clip(RoundedCornerShape(28.dp)).background(palette.glass)
                .clickable(enabled = false) {}.padding(20.dp)
                .heightIn(max = 690.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.width(174.dp).height(240.dp), contentAlignment = Alignment.Center) {
                    CoverFace(
                        book.title, null, book.coverUrl,
                        Modifier.width(150.dp).aspectRatio(0.68f).clip(RoundedCornerShape(10.dp)),
                        palette.dark, book.coverUrls
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(book.title, color = palette.ink, fontFamily = Playfair, fontSize = 23.sp, textAlign = TextAlign.Center)
                Text(book.authorLine, color = palette.mute, fontSize = 14.sp, modifier = Modifier.padding(top = 5.dp), textAlign = TextAlign.Center)
                val meta = buildList {
                    if (book.publishedDate.isNotBlank()) add(book.publishedDate)
                    if (book.ratingsCount > 0) add("★ ${"%.1f".format(book.averageRating)} · ${book.ratingsCount} ratings")
                }
                if (meta.isNotEmpty()) Text(meta.joinToString("  •  "), color = palette.mute, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                if (book.categories.isNotEmpty()) {
                    Text(book.categories.take(5).joinToString("  ·  "), color = palette.accent, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
                }
                Text("SYNOPSIS", color = palette.mute, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp, modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp))
                Text(
                    book.description.takeIf { it.isNotBlank() } ?: "A verified synopsis is not available for this title yet.",
                    color = palette.ink, fontSize = 14.sp, lineHeight = 21.sp,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("EXCERPT", color = palette.mute, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp, modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp))
                Text(
                    book.excerpt.takeIf { it.isNotBlank() }
                        ?: "No provider-supplied text sample is available for this title yet. Lore won’t invent an excerpt or present generated text as the author’s writing.",
                    color = if (book.excerpt.isNotBlank()) palette.ink else palette.mute,
                    fontSize = 13.sp, lineHeight = 19.sp,
                    modifier = Modifier.fillMaxWidth()
                )
                if (book.excerpt.isNotBlank()) {
                    Text(
                        "Text snippet supplied by the book metadata provider",
                        color = palette.mute, fontSize = 10.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            GlassSurface(palette, Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), fill = palette.accent, onClick = onWant) {
                Text(if (saved) "Saved to Want to Read" else "Want to Read", color = palette.accentInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp))
            }
            Spacer(Modifier.height(8.dp))
            GlassSurface(palette, Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), onClick = onNotInterested) {
                Text("Not Interested", color = palette.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp))
            }
        }
    }
}
