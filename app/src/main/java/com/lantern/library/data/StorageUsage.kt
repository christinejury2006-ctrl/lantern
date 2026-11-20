package com.lantern.library.data

import android.content.Context
import java.io.File

data class StorageUsageSnapshot(val bytes: Long, val files: Int) {
    fun displaySize(): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

/** Measures the app's actual private data and cache directories, not a guessed sum of book sizes. */
object StorageUsage {
    fun measure(context: Context): StorageUsageSnapshot {
        val roots = listOfNotNull(context.dataDir, context.externalCacheDir).distinctBy { it.absolutePath }
        var bytes = 0L
        var files = 0
        roots.forEach { root ->
            if (!root.exists()) return@forEach
            runCatching {
                root.walkTopDown().filter { it.isFile }.forEach { file ->
                    val length = runCatching { file.length() }.getOrDefault(0L).coerceAtLeast(0L)
                    bytes = if (Long.MAX_VALUE - bytes < length) Long.MAX_VALUE else bytes + length
                    files++
                }
            }
        }
        return StorageUsageSnapshot(bytes, files)
    }
}
