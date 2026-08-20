package com.lantern.library.data

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
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
    private val pendingDeleteFile = File(app.filesDir, "pending_deletes.json")
    private val libraryLock = Any()
    private val pendingDeleteLock = Any()
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
    var driveConsentIntent by mutableStateOf<Intent?>(null)
        private set
    private var driveConsentPrompted = false
    private var googleAccountKey: String? = null

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
        account = CloudAccount(
            prefs.getBoolean("in", false),
            prefs.getString("name", "") ?: "",
            prefs.getString("email", "") ?: "",
            prefs.getString("prov", "") ?: ""
        )
        synchronized(libraryLock) {
            loadBooksUnlocked()
            mergeSeedUnlocked()
        }
        loadWantToRead()
        GoogleAuth.lastAccount(app)?.let { acc ->
            googleAccountKey = GoogleAuth.accountKey(acc)
            applyAccount(acc, announce = false)
        }
        forYou = Recommendations.filterExcluded(
            Recommendations.cached(app).orEmpty(),
            books.toList(),
            wantToRead.toList()
        )
        ensureRecommendations()
        if (account.signedIn && account.provider == "google") {
            viewModelScope.launch(Dispatchers.IO) { connectDrive(migrate = true, quiet = true) }
        }
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
            persistBooksUnlocked()
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
                    persistBooksUnlocked()
                    CommitUserBookResult.ACCEPTED
                }
                isUserBook(book) && userBookCountUnlocked() >= MAX_USER_BOOKS ->
                    CommitUserBookResult.LIBRARY_FULL
                else -> {
                    books.add(0, book)
                    try {
                        persistBooksUnlocked()
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
            persistBooksUnlocked()
            found
        }
        b.filePath?.let { runCatching { File(it).delete() } }
        toast("Removed from library")
        val driveId = b.driveFileId
        if (!driveId.isNullOrBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                val ok = withDrive { token, _ -> DriveLibrary.delete(token, driveId) } != null
                if (!ok) addPendingDelete(driveId)
            }
        }
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
            val incoming = book.copy(pendingUpload = true)
            when (commitNewUserBook(incoming)) {
                CommitUserBookResult.ACCEPTED -> {
                    toast("Added ${book.title}")
                    uploadIfPossible(book.id)
                }
                CommitUserBookResult.LIBRARY_FULL -> {
                    withContext(Dispatchers.IO) { discardOrphan(incoming) }
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
            val incoming = book.copy(pendingUpload = true)
            when (commitNewUserBook(incoming)) {
                CommitUserBookResult.ACCEPTED -> {
                    toast("Saved ${book.title}")
                    then?.invoke(incoming)
                    uploadIfPossible(book.id)
                }
                CommitUserBookResult.LIBRARY_FULL -> {
                    withContext(Dispatchers.IO) { discardOrphan(incoming) }
                    toast("Library is full (200 books)")
                }
                CommitUserBookResult.PERSIST_FAILED -> toast("Download failed")
            }
        }
    }

    fun openForReading(book: LibraryBook, then: (LibraryBook?) -> Unit) {
        viewModelScope.launch {
            val ready = withContext(Dispatchers.IO) { ensureCached(book) }
            if (ready == null) {
                val signedIn = account.signedIn && account.provider == "google"
                toast(
                    when {
                        !book.filePath.isNullOrBlank() && !File(book.filePath).exists() && book.driveFileId.isNullOrBlank() ->
                            "File is missing. Import it again."
                        !signedIn && !book.driveFileId.isNullOrBlank() ->
                            "Sign in to download this book"
                        signedIn && !account.driveConnected && !book.driveFileId.isNullOrBlank() ->
                            "Connect Drive to download this book"
                        else -> "Could not open this book"
                    }
                )
            }
            then(ready)
        }
    }

    fun onGoogleSignedIn(acc: GoogleSignInAccount) {
        val incoming = GoogleAuth.accountKey(acc)
        if (googleAccountKey != null && incoming != null && googleAccountKey != incoming) {
            DriveLibrary.clearCachedFolder()
        }
        googleAccountKey = incoming
        applyAccount(acc, announce = true)
        viewModelScope.launch(Dispatchers.IO) { connectDrive(migrate = true, quiet = false) }
    }

    fun requestDriveConnect() {
        driveConsentPrompted = false
        viewModelScope.launch(Dispatchers.IO) { connectDrive(migrate = true, quiet = false) }
    }

    fun takeDriveConsentIntent(): Intent? {
        val intent = driveConsentIntent
        driveConsentIntent = null
        return intent
    }

    fun onDriveConsentFinished() {
        viewModelScope.launch(Dispatchers.IO) { connectDrive(migrate = true, quiet = false) }
    }

    fun signOut(activity: android.app.Activity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                DriveLibrary.clearCachedFolder()
                GoogleAuth.signOutAndClearToken(activity, getApplication())
            } finally {
                googleAccountKey = null
                withContext(Dispatchers.Main) {
                    driveConsentPrompted = false
                    driveConsentIntent = null
                    account = CloudAccount()
                    prefs.edit().putBoolean("in", false).putString("prov", "").apply()
                    toast("Signed out")
                }
            }
        }
    }

    private fun applyAccount(acc: GoogleSignInAccount, announce: Boolean) {
        val name = acc.displayName.orEmpty().ifBlank { acc.email.orEmpty() }
        val email = acc.email.orEmpty()
        account = CloudAccount(true, name, email, "google", driveConnected = false)
        prefs.edit().putBoolean("in", true).putString("name", name).putString("email", email).putString("prov", "google").apply()
        if (announce) toast("Signed in as $name")
    }

    private fun setDriveConnected(connected: Boolean) {
        val cur = account
        if (!cur.signedIn || cur.provider != "google" || cur.driveConnected == connected) return
        account = cur.copy(driveConnected = connected)
    }

    private fun offerDriveConsent(intent: Intent) {
        if (driveConsentPrompted) return
        driveConsentPrompted = true
        driveConsentIntent = intent
    }

    private suspend fun connectDrive(migrate: Boolean, quiet: Boolean) {
        when (val result = GoogleAuth.driveToken(getApplication())) {
            is DriveTokenResult.Ok -> {
                withContext(Dispatchers.Main) { setDriveConnected(true) }
                retryPendingDeletes()
                if (migrate) migrateLocalToDrive()
            }
            is DriveTokenResult.Recoverable -> {
                withContext(Dispatchers.Main) {
                    setDriveConnected(false)
                    if (driveConsentPrompted) {
                        if (!quiet) toast("Drive backup is not connected")
                    } else {
                        offerDriveConsent(result.intent)
                    }
                }
            }
            DriveTokenResult.Unavailable -> {
                withContext(Dispatchers.Main) {
                    setDriveConnected(false)
                    if (!quiet && account.signedIn) toast("Drive backup is not connected")
                }
            }
        }
    }

    private suspend fun <T> withDrive(op: (String, String) -> DriveOutcome<T>): T? {
        val ctx = getApplication<Application>()
        val owner = GoogleAuth.accountKey(ctx) ?: return null
        suspend fun token(): String? {
            if (GoogleAuth.accountKey(ctx) != owner) return null
            return when (val result = GoogleAuth.driveToken(ctx)) {
                is DriveTokenResult.Ok -> {
                    withContext(Dispatchers.Main) { setDriveConnected(true) }
                    result.token
                }
                is DriveTokenResult.Recoverable -> {
                    withContext(Dispatchers.Main) {
                        setDriveConnected(false)
                        offerDriveConsent(result.intent)
                    }
                    null
                }
                DriveTokenResult.Unavailable -> {
                    withContext(Dispatchers.Main) { setDriveConnected(false) }
                    null
                }
            }
        }
        val first = token() ?: return null
        return when (val out = op(first, owner)) {
            is DriveOutcome.Ok -> out.value
            DriveOutcome.Failed -> null
            DriveOutcome.Unauthorized -> {
                if (GoogleAuth.accountKey(ctx) != owner) return null
                GoogleAuth.clearToken(ctx, first)
                val second = token() ?: return null
                when (val retry = op(second, owner)) {
                    is DriveOutcome.Ok -> retry.value
                    else -> null
                }
            }
        }
    }

    private suspend fun ensureCached(book: LibraryBook): LibraryBook? {
        if (book.origin == BookOrigin.BUNDLED || book.format == BookFormat.TEXT) return book
        val local = book.filePath?.let { File(it) }
        if (local != null && local.exists() && local.length() > 0L) return book
        val driveId = book.driveFileId ?: return null
        val dest = File(BookIo.booksDir(getApplication()), cacheFileName(book))
        val ok = withDrive { token, _ -> DriveLibrary.download(token, driveId, dest) }
        if (ok == null || !dest.exists() || dest.length() <= 0L) return null
        val updated = book.copy(filePath = dest.absolutePath)
        withContext(Dispatchers.Main) { upsert(updated) }
        maybeEvictCache(keepId = book.id)
        return updated
    }

    private suspend fun uploadIfPossible(bookId: String) {
        withContext(Dispatchers.IO) {
            val book = book(bookId) ?: return@withContext
            val file = book.filePath?.let { File(it) } ?: return@withContext
            if (!file.exists()) return@withContext
            val id = withDrive { token, owner ->
                when (val folder = DriveLibrary.ensureFolder(token, owner)) {
                    is DriveOutcome.Ok -> DriveLibrary.upload(token, folder.value, book, file)
                    DriveOutcome.Unauthorized -> DriveOutcome.Unauthorized
                    DriveOutcome.Failed -> DriveOutcome.Failed
                }
            }
            if (id == null) {
                if (account.driveConnected) {
                    withContext(Dispatchers.Main) {
                        toast("Could not back up ${book.title}. It stays on this phone.")
                    }
                }
                return@withContext
            }
            withContext(Dispatchers.Main) {
                val latest = book(bookId) ?: return@withContext
                upsert(latest.copy(driveFileId = id, pendingUpload = false))
            }
        }
    }

    private suspend fun migrateLocalToDrive() {
        val pending = synchronized(libraryLock) {
            books.filter {
                (it.origin == BookOrigin.IMPORT || it.origin == BookOrigin.DOWNLOAD) &&
                    it.driveFileId.isNullOrBlank() &&
                    it.filePath?.let { p -> File(p).exists() } == true
            }
        }
        if (pending.isEmpty()) return
        withContext(Dispatchers.Main) { toast("Backing up ${pending.size} book${if (pending.size == 1) "" else "s"}…") }
        pending.forEach { uploadIfPossible(it.id) }
    }

    private suspend fun retryPendingDeletes() {
        val snapshot = synchronized(pendingDeleteLock) { loadPendingDeletesUnlocked() }
        if (snapshot.isEmpty()) return
        snapshot.forEach { id ->
            val ok = withDrive { token, _ -> DriveLibrary.delete(token, id) } != null
            synchronized(pendingDeleteLock) {
                val cur = loadPendingDeletesUnlocked().toMutableList()
                if (ok) cur.removeAll { it == id }
                else if (id !in cur) cur += id
                savePendingDeletesUnlocked(cur)
            }
        }
    }

    private fun addPendingDelete(driveId: String) {
        synchronized(pendingDeleteLock) {
            val ids = loadPendingDeletesUnlocked().toMutableList()
            if (driveId !in ids) ids += driveId
            savePendingDeletesUnlocked(ids)
        }
    }

    private fun loadPendingDeletesUnlocked(): List<String> {
        if (!pendingDeleteFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(pendingDeleteFile.readText())
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }.getOrDefault(emptyList())
    }

    private fun savePendingDeletesUnlocked(ids: List<String>) {
        if (ids.isEmpty()) {
            runCatching { pendingDeleteFile.delete() }
            return
        }
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        atomicWrite(pendingDeleteFile, arr.toString())
    }

    private suspend fun maybeEvictCache(keepId: String?) {
        val dir = BookIo.booksDir(getApplication())
        val used = dir.listFiles()?.sumOf { it.length() } ?: 0L
        val free = dir.usableSpace
        val needEvict = (used > 512L * 1024 * 1024 && free < 256L * 1024 * 1024) ||
            used > 1536L * 1024 * 1024 ||
            free < 80L * 1024 * 1024
        if (!needEvict) return
        val snapshot = synchronized(libraryLock) { books.toList() }
        val victims = snapshot
            .filter { it.id != keepId }
            .filter { !it.driveFileId.isNullOrBlank() }
            .filter { it.filePath?.let { p -> File(p).exists() } == true }
            .sortedBy { it.lastReadAt }
        val cleared = ArrayList<String>()
        var remainingUsed = used
        for (b in victims) {
            if (remainingUsed < 256L * 1024 * 1024 && free + (used - remainingUsed) > 200L * 1024 * 1024) break
            val f = b.filePath?.let { File(it) } ?: continue
            val size = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) {
                remainingUsed -= size
                cleared += b.id
            }
        }
        if (cleared.isEmpty()) return
        withContext(Dispatchers.Main) {
            cleared.forEach { id ->
                val latest = book(id) ?: return@forEach
                upsert(latest.copy(filePath = null))
            }
        }
    }

    private fun cacheFileName(book: LibraryBook): String {
        val ext = if (book.format == BookFormat.PDF) "pdf" else "epub"
        return "${book.id}.$ext"
    }

    private fun mergeSeedUnlocked() {
        BundledBooks.seed().forEach { s ->
            val i = books.indexOfFirst { it.id == s.id }
            if (i < 0) books.add(s) else books[i] = s.copy(
                currentPage = books[i].currentPage,
                lastReadAt = books[i].lastReadAt,
                finished = books[i].finished,
                addedAt = books[i].addedAt,
                driveFileId = books[i].driveFileId,
                pendingUpload = books[i].pendingUpload,
                filePath = books[i].filePath
            )
        }
        persistBooksUnlocked()
    }

    private fun loadBooksUnlocked() {
        if (!booksFile.exists()) return
        runCatching {
            val arr = JSONArray(booksFile.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val origin = runCatching { BookOrigin.valueOf(o.optString("origin", "BUNDLED")) }.getOrDefault(BookOrigin.BUNDLED)
                if (origin == BookOrigin.BUNDLED) continue
                books += LibraryBook(
                    o.getString("id"), o.getString("title"), o.optString("author"), null, o.optString("remoteCover").ifBlank { null },
                    runCatching { BookFormat.valueOf(o.optString("format", "TEXT")) }.getOrDefault(BookFormat.TEXT), origin,
                    o.optString("filePath").ifBlank { null }, o.optString("remoteEpub").ifBlank { null }, null,
                    o.optInt("pageCount", 1), o.optInt("currentPage", 0), o.optBoolean("finished"), o.optLong("addedAt", System.currentTimeMillis()),
                    o.optLong("lastReadAt"), o.optString("category", "Library"), o.optString("synopsis"), emptyList(),
                    o.optString("driveFileId").ifBlank { null }, o.optBoolean("pendingUpload", false)
                )
            }
        }
    }

    private fun persistBooksUnlocked() {
        val arr = JSONArray()
        books.forEach { b ->
            arr.put(
                JSONObject().put("id", b.id).put("title", b.title).put("author", b.author).put("remoteCover", b.remoteCover ?: "")
                    .put("format", b.format.name).put("origin", b.origin.name).put("filePath", b.filePath ?: "").put("remoteEpub", b.remoteEpub ?: "")
                    .put("pageCount", b.pageCount).put("currentPage", b.currentPage).put("finished", b.finished).put("addedAt", b.addedAt)
                    .put("lastReadAt", b.lastReadAt).put("category", b.category).put("synopsis", b.synopsis)
                    .put("driveFileId", b.driveFileId ?: "").put("pendingUpload", b.pendingUpload)
            )
        }
        atomicWrite(booksFile, arr.toString())
    }

    private fun atomicWrite(file: File, content: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
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
