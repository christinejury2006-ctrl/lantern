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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.CatalogBook
import com.lantern.library.data.Gutendex
import com.lantern.library.data.GoogleBooks
import com.lantern.library.data.DiscoveryBook
import com.lantern.library.data.ReaderTheme
import com.lantern.library.ui.components.RoomBackdrop
import com.lantern.library.ui.components.CoverFace
import com.lantern.library.ui.components.GlassCard
import com.lantern.library.ui.theme.Coral
import com.lantern.library.ui.theme.Ink
import com.lantern.library.ui.theme.InkSoft
import com.lantern.library.ui.theme.NightText
import com.lantern.library.ui.theme.Parchment
import com.lantern.library.ui.theme.Playfair
import org.json.JSONArray
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ExploreScreen(theme: ReaderTheme, onOpen: (CatalogBook) -> Unit) {
    CatalogBrowser("Explore", "Public-domain books you can download", theme, "Fantasy", onOpen)
}

@Composable
fun SearchScreen(theme: ReaderTheme, onOpen: (DiscoveryBook) -> Unit) {
    DiscoveryBrowser(theme, onOpen)
}

@Composable
private fun DiscoveryBrowser(theme: ReaderTheme, onOpen: (DiscoveryBook) -> Unit) {
    val dark = theme == ReaderTheme.DARK
    val ink = if (dark) NightText else Ink
    val mute = if (dark) Color(0xFFD0D6DE) else InkSoft
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<DiscoveryBook>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("lore_search", android.content.Context.MODE_PRIVATE) }
    var history by remember { mutableStateOf(prefs.getString("history", "")!!.split("\n").filter { it.isNotBlank() }.take(8)) }
    val trending = remember { listOf("Married to the Devil's Son", "billionaire", "werewolf", "Rich Dad Poor Dad", "romance", "mafia romance", "dark romance", "fantasy", "romantasy", "CEO romance") }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isBlank()) { results = emptyList(); loading = false; error = false; return@LaunchedEffect }
        kotlinx.coroutines.delay(350)
        loading = true
        error = false
        val google = runCatching { GoogleBooks.volumes(q, "relevance", 24) }.getOrDefault(emptyList())
        results = if (google.isNotEmpty()) google else runCatching {
            Gutendex.search(q, null).map { b ->
                DiscoveryBook(volumeId = "gutendex:${b.remoteId}", title = b.title,
                    authors = listOf(b.author), categories = listOf(b.subjects),
                    coverUrl = b.cover, coverUrls = listOfNotNull(b.cover),
                    infoLink = "https://www.gutenberg.org/ebooks/${b.remoteId}",
                    publicDomain = true)
            }
        }.getOrDefault(emptyList())
        error = results.isEmpty()
        loading = false
        if (results.isNotEmpty()) {
            history = (listOf(q) + history.filterNot { it.equals(q, true) }).take(8)
            prefs.edit().putString("history", history.joinToString("\n")).apply()
        }
    }
    AuroraBackdrop(theme) {
        LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(18.dp, 18.dp, 18.dp, 96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = { GridItemSpan(2) }) {
                Column {
                    Text("Explore", color = ink, fontFamily = Playfair, fontSize = 30.sp)
                    Text("Search books across genres, authors and trending stories", color = mute, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    GlassCard(Modifier.fillMaxWidth(), dark, 18) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Search, null, tint = mute, modifier = Modifier.size(20.dp))
                            BasicTextField(value = query, onValueChange = { query = it }, singleLine = true,
                                textStyle = TextStyle(color = ink, fontSize = 15.sp), cursorBrush = SolidColor(ink),
                                modifier = Modifier.weight(1f).padding(start = 8.dp), decorationBox = { inner ->
                                    if (query.isEmpty()) Text("Search books, authors, tropes…", color = mute, fontSize = 15.sp)
                                    inner()
                                })
                        }
                    }
                    if (query.isBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Text("TRENDING SEARCHES", color = mute, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            trending.forEach { term ->
                                Box(Modifier.clip(RoundedCornerShape(18.dp)).background(if (dark) Color(0xFF35404F) else Color(0xCCFFFFFF))
                                    .clickable { query = term }.padding(horizontal = 13.dp, vertical = 10.dp)) {
                                    Text(term, color = ink, fontSize = 12.sp, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                        if (history.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            Text("RECENT SEARCHES", color = mute, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                history.forEach { term ->
                                    Box(Modifier.clip(RoundedCornerShape(18.dp)).background(if (dark) Color(0xFF35404F) else Color(0xCCFFFFFF))
                                        .clickable { query = term }.padding(horizontal = 13.dp, vertical = 9.dp)) {
                                        Text(term, color = ink, fontSize = 12.sp, maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        }
                    }
                    if (loading) Text("Searching books…", color = mute, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                    if (error) Text("No results found. Try another title, author, or genre.", color = mute, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                    if (query.isNotBlank() && !GoogleBooks.isConfigured()) Text("Google Books discovery needs an API key in the build configuration. Public-domain matches are still searched.", color = mute, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            items(results, key = { it.volumeId }) { book ->
                GlassCard(Modifier.fillMaxWidth(), dark, 16) {
                    Column(Modifier.clickable { onOpen(book) }.padding(10.dp)) {
                        CoverFace(book.title, null, book.coverUrl, Modifier.fillMaxWidth().aspectRatio(0.68f), dark)
                        Spacer(Modifier.height(8.dp))
                        Text(book.title, color = ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(book.authorLine, color = mute, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (book.publicDomain) "Public-domain edition" else "Preview / book details",
                            color = if (book.publicDomain) Coral else mute, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogBrowser(title: String, subtitle: String, theme: ReaderTheme, initialTopic: String?, onOpen: (CatalogBook) -> Unit) {
    val dark = theme == ReaderTheme.DARK
    val ink = if (dark) NightText else Ink
    val mute = if (dark) Color(0xFFD0D6DE) else InkSoft
    val context = LocalContext.current
    val searchPrefs = remember { context.getSharedPreferences("lore_search", android.content.Context.MODE_PRIVATE) }
    val trending = remember {
        runCatching {
            context.assets.open("hot_search.json").bufferedReader().use { reader ->
                val arr = JSONArray(reader.readText())
                (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotBlank() }
            }
        }.getOrDefault(listOf("billionaire", "werewolf", "romance", "fantasy", "Rich Dad Poor Dad", "Married to the Devil's Son"))
    }
    var history by remember {
        mutableStateOf(searchPrefs.getString("history", "").orEmpty().split("\n").filter { it.isNotBlank() }.take(8))
    }
    var query by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf(initialTopic) }
    var results by remember { mutableStateOf<List<CatalogBook>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(query, topic) {
        delay(300)
        loading = true
        results = withContext(Dispatchers.IO) { runCatching { Gutendex.search(query, topic) }.getOrDefault(emptyList()) }
        loading = false
        val clean = query.trim()
        if (clean.length >= 2 && results.isNotEmpty()) {
            history = (listOf(clean) + history.filterNot { it.equals(clean, ignoreCase = true) }).take(8)
            searchPrefs.edit().putString("history", history.joinToString("\n")).apply()
        }
    }
    RoomBackdrop(dark) {
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
                    Text("TRENDING SEARCHES", color = mute, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(7.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        trending.forEach { term ->
                            Box(Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xCCFFFFFF)).clickable {
                                query = term
                                topic = null
                            }.padding(horizontal = 13.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
                                Text(term, color = ink, fontSize = 12.sp, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                    if (history.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("HISTORY", color = mute, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(7.dp))
                        history.forEach { term ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                                    query = term
                                    topic = null
                                }.padding(vertical = 9.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Search, null, tint = mute, modifier = Modifier.size(16.dp))
                                Text(term, color = ink, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
                            }
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
