package com.lantern.library.data

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LanternStore(app: Application) : AndroidViewModel(app) {
    companion object {
        const val MAX_USER_BOOKS = 200
    }

    private val prefs = app.getSharedPreferences("lantern", Context.MODE_PRIVATE)
    private val booksFile = File(app.filesDir, "library.json")
    private val wantFile = File(app.filesDir, "want_to_read.json")
    private val libraryLock = Any()
    val books = mutableStateListOf<LibraryBook>()
    val wantToRead = mutableStateListOf<DiscoveryBook>()
    var forYou by mutableStateOf<List<DiscoveryBook>>(emptyList())
        private set
    var readingPrefs by mutableStateOf(ReadingPrefs())
        private set
    var account by mutableStateOf(CloudAccount())
        private set
    var toast by mutableStateOf<String?>(null)
        private set

    init {
        val themeName = prefs.getString("theme", "LIGHT") ?: "LIGHT"
        readingPrefs = ReadingPrefs(
            theme = if (themeName == "DARK") ReaderTheme.DARK else ReaderTheme.LIGHT,
            fontId = prefs.getString("fontId", "times") ?: "times",
            fontSizeSp = prefs.getFloat("fontSize", 17f),
            brightness = prefs.getFloat("brightness", 0.85f),
            swipeMode = prefs.getBoolean("swipe", true),
            landscape = prefs.getBoolean("landscape", false),
            useMobileData = prefs.getBoolean("mobile", true)
        )
        account = CloudAccount(prefs.getBoolean("in", false), prefs.getString("name", "") ?: "", prefs.getString("email", "") ?: "", prefs.getString("prov", "") ?: "")
        synchronized(libraryLock) {
            loadBooks()
            mergeSeed()
        }
        loadWantToRead()
        forYou = Recommendations.filterExcluded(
            Recommendations.cached(app).orEmpty(),
            books.toList(),
            wantToRead.toList()
        )
        ensureRecommendations()
    }

    fun userBookCount(): Int = synchronized(libraryLock) { userBookCountUnlocked() }

    fun libraryFull(): Boolean = userBookCount() >= MAX_USER_BOOKS

    private fun userBookCountUnlocked(): Int =
        books.count { it.origin == BookOrigin.IMPORT || it.origin == BookOrigin.DOWNLOAD }

    private fun isUserBook(book: LibraryBook): Boolean =
        book.origin == BookOrigin.IMPORT || book.origin == BookOrigin.DOWNLOAD

    fun setPrefs(next: ReadingPrefs) {
        val clean = if (next.theme == ReaderTheme.DARK) next else next.copy(theme = ReaderTheme.LIGHT)
        readingPrefs = clean
        prefs.edit().putString("theme", clean.theme.name).putString("fontId", clean.fontId)
            .putFloat("fontSize", clean.fontSizeSp).putFloat("brightness", clean.brightness)
            .putBoolean("swipe", clean.swipeMode).putBoolean("landscape", clean.landscape)
            .putBoolean("mobile", clean.useMobileData).apply()
    }
    fun toast(msg: String) { viewModelScope.launch { toast = msg; delay(5000); if (toast == msg) toast = null } }
    fun book(id: String) = synchronized(libraryLock) { books.firstOrNull { it.id == id } }

    fun upsert(book: LibraryBook) {
        synchronized(libraryLock) {
            val i = books.indexOfFirst { it.id == book.id }
            if (i >= 0) {
                books[i] = book
            } else if (isUserBook(book) && userBookCountUnlocked() >= MAX_USER_BOOKS) {
                return
            } else {
                books.add(0, book)
            }
            persistBooks()
        }
        dropFromRecommendations { Recommendations.inLibrary(it, listOf(book)) }
    }

    private enum class CommitUserBookResult { ACCEPTED, LIBRARY_FULL, PERSIST_FAILED }

    private fun commitNewUserBook(book: LibraryBook): CommitUserBookResult {
        var discardCopiedFile = false
        val result = synchronized(libraryLock) {
            val i = books.indexOfFirst { it.id == book.id }
            when {
                i >= 0 -> {
                    books[i] = book
                    persistBooks()
                    CommitUserBookResult.ACCEPTED
                }
                isUserBook(book) && userBookCountUnlocked() >= MAX_USER_BOOKS ->
                    CommitUserBookResult.LIBRARY_FULL
                else -> {
                    books.add(0, book)
                    try {
                        persistBooks()
                        CommitUserBookResult.ACCEPTED
                    } catch (_: Exception) {
                        books.removeAll { it.id == book.id }
                        discardCopiedFile = true
                        CommitUserBookResult.PERSIST_FAILED
                    }
                }
            }
        }
        if (discardCopiedFile) discardOrphan(book)
        if (result == CommitUserBookResult.ACCEPTED) {
            dropFromRecommendations { Recommendations.inLibrary(it, listOf(book)) }
        }
        return result
    }

    private fun discardOrphan(book: LibraryBook) {
        book.filePath?.let { runCatching { File(it).delete() } }
        book.remoteCover?.let { path ->
            if (!path.startsWith("http")) runCatching { File(path).delete() }
        }
    }

    fun remove(id: String) {
        val b = synchronized(libraryLock) {
            val found = books.firstOrNull { it.id == id } ?: return
            if (found.origin == BookOrigin.BUNDLED) return
            books.removeAll { it.id == id }
            persistBooks()
            found
        }
        b.filePath?.let { runCatching { File(it).delete() } }
        toast("Removed from library")
    }
    fun markRead(id: String, page: Int, pages: Int) {
        val b = book(id) ?: return
        upsert(b.copy(currentPage = page.coerceAtLeast(0), pageCount = pages.coerceAtLeast(1), lastReadAt = System.currentTimeMillis(), finished = pages > 0 && page >= pages - 1))
    }
    fun addBookmark(bookId: String, page: Int) { toast("Bookmark saved · page ${page + 1}") }
    fun isWantToRead(book: DiscoveryBook) = wantToRead.any { Recommendations.sameWork(it, book) }
    fun addWantToRead(book: DiscoveryBook) {
        if (wantToRead.any { Recommendations.sameWork(it, book) }) return
        wantToRead.add(0, book.copy(savedAt = System.currentTimeMillis()))
        persistWantToRead()
        dropFromRecommendations { Recommendations.sameWork(it, book) }
        toast("Saved to Want to Read")
    }
    fun removeWantToRead(book: DiscoveryBook) {
        val removed = wantToRead.removeAll { Recommendations.sameWork(it, book) }
        if (!removed) return
        persistWantToRead()
        toast("Removed from Want to Read")
    }
    fun ensureRecommendations() {
        viewModelScope.launch {
            val list = Recommendations.daily(
                getApplication(),
                { books.toList() },
                { wantToRead.toList() }
            )
            forYou = Recommendations.filterExcluded(list, books.toList(), wantToRead.toList())
        }
    }
    fun importUri(uri: Uri) {
        viewModelScope.launch {
            val book = withContext(Dispatchers.IO) { runCatching { BookIo.importUri(getApplication(), uri) }.getOrNull() }
            if (book == null) {
                toast("Could not import that file")
                return@launch
            }
            when (commitNewUserBook(book)) {
                CommitUserBookResult.ACCEPTED -> toast("Added ${book.title}")
                CommitUserBookResult.LIBRARY_FULL -> {
                    withContext(Dispatchers.IO) { discardOrphan(book) }
                    toast("Library is full (200 books)")
                }
                CommitUserBookResult.PERSIST_FAILED -> toast("Could not import that file")
            }
        }
    }
    fun download(remote: CatalogBook, then: ((LibraryBook) -> Unit)? = null) {
        viewModelScope.launch {
            val existing = synchronized(libraryLock) { books.firstOrNull { it.id == "pg_${remote.remoteId}" } }
            if (existing != null) { then?.invoke(existing); return@launch }
            toast("Downloading ${remote.title}…")
            val book = withContext(Dispatchers.IO) { runCatching { BookIo.downloadCatalog(getApplication(), remote, false) }.getOrNull() }
            if (book == null) {
                toast("Download failed")
                return@launch
            }
            when (commitNewUserBook(book)) {
                CommitUserBookResult.ACCEPTED -> {
                    toast("Saved ${book.title}")
                    then?.invoke(book)
                }
                CommitUserBookResult.LIBRARY_FULL -> {
                    withContext(Dispatchers.IO) { discardOrphan(book) }
                    toast("Library is full (200 books)")
                }
                CommitUserBookResult.PERSIST_FAILED -> toast("Download failed")
            }
        }
    }
    fun signIn(name: String, email: String) { account = CloudAccount(true, name, email, "email"); prefs.edit().putBoolean("in", true).putString("name", name).putString("email", email).apply(); toast("Signed in as $name") }
    fun signOut() { account = CloudAccount(); prefs.edit().putBoolean("in", false).apply(); toast("Signed out") }
    private fun mergeSeed() {
        BundledBooks.seed().forEach { s ->
            val i = books.indexOfFirst { it.id == s.id }
            if (i < 0) books.add(s) else books[i] = s.copy(currentPage = books[i].currentPage, lastReadAt = books[i].lastReadAt, finished = books[i].finished, addedAt = books[i].addedAt)
        }
        persistBooks()
    }
    private fun loadBooks() {
        if (!booksFile.exists()) return
        runCatching {
            val arr = JSONArray(booksFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val origin = runCatching { BookOrigin.valueOf(o.optString("origin", "BUNDLED")) }.getOrDefault(BookOrigin.BUNDLED)
                if (origin == BookOrigin.BUNDLED) continue
                books += LibraryBook(o.getString("id"), o.getString("title"), o.optString("author"), null, o.optString("remoteCover").ifBlank { null },
                    runCatching { BookFormat.valueOf(o.optString("format", "TEXT")) }.getOrDefault(BookFormat.TEXT), origin,
                    o.optString("filePath").ifBlank { null }, o.optString("remoteEpub").ifBlank { null }, null,
                    o.optInt("pageCount", 1), o.optInt("currentPage", 0), o.optBoolean("finished"), o.optLong("addedAt", System.currentTimeMillis()),
                    o.optLong("lastReadAt"), o.optString("category", "Library"), o.optString("synopsis"))
            }
        }
    }
    private fun persistBooks() {
        val arr = JSONArray()
        books.forEach { b ->
            arr.put(JSONObject().put("id", b.id).put("title", b.title).put("author", b.author).put("remoteCover", b.remoteCover ?: "")
                .put("format", b.format.name).put("origin", b.origin.name).put("filePath", b.filePath ?: "").put("remoteEpub", b.remoteEpub ?: "")
                .put("pageCount", b.pageCount).put("currentPage", b.currentPage).put("finished", b.finished).put("addedAt", b.addedAt)
                .put("lastReadAt", b.lastReadAt).put("category", b.category).put("synopsis", b.synopsis))
        }
        booksFile.writeText(arr.toString())
    }
    private fun loadWantToRead() {
        if (!wantFile.exists()) return
        runCatching {
            val arr = JSONArray(wantFile.readText())
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                val incoming = Recommendations.parseBook(row) ?: continue
                if (wantToRead.none { Recommendations.sameWork(it, incoming) }) wantToRead += incoming
            }
        }
    }
    private fun dropFromRecommendations(drop: (DiscoveryBook) -> Boolean) {
        if (forYou.any(drop)) forYou = forYou.filterNot(drop)
        Recommendations.excludeFromCache(getApplication(), drop)
    }
    private fun persistWantToRead() {
        val arr = JSONArray()
        wantToRead.forEach { arr.put(Recommendations.toJson(it)) }
        runCatching { wantFile.writeText(arr.toString()) }
    }
}
