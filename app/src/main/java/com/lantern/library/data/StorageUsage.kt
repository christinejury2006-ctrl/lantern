package com.lantern.library.data

import android.content.Context
import java.io.File

data class StorageUsageSnapshot(
    val bytes: Long,
    val files: Int,
    val bookBytes: Long = 0L,
    val coverBytes: Long = 0L,
    val cloudBytes: Long = 0L,
    val cacheBytes: Long = 0L,
    val internalBytes: Long = 0L,
    val otherBytes: Long = 0L
) {
    fun displaySize(): String = formatBytes(bytes)
    fun booksSize(): String = formatBytes(bookBytes)
    fun coversSize(): String = formatBytes(coverBytes)
    fun cloudSize(): String = formatBytes(cloudBytes)
    fun cacheSize(): String = formatBytes(cacheBytes)
    fun internalSize(): String = formatBytes(internalBytes)
    fun otherSize(): String = formatBytes(otherBytes)

    companion object {
        fun formatBytes(value: Long): String {
            val safe = value.coerceAtLeast(0L)
            return when {
                safe < 1024L -> "$safe B"
                safe < 1024L * 1024L -> "%.1f KB".format(safe / 1024.0)
                safe < 1024L * 1024L * 1024L -> "%.1f MB".format(safe / (1024.0 * 1024.0))
                else -> "%.2f GB".format(safe / (1024.0 * 1024.0 * 1024.0))
            }
        }
    }
}

/**
 * Measures all app-owned storage roots. Android's internal dataDir does NOT include
 * getExternalFilesDir(), where Lore keeps imported books, extracted covers and cloud restores.
 * Those external app-private files must be counted separately or the displayed total is false.
 */
object StorageUsage {
    fun measure(context: Context): StorageUsageSnapshot {
        val internalRoot = context.dataDir
        val externalFilesRoot = context.getExternalFilesDir(null)
        val externalCacheRoot = context.externalCacheDir

        var total = 0L
        var count = 0
        var books = 0L
        var covers = 0L
        var cloud = 0L
        var cache = 0L
        var internal = 0L
        var other = 0L

        fun add(root: File?, classify: (File) -> Unit) {
            if (root == null || !root.exists()) return
            runCatching {
                root.walkTopDown().filter { it.isFile }.forEach { file ->
                    val size = runCatching { file.length() }.getOrDefault(0L).coerceAtLeast(0L)
                    total = saturatingAdd(total, size)
                    count++
                    when {
                        classifyFile(file, externalFilesRoot, "books") -> books = saturatingAdd(books, size)
                        classifyFile(file, externalFilesRoot, "covers") -> covers = saturatingAdd(covers, size)
                        classifyFile(file, externalFilesRoot, "cloud") -> cloud = saturatingAdd(cloud, size)
                        root == externalCacheRoot -> cache = saturatingAdd(cache, size)
                        root == internalRoot -> internal = saturatingAdd(internal, size)
                        else -> other = saturatingAdd(other, size)
                    }
                }
            }
        }

        // Each root is traversed once. These are separate Android app-private locations.
        add(internalRoot) {}
        add(externalFilesRoot) {}
        add(externalCacheRoot) {}

        return StorageUsageSnapshot(total, count, books, covers, cloud, cache, internal, other)
    }

    private fun classifyFile(file: File, externalRoot: File?, bucket: String): Boolean {
        if (externalRoot == null) return false
        return runCatching {
            val root = externalRoot.canonicalFile
            val target = file.canonicalFile
            val relative = target.relativeTo(root).path
            relative == bucket || relative.startsWith(bucket + File.separator)
        }.getOrDefault(false)
    }

    private fun saturatingAdd(a: Long, b: Long): Long =
        if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
}
