package com.lantern.library.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertFalse(BookRecords.decode(BookRecords.encode(fresh))!!.keepOffline)
    }

    @Test
    fun `offline retention choice survives record serialization`() {
        val encoded = BookRecords.encode(book(keepOffline = false).copy(keepOfflineExplicit = true))
        val decoded = BookRecords.decode(encoded)

        assertEquals(false, decoded?.keepOffline)
        assertEquals(true, decoded?.keepOfflineExplicit)
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
    fun `legacy persisted default true migrates when its cloud copy is checksum-verified`() {
        val oldRecord = BookRecords.encode(
            book(keepOffline = true).copy(
                driveFileId = "drive-file-123",
                backupAt = 1_700_000_000_000L,
                backupBytes = 4096L,
                backupMd5 = "a".repeat(32)
            )
        ).apply { remove("keepOfflineExplicit") }

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
                backupAt = 1_700_000_000_000L,
                backupBytes = 4096L,
                backupMd5 = "a".repeat(32),
                keepOfflineExplicit = true
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
    fun `verified cloud-only upload releases original and regenerable EPUB media`() {
        val books = temp.newFolder("books")
        val archive = File(books, "novel.epub").apply { writeBytes("verified epub bytes".toByteArray()) }
        val media = BookIo.mediaDirFor(archive).apply { mkdirs() }
        File(media, "plate.webp").writeBytes(ByteArray(512))
        val bytes = archive.length()
        val checksum = BookFiles.md5(archive)
        val library = temp.newFile("release-library.json")
        val record = book().copy(
            filePath = archive.absolutePath,
            driveFileId = "drive-verified",
            backupAt = 1_700_000_000_000L,
            backupBytes = bytes,
            backupMd5 = checksum
        )
        assertTrue(BookRecords.write(library, listOf(record)))

        val outcome = VerifiedUploadRetention.release(
            library, record.id, "drive-verified", bytes, checksum
        )

        assertEquals(VerifiedUploadRetention.Outcome.RELEASED, outcome)
        assertFalse("the verified original should be removed", archive.exists())
        assertFalse("derived EPUB media should be removed with its source", media.exists())
        assertNull("the shelf should now point to the cloud copy", BookRecords.read(library).single().filePath)
        assertEquals("drive-verified", BookRecords.read(library).single().driveFileId)
    }

    @Test
    fun `explicit offline pin prevents post-upload release`() {
        val books = temp.newFolder("pinned-books")
        val archive = File(books, "pinned.pdf").apply { writeBytes("offline copy".toByteArray()) }
        val checksum = BookFiles.md5(archive)
        val library = temp.newFile("pinned-library.json")
        val record = book().copy(
            format = BookFormat.PDF,
            filePath = archive.absolutePath,
            driveFileId = "drive-pinned",
            backupAt = 1_700_000_000_000L,
            backupBytes = archive.length(),
            backupMd5 = checksum,
            keepOffline = true,
            keepOfflineExplicit = true
        )
        assertTrue(BookRecords.write(library, listOf(record)))

        val outcome = VerifiedUploadRetention.release(
            library, record.id, "drive-pinned", archive.length(), checksum
        )

        assertEquals(VerifiedUploadRetention.Outcome.KEPT_OFFLINE, outcome)
        assertTrue(archive.exists())
        assertEquals(archive.absolutePath, BookRecords.read(library).single().filePath)
    }

    @Test
    fun `post-upload release refuses local bytes that no longer match verified checksum`() {
        val books = temp.newFolder("changed-books")
        val archive = File(books, "changed.pdf").apply { writeBytes("original bytes".toByteArray()) }
        val checksum = BookFiles.md5(archive)
        archive.appendText(" changed")
        val library = temp.newFile("changed-library.json")
        val record = book().copy(
            format = BookFormat.PDF,
            filePath = archive.absolutePath,
            driveFileId = "drive-original",
            backupAt = 1_700_000_000_000L,
            backupBytes = "original bytes".toByteArray().size.toLong(),
            backupMd5 = checksum
        )
        assertTrue(BookRecords.write(library, listOf(record)))

        val outcome = VerifiedUploadRetention.release(
            library, record.id, "drive-original",
            "original bytes".toByteArray().size.toLong(), checksum
        )

        assertEquals(VerifiedUploadRetention.Outcome.STALE_LOCAL, outcome)
        assertTrue("a changed source must never be deleted", archive.exists())
        assertEquals(archive.absolutePath, BookRecords.read(library).single().filePath)
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
