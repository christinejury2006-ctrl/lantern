package com.lantern.library.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a book owes the cloud a file, and when the app is allowed to say it is safe.
 *
 * The reader intends to delete their own copies of these books, so every one of these cases is a
 * promise being kept or broken. The rule the tests pin down: a book is only [BookBackupState.BACKED_UP]
 * after an upload or download has been *verified*, and a book with a Drive id but no confirmation is
 * still pending, because that id may point at an older or broken file.
 */
class BackupRulesTest {

    private fun book(
        id: String = "b1",
        format: BookFormat = BookFormat.EPUB,
        origin: BookOrigin = BookOrigin.IMPORT,
        driveFileId: String? = null,
        backupAt: Long = 0L,
        pendingUpload: Boolean = false,
        backupFailed: Boolean = false,
        filePath: String? = null
    ) = LibraryBook(
        id = id,
        title = "A Book",
        author = "An Author",
        format = format,
        origin = origin,
        filePath = filePath,
        driveFileId = driveFileId,
        backupAt = backupAt,
        pendingUpload = pendingUpload,
        backupFailed = backupFailed
    )

    @Test
    fun `a new import with a local file needs uploading`() {
        assertTrue(BackupRules.needsUpload(book(), localFileExists = true))
    }

    @Test
    fun `a book whose local file is gone needs a download, not an upload`() {
        assertFalse(BackupRules.needsUpload(book(driveFileId = "d1"), localFileExists = false))
    }

    @Test
    fun `an id without a confirmation still needs backing up`() {
        // This is the dangerous case: metadata says "it is in the cloud", nothing ever confirmed
        // the bytes. Treating it as done is how a library disappears.
        val b = book(driveFileId = "d1", backupAt = 0L)
        assertTrue(BackupRules.needsUpload(b, localFileExists = true))
        assertEquals(BookBackupState.PENDING, b.backupState)
    }

    @Test
    fun `a confirmed book is not uploaded again`() {
        val b = book(driveFileId = "d1", backupAt = 1_700_000_000_000L)
        assertFalse(BackupRules.needsUpload(b, localFileExists = true))
        assertEquals(BookBackupState.BACKED_UP, b.backupState)
        assertTrue(BackupRules.restorable(b))
    }

    @Test
    fun `a failed upload stays queued and says so`() {
        val b = book(driveFileId = "d1", backupAt = 0L, backupFailed = true, pendingUpload = true)
        assertTrue(BackupRules.needsUpload(b, localFileExists = true))
        assertEquals(BookBackupState.FAILED, b.backupState)
        assertFalse(BackupRules.restorable(b))
    }

    @Test
    fun `bundled and text books are not backed up and cannot be restored`() {
        val bundled = book(origin = BookOrigin.BUNDLED, format = BookFormat.TEXT)
        assertFalse(BackupRules.eligible(bundled))
        assertEquals(BookBackupState.NOT_APPLICABLE, bundled.backupState)
        val text = book(format = BookFormat.TEXT)
        assertFalse(BackupRules.needsUpload(text, localFileExists = true))
        assertFalse(BackupRules.restorable(text))
    }

    @Test
    fun `a pdf that was uploaded and confirmed is restorable`() {
        val b = book(format = BookFormat.PDF, driveFileId = "d9", backupAt = 42L)
        assertTrue(BackupRules.restorable(b))
        assertFalse(BackupRules.needsUpload(b, localFileExists = true))
    }

    @Test
    fun `a book with no id can never be called backed up`() {
        val b = book(backupAt = 1_700_000_000_000L) // a stamp with no file behind it
        assertFalse(BackupRules.restorable(b))
    }
}
