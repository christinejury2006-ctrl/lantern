package com.lantern.library.shelf

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ConfigurationInfo

/**
 * Decides whether this device can run the native bookcase.
 *
 * Filament needs OpenGL ES 3.0. That is present on every phone this app targets in practice,
 * but it is not formally mandatory on Android, so the check exists: without it the engine
 * would fail to create a swap chain and leave a black rectangle.
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
        return if (raw >= 0x30000) {
            Capability(true, text, null)
        } else {
            Capability(
                false,
                text,
                "This phone supports OpenGL ES $text. The bookcase needs 3.0 or newer."
            )
        }
    }
}
