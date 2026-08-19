package com.lantern.library.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.BookFormat
import com.lantern.library.data.BookIo
import com.lantern.library.data.Chapter
import com.lantern.library.data.LibraryBook
import com.lantern.library.data.Paginator
import com.lantern.library.data.ReaderPage
import com.lantern.library.data.ReaderTheme
import com.lantern.library.data.ReadingPrefs
import com.lantern.library.ui.components.readerFont
import com.lantern.library.ui.theme.Ink
import com.lantern.library.ui.theme.Lilac
import com.lantern.library.ui.theme.NightText
import com.lantern.library.ui.theme.Paper
import com.lantern.library.ui.theme.Periwinkle
import com.lantern.library.ui.theme.Playfair
import com.lantern.library.ui.theme.SlatePage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(book: LibraryBook, prefs: ReadingPrefs, onPrefs: (ReadingPrefs) -> Unit, onBack: () -> Unit, onProgress: (Int, Int) -> Unit, onBookmark: (Int) -> Unit) {
    val activity = LocalContext.current as Activity
    val family = readerFont(prefs.fontId)
    val pageColor = if (prefs.theme == ReaderTheme.DARK) SlatePage else Paper
    val ink = if (prefs.theme == ReaderTheme.DARK) NightText else Ink
    var chrome by remember { mutableStateOf(true) }
    var settings by remember { mutableStateOf(false) }
    var navBar by remember { mutableStateOf(false) }
    var chapters by remember { mutableStateOf(book.chapters) }
    var pdfCount by remember { mutableStateOf(book.pageCount.coerceAtLeast(1)) }
    var ready by remember { mutableStateOf(book.format == BookFormat.TEXT || book.chapters.isNotEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(prefs.landscape, prefs.brightness) {
        activity.requestedOrientation = if (prefs.landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val lp = activity.window.attributes; lp.screenBrightness = prefs.brightness.coerceIn(0.12f, 1f); activity.window.attributes = lp
        onDispose {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            val r = activity.window.attributes; r.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE; activity.window.attributes = r
        }
    }
    LaunchedEffect(book.id, book.filePath, book.format) {
        if (book.format == BookFormat.TEXT) { chapters = book.chapters; ready = true; return@LaunchedEffect }
        ready = false; error = null
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val file = book.filePath?.let { File(it) }
                if (file == null || !file.exists()) Triple(emptyList<Chapter>(), 1, "File is missing. Import it again.")
                else when (book.format) {
                    BookFormat.PDF -> Triple(emptyList(), BookIo.pdfPageCount(file).coerceAtLeast(1), null)
                    BookFormat.EPUB -> { val ch = BookIo.readEpubChapters(file); if (ch.isEmpty()) Triple(emptyList(), 1, "This file has no readable text.") else Triple(ch, ch.size, null) }
                    else -> Triple(book.chapters, 1, null)
                }
            }.getOrElse { Triple(emptyList(), 1, "Could not open this book.") }
        }
        chapters = loaded.first; pdfCount = loaded.second; error = loaded.third; ready = true
    }
    val pages = remember(chapters, prefs.fontSizeSp, book.id) {
        if (book.format == BookFormat.PDF) emptyList() else Paginator.pages(chapters, prefs.fontSizeSp, book.synopsis.ifBlank { "This book has no text yet." })
    }
    val pageCount = if (book.format == BookFormat.PDF) pdfCount else pages.size.coerceAtLeast(1)
    val pager = rememberPagerState(initialPage = book.currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)), pageCount = { pageCount })
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { onProgress(it, pageCount) } }
    fun toggle() { chrome = !chrome; if (!chrome) { settings = false; navBar = false } }
    Box(Modifier.fillMaxSize().background(pageColor)) {
        if (prefs.theme != ReaderTheme.DARK) Box(Modifier.fillMaxWidth().height(120.dp).background(Brush.verticalGradient(listOf(Lilac.copy(0.55f), Periwinkle.copy(0.28f), pageColor))))
        when {
            !ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Opening…", color = ink, fontFamily = family) }
            error != null && book.format != BookFormat.PDF -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Text(error ?: "", color = ink, textAlign = TextAlign.Center) }
            book.format == BookFormat.PDF -> HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
                var bmp by remember(book.filePath, i) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(book.filePath, i) { bmp = withContext(Dispatchers.IO) { book.filePath?.let { File(it) }?.takeIf { it.exists() }?.let { BookIo.renderPdfPage(it, i) } } }
                if (bmp != null) Image(bmp!!.asImageBitmap(), null, Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { toggle() } }, contentScale = ContentScale.Fit)
                else Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { toggle() } }, contentAlignment = Alignment.Center) { Text("Page ${i + 1}", color = ink) }
            }
            prefs.swipeMode -> HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
                PageLeaf(pages.getOrNull(i), family, prefs.fontSizeSp, ink, Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { toggle() } })
            }
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).pointerInput(Unit) { detectTapGestures { toggle() } }.padding(26.dp, 88.dp)) {
                Text(chapters.firstOrNull()?.title ?: book.title, color = ink, fontFamily = family, fontSize = (prefs.fontSizeSp + 10).sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Drop(chapters.joinToString("\n\n") { it.body }.ifBlank { book.synopsis }, family, prefs.fontSizeSp, ink)
            }
        }
        if (chrome) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.ArrowBack, null, tint = ink, modifier = Modifier.size(40.dp).clickable(onClick = onBack).padding(8.dp))
                Text("Book Shelf", Modifier.weight(1f), color = ink, fontFamily = Playfair, fontSize = 20.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.width(40.dp))
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp, top = 18.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                ColBtn(Icons.Filled.List, "Navigation Bar", ink) { navBar = !navBar; settings = false }
                ColBtn(Icons.Filled.Star, if (prefs.theme == ReaderTheme.DARK) "Dark Mode" else "Light Mode", ink) { onPrefs(prefs.copy(theme = if (prefs.theme == ReaderTheme.DARK) ReaderTheme.LIGHT else ReaderTheme.DARK)) }
                ColBtn(Icons.Filled.Settings, "Settings", ink) { settings = !settings; navBar = false }
                ColBtn(Icons.Filled.Favorite, "Bookmarks", ink) { onBookmark(pager.currentPage) }
            }
        }
        if (settings) Column(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(248.dp).statusBarsPadding().navigationBarsPadding().padding(top = 56.dp, bottom = 72.dp, end = 10.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xE62B2B2B)).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Text & Screen Settings", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Slider(prefs.brightness, { onPrefs(prefs.copy(brightness = it)) }, colors = SliderDefaults.colors(thumbColor = Color(0xFFE8D9A8), activeTrackColor = Color(0xFFE8D9A8)))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("A+" to (prefs.fontSizeSp + 2f).coerceAtMost(28f), "A-" to (prefs.fontSizeSp - 2f).coerceAtLeast(13f), "A" to 17f).forEach { (l, s) ->
                    Text(l, Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Color(0x33FFFFFF)).clickable { onPrefs(prefs.copy(fontSizeSp = s)) }.padding(10.dp), color = Color.White, textAlign = TextAlign.Center)
                }
            }
            listOf("times" to "Times New Roman", "garamond" to "Garamond", "arial" to "Arial").forEach { (id, label) ->
                Text(label, Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(if (prefs.fontId == id) Color(0x55FFFFFF) else Color(0x22FFFFFF)).clickable { onPrefs(prefs.copy(fontId = id)) }.padding(11.dp), color = Color.White, fontFamily = readerFont(id), textAlign = TextAlign.Center)
            }
            Text("Reading Mode", color = Color.White, modifier = Modifier.padding(top = 10.dp))
            Text("Swipe", color = Color.White, modifier = Modifier.clickable { onPrefs(prefs.copy(swipeMode = true)) }.padding(6.dp))
            Text("Scroll", color = Color.White, modifier = Modifier.clickable { onPrefs(prefs.copy(swipeMode = false)) }.padding(6.dp))
            Text("Portrait", color = Color.White, modifier = Modifier.clickable { onPrefs(prefs.copy(landscape = false)) }.padding(6.dp))
            Text("Landscape", color = Color.White, modifier = Modifier.clickable { onPrefs(prefs.copy(landscape = true)) }.padding(6.dp))
        }
        if (navBar) Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp, 0.dp, 20.dp, 78.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xDD2C2C2C)).padding(16.dp)) {
            Column {
                Text("Page ${pager.currentPage + 1} / $pageCount", color = Color.White, fontSize = 13.sp)
                Slider(pager.currentPage.toFloat(), { v -> scope.launch { pager.scrollToPage(v.toInt().coerceIn(0, pageCount - 1)) } }, valueRange = 0f..(pageCount - 1).coerceAtLeast(0).toFloat())
            }
        }
    }
}

