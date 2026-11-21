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

    private fun book(keepOffline: Boolean = false) = LibraryBook(
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
        val fresh = book()
        assertFalse(fresh.keepOffline)
    }

    @Test
    fun `offline retention choice survives record serialization`() {
        val encoded = BookRecords.encode(book(keepOffline = false))
        val decoded = BookRecords.decode(encoded)

        assertEquals(false, decoded?.keepOffline)
    }

    @Test
    fun `legacy records without a checksum remain protected until re-confirmed`() {
        val oldRecord = BookRecords.encode(
            book().copy(
                driveFileId = "drive-file-123",
                backupAt = 1_700_000_000_000L,
                backupBytes = 4096L,
                backupMd5 = ""
            )
        ).apply { remove("keepOffline") }

        val decoded = BookRecords.decode(oldRecord)

        assertEquals(true, decoded?.keepOffline)
    }

    @Test
    fun `legacy records with a verified checksum migrate to managed cache`() {
        val oldRecord = BookRecords.encode(
            book().copy(
                driveFileId = "drive-file-123",
                backupAt = 1_700_000_000_000L,
                backupBytes = 4096L,
                backupMd5 = "a".repeat(32)
            )
        ).apply { remove("keepOffline") }

        assertEquals(false, BookRecords.decode(oldRecord)?.keepOffline)
    }

    @Test
    fun `legacy records without a verified Drive copy remain protected`() {
        val oldRecord = BookRecords.encode(book()).apply { remove("keepOffline") }
        val decoded = BookRecords.decode(oldRecord)

        assertEquals(true, decoded?.keepOffline)
    }

    @Test
    fun `legacy records with pending or failed backups remain protected`() {
        val pending = BookRecords.encode(
            book().copy(driveFileId = "drive-file-123", backupAt = 100L, pendingUpload = true)
        ).apply { remove("keepOffline") }
        val failed = BookRecords.encode(
            book().copy(driveFileId = "drive-file-123", backupAt = 100L, backupFailed = true)
        ).apply { remove("keepOffline") }

        assertEquals(true, BookRecords.decode(pending)?.keepOffline)
        assertEquals(true, BookRecords.decode(failed)?.keepOffline)
    }

    @Test
    fun `explicit offline retention choice always wins over migration`() {
        val pinned = BookRecords.encode(
            book(keepOffline = true).copy(
                driveFileId = "drive-file-123",
                backupAt = 1_700_000_000_000L
            )
        )
        assertEquals(true, BookRecords.decode(pinned)?.keepOffline)
    }
    @Test
    fun `backup checksum survives local library record round trip`() {
        val checksum = "0123456789abcdef0123456789abcdef"
        val encoded = BookRecords.encode(book().copy(backupMd5 = checksum))
        val decoded = BookRecords.decode(encoded)
        assertEquals(checksum, decoded?.backupMd5)
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
        assertEquals(904L, BookIo.deleteDerivedMedia(archive))
        assertFalse("derived images should be regenerable and removable", media.exists())
    }
}
