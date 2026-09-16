package com.lantern.library.data

import org.json.JSONObject

enum class AppearFill { Solid, Ombre }

data class AppearLook(
    val fill: AppearFill = AppearFill.Ombre,
    val solidArgb: Long = 0xFFE8B8F4,
    val ombreStartArgb: Long = 0xFFE8B8F4,
    val ombreEndArgb: Long = 0xFF6ED4C8,
    val wallpaperId: String? = null,
    val wallpaperUrl: String? = null,
    val wallpaperOpacity: Float = 0.32f,
    val overlay: Float = 0.28f
) {
    val hasWallpaper: Boolean
        get() = !wallpaperId.isNullOrBlank() && !wallpaperUrl.isNullOrBlank()

    companion object {
        val libraryDefault = AppearLook(
            fill = AppearFill.Ombre,
            solidArgb = 0xFFE8B8F4,
            ombreStartArgb = 0xFFE8B8F4,
            ombreEndArgb = 0xFF6ED4C8,
            wallpaperOpacity = 0.32f,
            overlay = 0.22f
        )
        val readerDefault = AppearLook(
            fill = AppearFill.Ombre,
            solidArgb = 0xFFEDE4F6,
            ombreStartArgb = 0xFFE8B8F4,
            ombreEndArgb = 0xFF6ED4C8,
            wallpaperOpacity = 0.28f,
            overlay = 0.42f
        )
    }
}

data class LibraryAppearance(val look: AppearLook = AppearLook.libraryDefault)

data class ReaderAppearance(val look: AppearLook = AppearLook.readerDefault)

data class WallpaperChoice(
    val id: String,
    val label: String,
    val url: String
)

object WallpaperCatalog {
    val items: List<WallpaperChoice> = listOf(
        WallpaperChoice("picsum-1015", "Hills", "https://picsum.photos/id/1015/1080/1920"),
        WallpaperChoice("picsum-1018", "Trail", "https://picsum.photos/id/1018/1080/1920"),
        WallpaperChoice("picsum-1036", "Sea", "https://picsum.photos/id/1036/1080/1920"),
        WallpaperChoice("picsum-1044", "Dusk", "https://picsum.photos/id/1044/1080/1920"),
        WallpaperChoice("picsum-1050", "Fog", "https://picsum.photos/id/1050/1080/1920"),
        WallpaperChoice("picsum-1067", "City", "https://picsum.photos/id/1067/1080/1920"),
        WallpaperChoice("picsum-10", "Forest", "https://picsum.photos/id/10/1080/1920"),
        WallpaperChoice("picsum-29", "Shore", "https://picsum.photos/id/29/1080/1920")
    )

    fun find(id: String?): WallpaperChoice? =
        id?.let { want -> items.firstOrNull { it.id == want } }
}

val appearSwatches = listOf(
    0xFFE8B8F4, 0xFFC9B8F8, 0xFF7EC8E8, 0xFF6ED4C8,
    0xFFF7F1E8, 0xFFFFF8EE, 0xFFF3E2B8, 0xFFE8A0C0,
    0xFFE07A6A, 0xFF3A4454, 0xFF4A5668, 0xFF2B2430
)

fun AppearLook.toJson(): JSONObject = JSONObject()
    .put("fill", fill.name)
    .put("solid", solidArgb)
    .put("ombreStart", ombreStartArgb)
    .put("ombreEnd", ombreEndArgb)
    .put("wallpaperId", wallpaperId ?: "")
    .put("wallpaperUrl", wallpaperUrl ?: "")
    .put("wallpaperOpacity", wallpaperOpacity.toDouble())
    .put("overlay", overlay.toDouble())

fun appearLookFromJson(raw: String?, fallback: AppearLook): AppearLook {
    if (raw.isNullOrBlank()) return fallback
    return runCatching {
        val o = JSONObject(raw)
        val fill = runCatching { AppearFill.valueOf(o.optString("fill", fallback.fill.name)) }
            .getOrDefault(fallback.fill)
        val id = o.optString("wallpaperId").ifBlank { null }
        val url = o.optString("wallpaperUrl").ifBlank { null }
        AppearLook(
            fill = fill,
            solidArgb = o.optLong("solid", fallback.solidArgb),
            ombreStartArgb = o.optLong("ombreStart", fallback.ombreStartArgb),
            ombreEndArgb = o.optLong("ombreEnd", fallback.ombreEndArgb),
            wallpaperId = id,
            wallpaperUrl = url,
            wallpaperOpacity = o.optDouble("wallpaperOpacity", fallback.wallpaperOpacity.toDouble())
                .toFloat().coerceIn(0f, 1f),
            overlay = o.optDouble("overlay", fallback.overlay.toDouble()).toFloat().coerceIn(0f, 1f)
        )
    }.getOrDefault(fallback)
}
