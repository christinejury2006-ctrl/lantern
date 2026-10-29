package com.lantern.library.data

/**
 * The rules that decide what survives a sync.
 *
 * Everything in this file is pure: no Android, no network, no JSON. That is deliberate. The three
 * bugs this file exists to fix — a deleted book coming back, a half-uploaded book being called
 * safe, and a retry creating a second copy of the same file — are all decided by rules, and rules
 * can be tested on every push without two phones and two accounts.
 */

/**
 * A deliberate deletion, and when it happened.
 *
 * The library merge is a union, so "absent from this phone" has never meant "deleted": it also
 * means "not yet downloaded", "not yet synced", or "restore interrupted". A union therefore
 * cannot express a deletion at all, and a deleted book reappeared on the next device by design.
 * A tombstone is the missing statement: *this book was removed on purpose, at this time*, which
 * every device can compare against its own copy of the book.
 */
data class Tombstone(val bookId: String, val deletedAt: Long)

object Deletions {

    /**
     * How long a deletion is remembered.
     *
     * A tombstone has to outlive the slowest device that might still be holding the book offline.
     * Six months is far longer than any realistic gap between syncs, and keeps the list small:
     * it is one entry per book the reader has ever deleted.
     */
    const val RETENTION_MS = 180L * 24 * 60 * 60 * 1000

    /** Both sides' deletions, one entry per book, keeping the most recent timestamp. */
    fun merge(a: List<Tombstone>, b: List<Tombstone>): List<Tombstone> {
        val byId = LinkedHashMap<String, Tombstone>()
        (a + b).forEach { t ->
            if (t.bookId.isBlank()) return@forEach
            val prev = byId[t.bookId]
            if (prev == null || t.deletedAt > prev.deletedAt) byId[t.bookId] = t
        }
        return byId.values.sortedBy { it.bookId }
    }

    /** Drops deletions old enough that no device can still be holding the book. */
    fun prune(list: List<Tombstone>, now: Long = System.currentTimeMillis()): List<Tombstone> =
        list.filter { it.bookId.isNotBlank() && now - it.deletedAt <= RETENTION_MS }

    /**
     * Whether a deletion outranks this book.
     *
     * Deletion wins over an older copy of the book — that is the whole point — but not over a copy
     * the reader added back *after* deleting it. Re-importing a book stamps a fresh `addedAt`, so
     * "delete, then change your mind and import it again" works without a tombstone forever
     * shadowing the new copy.
     */
    fun blocks(book: LibraryBook, stones: List<Tombstone>): Boolean {
        val stone = stones.firstOrNull { it.bookId == book.id } ?: return false
        // A bundled or freshly re-added book is not what was deleted.
        if (book.origin == BookOrigin.BUNDLED) return false
        return book.addedAt <= stone.deletedAt
    }

    /** The books a set of deletions still applies to, in order. */
    fun alive(books: List<LibraryBook>, stones: List<Tombstone>): List<LibraryBook> =
        if (stones.isEmpty()) books else books.filterNot { blocks(it, stones) }

    fun ids(stones: List<Tombstone>): Set<String> = stones.mapTo(HashSet()) { it.bookId }
}

/**
 * How a failed transfer should be treated.
 *
 * The distinction is the difference between "try again in a second" and "this will never work, and
 * retrying every sync for a month will not change that". Before this existed, every failure was
 * identical: one attempt, counted as failed, retried only because the book was still pending.
 */
enum class TransferClass {
    /** No failure. */
    OK,
    /** Worth retrying: the network moved, Google was busy, or a token expired. */
    TRANSIENT,
    /** The token was rejected and must be re-acquired before anything else will work. */
    UNAUTHORIZED,
    /** Retrying cannot help: the Drive API is off, the scope was never granted, or the file is gone. */
    PERMANENT
}

object RetryPolicy {
    /** One initial attempt plus three retries. A phone on a bad connection should not give up first. */
    const val MAX_ATTEMPTS = 4

    /** Base delay for the first retry; each later retry doubles it, capped. */
    const val BASE_DELAY_MS = 600L
    const val MAX_DELAY_MS = 8_000L

    /**
     * Classifies a failure. `code = 0` means no response at all — a socket timeout, a dropped
     * connection, DNS — which is exactly the case the old code reported once and then gave up on.
     */
    fun classify(code: Int): TransferClass = when (code) {
        0, 408, 429, 500, 502, 503, 504 -> TransferClass.TRANSIENT
        401 -> TransferClass.UNAUTHORIZED
        403, 404, 410 -> TransferClass.PERMANENT
        in 200..299 -> TransferClass.OK
        in 400..499 -> TransferClass.PERMANENT
        else -> TransferClass.TRANSIENT
    }

    /** Exponential backoff: 600 ms, 1.2 s, 2.4 s… capped at eight seconds. */
    fun delayMs(attempt: Int): Long {
        if (attempt <= 1) return BASE_DELAY_MS
        var delay = BASE_DELAY_MS
        repeat(attempt - 1) {
            delay *= 2
            if (delay >= MAX_DELAY_MS) return MAX_DELAY_MS
        }
        return delay.coerceAtMost(MAX_DELAY_MS)
    }

    fun shouldRetry(attempt: Int, failure: TransferClass): Boolean =
        failure == TransferClass.TRANSIENT && attempt < MAX_ATTEMPTS

    fun shouldRetry(attempt: Int, code: Int): Boolean = shouldRetry(attempt, classify(code))

