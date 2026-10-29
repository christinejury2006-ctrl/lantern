package com.lantern.library.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide what a synced library looks like.
 *
 * These are the decisions that are expensive to get wrong, and impossible to test from the phone
 * without two devices and two accounts: whether merging keeps a book that only one side has,
 * whether it keeps the reader's place, and whether it ever asks for a file the phone does not
 * have. Everything here is pure — no Android, no network, no JSON — so it runs in CI on every
 * push and fails loudly rather than quietly losing a book.
 */
class AccountSyncMergeTest {

    private fun book(
        id: String,
        title: String = "A Book",
        page: Int = 0,
        pages: Int = 100,
        lastRead: Long = 0L,
        driveFileId: String? = null,
        filePath: String? = null,
        origin: BookOrigin = BookOrigin.IMPORT,
        finished: Boolean = false,
        backupAt: Long = 0L,
        backupFailed: Boolean = false,
        pendingUpload: Boolean = false
    ) = LibraryBook(
        id = id,
        title = title,
        author = "An Author",
        format = BookFormat.EPUB,
        origin = origin,
        filePath = filePath,
        pageCount = pages,
        currentPage = page,
        lastReadAt = lastRead,
        finished = finished,
        driveFileId = driveFileId,
        backupAt = backupAt,
        backupFailed = backupFailed,
        pendingUpload = pendingUpload
    )

    private fun snapshot(
        books: List<LibraryBook>,
        bookmarks: List<SavedBookmark> = emptyList(),
        order: List<String> = emptyList(),
        at: Long = 1_000L
    ) = AccountSnapshot(
        updatedAt = at,
        userId = "user-1",
        books = books,
        bookmarks = bookmarks,
        libraryAppearance = AppearLook.libraryDefault,
        readerAppearance = AppearLook.readerDefault,
        prefs = ReadingPrefs(),
        libraryAppearAt = at,
        readerAppearAt = at,
        prefsAt = at,
        bookOrder = order
    )

    @Test
    fun `a brand new account takes the cloud library whole`() {
        val local = snapshot(emptyList())
        val cloud = snapshot(listOf(book("a"), book("b")))
        val merged = AccountSync.merge(local, cloud)
        assertEquals(listOf("a", "b"), merged.books.map { it.id })
    }

    @Test
    fun `a book only the cloud has arrives without a file and is fetched when opened`() {
        // This is the reinstall case: the record comes back, the bytes do not, and the reader
        // fetches them from the account when the book is opened. A restored book that claimed a
        // local path would fail to open instead.
        val local = snapshot(emptyList())
        val cloud = snapshot(listOf(book("a", driveFileId = "drive-1")))
        val restored = AccountSync.merge(local, cloud).books.single()
        assertEquals("drive-1", restored.driveFileId)
        assertNull(restored.filePath)
        assertTrue(!restored.localFileAvailable)
    }

    @Test
    fun `merging never duplicates a book the phone and the account both have`() {
        val local = snapshot(listOf(book("a"), book("b")))
        val cloud = snapshot(listOf(book("a"), book("b"), book("c")))
        val merged = AccountSync.merge(local, cloud)
        assertEquals(merged.books.size, merged.books.map { it.id }.distinct().size)
        assertEquals(setOf("a", "b", "c"), merged.books.map { it.id }.toSet())
    }

    @Test
    fun `syncing twice changes nothing the second time`() {
        val local = snapshot(listOf(book("a", page = 10, lastRead = 500L), book("b")))
        val cloud = snapshot(listOf(book("a", page = 3, lastRead = 100L), book("c")))
        val once = AccountSync.merge(local, cloud)
        val twice = AccountSync.merge(once, once)
        assertEquals(once.books.map { it.id to it.currentPage }, twice.books.map { it.id to it.currentPage })
    }

    @Test
    fun `the further reading position wins, whichever side it is on`() {
        val ahead = book("a", page = 40, lastRead = 900L)
        val behind = book("a", page = 12, lastRead = 200L)
        assertEquals(40, AccountSync.merge(snapshot(listOf(ahead)), snapshot(listOf(behind))).books.single().currentPage)
        assertEquals(40, AccountSync.merge(snapshot(listOf(behind)), snapshot(listOf(ahead))).books.single().currentPage)
    }

