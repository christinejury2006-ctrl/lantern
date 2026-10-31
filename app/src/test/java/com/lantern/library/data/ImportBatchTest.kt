package com.lantern.library.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Importing a large batch: bounded, isolated, stoppable and retryable.
 *
 * These tests drive the production [ImportBatch] against workers that fail on purpose, so what they
 * prove is the orchestration the app actually uses: a failed file becomes one row and the loop
 * continues, a stop request ends the batch without losing what has already been added, a phone with
 * no space left fails exactly the file that did not fit, and retrying a batch never duplicates a
 * book. There is no shelf limit anywhere in these tests because the app has none: five hundred and a
 * thousand files are just longer runs of the same loop.
 *
 * What they do not prove: the Android copy itself. The copy is `BookIo` + [BookFiles], and the parts
 * that can be exercised without a device — validate, atomic commit, and the fact that an
 * interrupted copy never becomes a book — are checked at the end of this file against real files.
 */
class ImportBatchTest {

    // ---------------------------------------------------------------------------------------
    // A worker that behaves like the store, and can be told to fail
    // ---------------------------------------------------------------------------------------

    private class FakeWorker(
        /** Files whose import must throw. */
        val throwFor: MutableSet<String> = mutableSetOf(),
        /** Files that turn out to be a duplicate of something already on the shelf. */
        val duplicateFor: MutableSet<String> = mutableSetOf(),
        /**
         * Files that do not fit on the phone. The message is the one the store really produces:
         * the file's size and the space that was free, never a word about a library limit.
         */
        val noRoomFor: MutableMap<String, String> = mutableMapOf()
    ) : ImportWorker {
        /** High-water mark of concurrently imported files; must stay at 1. */
        val peakConcurrency = AtomicInteger()
        /** The shelf: content key -> title. A duplicate would show up here as a second entry. */
        val shelf = LinkedHashMap<String, String>()
        val addedOrder = CopyOnWriteArrayList<String>()
        val attempts = CopyOnWriteArrayList<String>()
        val live = AtomicInteger()

        override suspend fun import(item: ImportItem): ImportOutcome {
            val key = item.candidate.contentKey
            attempts += item.candidate.name
            // Never more than one file in flight: the claim is bounded work, not just bounded memory.
            val now = live.incrementAndGet()
            peakConcurrency.updateAndGet { previous -> maxOf(previous, now) }
            assertEquals("only one file may be imported at a time", 1, now)
            try {
                if (item.candidate.name in throwFor) {
                    throw IllegalStateException("simulated copy failure")
                }
                if (shelf.containsKey(key) || item.candidate.name in duplicateFor) {
                    return ImportOutcome.Skipped("Already in your library")
                }
                noRoomFor[item.candidate.name]?.let { return ImportOutcome.Failed(it) }
                shelf[key] = item.candidate.name
                addedOrder += key
                return ImportOutcome.Added
            } finally {
                live.decrementAndGet()
            }
        }
    }

    private fun candidate(i: Int, name: String = "book$i.epub") = ImportCandidate(
        uri = "content://docs/$i",
        name = name,
        format = BookFormat.EPUB,
        fingerprint = ImportFingerprint(1000L + i, "head$i", "tail$i")
    )

    private fun items(count: Int, from: Int = 1) =
        (from until from + count).map { i -> ImportItem(candidate(i), ImportVerdict.Ready) }

    private fun run(
        batch: ImportBatch,
        items: List<ImportItem>,
        stopped: () -> Boolean = { false }
    ): Pair<ImportSummary, List<ImportProgress>> = runBlocking {
        val rows = CopyOnWriteArrayList<ImportProgress>()
        val summary = batch.run(items, stopped = stopped) { rows += it }
        summary to rows.toList()
    }

    // 1 -------------------------------------------------------------------------------------
    // The size from the report.

