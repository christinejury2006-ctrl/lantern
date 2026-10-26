package com.lantern.library.ui.screens

import android.util.Log
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lantern.library.data.LibraryBook
import com.lantern.library.data.ShelfPicks
import com.lantern.library.shelf3d.SpineAtlas
import com.lantern.library.ui.components.BookLiftOverlay
import com.lantern.library.ui.components.LorePillButton
import com.lantern.library.ui.components.PaintedCarouselShelf
import com.lantern.library.ui.components.spineColorFor
import com.lantern.library.ui.theme.Parchment
import com.lantern.library.ui.theme.Playfair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The reader's shelf: one painted case, its recesses filled with their own books, revolving.
 *
 * The furniture is artwork; the spines are real. Each one carries the middle strip of that book's
 * cover and its title in gilt, painted once into a single atlas, so a whole case is one bitmap and
 * one draw pass.
 *
 * Tapping a book lifts it out — the same hardback lift the shelf has always had — and the case
 * softens behind it. Dragging turns the case, and it keeps turning after a throw.
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
    /** Whether to offer a search field that turns the case to a book. */
    searchable: Boolean = false,
    /** Shows a "+" to add a book, when the caller has somewhere for it to go. */
    onImport: (() -> Unit)? = null,
    /** Long-press on a spine, for removing a book. */
    onLongPressBook: ((LibraryBook) -> Unit)? = null
) {
    val context = LocalContext.current

    var spineAtlas by remember { mutableStateOf<SpineAtlas?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var binding by remember { mutableStateOf(true) }
    var frontIndex by remember { mutableStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // The book taken off the shelf, if any. While one is up, the case behind it softens.
    var lifted by remember { mutableStateOf<LibraryBook?>(null) }
    // The book to slide to the middle, and a counter so asking twice works.
    var focusBook by remember { mutableStateOf<Int?>(null) }
    var focusNonce by remember { mutableStateOf(0) }

    val colours = remember(books) { books.map { book -> spineColorFor(book.title, book.id) } }
    val picks = remember(books) { ShelfPicks.picks(books) }
    val pickedIndices = remember(books, picks) { books.indices.filter { books[it].id in picks }.toSet() }

    // Painting the atlas is the only work worth waiting for, and only on first open: covers are
    // decoded from disk once and every spine is drawn into one bitmap.
    LaunchedEffect(books.size, picks) {
        binding = true
        failure = null
        val built = withContext(Dispatchers.IO) {
            runCatching { SpineAtlas.build(context, books, colours) }
        }
        built.onFailure {
            failure = it.message ?: it.javaClass.simpleName
            Log.e("Shelf", "could not paint the spines", it)
        }
        spineAtlas = built.getOrNull()
        binding = false
    }

    val front = books.getOrNull(frontIndex.coerceIn(0, (books.size - 1).coerceAtLeast(0)))

    /** Slides the first book matching the query to the middle of the case. */
    fun search() {
        if (query.isBlank()) return
        val match = books.indexOfFirst { book ->
            book.title.contains(query, ignoreCase = true) ||
                book.author.contains(query, ignoreCase = true)
        }
        if (match >= 0) {
            // The point of a revolving case: the book comes to the reader, not the reverse.
            focusBook = match
            focusNonce++
            frontIndex = match
            searching = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF2A1B12))) {
        if (books.isNotEmpty() && failure == null) {
            PaintedCarouselShelf(
                books = books,
                atlas = spineAtlas,
                colours = colours,
                onTapBook = { index -> books.getOrNull(index)?.let { lifted = it } },
                onLongPressBook = { index ->
                    books.getOrNull(index)?.let { onLongPressBook?.invoke(it) }
                },
                onFrontBook = { index -> frontIndex = index },
                focusBook = focusBook,
                focusNonce = focusNonce,
                modifier = Modifier.fillMaxSize()
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
                            failure != null -> "The shelf could not open"
                            books.isEmpty() -> "Nothing on it yet"
                            binding -> "Binding the spines\u2026"
                            else -> "${books.size} books \u00b7 drag the case \u00b7 tap a book"
                        },
                        color = Color(0xFF3B2A1C),
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
                    if (onImport != null) LorePillButton("+", onClick = onImport)
                    if (showBack) LorePillButton("Back", onClick = onBack)
                }
            }

            if (searching) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .background(Color(0x33FFFFFF), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    if (query.isEmpty()) {
                        Text("Title or author", color = Color(0x993B2A1C), fontSize = 14.sp)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color(0xFF2A1B12), fontSize = 14.sp),
                        cursorBrush = SolidColor(Color(0xFF2A1B12)),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { search() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // The caption sits on the painted case, over a soft wash so it stays legible.
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC1A1008))))
                    .padding(top = 24.dp, bottom = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        front?.title.orEmpty(),
                        color = Parchment,
                        fontFamily = Playfair,
                        fontSize = 19.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    Text(
                        front?.author.orEmpty(),
                        color = Color(0xFFC9B79F),
                        fontSize = 12.sp,
                        maxLines = 1
                    )
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
            Spacer(Modifier.height(92.dp).navigationBarsPadding())
        }

        // If the spines could not be painted at all, say so rather than showing an empty case.
        val trouble = failure
        if (trouble != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xF22A1B12))
                    .padding(horizontal = 26.dp)
                    .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.CenterStart
            ) {
                Column {
                    Text(
                        "The shelf could not open",
                        color = Parchment,
                        fontFamily = Playfair,
                        fontSize = 24.sp
                    )
                    SelectionContainer {
                        Text(
                            trouble,
                            color = Color(0xFFE8735C),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                }
            }
        }
    }

    // The lifted book, over the softened case.
    val up = lifted
    if (up != null) {
        Box(Modifier.fillMaxSize().background(Color(0x991A1008)))
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
}
