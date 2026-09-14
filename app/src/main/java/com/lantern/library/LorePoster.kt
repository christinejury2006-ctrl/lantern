package com.lantern.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.lantern.library.data.StartupTrace

internal object LorePoster {
    const val W = 854
    const val H = 1842

    @Volatile
    var body: Bitmap? = null
        private set

    @Volatile
    var sparkles: Bitmap? = null
        private set

    fun decode(ctx: Context) {
        if (body != null) return
        synchronized(this) {
            if (body != null) return
            val opts = BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val res = ctx.applicationContext.resources
            body = BitmapFactory.decodeResource(res, R.drawable.lore_poster, opts)
            sparkles = BitmapFactory.decodeResource(res, R.drawable.lore_poster_sparkles, opts)
            StartupTrace.mark("poster decoded ${body?.width}x${body?.height}")
        }
    }
}