@Composable
private fun ColBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp)); Text(label, color = tint.copy(0.85f), fontSize = 10.sp)
    }
}

@Composable
private fun PageLeaf(page: ReaderPage?, family: FontFamily, size: Float, ink: Color, modifier: Modifier) {
    Column(modifier.padding(26.dp, 88.dp, 26.dp, 80.dp)) {
        if (page == null) { Text("…", color = ink, fontFamily = family); return }
        if (page.showChapterTitle) { Text(page.chapterTitle, color = ink, fontFamily = family, fontSize = (size + 10).sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center); Spacer(Modifier.height(20.dp)) }
        page.paragraphs.forEachIndexed { i, para ->
            if (i == 0 && page.showChapterTitle) Drop(para, family, size, ink)
            else Text(para, color = ink, fontFamily = family, fontSize = size.sp, lineHeight = (size * 1.55f).sp, modifier = Modifier.padding(bottom = 12.dp))
        }
    }
}

@Composable
private fun Drop(text: String, family: FontFamily, size: Float, ink: Color) {
    val t = text.trim(); if (t.isEmpty()) return
    Text(buildAnnotatedString {
        withStyle(SpanStyle(fontFamily = family, fontSize = (size * 3.1f).sp, fontWeight = FontWeight.Medium, color = ink)) { append(t.first()) }
        withStyle(SpanStyle(fontFamily = family, fontSize = size.sp, color = ink)) { append(t.drop(1)) }
    }, lineHeight = (size * 1.55f).sp, modifier = Modifier.padding(bottom = 12.dp))
}
