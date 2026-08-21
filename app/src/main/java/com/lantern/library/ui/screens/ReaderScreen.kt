package com.lantern.library.ui.screens

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.BatteryManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.lantern.library.data.BookFormat
import com.lantern.library.data.BookIo
import com.lantern.library.data.Chapter
import com.lantern.library.data.LanternFonts
import com.lantern.library.data.LibraryBook
import com.lantern.library.data.Paginator
import com.lantern.library.data.ReaderPage
import com.lantern.library.data.TocEntry
import com.lantern.library.data.ReaderTheme
import com.lantern.library.data.ReadingPrefs
import com.lantern.library.ui.theme.Aqua
import com.lantern.library.ui.theme.Ink
import com.lantern.library.ui.theme.Lilac
import com.lantern.library.ui.theme.NightText
import com.lantern.library.ui.theme.Periwinkle
import com.lantern.library.ui.theme.Playfair
import com.lantern.library.ui.theme.Sky
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ReaderMenu { None, Settings, Nav, Chapters, Marks }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(
    book: LibraryBook,
    prefs: ReadingPrefs,
    onPrefs: (ReadingPrefs) -> Unit,
    onBack: () -> Unit,
    onProgress: (Int, Int) -> Unit,
    onBookmark: (Int) -> Unit
) {
    val activity = LocalContext.current as Activity
    val family = LanternFonts.family(prefs.fontId)
    val dark = prefs.readerTheme == ReaderTheme.DARK
    val ink = if (dark) NightText else Ink
    var chrome by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(ReaderMenu.None) }
    val bookmarks = remember { mutableStateListOf<Int>() }
    var chapters by remember { mutableStateOf(book.chapters) }
    var toc by remember { mutableStateOf<List<TocEntry>>(emptyList()) }
    var pdfCount by remember { mutableStateOf(book.pageCount.coerceAtLeast(1)) }
    var ready by remember { mutableStateOf(book.format == BookFormat.TEXT || book.chapters.isNotEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var clock by remember { mutableStateOf(nowTime()) }
    var batteryPct by remember { mutableStateOf(100) }
    var batteryCharging by remember { mutableStateOf(false) }

    DisposableEffect(prefs.landscape, prefs.brightness) {
        activity.requestedOrientation = if (prefs.landscape)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val lp = activity.window.attributes
        lp.screenBrightness = prefs.brightness.coerceIn(0.12f, 1f)
        activity.window.attributes = lp
        onDispose {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            val reset = activity.window.attributes
            reset.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            activity.window.attributes = reset
        }
    }

    DisposableEffect(activity) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }
    SideEffect {
        val window = activity.window
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (chrome) controller.show(WindowInsetsCompat.Type.statusBars())
        else controller.hide(WindowInsetsCompat.Type.statusBars())
    }

    DisposableEffect(activity) {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        fun apply(intent: Intent?) {
            if (intent == null) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            batteryPct = ((level * 100f) / scale).toInt().coerceIn(0, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = apply(intent)
        }
        apply(registerBatteryReceiver(activity, null, filter))
        registerBatteryReceiver(activity, receiver, filter)
        onDispose { runCatching { activity.unregisterReceiver(receiver) } }
    }

    LaunchedEffect(Unit) {
        while (true) {
            clock = nowTime()
            val intoMinute = System.currentTimeMillis() % 60_000L
            delay(60_000L - intoMinute + 40L)
        }
    }

    LaunchedEffect(book.id, book.filePath, book.format) {
        if (book.format == BookFormat.TEXT) {
            chapters = book.chapters
            toc = book.chapters.mapIndexed { i, ch ->
                TocEntry(ch.title.ifBlank { "Chapter ${i + 1}" }, ch.href, 0, i)
            }
            ready = true
            return@LaunchedEffect
        }
        ready = false
        error = null
        toc = emptyList()
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val file = book.filePath?.let { File(it) }
                if (file == null || !file.exists()) {
                    Triple(emptyList<Chapter>(), emptyList<TocEntry>(), "File is missing. Import it again." to 1)
                } else when (book.format) {
                    BookFormat.PDF -> Triple(
                        emptyList<Chapter>(),
                        emptyList<TocEntry>(),
                        null to BookIo.pdfPageCount(file).coerceAtLeast(1)
                    )
                    BookFormat.EPUB -> {
                        val doc = BookIo.readEpubDocument(file)
                        if (doc.chapters.isEmpty()) Triple(emptyList(), emptyList(), "This file has no readable text." to 1)
                        else Triple(doc.chapters, doc.toc, null to doc.chapters.size)
                    }
                    else -> Triple(book.chapters, emptyList(), null to 1)
                }
            }.getOrElse { Triple(emptyList(), emptyList(), "Could not open this book." to 1) }
        }
        chapters = loaded.first
        toc = loaded.second
        pdfCount = loaded.third.second
        error = loaded.third.first
        ready = true
    }

    val pages = remember(chapters, prefs.fontSizeSp, book.id) {
        if (book.format == BookFormat.PDF) emptyList()
        else Paginator.pages(chapters, prefs.fontSizeSp, book.synopsis.ifBlank { "This book has no text yet." })
    }
    val pageCount = if (book.format == BookFormat.PDF) pdfCount else pages.size.coerceAtLeast(1)
    val pager = rememberPagerState(
        initialPage = book.currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
        pageCount = { pageCount }
    )
    val scrollState = rememberScrollState()
    val chapterY = remember(book.id) { mutableStateMapOf<Int, Int>() }
    val scope = rememberCoroutineScope()
    val epubUsesPager = prefs.swipeMode && book.format != BookFormat.PDF
    val pdfUsesPager = book.format == BookFormat.PDF && prefs.swipeMode
    LaunchedEffect(pager, pageCount, prefs.swipeMode, book.format) {
        if (!prefs.swipeMode) return@LaunchedEffect
        snapshotFlow { pager.currentPage }.collect { onProgress(it, pageCount) }
    }
    LaunchedEffect(scrollState, pageCount, prefs.swipeMode, book.format, ready) {
        if (prefs.swipeMode || !ready) return@LaunchedEffect
        snapshotFlow { scrollState.value to scrollState.maxValue }.collect { (value, max) ->
            if (max <= 0 || pageCount <= 0) return@collect
            val page = ((value.toFloat() / max.toFloat()) * (pageCount - 1).coerceAtLeast(0)).toInt()
            onProgress(page, pageCount)
        }
    }
    LaunchedEffect(book.id, ready, pageCount, prefs.swipeMode) {
        if (!ready || pageCount <= 0) return@LaunchedEffect
        val max = pageCount - 1
        val target = book.currentPage.coerceIn(0, max)
        if (prefs.swipeMode) {
            if (pager.currentPage != target) pager.scrollToPage(target)
        }
    }
    LaunchedEffect(book.id, ready, prefs.swipeMode, book.format, scrollState.maxValue, pageCount) {
        if (!ready || prefs.swipeMode) return@LaunchedEffect
        if (scrollState.maxValue <= 0 || pageCount <= 0) return@LaunchedEffect
        val frac = if (pageCount <= 1) 0f else book.currentPage.toFloat() / (pageCount - 1).coerceAtLeast(1)
        val target = (frac * scrollState.maxValue).toInt().coerceIn(0, scrollState.maxValue)
        if (kotlin.math.abs(scrollState.value - target) > 8) scrollState.scrollTo(target)
    }

    val menuOpen = menu != ReaderMenu.None
    fun closeMenus() { menu = ReaderMenu.None }
    fun toggleMenu(target: ReaderMenu) {
        menu = if (menu == target) ReaderMenu.None else target
        if (menu != ReaderMenu.None) chrome = true
    }
    fun toggleChrome() {
        if (menuOpen) {
            closeMenus()
            return
        }
        chrome = !chrome
    }
    fun go(p: Int) {
        val target = p.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        scope.launch {
            if (prefs.swipeMode) {
                pager.scrollToPage(target)
            } else if (scrollState.maxValue > 0 && pageCount > 1) {
                val dest = ((target.toFloat() / (pageCount - 1)) * scrollState.maxValue).toInt()
                scrollState.scrollTo(dest.coerceIn(0, scrollState.maxValue))
            }
            onProgress(target, pageCount)
        }
    }
    fun goToChapter(index: Int) {
        if (index < 0) return
        val page = pages.indexOfFirst { it.chapterIndex == index }
        if (book.format == BookFormat.PDF || prefs.swipeMode) {
            if (page < 0) return
            go(page)
            closeMenus()
            return
        }
        val y = chapterY[index] ?: return
        if (y > 0 && scrollState.maxValue <= 0) return
        scope.launch {
            scrollState.scrollTo(y.coerceIn(0, scrollState.maxValue))
            if (page >= 0) onProgress(page, pageCount)
        }
        closeMenus()
    }

    BackHandler {
        if (menuOpen) closeMenus() else onBack()
    }

    val aurora = if (dark) Brush.verticalGradient(
        listOf(Color(0xFF3E4A5C), Color(0xFF4A5668), Color(0xFF3A4454))
    ) else Brush.verticalGradient(listOf(Lilac, Periwinkle, Sky, Aqua))
    val glassFilm = if (dark) Color(0x99404A5C) else Color(0x99EDE4F6)
    val gold = Color(0xFFE8D9A8)
    val pageTap = Modifier.chromeTap(enabled = !menuOpen, onTap = { toggleChrome() })

    Box(Modifier.fillMaxSize().background(aurora)) {
        Box(Modifier.fillMaxSize().background(glassFilm))

        when {
            !ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Opening…", color = ink, fontFamily = family)
            }
            error != null && book.format != BookFormat.PDF -> Box(
                Modifier.fillMaxSize().padding(32.dp).then(pageTap),
                contentAlignment = Alignment.Center
            ) { Text(error ?: "", color = ink, textAlign = TextAlign.Center) }
            book.format == BookFormat.PDF -> key(book.id, pdfUsesPager) {
                if (pdfUsesPager) {
                    HorizontalPager(
                        state = pager,
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = !menuOpen
                    ) { i ->
                        Box(Modifier.fillMaxSize().then(pageTap), contentAlignment = Alignment.Center) {
                            PdfPageImage(
                                path = book.filePath,
                                index = i,
                                ink = ink,
                                modifier = Modifier.fillMaxSize(),
                                fillViewport = true
                            )
                        }
                    }
                } else {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState, enabled = !menuOpen)
                            .padding(horizontal = 8.dp, vertical = 0.dp)
                            .padding(top = 88.dp, bottom = 96.dp)
                    ) {
                        repeat(pdfCount) { i ->
                            PdfPageImage(
                                path = book.filePath,
                                index = i,
                                ink = ink,
                                modifier = Modifier.fillMaxWidth().then(pageTap),
                                fillViewport = false
                            )
                            if (i < pdfCount - 1) Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
            else -> key(book.id, epubUsesPager) {
                if (epubUsesPager) {
                    HorizontalPager(
                        state = pager,
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = !menuOpen
                    ) { i ->
                        key(prefs.fontSizeSp, i) {
                            PageLeaf(
                                pages.getOrNull(i), family, prefs.fontSizeSp, ink,
                                Modifier.fillMaxSize().then(pageTap)
                            )
                        }
                    }
                } else {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState, enabled = !menuOpen)
                            .padding(26.dp, 88.dp, 26.dp, 96.dp)
                    ) {
                        val body = chapters.ifEmpty { listOf(Chapter(book.title, book.synopsis.ifBlank { "This book has no text yet." })) }
                        body.forEachIndexed { index, ch ->
                            Text(
                                ch.title.ifBlank { "Chapter ${index + 1}" },
                                color = ink, fontFamily = family, fontSize = (prefs.fontSizeSp + 10).sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onGloballyPositioned { coords ->
                                        chapterY[index] = coords.positionInParent().y.toInt()
                                    }
                                    .padding(top = if (index == 0) 0.dp else 28.dp),
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(16.dp))
                            val paras = ch.body.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
                            paras.forEachIndexed { pi, para ->
                                if (pi == 0) Drop(para, family, prefs.fontSizeSp, ink)
                                else Text(
                                    para, color = ink, fontFamily = family, fontSize = prefs.fontSizeSp.sp,
                                    lineHeight = (prefs.fontSizeSp * 1.55f).sp, modifier = Modifier.padding(bottom = 12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                        .union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
                        .union(if (chrome) WindowInsets.statusBars else WindowInsets(0, 0, 0, 0))
                )
                .padding(top = if (chrome) 4.dp else 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GlassStatus(
                time = clock,
                percent = batteryPct,
                charging = batteryCharging,
                ink = ink,
                modifier = Modifier.clickable { toggleChrome() }
            )
            if (chrome) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.ArrowBack, "Back", tint = ink,
                        modifier = Modifier.size(40.dp).clickable {
                            if (menuOpen) closeMenus() else onBack()
                        }.padding(8.dp)
                    )
                    Text(
                        "Book Shelf", Modifier.weight(1f), color = ink,
                        fontFamily = Playfair, fontSize = 20.sp, textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.width(40.dp))
                }
            }
        }

        if (chrome) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                    .padding(bottom = 8.dp, top = 18.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ColBtn(Icons.Filled.List, "Chapters", ink, selected = menu == ReaderMenu.Chapters) {
                    toggleMenu(ReaderMenu.Chapters)
                }
                ColBtn(Icons.Filled.Search, "Navigate", ink, selected = menu == ReaderMenu.Nav) {
                    toggleMenu(ReaderMenu.Nav)
                }
                Column(
                    Modifier.clickable {
                        onPrefs(prefs.copy(readerTheme = if (dark) ReaderTheme.LIGHT else ReaderTheme.DARK))
                    }.padding(8.dp, 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    SunMoonIcon(dark, ink, Modifier.size(22.dp))
                    Text(if (dark) "Dark" else "Light", color = ink.copy(0.85f), fontSize = 10.sp)
                }
                ColBtn(Icons.Filled.Settings, "Settings", ink, selected = menu == ReaderMenu.Settings) {
                    toggleMenu(ReaderMenu.Settings)
                }
                ColBtn(Icons.Filled.Favorite, "Bookmarks", ink, selected = menu == ReaderMenu.Marks) {
                    val page = when {
                        prefs.swipeMode -> pager.currentPage
                        scrollState.maxValue <= 0 -> 0
                        else -> ((scrollState.value.toFloat() / scrollState.maxValue) * (pageCount - 1).coerceAtLeast(0)).toInt()
                    }
                    if (bookmarks.none { it == page }) {
                        bookmarks.add(page)
                        onBookmark(page)
                    }
                    toggleMenu(ReaderMenu.Marks)
                }
            }
        }

        if (menuOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(menu) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                                if (event.changes.none { it.pressed }) break
                            }
                            closeMenus()
                        }
                    }
            )
        }

        if (menu == ReaderMenu.Settings) {
            Box(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(248.dp)
                    .statusBarsPadding().navigationBarsPadding()
                    .padding(top = 56.dp, bottom = 72.dp, end = 10.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xE62B2B2B))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {}
                    )
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    Text("Text & Screen Settings", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(14.dp))
                    Text("Brightness", color = Color.White.copy(0.85f), fontSize = 13.sp)
                    Slider(
                        prefs.brightness,
                        { onPrefs(prefs.copy(brightness = it)) },
                        colors = SliderDefaults.colors(thumbColor = gold, activeTrackColor = gold)
                    )
                    if (book.format == BookFormat.PDF) {
                        Text(
                            "Font size is for EPUB text. PDFs stay as printed.",
                            color = Color.White.copy(0.7f), fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                "A+" to (prefs.fontSizeSp + 2f).coerceAtMost(28f),
                                "A-" to (prefs.fontSizeSp - 2f).coerceAtLeast(13f),
                                "A" to 17f,
                                "a" to 15f
                            ).forEach { (l, s) ->
                                Text(
                                    l,
                                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Color(0x33FFFFFF))
                                        .clickable { onPrefs(prefs.copy(fontSizeSp = s)) }.padding(10.dp),
                                    color = Color.White, textAlign = TextAlign.Center
                                )
                            }
                        }
                        Text("Font", color = Color.White, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                        LanternFonts.options.forEach { opt ->
                            val on = prefs.fontId == opt.id || (prefs.fontId == "times" && opt.id == "lora")
                            Text(
                                opt.label,
                                Modifier.fillMaxWidth().padding(bottom = 4.dp).clip(RoundedCornerShape(10.dp))
                                    .background(if (on) Color(0xFFE8D9A8) else Color(0x22FFFFFF))
                                    .clickable { onPrefs(prefs.copy(fontId = opt.id)) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                color = if (on) Color(0xFF2B2430) else Color.White,
                                fontSize = 13.sp
                            )
                        }
                    }
                    Text("Reading Mode", color = Color.White, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    ModeSwitch("Swipe", "Scroll", prefs.swipeMode) { onPrefs(prefs.copy(swipeMode = it)) }
                    Text("Page Orientation", color = Color.White, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    ModeSwitch("Portrait", "Landscape", !prefs.landscape) { onPrefs(prefs.copy(landscape = !it)) }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        if (menu == ReaderMenu.Nav) {
            Box(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                    .padding(16.dp, 0.dp, 16.dp, 78.dp)
                    .clip(RoundedCornerShape(18.dp)).background(Color(0xE62B2B2B))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {}
                    )
            ) {
                Column(Modifier.padding(16.dp)) {
                    val at = when {
                        prefs.swipeMode -> pager.currentPage
                        scrollState.maxValue <= 0 -> 0
                        else -> ((scrollState.value.toFloat() / scrollState.maxValue) * (pageCount - 1).coerceAtLeast(0)).toInt()
                    }
                    Text("Navigate", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "Page ${at + 1} of $pageCount",
                        color = Color.White.copy(0.8f), fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )
                    Slider(
                        at.toFloat(),
                        { go(it.toInt()) },
                        valueRange = 0f..(pageCount - 1).coerceAtLeast(0).toFloat(),
                        colors = SliderDefaults.colors(thumbColor = gold, activeTrackColor = gold)
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("First", color = Color.White, modifier = Modifier.clickable { go(0) }.padding(8.dp))
                        Text("Previous", color = Color.White, modifier = Modifier.clickable { go(at - 1) }.padding(8.dp))
                        Text("Next", color = Color.White, modifier = Modifier.clickable { go(at + 1) }.padding(8.dp))
                        Text("Last", color = Color.White, modifier = Modifier.clickable { go(pageCount - 1) }.padding(8.dp))
                    }
                }
            }
        }

        if (menu == ReaderMenu.Chapters) {
            Column(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                    .padding(16.dp, 0.dp, 16.dp, 78.dp)
                    .clip(RoundedCornerShape(18.dp)).background(Color(0xE62B2B2B))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {}
                    )
                    .fillMaxWidth()
                    .height(280.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text("Chapters", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                val list = toc.filter { it.chapterIndex >= 0 }
                if (list.isEmpty()) {
                    Text(
                        "No table of contents in this book.",
                        color = Color.White.copy(0.75f), fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else {
                    list.forEach { entry ->
                        Text(
                            entry.title,
                            color = Color.White,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { goToChapter(entry.chapterIndex) }
                                .padding(start = (entry.level * 16).dp, top = 10.dp, bottom = 10.dp)
                        )
                    }
                }
            }
        }

        if (menu == ReaderMenu.Marks) {
            Column(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                    .padding(16.dp, 0.dp, 16.dp, 78.dp)
                    .clip(RoundedCornerShape(18.dp)).background(Color(0xE62B2B2B))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {}
                    )
                    .padding(16.dp)
            ) {
                Text("Bookmarks", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (bookmarks.isEmpty()) {
                    Text(
                        "None yet. Open Bookmarks to save this page.",
                        color = Color.White.copy(0.75f), fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else {
                    bookmarks.distinct().sorted().forEach { p ->
                        Text(
                            "Page ${p + 1}",
                            color = Color.White,
                            modifier = Modifier.fillMaxWidth().clickable {
                                go(p)
                                closeMenus()
                            }.padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Modifier.chromeTap(enabled: Boolean, onTap: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return this.clickable(
        enabled = enabled,
        indication = null,
        interactionSource = source,
        onClick = onTap
    )
}

@Composable
private fun GlassStatus(time: String, percent: Int, charging: Boolean, ink: Color, modifier: Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0x55FFFFFF))
            .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(time, color = ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        BatteryGlyph(percent, charging, ink, Modifier.size(18.dp, 10.dp))
        Text("$percent%", color = ink.copy(0.9f), fontSize = 11.sp)
    }
}

@Composable
private fun BatteryGlyph(percent: Int, charging: Boolean, tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val body = Size(w * 0.82f, h)
        drawRoundRect(color = tint, size = body, cornerRadius = CornerRadius(2.dp.toPx()), style = Stroke(1.2.dp.toPx()))
        drawRect(color = tint, topLeft = Offset(body.width, h * 0.28f), size = Size(w * 0.14f, h * 0.44f))
        val fill = ((body.width - 3.dp.toPx()) * (percent / 100f)).coerceAtLeast(0f)
        val fillColor = if (charging) Color(0xFF8FDFB0) else tint
        if (fill > 0f) {
            drawRoundRect(
                color = fillColor,
                topLeft = Offset(1.6.dp.toPx(), 1.6.dp.toPx()),
                size = Size(fill, h - 3.2.dp.toPx()),
                cornerRadius = CornerRadius(1.dp.toPx())
            )
        }
    }
}

@Composable
private fun SunMoonIcon(dark: Boolean, tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension * 0.32f
        if (dark) {
            val moon = Path().apply {
                addOval(Rect(c.x - r, c.y - r, c.x + r, c.y + r))
            }
            val cutR = r * 0.88f
            val cutC = Offset(c.x + r * 0.42f, c.y - r * 0.16f)
            val cut = Path().apply {
                addOval(Rect(cutC.x - cutR, cutC.y - cutR, cutC.x + cutR, cutC.y + cutR))
            }
            val crescent = Path().apply { op(moon, cut, PathOperation.Difference) }
            drawPath(crescent, color = tint)
        } else {
            drawCircle(color = tint, radius = r * 0.72f, center = c)
            val path = Path()
            for (i in 0 until 8) {
                val a = Math.toRadians((i * 45).toDouble())
                val inner = r * 1.05f
                val outer = r * 1.45f
                path.moveTo(c.x + inner * kotlin.math.cos(a).toFloat(), c.y + inner * kotlin.math.sin(a).toFloat())
                path.lineTo(c.x + outer * kotlin.math.cos(a).toFloat(), c.y + outer * kotlin.math.sin(a).toFloat())
            }
            drawPath(path, color = tint, style = Stroke(width = 1.6.dp.toPx()))
        }
    }
}

@Composable
private fun ModeSwitch(left: String, right: String, leftOn: Boolean, onLeft: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x22FFFFFF)).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            left,
            Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                .background(if (leftOn) Color(0xFFE8D9A8) else Color.Transparent)
                .clickable { onLeft(true) }.padding(vertical = 10.dp),
            color = if (leftOn) Color(0xFF2B2430) else Color.White,
            textAlign = TextAlign.Center, fontSize = 13.sp
        )
        Text(
            right,
            Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                .background(if (!leftOn) Color(0xFFE8D9A8) else Color.Transparent)
                .clickable { onLeft(false) }.padding(vertical = 10.dp),
            color = if (!leftOn) Color(0xFF2B2430) else Color.White,
            textAlign = TextAlign.Center, fontSize = 13.sp
        )
    }
}

@Composable
private fun ColBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    val gold = Color(0xFFE8D9A8)
    Column(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(0x33E8D9A8) else Color.Transparent)
            .clickable(onClick = onClick).padding(8.dp, 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = if (selected) gold else tint, modifier = Modifier.size(22.dp))
        Text(label, color = (if (selected) gold else tint).copy(0.85f), fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun PageLeaf(page: ReaderPage?, family: FontFamily, size: Float, ink: Color, modifier: Modifier) {
    Column(modifier.padding(26.dp, 88.dp, 26.dp, 80.dp)) {
        if (page == null) {
            Text("…", color = ink, fontFamily = family)
            return
        }
        if (page.showChapterTitle) {
            Text(
                page.chapterTitle, color = ink, fontFamily = family, fontSize = (size + 10).sp,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
        }
        page.paragraphs.forEachIndexed { i, para ->
            if (i == 0 && page.showChapterTitle) Drop(para, family, size, ink)
            else Text(
                para, color = ink, fontFamily = family, fontSize = size.sp,
                lineHeight = (size * 1.55f).sp, modifier = Modifier.padding(bottom = 12.dp)
            )
        }
    }
}

@Composable
private fun Drop(text: String, family: FontFamily, size: Float, ink: Color) {
    val t = text.trim()
    if (t.isEmpty()) return
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontFamily = family, fontSize = (size * 3.1f).sp, fontWeight = FontWeight.Medium, color = ink)) {
                append(t.first())
            }
            withStyle(SpanStyle(fontFamily = family, fontSize = size.sp, color = ink)) { append(t.drop(1)) }
        },
        lineHeight = (size * 1.55f).sp,
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

private fun registerBatteryReceiver(
    activity: Activity,
    receiver: BroadcastReceiver?,
    filter: IntentFilter
): Intent? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        @Suppress("DEPRECATION")
        activity.registerReceiver(receiver, filter)
    }
}

private fun nowTime(): String =
    SimpleDateFormat("h:mm", Locale.getDefault()).format(Date())

@Composable
private fun PdfPageImage(
    path: String?,
    index: Int,
    ink: Color,
    modifier: Modifier,
    fillViewport: Boolean
) {
    var bmp by remember(path, index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path, index) {
        bmp = withContext(Dispatchers.IO) {
            path?.let { File(it) }?.takeIf { it.exists() }?.let { renderPdf(it, index) }
        }
    }
    val image = bmp
    if (image != null) {
        val aspect = (image.width.toFloat() / image.height.toFloat()).coerceIn(0.4f, 2.2f)
        Image(
            image.asImageBitmap(),
            contentDescription = "Page ${index + 1}",
            modifier = if (fillViewport) modifier else modifier.aspectRatio(aspect),
            contentScale = if (fillViewport) ContentScale.Fit else ContentScale.FillWidth
        )
    } else {
        Box(
            (if (fillViewport) modifier else modifier.aspectRatio(1f / 1.294f)),
            contentAlignment = Alignment.Center
        ) { Text("Page ${index + 1}", color = ink) }
    }
}

private fun renderPdf(file: File, index: Int): Bitmap? {
    return try {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        if (index !in 0 until renderer.pageCount) {
            renderer.close(); pfd.close(); return null
        }
        val page = renderer.openPage(index)
        val w = 1080
        val h = ((w.toFloat() * page.height) / page.width).toInt().coerceIn(200, 1800)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close(); renderer.close(); pfd.close()
        bmp
    } catch (_: Exception) {
        null
    }
}
