package com.lantern.library.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedBookmark(
    val bookId: String,
    val pageIndex: Int,
    val updatedAt: Long = System.currentTimeMillis()
)

data class AccountSnapshot(
    val updatedAt: Long,
    val userId: String,
    val books: List<LibraryBook>,
    val bookmarks: List<SavedBookmark>,
    val libraryAppearance: AppearLook,
    val readerAppearance: AppearLook,
    val prefs: ReadingPrefs,
    val libraryAppearAt: Long,
    val readerAppearAt: Long,
    val prefsAt: Long,
    val bookOrder: List<String>,
    /**
     * Books the reader deleted on purpose, with the time of the deletion.
     *
     * Part of the snapshot because a deletion is a user action that has to travel between devices;
     * without it the merge can only ever be a union, and a union puts deleted books back.
     */
    val deletions: List<Tombstone> = emptyList(),
    /** Saved-for-later discoveries are account data, not a device-only list. */
    val wantToRead: List<DiscoveryBook> = emptyList(),
    val wantToReadDeletedAt: Map<String, Long> = emptyMap()
)

object AccountSync {
    /**
     * Snapshot format.
     *
     * 1 — books, bookmarks, appearance, prefs.
     * 2 — adds `deletions` (tombstones) and a per-book `backupBytes` (the size that was verified).
     * 3 — adds saved-for-later discoveries (`wantToRead`).
     * 4 — adds saved-for-later deletion timestamps so removals sync without resurrection.
     * 5 — adds independent Drive cover-file references, without changing book-file confirmation.
     *     Older snapshots remain readable; missing cover ids simply mean no cloud cover asset.
     */
    const val SCHEMA = 5
    const val STATE_LORE_ID = "account_state"
    const val STATE_FILE_NAME = "lore_account.json"

    fun toJson(snap: AccountSnapshot): String {
        val books = JSONArray()
        snap.books.filter { it.origin != BookOrigin.BUNDLED }.forEach { b ->
            books.put(bookToCloud(b))
        }
        val marks = JSONArray()
        snap.bookmarks.forEach { m ->
            marks.put(
                JSONObject()
                    .put("bookId", m.bookId)
                    .put("pageIndex", m.pageIndex)
                    .put("updatedAt", m.updatedAt)
            )
        }
        val order = JSONArray()
        snap.bookOrder.forEach { order.put(it) }
        val saved = JSONArray()
        snap.wantToRead.forEach { saved.put(Recommendations.toJson(it)) }
        val savedDeleted = JSONObject()
        snap.wantToReadDeletedAt.forEach { (id, at) -> if (id.isNotBlank()) savedDeleted.put(id, at) }
        val deleted = JSONArray()
        Deletions.prune(snap.deletions).forEach { t ->
            deleted.put(JSONObject().put("id", t.bookId).put("at", t.deletedAt))
        }
        return JSONObject()
            .put("schema", SCHEMA)
            .put("deletions", deleted)
            .put("updatedAt", snap.updatedAt)
            .put("accountId", snap.userId)
            .put("books", books)
            .put("bookmarks", marks)
            .put("wantToRead", saved)
            .put("wantToReadDeletedAt", savedDeleted)
            .put("bookOrder", order)
            .put("libraryAppearance", snap.libraryAppearance.forCloud().toJson().put("updatedAt", snap.libraryAppearAt))
            .put("readerAppearance", snap.readerAppearance.forCloud().toJson().put("updatedAt", snap.readerAppearAt))
            .put("prefs", prefsToJson(snap.prefs).put("updatedAt", snap.prefsAt))
            .toString()
    }

