package com.lantern.library.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The on-disk shape of a book record: one JSON object in the active profile's `library.json`.
 *
 * This lives outside `LanternStore` because two things now read the same file:
 *
 * - the app, through the store, on the main-thread-safe copy it keeps in memory;
 * - a background upload worker, which has no ViewModel, no Activity and no composition, and must
 *   still be able to answer "what does this namespace think this book is?" and write back "the
 *   bytes were verified".
 *
 * One encoder and one decoder, so the app and the worker can never disagree about the format. Keys
 * are exactly the ones older versions wrote, and every field is read with a default: a record
 * written by an older build still loads, which is what keeps an upgrade from losing a shelf.
 */
object BookRecords {

    /**
     * Backward-compatible retention migration.
     *
     * Older builds sometimes persisted the default true value without a reader choice. Only
     * the explicit-choice marker makes a pin permanent; an unmarked legacy true migrates to
     * managed cache after a size-and-checksum-confirmed Drive backup. Unverified files stay local.
     */
    fun keepOffline(json: JSONObject): Boolean {
        // Only an explicit reader choice is permanent. Older versions wrote the default true
        // into library.json, so the presence of the key alone cannot distinguish a pin from a
        // legacy default. Those records migrate only when the exact remote bytes are verified.
        if (json.optBoolean("keepOfflineExplicit", false)) {
            return json.optBoolean("keepOffline", true)
        }
        // New records already persist false as the managed-cache default. Only legacy true (or
        // absent) values need the backup-proof migration.
        val storedChoice = json.optBoolean("keepOffline", true)
        if (!storedChoice) return false
        val hasVerifiedRemoteCopy =
            json.optLong("backupAt", 0L) > 0L &&
                !json.optBoolean("pendingUpload", false) &&
                !json.optBoolean("backupFailed", false) &&
                json.optString("driveFileId").isNotBlank() &&
                json.optLong("backupBytes", 0L) > 0L &&
                json.optString("backupMd5").matches(Regex("(?i)[0-9a-f]{32}"))
        return !hasVerifiedRemoteCopy
    }

    fun encode(book: LibraryBook): JSONObject = JSONObject()
        .put("id", book.id)
        .put("title", book.title)
        .put("author", book.author)
        .put("remoteCover", book.remoteCover ?: "")
        .put("format", book.format.name)
        .put("origin", book.origin.name)
        .put("filePath", book.filePath ?: "")
        .put("remoteEpub", book.remoteEpub ?: "")
        .put("pageCount", book.pageCount)
        .put("currentPage", book.currentPage)
        .put("finished", book.finished)
        .put("addedAt", book.addedAt)
        .put("lastReadAt", book.lastReadAt)
        .put("category", book.category)
        .put("synopsis", book.synopsis)
        .put("driveFileId", book.driveFileId ?: "")
        .put("driveCoverFileId", book.driveCoverFileId ?: "")
        .put("pendingUpload", book.pendingUpload)
        .put("backupAt", book.backupAt)
        .put("backupFailed", book.backupFailed)
        .put("dnf", book.dnf)
        .put("backupBytes", book.backupBytes)
        // Read back with optString, so an older library.json without this key loads as "no
        // checksum recorded" rather than failing.
        .put("backupMd5", book.backupMd5)
        .put("keepOffline", book.keepOffline)
        .put("keepOfflineExplicit", book.keepOfflineExplicit)

