package com.lantern.library.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The numbers on the shelf cards.
 *
 * Every case here is one the reader can see: a percentage that must be the transfer, an unknown
 * size that must not be drawn as 100%, a finished transfer that must not become a cloud, and a
 * file that changed after its verification. All pure — no Compose, no network, no device.
 */
class TransferProgressTest {

    private fun book(
        id: String = "b1",
        format: BookFormat = BookFormat.EPUB,
        backupAt: Long = 0L,
        backupBytes: Long = 0L,
        driveFileId: String? = null,
        pendingUpload: Boolean = false,
        backupFailed: Boolean = false,
        filePath: String? = null
    ) = LibraryBook(
        id = id,
        title = "A Book",
        author = "An Author",
        format = format,
        origin = BookOrigin.IMPORT,
        filePath = filePath,
        driveFileId = driveFileId,
        backupAt = backupAt,
        backupBytes = backupBytes,
        pendingUpload = pendingUpload,
        backupFailed = backupFailed
    )

    // ---------------------------------------------------------------- percentage

    @Test
    fun `percentage is measured from transferred bytes`() {
        assertEquals(0, Transfers.percent(0, 1_000))
        assertEquals(1, Transfers.percent(10, 1_000))
        assertEquals(25, Transfers.percent(250, 1_000))
        assertEquals(50, Transfers.percent(500, 1_000))
        assertEquals(99, Transfers.percent(999, 1_000))
        assertEquals(100, Transfers.percent(1_000, 1_000))
    }

    @Test
    fun `an unknown size has no percentage, rather than a hundred`() {
        assertNull("a zero-byte file cannot be 100% done", Transfers.percent(0, 0))
        assertNull("a negative size is nonsense, not progress", Transfers.percent(10, -5))
        // A file whose size has not been measured yet: bytes may be moving but there is no whole.
        assertNull(TransferProgress.preparing("b1", 0L).percent)
    }

    @Test
    fun `sending more than the file holds cannot exceed one hundred`() {
        assertEquals(100, Transfers.percent(9_999, 1_000))
        assertEquals(100, Transfers.percent(1_000, 1_000))
    }

    @Test
    fun `sent-everything and transferred-are-different-questions`() {
        val atEnd = TransferProgress.uploading("b1", 2_000, 2_000, 0L)
        assertTrue(atEnd.sentEverything)
        assertEquals(100, atEnd.percent)
        // Being at 100% of the bytes says nothing about verification, which is a different phase.
        assertEquals(TransferPhase.UPLOADING, atEnd.phase)
        val verifying = TransferProgress.verifying("b1", 2_000, 0L)
        assertEquals(TransferPhase.VERIFYING, verifying.phase)
        assertTrue(verifying.sentEverything)
    }

    @Test
    fun `sizes are formatted the way a reader expects`() {
        assertEquals("0 B", Transfers.size(0))
        assertEquals("512 B", Transfers.size(512))
        assertEquals("1.0 KB", Transfers.size(1_024))
        assertEquals("1.5 KB", Transfers.size(1_536))
        assertEquals("1.0 MB", Transfers.size(1_048_576))
        assertEquals("2.0 GB", Transfers.size(2L * 1024 * 1024 * 1024))
        assertEquals("1.5 MB of 2.0 MB", Transfers.transferred(1_572_864, 2_097_152))
        assertEquals("size unknown — 12 B sent", Transfers.transferred(12, 0))
    }

    // ---------------------------------------------------------------- badge truth

