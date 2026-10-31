package com.lantern.library.data

import java.io.File

/**
 * Removing a book's local bytes, and putting them back.
 *
 * ## Why this is not `remove()`
 *
 * [LanternStore.remove] deletes the book from the library *and* queues the account's copy for
 * deletion — a tombstone, a pending Drive delete, the lot. That is right for "I do not want this
 * book any more" and catastrophically wrong for "this book is taking up 40 MB and I want it back
 * later": the whole point of a backup is that removing the download costs nothing but bandwidth.
 *
 * So removal lives here, on its own path, with its own rules and its own failure handling. It
 * touches exactly one field — `filePath` — and it never creates a tombstone, never queues a remote
 * deletion, and never calls into the deletion code at all. The test for that is structural: this
 * file has no way to reach it.
 *
 * ## The order that makes it crash-safe
 *
 * The record must stop pointing at a file *before* the file goes away. If the process dies in
 * between, the reader has a book whose local copy is missing but whose cloud copy is verified —
 * [BookBackupState.RESTORE_REQUIRED], which is a state the app already knows how to recover from.
 * The other order (delete first, then write) would leave a record promising a file that is gone.
 *
 * Everything here is plain Kotlin behind a small [DownloadHost] interface, so the whole sequence
 * — including "the metadata write failed", "the file could not be deleted" and "the download was
 * truncated" — is tested without a device.
 */
sealed class RemoveBlocker {
    abstract val message: String

    /** Not an imported PDF/EPUB: a bundled or plain-text book has no download to remove. */
    object NotAFileBook : RemoveBlocker() {
        override val message = "Only imported PDF and EPUB books have a download to remove."
    }

    /** Nothing in the account, so removing the file would destroy the only copy. */
    object MissingDriveId : RemoveBlocker() {
        override val message = "This book has no copy in your account yet, so the download stays."
    }

    /** There is a file id but no confirmation that the bytes were ever proved. */
    object NotVerified : RemoveBlocker() {
        override val message = "This book has not been verified in your account yet, so the download stays."
    }

    /** The last attempt failed: the account's copy is not known to match this file. */
    object UploadFailed : RemoveBlocker() {
        override val message = "The last backup of this book failed, so the download stays until it succeeds."
    }

    /** An upload is owed or in progress. */
    object UploadPending : RemoveBlocker() {
        override val message = "This book is still being backed up, so the download stays for now."
    }

    object NoLocalFile : RemoveBlocker() {
        override val message = "There is no download on this phone to remove."
    }

    /** The confirmation predates the byte count, so it cannot be matched to this file. */
    object SizeNotRecorded : RemoveBlocker() {
        override val message =
            "This book was verified before Lore recorded file sizes. Back it up once more, then the download can be removed."
    }

    /** The file on the phone is not the file that was verified. */
    data class StaleBackup(val recorded: Long, val actual: Long) : RemoveBlocker() {
        override val message =
            "This file has changed since it was verified (${Transfers.size(actual)} now, " +
                "${Transfers.size(recorded)} verified), so the download stays until it is backed up again."
    }

    /** Offline, or Drive would not answer: the account's copy could not be confirmed. */
    object AccountUnconfirmed : RemoveBlocker() {
        override val message =
            "Lore could not confirm your account still holds this book. Connect and try again — the download is safe meanwhile."
    }

    /** The account answered, and does not hold this book under the expected file. */
    object NotInAccount : RemoveBlocker() {
        override val message =
            "Your account no longer holds this copy, so the download stays. Run a sync to back it up again."
    }
}

/** Why a book cannot be fetched back from the account. */
sealed class RestoreBlocker {
    abstract val message: String

    object NotAFileBook : RestoreBlocker() {
        override val message = "Only imported PDF and EPUB books are restored from your account."
    }

    object MissingDriveId : RestoreBlocker() {
        override val message = "This book has no copy in your account to restore."
    }

    object NotVerified : RestoreBlocker() {
        override val message = "This book has no verified copy in your account to restore."
    }
}

/** What one local removal achieved. Never a bare boolean: the reader is told which step failed. */
sealed class RemoveOutcome {
    /** The bytes are gone from the phone; the record and the account copy are untouched. */
    data class Removed(val book: LibraryBook) : RemoveOutcome()

    /** There was nothing local to remove. A second tap is harmless, and says so. */
    data class AlreadyRemoved(val book: LibraryBook) : RemoveOutcome()