    @Test
    fun `two hundred and fifty files import one at a time, in order`() {
        val worker = FakeWorker()
        val batch = ImportBatch(worker)
        val selection = items(250)

        val (summary, rows) = run(batch, selection)

        assertEquals(250, summary.total)
        assertEquals("every file must be imported", 250, summary.added)
        assertEquals(0, summary.failed)
        assertTrue("the batch must be complete", summary.complete)
        assertEquals(250, worker.shelf.size)
        assertEquals(
            "the shelf must fill in the order the reader chose",
            selection.map { it.candidate.contentKey },
            worker.addedOrder.toList()
        )
        assertEquals("one settled row per file", 250, rows.count { it.finished })
    }

    // 2 -------------------------------------------------------------------------------------

    @Test
    fun `a failure is one row and the rest of the batch continues`() {
        val worker = FakeWorker(throwFor = mutableSetOf("book3.epub", "book7.epub"))
        val batch = ImportBatch(worker)

        val (summary, rows) = run(batch, items(10))

        assertEquals("eight books must still be imported", 8, summary.added)
        assertEquals(2, summary.failed)
        assertEquals(8, worker.shelf.size)
        val failed = rows.filter { it.state == ImportState.FAILED }.map { it.name }
        assertEquals(setOf("book3.epub", "book7.epub"), failed.toSet())
        assertTrue(
            "the row must say what went wrong, not just that something did: $failed",
            rows.filter { it.state == ImportState.FAILED }.all { it.message.isNotBlank() }
        )
        // And the books after the failure were still attempted: no early exit.
        assertTrue(worker.attempts.containsAll((1..10).map { "book$it.epub" }))
    }

    // 3 -------------------------------------------------------------------------------------

    @Test
    fun `a stop request ends the batch and keeps what was already added`() {
        val worker = FakeWorker()
        val batch = ImportBatch(worker)
        val (summary, rows) = runUntilStopped(batch, items(20), stopAfterAdded = 5)

        assertEquals("the first five must stay imported", 5, summary.added)
        assertEquals("the rest are stopped, not failed", 15, summary.stopped)
        assertEquals(0, summary.failed)
        assertEquals(5, worker.shelf.size)
        assertEquals(
            "every chosen file still has a row",
            20,
            rows.distinctBy { it.uri }.size
        )
        assertTrue("nothing may claim to be finished that was not", summary.complete)
        assertEquals(
            "the sheet must be able to say what remains",
            "5 imported · stopped before the rest",
            summary.line()
        )
    }

    /**
     * Runs a batch and raises the stop flag once [stopAfterAdded] books have been added.
     *
     * The flag is raised from the progress callback, which is how the real Stop button works: the
     * batch checks it between files, so it takes effect after the file in hand has settled.
     */
    private fun runUntilStopped(
        batch: ImportBatch,
        items: List<ImportItem>,
        stopAfterAdded: Int
    ): Pair<ImportSummary, List<ImportProgress>> = runBlocking {
        val rows = CopyOnWriteArrayList<ImportProgress>()
        var added = 0
        var stop = false
        val summary = batch.run(items, stopped = { stop }) { row ->
            if (row.state == ImportState.ADDED) {
                added++
                if (added >= stopAfterAdded) stop = true
            }
            rows += row
        }
        summary to rows.toList()
    }

    // 4 -------------------------------------------------------------------------------------

    @Test
    fun `a coroutine cancellation marks the remainder stopped instead of losing it`() {
        val worker = object : ImportWorker {
            override suspend fun import(item: ImportItem): ImportOutcome {
                if (item.candidate.name == "book4.epub") throw CancellationException("app went away")
                return ImportOutcome.Added
            }
        }
        val batch = ImportBatch(worker)

        val rows = CopyOnWriteArrayList<ImportProgress>()
        val failure = runCatching {
            runBlocking { batch.run(items(10)) { rows += it } }
        }

        assertTrue("the cancellation must propagate, not be swallowed", failure.isFailure)
        assertTrue(failure.exceptionOrNull() is CancellationException)
        assertEquals("three books were added before it", 3, rows.count { it.state == ImportState.ADDED })
        assertEquals("the rest are accounted for", 7, rows.count { it.state == ImportState.STOPPED })
        assertEquals("and none of them is a failure", 0, rows.count { it.state == ImportState.FAILED })
    }

