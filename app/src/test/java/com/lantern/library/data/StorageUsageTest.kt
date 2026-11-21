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
}
