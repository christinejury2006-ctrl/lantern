package com.lantern.library.data

/**
 * Which books still owe the cloud a file, and why.
 *
 * Pure on purpose: the answer is the difference between "your books are safe" and "your books are
 * somewhere in a folder, maybe", so it is decided by one function that can be tested rather than by
 * a filter expression inside a coroutine.
 */
object BackupRules {

    /** Formats whose bytes can live in Drive. Bundled and TXT books have no file of their own. */
    fun eligible(book: LibraryBook): Boolean =
        book.origin != BookOrigin.BUNDLED &&
            (book.format == BookFormat.EPUB || book.format == BookFormat.PDF)

    /**
     * True when the account's copy of this book is missing, unconfirmed, or known to be stale.
     *
     * @param localFileExists whether the phone still has the file to upload. A book whose local
     *   copy is gone cannot be uploaded; it needs a download instead, which is a different action.
     */
    fun needsUpload(book: LibraryBook, localFileExists: Boolean): Boolean {
        if (!eligible(book)) return false
        if (!localFileExists) return false
        if (book.backupFailed) return true
        if (book.pendingUpload) return true
        if (book.driveFileId.isNullOrBlank()) return true
        // An id exists but its contents were never confirmed: it may be an older or broken file.
        if (book.backupAt <= 0L) return true
        // The file on this phone is no longer the file that was confirmed.
        return changedSinceBackup(book, localFileExists)
    }

    /**
     * Whether the local file differs from the one the account was told about.
     *
     * Only answerable when a size was recorded at confirmation time; older records have none, and
     * they are re-confirmed by checksum on the next pass instead of being assumed correct.
     */
    fun changedSinceBackup(book: LibraryBook, localFileExists: Boolean): Boolean {
        if (!eligible(book)) return false
        if (!localFileExists) return false
        val recorded = book.backupBytes
        if (recorded <= 0L) return false
        val file = book.filePath?.let { java.io.File(it) } ?: return false
        return file.exists() && file.length() != recorded
    }

    /**
     * What the next pass should do with this book, decided without touching the network.
     *
     * [UploadDecision.CONFIRM] exists so that a library which is already backed up costs one
     * checksum read per book rather than a full re-upload — and [UploadDecision.SEND] exists so
     * that a changed local file is never mistaken for a backed-up one.
     */
    fun decide(book: LibraryBook, localFileExists: Boolean): UploadDecision {
        if (!eligible(book)) return UploadDecision.NOT_APPLICABLE
        if (!localFileExists) return UploadDecision.NO_LOCAL_FILE
        if (book.driveFileId.isNullOrBlank()) return UploadDecision.SEND
        if (book.backupAt <= 0L) return UploadDecision.CONFIRM
        if (book.backupFailed || book.pendingUpload) return UploadDecision.SEND
        if (changedSinceBackup(book, localFileExists)) return UploadDecision.SEND
        return UploadDecision.ALREADY_VERIFIED
    }

    /** True when this book can be restored onto another device. */
    fun restorable(book: LibraryBook): Boolean =
        eligible(book) && !book.driveFileId.isNullOrBlank() && book.backupAt > 0L
}