    /** Refused before anything was touched, with a reason the reader can act on. */
    data class Refused(val blocker: RemoveBlocker) : RemoveOutcome()

    /** The library record could not be written, so **nothing was deleted**. */
    object MetadataFailed : RemoveOutcome()

    /** The record was cleared but the bytes would not go; the record was put back to match reality. */
    data class DeleteFailed(val reason: String) : RemoveOutcome()

    object NotFound : RemoveOutcome()
}

/** What one restore achieved. */
sealed class RestoreOutcome {
    data class Restored(val book: LibraryBook, val bytes: Long) : RestoreOutcome()

    /** A valid local file was already there; nothing was overwritten and nothing was fetched. */
    data class AlreadyAvailable(val book: LibraryBook) : RestoreOutcome()

    data class Refused(val blocker: RestoreBlocker) : RestoreOutcome()

    /** The download was rejected or failed. The account copy is untouched either way. */
    data class Failed(val reason: String) : RestoreOutcome()

    /**
     * Stopped because the reader asked it to.
     *
     * [resumable] says whether the bytes received so far are kept as a checkpoint. When it is
     * false the partial file has already been thrown away, so resuming restarts from zero — which
     * is the honest thing to do when the server would not let us continue.
     */
    data class Paused(val received: Long, val total: Long, val resumable: Boolean) : RestoreOutcome()

    /** Another restore for this book is already running. Two must never write the same file. */
    object Busy : RestoreOutcome()

    object NotFound : RestoreOutcome()
}

/**
 * The rules, separated from the doing.
 *
 * They are pure functions of the record plus one `File.length()`, which is what makes them cheap
 * enough to call from a composable on every recomposition and simple enough to test exhaustively.
 */
object DownloadRules {

    /** A book whose bytes Lore itself stores and can therefore fetch again. */
    fun isFileBook(book: LibraryBook): Boolean =
        book.origin != BookOrigin.BUNDLED &&
            (book.format == BookFormat.PDF || book.format == BookFormat.EPUB)

    /**
     * Whether the account is believed to hold these exact bytes.
     *
     * Deliberately stricter than "has a Drive file id": an id is created by an attempt, not by a
     * confirmation, and a book whose upload failed keeps its old id.
     */
    fun cloudConfirmed(book: LibraryBook): Boolean =
        !book.driveFileId.isNullOrBlank() && book.backupAt > 0L && !book.pendingUpload && !book.backupFailed

    /**
     * Why this book's local file may not be removed, or `null` when it may.
     *
     * @param localBytes the size of the file on disk now, or 0 when there is none.
     */
    fun removeBlocker(book: LibraryBook, localBytes: Long): RemoveBlocker? {
        if (!isFileBook(book)) return RemoveBlocker.NotAFileBook
        if (book.driveFileId.isNullOrBlank()) return RemoveBlocker.MissingDriveId
        if (book.pendingUpload) return RemoveBlocker.UploadPending
        if (book.backupFailed) return RemoveBlocker.UploadFailed
        if (book.backupAt <= 0L) return RemoveBlocker.NotVerified
        if (localBytes <= 0L) return RemoveBlocker.NoLocalFile
        // Evidence about bytes only counts if it is about *these* bytes.
        if (book.backupBytes <= 0L) return RemoveBlocker.SizeNotRecorded
        if (book.backupBytes != localBytes) return RemoveBlocker.StaleBackup(book.backupBytes, localBytes)
        return null
    }

    /** Why this book cannot be fetched back from the account, or `null` when it can. */
    fun restoreBlocker(book: LibraryBook): RestoreBlocker? {
        if (!isFileBook(book)) return RestoreBlocker.NotAFileBook
        if (book.driveFileId.isNullOrBlank()) return RestoreBlocker.MissingDriveId
        if (!cloudConfirmed(book)) return RestoreBlocker.NotVerified
        return null
    }

    /** What the book's own record says about the account copy, in the reader's words. */
    fun describe(book: LibraryBook, localBytes: Long): String = when (val blocker = removeBlocker(book, localBytes)) {
        null -> "Stored in your account and on this phone."
        RemoveBlocker.NoLocalFile -> "Stored in your account only."
        else -> blocker.message
    }
}

