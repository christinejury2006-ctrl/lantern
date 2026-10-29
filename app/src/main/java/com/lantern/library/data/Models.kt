package com.lantern.library.data

import androidx.annotation.DrawableRes
import androidx.compose.ui.text.font.FontFamily
import java.io.File
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

/**
 * The library's sections.
 *
 * The reader thinks in these, not in "current page 43 of 210": a book is something they want to
 * read, are reading, finished, or put down. [ALL] is the shelf itself.
 */
enum class LibraryCategory(val label: String) {
    ALL("All"),
    READING("Reading"),
    WANT_TO_READ("Want to read"),
    COMPLETED("Completed"),
    DNF("Did not finish")
}

enum class BookFormat { TEXT, EPUB, PDF }

enum class BookOrigin { BUNDLED, DOWNLOAD, IMPORT }

/**
 * How safe a book's contents are.
 *
 * Kept explicit rather than inferred at each call site: the reader will eventually delete their
 * own copies of these files, so "somewhere in the cloud" has to be a fact the app can prove, not a
 * hope. [BACKED_UP] is only reachable after a verified upload or a verified download.
 */
enum class BookBackupState {
    NOT_APPLICABLE,
    PENDING,
    BACKED_UP,
    FAILED
}

enum class ReaderTheme(val label: String) { LIGHT("Light Mode"), DARK("Dark Mode") }

data class Chapter(val title: String, val body: String, val href: String = "")

data class TocEntry(
    val title: String,
    val href: String = "",
    val level: Int = 0,
    val chapterIndex: Int = -1
)

data class EpubDocument(
    val chapters: List<Chapter>,
    val toc: List<TocEntry>
)

enum class StudioPhase { Idle, Picking, Extracting, Review, Committing, Failed }

data class StudioSession(
    val phase: StudioPhase = StudioPhase.Idle,
    val format: BookFormat? = null,
    val workingPath: String? = null,
    val coverPath: String? = null,
    val title: String = "",
    val author: String = "",
    val pageCount: Int = 0,
    val toc: List<TocEntry> = emptyList(),
    val chapterTitles: List<String> = emptyList(),
    val tocNote: String? = null,
    val warning: String? = null
)

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
    val pendingUpload: Boolean = false,
    /**
     * The reader has decided not to continue. A book keeps this until they say otherwise or
     * remove it; it is a decision, not a reading position, so progress never clears it.
     */
    val dnf: Boolean = false,
    /**
     * When the remote copy of this book's *contents* was last confirmed, in epoch millis.
     *
     * Zero means "no confirmed backup", and it stays zero until an upload has been verified against
     * Drive's checksum. A book whose metadata is synced is not thereby backed up: the snapshot
     * describes a file that may never have been uploaded, and the reader is planning to delete
     * their own copies on the strength of that claim.
     */
    val backupAt: Long = 0L,
    /** The last attempt to back this book up failed; it stays queued for retry. */
    val backupFailed: Boolean = false
) {
    val localFileAvailable: Boolean
        get() {
            if (origin == BookOrigin.BUNDLED || format == BookFormat.TEXT) return true
            val path = filePath ?: return false
            val file = File(path)
            return file.exists() && file.length() > 0L
        }

    val progress: Float
        get() = when {
            pageCount <= 0 -> 0f
            lastReadAt == 0L && currentPage == 0 -> 0f
            else -> ((currentPage + 1).toFloat() / pageCount.toFloat()).coerceIn(0f, 1f)
        }

    /** What the reader can honestly be told about this book's cloud copy. */
    val backupState: BookBackupState
        get() = when {
            origin == BookOrigin.BUNDLED || format == BookFormat.TEXT -> BookBackupState.NOT_APPLICABLE
            backupAt > 0L && !pendingUpload -> BookBackupState.BACKED_UP
            backupFailed -> BookBackupState.FAILED
            pendingUpload || driveFileId == null -> BookBackupState.PENDING
            // An id without a confirmation is still unverified: it may hold an older file.
            else -> BookBackupState.PENDING
        }

    /**
     * Which section of the library this book belongs to.
     *
     * Named `section` rather than `category` because [LibraryBook.category] already means the
     * book's genre as supplied by its source, and the two would collide.
     *
     * Completed is decided by [finished], which is *latched*: once a reader reaches the end, going
     * back to page three to re-read does not make the book un-completed. Only removing the book, or
     * that being the point of the shelf, takes it out again.
     */
    val section: LibraryCategory
        get() = when {
            dnf -> LibraryCategory.DNF
            finished || progress >= 0.999f -> LibraryCategory.COMPLETED
            lastReadAt > 0L || currentPage > 0 -> LibraryCategory.READING
            else -> LibraryCategory.WANT_TO_READ
        }

    val shelf: Shelf
        get() = when (section) {
            LibraryCategory.COMPLETED -> Shelf.FINISHED
            LibraryCategory.READING -> Shelf.CURRENT
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
    /** Provider-supplied text snippet, only when the API returns one. Never generated. */
    val excerpt: String = "",
    val categories: List<String> = emptyList(),
    val coverUrl: String? = null,
    val coverUrls: List<String> = emptyList(),
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
    val driveConnected: Boolean = false,
    val userId: String = "",
    val lastSyncAt: Long = 0L
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
