package com.lantern.library.data

import androidx.annotation.DrawableRes
import androidx.compose.ui.text.font.FontFamily
import com.lantern.library.ui.theme.Atkinson
import com.lantern.library.ui.theme.Baskerville
import com.lantern.library.ui.theme.Garamond
import com.lantern.library.ui.theme.Inter
import com.lantern.library.ui.theme.Literata
import com.lantern.library.ui.theme.Lora
import com.lantern.library.ui.theme.Merriweather
import com.lantern.library.ui.theme.Nunito
import com.lantern.library.ui.theme.Playfair
import com.lantern.library.ui.theme.SourceSerif

enum class Shelf { ALL, CURRENT, TO_READ, FINISHED }

enum class BookFormat { TEXT, EPUB, PDF }

enum class BookOrigin { BUNDLED, DOWNLOAD, IMPORT }

enum class ReaderTheme(val label: String) { LIGHT("Light Mode"), DARK("Dark Mode") }

data class Chapter(val title: String, val body: String)

data class LibraryBook(
    val id: String,
    val title: String,
    val author: String,
    @DrawableRes val localCover: Int? = null,
    val remoteCover: String? = null,
    val format: BookFormat,
    val origin: BookOrigin,
    val filePath: String? = null,
    val remoteEpub: String? = null,
    val remotePdf: String? = null,
    val pageCount: Int = 1,
    val currentPage: Int = 0,
    val finished: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long = 0L,
    val category: String = "Library",
    val synopsis: String = "",
    val chapters: List<Chapter> = emptyList(),
    val driveFileId: String? = null,
    val pendingUpload: Boolean = false
) {
    val progress: Float
        get() = when {
            pageCount <= 0 -> 0f
            lastReadAt == 0L && currentPage == 0 -> 0f
            else -> ((currentPage + 1).toFloat() / pageCount.toFloat()).coerceIn(0f, 1f)
        }

    val shelf: Shelf
        get() = when {
            finished || progress >= 0.98f -> Shelf.FINISHED
            lastReadAt > 0 || currentPage > 0 -> Shelf.CURRENT
            else -> Shelf.TO_READ
        }
}

data class CatalogBook(
    val remoteId: Int,
    val title: String,
    val author: String,
    val cover: String?,
    val epub: String?,
    val pdf: String?,
    val subjects: String,
    val downloads: Int
)

data class DiscoveryBook(
    val volumeId: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val description: String = "",
    val categories: List<String> = emptyList(),
    val coverUrl: String? = null,
    val publishedDate: String = "",
    val averageRating: Float = 0f,
    val ratingsCount: Int = 0,
    val isbn: String? = null,
    val infoLink: String? = null,
    val previewLink: String? = null,
    val canonicalLink: String? = null,
    val buyLink: String? = null,
    val publicDomain: Boolean = false,
    val savedAt: Long = 0L
) {
    val authorLine: String
        get() = authors.joinToString(", ").ifBlank { "Unknown" }
}

data class Highlight(
    val bookId: String,
    val pageIndex: Int,
    val paragraphIndex: Int,
    val colorArgb: Long
)

data class ReadingPrefs(
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val readerTheme: ReaderTheme = ReaderTheme.LIGHT,
    val fontId: String = "times",
    val fontSizeSp: Float = 17f,
    val brightness: Float = 0.85f,
    val swipeMode: Boolean = true,
    val landscape: Boolean = false,
    val highlightColor: Long = 0xFFFFF59D,
    val useMobileData: Boolean = true
)

data class CloudAccount(
    val signedIn: Boolean = false,
    val displayName: String = "",
    val email: String = "",
    val provider: String = "",
    val driveConnected: Boolean = false
)

data class Notice(
    val id: String,
    val title: String,
    val body: String,
    val time: String,
    val unread: Boolean = true
)

data class FontOption(val id: String, val label: String, val family: FontFamily)

object LanternFonts {
    val options = listOf(
        FontOption("playfair", "Playfair Display", Playfair),
        FontOption("lora", "Lora", Lora),
        FontOption("merriweather", "Merriweather", Merriweather),
        FontOption("garamond", "EB Garamond", Garamond),
        FontOption("literata", "Literata", Literata),
        FontOption("sourceserif", "Source Serif", SourceSerif),
        FontOption("baskerville", "Libre Baskerville", Baskerville),
        FontOption("inter", "Inter", Inter),
        FontOption("nunito", "Nunito", Nunito),
        FontOption("atkinson", "Atkinson Hyperlegible", Atkinson)
    )

    fun family(id: String): FontFamily =
        options.firstOrNull { it.id == id }?.family ?: Lora
}

object HighlightSwatches {
    val colors = listOf(
        0xFFFFF59D, 0xFFFFCC80, 0xFFEF9A9A, 0xFFF8BBD0,
        0xFFCE93D8, 0xFF90CAF9, 0xFF80DEEA, 0xFFA5D6A7, 0xFFB0BEC5
    )
}

data class ReaderPage(
    val chapterTitle: String,
    val chapterIndex: Int,
    val paragraphs: List<String>,
    val showChapterTitle: Boolean
)

object Paginator {
    fun pages(chapters: List<Chapter>, fontSizeSp: Float, fallback: String): List<ReaderPage> {
        val budget = when {
            fontSizeSp <= 15f -> 1100
            fontSizeSp <= 17f -> 900
            fontSizeSp <= 20f -> 720
            else -> 560
        }
        val src = chapters.ifEmpty { listOf(Chapter("Book", fallback)) }
        val out = mutableListOf<ReaderPage>()
        src.forEachIndexed { chapterIndex, chapter ->
            val paragraphs = chapter.body.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
            var bucket = mutableListOf<String>()
            var used = 0
            var first = true
            fun flush() {
                if (bucket.isEmpty()) return
                out += ReaderPage(chapter.title, chapterIndex, bucket.toList(), first)
                first = false
                bucket = mutableListOf()
                used = 0
            }
            paragraphs.forEach { para ->
                if (used > 0 && used + para.length > budget) flush()
                bucket += para
                used += para.length + 24
                if (used >= budget) flush()
            }
            flush()
        }
        if (out.isEmpty()) out += ReaderPage("Book", 0, listOf(fallback), true)
        return out
    }
}
