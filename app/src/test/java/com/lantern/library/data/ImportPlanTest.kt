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
 * duplicate twin instead of losing both, and - since round 8.1 - that there is no shelf capacity at
 * all: two hundred and fifty chosen books are two hundred and fifty books.
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
        val plan = ImportPlanner.plan(
            selection = listOf(candidate("a.epub")),
            findInLibrary = { inLibrary("A Book") }
        )
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
    fun `two hundred and fifty books are all ready because there is no shelf limit`() {
        // This replaces the old "a selection larger than the shelf keeps only what fits" test. That
        // one asserted the two-hundred-book cap - a product rule nobody asked for. The cap is gone,
        // so this asserts its absence: every chosen book is importable, with no refusal row at all.
        val selection = (1..250).map { i -> candidate("book$i.epub", bytes = 1000L + i) }
        val plan = ImportPlanner.plan(selection)

        assertEquals(250, plan.total)
        assertEquals("every chosen book is importable", 250, plan.readyCount)
        assertEquals(0, plan.duplicateCount)
        assertEquals("250 ready to import", plan.summary())
        assertTrue(
            "no row may mention a limit: " + plan.items.map { it.note() }.distinct().filter {
                it.contains("limit", ignoreCase = true) || it.contains("full", ignoreCase = true)
            },
            plan.items.none { it.note().contains("limit", ignoreCase = true) }
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
        val plan = ImportPlanner.plan(selection)
        assertEquals("both distinct books are ready", 2, plan.readyCount)
        assertEquals(1, plan.duplicateCount)
    }

    // 11 ------------------------------------------------------------------------------------

    @Test
    fun `matching fingerprints are only a candidate - the full-content check decides`() {
        // Two different books can share a length and both sampled windows: a re-exported PDF, or a
        // provider that pads a header. The fingerprint narrows the field; the streamed full-content
        // hash has the final say, and when it says "different" both books are imported.
        val a = candidate("a.epub", head = "same", tail = "same")
        val b = candidate("b.epub", head = "same", tail = "same")

        var asked = 0
        val differ = ImportPlanner.plan(
            selection = listOf(a, b),
            sameContent = { _, _ ->
                asked++
                false
            }
        )
        assertEquals("both books are offered when the bodies differ", 2, differ.readyCount)
        assertEquals(0, differ.duplicateCount)
        assertEquals("the planner really did ask for confirmation", 1, asked)

        val confirmed = ImportPlanner.plan(selection = listOf(a, b), sameContent = { _, _ -> true })
        assertEquals("a confirmed match is still one book", 1, confirmed.readyCount)
        assertEquals(1, confirmed.duplicateCount)
        assertTrue(confirmed.items[1].verdict is ImportVerdict.DuplicateInSelection)
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
