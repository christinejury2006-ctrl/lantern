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
    val temporaryBytes: Long = 0L,
    val installedBytes: Long = 0L,
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
    fun temporarySize(): String = formatBytes(temporaryBytes)
    fun installedSize(): String = formatBytes(installedBytes)

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
 * Measures Lore's private data and every app-specific external storage volume.
 *
 * Android exposes more than one app-private external location: a device can have primary and
 * secondary/shared volumes, separate external cache directories, media directories and OBB
 * directories. Scanning only getExternalFilesDir(null) misses files stored on any additional
 * volume. Installed APK/split APK files are also reported separately because Android's app-info
 * screen includes the installed package size while these files live outside dataDir.
 *
 * This is an app-visible audit, not a byte-for-byte replica of Android's StorageStats: the OS may
 * additionally account for optimised code and package-manager overhead that an app cannot scan.
 */
object StorageUsage {
    fun measure(context: Context): StorageUsageSnapshot {
        val internalRoot = context.dataDir
        val externalFilesRoots = uniqueRoots(context.getExternalFilesDirs(null).toList())
        val externalCacheRoots = uniqueRoots(context.externalCacheDirs.toList())
        val externalMediaRoots = uniqueRoots(context.externalMediaDirs.toList())
        val obbRoots = uniqueRoots(context.obbDirs.toList())

        var total = 0L
        var count = 0
        var books = 0L
        var derivedMedia = 0L
        var covers = 0L
        var cloud = 0L
        var cache = 0L
        var internal = 0L
        var other = 0L
        var temporary = 0L
        var installed = 0L
        val entries = ArrayList<StorageFileEntry>()
        val seenFiles = HashSet<String>()

        fun record(file: File, category: String, size: Long) {
            val identity = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            if (!seenFiles.add(identity)) return

            total = saturatingAdd(total, size)
            count++
            when (category) {
                "Temporary transfer" -> temporary = saturatingAdd(temporary, size)
                "EPUB media" -> derivedMedia = saturatingAdd(derivedMedia, size)
                "Book" -> books = saturatingAdd(books, size)
                "Cover" -> covers = saturatingAdd(covers, size)
                "Cloud transfer" -> cloud = saturatingAdd(cloud, size)
                "Cache" -> cache = saturatingAdd(cache, size)
                "App data" -> internal = saturatingAdd(internal, size)
                "App installation" -> installed = saturatingAdd(installed, size)
                else -> other = saturatingAdd(other, size)
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

        fun addRoot(root: File?) {
            if (root == null || !root.exists() || !root.isDirectory) return
            runCatching {
                root.walkTopDown().filter { it.isFile }.forEach { file ->
                    val size = runCatching { file.length() }.getOrDefault(0L).coerceAtLeast(0L)
                    val category = when {
                        isTemporaryFile(file) -> "Temporary transfer"
                        isDerivedMediaFile(file, externalFilesRoots) -> "EPUB media"
                        isInsideAny(file, externalFilesRoots, "books") -> "Book"
                        isInsideAny(file, externalFilesRoots, "covers") -> "Cover"
                        isInsideAny(file, externalFilesRoots, "cloud") -> "Cloud transfer"
                        isInsideAny(file, externalCacheRoots) ||
                            isInside(file, File(internalRoot, "cache")) ||
                            isInside(file, File(internalRoot, "code_cache")) -> "Cache"
                        isInside(file, internalRoot) -> "App data"
                        else -> "Other"
                    }
                    record(file, category, size)
                }
            }
        }

        // Canonical de-duplication prevents overlapping roots or aliases being counted twice.
        val roots = uniqueRoots(
            listOf(internalRoot) + externalFilesRoots + externalCacheRoots +
                externalMediaRoots + obbRoots
        )
        roots.forEach(::addRoot)

        // Android's app storage page includes installed APKs. They are not in dataDir, so include
        // base and split packages as a separate, read-only category for a more useful comparison.
        val appInfo = context.applicationInfo
        val packageFiles = listOfNotNull(appInfo.sourceDir) + appInfo.splitSourceDirs.orEmpty()
        packageFiles.forEach { path ->
            val file = File(path)
            if (file.isFile) {
                val size = runCatching { file.length() }.getOrDefault(0L).coerceAtLeast(0L)
                record(file, "App installation", size)
            }
        }

        return StorageUsageSnapshot(
            bytes = total,
            files = count,
            bookBytes = books,
            derivedMediaBytes = derivedMedia,
            coverBytes = covers,
            cloudBytes = cloud,
            cacheBytes = cache,
            internalBytes = internal,
            otherBytes = other,
            temporaryBytes = temporary,
            installedBytes = installed,
            largestFiles = largest(entries)
        )
    }

    internal fun uniqueRoots(roots: List<File?>): List<File> {
        val seen = HashSet<String>()
        return roots.filterNotNull().filter { root ->
            val identity = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
            seen.add(identity)
        }
    }

    internal fun isTemporaryFile(file: File): Boolean =
        file.name.lowercase().let { name ->
            name.endsWith(".restore") || name.endsWith(".part") ||
                name.endsWith(".tmp") || name.endsWith(".incoming")
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

    private fun isInsideAny(file: File, roots: List<File>, child: String? = null): Boolean =
        roots.any { root -> isInside(file, if (child == null) root else File(root, child)) }

    private fun isDerivedMediaFile(file: File, externalRoots: List<File>): Boolean =
        externalRoots.any { externalRoot ->
            runCatching {
                val root = externalRoot.canonicalFile
                val relative = file.canonicalFile.relativeTo(root).path
                relative.startsWith("books" + File.separator + "media_")
            }.getOrDefault(false)
        }

    private fun saturatingAdd(a: Long, b: Long): Long =
        if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
}
