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
    /**
     * A transfer that was in flight when the process died.
     *
     * Different from [WAITING] on purpose: waiting means "nothing has been tried", paused means
     * "this was tried, was interrupted, and the bytes on the phone are still the ones to send".
     * The reader deserves to know which one they are looking at.
     */
    PAUSED,
    UPLOADING,
    /** Uploaded and its checksum confirmed. The only state allowed to say "backed up". */
    VERIFIED,
    FAILED,
    /** Verified in the account, no file on this phone: fetched on open, or on demand. */
    RESTORE_REQUIRED
}

/**
 * Where one book's transfer is, right now.
 *
 * Measured in **bytes**, never in books or pages: [sentBytes] is incremented by the request body
 * as it writes to the socket, so the number on the card is the transfer and not an estimate.
 */
enum class TransferPhase {
    /** The file is being read and the Drive lookup is running. No bytes have moved yet. */
    PREPARING,
    /** Bytes are going out. */
    UPLOADING,
    /** All bytes were sent; the account is being asked to prove it has them. */
    VERIFYING,
    /** Interrupted (process death, cancelled by the reader). The book is still in the queue. */
    PAUSED,
    /** The attempt failed. [TransferProgress.reason] says why. */
    FAILED
}

data class TransferProgress(
    val bookId: String,
    val phase: TransferPhase,
    val sentBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val startedAt: Long = 0L,
    val reason: String = ""
) {
    /**
     * Percent transferred, or `null` when it cannot be known.
     *
     * A zero-byte or unreadable file has no honest percentage, so it gets `null` and the card shows
     * an activity indicator instead of a fabricated "100%".
     */
    val percent: Int? get() = Transfers.percent(sentBytes, totalBytes)

    /** True when every byte is out. Still not a backup: [phase] must reach VERIFYING's end. */
    val sentEverything: Boolean get() = totalBytes > 0L && sentBytes >= totalBytes

    companion object {
        fun preparing(bookId: String, totalBytes: Long) =
            TransferProgress(bookId, TransferPhase.PREPARING, 0L, totalBytes, System.currentTimeMillis())

        fun uploading(bookId: String, sent: Long, total: Long, startedAt: Long) =
            TransferProgress(bookId, TransferPhase.UPLOADING, sent, total, startedAt)

        fun verifying(bookId: String, total: Long, startedAt: Long) =
            TransferProgress(bookId, TransferPhase.VERIFYING, total, total, startedAt)

        fun paused(bookId: String, sent: Long, total: Long, reason: String = "") =
            TransferProgress(bookId, TransferPhase.PAUSED, sent, total, 0L, reason)

        fun failed(bookId: String, sent: Long, total: Long, reason: String) =
            TransferProgress(bookId, TransferPhase.FAILED, sent, total, 0L, reason)
    }
}

object Transfers {

