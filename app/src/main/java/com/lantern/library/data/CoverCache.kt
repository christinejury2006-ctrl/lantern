package com.lantern.library.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object CoverCache {
    private val mem = LruCache<String, Bitmap>(24)
    private val rejectedZoom = ConcurrentHashMap.newKeySet<String>()

    suspend fun load(pathOrUrl: String?): Bitmap? = withContext(Dispatchers.IO) {
        if (pathOrUrl.isNullOrBlank()) return@withContext null
        mem.get(pathOrUrl)?.let { return@withContext it }
        if (pathOrUrl.startsWith("http")) {
            val upgraded = experimentalZoom0(pathOrUrl)
            if (upgraded != null && upgraded != pathOrUrl && upgraded !in rejectedZoom) {
                val trial = decodeIfSuitable(Gutendex.downloadBytes(upgraded))
                if (trial != null) {
                    mem.put(pathOrUrl, trial)
                    return@withContext trial
                }
                rejectedZoom.add(upgraded)
            }
            val original = decodeIfSuitable(Gutendex.downloadBytes(pathOrUrl))
            if (original != null) {
                mem.put(pathOrUrl, original)
                return@withContext original
            }
            return@withContext null
        }
        val file = File(pathOrUrl)
        if (!file.exists()) return@withContext null
        val bmp = decodeIfSuitable(runCatching { file.readBytes() }.getOrNull()) ?: return@withContext null
        mem.put(pathOrUrl, bmp)
        bmp
    }

    /**
     * Accept a cover only if it decodes to a plausible book-cover rectangle.
     * Rejects empty/tiny files, decode failures, and extreme crops (wide text strips).
     */
    private fun decodeIfSuitable(bytes: ByteArray?): Bitmap? {
        if (bytes == null || bytes.size < 400) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w < 80 || h < 80) return null
        val aspect = w.toFloat() / h.toFloat()
        if (aspect < 0.4f || aspect > 1.25f) return null
        var sample = 1
        while (w / sample > 600 || h / sample > 900) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    /** Only for Google thumbnail URLs. Never persisted; rejected results are not cached as covers. */
    private fun experimentalZoom0(url: String): String? {
        val lower = url.lowercase()
        if ("books.google.com" !in lower && "googleusercontent.com" !in lower) return null
        return when {
            Regex("[?&]zoom=1(?:&|$)").containsMatchIn(url) ->
                url.replace(Regex("([?&])zoom=1(&|$)"), "$1zoom=0$2")
            Regex("[?&]zoom=5(?:&|$)").containsMatchIn(url) ->
                url.replace(Regex("([?&])zoom=5(&|$)"), "$1zoom=0$2")
            else -> null
        }
    }
}
