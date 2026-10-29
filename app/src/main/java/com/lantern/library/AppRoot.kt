package com.lantern.library

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.CatalogBook
import com.lantern.library.data.DiscoveryBook
import com.lantern.library.data.GoogleAuth
import com.lantern.library.data.Gutendex
import com.lantern.library.data.LanternStore
import com.lantern.library.data.ReaderTheme
import com.lantern.library.data.Recommendations
import com.lantern.library.ui.components.FadeToast
import com.lantern.library.ui.components.NookBottomBar
import com.lantern.library.ui.components.NookTabs
import com.lantern.library.ui.theme.nookPalette
import com.lantern.library.ui.screens.BookDetailsOverlay
import com.lantern.library.ui.screens.InterestOnboardingScreen
import com.lantern.library.ui.screens.LibraryScreen
import com.lantern.library.ui.screens.ProfileScreen
import com.lantern.library.ui.screens.ReadingNookScreen
import com.lantern.library.ui.screens.ReaderScreen
import com.lantern.library.ui.screens.SearchScreen
import com.lantern.library.ui.screens.StudioScreen
import com.lantern.library.ui.theme.Ink
import com.lantern.library.ui.theme.LanternTheme
import com.lantern.library.ui.theme.Brass
import com.lantern.library.ui.theme.NightText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun strongGutendexMatch(book: DiscoveryBook, hits: List<CatalogBook>): CatalogBook? {
    val wantTitle = Recommendations.normalize(book.title)
    if (wantTitle.isEmpty()) return null
    return hits.firstOrNull { hit ->
        Recommendations.normalize(hit.title) == wantTitle &&
            Recommendations.authorsCompatible(book.authors, listOf(hit.author))
    }
}

private sealed class Route {
    object Home : Route()
    object Library : Route()
    object Studio : Route()
    object Search : Route()
    object Profile : Route()
    data class Reader(val id: String, val fromStudio: Boolean = false) : Route()
}

