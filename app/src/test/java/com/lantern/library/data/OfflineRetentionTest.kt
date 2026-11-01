package com.lantern.library.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OfflineRetentionTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun book(keepOffline: Boolean = true) = LibraryBook(
        id = "offline-test",
        title = "Offline test",
        author = "Lore",
        format = BookFormat.EPUB,
        origin = BookOrigin.IMPORT,
        filePath = null,
        keepOffline = keepOffline
    )

    @Test
    fun `new books start as managed cache rather than pinned offline downloads`() {
        val fresh = book().copy(keepOffline = false)
        assertFalse(fresh.keepOffline)
    }

    @Test
    fun `offline retention choice survives record serialization`() {
        val encoded = BookRecords.encode(book(keepOffline = false))
        val decoded = BookRecords.decode(encoded)

        assertEquals(false, decoded?.keepOffline)
    }

    @Test
    fun `older records default to protected offline downloads`() {
        val oldRecord = BookRecords.encode(book()).apply { remove("keepOffline") }
        val decoded = BookRecords.decode(oldRecord)

        assertEquals(true, decoded?.keepOffline)
    }

    @Test
    fun `library file round trip preserves the retention choice`() {
        val file = temp.newFile("library.json")
        assertTrue(BookRecords.write(file, listOf(book(keepOffline = false))))

        val decoded = BookRecords.read(file).single()
        assertFalse(decoded.keepOffline)
    }

    @Test
    fun `storage accounting includes nested EPUB media files`() {
        val root = temp.newFolder("books")
        val archive = File(root, "novel.epub").apply { writeBytes(ByteArray(100)) }
        val media = File(root, "media_novel.epub").apply { mkdirs() }
        File(media, "illustration.jpg").writeBytes(ByteArray(900))
        File(media, ".unpacked").writeText("done")

        assertEquals(100L + 900L + 4L, BookIo.directoryBytes(root))
        assertEquals(media.canonicalFile, BookIo.mediaDirFor(archive).canonicalFile)
    }
}
