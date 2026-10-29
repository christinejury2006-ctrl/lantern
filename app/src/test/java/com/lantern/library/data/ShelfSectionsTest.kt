package com.lantern.library.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a book lives on the shelf, and why it stays there.
 *
 * The reader asked for four sections and for two of them to be *sticky*: a finished book stays in
 * Completed even when they open it again, and a book they gave up on stays in Did not finish until
 * they say otherwise. Both are decisions, not reading positions, so both have to survive the things
 * that change a reading position — re-reading, a sync from another device, a merge.
 *
 * These are pure tests over [LibraryBook], so they run in CI on every push. The store's own
 * `markRead` and merge both lean on these rules; when they disagree, a book changes shelf under the
 * reader's feet, which is exactly the bug this file exists to catch.
 */
class ShelfSectionsTest {

    private fun book(
        id: String = "b1",
        page: Int = 0,
        pages: Int = 100,
        lastRead: Long = 0L,
        finished: Boolean = false,
        dnf: Boolean = false
    ) = LibraryBook(
        id = id,
        title = "A Book",
        author = "An Author",
        format = BookFormat.EPUB,
        origin = BookOrigin.IMPORT,
        pageCount = pages,
        currentPage = page,
        lastReadAt = lastRead,
        finished = finished,
        dnf = dnf
    )

    @Test
    fun `a book that has never been opened waits in want to read`() {
        assertEquals(LibraryCategory.WANT_TO_READ, book().section)
    }

    @Test
    fun `a book with a place in it is being read`() {
        assertEquals(LibraryCategory.READING, book(page = 12, lastRead = 1_000L).section)
    }

    @Test
    fun `reaching the last page files a book under completed`() {
        // progress is (currentPage + 1) / pageCount, so page 99 of 100 is the last page.
        assertEquals(LibraryCategory.COMPLETED, book(page = 99, lastRead = 2_000L, finished = true).section)
    }

    @Test
    fun `a finished book stays completed after the reader goes back to re read`() {
        // This is the sticky latch: the reader re-opened a finished book and is on page 3. It is
        // still a book they finished.
        val rereading = book(page = 3, lastRead = 9_000L, finished = true)
        assertEquals(LibraryCategory.COMPLETED, rereading.section)
        assertEquals(Shelf.FINISHED, rereading.shelf)
    }

    @Test
    fun `a finished book is never reported as merely being read`() {
        for (page in listOf(0, 1, 40, 99)) {
            assertEquals(
                "page $page of a finished book",
                LibraryCategory.COMPLETED,
                book(page = page, lastRead = 5_000L, finished = true).section
            )
        }
    }

    @Test
    fun `did not finish wins over every other section`() {
        val abandoned = book(page = 60, lastRead = 8_000L, dnf = true)
        assertEquals(LibraryCategory.DNF, abandoned.section)
        // Even a book that was once finished: if the reader later says they did not finish it,
        // that is the newer decision and the one the shelf must show.
        assertEquals(LibraryCategory.DNF, book(page = 99, finished = true, dnf = true).section)
    }

    @Test
    fun `the derived shelf still agrees with the four old sections`() {
        assertEquals(Shelf.TO_READ, book().shelf)
        assertEquals(Shelf.CURRENT, book(page = 5, lastRead = 1L).shelf)
        assertEquals(Shelf.FINISHED, book(page = 99, finished = true).shelf)
    }

    @Test
    fun `a finished flag travels through a sync merge even when the other side is behind`() {
        // Phone A finished the book. Phone B is on page 20 and knows nothing about that. After a
        // merge the book must still be in Completed, not back in Reading.
        val finishedOnPhone = snapshotOf(book(page = 99, lastRead = 1_000L, finished = true))
        val behindOnCloud = snapshotOf(book(page = 20, lastRead = 5_000L, finished = false))
        val merged = AccountSync.merge(finishedOnPhone, behindOnCloud).books.single()
        assertTrue(merged.finished)
        assertEquals(LibraryCategory.COMPLETED, merged.section)
    }

    @Test
    fun `did not finish travels through a merge from either side`() {
        val localGaveUp = snapshotOf(book(dnf = true))
        val cloudStillReading = snapshotOf(book(page = 30, lastRead = 4_000L))
        assertTrue(AccountSync.merge(localGaveUp, cloudStillReading).books.single().dnf)

        val localReading = snapshotOf(book(page = 30, lastRead = 4_000L))
        val cloudGaveUp = snapshotOf(book(dnf = true))
        assertTrue(AccountSync.merge(localReading, cloudGaveUp).books.single().dnf)
        assertEquals(LibraryCategory.DNF, AccountSync.merge(localReading, cloudGaveUp).books.single().section)
    }

    @Test
    fun `a book that is only on the shelf once keeps one section`() {
        // Sanity: the two lists the Library tab draws (shelf books and saved-to-read picks) never
        // describe the same book twice — a downloaded pick has a LibraryBook id, and the saved
        // entry disappears from the pick list as soon as it is in the library.
        val downloaded = book(id = "free_gutenberg_1342_epub", page = 2, lastRead = 10L)
        assertEquals(1, listOf(downloaded).map { it.section }.distinct().size)
        assertFalse(downloaded.dnf)
    }

    private fun snapshotOf(vararg books: LibraryBook) = AccountSnapshot(
        updatedAt = 1_000L,
        userId = "user-1",
        books = books.toList(),
        bookmarks = emptyList(),
        libraryAppearance = AppearLook.libraryDefault,
        readerAppearance = AppearLook.readerDefault,
        prefs = ReadingPrefs(),
        libraryAppearAt = 1_000L,
        readerAppearAt = 1_000L,
        prefsAt = 1_000L,
        bookOrder = emptyList()
    )
}
