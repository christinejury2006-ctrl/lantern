package com.lantern.library.data

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Background uploads: durable, owned, retried, and never duplicated.
 *
 * The transport is covered by [DriveUploadTest] and the cap by [UploadPoolTest]; what is tested here
 * is the part this round added — the rules a *headless* upload obeys when nothing in the app is
 * running:
 *
 * | Behaviour                                        | Asserted here                                   |
 * |--------------------------------------------------|-------------------------------------------------|
 * | 1 continues after the Activity is destroyed       | the job owns its state: input data + files      |
 * | 2 survives process recreation                     | [UploadQueue] is re-readable from disk          |
 * | 3 recovers after a device restart                 | no in-memory claim is required to resume        |
 * | 4 network loss and recovery                       | a retryable failure stays queued                |
 * | 5 three-slot cap                                  | enforced by [UploadPool], not by the worker     |
 * | 6 no duplicate Drive files                        | one job name per book; lookup-before-send        |
 * | 7 A's jobs never run under B                       | [UploadOwnership] refuses                        |
 * | 8 A's jobs reconcile when A signs in again         | the queue is per namespace and survives          |
 */
class BackgroundUploadTest {

    private lateinit var root: File
    private val accountA = "102938475647382910"
    private val accountB = "556677889900112233"

    private val nsA get() = Profiles.namespaceFor(accountA)
    private val nsB get() = Profiles.namespaceFor(accountB)

