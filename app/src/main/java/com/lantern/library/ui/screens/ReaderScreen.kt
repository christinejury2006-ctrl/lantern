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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
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
import androidx.compose.ui.text.style.TextOverflow
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
import com.lantern.library.data.ReaderPage
import com.lantern.library.data.TocEntry
import com.lantern.library.data.ReaderAppearance
import com.lantern.library.data.ReaderTheme
import com.lantern.library.data.ReadingPrefs
import com.lantern.library.ui.components.AppearanceLayer
import com.lantern.library.ui.components.LeafPager
import com.lantern.library.ui.components.readerChromeBar
import com.lantern.library.ui.components.readerChromePanel
import com.lantern.library.ui.theme.NookPalette
import com.lantern.library.ui.theme.nookPalette
import com.lantern.library.ui.theme.Playfair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ReaderMenu { None, Settings, Appearance, Nav, Chapters, Marks }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(
    book: LibraryBook,
    prefs: ReadingPrefs,
    onPrefs: (ReadingPrefs) -> Unit,
    onBack: () -> Unit,
    onProgress: (Int, Int) -> Unit,
    onBookmark: (Int) -> Unit,
    appearance: ReaderAppearance = ReaderAppearance(),
    onAppearance: (ReaderAppearance) -> Unit = {},
    savedBookmarks: List<Int> = emptyList(),
    onEnsureAvailable: suspend () -> LibraryBook? = { null }
) {
    val activity = LocalContext.current as Activity
    val family = LanternFonts.family(prefs.fontId)
    val palette = nookPalette(prefs.readerTheme)
    val dark = palette.dark
    // Every colour in the reader comes from the palette now: the page, the type, the bars and
    // the panels. This is an appearance change only — the reading, the gestures, the menus and
    // the page turn are exactly as they were.
    val ink = palette.ink
    val mute = palette.mute
    // A recessed control: a chip that is not selected, or a switch's track.
    val well = if (dark) palette.ink.copy(alpha = 0.10f) else palette.ink.copy(alpha = 0.06f)
    var chrome by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(ReaderMenu.None) }
    val bookmarks = remember(book.id) {
        mutableStateListOf<Int>().also { it.addAll(savedBookmarks) }
    }
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
        val sourceBook = if (book.localFileAvailable) book else onEnsureAvailable() ?: book
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val file = sourceBook.filePath?.let { File(it) }
                if (file == null || !file.exists() || file.length() <= 0L) {
                    Triple(emptyList<Chapter>(), emptyList<TocEntry>(), "This book is not available offline and could not be restored." to 1)
                } else when (sourceBook.format) {
                    BookFormat.PDF -> {
                        val count = BookIo.pdfPageCount(file).coerceAtLeast(1)
                        Triple(emptyList<Chapter>(), BookIo.readPdfOutline(activity.applicationContext, file), null to count)
                    }
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

    // Pagination is measured, not estimated: the page box is reported by the pager itself, and
    // until the first layout pass has run the window size stands in for it so the first frame is
    // already close. See `PageMeasure.kt` — the old character budget is what clipped the text.
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    var pageBox by remember(book.id) { mutableStateOf(IntSize.Zero) }
    val estimatedBox = remember(config.screenWidthDp, config.screenHeightDp, density) {
        with(density) {
            IntSize(config.screenWidthDp.dp.roundToPx(), config.screenHeightDp.dp.roundToPx())
        }
    }
    val pages = rememberMeasuredPages(
        chapters = chapters,
        fallback = book.synopsis.ifBlank { "This book has no text yet." },
        family = family,
        fontSizeSp = prefs.fontSizeSp,
        pageBox = if (pageBox.width > 0 && pageBox.height > 0) pageBox else estimatedBox
    )
    val pageCount = if (book.format == BookFormat.PDF) pdfCount else pages.size.coerceAtLeast(1)
    val pager = rememberPagerState(
        initialPage = book.currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
        pageCount = { pageCount }
    )
    // EPUB swipe mode keeps its own page index: the page is turned by a hinged leaf,
    // not by a pager, so the reader owns the current page here.
    var epubPage by remember(book.id) {
        mutableStateOf(book.currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
    }
    // A re-pagination (font size change, rotation, a different screen) can leave the remembered
    // page index past the end of the book. Clamp it rather than showing a blank leaf.
    LaunchedEffect(pageCount) {
        if (epubPage > pageCount - 1) epubPage = (pageCount - 1).coerceAtLeast(0)
    }
    val scrollState = rememberScrollState()
    val pdfList = rememberLazyListState(
        initialFirstVisibleItemIndex = book.currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    )
    val chapterY = remember(book.id) { mutableStateMapOf<Int, Int>() }
    val scope = rememberCoroutineScope()
    val epubUsesPager = prefs.swipeMode && book.format != BookFormat.PDF
    val pdfUsesPager = book.format == BookFormat.PDF && prefs.swipeMode
    var epubRestored by remember(book.id) { mutableStateOf(book.format == BookFormat.PDF) }
    LaunchedEffect(book.id, prefs.swipeMode) {
        if (book.format != BookFormat.PDF) epubRestored = false
    }
    val pdfSession = remember(book.id, book.filePath, pdfUsesPager) {
        if (book.format == BookFormat.PDF && !pdfUsesPager)
            book.filePath?.let { PdfDocSession(File(it)) }
        else null
    }
    DisposableEffect(pdfSession) {
        onDispose { pdfSession?.close() }
    }
    LaunchedEffect(pager, pageCount, prefs.swipeMode, book.format, ready, epubRestored) {
        if (!prefs.swipeMode) return@LaunchedEffect
        if (book.format != BookFormat.PDF) return@LaunchedEffect
        snapshotFlow { pager.currentPage }.collect { onProgress(it, pageCount) }
    }
    LaunchedEffect(epubPage, pageCount, prefs.swipeMode, book.format, ready) {
        if (!prefs.swipeMode || book.format == BookFormat.PDF || !ready) return@LaunchedEffect
        onProgress(epubPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)), pageCount)
    }
    LaunchedEffect(scrollState, pageCount, prefs.swipeMode, book.format, ready, epubRestored) {
        if (prefs.swipeMode || book.format == BookFormat.PDF || !ready || !epubRestored) return@LaunchedEffect
        snapshotFlow { scrollState.value to scrollState.maxValue }.collect { (value, max) ->
            if (max <= 0 || pageCount <= 0) return@collect
            val page = ((value.toFloat() / max.toFloat()) * (pageCount - 1).coerceAtLeast(0)).toInt()
            onProgress(page, pageCount)
        }
    }
    LaunchedEffect(pdfList, pageCount, prefs.swipeMode, book.format, ready) {
        if (prefs.swipeMode || book.format != BookFormat.PDF || !ready) return@LaunchedEffect
        snapshotFlow { pdfList.firstVisibleItemIndex }.collect { index ->
            onProgress(index.coerceIn(0, (pageCount - 1).coerceAtLeast(0)), pageCount)
        }
    }
    LaunchedEffect(book.id, ready, pageCount, prefs.swipeMode, book.format) {
        if (book.format == BookFormat.PDF) return@LaunchedEffect
        if (!prefs.swipeMode) return@LaunchedEffect
        if (!ready || pageCount <= 0) return@LaunchedEffect
        val target = book.currentPage.coerceIn(0, pageCount - 1)
        if (epubPage != target) epubPage = target
        epubRestored = true
    }
    LaunchedEffect(book.id, ready, prefs.swipeMode, book.format, scrollState.maxValue, pageCount) {
        if (!ready || prefs.swipeMode || book.format == BookFormat.PDF) return@LaunchedEffect
        if (scrollState.maxValue <= 0 || pageCount <= 0) return@LaunchedEffect
        val frac = if (pageCount <= 1) 0f else book.currentPage.toFloat() / (pageCount - 1).coerceAtLeast(1)
        val target = (frac * scrollState.maxValue).toInt().coerceIn(0, scrollState.maxValue)
        if (kotlin.math.abs(scrollState.value - target) > 8) scrollState.scrollTo(target)
        epubRestored = true
    }
    LaunchedEffect(book.id, ready, prefs.swipeMode, book.format, pageCount) {
        if (!ready || prefs.swipeMode || book.format != BookFormat.PDF || pageCount <= 0) return@LaunchedEffect
        val target = book.currentPage.coerceIn(0, pageCount - 1)
        if (pdfList.firstVisibleItemIndex != target) pdfList.scrollToItem(target)
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
                if (book.format == BookFormat.PDF) pager.scrollToPage(target) else epubPage = target
            } else if (book.format == BookFormat.PDF) {
                pdfList.scrollToItem(target)
            } else if (scrollState.maxValue > 0 && pageCount > 1) {
                val dest = ((target.toFloat() / (pageCount - 1)) * scrollState.maxValue).toInt()
                scrollState.scrollTo(dest.coerceIn(0, scrollState.maxValue))
            }
            onProgress(target, pageCount)
        }
    }
    fun goToChapter(index: Int) {
        if (index < 0) return
        if (book.format == BookFormat.PDF) {
            go(index)
            closeMenus()
            return
        }
        val page = pages.indexOfFirst { it.chapterIndex == index }
        if (prefs.swipeMode) {
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

    val accent = palette.accent
    val onAccent = palette.accentInk
    val chromeOp = appearance.look.chromeOpacity
    val chromeBar = readerChromeBar(chromeOp, dark)
    val chromePanel = readerChromePanel(chromeOp, dark)
    val pageTap = Modifier.chromeTap(enabled = !menuOpen, onTap = { toggleChrome() })

    AppearanceLayer(appearance.look, dark, contentWash = false) {
    // Paper under the page. The appearance layer is the reader's own choice and stays exactly
    // where it was; this only fills in what was missing when no wallpaper is set, so the text
    // sits on paper rather than on the room's rose gradient. Choose a wallpaper and it shows.
    val pageModifier = if (appearance.look.hasWallpaper) Modifier else Modifier.background(palette.page)
    Box(Modifier.fillMaxSize().then(pageModifier)) {
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
                    LazyColumn(
                        state = pdfList,
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = !menuOpen,
                        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 88.dp, bottom = 96.dp)
                    ) {
                        items(
                            count = pdfCount,
                            key = { index -> index }
                        ) { i ->
                            PdfPageImage(
                                path = book.filePath,
                                index = i,
                                ink = ink,
                                modifier = Modifier.fillMaxWidth().then(pageTap),
                                fillViewport = false,
                                session = pdfSession
                            )
                            if (i < pdfCount - 1) Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
            else -> key(book.id, epubUsesPager) {
                if (epubUsesPager) {
                    // The pager is re-created when anything that decides where a page *breaks*
                    // changes — the book, the font size, the measured page box — instead of being
                    // re-fed underneath a curl in flight. A page list that shrinks during an
                    // animation is what used to hand `PageLeaf` a null page mid-turn; now the
                    // surface is rebuilt from a clean state and starts on the reader's page.
                    key(prefs.fontSizeSp, pages.size) {
                        LeafPager(
                            pageCount = pages.size.coerceAtLeast(1),
                            page = epubPage,
                            onPage = { epubPage = it },
                            enabled = !menuOpen,
                            paper = palette.page,
                            modifier = Modifier.fillMaxSize().onSizeChanged { pageBox = it }
                        ) { i, interactive ->
                            PageLeaf(
                                pages.getOrNull(i), family, prefs.fontSizeSp, ink,
                                Modifier.fillMaxSize()
                                    .then(if (interactive) pageTap else Modifier)
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
                chrome = chromeOp,
                dark = dark,
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
                        book.title.ifBlank { "Reading" },
                        Modifier.weight(1f), color = ink,
                        fontFamily = Playfair, fontSize = 17.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(40.dp))
                }
            }
        }

        if (chrome) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                    .padding(start = 10.dp, end = 10.dp, top = 18.dp, bottom = 8.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(chromeBar)
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ColBtn(Icons.Filled.List, "Chapters", palette, selected = menu == ReaderMenu.Chapters) {
                    toggleMenu(ReaderMenu.Chapters)
                }
                ColBtn(Icons.Filled.Search, "Navigate", palette, selected = menu == ReaderMenu.Nav) {
                    toggleMenu(ReaderMenu.Nav)
                }
                Column(
                    Modifier.clickable {
                        onPrefs(prefs.copy(readerTheme = if (dark) ReaderTheme.LIGHT else ReaderTheme.DARK))
                    }.padding(4.dp, 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(well),
                        contentAlignment = Alignment.Center
                    ) {
                        SunMoonIcon(dark, ink, Modifier.size(20.dp))
                    }
                    Text(if (dark) "Dark" else "Light", color = mute, fontSize = 10.sp)
                }
                ColBtn(Icons.Filled.Settings, "Settings", palette, selected = menu == ReaderMenu.Settings) {
                    toggleMenu(ReaderMenu.Settings)
                }
                ColBtn(Icons.Filled.Favorite, "Bookmarks", palette, selected = menu == ReaderMenu.Marks) {
                    val page = when {
                        prefs.swipeMode -> if (book.format == BookFormat.PDF) pager.currentPage else epubPage
                        book.format == BookFormat.PDF -> pdfList.firstVisibleItemIndex
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
                    .background(chromePanel)
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
                    Text("Text & Screen Settings", color = ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(14.dp))
                    Text("Brightness", color = mute, fontSize = 13.sp)
                    Slider(
                        prefs.brightness,
                        { onPrefs(prefs.copy(brightness = it)) },
                        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent)
                    )
                    if (book.format == BookFormat.PDF) {
                        Text(
                            "Font size is for EPUB text. PDFs stay as printed.",
                            color = mute, fontSize = 12.sp,
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
                                val on = kotlin.math.abs(prefs.fontSizeSp - s) < 0.05f
                                Text(
                                    l,
                                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                        .background(if (on) accent else well)
                                        .clickable { onPrefs(prefs.copy(fontSizeSp = s)) }.padding(10.dp),
                                    color = if (on) onAccent else ink, textAlign = TextAlign.Center
                                )
                            }
                        }
                        Text("Font", color = ink, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                        LanternFonts.options.forEach { opt ->
                            val on = prefs.fontId == opt.id || (prefs.fontId == "times" && opt.id == "lora")
                            Text(
                                opt.label,
                                Modifier.fillMaxWidth().padding(bottom = 4.dp).clip(RoundedCornerShape(12.dp))
                                    .background(if (on) accent else well)
                                    .clickable { onPrefs(prefs.copy(fontId = opt.id)) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                color = if (on) onAccent else ink,
                                fontSize = 13.sp
                            )
                        }
                    }
                    Text("Reading Mode", color = ink, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    ModeSwitch("Swipe", "Scroll", prefs.swipeMode, palette) { onPrefs(prefs.copy(swipeMode = it)) }
                    Text("Page Orientation", color = ink, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    ModeSwitch("Portrait", "Landscape", !prefs.landscape, palette) { onPrefs(prefs.copy(landscape = !it)) }
                    Text(
                        "Appearance",
                        color = accent,
                        modifier = Modifier.padding(top = 16.dp).clickable { menu = ReaderMenu.Appearance }
                    )
                    Text(
                        "Reader background only. Library is unchanged.",
                        color = mute,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        if (menu == ReaderMenu.Appearance) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {}
                    )
            ) {
                AppearanceEditor(
                    title = "Reader Appearance",
                    theme = prefs.readerTheme,
                    look = appearance.look,
                    onChange = { onAppearance(ReaderAppearance(it)) },
                    onDone = { closeMenus() }
                )
            }
        }

        if (menu == ReaderMenu.Nav) {
            Box(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                    .padding(16.dp, 0.dp, 16.dp, 78.dp)
                    .clip(RoundedCornerShape(18.dp)).background(chromePanel)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {}
                    )
            ) {
                Column(Modifier.padding(16.dp)) {
                    val at = when {
                        prefs.swipeMode -> pager.currentPage
                        book.format == BookFormat.PDF -> pdfList.firstVisibleItemIndex
                        scrollState.maxValue <= 0 -> 0
                        else -> ((scrollState.value.toFloat() / scrollState.maxValue) * (pageCount - 1).coerceAtLeast(0)).toInt()
                    }
                    Text("Navigate", color = ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "Page ${at + 1} of $pageCount",
                        color = mute, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )
                    Slider(
                        at.toFloat(),
                        { go(it.toInt()) },
                        valueRange = 0f..(pageCount - 1).coerceAtLeast(0).toFloat(),
                        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent)
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("First", color = ink, modifier = Modifier.clickable { go(0) }.padding(8.dp))
                        Text("Previous", color = ink, modifier = Modifier.clickable { go(at - 1) }.padding(8.dp))
                        Text("Next", color = ink, modifier = Modifier.clickable { go(at + 1) }.padding(8.dp))
                        Text("Last", color = ink, modifier = Modifier.clickable { go(pageCount - 1) }.padding(8.dp))
                    }
                }
            }
        }

        if (menu == ReaderMenu.Chapters) {
            Column(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                    .padding(16.dp, 0.dp, 16.dp, 78.dp)
                    .clip(RoundedCornerShape(18.dp)).background(chromePanel)
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
                Text("Chapters", color = ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                val list = toc.filter { it.chapterIndex >= 0 }
                if (list.isEmpty()) {
                    Text(
                        "No table of contents in this book.",
                        color = mute, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else {
                    list.forEach { entry ->
                        Text(
                            entry.title,
                            color = ink,
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
                    .clip(RoundedCornerShape(18.dp)).background(chromePanel)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {}
                    )
                    .padding(16.dp)
            ) {
                Text("Bookmarks", color = ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (bookmarks.isEmpty()) {
                    Text(
                        "None yet. Open Bookmarks to save this page.",
                        color = mute, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else {
                    bookmarks.distinct().sorted().forEach { p ->
                        Text(
                            "Page ${p + 1}",
                            color = ink,
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
private fun GlassStatus(
    time: String,
    percent: Int,
    charging: Boolean,
    ink: Color,
    chrome: Float,
    dark: Boolean,
    modifier: Modifier
) {
    Row(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(readerChromeBar(chrome, dark))
            .border(1.dp, nookPalette(dark).glassEdge, RoundedCornerShape(20.dp))
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
        // Charging is a device state, not a theme colour: it stays green in both voices.
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
private fun ModeSwitch(
    left: String,
    right: String,
    leftOn: Boolean,
    palette: NookPalette,
    onLeft: (Boolean) -> Unit
) {
    val well = if (palette.dark) palette.ink.copy(alpha = 0.10f) else palette.ink.copy(alpha = 0.06f)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(well).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            left,
            Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                .background(if (leftOn) palette.accent else Color.Transparent)
                .clickable { onLeft(true) }.padding(vertical = 10.dp),
            color = if (leftOn) palette.accentInk else palette.mute,
            textAlign = TextAlign.Center, fontSize = 13.sp
        )
        Text(
            right,
            Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                .background(if (!leftOn) palette.accent else Color.Transparent)
                .clickable { onLeft(false) }.padding(vertical = 10.dp),
            color = if (!leftOn) palette.accentInk else palette.mute,
            textAlign = TextAlign.Center, fontSize = 13.sp
        )
    }
}

@Composable
private fun ColBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    palette: NookPalette,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    val well = if (palette.dark) palette.ink.copy(alpha = 0.10f) else palette.ink.copy(alpha = 0.06f)
    Column(
        Modifier.clickable(onClick = onClick).padding(4.dp, 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(well)
                .then(if (selected) Modifier.border(2.dp, palette.accent, RoundedCornerShape(20.dp)) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = if (selected) palette.accent else palette.ink, modifier = Modifier.size(20.dp))
        }
        Text(label, color = if (selected) palette.accent else palette.mute, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun PageLeaf(page: ReaderPage?, family: FontFamily, size: Float, ink: Color, modifier: Modifier) {
    Column(
        modifier.padding(
            start = PagePadHorizontal,
            top = PagePadTop,
            end = PagePadHorizontal,
            bottom = PagePadBottom
        )
    ) {
        // No early return here. A page that is not available yet draws a placeholder in the same
        // slot the text would occupy, so the page subtree has one shape from the first frame after
        // a re-pagination to the last. Returning early is legal Kotlin, but it makes the number of
        // composition groups depend on data that can change while a gesture is running, which is
        // the class of thing that took the reader down.
        if (page == null) {
            Text("…", color = ink, fontFamily = family)
        } else {
            if (page.showChapterTitle) {
                Text(
                    page.chapterTitle, color = ink, fontFamily = family, fontSize = (size + 10).sp,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(PageTitleGap))
            }
            // A page is a block of text, not a column of lines: the paragraphs flow into one
            // justified measure rather than each starting a new, ragged line at the left edge.
            Text(
                text = buildAnnotatedString {
                    page.paragraphs.forEachIndexed { i, para ->
                        if (i > 0) append("\n\n")
                        append(para.trim())
                    }
                },
                color = ink,
                fontFamily = family,
                fontSize = size.sp,
                lineHeight = (size * 1.55f).sp,
                textAlign = TextAlign.Justify,
                modifier = Modifier.fillMaxWidth()
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
    fillViewport: Boolean,
    session: PdfDocSession? = null
) {
    var bmp by remember(path, index) { mutableStateOf<Bitmap?>(null) }
    var aspect by remember(path, index, session) {
        mutableStateOf(session?.cachedAspect(index) ?: session?.typicalAspect ?: (1f / 1.294f))
    }
    LaunchedEffect(path, index, session, fillViewport) {
        if (fillViewport || session == null) {
            bmp = withContext(Dispatchers.IO) {
                path?.let { File(it) }?.takeIf { it.exists() }?.let { renderPdf(it, index) }
            }
            return@LaunchedEffect
        }
        try {
            val measured = withContext(Dispatchers.IO) { session.aspect(index) }
            if (measured != null) aspect = measured
            val image = withContext(Dispatchers.IO) { session.render(index) }
            bmp = image
            awaitCancellation()
        } finally {
            bmp = null
            session.release(index)
        }
    }
    val image = bmp
    if (image != null && !image.isRecycled) {
        val drawn = (image.width.toFloat() / image.height.toFloat()).coerceIn(0.4f, 2.2f)
        Image(
            image.asImageBitmap(),
            contentDescription = "Page ${index + 1}",
            modifier = if (fillViewport) modifier else modifier.aspectRatio(drawn),
            contentScale = if (fillViewport) ContentScale.Fit else ContentScale.FillWidth
        )
    } else {
        Box(
            (if (fillViewport) modifier else modifier.aspectRatio(aspect)),
            contentAlignment = Alignment.Center
        ) { Text("Page ${index + 1}", color = ink) }
    }
}

private class PdfDocSession(private val file: File) {
    private val lock = Any()
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val aspects = HashMap<Int, Float>()
    private val bitmaps = HashMap<Int, Bitmap>()
    @Volatile var typicalAspect = 1f / 1.294f
        private set

    fun cachedAspect(index: Int): Float? = synchronized(lock) { aspects[index] }

    fun aspect(index: Int): Float? = synchronized(lock) {
        aspects[index] ?: run {
            val r = openLocked() ?: return@run null
            if (index !in 0 until r.pageCount) return@run null
            val page = r.openPage(index)
            val a = (page.width.toFloat() / page.height.toFloat()).coerceIn(0.4f, 2.2f)
            page.close()
            aspects[index] = a
            typicalAspect = a
            a
        }
    }

    fun render(index: Int): Bitmap? = synchronized(lock) {
        bitmaps[index]?.takeIf { !it.isRecycled }?.let { return it }
        val r = openLocked() ?: return null
        val painted = paintPdfPage(r, index) ?: return null
        aspects[index] = painted.second
        typicalAspect = painted.second
        bitmaps[index] = painted.first
        painted.first
    }

    fun release(index: Int) {
        synchronized(lock) {
            bitmaps.remove(index)?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    fun close() {
        synchronized(lock) {
            bitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
            bitmaps.clear()
            runCatching { renderer?.close() }
            renderer = null
            runCatching { pfd?.close() }
            pfd = null
        }
    }

    private fun openLocked(): PdfRenderer? {
        renderer?.let { return it }
        return try {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd = fd
            val r = PdfRenderer(fd)
            renderer = r
            r
        } catch (_: Exception) {
            runCatching { pfd?.close() }
            pfd = null
            renderer = null
            null
        }
    }
}

private fun paintPdfPage(renderer: PdfRenderer, index: Int): Pair<Bitmap, Float>? {
    if (index !in 0 until renderer.pageCount) return null
    val page = renderer.openPage(index)
    val w = 1080
    val h = ((w.toFloat() * page.height) / page.width).toInt().coerceIn(200, 1800)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    bmp.eraseColor(android.graphics.Color.WHITE)
    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    val aspect = (page.width.toFloat() / page.height.toFloat()).coerceIn(0.4f, 2.2f)
    page.close()
    return bmp to aspect
}

private fun renderPdf(file: File, index: Int): Bitmap? {
    return try {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val out = paintPdfPage(renderer, index)?.first
        renderer.close(); pfd.close()
        out
    } catch (_: Exception) {
        null
    }
}