@Composable
internal fun LanternRoot(store: LanternStore) {
    var tab by remember { mutableStateOf<Route>(Route.Home) }
    // Where closing a book goes back to. A book opened from the nook belongs to the nook, not to
    // the library tab, so the reader returns to wherever it was opened from.
    var readerBack by remember { mutableStateOf<Route>(Route.Home) }
    var details by remember { mutableStateOf<DiscoveryBook?>(null) }
    var pickerForStudio by remember { mutableStateOf(true) }
    val theme = store.readingPrefs.theme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activity = context as Activity
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data
        if (res.resultCode == Activity.RESULT_OK && uri != null) {
            if (pickerForStudio) store.studioOpen(uri) else store.importUri(uri)
        } else if (pickerForStudio) store.studioCancelPick()
    }
    // Every failure of Google sign-in that lives in the Firebase console — not in this code —
    // lands in this one dialog, with the package and SHA-1 of the running build, because the
    // alternative is a toast that says "failed (10)" and a reader with nothing to act on.
    var setupHint by remember { mutableStateOf<SetupHint?>(null) }
    val clipboard = LocalClipboardManager.current
    val googleSignIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        when (val out = GoogleAuth.parseOutcome(res.data)) {
            is com.lantern.library.data.GoogleSignInOutcome.Ok -> store.onGoogleSignedIn(out.account)
            com.lantern.library.data.GoogleSignInOutcome.Cancelled -> store.toast("Google sign-in cancelled")
            is com.lantern.library.data.GoogleSignInOutcome.Failed -> if (out.code == 10) {
                setupHint = SetupHint.Unregistered(GoogleAuth.registrationHint(context))
            } else if (out.code == 7 || out.code == 8) {
                // 7/8: network or Play Services. Neither is a console problem; say so plainly
                // instead of sending the reader to Firebase for nothing.
                store.toast("Google Play Services is unavailable or offline (${out.code})")
            } else {
                store.toast("Google sign-in failed (${out.code})")
            }
        }
    }

    /** Starts the Google sheet, or explains why it cannot start. */
    fun beginGoogleSignIn() {
        val missing = GoogleAuth.missingConfiguration(context)
        if (missing != null) {
            setupHint = SetupHint.Unconfigured(missing)
        } else {
            googleSignIn.launch(GoogleAuth.signInIntent(activity))
        }
    }
    setupHint?.let { hint ->
        val (title, body) = when (hint) {
            is SetupHint.Unregistered -> "Google Sign-In isn't registered for this build" to
                "Google rejected this build with error 10: the Firebase project has no Android " +
                "OAuth client carrying this exact signing fingerprint, so the sign-in sheet never " +
                "opened.\n\nFirebase → Project settings → Your apps → com.lantern.library → Add " +
                "fingerprint, paste the SHA-1 below, then download google-services.json again.\n\n" +
                hint.details
            is SetupHint.Unconfigured -> "Google Sign-In isn't configured in this build" to
                hint.details
        }
        AlertDialog(
            onDismissRequest = { setupHint = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(GoogleAuth.registrationHint(context)))
                    setupHint = null
                    store.toast("Copied package + SHA-1")
                }) { Text("Copy") }
            },
            dismissButton = { TextButton(onClick = { setupHint = null }) { Text("Close") } }
        )
    }
    val driveConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        store.onDriveConsentFinished()
    }
    LaunchedEffect(store.driveConsentIntent) {
        val intent = store.takeDriveConsentIntent() ?: return@LaunchedEffect
        driveConsent.launch(intent)
    }
    fun openBookFile(studio: Boolean) {
        pickerForStudio = studio
        if (studio) store.studioBeginPick()
        picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/epub+zip", "application/pdf", "application/octet-stream"))
        })
    }
    fun addFile() { openBookFile(true) }
    fun importFile() { openBookFile(false) }
    fun openDiscoveryLink(book: DiscoveryBook): Boolean {
        val url = book.infoLink ?: book.buyLink ?: book.previewLink ?: book.canonicalLink
        if (url.isNullOrBlank()) return false
        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        }.getOrDefault(false)
    }
    fun getDiscovery(book: DiscoveryBook) {
        val owned = store.books.firstOrNull { Recommendations.inLibrary(book, listOf(it)) }
        if (owned != null) {
            details = null
            readerBack = tab
            store.openForReading(owned) { ready -> if (ready != null) tab = Route.Reader(ready.id) }
            return
        }
        if (book.publicDomain) {
            scope.launch {
                val hits = withContext(Dispatchers.IO) {
                    runCatching { Gutendex.search(book.title, null) }.getOrDefault(emptyList())
                }
                val hit = strongGutendexMatch(book, hits)
                if (hit != null) {
                    details = null
                    readerBack = tab
                    store.download(hit) { opened -> tab = Route.Reader(opened.id) }
                } else if (!openDiscoveryLink(book)) {
                    store.toast("No free edition found")
                }
            }
            return
        }
        if (!openDiscoveryLink(book)) store.toast("No store link available")
    }
    val needsOnboarding = !store.interestsChosen
    LanternTheme(theme) {
        Box(Modifier.fillMaxSize()) {
            if (needsOnboarding) {
                InterestOnboardingScreen(
                    theme = theme,
                    initial = store.interests,
                    allowCancel = false,
                    onContinue = { store.saveInterests(it) },
                    onCancel = {}
                )
            } else when (val r = tab) {
                Route.Home -> ReadingNookScreen(
                    books = store.books,
                    picks = store.forYou,
                    account = store.account,
                    theme = theme,
                    picksBusy = store.forYouBusy,
                    picksFailed = store.forYouFailed,
                    onOpenBook = { book ->
                        readerBack = Route.Home
                        store.openForReading(book) { ready ->
                            if (ready != null) tab = Route.Reader(ready.id)
                        }
                    },
                    onOpenPick = { pick -> details = pick },
                    onSearch = { tab = Route.Search },
                    onShelf = { tab = Route.Library },
                    onStudio = { tab = Route.Studio },
                    onRefreshPicks = { store.refreshForYou() },
                    onNotifications = { tab = Route.Profile },
                    onSeeAll = { tab = Route.Library }
                )
                Route.Library -> LibraryScreen(
                    books = store.books,
                    theme = theme,
                    onOpen = { book ->
                        readerBack = Route.Library
                        store.openForReading(book) { ready ->
                            if (ready != null) tab = Route.Reader(ready.id)
                        }
                    },
                    onRemove = { store.remove(it) },
                    onImport = { importFile() }
                )
                Route.Studio -> StudioScreen(
                    theme = theme,
                    session = store.studio,
                    web = store.web,
                    studioBooks = store.books.filter { it.id.startsWith("web_") },
                    onOpenBook = { made ->
                        readerBack = Route.Studio
                        store.openForReading(made) { ready ->
                            if (ready != null) tab = Route.Reader(ready.id)
                        }
                    },
                    onAddFile = { addFile() },
                    onSend = { title, author -> store.studioSend(title, author) },
                    onCancel = { store.studioCancel() },
                    onAnalyze = { store.analyzeWeb(it) },
                    onWebKeep = { store.webKeep(it) },
                    onWebRemove = { store.webRemove(it) },
                    onWebCancel = { store.webCancel() },
                    onOpenChapter = { store.webOpenChapter(it) },
                    onFollowPossible = { store.webFollowPossible(it) },
                    onIgnorePossible = { store.webIgnorePossible(it) },
                    onBeginMeta = { store.webBeginMeta() },
                    onSaveMeta = { t, a, s, img -> store.webSaveMeta(t, a, s, img) },
                    onSelectCover = { store.webSelectCover(it) },
                    onCompile = { store.webCompile() },
                    onPreview = {
                        readerBack = Route.Studio
                        store.webOpenPreview { ready -> if (ready != null) tab = Route.Reader(ready.id) }
                    },
                    onAddLibrary = { store.webAddToLibrary() },
                    onToggleCollect = { store.webToggleCollect(it) },
                    onSelectCollectAll = { store.webSelectCollectAll(it) },
                    onMoveCollect = { id, d -> store.webMoveCollect(id, d) },
                    onFetchCollection = { store.webFetchCollection() }
                )
                Route.Search -> SearchScreen(theme) { remote -> getDiscovery(remote) }
                Route.Profile -> ProfileScreen(
                    store.books, store.account, store.readingPrefs,
                    { store.setPrefs(it) },
                    { beginGoogleSignIn() },
                    { store.signOut(activity) },
                    { store.requestDriveConnect() },
                    { store.openInterestEditor() },
                    { libraryAppearOpen = true },
                    { store.syncNow() }
                )
                is Route.Reader -> {
                    val book = store.book(r.id)
                    val back = { tab = if (r.fromStudio) Route.Studio else readerBack }
                    if (book == null) back()
                    else ReaderScreen(
                        book, store.readingPrefs, { store.setPrefs(it) }, back,
                        { p, n -> store.markRead(book.id, p, n) }, { store.addBookmark(book.id, it) },
                        store.readerAppearance, { store.saveReaderAppearance(it) },
                        store.bookmarksFor(book.id),
                        onEnsureAvailable = { store.ensureBookAvailable(book.id) }
                    )
                }
            }
            val detail = details
            if (detail != null && tab is Route.Library) {
                BookDetailsOverlay(
                    book = detail,
                    theme = theme,
                    saved = store.isWantToRead(detail),
                    onDismiss = { details = null },
                    onWant = { store.addWantToRead(detail); details = null },
                    onNotInterested = { store.markNotInterested(detail); details = null }
                )
            }
            if (!needsOnboarding && tab !is Route.Reader && details == null) {
                BottomBar(theme, tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
            }
            if (store.editingInterests && store.interestsChosen) {
                InterestOnboardingScreen(
                    theme = theme,
                    initial = store.interests,
                    allowCancel = true,
                    onContinue = { store.saveInterests(it) },
                    onCancel = { store.closeInterestEditor() }
                )
            }
            Box(Modifier.align(Alignment.TopCenter).padding(top = 48.dp)) { FadeToast(store.toast) }
        }
    }
}

