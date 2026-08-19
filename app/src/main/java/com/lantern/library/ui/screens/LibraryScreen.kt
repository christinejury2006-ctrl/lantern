package com.lantern.library.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.LibraryBook
import com.lantern.library.data.ReaderTheme
import com.lantern.library.data.Shelf
import com.lantern.library.ui.components.AuroraBackdrop
import com.lantern.library.ui.components.BookCover
import com.lantern.library.ui.components.GlassCard
import com.lantern.library.ui.theme.Coral
import com.lantern.library.ui.theme.Ink
import com.lantern.library.ui.theme.InkSoft
import com.lantern.library.ui.theme.NightText
import com.lantern.library.ui.theme.Playfair

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(books: List<LibraryBook>, theme: ReaderTheme, onOpen: (LibraryBook) -> Unit, onRemove: (String) -> Unit, onImport: () -> Unit) {
    val dark = theme == ReaderTheme.DARK
    val ink = if (dark) NightText else Ink
    val mute = if (dark) Color(0xFFD0D6DE) else InkSoft
    var filter by remember { mutableStateOf(Shelf.ALL) }
    val shown = if (filter == Shelf.ALL) books else books.filter { it.shelf == filter }
    AuroraBackdrop(theme) {
        LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 18.dp, 18.dp, 96.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item(span = { GridItemSpan(3) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("My Library", color = ink, fontFamily = Playfair, fontSize = 30.sp)
                        Text("${books.size} books  ·  Reading ${books.count { it.shelf == Shelf.CURRENT }}  ·  To read ${books.count { it.shelf == Shelf.TO_READ }}", color = mute, fontSize = 13.sp)
                    }
                    Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0x99FFFFFF)).combinedClickable(onClick = onImport), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Add, "Import", tint = ink)
                    }
                }
            }
            if (books.isNotEmpty()) item(span = { GridItemSpan(3) }) {
                val pager = rememberPagerState(pageCount = { books.size.coerceAtMost(12) })
                GlassCard(Modifier.fillMaxWidth(), dark, 20) {
                    Column(Modifier.padding(vertical = 16.dp)) {
                        HorizontalPager(state = pager, contentPadding = PaddingValues(horizontal = 72.dp), pageSpacing = 18.dp, modifier = Modifier.fillMaxWidth().height(210.dp)) { page ->
                            BookCover(books[page], Modifier.width(138.dp).aspectRatio(0.68f).combinedClickable(onClick = { onOpen(books[page]) }), dark)
                        }
                        val current = books.getOrNull(pager.currentPage)
                        if (current != null) {
                            Text(current.title, Modifier.padding(top = 10.dp, start = 16.dp), color = ink, fontFamily = Playfair, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(current.author, Modifier.padding(start = 16.dp, top = 2.dp), color = mute, fontSize = 12.sp)
                        }
                    }
                }
            }
            item(span = { GridItemSpan(3) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Shelf.ALL to "All", Shelf.CURRENT to "Currently Reading", Shelf.TO_READ to "To Read", Shelf.FINISHED to "Finished").forEach { (s, label) ->
                        Text(label, Modifier.clip(RoundedCornerShape(20.dp)).background(if (filter == s) Coral else Color(0x66FFFFFF)).combinedClickable(onClick = { filter = s }).padding(horizontal = 12.dp, vertical = 7.dp), color = if (filter == s) Color.White else ink, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
            items(shown, key = { it.id }) { book ->
                Column {
                    BookCover(book, Modifier.fillMaxWidth().aspectRatio(0.68f).combinedClickable(onClick = { onOpen(book) }, onLongClick = { onRemove(book.id) }), dark)
                    Spacer(Modifier.height(6.dp))
                    Text(book.title, color = ink, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(book.author, color = mute, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}
