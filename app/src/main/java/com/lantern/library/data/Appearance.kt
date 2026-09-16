package com.lantern.library.data

import androidx.annotation.DrawableRes
import com.lantern.library.R
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
    val overlay: Float = 0.28f,
    val chromeOpacity: Float = 0.5f
) {
    val hasWallpaper: Boolean
        get() = WallpaperCatalog.find(wallpaperId) != null

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
            wallpaperOpacity = 0.50f,
            overlay = 0.18f,
            chromeOpacity = 0.5f
        )
        val darkDefault = AppearLook(
            fill = AppearFill.Ombre,
            solidArgb = 0xFF3A4454,
            ombreStartArgb = 0xFF3E4A5C,
            ombreEndArgb = 0xFF2B2430,
            wallpaperOpacity = 0.28f,
            overlay = 0.42f
        )
    }

    fun resolvedFor(dark: Boolean): AppearLook {
        val factoryLight = sameFills(libraryDefault) || sameFills(readerDefault)
        val factoryDark = sameFills(darkDefault)
        return when {
            dark && factoryLight -> copy(
                solidArgb = darkDefault.solidArgb,
                ombreStartArgb = darkDefault.ombreStartArgb,
                ombreEndArgb = darkDefault.ombreEndArgb
            )
            !dark && factoryDark -> copy(
                solidArgb = libraryDefault.solidArgb,
                ombreStartArgb = libraryDefault.ombreStartArgb,
                ombreEndArgb = libraryDefault.ombreEndArgb
            )
            else -> this
        }
    }

    private fun sameFills(other: AppearLook): Boolean =
        fill == other.fill &&
            solidArgb == other.solidArgb &&
            ombreStartArgb == other.ombreStartArgb &&
            ombreEndArgb == other.ombreEndArgb

    fun forCloud(): AppearLook = copy(
        wallpaperId = WallpaperCatalog.find(wallpaperId)?.id,
        wallpaperUrl = null
    )
}

data class LibraryAppearance(val look: AppearLook = AppearLook.libraryDefault)

data class ReaderAppearance(val look: AppearLook = AppearLook.readerDefault)

data class WallpaperChoice(
    val id: String,
    val label: String,
    @DrawableRes val drawable: Int
)

object WallpaperCatalog {
    val items: List<WallpaperChoice> = listOf(
        WallpaperChoice("aurora_purple", "Aurora Purple", R.drawable.wp_aurora_purple),
        WallpaperChoice("aurora_teal", "Aurora Teal", R.drawable.wp_aurora_teal),
        WallpaperChoice("paper_cream", "Paper Cream", R.drawable.wp_paper_cream),
        WallpaperChoice("dusk_slate", "Dusk Slate", R.drawable.wp_dusk_slate),
        WallpaperChoice("mist_lilac", "Mist Lilac", R.drawable.wp_mist_lilac),
        WallpaperChoice("night_ink", "Night Ink", R.drawable.wp_night_ink),
        WallpaperChoice("forest_haze", "Forest Haze", R.drawable.wp_forest_haze),
        WallpaperChoice("shore_gold", "Shore Gold", R.drawable.wp_shore_gold)
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
    .put("wallpaperId", WallpaperCatalog.find(wallpaperId)?.id ?: "")
    .put("wallpaperUrl", "")
    .put("wallpaperOpacity", wallpaperOpacity.toDouble())
    .put("overlay", overlay.toDouble())
    .put("chromeOpacity", chromeOpacity.toDouble())

fun appearLookFromJson(raw: String?, fallback: AppearLook): AppearLook {
    if (raw.isNullOrBlank()) return fallback
    return runCatching {
        appearLookFromObj(JSONObject(raw), fallback)
    }.getOrDefault(fallback)
}

fun appearLookFromObj(o: JSONObject, fallback: AppearLook): AppearLook {
    val fill = runCatching { AppearFill.valueOf(o.optString("fill", fallback.fill.name)) }
        .getOrDefault(fallback.fill)
    val id = WallpaperCatalog.find(o.optString("wallpaperId").ifBlank { null })?.id
    return AppearLook(
        fill = fill,
        solidArgb = o.optLong("solid", fallback.solidArgb),
        ombreStartArgb = o.optLong("ombreStart", fallback.ombreStartArgb),
        ombreEndArgb = o.optLong("ombreEnd", fallback.ombreEndArgb),
        wallpaperId = id,
        wallpaperUrl = null,
        wallpaperOpacity = unit01(
            o.optDouble("wallpaperOpacity", fallback.wallpaperOpacity.toDouble()).toFloat(),
            fallback.wallpaperOpacity
        ),
        overlay = unit01(
            o.optDouble("overlay", fallback.overlay.toDouble()).toFloat(),
            fallback.overlay
        ),
        chromeOpacity = unit01(
            o.optDouble("chromeOpacity", fallback.chromeOpacity.toDouble()).toFloat(),
            fallback.chromeOpacity
        )
    )
}

fun appearUpdatedAt(raw: String?, fallback: Long = 0L): Long {
    if (raw.isNullOrBlank()) return fallback
    return runCatching { JSONObject(raw).optLong("updatedAt", fallback) }.getOrDefault(fallback)
}

private fun unit01(value: Float, fallback: Float): Float =
    if (value.isFinite()) value.coerceIn(0f, 1f) else fallback