    fun fromJson(raw: String?): AccountSnapshot? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(raw)
            val booksArr = o.optJSONArray("books") ?: JSONArray()
            val books = ArrayList<LibraryBook>(booksArr.length())
            for (i in 0 until booksArr.length()) {
                val row = booksArr.optJSONObject(i) ?: continue
                bookFromCloud(row)?.let { books += it }
            }
            val markArr = o.optJSONArray("bookmarks") ?: JSONArray()
            val marks = ArrayList<SavedBookmark>(markArr.length())
            for (i in 0 until markArr.length()) {
                val row = markArr.optJSONObject(i) ?: continue
                val bookId = row.optString("bookId")
                if (bookId.isBlank()) continue
                marks += SavedBookmark(
                    bookId = bookId,
                    pageIndex = row.optInt("pageIndex", 0).coerceAtLeast(0),
                    updatedAt = row.optLong("updatedAt", 0L)
                )
            }
            val orderArr = o.optJSONArray("bookOrder") ?: JSONArray()
            val order = ArrayList<String>(orderArr.length())
            for (i in 0 until orderArr.length()) {
                val id = orderArr.optString(i)
                if (id.isNotBlank()) order += id
            }
            val savedArr = o.optJSONArray("wantToRead") ?: JSONArray()
            val saved = ArrayList<DiscoveryBook>(savedArr.length())
            for (i in 0 until savedArr.length()) {
                val row = savedArr.optJSONObject(i) ?: continue
                Recommendations.parseBook(row)?.let { saved += it }
            }
            val savedDeletedObj = o.optJSONObject("wantToReadDeletedAt") ?: JSONObject()
            val savedDeleted = LinkedHashMap<String, Long>()
            val deletedKeys = savedDeletedObj.keys()
            while (deletedKeys.hasNext()) {
                val id = deletedKeys.next()
                val at = savedDeletedObj.optLong(id, 0L)
                if (id.isNotBlank() && at > 0L) savedDeleted[id] = at
            }
            val delArr = o.optJSONArray("deletions") ?: JSONArray()
            val deletions = ArrayList<Tombstone>(delArr.length())
            for (i in 0 until delArr.length()) {
                val row = delArr.optJSONObject(i) ?: continue
                val id = row.optString("id")
                if (id.isBlank()) continue
                deletions += Tombstone(id, row.optLong("at", 0L))
            }
            val libObj = o.optJSONObject("libraryAppearance")
            val readObj = o.optJSONObject("readerAppearance")
            val prefsObj = o.optJSONObject("prefs")
            AccountSnapshot(
                updatedAt = o.optLong("updatedAt", 0L),
                userId = o.optString("accountId").ifBlank { o.optString("userId") }.let { id ->
                    if (id.isBlank() || '@' in id) "" else id
                },
                books = books,
                bookmarks = marks,
                libraryAppearance = if (libObj != null) appearLookFromObj(libObj, AppearLook.libraryDefault) else AppearLook.libraryDefault,
                readerAppearance = if (readObj != null) appearLookFromObj(readObj, AppearLook.readerDefault) else AppearLook.readerDefault,
                prefs = if (prefsObj != null) prefsFromJson(prefsObj) else ReadingPrefs(),
                libraryAppearAt = libObj?.optLong("updatedAt", 0L) ?: 0L,
                readerAppearAt = readObj?.optLong("updatedAt", 0L) ?: 0L,
                prefsAt = prefsObj?.optLong("updatedAt", 0L) ?: 0L,
                bookOrder = order,
                deletions = Deletions.prune(deletions),
                wantToRead = saved,
                wantToReadDeletedAt = savedDeleted
            )
        }.getOrNull()
    }

    fun merge(local: AccountSnapshot, cloud: AccountSnapshot?): AccountSnapshot {
        if (cloud == null) {
            val stones = Deletions.prune(local.deletions)
            return local.copy(
                updatedAt = maxOf(local.updatedAt, System.currentTimeMillis()),
                books = Deletions.alive(local.books, stones),
                deletions = stones,
                wantToRead = local.wantToRead
            )
        }
        // A deletion is a statement, not an absence. Both sides' statements are kept, the newest
        // wins per book, and the books they name are removed from the merged library — which is
        // how a book deleted on the phone stops coming back from the account on every sync.
        val stones = Deletions.prune(Deletions.merge(local.deletions, cloud.deletions))
        val localById = local.books.associateBy { it.id }
        val cloudById = cloud.books.associateBy { it.id }
        val ids = LinkedHashSet<String>()
        mergeOrder(local.bookOrder, cloud.bookOrder, local.books, cloud.books).forEach { ids += it }
        localById.keys.forEach { ids += it }
        cloudById.keys.forEach { ids += it }
        val mergedBooks = ArrayList<LibraryBook>(ids.size)
        for (id in ids) {
            val l = localById[id]
            val c = cloudById[id]
            val deletedHere = l != null && Deletions.blocks(l, stones)
            val deletedThere = c != null && Deletions.blocks(c, stones)
            if (deletedHere || deletedThere) continue
            when {
                // Remote-only: the phone has no file, so nothing may claim it does. The Drive id
                // and the confirmed-backup stamp are what make this book restorable, so they are
                // carried over exactly as the cloud recorded them.
                l == null && c != null -> mergedBooks += c.copy(
                    filePath = null,
                    pendingUpload = false,
                    backupFailed = false,
                    // The cloud's own confirmation, never inferred from reading progress: a stamp
                    // invented here would tell the reader a book is safe when it never was.
                    backupAt = if (c.driveFileId.isNullOrBlank()) 0L else c.backupAt
                )
                l != null && c == null -> mergedBooks += l
                l != null && c != null -> mergedBooks += mergeBook(l, c)
            }
        }
        // A deleted book takes its bookmarks with it. Only books that are actually gone: a book
        // the reader deleted and then imported again keeps its new marks.
        val dead = Deletions.ids(stones) - mergedBooks.map { it.id }.toSet()
        val marks = LinkedHashMap<String, SavedBookmark>()
        (cloud.bookmarks + local.bookmarks).forEach { m ->
            if (m.bookId in dead) return@forEach
            val key = "${m.bookId}\u0000${m.pageIndex}"
            val prev = marks[key]
            if (prev == null || m.updatedAt >= prev.updatedAt) marks[key] = m
        }
        val savedDeleted = LinkedHashMap<String, Long>()
        (cloud.wantToReadDeletedAt + local.wantToReadDeletedAt).forEach { (id, at) ->
            savedDeleted[id] = maxOf(savedDeleted[id] ?: 0L, at)
        }
        val savedById = LinkedHashMap<String, DiscoveryBook>()
        (cloud.wantToRead + local.wantToRead).forEach { pick ->
            val previous = savedById[pick.volumeId]
            if (previous == null || pick.savedAt >= previous.savedAt) savedById[pick.volumeId] = pick
        }
        val libAt = if (local.libraryAppearAt >= cloud.libraryAppearAt) local.libraryAppearAt else cloud.libraryAppearAt
        val readAt = if (local.readerAppearAt >= cloud.readerAppearAt) local.readerAppearAt else cloud.readerAppearAt
        val prefsAt = if (local.prefsAt >= cloud.prefsAt) local.prefsAt else cloud.prefsAt
        return AccountSnapshot(
            updatedAt = maxOf(local.updatedAt, cloud.updatedAt, System.currentTimeMillis()),
            userId = listOf(local.userId, cloud.userId).firstOrNull { it.isNotBlank() && '@' !in it }.orEmpty(),
            books = mergedBooks,
            bookmarks = marks.values.toList(),
            libraryAppearance = if (local.libraryAppearAt >= cloud.libraryAppearAt) local.libraryAppearance else cloud.libraryAppearance,
            readerAppearance = if (local.readerAppearAt >= cloud.readerAppearAt) local.readerAppearance else cloud.readerAppearance,
            prefs = if (local.prefsAt >= cloud.prefsAt) local.prefs else cloud.prefs,
            libraryAppearAt = libAt,
            readerAppearAt = readAt,
            prefsAt = prefsAt,
            bookOrder = mergedBooks.map { it.id },
            deletions = stones,
            wantToRead = savedById.values.filter { it.savedAt > (savedDeleted[it.volumeId] ?: 0L) }.sortedByDescending { it.savedAt },
            wantToReadDeletedAt = savedDeleted
        )
    }

    private fun mergeOrder(
        localOrder: List<String>,
        cloudOrder: List<String>,
        localBooks: List<LibraryBook>,
        cloudBooks: List<LibraryBook>
    ): List<String> {
        val localUser = localBooks.any { it.origin != BookOrigin.BUNDLED }
        val source = if (!localUser && cloudOrder.isNotEmpty()) cloudOrder else localOrder
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        fun add(id: String) {
            if (id.isBlank() || id in seen) return
            seen += id
            out += id
        }
        source.forEach { add(it) }
        (if (source === localOrder) cloudOrder else localOrder).forEach { add(it) }
        localBooks.forEach { add(it.id) }
        cloudBooks.forEach { add(it.id) }
        return out
    }

    private fun mergeBook(local: LibraryBook, cloud: LibraryBook): LibraryBook {
        val localFile = local.filePath?.let { File(it) }
        val fileOk = localFile != null && localFile.exists() && localFile.length() > 0L
        val progress = pickProgress(local, cloud)
        val localCover = local.remoteCover
        val cover = when {
            !localCover.isNullOrBlank() && (localCover.startsWith("http") || File(localCover).exists()) -> localCover
            !cloud.remoteCover.isNullOrBlank() && cloud.remoteCover.startsWith("http") -> cloud.remoteCover
            else -> localCover ?: cloud.remoteCover
        }
        // A Drive id is not proof by itself. When the two devices disagree, keep the reference
        // with an actual confirmation; otherwise a stale local id can hide a verified cloud copy
        // and make every sign-in look like a fresh upload. If both are confirmed, the newest
        // confirmation identifies the current account copy. Local wins only when neither side
        // has a confirmed copy, preserving an in-progress local upload reference.
        val localHasConfirmedCopy =
            !local.driveFileId.isNullOrBlank() && local.backupAt > 0L &&
                !local.pendingUpload && !local.backupFailed
        val cloudHasConfirmedCopy =
            !cloud.driveFileId.isNullOrBlank() && cloud.backupAt > 0L &&
                !cloud.pendingUpload && !cloud.backupFailed
        val keepLocalDriveReference = when {
            localHasConfirmedCopy && cloudHasConfirmedCopy -> local.backupAt >= cloud.backupAt
            localHasConfirmedCopy -> true
            cloudHasConfirmedCopy -> false
            !local.driveFileId.isNullOrBlank() -> true
            else -> false
        }
        val keptId = if (keepLocalDriveReference) local.driveFileId else cloud.driveFileId
        val backupSource = when {
            keptId.isNullOrBlank() -> null
            local.driveFileId == keptId && cloud.driveFileId == keptId ->
                if (local.backupAt >= cloud.backupAt) local else cloud
            local.driveFileId == keptId -> local
            cloud.driveFileId == keptId -> cloud
            else -> null
        }
        return local.copy(
            title = if (fileOk || local.title.isNotBlank()) local.title.ifBlank { cloud.title } else cloud.title,
            author = if (fileOk) local.author.ifBlank { cloud.author } else local.author.ifBlank { cloud.author },
            remoteCover = cover,
            format = if (fileOk) local.format else local.format.takeIf { it != BookFormat.TEXT } ?: cloud.format,
            origin = if (local.origin == BookOrigin.BUNDLED) BookOrigin.BUNDLED else local.origin.takeIf { it != BookOrigin.BUNDLED } ?: cloud.origin,
            filePath = if (fileOk) local.filePath else null,
            pageCount = progress.pageCount,
            currentPage = progress.currentPage,
            // Finished is a latch on both sides, so it survives a merge even when the other
            // device has a later reading position. A book the reader finished does not quietly
            // fall back into Reading because they skimmed back a few pages on their phone.
            finished = local.finished || cloud.finished || progress.finished,
            addedAt = minOf(local.addedAt.takeIf { it > 0L } ?: cloud.addedAt, cloud.addedAt.takeIf { it > 0L } ?: local.addedAt),
            lastReadAt = progress.lastReadAt,
            category = local.category.ifBlank { cloud.category },
            synopsis = local.synopsis.ifBlank { cloud.synopsis },
            driveFileId = keptId,
            driveCoverFileId = local.driveCoverFileId ?: cloud.driveCoverFileId,
            // An explicit retention choice must travel across devices. Prefer this device's
            // explicit choice when both sides have one; otherwise inherit the other side's.
            keepOffline = when {
                local.keepOfflineExplicit -> local.keepOffline
                cloud.keepOfflineExplicit -> cloud.keepOffline
                else -> local.keepOffline || cloud.keepOffline
            },
            keepOfflineExplicit = local.keepOfflineExplicit || cloud.keepOfflineExplicit,
            // A confirmation is only meaningful for the file id it was issued against. If the two
            // sides point at the same file, either side's stamp counts; if only one side has an
            // id, only that side's stamp is evidence.
            backupAt = backupSource?.backupAt ?: 0L,
            pendingUpload = if ((backupSource?.backupAt ?: 0L) > 0L) false else local.pendingUpload || cloud.pendingUpload,
            backupFailed = if ((backupSource?.backupAt ?: 0L) > 0L) false else local.backupFailed || cloud.backupFailed,
            dnf = local.dnf || cloud.dnf,
            // The size is evidence about the same bytes as the stamp: take it from whichever side
            // the stamp came from, never from the other one.
            backupBytes = backupSource?.backupBytes ?: 0L,
            // The checksum belongs to the same bytes as the stamp and size; never mix evidence
            // from two different Drive files.
            backupMd5 = backupSource?.backupMd5.orEmpty()
        )
    }

    private data class ProgressPick(
        val currentPage: Int,
        val pageCount: Int,
        val lastReadAt: Long,
        val finished: Boolean
    )

    private fun pickProgress(local: LibraryBook, cloud: LibraryBook): ProgressPick {
        val lRead = local.lastReadAt
        val cRead = cloud.lastReadAt
        val lPage = local.currentPage.coerceAtLeast(0)
        val cPage = cloud.currentPage.coerceAtLeast(0)
        if (cRead <= 0L && (lRead > 0L || lPage > 0)) {
            return ProgressPick(lPage, local.pageCount.coerceAtLeast(cloud.pageCount).coerceAtLeast(1), lRead, local.finished)
        }
        if (lRead <= 0L && lPage <= 0 && (cRead > 0L || cPage > 0)) {
            return ProgressPick(cPage, cloud.pageCount.coerceAtLeast(local.pageCount).coerceAtLeast(1), cRead, cloud.finished)
        }
        return if (lRead >= cRead) {
            ProgressPick(lPage, local.pageCount.coerceAtLeast(1), lRead, local.finished)
        } else {
            ProgressPick(cPage, cloud.pageCount.coerceAtLeast(1), cRead, cloud.finished)
        }
    }

    private fun bookToCloud(b: LibraryBook): JSONObject {
        val cover = b.remoteCover.orEmpty().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "" }
        return JSONObject()
            .put("id", b.id)
            .put("title", b.title)
            .put("author", b.author)
            .put("format", b.format.name)
            .put("origin", b.origin.name)
            .put("pageCount", b.pageCount)
            .put("currentPage", b.currentPage)
            .put("finished", b.finished)
            .put("addedAt", b.addedAt)
            .put("lastReadAt", b.lastReadAt)
            .put("category", b.category)
            .put("synopsis", b.synopsis)
            .put("remoteCover", cover)
            // The Drive file id is what lets a fresh install fetch the book itself, so the
            // phone never has to keep every file locally. `backupAt` says whether the contents
            // behind that id were ever confirmed, which is a different question.
            .put("driveFileId", b.driveFileId.orEmpty())
            .put("driveCoverFileId", b.driveCoverFileId.orEmpty())
            .put("backupAt", b.backupAt)
            // The size the confirmation was issued against, so another device can tell whether a
            // local file is the one that was verified.
            .put("backupBytes", b.backupBytes)
            // The checksum travels with the size it describes, for the same reason: it is evidence
            // about one specific set of bytes, and a restore on another device can use it.
            .put("backupMd5", b.backupMd5)
            .put("keepOffline", b.keepOffline)
            .put("keepOfflineExplicit", b.keepOfflineExplicit)
            // The reader's own decisions about a book travel with it: a book they finished or
            // gave up on should look the same on their next phone.
            .put("dnf", b.dnf)
    }

    private fun bookFromCloud(o: JSONObject): LibraryBook? {
        val id = o.optString("id")
        if (id.isBlank() || id in BundledBooks.seedIds) return null
        val origin = runCatching {
            BookOrigin.valueOf(o.optString("origin", "IMPORT"))
        }.getOrDefault(BookOrigin.IMPORT).let { if (it == BookOrigin.BUNDLED) BookOrigin.IMPORT else it }
        val cover = o.optString("remoteCover").ifBlank { null }?.takeIf { it.startsWith("http") }
        return LibraryBook(
            id = id,
            title = o.optString("title").ifBlank { "Untitled" },
            author = o.optString("author"),
            remoteCover = cover,
            format = runCatching { BookFormat.valueOf(o.optString("format", "EPUB")) }.getOrDefault(BookFormat.EPUB),
            origin = origin,
            filePath = null,
            remoteEpub = null,
            pageCount = o.optInt("pageCount", 1).coerceAtLeast(1),
            currentPage = o.optInt("currentPage", 0).coerceAtLeast(0),
            finished = o.optBoolean("finished", false),
            addedAt = o.optLong("addedAt", System.currentTimeMillis()),
            lastReadAt = o.optLong("lastReadAt", 0L),
            category = o.optString("category", "Library"),
            synopsis = o.optString("synopsis"),
            driveFileId = o.optString("driveFileId").ifBlank { null },
            driveCoverFileId = o.optString("driveCoverFileId").ifBlank { null },
            pendingUpload = false,
            backupAt = o.optLong("backupAt", 0L),
            dnf = o.optBoolean("dnf", false),
            backupBytes = o.optLong("backupBytes", 0L),
            backupMd5 = o.optString("backupMd5"),
            keepOffline = o.optBoolean("keepOffline", false),
            keepOfflineExplicit = o.optBoolean("keepOfflineExplicit", false)
        )
    }

    private fun prefsToJson(p: ReadingPrefs): JSONObject = JSONObject()
        .put("theme", p.theme.name)
        .put("readerTheme", p.readerTheme.name)
        .put("fontId", p.fontId)
        .put("fontSizeSp", p.fontSizeSp.toDouble())
        .put("brightness", p.brightness.toDouble())
        .put("swipeMode", p.swipeMode)
        .put("landscape", p.landscape)

    private fun prefsFromJson(o: JSONObject): ReadingPrefs {
        fun theme(key: String, fallback: ReaderTheme): ReaderTheme {
            val n = o.optString(key)
            return if (n == "DARK") ReaderTheme.DARK else if (n == "LIGHT") ReaderTheme.LIGHT else fallback
        }
        val theme = theme("theme", ReaderTheme.LIGHT)
        return ReadingPrefs(
            theme = theme,
            readerTheme = theme("readerTheme", theme),
            fontId = o.optString("fontId", "times").ifBlank { "times" },
            fontSizeSp = o.optDouble("fontSizeSp", 17.0).toFloat().let { if (it.isFinite()) it.coerceIn(13f, 28f) else 17f },
            brightness = o.optDouble("brightness", 0.85).toFloat().let { if (it.isFinite()) it.coerceIn(0.12f, 1f) else 0.85f },
            swipeMode = o.optBoolean("swipeMode", true),
            landscape = o.optBoolean("landscape", false)
        )
    }
}