    /**
     * Reads one record.
     *
     * @param originFor lets the caller apply its own history rules (the store upgrades a legacy
     *   `origin` when a real file is sitting under `books/`). The worker passes the stored origin
     *   through unchanged, because it never rewrites a record it is not uploading.
     */
    fun decode(
        json: JSONObject,
        originFor: (id: String, stored: BookOrigin, filePath: String?) -> BookOrigin = { _, stored, _ -> stored }
    ): LibraryBook? {
        val id = runCatching { json.getString("id") }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val storedOrigin = runCatching { BookOrigin.valueOf(json.optString("origin", "BUNDLED")) }
            .getOrDefault(BookOrigin.BUNDLED)
        val filePath = json.optString("filePath").ifBlank { null }
        return LibraryBook(
            id = id,
            title = json.optString("title", "Untitled"),
            author = json.optString("author"),
            remoteCover = json.optString("remoteCover").ifBlank { null },
            format = runCatching { BookFormat.valueOf(json.optString("format", "TEXT")) }
                .getOrDefault(BookFormat.TEXT),
            origin = originFor(id, storedOrigin, filePath),
            filePath = filePath,
            remoteEpub = json.optString("remoteEpub").ifBlank { null },
            pageCount = json.optInt("pageCount", 1),
            currentPage = json.optInt("currentPage", 0),
            finished = json.optBoolean("finished"),
            addedAt = json.optLong("addedAt", System.currentTimeMillis()),
            lastReadAt = json.optLong("lastReadAt"),
            category = json.optString("category", "Library"),
            synopsis = json.optString("synopsis"),
            driveFileId = json.optString("driveFileId").ifBlank { null },
            driveCoverFileId = json.optString("driveCoverFileId").ifBlank { null },
            pendingUpload = json.optBoolean("pendingUpload", false),
            dnf = json.optBoolean("dnf", false),
            backupAt = json.optLong("backupAt", 0L),
            backupFailed = json.optBoolean("backupFailed", false),
            backupBytes = json.optLong("backupBytes", 0L),
            backupMd5 = json.optString("backupMd5"),
            // Older records default to protected so an upgrade never evicts an existing download.
            keepOffline = keepOffline(json),
            keepOfflineExplicit = json.optBoolean("keepOfflineExplicit", false)
        )
    }

    fun encodeAll(books: List<LibraryBook>): String {
        val arr = JSONArray()
        books.forEach { arr.put(encode(it)) }
        return arr.toString()
    }

    fun decodeAll(
        raw: String?,
        originFor: (id: String, stored: BookOrigin, filePath: String?) -> BookOrigin = { _, stored, _ -> stored }
    ): List<LibraryBook> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val out = ArrayList<LibraryBook>(arr.length())
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                decode(row, originFor)?.let { out += it }
            }
            out
        }.getOrDefault(emptyList())
    }

    /**
     * Replaces a namespace's library file, atomically.
     *
     * Used by sign-out to drop the cached entries that are provably safe in the cloud. Written to a
     * temporary and renamed for the same reason as [update]: a process killed mid-write must leave
     * the previous shelf, never half a shelf.
     */
    fun write(file: File, books: List<LibraryBook>): Boolean {
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching { tmp.writeText(encodeAll(books)) }.getOrElse { return false }
        if (!tmp.renameTo(file)) {
            val copied = runCatching {
                tmp.copyTo(file, overwrite = true)
                true
            }.getOrDefault(false)
            runCatching { tmp.delete() }
            if (!copied) return false
        }
        // Sign-out cleanup relies on this return value before deleting any physical book bytes.
        // Verify the exact serialized shelf landed, rather than treating a failed fallback copy as
        // success and potentially removing files while the old metadata still points at them.
        return runCatching { file.exists() && file.readText() == encodeAll(books) }.getOrDefault(false)
    }

    /** Reads a namespace's library file without any app state. */
    fun read(file: File): List<LibraryBook> =
        decodeAll(runCatching { if (file.exists()) file.readText() else null }.getOrNull())

    /**
     * Replaces one book in a namespace's library file, atomically, keeping every other record.
     *
     * Written to a temporary and renamed, so a process killed mid-write leaves the previous file
     * intact rather than a truncated shelf. Returns true when the record was found and written.
     */
    fun update(file: File, bookId: String, transform: (LibraryBook) -> LibraryBook): Boolean =
        synchronized(file.absolutePath.intern()) {
            val current = read(file)
            val at = current.indexOfFirst { it.id == bookId }
            if (at < 0) return@synchronized false
            val next = current.toMutableList()
            next[at] = transform(current[at])
            val expected = encodeAll(next)
            val tmp = File(file.parentFile, file.name + ".tmp")
            runCatching { tmp.writeText(expected) }.getOrElse { return@synchronized false }
            if (!tmp.renameTo(file)) {
                val copied = runCatching {
                    tmp.copyTo(file, overwrite = true)
                    true
                }.getOrDefault(false)
                runCatching { tmp.delete() }
                if (!copied) return@synchronized false
            }
            runCatching { file.exists() && file.readText() == expected }.getOrDefault(false)
        }
}

