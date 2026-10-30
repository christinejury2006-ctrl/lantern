package com.lantern.library.data

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val notInterestedIds = prefs.getStringSet("not_interested_volume_ids", emptySet())!!.toMutableSet()
    private val pendingDeleteFile = File(app.filesDir, "pending_deletes.json")
    /** Books the reader deleted on purpose. The other half of the merge — see [Deletions]. */
    private val deletionsFile = File(app.filesDir, "deletions.json")

    /**
     * The upload retry queue.
     *
     * A book that failed to upload is not forgotten: its id, how many attempts it has had, when it
     * was last tried and why it failed are written to disk, so the queue survives the process
     * being killed and the reader can be shown something true about it after a restart.
     */
    private val uploadQueueFile = File(app.filesDir, "upload_queue.json")
    /**
     * Uploads that were in flight when the process died.
     *
     * One small entry per book (id, bytes sent, bytes total, when). It is what lets the shelf say
     * "Paused" after a restart instead of "Failed" — or, worse, nothing at all.
     */
    private val uploadJournalFile = File(app.filesDir, "upload_journal.json")
    private val journalLock = Any()
    /** Per-book throttle, so one book's journal write cannot suppress another's. */
    private val journalFlushedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val deletionsLock = Any()
    private val queueLock = Any()
    private val libraryAppearFile = File(app.filesDir, "library_appearance.json")
    private val readerAppearFile = File(app.filesDir, "reader_appearance.json")
    private val bookmarksFile = File(app.filesDir, "bookmarks.json")
    private val libraryLock = Any()
    private val pendingDeleteLock = Any()
    private val bookmarksLock = Any()
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

    /**
     * Books with an upload running right now.
     *
     * Compose state, because the shelf shows "Uploading" only for these; a book that is merely
     * queued must not claim to be in flight.
     */
    var uploadingIds by mutableStateOf<Set<String>>(emptySet())
        private set

    /** The last computed per-book backup audit, for the summary line and the diagnostics panel. */
    var backupCounts by mutableStateOf(BackupAudit.Counts(0, 0, 0, 0, 0))
        private set

    /**
     * Book id -> why its upload is still queued.
     *
     * Compose state rather than a file read at composition time: the shelf shows the reason in the
     * detail sheet, and a JSON parse per recomposition is exactly the kind of thing that makes a
     * scroll stutter.
     */
    var uploadQueueReasons by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** The edition Explore is currently downloading, so its card can show progress honestly. */
    var fetchingEditionId by mutableStateOf<String?>(null)
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
    private var driveConsentPrompted = false
    private var googleAccountKey: String? = null
    private val bookmarkRecords = mutableStateListOf<SavedBookmark>()
    private var libraryAppearAt = 0L
    private var readerAppearAt = 0L
    private var prefsAt = 0L
    private var snapshotAt = 0L
    private var syncGen = 0
    private var applyingRemote = false

    /**
     * The locks, and what each one is actually for.
     *
     * Uploads used to run inside [passGate], which meant a manual retry waited behind a whole sync
     * pass and a Firestore mirror waited behind a Drive transfer. Neither of those is a reason to
     * block: moving bytes needs no library lock at all. The locks are now split by what they
     * protect, and none of them spans a file transfer:
     *
     * - **[passGate]** — one Drive sync *pass* at a time. It guards the sequence "read the account
     *   snapshot → merge → write the account snapshot", which must not interleave with an
     *   identical sequence from another pass. It is taken with [Mutex.tryLock]: a second pass is
     *   *skipped*, never queued, so nothing waits on it.
     * - **[stateMutex]** — the library itself, for the short local phase of a merge: read the
     *   books, merge both sides, apply, write. No network happens while it is held, so a slow
     *   upload cannot make another writer wait.
     * - **[firebaseGate]** — one Firestore mirror at a time, on the same tryLock terms as
     *   [passGate], so a mirror no longer blocks behind a Drive pass.
     * - **[libraryLock]** and friends — the small state files, unchanged.
     *
     * Duplicate uploads are not prevented by a lock any more: [UploadPool] holds a process-wide
     * single-flight set keyed by book id, and every attempt re-looks-up the book's `loreId`, so two
     * callers that want the same book cannot produce two transfers or two Drive files.
     */
    private val passGate = Mutex()

    /** Local merge + apply only. Never held across a network call. */
    private val stateMutex = Mutex()

    /** One Firestore mirror at a time. */
    private val firebaseGate = Mutex()

    /** True while a pass holds [passGate]; writes made inside it must not schedule another pass. */
    @Volatile private var syncInFlight = false

    /** A real local change arrived while a pass was running, so one more pass is owed. */
    @Volatile private var changedDuringSync = false

    /**
     * The snapshot last written successfully, per mirror.
     *
     * A pass runs after every library write, and most of them change nothing a snapshot can see.
     * Comparing the serialized bytes turns those passes into no-ops instead of Drive and Firestore
     * traffic — the device log had the same snapshot written three times in fourteen seconds.
     */
    private var lastDriveSnapshot: String? = null
    private var lastFirebaseSnapshot: String? = null

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
        val readerAppearance: ReaderAppearance,
        val bookmarks: List<SavedBookmark>,
        val libraryAppearAt: Long,
        val readerAppearAt: Long,
        val prefsAt: Long,
        val snapshotAt: Long
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
            libraryAppearAt = disk.libraryAppearAt
            readerAppearAt = disk.readerAppearAt
            prefsAt = disk.prefsAt
            snapshotAt = disk.snapshotAt
            synchronized(bookmarksLock) {
                bookmarkRecords.clear()
                bookmarkRecords.addAll(disk.bookmarks)
            }
            StartupTrace.mark("LanternStore prefs interestsChosen=$interestsChosen n=${interests.size}")
            if (interestsChosen) {
                forYou = disk.forYouCached.filterNot { it.volumeId in notInterestedIds }
                StartupTrace.mark("LanternStore cached forYou=${forYou.size}")
            }
            hydrated = true
            StartupTrace.mark("LanternStore hydrated")
            // The shelf and the You screen show a per-book backup state from the first frame, not
            // after the first sync.
            reconcileVerifications()
            reconcileInterruptedUploads()
            refreshBackupAudit()
            if (interestsChosen) ensureRecommendations()
            if (account.signedIn && account.provider == "google" && GoogleAuth.accountId(acc) != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    connectDrive(quiet = true, pullAccount = true)
                    // Anything still queued from a previous run gets another try on launch, which
                    // is what "retry when connectivity returns" means for an app that is not
                    // running a foreground service.
                    retryQueuedTransfers()
                }
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
        val storedId = prefs.getString("uid", "").orEmpty()
        val accountFromPrefs = CloudAccount(
            signedIn = prefs.getBoolean("in", false),
            displayName = prefs.getString("name", "") ?: "",
            email = prefs.getString("email", "") ?: "",
            provider = prefs.getString("prov", "") ?: "",
            userId = if (storedId.isNotBlank() && '@' !in storedId) storedId else ""
        )
        val loadedBooks = ArrayList<LibraryBook>()
        synchronized(libraryLock) {
            loadBooksInto(loadedBooks)
            maybeSeedStartersUnlocked(loadedBooks)
            maybeRefreshStarterCoversUnlocked(loadedBooks)
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
        val libRaw = runCatching { if (libraryAppearFile.exists()) libraryAppearFile.readText() else null }.getOrNull()
        val readRaw = runCatching { if (readerAppearFile.exists()) readerAppearFile.readText() else null }.getOrNull()
        val libAppear = LibraryAppearance(appearLookFromJson(libRaw, AppearLook.libraryDefault))
        val readAppear = ReaderAppearance(appearLookFromJson(readRaw, AppearLook.readerDefault))
        val marks = readBookmarksFile()
        return DiskHydrate(
            loadedPrefs, accountFromPrefs, googleAcc, loadedBooks, want,
            chosen, chosenInterests, cached, libAppear, readAppear, marks,
            appearUpdatedAt(libRaw, prefs.getLong("libraryAppearAt", 0L)),
            appearUpdatedAt(readRaw, prefs.getLong("readerAppearAt", 0L)),
            prefs.getLong("prefsAt", 0L),
            prefs.getLong("snapshotAt", 0L)
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
        prefsAt = System.currentTimeMillis()
        prefs.edit().putString("theme", clean.theme.name).putString("readerTheme", clean.readerTheme.name)
            .putString("fontId", clean.fontId)
            .putFloat("fontSize", clean.fontSizeSp).putFloat("brightness", clean.brightness)
            .putBoolean("swipe", clean.swipeMode).putBoolean("landscape", clean.landscape)
            .putBoolean("mobile", clean.useMobileData)
            .putLong("prefsAt", prefsAt)
            .apply()
        markLocalChanged()
    }

    fun saveLibraryAppearance(next: LibraryAppearance) {
        val look = next.look.forCloud().copy(
            wallpaperOpacity = next.look.wallpaperOpacity,
            overlay = next.look.overlay,
            fill = next.look.fill,
            solidArgb = next.look.solidArgb,
            ombreStartArgb = next.look.ombreStartArgb,
            ombreEndArgb = next.look.ombreEndArgb
        )
        libraryAppearance = LibraryAppearance(look)
        libraryAppearAt = System.currentTimeMillis()
        persistLook(libraryAppearFile, look, libraryAppearAt)
        markLocalChanged()
    }

    fun saveReaderAppearance(next: ReaderAppearance) {
        val look = next.look.forCloud().copy(
            wallpaperOpacity = next.look.wallpaperOpacity,
            overlay = next.look.overlay,
            fill = next.look.fill,
            solidArgb = next.look.solidArgb,
            ombreStartArgb = next.look.ombreStartArgb,
            ombreEndArgb = next.look.ombreEndArgb
        )
        readerAppearance = ReaderAppearance(look)
        readerAppearAt = System.currentTimeMillis()
        persistLook(readerAppearFile, look, readerAppearAt)
        markLocalChanged()
    }

    private fun persistLook(file: File, look: AppearLook, updatedAt: Long = 0L) {
        runCatching { atomicWrite(file, look.forCloud().toJson().put("updatedAt", updatedAt).toString()) }
    }

    fun toast(msg: String) { viewModelScope.launch { toast = msg; delay(5000); if (toast == msg) toast = null } }
    fun book(id: String): LibraryBook? = synchronized(libraryLock) { books.firstOrNull { it.id == id } }

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
        markLocalChanged()
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
            markLocalChanged()
        }
        return result
    }

    private fun discardOrphan(book: LibraryBook) {
        book.filePath?.let { runCatching { File(it).delete() } }
        book.remoteCover?.let { path ->
            if (!path.startsWith("http")) runCatching { File(path).delete() }
        }
    }

    /**
     * Removes a book from the shelf, everywhere.
     *
     * The order is the fix for "deleted books come back on the other device":
     *
     * 1. **The deletion is recorded first** ([recordDeletion]) — before the book leaves the
     *    library and before any network call. That record is what the sync sends, so a crash
     *    between these lines can leave the book on the shelf, but can never lose the fact that the
     *    reader deleted it.
     * 2. **The book leaves this phone** — record, bookmarks, and the local file.
     * 3. **The account's copy is scheduled for deletion**, not deleted here. It is only removed
     *    once a snapshot carrying the tombstone has been written, so another device can never be
     *    left holding a record whose file is already gone.
     */
    fun remove(id: String) {
        val b = synchronized(libraryLock) {
            val found = books.firstOrNull { it.id == id } ?: return
            if (found.origin == BookOrigin.BUNDLED) return
            found
        }
        // 1. The statement, before the consequences.
        recordDeletion(b)
        synchronized(libraryLock) {
            books.removeAll { it.id == id }
            persistBooksUnlocked()
        }
        synchronized(bookmarksLock) {
            val next = bookmarkRecords.filterNot { it.bookId == id }
            if (next.size != bookmarkRecords.size) {
                bookmarkRecords.clear()
                bookmarkRecords.addAll(next)
                persistBookmarksUnlocked()
            }
        }
        b.filePath?.let { runCatching { File(it).delete() } }
        clearTransfer(id)
        // The card is gone; a live or failed transfer entry for it must not outlive it.
        TransferBus.clear(id)
        journalDone(id)
        toast("Removed from library")
        markLocalChanged()
        // 3. The account's copy waits for the snapshot to carry the deletion first.
        val driveId = b.driveFileId
        if (!driveId.isNullOrBlank()) addPendingDelete(driveId)
    }
    fun markRead(id: String, page: Int, pages: Int) {
        val b = book(id) ?: return
        // `finished` latches. Reaching the end marks the book complete; scrolling back to re-read
        // does not un-complete it, which is what the reader asked for: a finished book stays in
        // Completed until they remove it or take it off the shelf.
        //
        // Two conditions guard the latch. A one-page report is not a finished book — it is a book
        // whose pages have not been measured yet, and the reader saw exactly that as unread EPUBs
        // sitting in Completed. And a report that does not know its page count must not overwrite
        // one that did, or leaving and reopening a book would reset its progress.
        if (pages <= 1) {
            if (b.pageCount <= 1) return
            upsert(b.copy(currentPage = page.coerceAtLeast(0), lastReadAt = System.currentTimeMillis()))
            return
        }
        val reachedEnd = page >= pages - 1
        upsert(
            b.copy(
                currentPage = page.coerceAtLeast(0),
                pageCount = pages.coerceAtLeast(1),
                lastReadAt = System.currentTimeMillis(),
                finished = b.finished || reachedEnd
            )
        )
    }

    /** Marks a book as one the reader does not want to continue. Keeps the file and the place. */
    fun markDidNotFinish(id: String) {
        val b = book(id) ?: return
        upsert(b.copy(dnf = true))
        toast("Moved to Did not finish")
        markLocalChanged()
    }

    /** Takes a book back out of Did not finish, so it returns to the shelf proper. */
    fun clearDidNotFinish(id: String) {
        val b = book(id) ?: return
        upsert(b.copy(dnf = false))
        toast("Back on the shelf")
        markLocalChanged()
    }
    fun addBookmark(bookId: String, page: Int) {
        val at = System.currentTimeMillis()
        synchronized(bookmarksLock) {
            if (bookmarkRecords.none { it.bookId == bookId && it.pageIndex == page }) {
                bookmarkRecords.add(0, SavedBookmark(bookId, page, at))
                persistBookmarksUnlocked()
            }
        }
        toast("Bookmark saved · page ${page + 1}")
        markLocalChanged()
    }

    fun bookmarksFor(bookId: String): List<Int> =
        synchronized(bookmarksLock) {
            bookmarkRecords.filter { it.bookId == bookId }.map { it.pageIndex }.distinct().sorted()
        }

    /**
     * Drops a saved-to-read entry once the book itself is on the shelf.
     *
     * "Want to read" is a promise the shelf makes; keeping the promise should remove the note, or
     * the same book shows up twice — once as a cover the reader owns and once as a placeholder
     * saying they are still waiting for it.
     */
    private fun clearWantForTitle(title: String) {
        val key = Recommendations.normalize(title)
        if (key.isEmpty()) return
        val removed = wantToRead.removeAll { Recommendations.normalize(it.title) == key }
        if (removed) persistWantToRead()
    }

    fun isWantToRead(book: DiscoveryBook) = wantToRead.any { Recommendations.sameWork(it, book) }

    fun markNotInterested(book: DiscoveryBook) {
        notInterestedIds.add(book.volumeId)
        prefs.edit().putStringSet("not_interested_volume_ids", notInterestedIds.toSet()).apply()
        dropFromRecommendations { it.volumeId in notInterestedIds || Recommendations.sameWork(it, book) }
        toast("Recommendation removed")
    }
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
                forYou = list.filterNot { it.volumeId in notInterestedIds }
                RecDiag.storeCount = forYou.size
                RecDiag.log(RecDiag.summary())
                StartupTrace.mark("ensureRecommendations done n=${list.size} failed=${list.isEmpty()}")
                if (list.isEmpty()) forYouFailed = true
                // Diagnostics stay in logcat: a debug build used to show this as a toast,
                // which landed on top of the shelf as a grey card full of HTTP codes.
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
            val incoming = book.copy(pendingUpload = false)
            when (commitNewUserBook(incoming)) {
                CommitUserBookResult.ACCEPTED -> {
                    toast("Added to Library")
                }
                CommitUserBookResult.LIBRARY_FULL -> {
                    withContext(Dispatchers.IO) { discardOrphan(incoming) }
                    toast("Library is full (200 books)")
                }
                CommitUserBookResult.PERSIST_FAILED -> toast("Could not add that file")
            }
        }
    }

    // Studio used to live here: its import workshop, its chapter crawler and its compiled-book
    // preview, about 480 lines of store code. Explore finds and fetches a book in one step now, so
    // the whole surface is gone. A book arrives as a file, not as a project.

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
            val incoming = book.copy(pendingUpload = false)
            when (commitNewUserBook(incoming)) {
                CommitUserBookResult.ACCEPTED -> {
                    clearWantForTitle(incoming.title)
                    toast("Saved ${book.title}")
                    then?.invoke(incoming)
                }
                CommitUserBookResult.LIBRARY_FULL -> {
                    withContext(Dispatchers.IO) { discardOrphan(incoming) }
                    toast("Library is full (200 books)")
                }
                CommitUserBookResult.PERSIST_FAILED -> toast("Download failed")
            }
        }
    }

    /**
     * Downloads a free edition found by Explore straight into the library.
     *
     * The file is validated by [BookIo.downloadEdition] before anything is added, so a failed or
     * mislabelled download leaves the shelf exactly as it was. Re-downloading an edition the
     * reader already has opens the existing book instead of adding a duplicate.
     */
    fun fetchEdition(edition: FreeEdition, then: ((LibraryBook) -> Unit)? = null) {
        if (fetchingEditionId != null) {
            toast("One download at a time")
            return
        }
        fetchingEditionId = edition.id
        viewModelScope.launch {
            toast("Downloading ${edition.title}…")
            val book = withContext(Dispatchers.IO) {
                runCatching { BookIo.downloadEdition(getApplication(), edition) }.getOrNull()
            }
            fetchingEditionId = null
            if (book == null) {
                toast("That download failed — the file was not a readable ${edition.format.name.lowercase()}")
                return@launch
            }
            val existing = synchronized(libraryLock) { books.firstOrNull { it.id == book.id } }
            if (existing != null) {
                toast("Already on your shelf")
                then?.invoke(existing)
                return@launch
            }
            when (commitNewUserBook(book)) {
                CommitUserBookResult.ACCEPTED -> {
                    clearWantForTitle(book.title)
                    toast("Added ${book.title}")
                    then?.invoke(book)
                }
                CommitUserBookResult.LIBRARY_FULL -> {
                    withContext(Dispatchers.IO) { discardOrphan(book) }
                    toast("Library is full (${MAX_USER_BOOKS} books)")
                }
                CommitUserBookResult.PERSIST_FAILED -> {
                    withContext(Dispatchers.IO) { discardOrphan(book) }
                    toast("Could not save that book")
                }
            }
        }
    }

    fun openForReading(book: LibraryBook, then: (LibraryBook?) -> Unit) {
        viewModelScope.launch {
            // A book restored onto a new phone has no file yet: pull it from the account
            // instead of making the user import it again.
            if (!book.localFileAvailable) {
                toast("Fetching this book from your account…")
            }
            val ready = withContext(Dispatchers.IO) { ensureCached(book) }
            if (ready == null) {
                toast(
                    if (book.driveFileId == null)
                        "This book is not in your account yet. Sign in and sync to store it."
                    else "Could not fetch this book. Check your connection."
                )
            }
            then(ready)
        }
    }

    fun onGoogleSignedIn(acc: GoogleSignInAccount) {
        val incoming = GoogleAuth.accountId(acc)
        if (googleAccountKey != null && incoming != null && googleAccountKey != incoming) {
            DriveLibrary.clearCachedFolder()
        }
        googleAccountKey = incoming
        applyAccount(acc, announce = incoming != null)
        if (incoming.isNullOrBlank()) {
            toast("Signed in, but Google did not return a stable account ID. Library stays on this phone.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) { connectDrive(quiet = false, pullAccount = true) }
        viewModelScope.launch(Dispatchers.IO) { syncFirebaseAccount(acc, announce = true) }
    }

    /**
     * Syncs now, on the reader's word.
     *
     * Three separate things happen and they are reported separately: files are uploaded, the
     * account snapshot is merged with the cloud copy, and the Firestore mirror is refreshed.
     * The toast only claims success once [runAccountSync] has actually come back with a merge.
     */
    fun syncNow() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!account.signedIn) {
                withContext(Dispatchers.Main) { toast("Sign in to sync your library.") }
                return@launch
            }
            connectDrive(quiet = true, pullAccount = false)
            runAccountSync(announce = true)
            syncFirebaseAccount(null, announce = false)
        }
    }

    /**
     * Runs the backup connection test and puts every step into the diagnosis.
     *
     * This is the only way to answer "will sync work now?" without a second phone and a second
     * account: it exercises the same three calls a real backup makes, reports Google's own status
     * for each one, and names the console setting when a step is refused.
     */
    fun testBackupConnection() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                runConnectionTest()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                SyncDiag.fail(
                    "Connection test",
                    "stopped by a ${t.javaClass.simpleName}: " +
                        t.message.orEmpty().take(160).ifBlank { "no message" }
                )
                withContext(Dispatchers.Main) { toast("Test stopped: ${t.javaClass.simpleName}") }
            }
        }
    }

    /** The body of [testBackupConnection], kept separate so the net above stays readable. */
    private suspend fun runConnectionTest() {
        SyncDiag.clear()
        SyncDiag.mark("Connection test", "started")
        if (!account.signedIn) {
            SyncDiag.fail("Google sign-in", "not signed in — sign in from this screen first")
            withContext(Dispatchers.Main) { toast("Sign in first") }
            return
        }
        val ctx = getApplication<Application>()
        val token = when (val result = GoogleAuth.driveToken(ctx)) {
            is DriveTokenResult.Ok -> {
                SyncDiag.mark("Drive token", "granted")
                result.token
            }
            is DriveTokenResult.Recoverable -> {
                SyncDiag.fail("Drive token", "the reader must approve Drive access")
                withContext(Dispatchers.Main) { offerDriveConsent(result.intent) }
                null
            }
            DriveTokenResult.Unavailable -> {
                SyncDiag.fail(
                    "Drive token",
                    "unavailable — no Google account on this device, or the app is not " +
                        "configured with the Drive scope"
                )
                null
            }
        }
        if (token != null) {
            DriveLibrary.clearError()
            val checks = runCatching { DriveLibrary.selfTest(token) }.getOrElse { error ->
                listOf(DriveLibrary.Check("Connection test", false, error.message ?: "failed"))
            }
            checks.forEach { check ->
                if (check.ok) SyncDiag.mark(check.stage, check.detail)
                else SyncDiag.fail(check.stage, check.detail)
            }
            val allOk = checks.isNotEmpty() && checks.all { it.ok }
            SyncDiag.mark(
                "Result",
                if (allOk) "backup works on this device" else "see the failing step above"
            )
            withContext(Dispatchers.Main) {
                toast(if (allOk) "Backup works on this device" else "Backup is blocked — see Sync diagnosis")
            }
        }
        // Firebase is the second half of the same question.
        syncFirebaseAccount(null, announce = false)
    }

    fun requestDriveConnect() {
        driveConsentPrompted = false
        viewModelScope.launch(Dispatchers.IO) { connectDrive(quiet = false, pullAccount = true) }
    }

    fun takeDriveConsentIntent(): Intent? {
        val intent = driveConsentIntent
        driveConsentIntent = null
        return intent
    }

    fun onDriveConsentFinished() {
        viewModelScope.launch(Dispatchers.IO) { connectDrive(quiet = false, pullAccount = true) }
    }

    fun signOut(activity: android.app.Activity) {
        syncGen++
        viewModelScope.launch(Dispatchers.IO) {
            try {
                DriveLibrary.clearCachedFolder()
                GoogleAuth.signOutAndClearToken(activity, getApplication())
                FirestoreAccountRepository.signOut()
            } finally {
                googleAccountKey = null
                withContext(Dispatchers.Main) {
                    driveConsentPrompted = false
                    driveConsentIntent = null
                    account = CloudAccount()
                    prefs.edit()
                        .putBoolean("in", false)
                        .putString("prov", "")
                        .putString("uid", "")
                        .apply()
                    toast("Signed out")
                }
            }
        }
    }

    private fun applyAccount(acc: GoogleSignInAccount, announce: Boolean) {
        val name = acc.displayName.orEmpty().ifBlank { acc.email.orEmpty() }
        val email = acc.email.orEmpty()
        val uid = GoogleAuth.accountId(acc).orEmpty()
        account = CloudAccount(true, name, email, "google", driveConnected = false, userId = uid)
        prefs.edit()
            .putBoolean("in", true)
            .putString("name", name)
            .putString("email", email)
            .putString("prov", "google")
            .putString("uid", uid)
            .apply()
        SyncDiag.mark(
            "Google sign-in",
            "name=$name id=${if (uid.isBlank()) "missing" else "ok"}"
        )
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

    private suspend fun connectDrive(quiet: Boolean, pullAccount: Boolean) {
        when (val result = GoogleAuth.driveToken(getApplication())) {
            is DriveTokenResult.Ok -> {
                SyncDiag.mark("Drive token", "granted")
                withContext(Dispatchers.Main) { setDriveConnected(true) }
                // Account-side deletions are *not* retried here on purpose: a file may only be
                // removed once a snapshot carrying its tombstone has been written, and that
                // happens inside [runAccountSyncInner]. Deleting first would leave another device
                // holding a record whose file no longer exists.
                if (pullAccount) runAccountSync(announce = !quiet) else retryPendingDeletes()
            }
            is DriveTokenResult.Recoverable -> {
                SyncDiag.fail("Drive token", "needs the reader to grant Drive access")
                withContext(Dispatchers.Main) {
                    setDriveConnected(false)
                    if (driveConsentPrompted) {
                        if (!quiet) toast("Account sync is not connected")
                    } else {
                        offerDriveConsent(result.intent)
                    }
                }
            }
            DriveTokenResult.Unavailable -> {
                SyncDiag.fail(
                    "Drive token",
                    "unavailable — no Google account on this device, or the Drive API is not enabled for the project"
                )
                withContext(Dispatchers.Main) {
                    setDriveConnected(false)
                    if (!quiet && account.signedIn) toast("Account sync is not connected")
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

    private suspend fun <T> withDriveOutcome(op: (String, String) -> DriveOutcome<T>): DriveOutcome<T>? {
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
            is DriveOutcome.Ok -> out
            DriveOutcome.Failed -> DriveOutcome.Failed
            DriveOutcome.Unauthorized -> {
                if (GoogleAuth.accountKey(ctx) != owner) return null
                GoogleAuth.clearToken(ctx, first)
                val second = token() ?: return null
                op(second, owner)
            }
        }
    }

    suspend fun ensureBookAvailable(bookId: String): LibraryBook? {
        val current = book(bookId) ?: return null
        val ready = withContext(Dispatchers.IO) { ensureCached(current) }
        if (ready == null) withContext(Dispatchers.Main) {
            toast(if (current.driveFileId.isNullOrBlank()) "This book has no cloud backup yet." else "Could not download this book. Check your connection and Drive access.")
        }
        return ready
    }

    /**
     * Fetches a book's bytes if this phone does not have them, and returns a book that points at a
     * file which is known to be a valid EPUB/PDF.
     *
     * The download lands in a `.restore` file, is checked against the recorded size and checksum
     * and the EPUB/PDF container rules, and only then is moved into place — so an HTML error page,
     * a half-finished transfer or a wrong file can never be mistaken for the reader's book. The
     * record is updated only after that succeeds, and `backupAt` is left alone: this operation
     * reads the account, it does not establish anything new about the account's copy.
     */
    private suspend fun ensureCached(book: LibraryBook): LibraryBook? {
        if (book.origin == BookOrigin.BUNDLED || book.format == BookFormat.TEXT) return book
        val local = book.filePath?.let { File(it) }
        if (local != null && local.exists() && local.length() > 0L &&
            BookFiles.validate(local, book.format) is BookFiles.Check.Ok
        ) {
            return book
        }
        if (book.driveFileId.isNullOrBlank()) return null
        // The same verified restore the Restore button runs. Opening a book whose download was
        // removed and tapping "Restore download" must not be two different behaviours: both check
        // the recorded size and checksum, and neither replaces a good file with a partial one.
        withContext(Dispatchers.Main) { restoringIds = restoringIds + book.id }
        val outcome = try {
            downloadService.restoreDownload(book.id)
        } finally {
            withContext(Dispatchers.Main) { restoringIds = restoringIds - book.id }
        }
        maybeEvictCache(keepId = book.id)
        return when (outcome) {
            is RestoreOutcome.Restored -> outcome.book
            is RestoreOutcome.AlreadyAvailable -> outcome.book
            else -> null
        }
    }

    private fun markLocalChanged() {
        if (applyingRemote) return
        snapshotAt = System.currentTimeMillis()
        prefs.edit().putLong("snapshotAt", snapshotAt).putBoolean("sync_dirty", true).apply()
        // A pass stamps confirmations and applies merged libraries; those writes are the pass's own
        // output, and re-running because of them is how one upload became six syncs. Remember that
        // something changed and let the pass decide, once, when it is done.
        if (syncInFlight) {
            changedDuringSync = true
            return
        }
        scheduleSync()
        scheduleFirebaseSync()
    }

    private fun scheduleFirebaseSync() {
        val gen = ++firebaseSyncGen
        viewModelScope.launch(Dispatchers.IO) {
            delay(8_000)
            if (gen != firebaseSyncGen || !FirestoreAccountRepository.isSignedIn()) return@launch
            syncFirebaseAccount(null, announce = false)
        }
    }

    private var firebaseSyncGen = 0

    /**
     * Set once Firestore says the rules are not published.
     *
     * Fuel for nothing: the document is rejected every single time until the project's rules are
     * deployed, and retrying it on every sync only fills the diagnosis with the same line. Drive
     * holds the library in the meantime, so the mirror is simply skipped until the next launch.
     */
    private var firebaseBlocked = false

    private suspend fun syncFirebaseAccount(account: GoogleSignInAccount?, announce: Boolean) {
        // Its own gate, not the Drive pass's: a mirror must not wait for a book upload to finish,
        // and the shared state it touches is protected by [stateMutex] for the merge itself.
        if (!firebaseGate.tryLock()) {
            SyncDiag.mark("Firebase", "skipped: a mirror is already running")
            return
        }
        try {
            syncInFlight = true
            try {
                mirrorToFirebase(account, announce)
            } finally {
                syncInFlight = false
                val owed = changedDuringSync
                changedDuringSync = false
                if (owed) scheduleFirebaseSync()
            }
        } finally {
            firebaseGate.unlock()
        }
    }

    private suspend fun mirrorToFirebase(account: GoogleSignInAccount?, announce: Boolean) {
        if (firebaseBlocked) {
            SyncDiag.mark("Firebase", "skipped: the project's Firestore rules are not published")
            return
        }
        try {
            if (account != null) FirestoreAccountRepository.signIn(account)
            if (!FirestoreAccountRepository.isSignedIn()) {
                SyncDiag.mark("Firebase", "skipped: not signed in to Firebase")
                return
            }
            // Read the account copy outside any lock; merge and apply inside it, which is local
            // work only and is the part that must not interleave with a Drive pass.
            val cloud = AccountSync.fromJson(FirestoreAccountRepository.readSnapshot())
            val merged = stateMutex.withLock {
                val local = withContext(Dispatchers.Main) { currentSnapshot() }
                AccountSync.merge(local, cloud).also { result ->
                    withContext(Dispatchers.Main) { applySnapshot(result) }
                }
            }
            val json = AccountSync.toJson(merged)
            if (json == lastFirebaseSnapshot) {
                SyncDiag.mark("Firebase", "already up to date")
            } else {
                FirestoreAccountRepository.writeSnapshot(json)
                lastFirebaseSnapshot = json
                SyncDiag.mark("Firebase", "snapshot written")
            }
            if (announce) withContext(Dispatchers.Main) { toast("Firebase library sync complete") }
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            if (message.contains("PERMISSION_DENIED", true) || message.contains("insufficient permissions", true)) {
                firebaseBlocked = true
            }
            SyncDiag.fail(
                "Firebase",
                if (message.contains("PERMISSION_DENIED", true) || message.contains("insufficient permissions", true)) {
                    "Firestore rejected the write: this project's security rules have never been " +
                        "published. Run `firebase deploy --only firestore:rules`. Drive sync is " +
                        "unaffected — it already holds your library."
                } else {
                    message.take(160).ifBlank { e.javaClass.simpleName }
                }
            )
            if (announce) withContext(Dispatchers.Main) {
                toast(if (message.contains("OAuth client ID") || message.contains("ID token"))
                    "Firebase needs its Web OAuth client ID. Google Drive sync remains available."
                else "Firebase sync unavailable. Your local library and Drive sync are preserved.")
            }
        }
    }

    private fun scheduleSync() {
        if (!account.signedIn || account.provider != "google") return
        if (GoogleAuth.accountId(getApplication<Application>()) == null) return
        val gen = ++syncGen
        viewModelScope.launch(Dispatchers.IO) {
            delay(8_000)
            if (gen != syncGen) return@launch
            runAccountSync(announce = false)
        }
    }

    private fun networkReady(): Boolean {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun currentSnapshot(): AccountSnapshot {
        val bookList = synchronized(libraryLock) { books.toList() }
        val marks = synchronized(bookmarksLock) { bookmarkRecords.toList() }
        val accountId = GoogleAuth.accountId(getApplication<Application>())
            ?: account.userId.takeIf { it.isNotBlank() && '@' !in it }
            ?: ""
        return AccountSnapshot(
            updatedAt = snapshotAt,
            userId = accountId,
            books = bookList,
            bookmarks = marks,
            libraryAppearance = libraryAppearance.look,
            readerAppearance = readerAppearance.look,
            prefs = readingPrefs,
            libraryAppearAt = libraryAppearAt,
            readerAppearAt = readerAppearAt,
            prefsAt = prefsAt,
            bookOrder = bookList.map { it.id },
            deletions = deletions()
        )
    }


    /**
     * Writes a batch's outcome into the book records and the retry queue.
     *
     * One place, because "confirmed" must mean the same thing everywhere it is recorded: the bytes
     * were verified against the account, the size that was verified is stored with the stamp, and
     * the queue entry is cleared. A failure records the reason and the attempt count instead, and
     * never clears `backupAt` into something that looks like success.
     */
    private suspend fun recordBatch(batch: BatchResult) {
        val now = System.currentTimeMillis()
        withContext(Dispatchers.Main) {
            batch.confirmed.forEach { (bookId, verified) ->
                val latest = book(bookId) ?: return@forEach
                upsert(
                    latest.copy(
                        driveFileId = verified.first,
                        pendingUpload = false,
                        backupFailed = false,
                        backupAt = now,
                        backupBytes = verified.second
                    )
                )
                clearTransfer(bookId)
            }
            batch.failed.forEach { (book, failure) ->
                val latest = book(book.id) ?: return@forEach
                upsert(latest.copy(pendingUpload = true, backupFailed = true, backupAt = 0L))
                noteTransferFailed(latest, failure.reason, failure.retryable)
            }
            refreshBackupAudit()
        }
    }

    /**
     * Gives the retry queue another chance.
     *
     * This is the "retry when connectivity returns" half of the queue. The app has no foreground
     * service, so the honest triggers are: app launch, sign-in, and any explicit sync. Each of
     * those calls this, and a book that has been failing for a week is retried exactly as often as
     * the reader uses the app — not once per keystroke.
     */
    fun retryQueuedTransfers() {
        viewModelScope.launch(Dispatchers.IO) {
            val queued = pendingTransfers().filter { it.retryable }
            if (queued.isEmpty()) return@launch
            if (!account.signedIn || account.provider != "google") {
                SyncDiag.mark("Retry queue", "${queued.size} book(s) waiting for a signed-in account")
                return@launch
            }
            if (!networkReady()) {
                SyncDiag.mark("Retry queue", "${queued.size} book(s) waiting for a connection")
                return@launch
            }
            val books = queued.mapNotNull { transfer -> book(transfer.bookId) }.filter { candidate ->
                val file = candidate.filePath?.let(::File)
                BackupRules.needsUpload(candidate, file != null && file.exists() && file.length() > 0L)
            }
            if (books.isEmpty()) {
                // Nothing left to send (deleted, restored, or already confirmed): forget them.
                queued.forEach { clearTransfer(it.bookId) }
                return@launch
            }
            SyncDiag.mark("Retry queue", "retrying ${books.size} book(s) from the queue")
            // No pass lock: this is only transfers, and UploadPool's single-flight set already
            // stops it from racing a pass for the same book.
            val batch = uploadBatch(books)
            batch.failed.forEach { (book, failure) ->
                SyncDiag.fail(
                    "Retry failed — ${book.title.ifBlank { "Untitled" }} (${book.id.take(8)})",
                    failure.reason + if (failure.retryable) " · still queued" else " · will not retry by itself"
                )
            }
            if (batch.confirmed.isNotEmpty()) {
                SyncDiag.mark("Retry queue", "${batch.confirmed.size} recovered")
            }
            recordBatch(batch)
        }
    }

    /**
     * Puts every book that still owes the cloud a file into Drive, and records what happened.
     *
     * Three things changed here and each of them was a real way to lose a book:
     *
     * - **Stale copies are re-uploaded.** The old filter only looked at books with no Drive id, so a
     *   book whose remote copy was older than the local file was skipped forever.
     * - **Nothing is called backed up without confirmation.** A book only gets [LibraryBook.backupAt]
     *   after `DriveLibrary.upload` has verified the bytes against Drive's checksum. A failed upload
     *   leaves `backupFailed` set and the book queued.
     * - **The caller learns whether the account is complete**, so a snapshot is never presented as a
     *   finished backup when some of it is missing.
     */
    /**
     * One batch of uploads, with the per-book outcome kept apart from the HTTP outcome.
     *
     * Every book ends in exactly one of two places: *confirmed* (the bytes were verified against
     * the account) or *failed with a reason* (which is written to the retry queue and shown with
     * the book's own title). Nothing is marked backed up on the strength of a request having been
     * sent.
     */
    /**
     * Sends [todo] through [UploadPool]: several books at once, each verified the moment its own
     * last byte is out.
     *
     * This function is the bridge between the pool (transport, concurrency, per-book verification)
     * and the app (records, live badges, the journal, the diagnosis). It keeps the same
     * [BatchResult] contract the sync pass already used, so the merge and snapshot logic around it
     * is unchanged.
     */
    private suspend fun uploadBatch(todo: List<LibraryBook>): BatchResult {
        if (todo.isEmpty()) return BatchResult(emptyMap(), emptyList())
        val confirmed = LinkedHashMap<String, Pair<String, Long>>()
        val failed = ArrayList<Pair<LibraryBook, DriveLibrary.TransferFailure>>()

        // Say "Uploading" only while it is true, and for the books that are actually going.
        withContext(Dispatchers.Main) { uploadingIds = uploadingIds + todo.map { it.id } }
        try {
            val ctx = getApplication<Application>()
            // Books with no readable local file are settled here: they need a restore or a
            // re-import, and no amount of retrying will produce bytes that are not on the phone.
            var remaining = todo.filter { book ->
                val file = book.filePath?.let(::File)
                if (file != null && file.exists() && file.length() > 0L) {
                    true
                } else {
                    failed += book to DriveLibrary.TransferFailure(
                        book.id, book.title, "upload", "no readable local file to send", retryable = false
                    )
                    false
                }
            }

            // Two rounds at most: the second exists only to recover from a token that was refused
            // part-way through (the reader may have signed in again in between).
            for (round in 0 until 2) {
                if (remaining.isEmpty()) break
                val token = driveToken()
                if (token == null) {
                    // No token: nothing can be sent, and none of these books may be marked done.
                    remaining.forEach { book ->
                        failed += book to DriveLibrary.TransferFailure(
                            book.id, book.title, "upload",
                            DriveLibrary.lastError ?: "Drive access is not available on this device",
                            retryable = true
                        )
                    }
                    remaining = emptyList()
                    break
                }
                val result = UploadPool.shared.run(token, remaining, uploadListener)
                confirmed.putAll(result.confirmed)
                failed += result.failed
                // A book another transfer already owns is neither done nor broken. Say so, so it
                // stays in the queue instead of looking finished because nothing was reported.
                result.skipped.forEach { book ->
                    failed += book to DriveLibrary.TransferFailure(
                        book.id, book.title, "upload",
                        "another transfer for this book is already running", retryable = true
                    )
                }
                if (!result.unauthorized) break
                // A refused token is worth exactly one more attempt with a fresh one.
                GoogleAuth.clearToken(ctx, token)
                remaining = remaining.filter { it.id !in confirmed }
                if (round == 1) {
                    remaining.forEach { book ->
                        if (failed.none { it.first.id == book.id }) {
                            failed += book to DriveLibrary.TransferFailure(
                                book.id, book.title, "upload",
                                "Google kept refusing the sign-in — sign out and in again",
                                retryable = false
                            )
                        }
                    }
                }
            }
        } finally {
            withContext(Dispatchers.Main) { uploadingIds = uploadingIds - todo.map { it.id }.toSet() }
        }
        // One entry per book, and the most recent reason wins: the second round's answer is the
        // one the reader needs. Two failures for one book would also make the counts lie.
        val deduped = failed.reversed().distinctBy { it.first.id }.reversed()
        return BatchResult(confirmed, deduped)
    }

    /** A Drive token, or null if this device cannot get one right now. */
    private suspend fun driveToken(): String? {
        val ctx = getApplication<Application>()
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

    /**
     * Turns pool events into what the app shows.
     *
     * The important one is [UploadListener.onVerified]: it writes the confirmation and clears the
     * live progress **for that book**, on its own, the moment its own verification passes. Book A
     * therefore turns into a cloud while B and C are still counting bytes — no batch-level step
     * waits for the slowest upload.
     */
    private val uploadListener = object : UploadListener {
        override fun onStarted(book: LibraryBook, totalBytes: Long) {
            TransferBus.start(book.id, totalBytes)
            journalProgress(book.id, 0L, totalBytes, force = true)
        }

        override fun onProgress(book: LibraryBook, sent: Long, total: Long) {
            TransferBus.sent(book.id, sent, total)
            journalProgress(book.id, sent, total)
        }

        override fun onVerifying(book: LibraryBook, totalBytes: Long) {
            TransferBus.verifying(book.id, totalBytes)
            journalProgress(book.id, totalBytes, totalBytes, force = true)
        }

        override fun onVerified(book: LibraryBook, driveFileId: String, bytes: Long) {
            TransferBus.clear(book.id)
            journalDone(book.id)
            // Independent of the rest of the batch: this book is safe as soon as it is proved.
            viewModelScope.launch(Dispatchers.Main) {
                recordVerified(book.id, driveFileId, bytes)
            }
        }

        override fun onFailed(book: LibraryBook, failure: DriveLibrary.TransferFailure, reachedBytes: Long) {
            TransferBus.failed(book.id, reachedBytes, TransferBus.progressFor(book.id)?.totalBytes ?: 0L, failure.reason)
            journalDone(book.id)
        }

        override fun onPaused(book: LibraryBook, sent: Long, total: Long, reason: String) {
            // Interrupted, not failed: the bytes already sent and the size are kept so the card can
            // say how far it got and the journal can resume it.
            TransferBus.paused(book.id, sent, total, reason)
            journalProgress(book.id, sent, total, force = true)
        }

        override fun onSkipped(book: LibraryBook, reason: String) {
            // Another caller owns this book right now; this pass must not touch its badge.
            SyncDiag.mark("Upload skipped — ${book.title.ifBlank { "Untitled" }}", reason)
        }

        override fun onRetry(book: LibraryBook, attempt: Int, reason: String) {
            SyncDiag.mark(
                "Retrying — ${book.title.ifBlank { "Untitled" }}",
                "attempt ${attempt + 1}: $reason"
            )
        }
    }

    /** Writes one book's verified outcome to its record. Idempotent, and never invents a stamp. */
    private fun recordVerified(bookId: String, driveFileId: String, bytes: Long) {
        val latest = book(bookId) ?: return
        upsert(
            latest.copy(
                driveFileId = driveFileId,
                pendingUpload = false,
                backupFailed = false,
                backupAt = System.currentTimeMillis(),
                backupBytes = bytes
            )
        )
        clearTransfer(bookId)
        refreshBackupAudit()
    }

    /** What one batch managed to confirm (id -> bytes) and what failed, with reasons. */
    private data class BatchResult(
        val confirmed: Map<String, Pair<String, Long>>,
        val failed: List<Pair<LibraryBook, DriveLibrary.TransferFailure>>
    )

    /**
     * Puts every book that still owes the cloud a file into Drive, and records what happened.
     *
     * Three things changed here and each of them was a real way to lose a book:
     *
     * - **Stale copies are re-uploaded.** The old filter only looked at books with no Drive id, so a
     *   book whose remote copy was older than the local file was skipped forever.
     * - **Nothing is called backed up without confirmation.** A book only gets [LibraryBook.backupAt]
     *   after `DriveLibrary.upload` has verified the bytes against Drive's checksum or by reading
     *   them back. A failed upload leaves `backupFailed` set, the queue entry written, and the book
     *   clearly labelled in the shelf and the diagnosis.
     * - **The caller learns whether the account is complete**, so a snapshot is never presented as a
     *   finished backup when some of it is missing.
     */
    private suspend fun uploadAvailableBooks(): UploadSummary {
        val candidates = withContext(Dispatchers.Main) {
            synchronized(libraryLock) { books.toList() }
        }.filter { book ->
            val file = book.filePath?.let(::File)
            BackupRules.needsUpload(book, file != null && file.exists() && file.length() > 0L)
        }
        if (candidates.isEmpty()) {
            withContext(Dispatchers.Main) { refreshBackupAudit() }
            return UploadSummary(attempted = 0, confirmed = emptyList(), failed = emptyList())
        }

        val batch = uploadBatch(candidates)
        // (book id, drive id): the same pair the old summary carried, now taken from the batch that
        // actually verified the bytes.
        val summary = UploadSummary(
            candidates.size,
            batch.confirmed.map { (bookId, verified) -> bookId to verified.first },
            batch.failed.map { it.first.id }
        )
        SyncDiag.mark(
            "File uploads",
            "${summary.confirmed.size} confirmed, ${summary.failed.size} failed of ${summary.attempted}"
        )
        // A count is not a diagnosis. Every failure names the book, what was attempted, and why —
        // plus whether it will be tried again, so "failed" is not mistaken for "gone".
        batch.failed.forEach { (book, failure) ->
            SyncDiag.fail(
                "Upload failed — ${book.title.ifBlank { "Untitled" }} (${book.id.take(8)})",
                failure.reason + if (failure.retryable) " · queued for retry" else " · will not retry by itself"
            )
        }

        recordBatch(batch)
        return summary
    }

    /** What a backup pass managed to confirm, and what it still owes. */
    private data class UploadSummary(
        val attempted: Int,
        val confirmed: List<Pair<String, String>>,
        val failed: List<String>
    ) {
        val complete: Boolean get() = failed.isEmpty()
    }

    /**
     * Sync, with a net under it.
     *
     * Sync is the one part of the app that talks to two networks and three encodings at once, and
     * it runs in a coroutine nobody is watching: an exception in here used to reach
     * `viewModelScope` and take the whole app down (an OkHttp multipart rule did exactly that, on
     * every sync, for several rounds). A bug in sync must cost the reader a sync, not their book.
     */
    private suspend fun runAccountSync(announce: Boolean) {
        // tryLock, not lock: a second pass is skipped rather than queued. It used to queue, which
        // meant every scheduled pass, retry and sign-in waited behind whichever pass was running —
        // including its network transfers. Skipping is safe because the pass that is running will
        // schedule one more if the library changed underneath it, and because a skipped pass has
        // nothing to merge that the running one will not also merge.
        if (!passGate.tryLock()) {
            SyncDiag.mark("Account sync", "skipped: a pass is already running")
            return
        }
        try {
            syncInFlight = true
            changedDuringSync = false
            try {
                runAccountSyncInner(announce)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                SyncDiag.fail(
                    "Account sync",
                    "stopped by a ${t.javaClass.simpleName}: " + t.message.orEmpty().take(160).ifBlank { "no message" }
                )
                if (announce) {
                    withContext(Dispatchers.Main) { toast("Sync stopped: ${t.javaClass.simpleName}") }
                }
            } finally {
                syncInFlight = false
                val owed = changedDuringSync
                changedDuringSync = false
                // One more pass, and only one: the pass clears the flag before it starts, so this
                // cannot chain forever.
                if (owed) scheduleSync()
            }
        } finally {
            passGate.unlock()
        }
    }

    private suspend fun runAccountSyncInner(announce: Boolean) {
        if (!account.signedIn || account.provider != "google") {
            SyncDiag.fail("Account sync", "skipped: not signed in with Google")
            return
        }
        if (GoogleAuth.accountId(getApplication<Application>()).isNullOrBlank()) {
            SyncDiag.fail("Account sync", "skipped: Google returned no stable account id")
            return
        }
        if (!networkReady()) {
            SyncDiag.fail("Account sync", "skipped: no usable connection")
            return
        }
        // Files first: the snapshot describes them, so it must not be written before them.
        val uploads = uploadAvailableBooks()
        val local = withContext(Dispatchers.Main) { currentSnapshot() }
        val cloudOutcome = withDriveOutcome { token, owner ->
            when (val folder = DriveLibrary.ensureFolder(token, owner)) {
                is DriveOutcome.Ok -> DriveLibrary.readJsonByLoreId(token, AccountSync.STATE_LORE_ID)
                DriveOutcome.Unauthorized -> DriveOutcome.Unauthorized
                DriveOutcome.Failed -> DriveOutcome.Failed
            }
        }
        val cloudRaw = when (cloudOutcome) {
            null, DriveOutcome.Unauthorized -> return
            DriveOutcome.Failed -> {
                if (announce) withContext(Dispatchers.Main) { toast("Could not sync account. Library stays on this phone.") }
                return
            }
            is DriveOutcome.Ok -> cloudOutcome.value
        }
        val cloud = AccountSync.fromJson(cloudRaw)
        SyncDiag.mark(
            "Account snapshot read",
            when {
                cloudRaw.isNullOrBlank() -> "no snapshot in the account yet"
                cloud == null -> "snapshot present but unreadable"
                else -> "snapshot has ${cloud.books.size} books, updatedAt=${cloud.updatedAt}"
            }
        )
        // One Google account must never inherit another's library. Drive's appDataFolder is already
        // per-user, and this is the second lock: if the snapshot names an account and it is not the
        // one signed in, nothing is merged and nothing is written over it.
        if (cloud != null && cloud.userId.isNotBlank() && local.userId.isNotBlank() && cloud.userId != local.userId) {
            if (announce) withContext(Dispatchers.Main) {
                toast("That backup belongs to a different Google account. Sign in with the account it was made with.")
            }
            return
        }
        var merged = stateMutex.withLock {
            val fresh = withContext(Dispatchers.Main) { currentSnapshot() }
            AccountSync.merge(fresh, cloud).also { result ->
                withContext(Dispatchers.Main) { applySnapshot(result) }
            }
        }
        // Books that arrived from the cloud have no file here; books added locally since the pass
        // above still owe one. Deliberately excludes anything the phone cannot read, and caps the
        // batch so one sync cannot turn into an hour of uploads on a phone connection.
        val stillOwed = merged.books.filter { b ->
            val file = b.filePath?.let(::File)
            BackupRules.needsUpload(b, file != null && file.exists() && file.length() > 0L)
        }.take(40)
        var extraFailures = 0
        if (stillOwed.isNotEmpty()) {
            val batch = uploadBatch(stillOwed)
            extraFailures = batch.failed.size
            if (batch.failed.isNotEmpty()) {
                SyncDiag.mark(
                    "File uploads (after merge)",
                    "${batch.confirmed.size} confirmed, ${batch.failed.size} failed of ${stillOwed.size}"
                )
                batch.failed.forEach { (book, failure) ->
                    SyncDiag.fail(
                        "Upload failed — ${book.title.ifBlank { "Untitled" }} (${book.id.take(8)})",
                        failure.reason + if (failure.retryable) " · queued for retry" else " · will not retry by itself"
                    )
                }
            }
            if (batch.confirmed.isNotEmpty()) {
                val at = System.currentTimeMillis()
                merged = merged.copy(
                    books = merged.books.map { b ->
                        val verified = batch.confirmed[b.id]
                        if (verified != null) {
                            b.copy(
                                driveFileId = verified.first,
                                pendingUpload = false,
                                backupFailed = false,
                                backupAt = at,
                                backupBytes = verified.second
                            )
                        } else b
                    }
                )
            }
            recordBatch(batch)
        }
        // The merged library was applied above, inside the state lock, before any of the uploads
        // below ran. Uploads take time, and the reader can delete a book while its file is in
        // flight, so the snapshot is reconciled against the *current* tombstones immediately before
        // it is written: a book deleted during this pass is never carried back into the account
        // copy by a merge that was computed before the deletion happened.
        val reconciled = AccountReconcile.snapshot(merged, deletions())
        if (reconciled.books.size != merged.books.size) {
            SyncDiag.mark(
                "Account merge",
                "${merged.books.size - reconciled.books.size} book(s) deleted during this pass were dropped"
            )
        }
        merged = reconciled
        SyncDiag.mark(
            "Library applied",
            "local=${local.books.size} cloud=${cloud?.books?.size ?: 0} merged=${merged.books.size} books"
        )
        val json = AccountSync.toJson(merged)
        val unchanged = json == lastDriveSnapshot
        val written = if (unchanged) {
            // Byte-identical to what this process last wrote, so the account already has it.
            DriveOutcome.Ok(AccountSync.STATE_LORE_ID)
        } else {
            withDrive { token, owner ->
                when (val folder = DriveLibrary.ensureFolder(token, owner)) {
                    is DriveOutcome.Ok -> DriveLibrary.writeJsonByLoreId(
                        token,
                        folder.value,
                        AccountSync.STATE_LORE_ID,
                        AccountSync.STATE_FILE_NAME,
                        json
                    )
                    DriveOutcome.Unauthorized -> DriveOutcome.Unauthorized
                    DriveOutcome.Failed -> DriveOutcome.Failed
                }
            }
        }
        if (written != null) {
            if (!unchanged) lastDriveSnapshot = json
            SyncDiag.mark("Account snapshot written", if (unchanged) "unchanged — not re-sent" else "ok")
            // The deletion is now durably in the account, so the files it names can go. This is
            // also the point at which a retry of an earlier failed delete becomes safe.
            retryPendingDeletes()
            withContext(Dispatchers.Main) {
                val at = System.currentTimeMillis()
                val pending = uploads.failed.size + extraFailures
                // The dirty flag means "the account is fully up to date". While a file is still
                // owed, it stays set so the next sync retries it.
                prefs.edit()
                    .putBoolean("sync_dirty", pending > 0)
                    .putLong("lastSyncAt", at)
                    .apply()
                account = account.copy(lastSyncAt = at)
                if (announce) {
                    toast(
                        when {
                            pending == 0 -> "Account sync complete"
                            pending == 1 -> "Library synced. 1 book is still waiting to upload."
                            else -> "Library synced. $pending books are still waiting to upload."
                        }
                    )
                }
            }
        } else {
            SyncDiag.fail("Account snapshot written", "Drive rejected the write; the account copy is unchanged")
            if (announce) withContext(Dispatchers.Main) {
                toast("Could not sync account. Library stays on this phone.")
            }
        }
    }

    private fun applySnapshot(snap: AccountSnapshot) {
        applyingRemote = true
        try {
            // Deletions first, and on their own: a tombstone that arrives with a snapshot must be
            // written down before the books are, or an interrupted apply would resurrect exactly
            // the book this snapshot was sent to delete.
            val stones = Deletions.prune(snap.deletions)
            mergeDeletions(stones)
            val incoming = snap.books.filter {
                it.origin != BookOrigin.BUNDLED &&
                    it.id !in BundledBooks.seedIds &&
                    !Deletions.blocks(it, stones)
            }
            // Books this device still holds that the account has deleted.
            val blocked = synchronized(libraryLock) { books.filter { Deletions.blocks(it, stones) } }
            synchronized(libraryLock) {
                val localById = books.associateBy { it.id }
                val keptBundled = books.filter { it.origin == BookOrigin.BUNDLED }
                val next = ArrayList<LibraryBook>(keptBundled.size + incoming.size)
                next.addAll(keptBundled)
                incoming.forEach { cloudBook ->
                    val local = localById[cloudBook.id]
                    val localFile = local?.filePath?.let { File(it) }
                    val fileOk = localFile != null && localFile.exists() && localFile.length() > 0L
                    next += if (local != null) {
                        val keptId = local.driveFileId ?: cloudBook.driveFileId
                        cloudBook.copy(
                            filePath = if (fileOk) local.filePath else null,
                            remoteCover = local.remoteCover?.takeIf { !it.startsWith("http") && File(it).exists() }
                                ?: cloudBook.remoteCover ?: local.remoteCover,
                            driveFileId = keptId,
                            // Keep the confirmation only while it belongs to the id we still point
                            // at; a stamp for a different file is not evidence about this one.
                            backupAt = if (keptId == local.driveFileId) maxOf(local.backupAt, cloudBook.backupAt)
                            else cloudBook.backupAt,
                            pendingUpload = false,
                            backupFailed = local.backupFailed && !fileOk
                        )
                    } else {
                        // Remote-only: no file on this phone, and nothing may say there is.
                        cloudBook.copy(filePath = null, pendingUpload = false, backupFailed = false)
                    }
                }
                val ordered = ArrayList<LibraryBook>(next.size)
                val seen = HashSet<String>()
                snap.bookOrder.forEach { id ->
                    val b = next.firstOrNull { it.id == id } ?: return@forEach
                    if (seen.add(b.id)) ordered += b
                }
                next.forEach { if (seen.add(it.id)) ordered += it }
                books.clear()
                books.addAll(ordered)
                persistBooksUnlocked()
            }
            synchronized(bookmarksLock) {
                bookmarkRecords.clear()
                bookmarkRecords.addAll(snap.bookmarks)
                persistBookmarksUnlocked()
            }
            libraryAppearance = LibraryAppearance(snap.libraryAppearance.forCloud())
            readerAppearance = ReaderAppearance(snap.readerAppearance.forCloud())
            libraryAppearAt = snap.libraryAppearAt
            readerAppearAt = snap.readerAppearAt
            persistLook(libraryAppearFile, libraryAppearance.look, libraryAppearAt)
            persistLook(readerAppearFile, readerAppearance.look, readerAppearAt)
            if (snap.prefsAt >= prefsAt) {
                readingPrefs = snap.prefs
                prefsAt = snap.prefsAt
                prefs.edit().putString("theme", snap.prefs.theme.name)
                    .putString("readerTheme", snap.prefs.readerTheme.name)
                    .putString("fontId", snap.prefs.fontId)
                    .putFloat("fontSize", snap.prefs.fontSizeSp)
                    .putFloat("brightness", snap.prefs.brightness)
                    .putBoolean("swipe", snap.prefs.swipeMode)
                    .putBoolean("landscape", snap.prefs.landscape)
                    .putLong("prefsAt", prefsAt)
                    .apply()
            }
            snapshotAt = snap.updatedAt
            prefs.edit()
                .putLong("libraryAppearAt", libraryAppearAt)
                .putLong("readerAppearAt", readerAppearAt)
                .putLong("snapshotAt", snapshotAt)
                .apply()

            // The account deleted these; this phone now does the same. Only books a tombstone
            // names are touched — a book merely absent from a snapshot is left exactly where it
            // is, which is the difference between "deleted elsewhere" and "never synced here".
            if (blocked.isNotEmpty()) {
                blocked.forEach { book ->
                    book.filePath?.let { path -> runCatching { File(path).delete() } }
                    val driveId = book.driveFileId
                    if (!driveId.isNullOrBlank()) addPendingDelete(driveId)
                }
                synchronized(deletionsLock) {
                    saveDeletionsUnlocked(Deletions.prune(loadDeletionsUnlocked()))
                }
                toast(
                    if (blocked.size == 1) "“${blocked.first().title}” was deleted on another device"
                    else "${blocked.size} books were deleted on another device"
                )
            }
            refreshBackupAudit()
        } finally {
            applyingRemote = false
        }
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

    // ---------------------------------------------------------------------------------------
    // Deletions (tombstones)
    // ---------------------------------------------------------------------------------------

    /** Every deletion this install knows about, newest last. Cheap: one small file. */
    fun deletions(): List<Tombstone> = synchronized(deletionsLock) { loadDeletionsUnlocked() }

    /**
     * Records a deletion *before* anything else happens to the book.
     *
     * Order matters: the tombstone is the durable statement that the reader meant it. If the app
     * dies after this line, the deletion still syncs. If it died before, the book comes back —
     * annoying, but not data loss, and never the reverse.
     */
    private fun recordDeletion(book: LibraryBook) {
        val at = System.currentTimeMillis()
        synchronized(deletionsLock) {
            val list = loadDeletionsUnlocked().toMutableList()
            // Idempotent: deleting twice does not create two entries, and a repeated sync does not
            // keep pushing the timestamp forward.
            list.removeAll { it.bookId == book.id }
            list += Tombstone(book.id, maxOf(at, book.addedAt))
            saveDeletionsUnlocked(Deletions.prune(Deletions.merge(list, emptyList())))
        }
    }

    /** Adopts deletions that arrived from the account, so this device stops re-adding those books. */
    private fun mergeDeletions(incoming: List<Tombstone>) {
        if (incoming.isEmpty()) return
        synchronized(deletionsLock) {
            val merged = Deletions.prune(Deletions.merge(loadDeletionsUnlocked(), incoming))
            saveDeletionsUnlocked(merged)
        }
    }

    private fun loadDeletionsUnlocked(): List<Tombstone> {
        if (!deletionsFile.exists()) return emptyList()
        return runCatching {
            val o = JSONObject(deletionsFile.readText())
            // A deletion belongs to the account it was made in. Signing into a different account
            // must not delete that account's books.
            val owner = o.optString("account")
            if (owner.isNotBlank() && account.userId.isNotBlank() && owner != account.userId) {
                emptyList()
            } else {
                val arr = o.optJSONArray("books") ?: JSONArray()
                val out = ArrayList<Tombstone>(arr.length())
                for (i in 0 until arr.length()) {
                    val row = arr.optJSONObject(i) ?: continue
                    val id = row.optString("id")
                    if (id.isNotBlank()) out += Tombstone(id, row.optLong("at", 0L))
                }
                out
            }
        }.getOrDefault(emptyList())
    }

    private fun saveDeletionsUnlocked(list: List<Tombstone>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.bookId).put("at", it.deletedAt)) }
        runCatching {
            atomicWrite(
                deletionsFile,
                JSONObject().put("account", account.userId).put("books", arr).toString()
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Upload journal (restart recovery)
    // ---------------------------------------------------------------------------------------

    /** What was in flight when the app last stopped. */
    fun uploadJournal(): List<TransferProgress> = synchronized(journalLock) { loadJournalUnlocked() }

    private fun loadJournalUnlocked(): List<TransferProgress> {
        if (!uploadJournalFile.exists()) return emptyList()
        return TransferJournal.decode(runCatching { uploadJournalFile.readText() }.getOrNull())
    }

    /**
     * Records how far an upload has got.
     *
     * Throttled to one write every two seconds: the callback fires per socket chunk, and a phone's
     * flash storage should not be rewritten two thousand times for one book. The phase changes
     * (start, verify, fail) always write immediately — those are the states a restart must not
     * lose.
     */
    private fun journalProgress(bookId: String, sent: Long, total: Long, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val last = journalFlushedAt[bookId] ?: 0L
        if (!force && now - last < 2_000L) return
        journalFlushedAt[bookId] = now
        synchronized(journalLock) {
            val list = loadJournalUnlocked().toMutableList()
            list.removeAll { it.bookId == bookId }
            list += TransferProgress.paused(bookId, sent, total)
            saveJournalUnlocked(list)
        }
    }

    private fun journalDone(bookId: String) {
        journalFlushedAt.remove(bookId)
        synchronized(journalLock) {
            val list = loadJournalUnlocked()
            if (list.none { it.bookId == bookId }) return
            saveJournalUnlocked(list.filterNot { it.bookId == bookId })
        }
    }

    private fun saveJournalUnlocked(list: List<TransferProgress>) {
        if (list.isEmpty()) {
            runCatching { uploadJournalFile.delete() }
            return
        }
        runCatching { atomicWrite(uploadJournalFile, TransferJournal.encode(list.take(200))) }
    }

    /**
     * Settles what a previous run left behind, once the library is loaded.
     *
     * Three outcomes, decided by [Resumable] with no network: the book was already verified (drop
     * the entry), the file changed while the app was closed (send the current one), or the upload
     * was simply interrupted (show Paused and put it back in the queue).
     */
    private fun reconcileInterruptedUploads() {
        val journal = uploadJournal()
        if (journal.isEmpty()) return
        journal.forEach { entry ->
            val book = book(entry.bookId)
            val fileBytes = book?.filePath?.let { File(it) }?.takeIf { it.exists() }?.length() ?: 0L
            when (Resumable.decide(entry, book, fileBytes)) {
                ResumeDecision.NONE -> Unit
                ResumeDecision.ALREADY_DONE -> {
                    journalDone(entry.bookId)
                    TransferBus.clear(entry.bookId)
                }
                ResumeDecision.RESEND, ResumeDecision.RETRY -> {
                    // Truthful state: interrupted, not failed, and still owed.
                    TransferBus.paused(entry.bookId, entry.sentBytes, entry.totalBytes, "interrupted")
                    if (book != null) {
                        upsert(book.copy(pendingUpload = true))
                        noteTransferFailed(book, "upload was interrupted last time", retryable = true)
                    }
                }
            }
        }
        SyncDiag.mark(
            "Interrupted uploads",
            "${journal.size} from the previous run — restored as paused"
        )
    }

    /**
     * Invalidates confirmations whose file no longer matches.
     *
     * A stamp says "these exact bytes are in the account". If the file on the phone changed — a
     * re-export, a repaired EPUB — the stamp is no longer evidence about the current file, so the
     * book goes back to pending and the next sync re-uploads it. Nothing is deleted, and the
     * account's copy is never touched until the new bytes are verified.
     */
    private fun reconcileVerifications() {
        val stale = synchronized(libraryLock) {
            books.filter { b ->
                b.backupAt > 0L && b.backupBytes > 0L && b.localFileAvailable &&
                    BackupRules.changedSinceBackup(b, localFileExists = true)
            }
        }
        if (stale.isEmpty()) return
        stale.forEach { b ->
            upsert(b.copy(pendingUpload = true))
            noteTransferFailed(b, "the file changed since it was backed up", retryable = true)
        }
        SyncDiag.mark(
            "Backup check",
            "${stale.size} book(s) changed since their last verified upload — queued again"
        )
    }

    /**
     * Retries one book, on the reader's word.
     *
     * Used by the card's detail sheet. It goes through the same batch path as a sync, so the retry
     * behaves exactly like a scheduled one: same lookup by `loreId` (so no duplicate Drive file),
     * same checksum verification, same queue.
     */
    fun retryBook(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val book = book(bookId) ?: return@launch
            val file = book.filePath?.let(::File)
            if (file == null || !file.exists() || file.length() <= 0L) {
                withContext(Dispatchers.Main) {
                    toast("This book has no file on this phone to upload. Open it to restore the copy.")
                }
                return@launch
            }
            if (!networkReady()) {
                withContext(Dispatchers.Main) { toast("No connection — the book stays queued.") }
                return@launch
            }
            if (!account.signedIn || account.provider != "google") {
                withContext(Dispatchers.Main) { toast("Sign in to back this book up.") }
                return@launch
            }
            TransferBus.paused(bookId, 0L, file.length(), "retrying")
            SyncDiag.mark("Retry", "one book by request: ${book.title}")
            // No pass lock: UploadPool refuses to start a second transfer for a book that is
            // already uploading, which is the only conflict a manual retry can have.
            val batch = uploadBatch(listOf(book.copy(pendingUpload = true)))
            batch.failed.forEach { (failed, failure) ->
                SyncDiag.fail(
                    "Retry failed — ${failed.title.ifBlank { "Untitled" }} (${failed.id.take(8)})",
                    failure.reason + if (failure.retryable) " · still queued" else " · will not retry by itself"
                )
            }
            recordBatch(batch)
            if (batch.confirmed.isNotEmpty()) {
                withContext(Dispatchers.Main) { toast("“${book.title}” is backed up and verified") }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Local downloads: remove and restore
    // ---------------------------------------------------------------------------------------
    //
    // Three operations, and they must never be confused with each other:
    //
    //   Remove download   deletes the bytes on this phone. The library entry and the verified
    //                     cloud copy are untouched. This is the one implemented here.
    //   Restore download  fetches those bytes back from the account.
    //   Remove book       [remove]: deletes the entry, writes a tombstone and queues the remote
    //                     copy for deletion. Destructive, and deliberately a different method.
    //
    // Removal goes through [DownloadService], which has no way to reach the deletion code.

    /** Books whose download is being fetched back from the account right now. */
    var restoringIds by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Books whose local download is being removed right now. */
    var removingIds by mutableStateOf<Set<String>>(emptySet())
        private set

    private val downloadService = DownloadService(object : DownloadHost {
        override fun latest(bookId: String): LibraryBook? = book(bookId)

        override fun persist(book: LibraryBook): Boolean = commitDownloadRecord(book)

        override val booksDir: File get() = BookIo.booksDir(getApplication())

        override suspend fun accountHolds(book: LibraryBook): AccountLink {
            val expected = book.driveFileId ?: return AccountLink.MISSING
            if (!account.signedIn || account.provider != "google") return AccountLink.UNKNOWN
            if (!networkReady()) return AccountLink.UNKNOWN
            val token = driveToken() ?: return AccountLink.UNKNOWN
            return withContext(Dispatchers.IO) {
                when (val lookup = DriveLibrary.findByLoreId(token, book.id)) {
                    is LoreLookup.Found -> if (lookup.id == expected) AccountLink.MATCHED else AccountLink.MISSING
                    // The account answered, and does not have it: that is an answer, not a failure.
                    LoreLookup.NotFound -> AccountLink.MISSING
                    LoreLookup.Error -> AccountLink.UNKNOWN
                }
            }
        }

        override fun deleteLocal(file: File): Boolean =
            runCatching { file.delete() }.getOrDefault(false)

        override suspend fun fetchCloud(book: LibraryBook, dest: File): Boolean {
            val id = book.driveFileId ?: return false
            if (!networkReady()) return false
            val token = driveToken() ?: return false
            return withContext(Dispatchers.IO) {
                DriveLibrary.download(token, id, dest, book.format) is DriveOutcome.Ok
            }
        }

        override fun refreshAudit() {
            // Always on the main thread: the audit feeds Compose state, and these calls arrive
            // from an IO coroutine.
            viewModelScope.launch(Dispatchers.Main) { refreshBackupAudit() }
        }

        override fun note(message: String) {
            SyncDiag.mark("Download", message)
            viewModelScope.launch(Dispatchers.Main) { toast(message) }
        }
    })

    /**
     * Writes one book's record and proves it landed.
     *
     * The read-back is the point: removal deletes the reader's bytes on the strength of this
     * write, so "the file was probably saved" is not good enough. If the write cannot be confirmed
     * the in-memory record is put back the way it was, so what the shelf shows and what the disk
     * holds do not drift apart.
     */
    private fun commitDownloadRecord(book: LibraryBook): Boolean {
        val previous = synchronized(libraryLock) {
            val i = books.indexOfFirst { it.id == book.id }
            if (i < 0) return false
            val old = books[i]
            books[i] = book
            old
        }
        val wrote = runCatching { synchronized(libraryLock) { persistBooksList(books.toList()) } }.isSuccess
        val confirmed = wrote && runCatching {
            val arr = JSONArray(booksFile.readText())
            (0 until arr.length()).any { i ->
                val row = arr.optJSONObject(i) ?: return@any false
                row.optString("id") == book.id &&
                    row.optString("filePath") == book.filePath.orEmpty() &&
                    row.optString("driveFileId") == book.driveFileId.orEmpty()
            }
        }.getOrDefault(false)
        if (confirmed) {
            markLocalChanged()
            return true
        }
        // Put the record back: nothing was deleted, so nothing may claim otherwise.
        synchronized(libraryLock) {
            val i = books.indexOfFirst { it.id == previous.id }
            if (i >= 0) books[i] = previous
            runCatching { persistBooksList(books.toList()) }
        }
        return false
    }

    /** Why this book's download cannot be removed, or null when it can. For the UI. */
    fun downloadRemovalBlocker(book: LibraryBook): String? {
        val bytes = book.filePath?.let(::File)?.takeIf { it.exists() }?.length() ?: 0L
        return DownloadRules.removeBlocker(book, bytes)?.message
    }

    /** Why this book cannot be restored from the account, or null when it can. */
    fun downloadRestoreBlocker(book: LibraryBook): String? =
        DownloadRules.restoreBlocker(book)?.message

    /** True while a restore for this book is running, so the sheet can say "Restoring…". */
    fun isRestoring(bookId: String): Boolean = bookId in restoringIds

    /**
     * Removes a verified book's local bytes, keeping the library entry and the cloud copy.
     *
     * Only ever calls [DownloadService.removeDownload]. It never calls [remove], never writes a
     * tombstone and never queues a Drive deletion: that is the entire difference between "free up
     * space" and "delete my book".
     */
    fun removeDownload(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { removingIds = removingIds + bookId }
            val outcome = try {
                downloadService.removeDownload(bookId)
            } finally {
                withContext(Dispatchers.Main) { removingIds = removingIds - bookId }
            }
            withContext(Dispatchers.Main) {
                when (outcome) {
                    is RemoveOutcome.Removed, is RemoveOutcome.AlreadyRemoved -> Unit
                    is RemoveOutcome.Refused -> toast(outcome.blocker.message)
                    RemoveOutcome.MetadataFailed ->
                        toast("Could not update the library, so the download was kept.")
                    is RemoveOutcome.DeleteFailed -> toast(outcome.reason)
                    RemoveOutcome.NotFound -> toast("That book is no longer in your library.")
                }
                refreshBackupAudit()
            }
        }
    }

    /**
     * Fetches a removed book's bytes back from the account.
     *
     * Verified against the recorded size and checksum before it replaces anything, so a truncated
     * or wrong download can never take the place of a book the reader can open.
     */
    fun restoreDownload(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { restoringIds = restoringIds + bookId }
            val outcome = try {
                downloadService.restoreDownload(bookId)
            } finally {
                withContext(Dispatchers.Main) { restoringIds = restoringIds - bookId }
            }
            withContext(Dispatchers.Main) {
                when (outcome) {
                    is RestoreOutcome.Restored -> Unit
                    is RestoreOutcome.AlreadyAvailable -> toast("This book is already on this phone.")
                    is RestoreOutcome.Refused -> toast(outcome.blocker.message)
                    is RestoreOutcome.Failed -> toast("Could not restore this book: ${outcome.reason}")
                    RestoreOutcome.NotFound -> toast("That book is no longer in your library.")
                }
                refreshBackupAudit()
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Transfer queue
    // ---------------------------------------------------------------------------------------

    /** One book that owes the account a file, and why it still does. */
    data class PendingTransfer(
        val bookId: String,
        val title: String,
        val attempts: Int,
        val lastTryAt: Long,
        val reason: String,
        val retryable: Boolean
    )

    fun pendingTransfers(): List<PendingTransfer> = synchronized(queueLock) { loadQueueUnlocked() }

    /** Recomputes [uploadQueueReasons] from the persisted queue. */
    private fun refreshQueueReasons() {
        uploadQueueReasons = pendingTransfers().associate { it.bookId to it.reason }
    }

    private fun noteTransferFailed(book: LibraryBook, reason: String, retryable: Boolean) {
        synchronized(queueLock) {
            val list = loadQueueUnlocked().toMutableList()
            val prev = list.firstOrNull { it.bookId == book.id }
            list.removeAll { it.bookId == book.id }
            list += PendingTransfer(
                bookId = book.id,
                title = book.title,
                attempts = (prev?.attempts ?: 0) + 1,
                lastTryAt = System.currentTimeMillis(),
                reason = reason,
                retryable = retryable
            )
            saveQueueUnlocked(list)
        }
        refreshQueueReasons()
        refreshBackupAudit()
    }

    private fun clearTransfer(bookId: String) {
        synchronized(queueLock) {
            val list = loadQueueUnlocked()
            if (list.none { it.bookId == bookId }) return
            saveQueueUnlocked(list.filterNot { it.bookId == bookId })
        }
        refreshQueueReasons()
    }

    private fun loadQueueUnlocked(): List<PendingTransfer> {
        if (!uploadQueueFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(uploadQueueFile.readText())
            val out = ArrayList<PendingTransfer>(arr.length())
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                val id = row.optString("id")
                if (id.isBlank()) continue
                out += PendingTransfer(
                    bookId = id,
                    title = row.optString("title"),
                    attempts = row.optInt("attempts", 0),
                    lastTryAt = row.optLong("at", 0L),
                    reason = row.optString("reason"),
                    retryable = row.optBoolean("retryable", true)
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun saveQueueUnlocked(list: List<PendingTransfer>) {
        if (list.isEmpty()) {
            runCatching { uploadQueueFile.delete() }
            return
        }
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.bookId)
                    .put("title", t.title)
                    .put("attempts", t.attempts)
                    .put("at", t.lastTryAt)
                    .put("reason", t.reason)
                    .put("retryable", t.retryable)
            )
        }
        runCatching { atomicWrite(uploadQueueFile, arr.toString()) }
    }

    // ---------------------------------------------------------------------------------------
    // Backup audit
    // ---------------------------------------------------------------------------------------

    /** Recomputes the counts the summary line and the diagnostics panel show. */
    fun refreshBackupAudit() {
        if (uploadQueueReasons.isEmpty() && pendingTransfers().isNotEmpty()) refreshQueueReasons()
        val books = synchronized(libraryLock) { books.toList() }
            .filter { it.origin != BookOrigin.BUNDLED && it.format != BookFormat.TEXT }
        // Live transfers (including paused ones) come from the bus; the batch flag is only a hint.
        backupCounts = BackupAudit.counts(books, uploadingIds + TransferBus.activeIds())
    }

    /** "2 verified, 1 pending, 1 failed" — counted from files, never from metadata. */
    fun backupSummary(): String = backupCounts.summary()

    /** What to tell the reader about one book, right now. */
    fun backupBadge(book: LibraryBook): BackupBadge = BackupAudit.badge(
        book,
        TransferBus.progressFor(book.id),
        online = networkReady(),
        queued = pendingTransfers().any { it.bookId == book.id }
    )

    /**
     * The per-book backup table for the diagnosis, one line per book that is not verified.
     *
     * Title first, because that is what the reader recognises; the id is a short stable handle and
     * contains nothing personal. No tokens, no file contents, no account email.
     */
    fun backupAuditLines(): List<String> = backupLines(onlyProblems = false)

    /** Just the books that are *not* verified — the list the You screen shows. */
    fun backupProblemLines(): List<String> = backupLines(onlyProblems = true)

    private fun backupLines(onlyProblems: Boolean): List<String> {
        val queued = pendingTransfers().associateBy { it.bookId }
        val books = synchronized(libraryLock) { books.toList() }
            .filter { it.origin != BookOrigin.BUNDLED && it.format != BookFormat.TEXT }
        return books.mapNotNull { book ->
            val transfer = TransferBus.progressFor(book.id)
            val badge = BackupAudit.badge(
                book, transfer, online = networkReady(), queued = queued.containsKey(book.id)
            )
            if (onlyProblems && badge == BackupBadge.VERIFIED) return@mapNotNull null
            val queue = queued[book.id]
            buildString {
                append(BackupAudit.label(badge))
                append(" · ").append(book.title.ifBlank { "Untitled" })
                append(" · ").append(book.format.name.lowercase())
                append(" · id=").append(book.id.take(12))
                if (book.driveFileId != null) append(" · drive=").append(book.driveFileId.take(8))
                if (book.backupBytes > 0L) append(" · ").append(book.backupBytes / 1024).append(" KB verified")
                if (transfer != null) {
                    append(" · ").append(transfer.phase.name.lowercase())
                    append(" ").append(Transfers.transferred(transfer.sentBytes, transfer.totalBytes))
                    transfer.percent?.let { append(" (").append(it).append("%)") }
                }
                if (queue != null) {
                    append(" · ").append(queue.attempts).append(" attempt(s), last tried ")
                    append(android.text.format.DateFormat.format("HH:mm:ss", queue.lastTryAt))
                    if (queue.reason.isNotBlank()) append(" — ").append(queue.reason)
                    append(if (queue.retryable) "" else " (will not be retried automatically)")
                }
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

    /**
     * The books that used to ship with the app are gone, and existing copies are cleared out.
     *
     * They were starter titles copied out of the APK on first launch, so they are not the
     * reader's books, they cannot be backed up, and — never opened — they filled Want to read with
     * three titles nobody chose. The shelf holds what the reader added: imported files,
     * downloads from Explore, and what came back from their account.
     */
    private fun maybeSeedStartersUnlocked(target: MutableList<LibraryBook>) {
        val removed = target.filter { book ->
            book.id.startsWith("starter_") || BundledBooks.seedIds.contains(book.id)
        }
        if (removed.isNotEmpty()) {
            val ids = removed.map { it.id }.toSet()
            target.removeAll { it.id in ids }
            // The copies in app storage go too: they were unpacked from the APK, so nothing of
            // the reader's is lost, and leaving them behind would just waste space.
            removed.forEach { book ->
                book.filePath?.let { runCatching { File(it).delete() } }
            }
        }
        prefs.edit().putBoolean("starter_seeded", true).apply()
    }

    /** Retained as a no-op: the bundled PDF is removed with the rest of the starter books. */
    private fun maybeSeedStarterPdfUnlocked(target: MutableList<LibraryBook>) {
        prefs.edit().putBoolean("starter_pdf_v1", true).apply()
        target.removeAll { it.id.startsWith("starter_") }
    }

    private fun addStarterUnlocked(app: Application, spec: StarterSpec, target: MutableList<LibraryBook>) {
        if (target.any { it.id == spec.id }) return
        val ext = if (spec.format == BookFormat.PDF) "pdf" else "epub"
        val dest = File(BookIo.booksDir(app), "${spec.id}.$ext")
        val coverDest = File(BookIo.coversDir(app), "${spec.id}.jpg")
        val copied = runCatching {
            app.assets.open(spec.asset).use { inp ->
                dest.parentFile?.mkdirs()
                dest.outputStream().use { inp.copyTo(it) }
            }
            dest.exists() && dest.length() > 200L
        }.getOrDefault(false)
        if (!copied) {
            runCatching { dest.delete() }
            return
        }
        val coverOk = writeStarterCover(app, spec.coverRes, coverDest)
        target.add(
            0,
            LibraryBook(
                id = spec.id,
                title = spec.title,
                author = spec.author,
                remoteCover = if (coverOk) coverDest.absolutePath else null,
                format = spec.format,
                origin = BookOrigin.IMPORT,
                filePath = dest.absolutePath,
                pageCount = 1,
                currentPage = 0,
                lastReadAt = 0L,
                category = spec.category,
                synopsis = spec.synopsis,
                pendingUpload = false
            )
        )
    }

    private fun maybeRefreshStarterCoversUnlocked(target: MutableList<LibraryBook>) {
        if (prefs.getBoolean("starter_covers_pd", false)) return
        prefs.edit().putBoolean("starter_covers_pd", true).apply()
        val app = getApplication<Application>()
        BundledBooks.starter.forEach { spec ->
            val i = target.indexOfFirst { it.id == spec.id }
            if (i < 0) return@forEach
            val coverDest = File(BookIo.coversDir(app), "${spec.id}.jpg")
            if (!writeStarterCover(app, spec.coverRes, coverDest)) return@forEach
            target[i] = target[i].copy(remoteCover = coverDest.absolutePath)
        }
    }

    private fun writeStarterCover(app: Application, resId: Int, dest: File): Boolean {
        return runCatching {
            val bmp = BitmapFactory.decodeResource(app.resources, resId) ?: return@runCatching false
            dest.parentFile?.mkdirs()
            dest.outputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            if (!bmp.isRecycled) bmp.recycle()
            dest.exists() && dest.length() > 40L
        }.getOrDefault(false)
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
                    o.optString("driveFileId").ifBlank { null }, o.optBoolean("pendingUpload", false),
                    o.optBoolean("dnf", false), o.optLong("backupAt", 0L), o.optBoolean("backupFailed", false),
                    o.optLong("backupBytes", 0L), backupMd5 = o.optString("backupMd5")
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
                    .put("backupAt", b.backupAt).put("backupFailed", b.backupFailed).put("dnf", b.dnf)
                    .put("backupBytes", b.backupBytes)
                    // Read back with optString, so an older library.json without this key loads
                    // as "no checksum recorded" rather than failing.
                    .put("backupMd5", b.backupMd5)
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
        val excluded = { book: DiscoveryBook -> book.volumeId in notInterestedIds || drop(book) }
        if (forYou.any(excluded)) forYou = forYou.filterNot(excluded)
        Recommendations.excludeFromCache(getApplication(), excluded)
    }
    private fun persistWantToRead() {
        val arr = JSONArray()
        wantToRead.forEach { arr.put(Recommendations.toJson(it)) }
        runCatching { wantFile.writeText(arr.toString()) }
    }

    private fun readBookmarksFile(): List<SavedBookmark> {
        if (!bookmarksFile.exists()) return emptyList()
        val out = ArrayList<SavedBookmark>()
        runCatching {
            val arr = JSONArray(bookmarksFile.readText())
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                val bookId = row.optString("bookId")
                if (bookId.isBlank()) continue
                out += SavedBookmark(
                    bookId = bookId,
                    pageIndex = row.optInt("pageIndex", 0).coerceAtLeast(0),
                    updatedAt = row.optLong("updatedAt", 0L)
                )
            }
        }
        return out
    }

    private fun persistBookmarksUnlocked() {
        val arr = JSONArray()
        bookmarkRecords.forEach { m ->
            arr.put(
                JSONObject()
                    .put("bookId", m.bookId)
                    .put("pageIndex", m.pageIndex)
                    .put("updatedAt", m.updatedAt)
            )
        }
        runCatching { atomicWrite(bookmarksFile, arr.toString()) }
    }
}
