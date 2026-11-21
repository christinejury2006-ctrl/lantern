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
    fun `negative file sizes are treated as zero`() {
        assertEquals("0 B", StorageUsageSnapshot.formatBytes(-1L))
    }
}
