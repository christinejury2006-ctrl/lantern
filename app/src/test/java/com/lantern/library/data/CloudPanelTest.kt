package com.lantern.library.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four numbers on the cloud panel.
 *
 * The panel's whole value is that the reader can add the numbers up and believe them, so these
 * tests are mostly about *exclusivity*: every book lands in exactly one bucket, a queued book is
 * never counted as uploading, a paused book is never counted as uploading, and 100% of the bytes is
 * never mistaken for a verified copy.
 *
 * Pure functions only: no files, no network, no clock.
 */
class CloudPanelTest {

    private fun book(
        id: String,
        format: BookFormat = BookFormat.EPUB,
        origin: BookOrigin = BookOrigin.IMPORT,
        local: Boolean = true,
        driveId: String? = "drive-$id",
        backupAt: Long = 1L,
        backupBytes: Long = 100L,
        pending: Boolean = false,
        failed: Boolean = false
    ) = LibraryBook(
        id = id,
        title = "Book $id",
        author = "Writer",
        format = format,
        origin = origin,
        // A real path is not needed: `localFileAvailable` checks the path, so "no file" is the
        // absent path and "file" is a path this process will never open during a pure test.
        filePath = if (local) "/nonexistent/$id.epub" else null,
        pageCount = 10,
        driveFileId = driveId,
        backupAt = backupAt,
        backupBytes = backupBytes,
        pendingUpload = pending,
        backupFailed = failed
    )

    /** The one invariant the panel must never break. */
    private fun assertConsistent(m: CloudPanel.Metrics) {
        assertEquals(
            "the buckets must add up to the shelf: $m",
            m.total,
            m.accountedFor
        )
        assertTrue("consistent must agree with the arithmetic: $m", m.consistent)
    }

    // 1 -------------------------------------------------------------------------------------

    @Test
    fun `an empty library is four zeroes and consistent`() {
        val m = CloudPanel.metrics(emptyList())
        assertEquals(0, m.total)
        assertEquals(0, m.verified)
        assertEquals(0, m.failed)
        assertEquals(0, m.uploading)
        assertConsistent(m)
    }

    // 2 -------------------------------------------------------------------------------------

    @Test
    fun `a verified local book counts as uploaded and verified, and nothing else`() {
        val m = CloudPanel.metrics(listOf(book("a")))
        assertEquals(1, m.total)
        assertEquals(1, m.verified)
        assertEquals(0, m.uploading)
        assertEquals(0, m.failed)
        assertEquals(0, m.queued)
        assertConsistent(m)
    }

    // 3 -------------------------------------------------------------------------------------

    @Test
    fun `a live upload counts as still uploading, not as verified`() {
        val progress = TransferProgress.uploading("a", 500L, 1000L, 1L)
        val m = CloudPanel.metrics(listOf(book("a", backupAt = 0L, driveId = null)), mapOf("a" to progress))
        assertEquals(1, m.uploading)
        assertEquals(0, m.verified)
        assertEquals(1, m.total)
        assertConsistent(m)
    }

    // 4 -------------------------------------------------------------------------------------

    @Test
    fun `a transfer at one hundred percent is still not verified`() {
        val progress = TransferProgress.uploading("a", 1000L, 1000L, 1L)
        val m = CloudPanel.metrics(listOf(book("a", backupAt = 0L, driveId = null)), mapOf("a" to progress))
        assertEquals("all bytes sent is not a confirmation", 1, m.uploading)
        assertEquals(0, m.verified)
        assertConsistent(m)
    }

    // 5 -------------------------------------------------------------------------------------

    @Test
    fun `a paused upload is reported separately and never counted as uploading`() {
        val progress = TransferProgress.paused("a", 400L, 1000L, "paused by you")
        val m = CloudPanel.metrics(
            books = listOf(book("a", backupAt = 0L, driveId = null)),
            transfers = mapOf("a" to progress),
            pausedUploads = setOf("a")
        )
        assertEquals(0, m.uploading)
        assertEquals(1, m.paused)
        assertEquals(1, m.total)
        assertConsistent(m)
    }

    // 6 -------------------------------------------------------------------------------------