/** Which of the two non-destructive download actions a book can offer, right now. */
enum class DownloadAction {
    /** A verified local file whose download can be removed. */
    REMOVE,

    /** No local file, verified cloud copy: it can be fetched back. */
    RESTORE,

    /** Neither: no download to remove, or nothing verified to fetch. */
    NONE
}

/**
 * The one decision the library and the cloud-copy sheet share.
 *
 * Kept here, as a pure function, so "the Remove download button is not offered for an unverified
 * file" is a tested statement rather than a property of two screens that happen to agree today.
 */
object DownloadUi {

    fun actionFor(book: LibraryBook, localBytes: Long): DownloadAction = when {
        localBytes > 0L && DownloadRules.removeBlocker(book, localBytes) == null -> DownloadAction.REMOVE
        localBytes <= 0L && DownloadRules.restoreBlocker(book) == null -> DownloadAction.RESTORE
        else -> DownloadAction.NONE
    }

    /** The line under a card while a download is being removed or fetched back. */
    fun busyLabel(removing: Boolean, restoring: Boolean): String? = when {
        restoring -> "Restoring…"
        removing -> "Removing download…"
        else -> null
    }

    /** What the card says about a book whose bytes are not on this phone. */
    fun cloudOnlyLabel(book: LibraryBook): String =
        if (book.backupState == BookBackupState.RESTORE_REQUIRED) "Cloud copy · download removed" else "In your account"
}

/**
 * What a removal or a restore needs from the app.
 *
 * Kept deliberately small: a book lookup, a record write that reports success, the library
 * directory, one network fetch, and a way to say something. There is no member here that can
 * delete a book, record a tombstone or queue a remote deletion, which is what keeps a local
 * removal from ever turning into a library deletion.
 */
interface DownloadHost {
    /** The book as it is now, or null if it is gone. */
    fun latest(bookId: String): LibraryBook?

    /**
     * Writes the record and returns true **only if it can be read back** as written.
     *
     * A write that cannot be confirmed is treated as a failed write: the bytes must not be deleted
     * on the strength of a record that may not have landed.
     */
    fun persist(book: LibraryBook): Boolean

    /** Where local book files live. */
    val booksDir: File

    /** Whether the signed-in account still holds this book's file. `false` when it cannot be asked. */
    suspend fun accountHolds(book: LibraryBook): AccountLink

    /**
     * Deletes one local file and reports whether it is gone.
     *
     * A seam rather than a direct `File.delete()` call so "the storage refused" can be tested
     * without making a test depend on file-system permissions, and so the one place that removes a
     * reader's bytes is explicitly the one place.
     */
    fun deleteLocal(file: File): Boolean

    /** Downloads the book's file into [dest]. False on any failure; the file may be absent or partial. */
    suspend fun fetchCloud(book: LibraryBook, dest: File): Boolean

    /**
     * Streams the account's copy into [dest], continuing from [from] when it can.
     *
     * The default has no streaming transport to offer: it fetches the whole file and reports it as
     * complete, which is exactly what the older single-shot path did. The store overrides it with a
     * ranged, cancellable download so the reader gets real bytes, a pause that stops the transfer
     * and a resume that continues from the checkpoint.
     */
    suspend fun streamCloud(
        book: LibraryBook,
        dest: File,
        from: Long,
        control: RestoreControl,
        onBytes: (received: Long, total: Long) -> Unit
    ): StreamReport {
        if (from > 0L) dest.delete()
        if (!fetchCloud(book, dest)) {
            return StreamReport(received = 0L, total = -1L, complete = false, paused = false, retryable = true, reason = "the download failed")
        }
        val size = if (dest.exists()) dest.length() else 0L
        onBytes(size, book.backupBytes.takeIf { it > 0L } ?: size)
        return StreamReport(
            received = size,
            total = book.backupBytes.takeIf { it > 0L } ?: size,
            complete = true,
            paused = false,
            retryable = false,
            reason = ""
        )
    }

    /** Bytes already on disk for a paused restore of this book, or 0 when there are none. */
    fun resumeOffset(bookId: String): Long = 0L

    /** Remembers how far a restore got, so a restart can offer to continue instead of restarting. */
    fun noteRestore(bookId: String, received: Long, total: Long) {}

    /** The restore finished or was abandoned: nothing is left to resume. */
    fun clearRestore(bookId: String) {}

