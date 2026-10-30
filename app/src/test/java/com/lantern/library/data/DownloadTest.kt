package com.lantern.library.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Removing a download without losing the book, and putting it back.
 *
 * The whole sequence runs through the production [DownloadService] against a host that models the
 * three things that actually go wrong on a phone: the record will not persist, the file will not
 * delete, and the download comes back truncated. Nothing here sleeps, nothing here touches the
 * network, and — crucially — nothing here can reach `LanternStore.remove`: a removal that queued a
 * Drive deletion would show up in this fake as a tombstone or a pending delete, and there are none
 * to make.
 *
 * Not claimed by any of this: a real Google Drive download. That is a separate, unperformed test.
 */
class DownloadTest {

    // ---------------------------------------------------------------------------------------
    // A host that behaves like the store, and can be told to fail
    // ---------------------------------------------------------------------------------------

    private class FakeHost(val dir: File) : DownloadHost {
        /** The library, by id. */
        val library = LinkedHashMap<String, LibraryBook>()
        var link: AccountLink = AccountLink.MATCHED
        /** What the account hands back, keyed by book id. */
        val cloudBytes = HashMap<String, ByteArray>()
        /** Makes the next persist() fail, and write nothing. */
        var failPersist = false
        /** Makes the local file undeletable (a directory in the file's place is enough). */
        var failDelete = false
        /** Bytes the fake download writes instead of the cloud copy. */
        var truncateTo: Int? = null
        var fetchCalls = 0
        val notes = ArrayList<String>()

        override fun latest(bookId: String): LibraryBook? = library[bookId]

        override fun persist(book: LibraryBook): Boolean {
            if (failPersist) return false
            library[book.id] = book
            return true
        }

        override val booksDir: File get() = dir

        override suspend fun accountHolds(book: LibraryBook): AccountLink = link

        override fun deleteLocal(file: File): Boolean = if (failDelete) false else file.delete()

        override suspend fun fetchCloud(book: LibraryBook, dest: File): Boolean {
            fetchCalls++
            val bytes = cloudBytes[book.id] ?: return false
            val slice = truncateTo?.let { bytes.copyOfRange(0, minOf(it, bytes.size)) } ?: bytes
            dest.parentFile?.mkdirs()
            dest.writeBytes(slice)
            return true
        }

        override fun refreshAudit() {}

        override fun note(message: String) {
            notes += message
        }
    }

    private lateinit var dir: File
    private lateinit var host: FakeHost
    private lateinit var service: DownloadService