    @Test
    fun `a cloud file id survives a merge where the phone has no copy`() {
        val local = book("a", driveFileId = null)
        val cloud = book("a", driveFileId = "drive-9")
        assertEquals(
            "drive-9",
            AccountSync.merge(snapshot(listOf(local)), snapshot(listOf(cloud))).books.single().driveFileId
        )
    }

    @Test
    fun `bookmarks from both sides are kept and the newest wins for a page`() {
        val local = snapshot(
            listOf(book("a")),
            bookmarks = listOf(SavedBookmark("a", 5, 300L))
        )
        val cloud = snapshot(
            listOf(book("a")),
            bookmarks = listOf(SavedBookmark("a", 5, 100L), SavedBookmark("a", 9, 50L))
        )
        val marks = AccountSync.merge(local, cloud).bookmarks
        assertEquals(setOf(5, 9), marks.map { it.pageIndex }.toSet())
        assertEquals(300L, marks.first { it.pageIndex == 5 }.updatedAt)
    }

    @Test
    fun `a merge with no cloud copy is just the phone's own library`() {
        val local = snapshot(listOf(book("a"), book("b")))
        val merged = AccountSync.merge(local, null)
        assertEquals(listOf("a", "b"), merged.books.map { it.id })
    }

    @Test
    fun `a restored book keeps its remote id and claims no local file`() {
        val cloud = snapshot(listOf(book("a", driveFileId = "drive-1", backupAt = 555L)))
        val restored = AccountSync.merge(snapshot(emptyList()), cloud).books.single()
        assertEquals("drive-1", restored.driveFileId)
        assertEquals(555L, restored.backupAt)
        assertNull(restored.filePath)
        assertTrue(!restored.localFileAvailable)
        assertTrue(BackupRules.restorable(restored))
    }

    @Test
    fun `a merge never invents a confirmation the cloud did not have`() {
        // The cloud entry has an id but was never confirmed; reading progress must not be mistaken
        // for evidence that the file is safe.
        val cloud = snapshot(listOf(book("a", driveFileId = "drive-1", backupAt = 0L, lastRead = 9_000L)))
        val merged = AccountSync.merge(snapshot(emptyList()), cloud).books.single()
        assertEquals(0L, merged.backupAt)
        assertEquals(BookBackupState.PENDING, merged.backupState)
        assertTrue(!BackupRules.restorable(merged))
    }

    @Test
    fun `a confirmed backup survives a merge where the phone also has the file`() {
        val local = book("a", driveFileId = "drive-1", backupAt = 700L)
        val cloud = book("a", driveFileId = "drive-1", backupAt = 600L)
        assertEquals(700L, AccountSync.merge(snapshot(listOf(local)), snapshot(listOf(cloud))).books.single().backupAt)
    }

    @Test
    fun `a confirmation for a different file is not carried across`() {
        // The phone points at drive-1 and was confirmed; the cloud now points at drive-2. The
        // stamp says nothing about drive-2, so only the cloud's own stamp may be used.
        val local = book("a", driveFileId = "drive-1", backupAt = 700L)
        val cloud = book("a", driveFileId = "drive-2", backupAt = 0L)
        val merged = AccountSync.merge(snapshot(listOf(local)), snapshot(listOf(cloud))).books.single()
        assertEquals("drive-1", merged.driveFileId)
        assertEquals(700L, merged.backupAt)
    }

    @Test
    fun `repeat merges keep the remote ids stable`() {
        val local = snapshot(listOf(book("a", driveFileId = "drive-1"), book("b")))
        val cloud = snapshot(listOf(book("a", driveFileId = "drive-1"), book("c", driveFileId = "drive-3")))
        val once = AccountSync.merge(local, cloud)
        val twice = AccountSync.merge(once, once)
        assertEquals("drive-1", twice.books.first { it.id == "a" }.driveFileId)
        assertEquals("drive-3", twice.books.first { it.id == "c" }.driveFileId)
        assertEquals(3, twice.books.size)
    }

    @Test
    fun `a local failure state does not leak into the account`() {
        val local = book("a", driveFileId = "drive-1", backupAt = 0L, backupFailed = true)
        val cloud = book("a", driveFileId = "drive-1", backupAt = 900L)
        val merged = AccountSync.merge(snapshot(listOf(local)), snapshot(listOf(cloud))).books.single()
        assertTrue(!merged.backupFailed)
        assertEquals(900L, merged.backupAt)
    }
}
