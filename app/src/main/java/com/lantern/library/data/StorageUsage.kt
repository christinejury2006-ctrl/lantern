package com.lantern.library.data

import android.content.Context
import java.io.File

data class StorageFileEntry(val path: String, val name: String, val bytes: Long, val category: String)

data class StorageUsageSnapshot(
    val bytes: Long,
    val files: Int,
    val bookBytes: Long = 0L,
    val derivedMediaBytes: Long = 0L,
    val coverBytes: Long = 0L,
    val cloudBytes: Long = 0L,
    val cacheBytes: Long = 0L,
    val internalBytes: Long = 0L,
    val otherBytes: Long = 0L,
    val largestFiles: List<StorageFileEntry> = emptyList()
) {
    fun displaySize(): String = formatBytes(bytes)
    fun booksSize(): String = formatBytes(bookBytes)
    fun derivedMediaSize(): String = formatBytes(derivedMediaBytes)
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
        var derivedMedia = 0L
        var covers = 0L
        var cloud = 0L
        var cache = 0L
        var internal = 0L
        var other = 0L
        val entries = ArrayList<StorageFileEntry>()

        fun add(root: File?) {
            if (root == null || !root.exists()) return
            runCatching {
                root.walkTopDown().filter { it.isFile }.forEach { file ->
                    val size = runCatching { file.length() }.getOrDefault(0L).coerceAtLeast(0L)
                    total = saturatingAdd(total, size)
                    count++
                    val category = when {
                        isDerivedMediaFile(file, externalFilesRoot) -> {
                            derivedMedia = saturatingAdd(derivedMedia, size)
                            "EPUB media"
                        }
                        classifyFile(file, externalFilesRoot, "books") -> {
                            books = saturatingAdd(books, size)
                            "Book"
                        }
                        classifyFile(file, externalFilesRoot, "covers") -> {
                            covers = saturatingAdd(covers, size)
                            "Cover"
                        }
                        classifyFile(file, externalFilesRoot, "cloud") -> {
                            cloud = saturatingAdd(cloud, size)
                            "Cloud transfer"
                        }
                        root == externalCacheRoot || isInside(file, File(internalRoot, "cache")) ||
                            isInside(file, File(internalRoot, "code_cache")) -> {
                            cache = saturatingAdd(cache, size)
                            "Cache"
                        }
                        root == internalRoot -> {
                            internal = saturatingAdd(internal, size)
                            "App data"
                        }
                        else -> {
                            other = saturatingAdd(other, size)
                            "Other"
                        }
                    }
                    val entry = StorageFileEntry(file.absolutePath, file.name, size, category)
                    if (entries.size < 8) {
                        entries += entry
                    } else {
                        val smallestIndex = entries.indices.minByOrNull { entries[it].bytes }
                        if (smallestIndex != null && size > entries[smallestIndex].bytes) {
                            entries[smallestIndex] = entry
                        }
                    }
                }
            }
        }

        // Each root is traversed once. These are separate Android app-private locations.
        add(internalRoot)
        add(externalFilesRoot)
        add(externalCacheRoot)

        return StorageUsageSnapshot(
            total, count, books, derivedMedia, covers, cloud, cache, internal, other,
            largest(entries)
        )
    }

    internal fun largest(entries: List<StorageFileEntry>): List<StorageFileEntry> =
        entries.sortedByDescending { it.bytes }.take(8)

    private fun isInside(file: File, directory: File): Boolean = runCatching {
        val target = directory.canonicalFile
        var current: File? = file.canonicalFile
        while (current != null) {
            if (current == target) return@runCatching true
            current = current.parentFile
        }
        false
    }.getOrDefault(false)

    private fun isDerivedMediaFile(file: File, externalRoot: File?): Boolean {
        if (externalRoot == null) return false
        return runCatching {
            val root = externalRoot.canonicalFile
            val relative = file.canonicalFile.relativeTo(root).path
            relative.startsWith("books" + File.separator + "media_")
        }.getOrDefault(false)
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
