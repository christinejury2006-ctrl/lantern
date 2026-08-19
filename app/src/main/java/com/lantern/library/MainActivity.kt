package com.lantern.library

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.LanternStore
import com.lantern.library.data.ReaderTheme
import com.lantern.library.ui.components.FadeToast
import com.lantern.library.ui.screens.ExploreScreen
import com.lantern.library.ui.screens.LibraryScreen
import com.lantern.library.ui.screens.ProfileScreen
import com.lantern.library.ui.screens.ReaderScreen
import com.lantern.library.ui.screens.SearchScreen
import com.lantern.library.ui.theme.Ink
import com.lantern.library.ui.theme.LanternTheme
import com.lantern.library.ui.theme.NightText

class MainActivity : ComponentActivity() {
    private val store: LanternStore by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LanternRoot(store) }
    }
}

private sealed class Route {
    object Library : Route()
    object Search : Route()
    object Explore : Route()
    object Profile : Route()
    data class Reader(val id: String) : Route()
}

@Composable
private fun LanternRoot(store: LanternStore) {
    var tab by remember { mutableStateOf<Route>(Route.Library) }
    val theme = store.readingPrefs.theme
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) res.data?.data?.let { store.importUri(it) }
    }
    fun import() {
        picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/epub+zip", "application/pdf", "application/octet-stream"))
        })
    }
    LanternTheme(theme) {
        Box(Modifier.fillMaxSize()) {
            when (val r = tab) {
                Route.Library -> LibraryScreen(store.books, theme, onOpen = { tab = Route.Reader(it.id) }, onRemove = { store.remove(it) }, onImport = { import() })
                Route.Search -> SearchScreen(theme) { remote -> store.download(remote) { book -> tab = Route.Reader(book.id) } }
                Route.Explore -> ExploreScreen(theme) { remote -> store.download(remote) { book -> tab = Route.Reader(book.id) } }
                Route.Profile -> ProfileScreen(store.books, store.account, store.readingPrefs, { store.setPrefs(it) }, { n, e -> store.signIn(n, e) }, { store.signOut() })
                is Route.Reader -> {
                    val book = store.book(r.id)
                    if (book == null) tab = Route.Library
                    else ReaderScreen(book, store.readingPrefs, { store.setPrefs(it) }, { tab = Route.Library }, { p, n -> store.markRead(book.id, p, n) }, { store.addBookmark(book.id, it) })
                }
            }
            if (tab !is Route.Reader) BottomBar(theme, tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
            Box(Modifier.align(Alignment.TopCenter).padding(top = 48.dp)) { FadeToast(store.toast) }
        }
    }
}

@Composable
private fun BottomBar(theme: ReaderTheme, current: Route, onTab: (Route) -> Unit, modifier: Modifier = Modifier) {
    val dark = theme == ReaderTheme.DARK
    val ink = if (dark) NightText else Ink
    Row(
        modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp).clip(RoundedCornerShape(24.dp))
            .background(if (dark) Color(0xCC3A4454) else Color(0xCCFFFFFF)).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        TabIcon(Icons.Filled.Home, "Library", current is Route.Library, ink) { onTab(Route.Library) }
        TabIcon(Icons.Filled.Search, "Search", current is Route.Search, ink) { onTab(Route.Search) }
        TabIcon(Icons.Filled.Star, "Explore", current is Route.Explore, ink) { onTab(Route.Explore) }
        TabIcon(Icons.Filled.Person, "You", current is Route.Profile, ink) { onTab(Route.Profile) }
    }
}

@Composable
private fun TabIcon(icon: ImageVector, label: String, on: Boolean, ink: Color, click: () -> Unit) {
    Column(Modifier.clickable(onClick = click).padding(horizontal = 10.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = if (on) ink else ink.copy(0.45f), modifier = Modifier.size(22.dp))
        Text(label, color = if (on) ink else ink.copy(0.45f), fontSize = 10.sp)
    }
}
