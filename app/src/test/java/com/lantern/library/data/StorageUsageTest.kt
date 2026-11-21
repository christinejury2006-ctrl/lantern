package com.lantern.library.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageUsageTest {
    @Test
    fun `formats bytes and kilobytes without rounding tiny values to zero`() {
        assertEquals("0 B", StorageUsageSnapshot.formatBytes(0L))
        assertEquals("512 B", StorageUsageSnapshot.formatBytes(512L))
        assertEquals("1.0 KB", StorageUsageSnapshot.formatBytes(1024L))
    }

    @Test
    fun `formats megabytes and gigabytes for device storage summaries`() {
        assertEquals("1.0 MB", StorageUsageSnapshot.formatBytes(1024L * 1024L))
        assertEquals("1.00 GB", StorageUsageSnapshot.formatBytes(1024L * 1024L * 1024L))
    }

    @Test
    fun `storage audit ranks largest files and caps the visible list`() {
        val entries = (1..12).map { index ->
            StorageFileEntry("/app/file-$index", "file-$index", index.toLong() * 100L, "Book")
        }
        val largest = StorageUsage.largest(entries)
        assertEquals(8, largest.size)
        assertEquals("file-12", largest.first().name)
        assertEquals("file-5", largest.last().name)
    }

    @Test
    fun `negative file sizes are treated as zero`() {
        assertEquals("0 B", StorageUsageSnapshot.formatBytes(-1L))
    }

    @Test
    fun `storage audit recognises incomplete transfer files separately`() {
        assertEquals(true, StorageUsage.isTemporaryFile(java.io.File("book.epub.restore")))
        assertEquals(true, StorageUsage.isTemporaryFile(java.io.File("book.pdf.part")))
        assertEquals(true, StorageUsage.isTemporaryFile(java.io.File("library.json.tmp")))
        assertEquals(true, StorageUsage.isTemporaryFile(java.io.File("cover.jpg.incoming")))
        assertEquals(false, StorageUsage.isTemporaryFile(java.io.File("complete-book.epub")))
    }


    @Test
    fun `storage roots are canonicalised and de duplicated`() {
        val root = java.nio.file.Files.createTempDirectory("lore-storage-root").toFile()
        try {
            val alias = java.io.File(root, "nested/..")
            val roots = StorageUsage.uniqueRoots(listOf(root, alias, null))
            assertEquals(1, roots.size)
            assertEquals(root.canonicalPath, roots.single().canonicalPath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `installed package bytes have a separate display value`() {
        val snapshot = StorageUsageSnapshot(bytes = 4096L, files = 2, installedBytes = 2048L)
        assertEquals("2.0 KB", snapshot.installedSize())
    }
}