@Composable
private fun BottomBar(theme: ReaderTheme, current: Route, onTab: (Route) -> Unit, modifier: Modifier = Modifier) {
    // Four tabs, as the nook has: the room, discovery, the physical case, and the reader
    // themselves. Studio is not a tab — it is a tool reached from the nook's header, so the bar
    // stays a description of where you are rather than a menu of everything.
    val selected = when (current) {
        is Route.Home, is Route.Studio, is Route.Reader -> "home"
        is Route.Search -> "explore"
        is Route.Library -> "library"
        is Route.Profile -> "you"
    }
    NookBottomBar(
        palette = nookPalette(theme),
        tabs = NookTabs,
        selected = selected,
        onSelect = { key ->
            when (key) {
                "home" -> onTab(Route.Home)
                "explore" -> onTab(Route.Search)
                "library" -> onTab(Route.Library)
                else -> onTab(Route.Profile)
            }
        },
        modifier = modifier
    )
}

/** A Google sign-in failure that is the console's, not the code's, and what to do about it. */
private sealed interface SetupHint {
    /** Google rejected the build with error 10: no Android OAuth client for this fingerprint. */
    data class Unregistered(val details: String) : SetupHint
    /** The build itself is missing a piece the code cannot supply, such as the Web client ID. */
    data class Unconfigured(val details: String) : SetupHint
}
