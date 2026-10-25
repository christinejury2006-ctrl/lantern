package com.lantern.library.data

import kotlin.math.abs

/**
 * Pure helpers that decide how a book looks as a physical spine on the shelf.
 *
 * Kept free of Android types so it can be unit tested on the JVM. Everything here is
 * deterministic for a given key: the same book always sits on the shelf with the same
 * width, colour, lean and height, which is what makes the shelf feel tangible.
 */
object ShelfMath {

    /** Stable non-negative hash. Avoids String.hashCode() being negative for short keys. */
    fun hash(key: String): Int {
        var h = 7
        for (c in key) h = h * 31 + c.code
        return abs(if (h == Int.MIN_VALUE) 0 else h)
    }

    /** Spine width in dp. Longer books look thicker, the way a real hardback does. */
    fun spineWidthDp(key: String, pageCount: Int): Int {
        val base = 15 + hash("$key:w") % 7
        val bulk = pageCount.coerceIn(0, 900) / 170
        return (base + bulk).coerceIn(15, 27)
    }

    /** Spine height as a fraction of the shelf's usable height. Books are never all equal. */
    fun spineHeightFraction(key: String): Float {
        val step = hash("$key:h") % 15
        return 0.82f + step / 100f
    }

    /** A tiny lean in degrees, so the row is not mechanically straight. */
    fun leanDegrees(key: String): Float {
        val step = hash("$key:l") % 5
        return (step - 2) * 0.7f
    }

    /** ARGB colour of the cloth / leather binding. */
    fun spineTintArgb(key: String): Long = TINTS[hash("$key:c") % TINTS.size]

    /** Half-width ribbon length in dp for a book that is part read. */
    fun ribbonHeightDp(key: String, progress: Float, spineHeightDp: Float): Float {
        val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
        if (p <= 0.01f) return 0f
        return (spineHeightDp * (0.18f + 0.42f * p)).coerceAtMost(spineHeightDp * 0.7f)
    }

    /** Index into a looping row. Always lands inside 0 until size. */
    fun wrapIndex(index: Int, size: Int): Int {
        if (size <= 0) return 0
        val m = index % size
        return if (m < 0) m + size else m
    }

    /** Number of shelf rows needed for [items] books at [perRow] per shelf. */
    fun rowsOf(items: Int, perRow: Int): Int {
        if (items <= 0 || perRow <= 0) return 0
        return (items + perRow - 1) / perRow
    }

    /**
     * Muted bookbinding colours: oxblood, navy, olive, mustard, teal, plum, chestnut.
     * Deliberately desaturated so the shelf reads as a real library rather than a toy.
     */
    private val TINTS = longArrayOf(
        0xFF5B2A2A, // oxblood
        0xFF23364F, // navy
        0xFF3D4A2E, // olive
        0xFF8A6A32, // mustard
        0xFF1F4448, // deep teal
        0xFF4A2C46, // plum
        0xFF5C3A25, // chestnut
        0xFF2E3B33, // forest
        0xFF6B3B2E, // rust
        0xFF34405A, // slate blue
        0xFF6E5540, // tan leather
        0xFF43281F, // dark cocoa
        0xFF2B4A44, // pine
        0xFF7A4A3A, // clay
        0xFF3A3550, // dusty violet
        0xFF4E4436  // stone
    )
}
