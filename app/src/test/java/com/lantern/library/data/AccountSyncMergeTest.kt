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
        finished: Boolean = false
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
        driveFileId = driveFileId
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
}
