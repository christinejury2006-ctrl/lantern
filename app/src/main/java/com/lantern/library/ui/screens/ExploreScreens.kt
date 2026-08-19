package com.lantern.library.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.CatalogBook
import com.lantern.library.data.Gutendex
import com.lantern.library.data.ReaderTheme
import com.lantern.library.ui.components.AuroraBackdrop
import com.lantern.library.ui.components.CoverFace
import com.lantern.library.ui.components.GlassCard
import com.lantern.library.ui.theme.Coral
import com.lantern.library.ui.theme.Ink
import com.lantern.library.ui.theme.InkSoft
import com.lantern.library.ui.theme.NightText
import com.lantern.library.ui.theme.Playfair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ExploreScreen(theme: ReaderTheme, onOpen: (CatalogBook) -> Unit) {
    CatalogBrowser("Explore", "Public-domain books you can download", theme, "Fantasy", onOpen)
}

@Composable
fun SearchScreen(theme: ReaderTheme, onOpen: (CatalogBook) -> Unit) {
    CatalogBrowser("Search", "Find a free EPUB by title or author", theme, null, onOpen)
}

@Composable
private fun CatalogBrowser(title: String, subtitle: String, theme: ReaderTheme, initialTopic: String?, onOpen: (CatalogBook) -> Unit) {
    val dark = theme == ReaderTheme.DARK
    val ink = if (dark) NightText else Ink
    val mute = if (dark) Color(0xFFD0D6DE) else InkSoft
    var query by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf(initialTopic) }
    var results by remember { mutableStateOf<List<CatalogBook>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(query, topic) {
        loading = true
        results = withContext(Dispatchers.IO) { runCatching { Gutendex.search(query, topic) }.getOrDefault(emptyList()) }
        loading = false
    }
    AuroraBackdrop(theme) {
        LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 18.dp, 18.dp, 96.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = { GridItemSpan(2) }) {
                Column {
                    Text(title, color = ink, fontFamily = Playfair, fontSize = 30.sp)
                    Text(subtitle, color = mute, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    GlassCard(Modifier.fillMaxWidth(), dark, 18) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Search, null, tint = mute, modifier = Modifier.size(20.dp))
                            BasicTextField(value = query, onValueChange = { query = it }, singleLine = true, textStyle = TextStyle(color = ink, fontSize = 15.sp), cursorBrush = SolidColor(ink), modifier = Modifier.weight(1f).padding(start = 8.dp), decorationBox = { inner ->
                                if (query.isEmpty()) Text("Search titles…", color = mute, fontSize = 15.sp)
                                inner()
                            })
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Gutendex.topics.forEach { t ->
                            val on = topic == t && query.isEmpty()
                            Box(Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(if (on) Coral else Color(0xCCFFFFFF)).clickable { topic = t; query = "" }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                                Text(t, color = if (on) Color.White else ink, fontSize = 13.sp, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                    if (loading) Text("Looking up free books…", color = mute, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                }
            }
            items(results, key = { it.remoteId }) { book ->
                GlassCard(Modifier.fillMaxWidth(), dark, 16) {
                    Column(Modifier.clickable { onOpen(book) }.padding(10.dp)) {
                        CoverFace(book.title, null, book.cover, Modifier.fillMaxWidth().aspectRatio(0.68f), dark)
                        Spacer(Modifier.height(8.dp))
                        Text(book.title, color = ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(book.author, color = mute, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}
