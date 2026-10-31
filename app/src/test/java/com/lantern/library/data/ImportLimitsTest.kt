package com.lantern.library.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * The library has no ceiling, and that is a product requirement.
 *
 * Round 8 shipped an import path that refused the two hundred and first book, and a review sheet
 * that announced "your library holds 200 imported books". The number was never asked for, and a
 * single large batch could hit it. Round 8.1 removed it from the planner, the batch runner, the
 * store and the sheet; this file is what stops it coming back.
 *
 * The last test reads the production sources. It is a blunt instrument, but it is the only kind that
 * catches a limit being reintroduced in a file nobody thinks of as "the import path" - which is
 * exactly how the first one survived four rounds of tests that asserted it was there.
 */
class ImportLimitsTest {

    // 1 -------------------------------------------------------------------------------------

    @Test
    fun `a thousand chosen books are all ready, with nothing refused for want of room`() {
        val selection = (1..1000).map { i ->
            ImportCandidate(
                uri = "content://docs/$i",
                name = "book$i.epub",
                format = BookFormat.EPUB,
                fingerprint = ImportFingerprint(1000L + i, "head$i", "tail$i")
            )
        }
        val plan = ImportPlanner.plan(selection)

        assertEquals(1000, plan.total)
        assertEquals("every chosen book is importable", 1000, plan.readyCount)
        assertEquals(0, plan.problemCount)
        assertEquals(0, plan.duplicateCount)
        assertEquals("all of them go to the batch, in order", selection.map { it.uri }, plan.readyItems().map { it.candidate.uri })
    }

    // 2 -------------------------------------------------------------------------------------

    @Test
    fun `every reason an import can fail names a real resource, never a book count`() {
        // The sheet's wording is the reader's only explanation. These are the strings the store can
        // produce, and none of them may suggest the app decided the reader had enough books.
        val notes = listOf(
            ImportVerdict.Unsupported("Only EPUB and PDF books can be imported."),
            ImportVerdict.Unreadable("The file could not be read."),
            ImportVerdict.DuplicateInSelection("Earlier.epub"),
            ImportVerdict.DuplicateInLibrary("Already Here"),
            ImportVerdict.Ready
        ).map { ImportItem(ImportCandidate("content://x", "x.epub", BookFormat.EPUB), it).note() }

        notes.forEach { note ->
            assertFalse("no row may imply a shelf limit: $note", note.contains("limit", true))
            assertFalse("nor a full shelf: $note", note.contains("full", true))
            assertFalse("nor a maximum count: $note", note.contains("200", true))
        }
    }

    // 3 -------------------------------------------------------------------------------------

    @Test
    fun `storage exhaustion is recognised from the message Android actually uses`() {
        assertTrue(
            "ENOSPC is what a full disk reports",
            BookIo.isOutOfSpace(IOException("write failed: ENOSPC (No space left on device)"))
        )
        assertTrue(
            "and it can arrive wrapped",
            BookIo.isOutOfSpace(
                IOException("failed to write", IOException("No space left on device"))
            )
        )
        assertFalse(
            "a revoked permission is not a full disk",
            BookIo.isOutOfSpace(IOException("open failed: EACCES (Permission denied)"))
        )
        assertFalse("nor is a closed stream", BookIo.isOutOfSpace(IOException("Stream closed")))
    }

    // 4 -------------------------------------------------------------------------------------

    @Test
    fun `no fixed book-count limit survives in the production import path`() {
        val roots = listOf(File("."), File("app"), File(".."), File("../.."))
            .map { it.absoluteFile }
            .map { File(it, "src/main/java/com/lantern/library") }
            .filter { it.isDirectory }
        assumeTrue("production sources not reachable from ${File("").absolutePath}", roots.isNotEmpty())
        val library = roots.first()

        val watched = listOf(
            "data/ImportPlan.kt",
            "data/ImportBatch.kt",
            "data/ImportMeasure.kt",
            "data/BookIo.kt",
            "data/LanternStore.kt",
            "ui/components/ImportSheet.kt"
        ).map { File(library, it) }

        watched.forEach { file -> assertTrue("${file.path} must exist", file.isFile) }

        // Identifiers, not prose. Comments in these files explain what was removed, and a comment
        // saying "there was once a cap here" is the opposite of a violation.
        val forbidden = listOf(
            "MAX_USER_BOOKS",
            "MAX_BOOKS",
            "MAX_LIBRARY_BOOKS",
            "LIBRARY_FULL",
            "LibraryFull",
            "libraryFull",
            "ImportOutcome.Full",
            "remainingUserBookCapacity",
            "userBookCount",
            "overflowCount"
        )
        val offenders = ArrayList<String>()
        watched.forEach { file ->
            val code = file.readLines()
                .filterNot { line ->
                    val trimmed = line.trimStart()
                    trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
                }
                .joinToString("\n")
            forbidden.forEach { token ->
                if (code.contains(token)) offenders += "${file.name}: $token"
            }
        }
        assertTrue("no fixed book-count limit may remain: $offenders", offenders.isEmpty())
    }

    // 5 -------------------------------------------------------------------------------------

    @Test
    fun `the production store confirms duplicates by full content, not by the sampled windows`() {
        // The planner can only decide what it is told. If the store ever stopped passing a
        // confirmer, every duplicate verdict would go back to resting on 128 KiB of each file.
        val source = listOf(File("."), File("app"), File(".."), File("../.."))
            .map { File(it.absoluteFile, "src/main/java/com/lantern/library/data/LanternStore.kt") }
            .firstOrNull { it.isFile }
        assumeTrue("LanternStore.kt not reachable from ${File("").absolutePath}", source != null)
        val text = source!!.readText()

        assertTrue("the plan must be given a content confirmer", text.contains("sameContent ="))
        assertTrue(
            "and the confirmer must stream whole documents",
            text.contains("fullMd5OfDocument")
        )
    }
}