    @Test
    fun `a queued book is not uploading`() {
        val m = CloudPanel.metrics(
            books = listOf(book("a", backupAt = 0L, driveId = null, failed = true)),
            queuedIds = setOf("a")
        )
        assertEquals(0, m.uploading)
        assertEquals(1, m.queued)
        assertConsistent(m)
    }

    // 7 -------------------------------------------------------------------------------------

    @Test
    fun `a restored cloud-only book is on the account, not a pending upload`() {
        // Confirmed in the account, no local file: `localFileAvailable` is false.
        val m = CloudPanel.metrics(listOf(book("a", local = false)))
        assertEquals(1, m.restoreRequired)
        assertEquals(0, m.queued)
        assertEquals(0, m.uploading)
        assertEquals(0, m.verified)
        assertEquals(0, m.localOnly)
        assertConsistent(m)
    }

    // 8 -------------------------------------------------------------------------------------

    @Test
    fun `bundled and text books are not counted as needing a backup`() {
        val bundled = book("b", origin = BookOrigin.BUNDLED, driveId = null, backupAt = 0L)
        val text = book("t", format = BookFormat.TEXT, driveId = null, backupAt = 0L)
        val m = CloudPanel.metrics(listOf(bundled, text))
        assertEquals(2, m.notApplicable)
        assertEquals(0, m.verified)
        assertEquals(0, m.localOnly)
        assertEquals(0, m.cloudBooks)
        assertConsistent(m)
    }

    // 9 -------------------------------------------------------------------------------------

    @Test
    fun `a mixed shelf adds up, with no book in two buckets`() {
        val books = listOf(
            book("verified"),
            book("uploading", backupAt = 0L, driveId = null),
            book("paused", backupAt = 0L, driveId = null),
            book("failed", backupAt = 0L, driveId = null, failed = true),
            book("queued", backupAt = 0L, driveId = null),
            book("remote", local = false),
            book("local", backupAt = 0L, driveId = null),
            book("bundled", origin = BookOrigin.BUNDLED, driveId = null, backupAt = 0L)
        )
        val m = CloudPanel.metrics(
            books = books,
            transfers = mapOf(
                "uploading" to TransferProgress.uploading("uploading", 10L, 100L, 1L),
                "paused" to TransferProgress.paused("paused", 10L, 100L, "paused by you")
            ),
            queuedIds = setOf("queued"),
            pausedUploads = setOf("paused")
        )
        assertEquals(8, m.total)
        assertEquals(1, m.verified)
        assertEquals(1, m.uploading)
        assertEquals(1, m.paused)
        assertEquals(1, m.failed)
        assertEquals(1, m.queued)
        assertEquals(1, m.restoreRequired)
        assertEquals(1, m.localOnly)
        assertEquals(1, m.notApplicable)
        assertConsistent(m)
        assertEquals(7, m.cloudBooks)
        // The remainder names every unfinished state, so nothing is silently folded away.
        val named = m.remainder().associate { it.first to it.second }
        assertEquals(1, named["Waiting to upload"])
        assertEquals(1, named["Paused"])
        assertEquals(1, named["On the account, not on this phone"])
        assertEquals(1, named["Not backed up yet"])
        assertEquals(1, named["No file to back up"])
    }

    // 10 ------------------------------------------------------------------------------------

    @Test
    fun `a restore in flight leaves the book on the account rather than counting it as uploading`() {
        val progress = TransferProgress.restoring("a", 300L, 1000L, 1L)
        val m = CloudPanel.metrics(
            books = listOf(book("a", local = false)),
            transfers = mapOf("a" to progress)
        )
        assertEquals(0, m.uploading)
        assertEquals(1, m.restoreRequired)
        assertFalse("a restore is not an upload", m.uploading > 0)
        assertConsistent(m)
    }

    // 11 ------------------------------------------------------------------------------------

    @Test
    fun `offline, a failure is waiting rather than failed`() {
        val m = CloudPanel.metrics(
            books = listOf(book("a", backupAt = 0L, driveId = null, failed = true)),
            online = false
        )
        assertEquals(0, m.failed)
        assertEquals(1, m.queued)
        assertConsistent(m)
    }
}
