package com.lantern.library.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Choosing several books at once, before anything is copied.
 *
 * The point of these tests is that identity is decided by *content*, not by filename: the same file
 * picked from two folders is one book, a renamed copy is one book, and the same bytes already on
 * the shelf are no book at all. Also that removing a row from the review sheet promotes its
 * duplicate twin instead of losing both.
 *
 * Nothing here copies a file or touches a URI: [ImportPlanner] is pure, and the store is the only
 * thing that reads bytes.
 */
class ImportPlanTest {

    private fun candidate(
        uri: String,
        name: String = uri.substringAfterLast('/'),
        format: BookFormat? = BookFormat.EPUB,
        bytes: Long = 1000L,
        head: String = "hash-$uri"
    ) = ImportCandidate(uri = uri, name = name, format = format, bytes = bytes, headHash = head)

    private fun inLibrary(title: String) = LibraryBook(
        id = "lib-$title",
        title = title,
        author = "Writer",
        format = BookFormat.EPUB,
        origin = BookOrigin.IMPORT,
        filePath = "/nonexistent/$title.epub"
    )

    // 1 -------------------------------------------------------------------------------------

    @Test
    fun `distinct files are all ready`() {
        val plan = ImportPlanner.plan(
            listOf(candidate("a.epub"), candidate("b.epub"), candidate("c.epub"))
        ) { null }
        assertEquals(3, plan.total)
        assertEquals(3, plan.readyCount)
        assertEquals(0, plan.duplicateCount)
        assertEquals("3 ready to import", plan.summary())
    }

    // 2 -------------------------------------------------------------------------------------

    @Test
    fun `the same document chosen twice is a duplicate in the selection`() {
        val plan = ImportPlanner.plan(listOf(candidate("a.epub"), candidate("a.epub"))) { null }
        assertEquals(1, plan.readyCount)
        assertTrue(plan.items[1].verdict is ImportVerdict.DuplicateInSelection)
        assertFalse("a duplicate must not be importable", plan.items[1].importable)
    }

    // 3 -------------------------------------------------------------------------------------

    @Test
    fun `different documents with identical bytes are one book, whatever they are called`() {
        // Two folders, two names, one book: the size and head hash are the identity.
        val plan = ImportPlanner.plan(
            listOf(
                candidate("Downloads/a.epub", name = "a.epub", head = "same"),
                candidate("Books/renamed.epub", name = "renamed.epub", head = "same")
            )
        ) { null }
        assertEquals(1, plan.readyCount)
        assertTrue(plan.items[1].verdict is ImportVerdict.DuplicateInSelection)
        assertEquals("1 of 2 ready", plan.summary())
    }

    // 4 -------------------------------------------------------------------------------------

    @Test
    fun `a file already on the shelf is a library duplicate`() {
        val plan = ImportPlanner.plan(listOf(candidate("a.epub"))) { inLibrary("A Book") }
        assertEquals(0, plan.readyCount)
        assertEquals(1, plan.duplicateCount)
        val verdict = plan.items[0].verdict
        assertTrue("expected a library duplicate, got $verdict", verdict is ImportVerdict.DuplicateInLibrary)
        assertEquals("A Book", (verdict as ImportVerdict.DuplicateInLibrary).title)
    }

    // 5 -------------------------------------------------------------------------------------

    @Test
    fun `unsupported and unreadable files are refused with a reason, without stopping the others`() {
        val plan = ImportPlanner.plan(
            listOf(
                candidate("notes.txt", name = "notes.txt", format = null),
                candidate("empty.epub", bytes = 0L, head = ""),
                candidate("good.epub")
            )
        ) { null }
        assertEquals(3, plan.total)
        assertEquals(1, plan.readyCount)
        assertEquals(2, plan.problemCount)
        assertTrue(plan.items[0].verdict is ImportVerdict.Unsupported)
        assertTrue(plan.items[1].verdict is ImportVerdict.Unreadable)
        assertTrue("the good file must still be ready", plan.items[2].importable)
    }

    // 6 -------------------------------------------------------------------------------------

    @Test
    fun `removing the first of a duplicate pair promotes the second`() {
        val first = candidate("a.epub", head = "same")
        val second = candidate("b.epub", head = "same")
        val plan = ImportPlanner.plan(listOf(first, second)) { null }
        assertEquals(1, plan.readyCount)

        val after = plan.without("a.epub")
        assertEquals(1, after.total)
        assertTrue("the surviving copy must become importable", after.items[0].importable)
        assertEquals(1, after.readyCount)
    }

    // 7 -------------------------------------------------------------------------------------

    @Test
    fun `a library duplicate survives an unrelated removal`() {
        val known = candidate("known.epub")
        val other = candidate("other.epub")
        val plan = ImportPlanner.plan(listOf(known, other)) { c ->
            if (c.uri == "known.epub") inLibrary("Already Here") else null
        }
        assertTrue(plan.items[0].verdict is ImportVerdict.DuplicateInLibrary)

        val after = plan.without("other.epub")
        assertTrue(
            "a fact about the shelf must not become importable by deleting another row",
            after.items[0].verdict is ImportVerdict.DuplicateInLibrary
        )
        assertEquals(0, after.readyCount)
    }

    // 8 -------------------------------------------------------------------------------------

    @Test
    fun `the summary counts only what actually happened`() {
        val rows = listOf(
            ImportProgress("a", "a.epub", ImportState.ADDED),
            ImportProgress("b", "b.epub", ImportState.ADDED),
            ImportProgress("c", "c.epub", ImportState.FAILED, "unreadable"),
            ImportProgress("d", "d.epub", ImportState.SKIPPED, "duplicate")
        )
        val summary = ImportSummary.of(rows, total = 4)
        assertEquals(4, summary.total)
        assertEquals(2, summary.added)
        assertEquals(1, summary.failed)
        assertEquals(1, summary.skipped)
        assertTrue(summary.complete)
        assertEquals("2 imported · 1 failed", summary.line())

        val midRun = ImportSummary.of(rows.take(2) + ImportProgress("c", "c.epub", ImportState.WAITING), total = 4)
        assertFalse("two of four is not complete", midRun.complete)
        assertEquals("2 of 4 imported · 2 to go", midRun.line())
    }
}