    @Test
    fun `a hundred percent transferred is not a verified cloud`() {
        val file = tempFile(4_096)
        try {
            val b = book(filePath = file.absolutePath)
            val full = TransferProgress.uploading(b.id, 4_096, 4_096, 0L)
            val uploading = BackupAudit.badge(b, full, online = true)
            assertEquals("bytes out is not bytes proven", BackupBadge.UPLOADING, uploading)
            assertFalse(
                "the cloud is only for a confirmed upload",
                uploading == BackupBadge.VERIFIED
            )

            val verifying = BackupAudit.badge(b, TransferProgress.verifying(b.id, 4_096, 0L), online = true)
            assertEquals("verification is still in flight", BackupBadge.UPLOADING, verifying)

            // Only after the record itself carries a confirmation does the cloud appear.
            val verified = b.copy(driveFileId = "drive-1", backupAt = 5L, backupBytes = 4_096)
            assertEquals(BackupBadge.VERIFIED, BackupAudit.badge(verified, null, online = true))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a zero-byte file is never drawn as complete`() {
        val b = book()
        val empty = TransferProgress.uploading(b.id, 0, 0, 0L)
        assertNull(empty.percent)
        assertFalse(empty.sentEverything)
        assertFalse(BackupAudit.badge(b, empty, online = true) == BackupBadge.VERIFIED)
    }

    @Test
    fun `upload completion clears to verified only with a stamped record`() {
        val file = tempFile(2_000)
        try {
            val done = book(
                filePath = file.absolutePath,
                driveFileId = "drive-1",
                backupAt = 10L,
                backupBytes = 2_000L
            )
            assertEquals(BackupBadge.VERIFIED, BackupAudit.badge(done, null, online = true))
            // A record without the file is the restore state, never the cloud.
            val noFile = done.copy(filePath = null)
            assertEquals(BackupBadge.RESTORE_REQUIRED, BackupAudit.badge(noFile, null, online = true))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a transient failure reads as failed online and waiting offline`() {
        val b = book(pendingUpload = true, backupFailed = true)
        val failed = TransferProgress.failed(b.id, 1_000, 4_000, "no response from Google Drive (network timed out)")
        assertEquals(BackupBadge.FAILED, BackupAudit.badge(b, failed, online = true))
        assertEquals(BackupBadge.WAITING, BackupAudit.badge(b, failed, online = false))
        assertEquals("failure keeps the byte count reached", 1_000L, failed.sentBytes)
        assertEquals(25, failed.percent)
    }

    @Test
    fun `an offline queued book is waiting, and a paused one says paused`() {
        val b = book(pendingUpload = true)
        assertEquals(BackupBadge.WAITING, BackupAudit.badge(b, null, online = false))
        assertEquals(BackupBadge.WAITING, BackupAudit.badge(b, null, online = true, queued = true))
        val paused = TransferProgress.paused(b.id, 640, 1_280, "interrupted")
        assertEquals(BackupBadge.PAUSED, BackupAudit.badge(b, paused, online = true))
        assertEquals(50, paused.percent)
    }

    @Test
    fun `a file modified after verification is no longer verified`() {
        val file = tempFile(1_000)
        try {
            val verified = book(
                filePath = file.absolutePath,
                driveFileId = "drive-1",
                backupAt = 10L,
                backupBytes = 1_000L
            )
            assertEquals(BackupBadge.VERIFIED, BackupAudit.badge(verified, null, online = true))

            // The reader re-exports the book: same path, different bytes.
            file.writeBytes(ByteArray(1_400))
            assertTrue(BackupRules.changedSinceBackup(verified, localFileExists = true))
            assertEquals(
                "a stale confirmation must not keep the cloud",
                BookBackupState.PENDING,
                BackupAudit.effective(verified)
            )
            assertEquals(BackupBadge.WAITING, BackupAudit.badge(verified, null, online = true))
            // And it goes back into the upload queue.
            assertTrue(BackupRules.needsUpload(verified, localFileExists = true))
            assertEquals(UploadDecision.SEND, BackupRules.decide(verified, localFileExists = true))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a verification failure is a failure, not a percentage on a cloud`() {
        val b = book()
        val afterVerify = TransferProgress.failed(
            b.id, 4_000, 4_000,
            "the account's copy still differs from the file after two uploads"
        )
        assertEquals(BackupBadge.FAILED, BackupAudit.badge(b, afterVerify, online = true))
        assertEquals(100, afterVerify.percent)
        assertNotNull("the reader must be told why", afterVerify.reason)
        assertFalse(BackupAudit.badge(b, afterVerify, online = true) == BackupBadge.VERIFIED)
    }

    @Test
    fun `counts never include an unverified book as verified`() {
        val file = tempFile(500)
        try {
            val verified = book(
                id = "v", filePath = file.absolutePath, driveFileId = "d", backupAt = 1L, backupBytes = 500L
            )
            val uploading = book(id = "u", filePath = file.absolutePath)
            val counts = BackupAudit.counts(
                listOf(verified, uploading),
                uploading = setOf("u")
            )
            assertEquals(1, counts.verified)
            assertEquals(1, counts.pending)
            assertFalse(counts.complete)
            assertEquals("1 verified, 1 pending", counts.summary())
        } finally {
            file.delete()
        }
    }

    // ---------------------------------------------------------------- restart

    @Test
    fun `an upload interrupted by the process dying comes back as retryable`() {
        val file = tempFile(8_192)
        try {
            val b = book(filePath = file.absolutePath, pendingUpload = true)
            val journal = TransferProgress.paused(b.id, 4_096, 8_192)
            assertEquals(
                ResumeDecision.RETRY,
                Resumable.decide(journal, b, file.length())
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a journal entry for a book that finished is dropped`() {
        val file = tempFile(8_192)
        try {
            val done = book(
                filePath = file.absolutePath, driveFileId = "d", backupAt = 9L, backupBytes = 8_192L
            )
            val journal = TransferProgress.paused(done.id, 4_096, 8_192)
            assertEquals(ResumeDecision.ALREADY_DONE, Resumable.decide(journal, done, file.length()))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a journal entry for a deleted book is dropped, not resurrected`() {
        val journal = TransferProgress.paused("gone", 100, 1_000)
        assertEquals(ResumeDecision.ALREADY_DONE, Resumable.decide(journal, null, 1_000))
    }

    @Test
    fun `a file that changed while the app was closed is sent again, not resumed`() {
        val file = tempFile(4_096)
        try {
            val b = book(filePath = file.absolutePath, pendingUpload = true)
            // The journal says it was sending a different size.
            val journal = TransferProgress.paused(b.id, 1_000, 9_999)
            assertEquals(ResumeDecision.RESEND, Resumable.decide(journal, b, file.length()))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `no journal entry means nothing to do`() {
        assertEquals(ResumeDecision.NONE, Resumable.decide(null, book(), 1_000))
    }

    @Test
    fun `the bus throttles to whole percentage points and clears when done`() {
        TransferBus.clearAll()
        TransferBus.start("b1", 1_000)
        assertEquals(TransferPhase.PREPARING, TransferBus.progressFor("b1")?.phase)

        TransferBus.sent("b1", 100, 1_000)
        assertEquals(10, TransferBus.progressFor("b1")?.percent)
        TransferBus.sent("b1", 105, 1_000)
        assertEquals("within one percent: no new value to draw", 10, TransferBus.progressFor("b1")?.percent)
        TransferBus.sent("b1", 500, 1_000)
        assertEquals(50, TransferBus.progressFor("b1")?.percent)

        TransferBus.verifying("b1", 1_000)
        assertEquals(TransferPhase.VERIFYING, TransferBus.progressFor("b1")?.phase)
        assertEquals(100, TransferBus.progressFor("b1")?.percent)

        TransferBus.clear("b1")
        assertNull(TransferBus.progressFor("b1"))
        assertTrue(TransferBus.activeIds().isEmpty())
        TransferBus.clearAll()
    }

    @Test
    fun `paused entries restored at launch are not counted as transfers`() {
        TransferBus.clearAll()
        TransferBus.restorePaused(listOf(TransferProgress.paused("b2", 10, 100)))
        assertEquals(TransferPhase.PAUSED, TransferBus.progressFor("b2")?.phase)
        assertTrue("paused is not uploading", TransferBus.activeIds().isEmpty())
        TransferBus.clearAll()
    }

    private fun tempFile(bytes: Int): File =
        File.createTempFile("lore-progress", ".epub").apply { writeBytes(ByteArray(bytes)) }
}
