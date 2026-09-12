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
    private val failed = ConcurrentHashMap.newKeySet<String>()

    suspend fun load(pathOrUrl: String?, fallbacks: List<String> = emptyList()): Bitmap? =
        withContext(Dispatchers.IO) {
            val chain = LinkedHashSet<String>()
            if (!pathOrUrl.isNullOrBlank()) chain += pathOrUrl
            fallbacks.forEach { if (it.isNotBlank()) chain += it }
            if (chain.isEmpty()) return@withContext null
            val primary = chain.first()
            mem.get(primary)?.let { return@withContext it }
            for (url in chain) {
                if (url in failed) continue
                if (Regex("[?&]zoom=0(?:&|$)").containsMatchIn(url)) {
                    failed.add(url)
                    continue
                }
                mem.get(url)?.let { bmp ->
                    mem.put(primary, bmp)
                    return@withContext bmp
                }
                val bmp = readBitmap(url)
                if (bmp != null) {
                    mem.put(url, bmp)
                    mem.put(primary, bmp)
                    return@withContext bmp
                }
                if (url.startsWith("http")) failed.add(url)
            }
            null
        }

    private fun readBitmap(pathOrUrl: String): Bitmap? {
        val bytes = when {
            pathOrUrl.startsWith("http") -> Gutendex.downloadBytes(pathOrUrl)
            File(pathOrUrl).exists() -> runCatching { File(pathOrUrl).readBytes() }.getOrNull()
            else -> null
        }
        if (bytes == null || bytes.size < 40) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth < 1 || bounds.outHeight < 1) return null
        var sample = 1
        while (bounds.outWidth / sample > 600 || bounds.outHeight / sample > 900) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }
}
