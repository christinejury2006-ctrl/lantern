package com.lantern.library.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.lantern.library.data.LibraryBook
import com.lantern.library.shelf3d.CylinderRotationController
import com.lantern.library.shelf3d.CylinderShelfSurfaceView
import com.lantern.library.shelf3d.ShelfMaterials
import com.lantern.library.ui.components.LorePillButton
import com.lantern.library.ui.components.spineColorFor
import com.lantern.library.ui.theme.Parchment
import com.lantern.library.ui.theme.Playfair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A photorealistic revolving bookcase, drawn with Filament in a SurfaceView.
 *
 * The 3D content is a native surface behind the window; everything in this file that is not
 * the [AndroidView] is ordinary Compose drawn over it, so the HUD stays crisp and the shelf
 * cannot interfere with it.
 *
 * Materials are compiled before the engine is created: filamat takes a moment on its first run
 * (afterwards it reads a cached package), and paying that while a spinner is visible is much
 * better than stalling the first frame.
 */
@Composable
fun CylinderShelfScreen(
    books: List<LibraryBook>,
    onOpenBook: (LibraryBook) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { CylinderRotationController() }

    var materialBytes by remember { mutableStateOf<Map<String, ByteArray>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var phase by remember { mutableStateOf("Warming the workshop\u2026") }
    var frontIndex by remember { mutableStateOf(0) }

    val colors = remember(books) {
        books.map { book ->
            // A leather colour derived from the title, so a book looks the same every launch.
            spineColorFor(book.title, book.id)
        }
    }

    LaunchedEffect(books.size) {
        controller.setBookCount(books.size)
        // Compiling the two PBR shaders is the only slow step, and only on first launch.
        val result = withContext(Dispatchers.Default) {
            runCatching { ShelfMaterials.load(context) }
        }
        result.onSuccess { materialBytes = it }
        result.onFailure { failure = it.message ?: it.javaClass.simpleName }
        phase = "Raising the shelf\u2026"
    }

    val ready = materialBytes
    val front = books.getOrNull(frontIndex.coerceIn(0, (books.size - 1).coerceAtLeast(0)))

    Box(Modifier.fillMaxSize().background(Color(0xFF100B07))) {
        if (ready != null && books.isNotEmpty() && failure == null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    CylinderShelfSurfaceView(
                        context = ctx,
                        lifecycleOwner = lifecycleOwner,
                        controller = controller,
                        materialBytes = ready,
                        bookCount = books.size,
                        bookColors = colors,
                        onError = { message -> failure = message },
                        onFrontBook = { index -> frontIndex = index },
                        onTapBook = { index ->
                            // First tap brings the book to the front; tapping it there opens it.
                            if (index == frontIndex) {
                                books.getOrNull(index)?.let(onOpenBook)
                            } else {
                                controller.revealBook(index)
                                frontIndex = index
                            }
                        }
                    ).also { it.start() }
                }
            )
        }

        // HUD: title, the book the shelf is offering, and a way out.
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Your shelf", color = Parchment, fontFamily = Playfair, fontSize = 30.sp)
                    Text(
                        when {
                            failure != null -> "The shelf could not start"
                            books.isEmpty() -> "Nothing on it yet"
                            else -> "Spin the case \u00b7 tap a book to open it"
                        },
                        color = Color(0xFFC9B79F),
                        fontSize = 12.sp
                    )
                }
                LorePillButton("Back", onClick = onBack)
            }

            Spacer(Modifier.weight(1f))

            // A soft scrim so white type stays legible over whatever the 3D scene is doing.
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xCC000000))
                        )
                    )
                    .padding(top = 28.dp, bottom = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val title = failure ?: front?.title.orEmpty()
                    Text(
                        title,
                        color = Parchment,
                        fontFamily = Playfair,
                        fontSize = 19.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    if (failure == null) {
                        Text(
                            front?.author.orEmpty(),
                            color = Color(0xFFC9B79F),
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                }
            }
            Spacer(Modifier.height(96.dp).navigationBarsPadding())
        }

        // Loading cover, only while there is genuinely nothing to show.
        if (ready == null && failure == null && books.isNotEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    phase,
                    color = Color(0xFFC9B79F),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    // The SurfaceView's engine is torn down with the composable, in addition to the lifecycle
    // observer it holds: whichever happens first wins, and destroy() is idempotent.
    DisposableEffect(Unit) {
        onDispose { controller.reset() }
    }
}