    /** Publishes restore progress. Called between chunks, on a background thread. */
    fun noteRestoreProgress(bookId: String, received: Long, total: Long, phase: TransferPhase) {}

    /** Recomputes the backup audit and publishes it to the UI. */
    fun refreshAudit()

    /** A one-line message for the reader (toast + diagnosis). */
    fun note(message: String)
}

/** One streaming download's outcome. */
data class StreamReport(
    /** Bytes written into the destination by *this* call. */
    val received: Long,
    /** The full size of the file when the service said so, or -1. Never guessed. */
    val total: Long,
    /** The whole file is on disk now. */
    val complete: Boolean,
    /** The reader paused: stop here, keep the checkpoint. */
    val paused: Boolean,
    /** Worth trying again (a dropped socket, a timeout). */
    val retryable: Boolean,
    val reason: String = ""
)

/**
 * What a running restore checks between chunks.
 *
 * Pause is implemented by asking the transfer to stop at the next chunk boundary and closing the
 * connection — not by hiding a spinner. The bytes already written stay in the `.restore` file as a
 * checkpoint, and the next attempt asks the service to continue from that offset.
 */
interface RestoreControl {
    fun paused(): Boolean
    fun cancelled(): Boolean
}

/** Whether the account still holds the file a book points at. */
enum class AccountLink {
    /** Asked, and answered yes. */
    MATCHED,
    /** Asked, and answered no: the file is gone, or never belonged to this account. */
    MISSING,
    /** Could not be asked: offline, refused, or no account. */
    UNKNOWN
}

/** The crash-safe sequences. See the file comment for why the order is what it is. */
class DownloadService(private val host: DownloadHost) {

    /** Books with a restore running. Also the single-flight guard: two per book is never right. */
    private val bookIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Pauses the reader asked for, honoured between chunks. */
    private val pauseRequests = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Cancellations: the app is going away, or the book left the library. */
    private val cancelRequests = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Deletes a verified book's **local file only**.
     *
     * Order: record first, bytes second, and the record put back if the bytes will not go. Nothing
     * here can queue a remote deletion.
     */
    suspend fun removeDownload(bookId: String): RemoveOutcome {
        val book = host.latest(bookId) ?: return RemoveOutcome.NotFound

        // The second tap on the same button: the record already says there is no local copy, and
        // the account is still confirmed to hold one. Nothing to do, nothing to report as an error.
        if (book.filePath == null && DownloadRules.cloudConfirmed(book)) {
            return RemoveOutcome.AlreadyRemoved(book)
        }

        val file = book.filePath?.let(::File)
        val localBytes = if (file != null && file.exists()) file.length() else 0L
        // A record with a path but no readable bytes has nothing to remove, and nothing may be
        // deleted on the strength of a path alone.
        if (localBytes <= 0L) return RemoveOutcome.Refused(RemoveBlocker.NoLocalFile)
        val present = file ?: return RemoveOutcome.Refused(RemoveBlocker.NoLocalFile)

        DownloadRules.removeBlocker(book, localBytes)?.let { return RemoveOutcome.Refused(it) }

        // The account must still be the one holding the copy. A wrong answer here is cheap to
        // recover from; deleting the only copy of a book is not.
        when (host.accountHolds(book)) {
            AccountLink.MATCHED -> Unit
            AccountLink.MISSING -> return RemoveOutcome.Refused(RemoveBlocker.NotInAccount)
            AccountLink.UNKNOWN -> return RemoveOutcome.Refused(RemoveBlocker.AccountUnconfirmed)
        }

        // A checksum of the bytes being removed, so a later restore can prove it got these bytes
        // back and not a different file that happens to be the same length.
        val md5 = BookFiles.md5(present)
        val cleared = book.copy(
            filePath = null,
            backupMd5 = md5.ifBlank { book.backupMd5 }
        )

        if (!host.persist(cleared)) {
            // Nothing has been deleted, and nothing may be: the record still promises a file.
            host.note("Could not update the library, so the download was kept.")
            return RemoveOutcome.MetadataFailed
        }

        val gone = host.deleteLocal(present) && !present.exists()
        if (!gone) {
            // Reconcile: the record says "no file" but a file is still there. The record has to
            // describe the phone, so put the path back and report the truth.
            val restored = host.persist(book)
            val reason = if (restored) {
                "The download could not be deleted from this phone. The book is still available offline."
            } else {
                "The download could not be deleted, and the library could not be updated to say so. " +
                    "Open the book again to repair it."
            }
            host.refreshAudit()
            host.note(reason)
            return RemoveOutcome.DeleteFailed(reason)
        }

        host.refreshAudit()
        host.note("\u201C${book.title.ifBlank { "Book" }}\u201D download removed. It stays in your library.")
        return RemoveOutcome.Removed(cleared)
    }