    // 5 -------------------------------------------------------------------------------------

    @Test
    fun `a phone with no space fails only the file that did not fit`() {
        // This replaces the old "a full shelf stops the batch" test. Storage exhaustion is a real
        // constraint and it is per file: the small books after a big one still fit. The batch must
        // therefore carry on, name the file, and never call this a library limit.
        val outOfSpace = "No room for this book: it needs 42.0 MB and the phone has 3.4 MB free. " +
            "Free some space, then tap Retry."
        val worker = FakeWorker(
            noRoomFor = mutableMapOf("book11.epub" to outOfSpace, "book12.epub" to outOfSpace)
        )
        val batch = ImportBatch(worker)

        val (summary, rows) = run(batch, items(50))

        assertEquals("the other forty-eight books are imported", 48, summary.added)
        assertEquals(2, summary.failed)
        assertEquals("nothing is left unattempted", 0, summary.stopped)
        assertEquals(48, worker.shelf.size)
        val failed = rows.filter { it.state == ImportState.FAILED }
        assertEquals(setOf("book11.epub", "book12.epub"), failed.map { it.name }.toSet())
        val message = failed.first().message
        assertTrue("the row must say what is needed: $message", message.contains("needs"))
        assertTrue("and what is free: $message", message.contains("free"))
        assertFalse(
            "a storage failure must never be reported as a library limit: $message",
            message.contains("library", ignoreCase = true) ||
                message.contains("limit", ignoreCase = true) ||
                message.contains("full shelf", ignoreCase = true)
        )
        // And the files after the failures were attempted: no halt.
        assertTrue(worker.attempts.contains("book13.epub"))
        assertTrue(worker.attempts.contains("book50.epub"))
    }

    // 5b ------------------------------------------------------------------------------------

    @Test
    fun `five hundred and one thousand files run through the same bounded loop`() {
        // A simulated long run: the fake worker stands in for the copy, so this proves the batch's
        // bookkeeping - order, one-at-a-time, a row per file, no drift in the counters - and not
        // that a phone can store a thousand books. Memory is bounded by construction: at most one
        // file is in flight, and nothing here holds file contents at all.
        for (size in listOf(500, 1000)) {
            val worker = FakeWorker()
            val batch = ImportBatch(worker)
            val selection = items(size)

            val (summary, rows) = run(batch, selection)

            assertEquals("$size files, all imported", size, summary.added)
            assertEquals(0, summary.failed)
            assertEquals(0, summary.stopped)
            assertTrue(summary.complete)
            assertEquals("$size books on the shelf", size, worker.shelf.size)
            assertEquals(
                "$size files in the chosen order",
                selection.map { it.candidate.contentKey },
                worker.addedOrder.toList()
            )
            assertEquals(
                "the loop never had two files open at once",
                1,
                worker.peakConcurrency.get()
            )
            assertEquals(
                "one settled row per file, with the sheet's index",
                (0 until size).toList(),
                rows.filter { it.state == ImportState.ADDED }.map { it.index }
            )
        }
    }

    // 6 -------------------------------------------------------------------------------------

    @Test
    fun `retrying a batch never duplicates the books it already added`() {
        val worker = FakeWorker(throwFor = mutableSetOf("book2.epub"))
        val batch = ImportBatch(worker)
        val selection = items(6)

        val first = run(batch, selection).first
        assertEquals(5, first.added)
        assertEquals(1, first.failed)
        val shelfAfterFirst = worker.shelf.toMap()

        // The reader retries the files that did not make it. The worker deduplicates by content, as
        // the store does; nothing that already landed may land twice.
        worker.throwFor.clear()
        val second = run(batch, selection).first

        assertEquals("the failed book is now in", 1, second.added)
        assertEquals("the other five are skipped, not re-added", 5, second.skipped)
        assertEquals("the shelf is unchanged", shelfAfterFirst.keys + worker.shelf.keys, worker.shelf.keys)
        assertEquals(6, worker.shelf.size)
        assertEquals("each book exactly once", 6, worker.addedOrder.distinct().size)
    }

