package com.lantern.library.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

object WallpaperStore {
    private const val MAX_BYTES = 2_500_000

    fun file(context: Context, id: String): File =
        File(File(context.filesDir, "wallpapers").apply { mkdirs() }, "$id.jpg")

    fun load(context: Context, id: String?, url: String?): Bitmap? {
        if (id.isNullOrBlank() || url.isNullOrBlank()) return null
        return runCatching {
            val dest = file(context, id)
            if (!dest.exists() || dest.length() < 80L) {
                val bytes = Gutendex.downloadBytes(url) ?: return@runCatching null
                if (bytes.size < 80 || bytes.size > MAX_BYTES) return@runCatching null
                dest.writeBytes(bytes)
            }
            if (!dest.exists() || dest.length() < 80L) return@runCatching null
            decode(dest)
        }.getOrNull()
    }

    private fun decode(file: File): Bitmap? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.size < 80) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth < 1 || bounds.outHeight < 1) return null
        var sample = 1
        while (bounds.outWidth / sample > 1080 || bounds.outHeight / sample > 1920) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }
}