    /** A short, actionable sentence for the diagnosis. Never contains a token or file contents. */
    fun explain(failure: TransferClass, code: Int, detail: String): String {
        val trimmed = detail.replace(Regex("\\s+"), " ").trim().take(180)
        val base = when (failure) {
            TransferClass.OK -> "ok"
            TransferClass.TRANSIENT ->
                if (code == 0) "no response from Google Drive (network timed out)" else "Google Drive returned HTTP $code"
            TransferClass.UNAUTHORIZED -> "the Drive access token was rejected — sign in again"
            TransferClass.PERMANENT -> when (code) {
                403 -> "Google Drive refused the request (403) — the Drive API may be disabled, or the " +
                    "app-data scope was never granted"
                404, 410 -> "the file no longer exists in the account (HTTP $code)"
                else -> "Google Drive returned HTTP $code"
            }
        }
        return if (trimmed.isBlank()) base else "$base — $trimmed"
    }
}

/** What a backup pass should do with one book, decided before any network call is made. */
enum class UploadDecision {
    /** Nothing to do: the account already holds these exact bytes. */
    ALREADY_VERIFIED,
    /** There is an id, but no confirmation for it: cheap checksum read, upload only if it differs. */
    CONFIRM,
    /** The bytes must be sent (new book, or the local file changed since the last confirmation). */
    SEND,
    /** The phone has no file to send. That is a download problem, not an upload problem. */
    NO_LOCAL_FILE,
    /** Not a format the account holds (bundled text, TXT). */
    NOT_APPLICABLE
}

/**
 * The per-book backup audit.
 *
 * [LibraryBook.backupState] answers "was this ever confirmed?" — a fact about the record. The badge
 * answers "what should the reader be told *right now*", which also depends on whether an upload is
 * running and whether the phone is online. Keeping them separate is what stops a queued book from
 * being shown as failed merely because the phone is in a tunnel.
 */
enum class BackupBadge {
    NOT_APPLICABLE,
    /** No file in the account and none on the phone: metadata only. */
    LOCAL_ONLY,
    /** Queued: waiting for a connection, or waiting its turn. */
    WAITING,
    UPLOADING,
    /** Uploaded and its checksum confirmed. The only state allowed to say "backed up". */
    VERIFIED,
    FAILED,
    /** Verified in the account, no file on this phone: fetched on open, or on demand. */
    RESTORE_REQUIRED
}

object BackupAudit {

    /**
     * What to tell the reader about one book.
     *
     * @param uploading whether an upload for this book is running in this process right now.
     * @param online whether the phone has a usable connection.
     */
    fun badge(book: LibraryBook, uploading: Boolean, online: Boolean = true): BackupBadge {
        val stored = book.backupState
        return when {
            stored == BookBackupState.NOT_APPLICABLE -> BackupBadge.NOT_APPLICABLE
            uploading -> BackupBadge.UPLOADING
            stored == BookBackupState.BACKED_UP ->
                if (book.localFileAvailable) BackupBadge.VERIFIED else BackupBadge.RESTORE_REQUIRED
            stored == BookBackupState.FAILED -> if (online) BackupBadge.FAILED else BackupBadge.WAITING
            // Nothing in the account, nothing on the phone: a record and no file anywhere.
            book.driveFileId.isNullOrBlank() && !book.localFileAvailable -> BackupBadge.LOCAL_ONLY
            else -> BackupBadge.WAITING
        }
    }

    fun label(badge: BackupBadge): String = when (badge) {
        BackupBadge.NOT_APPLICABLE -> "Not backed up"
        BackupBadge.LOCAL_ONLY -> "Local only"
        BackupBadge.WAITING -> "Waiting for connection"
        BackupBadge.UPLOADING -> "Uploading"
        BackupBadge.VERIFIED -> "Backed up and verified"
        BackupBadge.FAILED -> "Upload failed"
        BackupBadge.RESTORE_REQUIRED -> "Restore required"
    }

    /** Counts over the books the account can hold, so "2 verified" means two actual files. */
    fun counts(books: List<LibraryBook>, uploading: Set<String>, online: Boolean = true): Counts {
        var verified = 0
        var pending = 0
        var failed = 0
        var restore = 0
        var localOnly = 0
        books.forEach { book ->
            when (badge(book, book.id in uploading, online)) {
                BackupBadge.VERIFIED -> verified++
                BackupBadge.WAITING, BackupBadge.UPLOADING -> pending++
                BackupBadge.FAILED -> failed++
                BackupBadge.RESTORE_REQUIRED -> restore++
                BackupBadge.LOCAL_ONLY -> localOnly++
                BackupBadge.NOT_APPLICABLE -> Unit
            }
        }
        return Counts(verified, pending, failed, restore, localOnly)
    }

    data class Counts(
        val verified: Int,
        val pending: Int,
        val failed: Int,
        val restoreRequired: Int,
        val localOnly: Int
    ) {
        val total: Int get() = verified + pending + failed + restoreRequired + localOnly
        val complete: Boolean get() = pending == 0 && failed == 0 && localOnly == 0

        /**
         * The one line the reader sees. Says out loud when the answer is not "everything is safe",
         * because a cheerful summary over a partial backup is how they delete the only copy.
         */
        fun summary(): String {
            if (total == 0) return "No book files to back up yet"
            val parts = ArrayList<String>(4)
            parts += "$verified verified"
            if (pending > 0) parts += "$pending pending"
            if (failed > 0) parts += "$failed failed"
            if (restoreRequired > 0) parts += "$restoreRequired to restore"
            if (localOnly > 0) parts += "$localOnly local only"
            return parts.joinToString(", ")
        }
    }
}
