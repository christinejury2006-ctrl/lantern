package com.lantern.library.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Choosing several books at once, before anything is copied.
 *
 * The point of these tests is that identity is decided by *content*, not by filename: the same file
 * picked from two folders is one book, a renamed copy is one book, and the same bytes already on
 * the shelf are no book at all. Also that removing a row from the review sheet promotes its
 * duplicate twin instead of losing both, and that the shelf's capacity is decided here rather than
 * discovered file by file at the end of a long run.
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
        head: String = "head-$uri",
        tail: String = "tail-$uri",
        known: Boolean = true
    ) = ImportCandidate(
        uri = uri,
        name = name,
        format = format,
        fingerprint = ImportFingerprint(bytes, head, tail, sizeKnown = known)
    )

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
        )
        assertEquals(3, plan.total)
        assertEquals(3, plan.readyCount)
        assertEquals(0, plan.duplicateCount)
        assertEquals("3 ready to import", plan.summary())
    }

    // 2 -------------------------------------------------------------------------------------

    @Test
    fun `the same document chosen twice is a duplicate in the selection`() {
        val plan = ImportPlanner.plan(listOf(candidate("a.epub"), candidate("a.epub")))
        assertEquals(1, plan.readyCount)
        assertTrue(plan.items[1].verdict is ImportVerdict.DuplicateInSelection)
        assertFalse("a duplicate must not be importable", plan.items[1].importable)
    }

    // 3 -------------------------------------------------------------------------------------

    @Test
    fun `different documents with identical bytes are one book, whatever they are called`() {
        // Two folders, two names, one book: the size and the two hashes are the identity.
        val plan = ImportPlanner.plan(
            listOf(
                candidate("Downloads/a.epub", name = "a.epub", head = "same", tail = "same"),
                candidate("Books/renamed.epub", name = "renamed.epub", head = "same", tail = "same")
            )
        )
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
                candidate("empty.epub", bytes = 0L, head = "", tail = ""),
                candidate("good.epub")
            )
        )
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
        val plan = ImportPlanner.plan(
            listOf(
                candidate("a.epub", head = "same", tail = "same"),
                candidate("b.epub", head = "same", tail = "same")
            )
        )
        assertEquals(1, plan.readyCount)

        val after = plan.without("a.epub")
        assertEquals(1, after.total)
        assertTrue("the surviving copy must become importable", after.items[0].importable)
        assertEquals(1, after.readyCount)
    }

    // 7 -------------------------------------------------------------------------------------

    @Test
    fun `a library duplicate survives an unrelated removal`() {
        val plan = ImportPlanner.plan(
            listOf(candidate("known.epub"), candidate("other.epub")),
            findInLibrary = { c -> if (c.uri == "known.epub") inLibrary("Already Here") else null }
        )
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
    fun `a file whose length could not be trusted is imported but never used to reject another`() {
        // The provider mis-stated the size, so the length is a guess. The file is still a book and
        // is imported — but a guess cannot prove two files identical, so it must not make a second
        // file a "duplicate" and it must not make the shelf refuse this one.
        val shaky = candidate("a.epub", head = "same", tail = "same", known = false)
        val other = candidate("b.epub", head = "same", tail = "same")
        val plan = ImportPlanner.plan(listOf(shaky, other))

        assertTrue("an untrusted length must not reject the file", plan.items[0].importable)
        assertTrue("nor turn a distinct file into a duplicate", plan.items[1].importable)
        assertEquals("both are imported", 2, plan.readyCount)

        val unreadable = candidate("c.epub", bytes = 0L, head = "", tail = "")
        val withUnreadable = ImportPlanner.plan(listOf(unreadable, candidate("d.epub")))
        assertTrue(
            "a file that could not be read at all is refused, with a reason",
            withUnreadable.items[0].verdict is ImportVerdict.Unreadable
        )
        assertTrue("and does not affect the next file", withUnreadable.items[1].importable)
    }

    // 9 -------------------------------------------------------------------------------------

    @Test
    fun `a selection larger than the shelf keeps only what fits`() {
        val selection = (1..250).map { i -> candidate("book$i.epub", bytes = 1000L + i) }
        val plan = ImportPlanner.plan(selection, capacity = 200)

        assertEquals(250, plan.total)
        assertEquals("exactly the room available is taken", 200, plan.readyCount)
        assertEquals("the rest are refused with a reason, not silently dropped", 50, plan.overflowCount)
        assertTrue(plan.items.last().verdict is ImportVerdict.LibraryFull)
        assertTrue(
            "the refusal must explain itself: ${plan.items.last().note()}",
            plan.items.last().note().contains("library")
        )
    }

    // 10 ------------------------------------------------------------------------------------

    @Test
    fun `duplicates do not consume shelf space`() {
        val selection = listOf(
            candidate("a1.epub", head = "h", tail = "t"),
            candidate("a2.epub", head = "h", tail = "t"), // duplicate of a1
            candidate("b.epub", head = "h2", tail = "t2")
        )
        val plan = ImportPlanner.plan(selection, capacity = 2)
        assertEquals("two distinct books fit in two places", 2, plan.readyCount)
        assertEquals(1, plan.duplicateCount)
        assertEquals(0, plan.overflowCount)
    }

    // 11 ------------------------------------------------------------------------------------

    @Test
    fun `a shelf with no room imports nothing and says why`() {
        val plan = ImportPlanner.plan(listOf(candidate("a.epub"), candidate("b.epub")), capacity = 0)
        assertEquals(0, plan.readyCount)
        assertEquals(2, plan.overflowCount)
        plan.items.forEach { item ->
            assertTrue("each row must carry the reason", item.verdict is ImportVerdict.LibraryFull)
            assertNotNull(item.note())
        }
    }

    // 12 ------------------------------------------------------------------------------------

    @Test
    fun `the summary counts only what actually happened`() {
        val rows = listOf(
            ImportProgress("a", "a.epub", ImportState.ADDED, index = 0),
            ImportProgress("b", "b.epub", ImportState.ADDED, index = 1),
            ImportProgress("c", "c.epub", ImportState.FAILED, "unreadable", index = 2),
            ImportProgress("d", "d.epub", ImportState.SKIPPED, "duplicate", index = 3)
        )
        val summary = ImportSummary.of(rows, total = 4)
        assertEquals(4, summary.total)
        assertEquals(2, summary.added)
        assertEquals(1, summary.failed)
        assertEquals(1, summary.skipped)
        assertTrue(summary.complete)
        assertEquals("2 imported · 1 failed", summary.line())

        val midRun = ImportSummary.of(
            rows.take(2) + ImportProgress("c", "c.epub", ImportState.WAITING, index = 2),
            total = 4
        )
        assertFalse("two of four is not complete", midRun.complete)
        assertEquals("2 of 4 imported · 2 to go", midRun.line())
    }
}