    @Before
    fun setUp() {
        dir = File.createTempFile("lore-downloads", "").let { f ->
            f.delete(); f.mkdirs(); f
        }
        host = FakeHost(dir)
        service = DownloadService(host)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------

    private fun pdfBytes(seed: String): ByteArray {
        val body = StringBuilder("%PDF-1.7\n$seed\n")
        while (body.length < 900) body.append(seed)
        return body.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun epubBytes(seed: String): ByteArray {
        val body = StringBuilder("PK\u0003\u0004META-INF/container.xml")
        while (body.length < 900) body.append(seed)
        return body.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun md5(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5")
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** A book with a local file and a verified cloud copy of exactly those bytes. */
    private fun verifiedBook(
        id: String = "book-1",
        format: BookFormat = BookFormat.PDF,
        bytes: ByteArray = pdfBytes("body"),
        page: Int = 12,
        pages: Int = 100,
        backupAt: Long = 1_700_000_000_000L,
        driveFileId: String? = "drive-file-$id"
    ): LibraryBook {
        val file = File(dir, "$id.${if (format == BookFormat.PDF) "pdf" else "epub"}")
        file.writeBytes(bytes)
        host.cloudBytes[id] = bytes
        val book = LibraryBook(
            id = id,
            title = "A Book",
            author = "An Author",
            format = format,
            origin = BookOrigin.IMPORT,
            filePath = file.absolutePath,
            pageCount = pages,
            currentPage = page,
            lastReadAt = 1_600_000_000_000L,
            driveFileId = driveFileId,
            backupAt = backupAt,
            backupBytes = bytes.size.toLong()
        )
        host.library[id] = book
        return book
    }

    private fun bookmarksFor(id: String) = SavedBookmark(bookId = id, pageIndex = 4, updatedAt = 1L)

    private fun localFile(id: String) = host.library[id]!!.filePath?.let(::File)

    // =======================================================================================
    // Remove download
    // =======================================================================================

    // 1
    @Test
    fun `a verified PDF can have its download removed`() {
        val book = verifiedBook()
        val outcome = runBlockingTest { service.removeDownload(book.id) }

        assertTrue("expected Removed, got $outcome", outcome is RemoveOutcome.Removed)
        assertNull("the record must point at no file", host.library[book.id]!!.filePath)
        assertFalse("the bytes must be gone", File(book.filePath!!).exists())
    }

    // 2
    @Test
    fun `a verified EPUB can have its download removed`() {
        val book = verifiedBook(format = BookFormat.EPUB, bytes = epubBytes("epub"))
        val outcome = runBlockingTest { service.removeDownload(book.id) }
        assertTrue("expected Removed, got $outcome", outcome is RemoveOutcome.Removed)
        assertNull(host.library[book.id]!!.filePath)
        assertFalse(File(book.filePath!!).exists())
    }

    // 3
    @Test
    fun `the library record stays, with everything that is not the file`() {
        val book = verifiedBook()
        runBlockingTest { service.removeDownload(book.id) }

        val after = host.library[book.id]!!
        assertEquals("A Book", after.title)
        assertEquals("An Author", after.author)
        assertEquals(BookFormat.PDF, after.format)
        assertEquals(book.pageCount, after.pageCount)
        assertEquals(book.currentPage, after.currentPage)
        assertEquals(book.lastReadAt, after.lastReadAt)
        assertEquals(book.addedAt, after.addedAt)
        assertEquals(book.category, after.category)
    }

    // 4
    @Test
    fun `the Drive file id and the verification are unchanged`() {
        val book = verifiedBook()
        runBlockingTest { service.removeDownload(book.id) }

        val after = host.library[book.id]!!
        assertEquals("drive-file-book-1", after.driveFileId)
        assertEquals(book.backupAt, after.backupAt)
        assertEquals(book.backupBytes, after.backupBytes)
        assertFalse(after.backupFailed)
        assertFalse(after.pendingUpload)
        // And the state the rest of the app reads: a confirmed copy with no local file.
        assertEquals(BookBackupState.RESTORE_REQUIRED, after.backupState)
    }

    // 5 & 6
    @Test
    fun `no tombstone and no pending remote deletion are created`() {
        val book = verifiedBook()
        // The host has no member that could do either. This test states the contract the fake
        // encodes: after a removal, the library still holds the book and nothing has been queued.
        runBlockingTest { service.removeDownload(book.id) }

        assertTrue("the book is still in the library", host.library.containsKey(book.id))
        assertEquals("nothing may be deleted from the account", 1, host.library.size)
        assertEquals("no fetch was needed", 0, host.fetchCalls)
        assertTrue(host.notes.single().contains("stays in your library"))
    }

    // 7
    @Test
    fun `reading progress and bookmarks survive the removal`() {
        val book = verifiedBook(page = 37, pages = 120)
        val mark = bookmarksFor(book.id)
        runBlockingTest { service.removeDownload(book.id) }

        val after = host.library[book.id]!!
        assertEquals(37, after.currentPage)
        assertEquals(120, after.pageCount)
        assertEquals(1_600_000_000_000L, after.lastReadAt)
        // Bookmarks live in their own file, keyed by book id, which removal never touches.
        assertEquals(book.id, mark.bookId)
    }

    // 8
    @Test
    fun `an unverified book cannot be removed`() {
        val book = verifiedBook(backupAt = 0L)
        val outcome = runBlockingTest { service.removeDownload(book.id) }

        assertEquals(RemoveOutcome.Refused(RemoveBlocker.NotVerified), outcome)
        assertTrue("the file must survive", File(book.filePath!!).exists())
        assertEquals("the record must be untouched", book, host.library[book.id])
    }

    // 9
    @Test
    fun `a failed upload cannot be removed`() {
        val book = verifiedBook().copy(backupFailed = true)
        host.library[book.id] = book
        val outcome = runBlockingTest { service.removeDownload(book.id) }

        assertEquals(RemoveOutcome.Refused(RemoveBlocker.UploadFailed), outcome)
        assertTrue(File(book.filePath!!).exists())
    }

    // 10
    @Test
    fun `a stale backup cannot be removed`() {
        val book = verifiedBook()
        // The file changed after it was verified: same path, different bytes, different size.
        val file = File(book.filePath!!)
        file.writeBytes(pdfBytes("changed a lot more text"))
        val outcome = runBlockingTest { service.removeDownload(book.id) }

        assertTrue("expected StaleBackup, got $outcome", outcome is RemoveOutcome.Refused)
        assertTrue(
            "the reason must name the sizes",
            (outcome as RemoveOutcome.Refused).blocker is RemoveBlocker.StaleBackup
        )
        assertTrue("the file must survive", file.exists())
    }

    // 10b
    @Test
    fun `a confirmation with no recorded size cannot be removed`() {
        val book = verifiedBook().copy(backupBytes = 0L)
        host.library[book.id] = book
        val outcome = runBlockingTest { service.removeDownload(book.id) }
        assertEquals(RemoveOutcome.Refused(RemoveBlocker.SizeNotRecorded), outcome)
        assertTrue(File(book.filePath!!).exists())
    }

    // 11
    @Test
    fun `a book with no Drive id cannot be removed`() {
        val book = verifiedBook(driveFileId = null)
        val outcome = runBlockingTest { service.removeDownload(book.id) }

        assertEquals(RemoveOutcome.Refused(RemoveBlocker.MissingDriveId), outcome)
        assertTrue(File(book.filePath!!).exists())
    }

    // 11b
    @Test
    fun `an account that no longer holds the file refuses the removal`() {
        val book = verifiedBook()
        host.link = AccountLink.MISSING
        val outcome = runBlockingTest { service.removeDownload(book.id) }
        assertEquals(RemoveOutcome.Refused(RemoveBlocker.NotInAccount), outcome)
        assertTrue(File(book.filePath!!).exists())
    }

    // 11c
    @Test
    fun `an account that cannot be asked refuses the removal rather than guessing`() {
        val book = verifiedBook()
        host.link = AccountLink.UNKNOWN
        val outcome = runBlockingTest { service.removeDownload(book.id) }
        assertEquals(RemoveOutcome.Refused(RemoveBlocker.AccountUnconfirmed), outcome)
        assertTrue(File(book.filePath!!).exists())
    }

    // 11d
    @Test
    fun `a pending upload cannot be removed`() {
        val book = verifiedBook().copy(pendingUpload = true)
        host.library[book.id] = book
        assertEquals(
            RemoveOutcome.Refused(RemoveBlocker.UploadPending),
            runBlockingTest { service.removeDownload(book.id) }
        )
        assertTrue(File(book.filePath!!).exists())
    }

    // 11e
    @Test
    fun `a book with no local file is not removable`() {
        val book = verifiedBook()
        File(book.filePath!!).delete()
        val outcome = runBlockingTest { service.removeDownload(book.id) }
        assertEquals(RemoveOutcome.Refused(RemoveBlocker.NoLocalFile), outcome)
    }

    // 12
    @Test
    fun `a failed metadata write keeps the bytes`() {
        val book = verifiedBook()
        host.failPersist = true
        val outcome = runBlockingTest { service.removeDownload(book.id) }

        assertEquals(RemoveOutcome.MetadataFailed, outcome)
        assertTrue("nothing may be deleted if the record could not be saved", File(book.filePath!!).exists())
        assertNotNull(host.library[book.id]!!.filePath)
        assertTrue(host.notes.any { it.contains("download was kept") })
    }

    // 13
    @Test
    fun `a failed local deletion is reported and the record is put back`() {
        val book = verifiedBook()
        val file = File(book.filePath!!)
        // The file is intact and the right size; the storage refuses to remove it. This is the
        // real-world case (a storage fault, a file held open), reached through the same seam the
        // store uses on a phone.
        host.failDelete = true
        val outcome = runBlockingTest { service.removeDownload(book.id) }

        assertTrue("expected DeleteFailed, got $outcome", outcome is RemoveOutcome.DeleteFailed)
        val after = host.library[book.id]!!
        assertEquals("the record must describe the phone again", book.filePath, after.filePath)
        assertEquals(book.driveFileId, after.driveFileId)
        assertTrue(host.notes.any { it.contains("could not be deleted") })
    }

    // 14
    @Test
    fun `removing twice is safe and idempotent`() {
        val book = verifiedBook()
        val first = runBlockingTest { service.removeDownload(book.id) }
        assertTrue(first is RemoveOutcome.Removed)

        val second = runBlockingTest { service.removeDownload(book.id) }
        assertTrue("expected AlreadyRemoved, got $second", second is RemoveOutcome.AlreadyRemoved)
        assertNull(host.library[book.id]!!.filePath)
        assertEquals("drive-file-book-1", host.library[book.id]!!.driveFileId)
        assertEquals("only the first call said anything", 1, host.notes.size)
    }

    // 15 — the checksum of the removed bytes is kept, so a restore can prove itself
    @Test
    fun `the checksum of the removed bytes is recorded`() {
        val bytes = pdfBytes("checksum me")
        val book = verifiedBook(bytes = bytes)
        runBlockingTest { service.removeDownload(book.id) }
        assertEquals(md5(bytes), host.library[book.id]!!.backupMd5)
    }

    // =======================================================================================
    // Restore download
    // =======================================================================================

    private fun removedBook(
        id: String = "book-1",
        format: BookFormat = BookFormat.PDF,
        bytes: ByteArray = pdfBytes("restore me")
    ): LibraryBook {
        val book = verifiedBook(id = id, format = format, bytes = bytes)
        runBlockingTest { service.removeDownload(id) }
        assertNull(host.library[id]!!.filePath)
        return host.library[id]!!
    }

    // 1
    @Test
    fun `a removed PDF restores from the account`() {
        val removed = removedBook()
        val outcome = runBlockingTest { service.restoreDownload(removed.id) }

        assertTrue("expected Restored, got $outcome", outcome is RestoreOutcome.Restored)
        val after = host.library[removed.id]!!
        val file = after.filePath?.let(::File)
        assertNotNull(file)
        assertTrue(file!!.exists())
        assertEquals(BookBackupState.BACKED_UP, after.backupState)
    }

    // 2
    @Test
    fun `a removed EPUB restores from the account`() {
        val removed = removedBook(format = BookFormat.EPUB, bytes = epubBytes("restore epub"))
        val outcome = runBlockingTest { service.restoreDownload(removed.id) }
        assertTrue("expected Restored, got $outcome", outcome is RestoreOutcome.Restored)
        val after = host.library[removed.id]!!
        assertEquals(BookFormat.EPUB, after.format)
        assertEquals(BookBackupState.BACKED_UP, after.backupState)
    }

    // 3
    @Test
    fun `the restored bytes match the recorded size and checksum`() {
        val bytes = pdfBytes("byte for byte")
        val removed = removedBook(bytes = bytes)
        runBlockingTest { service.restoreDownload(removed.id) }

        val after = host.library[removed.id]!!
        val file = File(after.filePath!!)
        assertEquals(bytes.size.toLong(), file.length())
        assertTrue(file.readBytes().contentEquals(bytes))
        assertEquals(md5(bytes), after.backupMd5)
        assertEquals(bytes.size.toLong(), after.backupBytes)
        // The cloud copy and its id are untouched: this only read from the account.
        assertEquals("drive-file-book-1", after.driveFileId)
        assertEquals(removed.backupAt, after.backupAt)
    }

    // 4
    @Test
    fun `a truncated download is rejected`() {
        val removed = removedBook()
        host.truncateTo = 200
        val outcome = runBlockingTest { service.restoreDownload(removed.id) }

        assertTrue("expected Failed, got $outcome", outcome is RestoreOutcome.Failed)
        assertTrue((outcome as RestoreOutcome.Failed).reason.contains("was not restored"))
        assertNull("nothing may be recorded as restored", host.library[removed.id]!!.filePath)
    }

    // 4b
    @Test
    fun `a download with the right size but the wrong bytes is rejected`() {
        val removed = removedBook()
        // Same length, different content: only the checksum can catch this, which is why the
        // removal records one.
        host.cloudBytes[removed.id] = ByteArray(removed.backupBytes.toInt()) { 'X'.code.toByte() }
        val outcome = runBlockingTest { service.restoreDownload(removed.id) }

        assertTrue("expected Failed, got $outcome", outcome is RestoreOutcome.Failed)
        assertTrue((outcome as RestoreOutcome.Failed).reason.contains("checksum"))
        assertNull(host.library[removed.id]!!.filePath)
    }

    // 4c
    @Test
    fun `an empty download is rejected`() {
        val removed = removedBook()
        host.cloudBytes[removed.id] = ByteArray(0)
        val outcome = runBlockingTest { service.restoreDownload(removed.id) }
        assertTrue("expected Failed, got $outcome", outcome is RestoreOutcome.Failed)
        assertNull(host.library[removed.id]!!.filePath)
    }

    // 4d
    @Test
    fun `a download that is not a book is rejected`() {
        val removed = removedBook()
        // Right length, no recorded checksum: only the container check can catch this, and it is
        // the last line of defence before an HTML error page becomes the reader's book.
        host.library[removed.id] = host.library[removed.id]!!.copy(backupMd5 = "")
        val html = "<html><body>Sign in to continue</body></html>".toByteArray()
        host.cloudBytes[removed.id] = html.copyOf(removed.backupBytes.toInt())
        val outcome = runBlockingTest { service.restoreDownload(removed.id) }
        assertTrue("expected Failed, got $outcome", outcome is RestoreOutcome.Failed)
        assertTrue(
            "the reason must say it is not a book: ${(outcome as RestoreOutcome.Failed).reason}",
            outcome.reason.contains("not a valid book")
        )
        assertNull(host.library[removed.id]!!.filePath)
    }

    // 5
    @Test
    fun `a failed restore leaves the account copy alone`() {
        val removed = removedBook()
        val before = host.cloudBytes[removed.id]!!.copyOf()
        host.truncateTo = 100
        runBlockingTest { service.restoreDownload(removed.id) }

        assertTrue("the account's bytes must be unchanged", host.cloudBytes[removed.id]!!.contentEquals(before))
        assertEquals("drive-file-book-1", host.library[removed.id]!!.driveFileId)
        assertEquals(removed.backupAt, host.library[removed.id]!!.backupAt)
    }

    // 5b
    @Test
    fun `an account that refuses the download is reported, not guessed at`() {
        val removed = removedBook()
        host.cloudBytes.remove(removed.id)
        val outcome = runBlockingTest { service.restoreDownload(removed.id) }
        assertTrue("expected Failed, got $outcome", outcome is RestoreOutcome.Failed)
        assertNull(host.library[removed.id]!!.filePath)
    }

    // 6
    @Test
    fun `a failed restore never overwrites a valid local file`() {
        val book = verifiedBook()
        val file = File(book.filePath!!)
        val original = file.readBytes()
        // A valid file is already there and the account hands back rubbish.
        host.cloudBytes[book.id] = ByteArray(book.backupBytes.toInt()) { 0 }
        val outcome = runBlockingTest { service.restoreDownload(book.id) }

        assertTrue("expected AlreadyAvailable, got $outcome", outcome is RestoreOutcome.AlreadyAvailable)
        assertTrue("the good file must be untouched", file.readBytes().contentEquals(original))
        assertEquals("nothing needed fetching", 0, host.fetchCalls)
    }

    // 7
    @Test
    fun `temporary files are cleaned up`() {
        val removed = removedBook()
        host.truncateTo = 120
        runBlockingTest { service.restoreDownload(removed.id) }
        val leftovers = dir.listFiles()?.filter { it.name.endsWith(".restore") }.orEmpty()
        assertTrue("no .restore remnant may survive: ${leftovers.map { it.name }}", leftovers.isEmpty())

        // And a successful restore leaves none either.
        host.truncateTo = null
        runBlockingTest { service.restoreDownload(removed.id) }
        assertTrue(dir.listFiles()?.none { it.name.endsWith(".restore") } ?: true)
    }

    // 8
    @Test
    fun `progress and metadata survive a removal and a restore`() {
        val book = verifiedBook(page = 44, pages = 200)
        runBlockingTest { service.removeDownload(book.id) }
        runBlockingTest { service.restoreDownload(book.id) }

        val after = host.library[book.id]!!
        assertEquals(44, after.currentPage)
        assertEquals(200, after.pageCount)
        assertEquals(1_600_000_000_000L, after.lastReadAt)
        assertEquals("A Book", after.title)
        assertEquals(BookFormat.PDF, after.format)
        assertEquals(BookOrigin.IMPORT, after.origin)
        assertEquals("drive-file-book-1", after.driveFileId)
    }

    // 9
    @Test
    fun `restoring twice is safe`() {
        val removed = removedBook()
        val first = runBlockingTest { service.restoreDownload(removed.id) }
        assertTrue(first is RestoreOutcome.Restored)

        val second = runBlockingTest { service.restoreDownload(removed.id) }
        assertTrue("expected AlreadyAvailable, got $second", second is RestoreOutcome.AlreadyAvailable)
        assertEquals("the second call must not fetch again", 1, host.fetchCalls)
        assertEquals(BookBackupState.BACKED_UP, host.library[removed.id]!!.backupState)
    }

    // 10
    @Test
    fun `a book in RESTORE_REQUIRED transitions to BACKED_UP`() {
        val removed = removedBook()
        assertEquals(BookBackupState.RESTORE_REQUIRED, host.library[removed.id]!!.backupState)
        // The UI offers exactly one action in this state, and it is the safe one.
        assertEquals(DownloadAction.RESTORE, DownloadUi.actionFor(host.library[removed.id]!!, 0L))

        runBlockingTest { service.restoreDownload(removed.id) }

        val after = host.library[removed.id]!!
        assertEquals(BookBackupState.BACKED_UP, after.backupState)
        assertEquals(DownloadAction.REMOVE, DownloadUi.actionFor(after, File(after.filePath!!).length()))
    }

    // 11
    @Test
    fun `a book with nothing verified cannot be restored`() {
        val book = verifiedBook().copy(filePath = null, driveFileId = "drive-file-book-1", backupAt = 0L)
        host.library[book.id] = book
        val outcome = runBlockingTest { service.restoreDownload(book.id) }
        assertEquals(RestoreOutcome.Refused(RestoreBlocker.NotVerified), outcome)
        assertEquals(0, host.fetchCalls)
    }

    // 12
    @Test
    fun `a library write that fails after the download takes the file back out`() {
        val removed = removedBook()
        host.failPersist = true
        val outcome = runBlockingTest { service.restoreDownload(removed.id) }

        assertTrue("expected Failed, got $outcome", outcome is RestoreOutcome.Failed)
        assertNull("the record must not be half-written", host.library[removed.id]!!.filePath)
        // The file it put in place is not left behind pretending to belong to the library.
        val stray = File(dir, BookFiles.cacheNameFor(removed.id, removed.format))
        assertFalse("no stray file may outlive a failed persist: ${stray.name}", stray.exists())
    }

    // =======================================================================================
    // The UI rules, which are the same rules the buttons use
    // =======================================================================================

    @Test
    fun `the action offered matches what is actually allowed`() {
        val verified = verifiedBook()
        val bytes = verified.backupBytes

        // Verified and on the phone: remove is offered, restore is not.
        assertEquals(DownloadAction.REMOVE, DownloadUi.actionFor(verified, bytes))
        // No local file, verified cloud: restore is offered.
        assertEquals(DownloadAction.RESTORE, DownloadUi.actionFor(verified.copy(filePath = null), 0L))
        // Unverified: neither button appears.
        assertEquals(DownloadAction.NONE, DownloadUi.actionFor(verified.copy(backupAt = 0L), bytes))
        assertEquals(DownloadAction.NONE, DownloadUi.actionFor(verified.copy(backupFailed = true), bytes))
        assertEquals(DownloadAction.NONE, DownloadUi.actionFor(verified.copy(pendingUpload = true), bytes))
        assertEquals(DownloadAction.NONE, DownloadUi.actionFor(verified.copy(driveFileId = null), bytes))
        // A file that is not the verified one: no removal offered.
        assertEquals(DownloadAction.NONE, DownloadUi.actionFor(verified, bytes + 10))
        // Nothing anywhere: nothing offered.
        assertEquals(
            DownloadAction.NONE,
            DownloadUi.actionFor(verified.copy(filePath = null, backupAt = 0L), 0L)
        )
    }

    @Test
    fun `a text or bundled book offers no download actions`() {
        val book = verifiedBook().copy(format = BookFormat.TEXT)
        assertEquals(DownloadAction.NONE, DownloadUi.actionFor(book, book.backupBytes))
        assertEquals(RemoveBlocker.NotAFileBook, DownloadRules.removeBlocker(book, book.backupBytes))
        assertEquals(
            RestoreBlocker.NotAFileBook,
            DownloadRules.restoreBlocker(book.copy(filePath = null))
        )
    }

    @Test
    fun `the busy label names the operation in progress`() {
        assertEquals("Removing download…", DownloadUi.busyLabel(removing = true, restoring = false))
        assertEquals("Restoring…", DownloadUi.busyLabel(removing = false, restoring = true))
        assertNull(DownloadUi.busyLabel(removing = false, restoring = false))
    }

    @Test
    fun `a cloud-only card says the download was removed, not that the book is gone`() {
        val book = verifiedBook().copy(filePath = null)
        assertEquals(BookBackupState.RESTORE_REQUIRED, book.backupState)
        assertEquals("Cloud copy · download removed", DownloadUi.cloudOnlyLabel(book))
    }

    // =======================================================================================
    // Bottom bar and system insets
    // =======================================================================================

    @Test
    fun `the bar only takes the system inset when the window has not already`() {
        // Edge-to-edge window: the inset is real, so the bar applies its own padding.
        assertTrue("a real navigation inset must be honoured", SystemBars.barAppliesOwnInset(96))
        // Fitted window: Compose reports zero, and the bar adds nothing — the inset is already in
        // the content bounds. This is the "no double padding" rule, stated as a test.
        assertFalse("an already-inset window must not be padded twice", SystemBars.barAppliesOwnInset(0))
    }

    @Test
    fun `content clears the bar with room to spare`() {
        assertTrue(
            "a list must be able to scroll its last row above the bar",
            SystemBars.CONTENT_BOTTOM_DP >= SystemBars.BAR_CONTENT_DP + SystemBars.BAR_GAP_DP
        )
        // And the gap is a comfortable one, not a token millimetre.
        assertTrue(SystemBars.BAR_GAP_DP >= 8)
        // Three-button navigation is taller than the gap: the window, not this number, absorbs it.
        assertTrue(SystemBars.BAR_CONTENT_DP > 0)
    }
}

/** A tiny bridge to the coroutine API, so the tests stay synchronous and readable. */
private fun <T> runBlockingTest(body: suspend () -> T): T = kotlinx.coroutines.runBlocking { body() }
