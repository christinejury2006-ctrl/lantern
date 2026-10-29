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
        return book.backupAt <= 0L
    }

    /** True when this book can be restored onto another device. */
    fun restorable(book: LibraryBook): Boolean =
        eligible(book) && !book.driveFileId.isNullOrBlank() && book.backupAt > 0L
}