    /**
     * Transferred bytes as a percentage.
     *
     * `null` — not 0, and certainly not 100 — when the total is unknown. A zero-byte file, an
     * unreadable file and a length that has not been measured yet all land here; drawing "100%" for
     * any of them would be a lie the reader could act on.
     */
    fun percent(sent: Long, total: Long): Int? {
        if (total <= 0L) return null
        val clamped = sent.coerceIn(0L, total)
        return ((clamped.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
    }

    /** "0 B", "512 B", "1.4 MB", "2.0 GB" — the same units for sent and total. */
    fun size(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        if (bytes < 1024L) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble() / 1024.0
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (value >= 100.0) "${value.toInt()} ${units[unit]}" else "%.1f %s".format(value, units[unit])
    }

    /** "1.4 MB of 2.0 MB" — the pair the detail sheet shows. */
    fun transferred(sent: Long, total: Long): String = when {
        total <= 0L -> "size unknown — ${size(sent)} sent"
        else -> "${size(sent)} of ${size(total)}"
    }
}

/**
 * What to do with an upload that was in flight when the process died.
 *
 * The journal on disk records the book, the size it was sending and how far it got. On the next
 * launch this decides, without the network, whether the book is already done, whether the file has
 * changed under us, or whether the transfer should simply be picked up again.
 */
enum class ResumeDecision {
    /** Nothing was in flight. */
    NONE,
    /** The book was in flight and is not finished: put it back in the queue. */
    RETRY,
    /** The account already holds this exact file (verified at this size): nothing to do. */
    ALREADY_DONE,
    /** The local file is not the one that was being sent any more: send the current one. */
    RESEND
}

object Resumable {

    /**
     * @param journal the unfinished entry, if any.
     * @param book the book as it is now, or null if it is gone (deleted while we were away).
     * @param fileBytes the current size of the local file, or 0 when it is missing.
     */
    fun decide(journal: TransferProgress?, book: LibraryBook?, fileBytes: Long): ResumeDecision {
        if (journal == null) return ResumeDecision.NONE
        // Deleted, not a file book any more, or simply not here: forget it. A tombstone or a
        // missing record must never be resurrected by an upload journal.
        if (book == null) return ResumeDecision.ALREADY_DONE
        if (!BackupRules.eligible(book)) return ResumeDecision.ALREADY_DONE
        if (fileBytes <= 0L) return ResumeDecision.RESEND
        val verified = book.backupAt > 0L && book.backupBytes == fileBytes && !book.backupFailed
        if (verified) return ResumeDecision.ALREADY_DONE
        // The bytes changed while we were away: whatever was half-sent is not this file.
        if (journal.totalBytes > 0L && fileBytes != journal.totalBytes) return ResumeDecision.RESEND
        return ResumeDecision.RETRY
    }
}

object BackupAudit {

    /**
     * What to tell the reader about one book.
     *
     * @param uploading whether an upload for this book is running in this process right now.
     * @param online whether the phone has a usable connection.
     */
    fun badge(book: LibraryBook, uploading: Boolean, online: Boolean = true): BackupBadge =
        badge(book, if (uploading) TransferProgress.preparing(book.id, 0L) else null, online, queued = false)

    /**
     * The same question, with the live transfer taken into account.
     *
     * Precedence matters and is deliberate: a running or paused transfer speaks first, then the
     * stored record — and the stored record is only allowed to say "verified" when the file on the
     * phone is still the file that was verified.
     */
    fun badge(
        book: LibraryBook,
        transfer: TransferProgress?,
        online: Boolean = true,
        queued: Boolean = false
    ): BackupBadge {
        if (book.origin == BookOrigin.BUNDLED || book.format == BookFormat.TEXT) {
            return BackupBadge.NOT_APPLICABLE
        }
        if (transfer != null) {
            when (transfer.phase) {
                TransferPhase.PREPARING, TransferPhase.UPLOADING, TransferPhase.VERIFYING ->
                    return BackupBadge.UPLOADING
                TransferPhase.PAUSED -> return BackupBadge.PAUSED
                TransferPhase.FAILED -> return if (online) BackupBadge.FAILED else BackupBadge.WAITING
            }
        }
        return when (effective(book)) {
            // A confirmed copy is either here (verified) or owed to this phone (restore). The two
            // are different promises and must never be merged into one label.
            BookBackupState.BACKED_UP ->
                if (book.localFileAvailable) BackupBadge.VERIFIED else BackupBadge.RESTORE_REQUIRED
            BookBackupState.RESTORE_REQUIRED -> BackupBadge.RESTORE_REQUIRED
            BookBackupState.FAILED -> when {
                !online -> BackupBadge.WAITING
                queued -> BackupBadge.WAITING
                else -> BackupBadge.FAILED
            }
            BookBackupState.NOT_APPLICABLE -> BackupBadge.NOT_APPLICABLE
            BookBackupState.PENDING -> when {
                // Nothing in the account, nothing on the phone: a record and no file anywhere.
                book.driveFileId.isNullOrBlank() && !book.localFileAvailable -> BackupBadge.LOCAL_ONLY
                else -> BackupBadge.WAITING
            }
        }
    }

    /**
     * The stored state, corrected by the file that is actually on the phone.
     *
     * This is the "invalidate a stale verification" rule in one place: a book whose file changed
     * after it was confirmed is *not* verified any more, even though its record still carries the
     * old stamp — the account holds the previous version, and saying otherwise would let the
     * reader delete the only copy of the new one. The stamp itself is left alone so the record
     * still says what the account holds; the next upload replaces both.
     */
    fun effective(book: LibraryBook): BookBackupState {
        val stored = book.backupState
        if (stored == BookBackupState.BACKED_UP && BackupRules.changedSinceBackup(book, book.localFileAvailable)) {
            return BookBackupState.PENDING
        }
        return stored
    }

    fun label(badge: BackupBadge): String = when (badge) {
        BackupBadge.NOT_APPLICABLE -> "Not backed up"
        BackupBadge.LOCAL_ONLY -> "Local only"
        BackupBadge.WAITING -> "Waiting for connection"
        BackupBadge.UPLOADING -> "Uploading"
        BackupBadge.PAUSED -> "Paused — will continue"
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
                // A paused transfer is still owed to the account, so it counts as pending — but it
                // never counts as verified, and the shelf shows it differently.
                BackupBadge.WAITING, BackupBadge.UPLOADING, BackupBadge.PAUSED -> pending++
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

        /**
         * True only when every book is verified *and* present.
         *
         * A book the account holds but this phone does not is safe in the cloud, but it is not
         * "backed up and verified" on this device: it still needs a download before it can be
         * opened. Saying otherwise is the kind of claim this whole change exists to remove.
         */
        val complete: Boolean
            get() = pending == 0 && failed == 0 && localOnly == 0 && restoreRequired == 0

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