    /**
     * Fetches a removed book's bytes back from the account.
     *
     * The download lands in a sibling `.restore` file, is checked for emptiness, recorded size,
     * recorded checksum and container shape, and only then is atomically put in place. A valid
     * local file is never replaced, and a failure never touches the account copy.
     */
    suspend fun restoreDownload(bookId: String): RestoreOutcome {
        val book = host.latest(bookId) ?: return RestoreOutcome.NotFound
        val existing = book.filePath?.let(::File)
        if (existing != null && existing.exists() && existing.length() > 0L &&
            BookFiles.validate(existing, book.format) is BookFiles.Check.Ok
        ) {
            return RestoreOutcome.AlreadyAvailable(book)
        }

        DownloadRules.restoreBlocker(book)?.let { return RestoreOutcome.Refused(it) }

        // One restore per book. Two writers on one `.restore` file would interleave their bytes
        // into something that passes a size check and fails a checksum, or worse, nearly passes.
        if (!bookIds.add(book.id)) return RestoreOutcome.Busy
        val outcome = try {
            streamInto(book)
        } finally {
            bookIds.remove(book.id)
        }
        // A pause keeps its checkpoint so resuming can continue from it. Everything else — done,
        // refused, failed — leaves nothing to resume.
        if (outcome !is RestoreOutcome.Paused) host.clearRestore(book.id)
        return outcome
    }

    /** The transfer half: resume, download, pause, retry, and only then verify and commit. */
    private suspend fun streamInto(book: LibraryBook): RestoreOutcome {
        val dest = File(host.booksDir, BookFiles.cacheNameFor(book.id, book.format))
        val temp = File(host.booksDir, dest.name + ".restore")

        // Resume only from a checkpoint the journal agrees with. A `.restore` file whose length
        // does not match what was recorded belongs to some other attempt: continuing from it would
        // append the wrong bytes to the wrong prefix, which is exactly how a corrupt book gets
        // committed. It is discarded instead.
        var from = if (temp.exists()) temp.length() else 0L
        val recorded = host.resumeOffset(book.id)
        if (from > 0L && recorded != from) {
            runCatching { temp.delete() }
            from = 0L
        }
        if (from > 0L && book.backupBytes > 0L && from > book.backupBytes) {
            runCatching { temp.delete() }
            from = 0L
        }

        val total = book.backupBytes.takeIf { it > 0L } ?: -1L
        val control = object : RestoreControl {
            override fun paused(): Boolean = book.id in pauseRequests
            override fun cancelled(): Boolean = book.id in cancelRequests
        }

        var attempts = 0
        while (attempts < MAX_STREAM_ATTEMPTS) {
            attempts++
            host.noteRestoreProgress(book.id, from, total, TransferPhase.PREPARING)
            if (control.paused() || control.cancelled()) return pausedOutcome(book, temp, from, total)

            val report = host.streamCloud(book, temp, from, control) { received, size ->
                host.noteRestore(book.id, received, size)
                host.noteRestoreProgress(book.id, received, size, TransferPhase.UPLOADING)
            }
            val onDisk = if (temp.exists()) temp.length() else 0L

            if (report.paused || control.paused() || control.cancelled()) {
                return pausedOutcome(book, temp, onDisk, report.total.takeIf { it > 0L } ?: total)
            }
            if (report.complete) {
                return verifyAndCommit(book, temp, dest)
            }
            from = onDisk
            host.noteRestore(book.id, from, total)
            if (!report.retryable) {
                // A refusal will not improve by repeating it, and the partial file is not a book.
                runCatching { temp.delete() }
                return RestoreOutcome.Failed(
                    report.reason.ifBlank { "your account did not return the file" }
                )
            }
        }
        // Out of attempts. The partial file is not a book and must not be left where a later
        // attempt — or the reader's eyes — could mistake it for one.
        runCatching { temp.delete() }
        return RestoreOutcome.Failed("the download kept stopping — check your connection and try again")
    }