    // 7 -------------------------------------------------------------------------------------

    @Test
    fun `a duplicate found late is skipped, and does not stop the batch`() {
        val worker = FakeWorker(duplicateFor = mutableSetOf("book3.epub"))
        val batch = ImportBatch(worker)

        val (summary, _) = run(batch, items(5))

        assertEquals(4, summary.added)
        assertEquals(1, summary.skipped)
        assertEquals(0, summary.failed)
    }

    // 8 -------------------------------------------------------------------------------------

    @Test
    fun `progress is reported once per file and only for that file`() {
        val worker = FakeWorker()
        val batch = ImportBatch(worker)
        val rows = CopyOnWriteArrayList<ImportProgress>()
        runBlocking { batch.run(items(20)) { rows += it } }

        val copying = rows.filter { it.state == ImportState.COPYING }
        val added = rows.filter { it.state == ImportState.ADDED }
        assertEquals("one 'copying' per file", 20, copying.size)
        assertEquals("one 'added' per file", 20, added.size)
        assertEquals("rows carry their index", (0 until 20).toList(), added.map { it.index })
        // No row is ever published for a file that is not the one being worked on.
        rows.groupBy { it.uri }.forEach { (uri, group) ->
            assertTrue(
                "every row must belong to its own file: $uri had ${group.map { it.state }}",
                group.all { it.uri == uri }
            )
        }
    }

    // 9 -------------------------------------------------------------------------------------
    // Real files: the safety claims that do not need a device.

    @Test
    fun `an interrupted copy never becomes a book`() {
        val dir = File.createTempFile("lore-import", "").let { f ->
            f.delete(); f.mkdirs(); f
        }
        try {
            val dest = File(dir, "imp_test.epub")
            val incoming = File(dir, dest.name + ".incoming")
            // A copy interrupted in the first few kilobytes of a large archive: the ZIP header is
            // there, but the entry names and the EPUB marker come later and never arrived.
            val partial = ByteArray(4000) { i -> if (i < 4) byteArrayOf(0x50, 0x4B, 0x03, 0x04)[i] else (i % 251).toByte() }
            incoming.writeBytes(partial)

            val check = BookFiles.validate(incoming, BookFormat.EPUB)
            assertTrue(
                "a truncated archive must fail the container check, got $check",
                check is BookFiles.Check.Invalid
            )
            assertFalse("and must never be moved into place", dest.exists())

            File(dir, "good.epub").writeBytes(copyOfValidEpub())
            val good = File(dir, "good.epub")
            assertEquals(
                "a whole EPUB passes the same check",
                BookFiles.Check.Ok.javaClass,
                BookFiles.validate(good, BookFormat.EPUB).javaClass
            )
            assertTrue("and its atomic commit succeeds", BookFiles.commit(good, dest))
            assertTrue("leaving the book in place", dest.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an unreadable file is reported and the batch carries on`() {
        val worker = object : ImportWorker {
            override suspend fun import(item: ImportItem): ImportOutcome =
                if (item.candidate.name == "gone.epub") {
                    ImportOutcome.Failed("The file could not be opened — it may have been moved or its permission revoked.")
                } else {
                    ImportOutcome.Added
                }
        }
        val batch = ImportBatch(worker)
        val selection = listOf(
            ImportItem(candidate(1, "gone.epub"), ImportVerdict.Ready),
            ImportItem(candidate(2, "fine.epub"), ImportVerdict.Ready)
        )

        val (summary, rows) = run(batch, selection)

        assertEquals(1, summary.added)
        assertEquals(1, summary.failed)
        assertNotNull(rows.firstOrNull { it.name == "gone.epub" && it.state == ImportState.FAILED })
    }

    /** An EPUB-shaped blob that passes [BookFiles.validate]: a ZIP with the EPUB marker. */
    private fun copyOfValidEpub(): ByteArray {
        val body = StringBuilder("PK\u0003\u0004META-INF/container.xml mimetype application/epub+zip ")
        while (body.length < 2000) body.append("chapter ")
        return body.toString().toByteArray(Charsets.ISO_8859_1)
    }
}
