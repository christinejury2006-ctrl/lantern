package com.lantern.library.shelf

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ConfigurationInfo

/**
 * Decides whether this device can run the bookcase.
 *
 * The scene draws with OpenGL ES 2.0, which has been mandatory on Android since 2.2, so this
 * passes on essentially anything that can install the app. It exists so a device without a
 * usable driver gets a sentence instead of a black rectangle.
 *
 * There is deliberately no 2D replacement here. If a device cannot render the bookcase, the
 * library says so plainly rather than pretending with a flat image.
 */
object ShelfRuntime {

    data class Capability(
        val supported: Boolean,
        val glEsVersion: String,
        val reason: String?
    )

    fun inspect(context: Context): Capability {
        val info: ConfigurationInfo = try {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .deviceConfigurationInfo
        } catch (_: Throwable) {
            return Capability(false, "unknown", "Could not read the graphics configuration.")
        }
        val raw = info.reqGlEsVersion
        val text = "${(raw shr 16) and 0xFFFF}.${raw and 0xFFFF}"
        return if (raw >= 0x20000) {
            Capability(true, text, null)
        } else {
            Capability(
                false,
                text,
                "This phone supports OpenGL ES $text. The bookcase needs 2.0 or newer."
            )
        }
    }
}