    /**
     * Keeps the checkpoint only when the transfer can actually continue from it.
     *
     * The `from` offset came from the transport, so if the server honoured the range request the
     * bytes on disk are a genuine prefix and resuming is safe. A pause before any byte arrived
     * leaves nothing to keep.
     */
    private fun pausedOutcome(book: LibraryBook, temp: File, received: Long, total: Long): RestoreOutcome {
        host.noteRestore(book.id, received, total)
        host.noteRestoreProgress(book.id, received, total, TransferPhase.PAUSED)
        if (received <= 0L) {
            runCatching { temp.delete() }
            return RestoreOutcome.Paused(0L, total, resumable = false)
        }
        return RestoreOutcome.Paused(received, total, resumable = true)
    }

    /** Size, checksum, container: three independent reasons to refuse a file. */
    private suspend fun verifyAndCommit(book: LibraryBook, temp: File, dest: File): RestoreOutcome {
        host.noteRestoreProgress(book.id, temp.length(), book.backupBytes, TransferPhase.VERIFYING)
        try {
            if (!temp.exists() || temp.length() <= 0L) {
                return RestoreOutcome.Failed("the account returned an empty file")
            }
            if (book.backupBytes > 0L && temp.length() != book.backupBytes) {
                return RestoreOutcome.Failed(
                    "the copy in your account is ${Transfers.size(temp.length())}, but " +
                        "${Transfers.size(book.backupBytes)} were verified — it was not restored"
                )
            }
            if (book.backupMd5.isNotBlank() && !book.backupMd5.equals(BookFiles.md5(temp), ignoreCase = true)) {
                return RestoreOutcome.Failed("the copy in your account does not match the checksum of the file that was removed")
            }
            when (val check = BookFiles.validate(temp, book.format)) {
                is BookFiles.Check.Invalid ->
                    return RestoreOutcome.Failed("the downloaded file is not a valid book (${check.reason})")
                BookFiles.Check.Ok -> Unit
            }
            // Last look before anything is overwritten: a good local file is never replaced.
            if (dest.exists() && dest.length() > 0L && BookFiles.validate(dest, book.format) is BookFiles.Check.Ok) {
                return RestoreOutcome.AlreadyAvailable(book)
            }
            if (!BookFiles.commit(temp, dest)) {
                return RestoreOutcome.Failed("the verified file could not be moved into place")
            }
            val updated = book.copy(
                filePath = dest.absolutePath,
                // The cloud copy is unchanged: this only records the bytes now on the phone.
                backupBytes = dest.length(),
                backupFailed = false,
                backupMd5 = book.backupMd5.ifBlank { BookFiles.md5(dest) }
            )
            if (!host.persist(updated)) {
                // A file the library does not know about is a file the reader cannot use and the
                // audit would mis-report. Take it back out rather than leave a half-truth.
                runCatching { dest.delete() }
                return RestoreOutcome.Failed("the book arrived but the library could not be updated")
            }
            host.refreshAudit()
            host.note("\u201C${book.title.ifBlank { "Book" }}\u201D restored from your account.")
            return RestoreOutcome.Restored(updated, dest.length())
        } finally {
            // Verified or rejected: either way the `.restore` file has done its job. A rejection
            // never leaves a partial file lying around for a later attempt to trust.
            if (temp.exists()) runCatching { temp.delete() }
        }
    }

    /** True when a restore for this book is running right now. */
    fun isRestoring(bookId: String): Boolean = bookId in bookIds

    /**
     * Asks the running restore for [bookId] to stop at the next chunk boundary.
     *
     * The request is remembered even when no transfer is running yet, so a pause tapped a
     * millisecond before the download starts is not lost.
     */
    fun pause(bookId: String) {
        pauseRequests += bookId
    }

    /** Clears a pause. The caller starts the restore again; it continues from the checkpoint. */
    fun resume(bookId: String) {
        pauseRequests -= bookId
    }

    fun isPaused(bookId: String): Boolean = bookId in pauseRequests

    /** Stops a restore for good (the app is going away, or the book left the library). */
    fun cancel(bookId: String) {
        cancelRequests += bookId
        pauseRequests -= bookId
    }

    private companion object {
        /** Three transport attempts, then the reader is told rather than kept waiting. */
        const val MAX_STREAM_ATTEMPTS = 3
    }
}
