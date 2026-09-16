package com.lantern.library.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

object WallpaperStore {
    fun load(context: Context, id: String?, url: String? = null): Bitmap? {
        val choice = WallpaperCatalog.find(id) ?: return null
        return decode(context, choice.drawable, 1080, 1920)
    }

    fun thumb(context: Context, id: String?): Bitmap? {
        val choice = WallpaperCatalog.find(id) ?: return null
        return decode(context, choice.drawable, 160, 240)
    }

    private fun decode(context: Context, resId: Int, maxW: Int, maxH: Int): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(context.resources, resId, bounds)
            if (bounds.outWidth < 1 || bounds.outHeight < 1) return@runCatching null
            var sample = 1
            while (bounds.outWidth / sample > maxW || bounds.outHeight / sample > maxH) sample *= 2
            BitmapFactory.decodeResource(
                context.resources,
                resId,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }.getOrNull()
    }
}
