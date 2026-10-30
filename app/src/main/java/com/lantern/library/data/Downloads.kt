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

    /** Recomputes the backup audit and publishes it to the UI. */
    fun refreshAudit()

    /** A one-line message for the reader (toast + diagnosis). */
    fun note(message: String)
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

    /**
     * Deletes a verified book's **local file only**.
     *
     * Order: record first, bytes second, and the record put back if the bytes will not go. Nothing
     * here can queue a remote deletion.
     */
    suspend fun removeDownload(bookId: String): RemoveOutcome {
        val book = host.latest(bookId) ?: return RemoveOutcome.NotFound
        val file = book.filePath?.let(::File)
        val localBytes = if (file != null && file.exists()) file.length() else 0L

        if (localBytes <= 0L) {
            // Nothing local. If the record already describes a confirmed cloud copy with no path,
            // this is the second tap on the same button and it has nothing left to do.
            return if (book.filePath == null && DownloadRules.cloudConfirmed(book)) {
                RemoveOutcome.AlreadyRemoved(book)
            } else {
                RemoveOutcome.Refused(RemoveBlocker.NoLocalFile)
            }
        }

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
        val md5 = BookFiles.md5(file)
        val cleared = book.copy(
            filePath = null,
            backupMd5 = md5.ifBlank { book.backupMd5 }
        )

        if (!host.persist(cleared)) {
            // Nothing has been deleted, and nothing may be: the record still promises a file.
            host.note("Could not update the library, so the download was kept.")
            return RemoveOutcome.MetadataFailed
        }

        val gone = host.deleteLocal(file) && !file.exists()
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

        val dest = File(host.booksDir, BookFiles.cacheNameFor(book.id, book.format))
        val temp = File(host.booksDir, dest.name + ".restore")
        // A `.restore` file from an earlier attempt is never evidence of anything.
        temp.delete()
        try {
            if (!host.fetchCloud(book, temp)) {
                return RestoreOutcome.Failed("your account did not return the file. Check your connection and try again")
            }
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
                is BookFiles.Check.Invalid -> return RestoreOutcome.Failed("the downloaded file is not a valid book (${check.reason})")
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
            // Interrupted or rejected: no `.restore` remnant is left behind for a later retry to
            // trip over, and the reader can simply try again.
            if (temp.exists()) runCatching { temp.delete() }
        }
    }
}