/**
 * Releases a just-uploaded original from the phone only after its exact bytes have been verified
 * by Drive and the verified metadata has been durably written. Explicit offline pins always win.
 *
 * The metadata is changed before deleting bytes. If deletion fails, the old path is restored so
 * the shelf never claims a local download is missing when it is still present.
 */
object VerifiedUploadRetention {
    enum class Outcome { RELEASED, KEPT_OFFLINE, NOT_ELIGIBLE, STALE_LOCAL, METADATA_FAILED, DELETE_FAILED }

    fun release(
        libraryFile: File,
        bookId: String,
        driveFileId: String,
        bytes: Long,
        md5: String
    ): Outcome {
        val before = BookRecords.read(libraryFile).firstOrNull { it.id == bookId }
            ?: return Outcome.NOT_ELIGIBLE
        if (before.keepOfflineExplicit && before.keepOffline) return Outcome.KEPT_OFFLINE
        if (before.origin == BookOrigin.BUNDLED ||
            (before.format != BookFormat.PDF && before.format != BookFormat.EPUB)) {
            return Outcome.NOT_ELIGIBLE
        }
        // The durable record must describe this exact verified upload, not merely some older
        // backup or a stale Drive id left behind by a failed replacement.
        if (before.driveFileId != driveFileId || before.backupAt <= 0L ||
            before.backupBytes != bytes || !before.backupMd5.equals(md5, ignoreCase = true) ||
            before.pendingUpload || before.backupFailed) {
            return Outcome.METADATA_FAILED
        }
        val path = before.filePath ?: return Outcome.NOT_ELIGIBLE
        val local = File(path)
        if (!local.isFile || local.length() != bytes || bytes <= 0L ||
            md5.isBlank() || !BookFiles.md5(local).equals(md5, ignoreCase = true)) {
            return Outcome.STALE_LOCAL
        }

        val cleared = BookRecords.update(libraryFile, bookId) { latest ->
            if (latest.filePath != path || (latest.keepOfflineExplicit && latest.keepOffline)) {
                latest
            } else {
                latest.copy(
                    filePath = null,
                    driveFileId = driveFileId,
                    pendingUpload = false,
                    backupFailed = false,
                    backupAt = latest.backupAt.coerceAtLeast(System.currentTimeMillis()),
                    backupBytes = bytes,
                    backupMd5 = md5,
                    keepOffline = false
                )
            }
        }
        if (!cleared) return Outcome.METADATA_FAILED
        val after = BookRecords.read(libraryFile).firstOrNull { it.id == bookId }
            ?: return Outcome.METADATA_FAILED
        if (after.filePath != null) {
            return if (after.keepOfflineExplicit && after.keepOffline) Outcome.KEPT_OFFLINE
            else Outcome.METADATA_FAILED
        }
        if (after.driveFileId != driveFileId || after.backupBytes != bytes ||
            !after.backupMd5.equals(md5, ignoreCase = true) || after.backupAt <= 0L ||
            after.pendingUpload || after.backupFailed) {
            // The record did not retain the exact confirmation that authorises deletion.
            BookRecords.update(libraryFile, bookId) { latest ->
                if (latest.filePath == null) latest.copy(filePath = path) else latest
            }
            return Outcome.METADATA_FAILED
        }

        if (local.delete() && !local.exists()) {
            BookIo.deleteDerivedMedia(local)
            return Outcome.RELEASED
        }

        // Bytes remain, so put the path back. A later cleanup can safely retry.
        BookRecords.update(libraryFile, bookId) { latest ->
            if (latest.filePath == null) latest.copy(filePath = path) else latest
        }
        return Outcome.DELETE_FAILED
    }
}
