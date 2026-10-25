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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.lantern.library.data.LibraryBook
import com.lantern.library.data.ShelfPicks
import com.lantern.library.shelf3d.CylinderRotationController
import com.lantern.library.shelf3d.CylinderMath
import com.lantern.library.shelf3d.CylinderShelfSurfaceView
import com.lantern.library.shelf3d.ShelfMaterials
import com.lantern.library.shelf3d.SpineAtlas
import com.lantern.library.ui.components.BookLiftOverlay
import com.lantern.library.ui.components.LorePillButton
import com.lantern.library.ui.components.spineColorFor
import com.lantern.library.ui.theme.Parchment
import com.lantern.library.ui.theme.Playfair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A photorealistic revolving bookcase, drawn with Filament in a SurfaceView.
 *
 * The 3D content is a native surface behind the window; everything in this file that is not the
 * [AndroidView] is ordinary Compose drawn over it, so the HUD stays crisp and the shelf cannot
 * interfere with it.
 *
 * Materials are compiled before the engine is created: filamat takes a moment on its first run
 * (afterwards it reads a cached package), and paying that while a spinner is visible is much
 * better than stalling the first frame.
 */
@Composable
fun CylinderShelfScreen(
    books: List<LibraryBook>,
    onOpenBook: (LibraryBook) -> Unit,
    onBack: () -> Unit,
    /** Heading over the shelf. */
    title: String = "Your shelf",
    /** The preview screen offers a way back; the Library tab has the bottom bar instead. */
    showBack: Boolean = true,
    /** Whether to offer a search field that turns the drum to a book. */
    searchable: Boolean = false,
    /** Shows a "+" to add a book, when the caller has somewhere for it to go. */
    onImport: (() -> Unit)? = null,
    /** Long-press on a spine, for removing a book. */
    onLongPressBook: ((LibraryBook) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // How many tiers of books the case carries follows the phone's length: one row of books
    // would be a band across the middle of any screen, and a revolving case is tall furniture.
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.toFloat()
    val rows = remember(screenHeightDp) { CylinderMath.rowsFor(screenHeightDp) }
    val controller = remember { CylinderRotationController() }

    var materialBytes by remember { mutableStateOf<Map<String, ByteArray>?>(null) }
    var spineAtlas by remember { mutableStateOf<SpineAtlas?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var phase by remember { mutableStateOf("Warming the workshop\u2026") }
    var frontIndex by remember { mutableStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // The book that has been taken off the shelf, if any. While one is up, the case behind it
    // softens: the same lift the shelf always had, now over the 3D scene.
    var lifted by remember { mutableStateOf<LibraryBook?>(null) }

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
        phase = "Binding the spines\u2026"
        // Covers are decoded from disk; a book with none keeps its leather colour.
        spineAtlas = runCatching { SpineAtlas.build(context, books) }.getOrNull()
        result.onSuccess { materialBytes = it }
        result.onFailure { failure = it.message ?: it.javaClass.simpleName }
        phase = "Raising the shelf\u2026"
    }

    val ready = materialBytes
    // Books per tier. The tiers share the drum's slots, so whether a tapped book is the one at
    // the front is a question about its slot, not about its index.
    val perRow = remember(books.size, rows) {
        CylinderMath.perRowFor(books.size, rows).coerceAtLeast(1)
    }
    val front = books.getOrNull(frontIndex.coerceIn(0, (books.size - 1).coerceAtLeast(0)))

    // Books picked for the reader, marked with a brass ribbon on the spine by the scene.
    val picks = remember(books) { ShelfPicks.picks(books) }
    val pickedIndices = remember(books, picks) {
        books.indices.filter { books[it].id in picks }.toSet()
    }

    /** Turns the drum to the first book matching the query. */
    fun search() {
        if (query.isBlank()) return
        val match = books.indexOfFirst { book ->
            book.title.contains(query, ignoreCase = true) ||
                book.author.contains(query, ignoreCase = true)
        }
        if (match >= 0) {
            // The whole point of the 3D shelf: the book comes to the reader, not the reverse.
            controller.revealBook(match)
            frontIndex = match
            searching = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF100B07))) {
        if (ready != null && books.isNotEmpty() && failure == null) {
            AndroidView(
                // Taking a book out blurs everything behind it. Blur is ignored below Android
                // 12, where the lifted book simply sits over a sharp shelf.
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (lifted != null) Modifier.blur(18.dp) else Modifier),
                factory = { ctx ->
                    CylinderShelfSurfaceView(
                        context = ctx,
                        lifecycleOwner = lifecycleOwner,
                        controller = controller,
                        materialBytes = ready,
                        bookCount = books.size,
                        bookColors = colors,
                        spineAtlas = spineAtlas,
                        rows = rows,
                        picked = pickedIndices,
                        onError = { message -> failure = message },
                        onFrontBook = { index -> frontIndex = index },
                        onTapBook = { index ->
                            // First tap turns the case to the book; tapping it at the front
                            // lifts it out, with the shelf softening behind. On any tier: the
                            // front most book is the one at the front slot, whichever row it
                            // stands on.
                            val atFront = index % perRow == frontIndex % perRow
                            if (atFront) {
                                books.getOrNull(index)?.let { lifted = it }
                            } else {
                                controller.revealBook(index)
                                frontIndex = index
                            }
                        },
                        onLongPressBook = { index ->
                            books.getOrNull(index)?.let { onLongPressBook?.invoke(it) }
                        }
                    ).also { it.start() }
                }
            )
        }

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
                Column(Modifier.weight(1f)) {
                    Text(title, color = Parchment, fontFamily = Playfair, fontSize = 30.sp)
                    Text(
                        when {
                            failure != null -> "The shelf could not start"
                            books.isEmpty() -> "Nothing on it yet"
                            else -> "${books.size} books \u00b7 spin the case \u00b7 tap a book"
                        },
                        color = Color(0xFFC9B79F),
                        fontSize = 12.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (searchable) {
                        LorePillButton(
                            if (searching) "Close" else "Find",
                            onClick = {
                                searching = !searching
                                if (!searching) query = ""
                            }
                        )
                    }
                    if (onImport != null) {
                        LorePillButton("+", onClick = onImport)
                    }
                    if (showBack) {
                        LorePillButton("Back", onClick = onBack)
                    }
                }
            }

            if (searching) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .background(Color(0x33FFFFFF), androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    if (query.isEmpty()) {
                        Text("Title or author", color = Color(0x99C9B79F), fontSize = 14.sp)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Parchment, fontSize = 14.sp),
                        cursorBrush = SolidColor(Parchment),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
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
                    val headline = failure ?: front?.title.orEmpty()
                    Text(
                        headline,
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
                        // The ribbon on the spine, said in words. Only for the book at the
                        // front, so the line names the book the reader is looking at.
                        if (front != null && front.id in picks) {
                            Text(
                                "Picked for you",
                                color = Color(0xFFD9A94F),
                                fontSize = 11.sp,
                                maxLines = 1,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
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

    // The lifted book, drawn over the softened shelf.
    val up = lifted
    // Modifier.blur is a Compose render effect and does not reach a SurfaceView's own buffer, and
    // it is unavailable before Android 12 in any case. A scrim is what actually pushes the shelf
    // back behind the lifted book on most phones; the blur is a bonus where it works.
    if (up != null) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
        )
    }
    if (up != null) {
        BookLiftOverlay(
            book = up,
            dark = true,
            onRead = {
                lifted = null
                onOpenBook(up)
            },
            onDismiss = { lifted = null }
        )
    }

    // The SurfaceView's engine is torn down with the composable, in addition to the lifecycle
    // observer it holds: whichever happens first wins, and destroy() is idempotent.
    DisposableEffect(Unit) {
        onDispose { controller.reset() }
    }
}
