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
        private const val STUDIO_PREVIEW_ID = "studio_preview"
    }

    private val prefs = app.getSharedPreferences("lantern", Context.MODE_PRIVATE)
    private val booksFile = File(app.filesDir, "library.json")
    private val wantFile = File(app.filesDir, "want_to_read.json")
    private val pendingDeleteFile = File(app.filesDir, "pending_deletes.json")
    private val libraryAppearFile = File(app.filesDir, "library_appearance.json")
    private val readerAppearFile = File(app.filesDir, "reader_appearance.json")
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
    var forYouBusy by mutableStateOf(false)
        private set
    var forYouFailed by mutableStateOf(false)
        private set
    var interestsChosen by mutableStateOf(false)
        private set
    var interests by mutableStateOf<List<String>>(emptyList())
        private set
    var editingInterests by mutableStateOf(false)
    var hydrated by mutableStateOf(false)
        private set
    var libraryAppearance by mutableStateOf(LibraryAppearance())
        private set
    var readerAppearance by mutableStateOf(ReaderAppearance())
        private set
    var studio by mutableStateOf<StudioSession?>(null)
        private set
    private var studioGen = 0
    var web by mutableStateOf<com.lantern.library.studio.WebDraft?>(null)
        private set
    private var webGen = 0
    private var previewBook: LibraryBook? = null
    private var driveConsentPrompted = false
    private var googleAccountKey: String? = null

    private data class DiskHydrate(
        val readingPrefs: ReadingPrefs,
        val account: CloudAccount,
        val googleAcc: GoogleSignInAccount?,
        val books: List<LibraryBook>,
        val want: List<DiscoveryBook>,
        val interestsChosen: Boolean,
        val interests: List<String>,
        val forYouCached: List<DiscoveryBook>,
        val libraryAppearance: LibraryAppearance,
        val readerAppearance: ReaderAppearance
    )

    init {
        StartupTrace.mark("LanternStore.init scheduled")
        viewModelScope.launch {
            StartupTrace.mark("LanternStore hydrate start")
            val disk = withContext(Dispatchers.IO) {
                StartupTrace.mark("LanternStore hydrate IO")
                hydrateFromDisk()
            }
            readingPrefs = disk.readingPrefs
            val acc = disk.googleAcc
            if (acc != null) {
                googleAccountKey = GoogleAuth.accountKey(acc)
                applyAccount(acc, announce = false)
            } else {
                account = disk.account
            }
            synchronized(libraryLock) {
                books.clear()
                books.addAll(disk.books)
            }
            wantToRead.clear()
            wantToRead.addAll(disk.want)
            interestsChosen = disk.interestsChosen
            interests = disk.interests
            libraryAppearance = disk.libraryAppearance
            readerAppearance = disk.readerAppearance
            StartupTrace.mark("LanternStore prefs interestsChosen=$interestsChosen n=${interests.size}")
            if (interestsChosen) {
                forYou = disk.forYouCached
                StartupTrace.mark("LanternStore cached forYou=${forYou.size}")
            }
            hydrated = true
            StartupTrace.mark("LanternStore hydrated")
            if (interestsChosen) ensureRecommendations()
            if (account.signedIn && account.provider == "google") {
                viewModelScope.launch(Dispatchers.IO) { connectDrive(migrate = true, quiet = true) }
            }
        }
    }

    private fun hydrateFromDisk(): DiskHydrate {
        val app = getApplication<Application>()
        val themeName = prefs.getString("theme", "LIGHT") ?: "LIGHT"
        val appTheme = if (themeName == "DARK") ReaderTheme.DARK else ReaderTheme.LIGHT
        val readerThemeName = prefs.getString("readerTheme", null)
        val loadedPrefs = ReadingPrefs(
            theme = appTheme,
            readerTheme = if (readerThemeName == "DARK") ReaderTheme.DARK
                else if (readerThemeName == "LIGHT") ReaderTheme.LIGHT
                else appTheme,
            fontId = prefs.getString("fontId", "times") ?: "times",
            fontSizeSp = prefs.getFloat("fontSize", 17f),
            brightness = prefs.getFloat("brightness", 0.85f),
            swipeMode = prefs.getBoolean("swipe", true),
            landscape = prefs.getBoolean("landscape", false),
            useMobileData = prefs.getBoolean("mobile", true)
        )
        val accountFromPrefs = CloudAccount(
            prefs.getBoolean("in", false),
            prefs.getString("name", "") ?: "",
            prefs.getString("email", "") ?: "",
            prefs.getString("prov", "") ?: ""
        )
        val loadedBooks = ArrayList<LibraryBook>()
        synchronized(libraryLock) {
            loadBooksInto(loadedBooks)
            mergeSeedInto(loadedBooks)
            persistBooksList(loadedBooks)
        }
        val want = readWantFile()
        val googleAcc = runCatching { GoogleAuth.lastAccount(app) }.getOrNull()
        val chosen = prefs.getBoolean("interests_chosen", false)
        val chosenInterests = prefs.getString("interests", "")
            .orEmpty()
            .split("|")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val cached = if (chosen) Recommendations.cached(app, chosenInterests).orEmpty() else emptyList()
        val libAppear = LibraryAppearance(
            appearLookFromJson(
                runCatching { if (libraryAppearFile.exists()) libraryAppearFile.readText() else null }.getOrNull(),
                AppearLook.libraryDefault
            )
        )
        val readAppear = ReaderAppearance(
            appearLookFromJson(
                runCatching { if (readerAppearFile.exists()) readerAppearFile.readText() else null }.getOrNull(),
                AppearLook.readerDefault
            )
        )
        return DiskHydrate(
            loadedPrefs, accountFromPrefs, googleAcc, loadedBooks, want,
            chosen, chosenInterests, cached, libAppear, readAppear
        )
    }

    fun userBookCount(): Int = synchronized(libraryLock) { userBookCountUnlocked() }

    fun libraryFull(): Boolean = userBookCount() >= MAX_USER_BOOKS

    private fun userBookCountUnlocked(): Int =
        books.count { it.origin == BookOrigin.IMPORT || it.origin == BookOrigin.DOWNLOAD }

    private fun isUserBook(book: LibraryBook): Boolean =
        book.origin == BookOrigin.IMPORT || book.origin == BookOrigin.DOWNLOAD

    fun setPrefs(next: ReadingPrefs) {
        val clean = next.copy(
            theme = if (next.theme == ReaderTheme.DARK) ReaderTheme.DARK else ReaderTheme.LIGHT,
            readerTheme = if (next.readerTheme == ReaderTheme.DARK) ReaderTheme.DARK else ReaderTheme.LIGHT
        )
        readingPrefs = clean
        prefs.edit().putString("theme", clean.theme.name).putString("readerTheme", clean.readerTheme.name)
            .putString("fontId", clean.fontId)
            .putFloat("fontSize", clean.fontSizeSp).putFloat("brightness", clean.brightness)
            .putBoolean("swipe", clean.swipeMode).putBoolean("landscape", clean.landscape)
            .putBoolean("mobile", clean.useMobileData).apply()
    }

    fun saveLibraryAppearance(next: LibraryAppearance) {
        libraryAppearance = next
        persistLook(libraryAppearFile, next.look)
        prefetchWallpaper(next.look)
    }

    fun saveReaderAppearance(next: ReaderAppearance) {
        readerAppearance = next
        persistLook(readerAppearFile, next.look)
        prefetchWallpaper(next.look)
    }

    private fun persistLook(file: File, look: AppearLook) {
        runCatching { atomicWrite(file, look.toJson().toString()) }
    }

    private fun prefetchWallpaper(look: AppearLook) {
        if (!look.hasWallpaper) return
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            WallpaperStore.load(app, look.wallpaperId, look.wallpaperUrl)
        }
    }

    fun toast(msg: String) { viewModelScope.launch { toast = msg; delay(5000); if (toast == msg) toast = null } }
    fun book(id: String): LibraryBook? {
        if (id == STUDIO_PREVIEW_ID) return previewBook
        return synchronized(libraryLock) { books.firstOrNull { it.id == id } }
    }

    fun upsert(book: LibraryBook) {
        if (book.id == STUDIO_PREVIEW_ID) {
            previewBook = book
            return
        }
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
        if (id == STUDIO_PREVIEW_ID) {
            val b = previewBook ?: return
            previewBook = b.copy(
                currentPage = page.coerceAtLeast(0),
                pageCount = pages.coerceAtLeast(1),
                lastReadAt = System.currentTimeMillis(),
                finished = pages > 0 && page >= pages - 1
            )
            return
        }
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
    fun saveInterests(ids: List<String>) {
        val clean = ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (clean.isEmpty()) return
        interests = clean
        interestsChosen = true
        editingInterests = false
        prefs.edit()
            .putBoolean("interests_chosen", true)
            .putString("interests", clean.joinToString("|"))
            .apply()
        StartupTrace.mark("saveInterests n=${clean.size} ids=${clean.joinToString(",")}")
        Recommendations.clearCache(getApplication())
        forYou = emptyList()
        forYouFailed = false
        ensureRecommendations(force = true)
    }

    fun openInterestEditor() {
        if (interestsChosen) editingInterests = true
    }

    fun closeInterestEditor() {
        editingInterests = false
    }

    fun refreshForYou() {
        ensureRecommendations(force = true)
    }

    fun ensureRecommendations(force: Boolean = false) {
        StartupTrace.mark("ensureRecommendations force=$force chosen=$interestsChosen busy=$forYouBusy")
        if (!interestsChosen || forYouBusy) {
            StartupTrace.mark("ensureRecommendations SKIP chosen=$interestsChosen busy=$forYouBusy")
            return
        }
        viewModelScope.launch {
            StartupTrace.mark("ensureRecommendations coroutine start")
            forYouBusy = true
            forYouFailed = false
            try {
                val list = Recommendations.daily(getApplication(), interests, force)
                forYou = list
                RecDiag.storeCount = forYou.size
                RecDiag.log(RecDiag.summary())
                StartupTrace.mark("ensureRecommendations done n=${list.size} failed=${list.isEmpty()}")
                if (list.isEmpty()) forYouFailed = true
                if (com.lantern.library.BuildConfig.DEBUG) toast(RecDiag.summary())
            } finally {
                forYouBusy = false
            }
        }
    }
    fun importUri(uri: Uri) {
        viewModelScope.launch {
            val book = withContext(Dispatchers.IO) { runCatching { BookIo.importUri(getApplication(), uri) }.getOrNull() }
            if (book == null) {
                toast("Could not add that file")
                return@launch
            }
            val incoming = book.copy(pendingUpload = true)
            when (commitNewUserBook(incoming)) {
                CommitUserBookResult.ACCEPTED -> {
                    toast("Added to Library")
                    uploadIfPossible(book.id)
                }
                CommitUserBookResult.LIBRARY_FULL -> {
                    withContext(Dispatchers.IO) { discardOrphan(incoming) }
                    toast("Library is full (200 books)")
                }
                CommitUserBookResult.PERSIST_FAILED -> toast("Could not add that file")
            }
        }
    }

    fun studioBeginPick() {
        studio = StudioSession(phase = StudioPhase.Picking)
    }

    fun studioCancelPick() {
        if (studio?.phase == StudioPhase.Picking) studio = null
    }

    fun studioOpen(uri: Uri) {
        val gen = ++studioGen
        viewModelScope.launch {
            val previous = studio
            studio = StudioSession(phase = StudioPhase.Extracting)
            withContext(Dispatchers.IO) {
                wipeStudioSession(previous)
                BookIo.clearStudioDir(getApplication())
            }
            val copy = withContext(Dispatchers.IO) {
                runCatching { BookIo.copyToStudio(getApplication(), uri) }.getOrNull()
            }
            if (gen != studioGen) {
                copy?.file?.let { runCatching { it.delete() } }
                return@launch
            }
            if (copy == null) {
                studio = StudioSession(phase = StudioPhase.Failed, warning = "Could not add that file.")
                return@launch
            }
            val inspected = withContext(Dispatchers.IO) {
                runCatching {
                    BookIo.inspectStudio(getApplication(), copy.file, copy.format, copy.displayName)
                }.getOrNull()
            }
            if (gen != studioGen) {
                runCatching { copy.file.delete() }
                return@launch
            }
            studio = inspected ?: StudioSession(
                phase = StudioPhase.Failed,
                format = copy.format,
                workingPath = copy.file.absolutePath,
                title = BookIo.cleanImportTitle(copy.displayName).let { t ->
                    if (t.equals("Imported book", true) || t.isBlank()) "Untitled" else t
                },
                author = "",
                pageCount = 1,
                tocNote = if (copy.format == BookFormat.PDF) {
                    "This PDF has no table of contents."
                } else {
                    "No table of contents found."
                },
                warning = "Some details could not be read."
            )
        }
    }

    fun studioCancel() {
        studioGen++
        val current = studio
        studio = null
        viewModelScope.launch(Dispatchers.IO) { wipeStudioSession(current) }
    }

    fun studioSend(title: String, author: String) {
        val current = studio ?: return
        val working = current.workingPath?.let { File(it) } ?: return
        if (!working.exists()) {
            studio = current.copy(phase = StudioPhase.Failed, warning = "The working file is gone.")
            return
        }
        val format = current.format ?: return
        viewModelScope.launch {
            studio = current.copy(phase = StudioPhase.Committing)
            if (libraryFull()) {
                toast("Library is full (200 books)")
                studio = current.copy(phase = StudioPhase.Review)
                return@launch
            }
            val app = getApplication<Application>()
            val id = "imp_${System.currentTimeMillis()}_${working.name.hashCode().toUInt()}"
            val ext = if (format == BookFormat.EPUB) "epub" else "pdf"
            val dest = File(BookIo.booksDir(app), "$id.$ext")
            val coverDest = File(BookIo.coversDir(app), "$id.jpg")
            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    working.copyTo(dest, overwrite = true)
                    val srcCover = current.coverPath?.let { File(it) }
                    val coverOk = srcCover != null && srcCover.exists() && srcCover.length() > 0L &&
                        runCatching {
                            srcCover.copyTo(coverDest, overwrite = true)
                            true
                        }.getOrDefault(false)
                    val fileOk = dest.exists() && dest.length() > 0L
                    Pair(fileOk, coverOk)
                }.getOrDefault(Pair(false, false))
            }
            if (!copied.first) {
                withContext(Dispatchers.IO) {
                    runCatching { dest.delete() }
                    runCatching { coverDest.delete() }
                }
                studio = current.copy(phase = StudioPhase.Failed, warning = "Could not add that file.")
                return@launch
            }
            val incoming = LibraryBook(
                id = id,
                title = title.trim().ifBlank { "Untitled" },
                author = author.trim(),
                remoteCover = if (copied.second) coverDest.absolutePath else null,
                format = format,
                origin = BookOrigin.IMPORT,
                filePath = dest.absolutePath,
                pageCount = current.pageCount.coerceAtLeast(1),
                pendingUpload = true
            )
            when (commitNewUserBook(incoming)) {
                CommitUserBookResult.ACCEPTED -> {
                    toast("Added to Library")
                    studioGen++
                    studio = null
                    withContext(Dispatchers.IO) { wipeStudioSession(current) }
                    uploadIfPossible(id)
                }
                CommitUserBookResult.LIBRARY_FULL -> {
                    withContext(Dispatchers.IO) { discardOrphan(incoming) }
                    toast("Library is full (200 books)")
                    studio = current.copy(phase = StudioPhase.Review)
                }
                CommitUserBookResult.PERSIST_FAILED -> {
                    withContext(Dispatchers.IO) { discardOrphan(incoming) }
                    studio = current.copy(phase = StudioPhase.Failed, warning = "Could not add that file.")
                }
            }
        }
    }

    fun analyzeWeb(url: String) {
        val gen = ++webGen
        viewModelScope.launch {
            web = com.lantern.library.studio.WebDraft(
                phase = com.lantern.library.studio.WebPhase.Fetching,
                url = url.trim(),
                progress = "Reading first page…"
            )
            val cache = getApplication<Application>().cacheDir
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    com.lantern.library.studio.BookCrawler.crawl(
                        url,
                        cache,
                        cancelled = { gen != webGen }
                    ) { msg ->
                        viewModelScope.launch {
                            if (gen == webGen) web = web?.copy(progress = msg)
                        }
                    }
                }
            }
            if (gen != webGen) return@launch
            web = result.getOrElse { e ->
                com.lantern.library.studio.WebDraft(
                    phase = com.lantern.library.studio.WebPhase.Failed,
                    url = url.trim(),
                    error = e.message?.takeIf { it.isNotBlank() } ?: "Could not analyze that page."
                )
            }
        }
    }

    fun webKeep(id: String) = webOverride(id, com.lantern.library.studio.WebVerdict.Keep)

    fun webRemove(id: String) = webOverride(id, com.lantern.library.studio.WebVerdict.Drop)

    fun webOpenChapter(id: String) {
        val cur = web ?: return
        if (cur.chapters.any { it.id == id }) web = cur.copy(openChapterId = id)
    }

    fun webIgnorePossible(id: String) {
        val cur = web ?: return
        web = cur.copy(possible = cur.possible.filterNot { it.id == id })
    }

    fun webFollowPossible(id: String) {
        val cur = web ?: return
        val guess = cur.possible.firstOrNull { it.id == id } ?: return
        val gen = webGen
        viewModelScope.launch {
            web = cur.copy(progress = "Reading possible chapter…", possible = cur.possible.filterNot { it.id == id })
            val chapter = withContext(Dispatchers.IO) {
                runCatching {
                    com.lantern.library.studio.BookCrawler.fetchOne(
                        guess.url,
                        getApplication<Application>().cacheDir,
                        cur.chapters.size
                    )
                }.getOrNull()
            }
            if (gen != webGen) return@launch
            val now = web ?: return@launch
            web = if (chapter == null) now.copy(progress = "")
            else now.copy(
                progress = "",
                chapters = now.chapters + chapter,
                openChapterId = chapter.id
            )
        }
    }

    private fun webOverride(id: String, verdict: com.lantern.library.studio.WebVerdict) {
        val cur = web ?: return
        val openId = cur.openChapterId ?: cur.chapters.firstOrNull()?.id
        web = cur.copy(
            chapters = cur.chapters.map { ch ->
                if (ch.id != openId) ch
                else ch.copy(candidates = ch.candidates.map { if (it.id == id) it.copy(verdict = verdict) else it })
            }
        )
    }

    fun webCancel() {
        webGen++
        web = null
        previewBook = null
        viewModelScope.launch(Dispatchers.IO) {
            com.lantern.library.studio.WebFetch.clearWebDir(getApplication<Application>().cacheDir)
        }
    }

    fun webBeginMeta() {
        val cur = web ?: return
        if (cur.phase != com.lantern.library.studio.WebPhase.Ready) return
        if (cur.chapters.isEmpty()) return
        if (cur.pendingUnsure > 0) {
            toast("Decide Unsure items first.")
            return
        }
        web = cur.copy(phase = com.lantern.library.studio.WebPhase.Meta)
    }

    fun webSaveMeta(title: String, author: String, series: String, images: Boolean) {
        val cur = web ?: return
        val kind = if (images) com.lantern.library.studio.PageKind.Images else com.lantern.library.studio.PageKind.Text
        val choices = ArrayList<com.lantern.library.studio.CoverChoice>()
        cur.chapters.forEach { ch ->
            ch.kept.filter { it.type == com.lantern.library.studio.WebBlockType.Image }.forEach { c ->
                val path = c.localPath ?: c.imageUrl ?: return@forEach
                if (choices.none { it.path == path }) {
                    choices += com.lantern.library.studio.CoverChoice("cv${choices.size}", path, ch.title)
                }
            }
        }
        web = cur.copy(
            phase = com.lantern.library.studio.WebPhase.Cover,
            title = title.trim().ifBlank { cur.title.ifBlank { "Untitled" } },
            author = author.trim().let { if (it.equals("Imported", true)) "" else it },
            series = series.trim(),
            kind = kind,
            coverChoices = choices.take(8),
            coverPath = null
        )
    }

    fun webSelectCover(id: String?) {
        val cur = web ?: return
        val path = cur.coverChoices.firstOrNull { it.id == id }?.path
        web = cur.copy(coverPath = path)
    }

    fun webCompile() {
        val cur = web ?: return
        val gen = webGen
        viewModelScope.launch {
            web = cur.copy(phase = com.lantern.library.studio.WebPhase.Compiling, progress = "Compiling book…")
            val cache = getApplication<Application>().cacheDir
            val out = File(com.lantern.library.studio.WebFetch.webDir(cache), "book.epub")
            val compiled = withContext(Dispatchers.IO) {
                runCatching {
                    val model = com.lantern.library.studio.EpubWriter.buildModel(cur) ?: return@runCatching null
                    com.lantern.library.studio.EpubWriter.write(model, out, cache)
                }.getOrNull()
            }
            if (gen != webGen) return@launch
            web = if (compiled != null && com.lantern.library.studio.EpubWriter.validate(compiled)) {
                cur.copy(
                    phase = com.lantern.library.studio.WebPhase.Preview,
                    compiledPath = compiled.absolutePath,
                    progress = ""
                )
            } else {
                cur.copy(
                    phase = com.lantern.library.studio.WebPhase.Failed,
                    error = "Could not compile a readable EPUB.",
                    compiledPath = null
                )
            }
        }
    }

    fun webOpenPreview(then: (LibraryBook?) -> Unit) {
        val cur = web ?: return then(null)
        val compiled = cur.compiledPath?.let { File(it) }
        if (compiled == null || !compiled.exists()) {
            toast("Could not open this book")
            then(null)
            return
        }
        viewModelScope.launch {
            val ready = withContext(Dispatchers.IO) {
                if (!com.lantern.library.studio.EpubWriter.validate(compiled)) return@withContext null
                val pages = runCatching {
                    BookIo.readEpubDocument(compiled).chapters.size
                }.getOrDefault(1).coerceAtLeast(1)
                LibraryBook(
                    id = STUDIO_PREVIEW_ID,
                    title = cur.title.trim().ifBlank { "Untitled" },
                    author = cur.author.trim(),
                    remoteCover = cur.coverPath,
                    format = BookFormat.EPUB,
                    origin = BookOrigin.IMPORT,
                    filePath = compiled.absolutePath,
                    pageCount = pages
                )
            }
            if (ready == null) toast("Could not open this book")
            previewBook = ready
            then(ready)
        }
    }

    fun webAddToLibrary() {
        val cur = web ?: return
        val compiled = cur.compiledPath?.let { File(it) } ?: return
        if (!compiled.exists() || !com.lantern.library.studio.EpubWriter.validate(compiled)) {
            web = cur.copy(phase = com.lantern.library.studio.WebPhase.Failed, error = "Could not compile a readable EPUB.")
            return
        }
        viewModelScope.launch {
            if (libraryFull()) {
                toast("Library is full (200 books)")
                return@launch
            }
            val app = getApplication<Application>()
            val id = "web_${System.currentTimeMillis()}"
            val dest = File(com.lantern.library.data.BookIo.booksDir(app), "$id.epub")
            val coverDest = File(com.lantern.library.data.BookIo.coversDir(app), "$id.jpg")
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    compiled.copyTo(dest, overwrite = true)
                    val coverSrc = cur.coverPath
                    if (!coverSrc.isNullOrBlank()) {
                        val local = File(coverSrc)
                        when {
                            local.exists() && local.length() > 40L -> local.copyTo(coverDest, overwrite = true)
                            coverSrc.startsWith("http") ->
                                com.lantern.library.studio.WebFetch.image(coverSrc, coverDest)
                        }
                    }
                    dest.exists() && dest.length() > 200L
                }.getOrDefault(false)
            }
            if (!ok) {
                withContext(Dispatchers.IO) { runCatching { dest.delete() }; runCatching { coverDest.delete() } }
                toast("Could not add that file")
                return@launch
            }
            val pages = withContext(Dispatchers.IO) {
                runCatching { com.lantern.library.data.BookIo.readEpubDocument(dest).chapters.size }.getOrDefault(1)
            }.coerceAtLeast(1)
            val incoming = LibraryBook(
                id = id,
                title = cur.title.trim().ifBlank { "Untitled" },
                author = cur.author.trim(),
                remoteCover = if (coverDest.exists() && coverDest.length() > 0L) coverDest.absolutePath else null,
                format = BookFormat.EPUB,
                origin = BookOrigin.IMPORT,
                filePath = dest.absolutePath,
                pageCount = pages,
                pendingUpload = true
            )
            when (commitNewUserBook(incoming)) {
                CommitUserBookResult.ACCEPTED -> {
                    toast("Added to Library")
                    webGen++
                    web = null
                    previewBook = null
                    withContext(Dispatchers.IO) {
                        com.lantern.library.studio.WebFetch.clearWebDir(app.cacheDir)
                    }
                    uploadIfPossible(id)
                }
                CommitUserBookResult.LIBRARY_FULL -> {
                    withContext(Dispatchers.IO) { discardOrphan(incoming) }
                    toast("Library is full (200 books)")
                }
                CommitUserBookResult.PERSIST_FAILED -> {
                    withContext(Dispatchers.IO) { discardOrphan(incoming) }
                    toast("Could not add that file")
                }
            }
        }
    }

    private fun wipeStudioSession(session: StudioSession?) {
        session?.workingPath?.let { runCatching { File(it).delete() } }
        session?.coverPath?.let { path ->
            val f = File(path)
            if (f.parentFile?.name == "studio") runCatching { f.delete() }
        }
        BookIo.clearStudioDir(getApplication())
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

    private fun mergeSeedInto(target: MutableList<LibraryBook>) {
        BundledBooks.seed().forEach { s ->
            val i = target.indexOfFirst { it.id == s.id }
            if (i < 0) target.add(s) else target[i] = s.copy(
                currentPage = target[i].currentPage,
                lastReadAt = target[i].lastReadAt,
                finished = target[i].finished,
                addedAt = target[i].addedAt,
                driveFileId = target[i].driveFileId,
                pendingUpload = target[i].pendingUpload,
                filePath = target[i].filePath
            )
        }
    }

    private fun loadBooksInto(target: MutableList<LibraryBook>) {
        if (!booksFile.exists()) return
        var originDirty = false
        runCatching {
            val arr = JSONArray(booksFile.readText())
            val booksDir = BookIo.booksDir(getApplication())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                val storedOrigin = runCatching {
                    BookOrigin.valueOf(o.optString("origin", "BUNDLED"))
                }.getOrDefault(BookOrigin.BUNDLED)
                val filePath = o.optString("filePath").ifBlank { null }
                val origin = migrateLoadedOrigin(id, storedOrigin, filePath, booksDir)
                if (origin != storedOrigin) originDirty = true
                if (origin == BookOrigin.BUNDLED) continue
                target += LibraryBook(
                    id, o.getString("title"), o.optString("author"), null, o.optString("remoteCover").ifBlank { null },
                    runCatching { BookFormat.valueOf(o.optString("format", "TEXT")) }.getOrDefault(BookFormat.TEXT), origin,
                    filePath, o.optString("remoteEpub").ifBlank { null }, null,
                    o.optInt("pageCount", 1), o.optInt("currentPage", 0), o.optBoolean("finished"), o.optLong("addedAt", System.currentTimeMillis()),
                    o.optLong("lastReadAt"), o.optString("category", "Library"), o.optString("synopsis"), emptyList(),
                    o.optString("driveFileId").ifBlank { null }, o.optBoolean("pendingUpload", false)
                )
            }
        }
        if (originDirty) persistBooksList(target)
    }

    /** Legacy BUNDLED/missing origin with a real file under books/ becomes IMPORT. Seed ids stay BUNDLED. Never deletes files. */
    private fun migrateLoadedOrigin(
        id: String,
        origin: BookOrigin,
        filePath: String?,
        booksDir: File
    ): BookOrigin {
        if (id in BundledBooks.seedIds) return BookOrigin.BUNDLED
        if (origin != BookOrigin.BUNDLED) return origin
        val path = filePath ?: return origin
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) return origin
        val underBooks = runCatching {
            val dir = booksDir.canonicalFile
            val target = file.canonicalFile
            target.path == dir.path || target.path.startsWith(dir.path + File.separator)
        }.getOrDefault(false)
        return if (underBooks) BookOrigin.IMPORT else origin
    }

    private fun persistBooksUnlocked() {
        persistBooksList(books.toList())
    }

    private fun persistBooksList(list: List<LibraryBook>) {
        val arr = JSONArray()
        list.forEach { b ->
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

    private fun readWantFile(): List<DiscoveryBook> {
        if (!wantFile.exists()) return emptyList()
        val out = ArrayList<DiscoveryBook>()
        runCatching {
            val arr = JSONArray(wantFile.readText())
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                val incoming = Recommendations.parseBook(row) ?: continue
                if (out.none { Recommendations.sameWork(it, incoming) }) out += incoming
            }
        }
        return out
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
