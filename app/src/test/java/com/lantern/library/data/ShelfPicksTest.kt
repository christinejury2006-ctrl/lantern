package com.lantern.library.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfPicksTest {

    private fun book(
        id: String,
        category: String = "Library",
        addedAt: Long = 0L,
        currentPage: Int = 0,
        lastReadAt: Long = 0L
    ) = LibraryBook(
        id = id,
        title = id,
        author = "Someone",
        format = BookFormat.TEXT,
        origin = BookOrigin.BUNDLED,
        pageCount = 100,
        currentPage = currentPage,
        addedAt = addedAt,
        lastReadAt = lastReadAt,
        category = category
    )

    @Test
    fun `a book already started is never picked`() {
        val books = listOf(
            book("reading", currentPage = 12, lastReadAt = 10L),
            book("done", currentPage = 100),
            book("fresh")
        )
        assertEquals(setOf("fresh"), ShelfPicks.picks(books))
    }

    @Test
    fun `picks follow the category of the book read most recently`() {
        val books = listOf(
            book("history-read", category = "History", currentPage = 3, lastReadAt = 5L),
            book("poetry-read", category = "Poetry", currentPage = 3, lastReadAt = 99L),
            book("history-old", category = "History", addedAt = 1_000L),
            book("poetry-new", category = "Poetry", addedAt = 900L),
            book("science-newest", category = "Science", addedAt = 9_999L)
        )
        // Poetry was read last, so poetry comes first; then the rest by when they arrived.
        val picks = ShelfPicks.picks(books, count = 3).toList()
        assertEquals("poetry-new", picks[0])
        assertTrue(picks.contains("science-newest"))
        assertTrue(picks.contains("history-old"))
    }

    @Test
    fun `the newest book in a category comes first`() {
        val books = listOf(
            book("older", addedAt = 10L),
            book("newer", addedAt = 20L),
            book("newest", addedAt = 30L)
        )
        assertEquals(setOf("newest", "newer", "older"), ShelfPicks.picks(books))
    }

    @Test
    fun `nothing unread means nothing picked rather than an invented pick`() {
        val books = listOf(
            book("a", currentPage = 4, lastReadAt = 1L),
            book("b", currentPage = 100)
        )
        assertTrue(ShelfPicks.picks(books).isEmpty())
    }

    @Test
    fun `picks are stable, so a ribbon never moves between recompositions`() {
        val books = (1..20).map { book("b$it", addedAt = it.toLong()) }
        assertEquals(ShelfPicks.picks(books), ShelfPicks.picks(books))
        assertEquals(ShelfPicks.PICK_COUNT, ShelfPicks.picks(books).size)
    }

    @Test
    fun `an empty library picks nothing`() {
        assertTrue(ShelfPicks.picks(emptyList()).isEmpty())
    }
}