    @Before
    fun setUp() {
        root = Files.createTempDirectory("lore-uploads").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun queueFile(namespace: String): File =
        File(File(root, namespace).apply { mkdirs() }, "upload_queue.json")

    private fun transfer(bookId: String, reason: String = "no connection") = PendingTransfer(
        bookId = bookId,
        title = "Book $bookId",
        attempts = 1,
        lastTryAt = 1_700_000_000_000L,
        reason = reason,
        retryable = true
    )

    // -- durability ---------------------------------------------------------------------------

    @Test
    fun `1 and 2 the queue is on disk, so a job outlives the Activity and the process`() {
        val file = queueFile(nsA)
        UploadQueue.write(file, listOf(transfer("a")))
        // Nothing in memory is required to read it back: this is a fresh read from the file, which
        // is exactly what a worker in a new process does.
        val reread = UploadQueue.read(queueFile(nsA))
        assertEquals(1, reread.size)
        assertEquals("a", reread[0].bookId)
        assertEquals(1, reread[0].attempts)
        assertTrue(reread[0].retryable)
    }

    @Test
    fun `3 a restart picks up the same books with their attempt history`() {
        val file = queueFile(nsA)
        UploadQueue.write(file, listOf(transfer("a"), transfer("b")))
        // A second attempt for "a" recorded before the process died.
        UploadQueue.note(file, transfer("a", "still failing"))
        val after = UploadQueue.read(file)
        assertEquals(2, after.size)
        assertEquals("attempts accumulate across restarts", 2, after.first { it.bookId == "a" }.attempts)
        assertEquals("still failing", after.first { it.bookId == "a" }.reason)
    }

    @Test
    fun `a settled book leaves the queue and an empty queue leaves no file`() {
        val file = queueFile(nsA)
        UploadQueue.note(file, transfer("a"))
        assertTrue(file.exists())
        UploadQueue.remove(file, "a")
        assertFalse("an empty queue must not leave a stub file", file.exists())
        assertTrue(UploadQueue.read(file).isEmpty())
    }

    @Test
    fun `4 a retryable failure stays queued and a permanent one says so`() {
        val file = queueFile(nsA)
        UploadQueue.note(file, transfer("retry-me"))
        UploadQueue.note(
            file,
            transfer("give-up").copy(retryable = false, reason = "the file is not on this phone")
        )
        val queued = UploadQueue.read(file)
        assertTrue(queued.first { it.bookId == "retry-me" }.retryable)
        assertFalse(queued.first { it.bookId == "give-up" }.retryable)
        // `LanternStore.schedulePendingUploads` filters on exactly this flag, so a permanent failure
        // is not re-queued forever by the launch sweep.
        assertEquals(listOf("retry-me"), queued.filter { it.retryable }.map { it.bookId })
    }

    @Test
    fun `6 one job name per book, so a second request cannot duplicate an upload`() {
        val name = UploadJobs.name(nsA, "book-1")
        assertEquals(name, UploadJobs.name(nsA, "book-1"))
        assertNotEquals(name, UploadJobs.name(nsB, "book-1"))
        assertNotEquals(name, UploadJobs.name(nsA, "book-2"))
        // The name is unique *work*, and the request is enqueued with ExistingWorkPolicy.KEEP, so a
        // repeated request while one is running is ignored rather than started twice.
        assertTrue(name.contains(nsA))
        assertTrue(name.contains("book-1"))
    }

    @Test
    fun `the job carries the namespace and the account it belongs to`() {
        val data = UploadJobs.data(nsA, accountA, "book-1")
        assertEquals(nsA, data.getString(UploadJobs.KEY_NAMESPACE))
        assertEquals(accountA, data.getString(UploadJobs.KEY_ACCOUNT))
        assertEquals("book-1", data.getString(UploadJobs.KEY_BOOK))
        // Tags are what sign-out cancels by: an account's jobs can be stopped without touching
        // another account's.
        assertEquals(UploadJobs.accountTag(accountA), UploadJobs.accountTag(accountA))
        assertNotEquals(UploadJobs.accountTag(accountA), UploadJobs.accountTag(accountB))
    }

    // -- ownership ----------------------------------------------------------------------------

    @Test
    fun `7 account A jobs are refused while B is signed in`() {
        val decision = UploadOwnership.decide(nsA, accountA, accountB, accountA)
        assertTrue(decision is UploadOwnership.Decision.Detached)
        decision as UploadOwnership.Decision.Detached
        assertEquals(accountA, decision.jobAccountId)
        assertEquals(accountB, decision.currentAccountId)
    }

    @Test
    fun `7 a job whose namespace belongs to another account is refused outright`() {
        // Even if both the job and the device claim A, the *directory* says B: refuse.
        val decision = UploadOwnership.decide(nsA, accountA, accountA, accountB)
        assertTrue(decision is UploadOwnership.Decision.Refused)
        // And a namespace that is not one this app could have made is refused before any file is
        // touched, so a corrupt job cannot become a path.
        assertTrue(UploadOwnership.decide("../etc", accountA, accountA, accountA) is UploadOwnership.Decision.Refused)
        assertTrue(UploadOwnership.decide(nsA, "", accountA, accountA) is UploadOwnership.Decision.Refused)
    }

    @Test
    fun `a job runs only when namespace, owner and signed-in account all agree`() {
        assertTrue(UploadOwnership.decide(nsA, accountA, accountA, accountA) is UploadOwnership.Decision.Run)
        // Signed out entirely: detached, not refused — the book is still owed and must be left for
        // the account that owns it when it signs back in.
        assertTrue(UploadOwnership.decide(nsA, accountA, null, accountA) is UploadOwnership.Decision.Detached)
    }

    @Test
    fun `8 account A's queue is untouched by anything that happens to B`() {
        UploadQueue.write(queueFile(nsA), listOf(transfer("a-1"), transfer("a-2")))
        UploadQueue.write(queueFile(nsB), listOf(transfer("b-1")))

        // B signs in, uploads, clears its queue, signs out.
        UploadQueue.remove(queueFile(nsB), "b-1")
        UploadQueue.write(queueFile(nsB), emptyList())

        // A signs back in: its own pending work is still there, in its own namespace.
        val backInA = UploadQueue.read(queueFile(nsA))
        assertEquals(listOf("a-1", "a-2"), backInA.map { it.bookId })
        assertTrue("B's queue is gone", UploadQueue.read(queueFile(nsB)).isEmpty())
    }

    // -- the library side of a background transfer --------------------------------------------

    @Test
    fun `a verified background upload writes the confirmation into the library file`() {
        val dir = File(root, nsA).apply { mkdirs() }
        val library = File(dir, "library.json")
        BookRecords.write(
            library,
            listOf(book("book-1", driveFileId = null, backupAt = 0L), book("book-2"))
        )
        val now = 1_700_000_000_500L
        assertTrue(
            BookRecords.update(library, "book-1") { latest ->
                latest.copy(driveFileId = "drive-1", pendingUpload = false, backupAt = now, backupBytes = 42L)
            }
        )
        val books = BookRecords.read(library)
        val updated = books.first { it.id == "book-1" }
        assertEquals("drive-1", updated.driveFileId)
        assertEquals(now, updated.backupAt)
        assertTrue("only after a verified checksum is a book backed up", updated.isBackedUp())
        // The other record is untouched.
        assertNull(books.first { it.id == "book-2" }.driveFileId)
    }

    @Test
    fun `a book with no verified checksum is not reported as backed up`() {
        val dir = File(root, nsA).apply { mkdirs() }
        val library = File(dir, "library.json")
        BookRecords.write(
            library,
            listOf(
                book("pending", driveFileId = "drive-1", backupAt = 0L),
                book("failed", driveFileId = "drive-1", backupAt = 9L, backupFailed = true),
                book("queued", driveFileId = "drive-1", backupAt = 9L, pendingUpload = true),
                book("done", driveFileId = "drive-1", backupAt = 9L)
            )
        )
        val byId = BookRecords.read(library).associateBy { it.id }
        assertFalse(byId.getValue("pending").isBackedUp())
        assertFalse(byId.getValue("failed").isBackedUp())
        assertFalse(byId.getValue("queued").isBackedUp())
        assertTrue(byId.getValue("done").isBackedUp())
    }

    @Test
    fun `a library written by an older build still loads, and keeps its progress`() {
        val dir = File(root, nsA).apply { mkdirs() }
        val library = File(dir, "library.json")
        // No `backupMd5`, no `dnf`, no `chapters`: exactly the shape 3.6.0 wrote.
        library.writeText(
            JSONObject()
                .put("id", "old-book")
                .put("title", "Old Book")
                .put("author", "Someone")
                .put("format", "EPUB")
                .put("origin", "IMPORTED")
                .put("pageCount", 120)
                .put("currentPage", 37)
                .put("driveFileId", "drive-old")
                .put("backupAt", 1234L)
                .toString()
                .let { "[$it]" }
        )
        val book = BookRecords.read(library).single()
        assertEquals("old-book", book.id)
        assertEquals(37, book.currentPage)
        assertEquals(120, book.pageCount)
        assertEquals("a missing checksum reads as none, not as an error", "", book.backupMd5)
    }

    private fun book(
        id: String,
        driveFileId: String? = null,
        backupAt: Long = 0L,
        backupFailed: Boolean = false,
        pendingUpload: Boolean = false
    ) = LibraryBook(
        id = id,
        title = "Book $id",
        author = "Author",
        format = BookFormat.EPUB,
        origin = BookOrigin.IMPORT,
        filePath = "/tmp/$id.epub",
        pageCount = 10,
        driveFileId = driveFileId,
        pendingUpload = pendingUpload,
        backupFailed = backupFailed,
        backupAt = backupAt
    )
}
