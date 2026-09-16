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
    val bookOrder: List<String>
)

object AccountSync {
    const val SCHEMA = 1
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
        return JSONObject()
            .put("schema", SCHEMA)
            .put("updatedAt", snap.updatedAt)
            .put("accountId", snap.userId)
            .put("books", books)
            .put("bookmarks", marks)
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
                bookOrder = order
            )
        }.getOrNull()
    }

    fun merge(local: AccountSnapshot, cloud: AccountSnapshot?): AccountSnapshot {
        if (cloud == null) return local.copy(updatedAt = maxOf(local.updatedAt, System.currentTimeMillis()))
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
            when {
                l == null && c != null -> mergedBooks += c.copy(filePath = null, pendingUpload = false)
                l != null && c == null -> mergedBooks += l
                l != null && c != null -> mergedBooks += mergeBook(l, c)
            }
        }
        val marks = LinkedHashMap<String, SavedBookmark>()
        (cloud.bookmarks + local.bookmarks).forEach { m ->
            val key = "${m.bookId}\u0000${m.pageIndex}"
            val prev = marks[key]
            if (prev == null || m.updatedAt >= prev.updatedAt) marks[key] = m
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
            bookOrder = mergedBooks.map { it.id }
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
        return local.copy(
            title = if (fileOk || local.title.isNotBlank()) local.title.ifBlank { cloud.title } else cloud.title,
            author = if (fileOk) local.author.ifBlank { cloud.author } else local.author.ifBlank { cloud.author },
            remoteCover = cover,
            format = if (fileOk) local.format else local.format.takeIf { it != BookFormat.TEXT } ?: cloud.format,
            origin = if (local.origin == BookOrigin.BUNDLED) BookOrigin.BUNDLED else local.origin.takeIf { it != BookOrigin.BUNDLED } ?: cloud.origin,
            filePath = if (fileOk) local.filePath else null,
            pageCount = progress.pageCount,
            currentPage = progress.currentPage,
            finished = progress.finished,
            addedAt = minOf(local.addedAt.takeIf { it > 0L } ?: cloud.addedAt, cloud.addedAt.takeIf { it > 0L } ?: local.addedAt),
            lastReadAt = progress.lastReadAt,
            category = local.category.ifBlank { cloud.category },
            synopsis = local.synopsis.ifBlank { cloud.synopsis },
            driveFileId = local.driveFileId ?: cloud.driveFileId,
            pendingUpload = false
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
            driveFileId = null,
            pendingUpload = false
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
