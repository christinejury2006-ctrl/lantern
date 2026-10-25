package com.lantern.library.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.LibraryBook
import com.lantern.library.shelf.BookArt
import com.lantern.library.shelf.FilamentShelf
import com.lantern.library.shelf.ShelfLayout
import com.lantern.library.shelf.ShelfPrepared
import com.lantern.library.shelf.ShelfPreparer
import com.lantern.library.shelf.ShelfRuntime
import com.lantern.library.ui.components.LorePillButton
import com.lantern.library.ui.theme.Ink
import com.lantern.library.ui.theme.Parchment
import com.lantern.library.ui.theme.Playfair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The shelf rendered as a real lit room, with real books standing in it.
 *
 * This is the native scene, not the flat drawing: books have depth, the case curves around
 * you and a lifted book comes out of the shelf toward you. If the device cannot render it,
 * the screen says so — it does not quietly show the flat version instead.
 */
@Composable
fun ShelfPreviewScreen(
    books: List<LibraryBook>,
    onOpenBook: (LibraryBook) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val capability = remember { ShelfRuntime.inspect(context) }
    val room = remember(books) {
        books.mapIndexed { index, book ->
            BookArt(
                title = book.title,
                author = book.author,
                seed = if (book.title.isEmpty()) index else book.title.hashCode()
            )
        }
    }
    val slots = remember(room) { ShelfLayout.slots(room.size) }

    var prepared by remember { mutableStateOf<ShelfPrepared?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var lifted by remember { mutableStateOf(-1) }
    var drawn by remember { mutableStateOf(false) }
    var hud by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf("Lighting the room\u2026") }

    // If no frame arrives at all, say so rather than leaving a permanent "loading" screen.
    LaunchedEffect(capability.supported, room.size) {
        if (!capability.supported || room.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(12_000)
        if (!drawn && failure == null) {
            failure = "The scene has not drawn a frame yet. This phone may not be able to " +
                "run it, or another app is holding the graphics driver."
        }
    }

    LaunchedEffect(room, capability.supported) {
        if (!capability.supported || room.isEmpty()) return@LaunchedEffect
        // Compiling the material and painting 64 spines takes a moment; none of it belongs
        // on the main thread.
        val result = withContext(Dispatchers.Default) {
            runCatching { ShelfPreparer.prepare(context, room) { text -> phase = text } }
        }
        result.onSuccess { prepared = it }
        result.onFailure { failure = it.message ?: it.javaClass.simpleName }
    }

    // Fade the cover away only once a frame has actually reached the screen, so the scene
    // never announces itself with a black flash.
    val coverAlpha by animateFloatAsState(
        targetValue = if (drawn) 0f else 1f,
        animationSpec = tween(durationMillis = 420),
        label = "shelf-cover"
    )

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ink, Color(0xFF241A13))))) {
        val ready = prepared
        if (capability.supported && room.isNotEmpty() && ready != null && failure == null) {
            FilamentShelf(
                prepared = ready,
                slots = slots,
                modifier = Modifier.fillMaxSize(),
                onStats = { fps, p95, calls, moving ->
                    hud = "$fps fps  ·  p95 ${"%.1f".format(p95)} ms  ·  $calls draw calls" +
                        if (moving) "  ·  moving" else "  ·  idle"
                },
                onOpen = { index -> books.getOrNull(index)?.let(onOpenBook) },
                onLifted = { lifted = it },
                onError = { message -> failure = message },
                onFirstFrame = { drawn = true }
            )
        }

        // Covers the surface until the first frame lands, and stays if the scene fails:
        // a failure has to be readable, not hidden behind a half-drawn shelf.
        if (coverAlpha > 0f || failure != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .alpha(if (failure != null) 1f else coverAlpha)
                    .background(Brush.verticalGradient(listOf(Ink, Color(0xFF241A13))))
            ) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        when {
                            !capability.supported -> "This phone cannot show the 3D shelf"
                            room.isEmpty() -> "Your shelf is empty"
                            failure != null -> "The shelf could not start"
                            else -> phase
                        },
                        color = Parchment,
                        fontFamily = Playfair,
                        fontSize = 21.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        when {
                            !capability.supported -> capability.reason ?: "OpenGL ES 3.0 is required."
                            room.isEmpty() -> "Add a book and it will appear on the shelf."
                            failure != null -> failure ?: ""
                            else -> "One time only \u00b7 cached after this"
                        },
                        color = Color(0xFFC9B79F),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 40.dp)
                    )
                }
            }
        }

        // Caption for the lifted book, and the way back out.
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(18.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Your shelf",
                    color = Parchment,
                    fontFamily = Playfair,
                    fontSize = 24.sp
                )
                LorePillButton("Back", onClick = onBack)
            }
            Spacer(Modifier.weight(1f))
            if (lifted >= 0) {
                val book = books.getOrNull(lifted)
                if (book != null) {
                    Text(
                        book.title,
                        color = Parchment,
                        fontFamily = Playfair,
                        fontSize = 22.sp
                    )
                    Text(
                        book.author,
                        color = Color(0xFFC9B79F),
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tap it again to open it",
                        color = Color(0xFF9C8A74),
                        fontSize = 12.sp
                    )
                }
            } else {
                Text(
                    "Tap a spine to lift it",
                    color = Color(0xFFC9B79F),
                    fontSize = 13.sp
                )
            }
            if (hud.isNotEmpty()) {
                Text(hud, color = Color(0xFF7E6A57), fontSize = 10.sp)
            }
        }
    }
}
