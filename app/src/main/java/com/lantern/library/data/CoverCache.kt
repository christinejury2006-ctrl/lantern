package com.lantern.library.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object CoverCache {
    private val mem = LruCache<String, Bitmap>(24)
    suspend fun load(pathOrUrl: String?): Bitmap? = withContext(Dispatchers.IO) {
        if (pathOrUrl.isNullOrBlank()) return@withContext null
        mem.get(pathOrUrl)?.let { return@withContext it }
        val bytes = when {
            pathOrUrl.startsWith("http") -> Gutendex.downloadBytes(pathOrUrl)
            File(pathOrUrl).exists() -> runCatching { File(pathOrUrl).readBytes() }.getOrNull()
            else -> null
        }
        if (bytes == null || bytes.size < 40) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 600 || bounds.outHeight / sample > 900) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@withContext null
        mem.put(pathOrUrl, bmp)
        bmp
    }
}
