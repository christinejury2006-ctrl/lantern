package com.lantern.library.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shelf is the most visible surface in the app, so the rules that decide how a book
 * looks are pinned down here. These run on the JVM with no Android dependencies.
 */
class ShelfMathTest {

    @Test
    fun hashIsStableAndNeverNegative() {
        assertEquals(ShelfMath.hash("treasure-island"), ShelfMath.hash("treasure-island"))
        val keys = listOf("", "pg120", "web_1699999999", "starter_island", "a", "a-very-long-book-identifier-that-keeps-going")
        keys.forEach { key ->
            assertTrue("hash of '$key' must be >= 0", ShelfMath.hash(key) >= 0)
        }
    }

    @Test
    fun spineWidthStaysOnTheShelf() {
        repeat(200) { i ->
            val width = ShelfMath.spineWidthDp("book-$i", i * 7)
            assertTrue("width $width out of range", width in 15..27)
        }
    }

    @Test
    fun longerBooksGetThickerSpines() {
        val thin = ShelfMath.spineWidthDp("same-key", 0)
        val thick = ShelfMath.spineWidthDp("same-key", 900)
        assertTrue("expected $thick > $thin", thick > thin)
        assertEquals(thin + 5, thick)
    }

    @Test
    fun spineHeightsVaryButStayPlausible() {
        val heights = (0 until 40).map { ShelfMath.spineHeightFraction("spine-$it") }
        assertTrue(heights.all { it in 0.82f..0.97f })
        assertTrue("books should not all be the same height", heights.distinct().size > 3)
    }

    @Test
    fun leanIsSubtle() {
        (0 until 50).forEach { i ->
            val lean = ShelfMath.leanDegrees("lean-$i")
            assertTrue("lean $lean too steep", lean >= -1.5f && lean <= 1.5f)
        }
    }

    @Test
    fun everyTintIsFullyOpaque() {
        (0 until 50).forEach { i ->
            val argb = ShelfMath.spineTintArgb("tint-$i")
            assertEquals("alpha must be opaque for $argb", 0xFFL, (argb shr 24) and 0xFFL)
        }
    }

    @Test
    fun ribbonTracksProgress() {
        assertEquals(0f, ShelfMath.ribbonHeightDp("k", 0f, 150f), 0.001f)
        assertEquals(0f, ShelfMath.ribbonHeightDp("k", 0.005f, 150f), 0.001f)
        val early = ShelfMath.ribbonHeightDp("k", 0.2f, 150f)
        val late = ShelfMath.ribbonHeightDp("k", 0.9f, 150f)
        assertTrue(early > 0f)
        assertTrue(late > early)
        assertTrue("ribbon must not run off the shelf", late <= 150f * 0.7f)
    }

    @Test
    fun ribbonIgnoresBrokenProgressValues() {
        assertEquals(0f, ShelfMath.ribbonHeightDp("k", Float.NaN, 150f), 0.001f)
        assertTrue(ShelfMath.ribbonHeightDp("k", 4f, 150f) > 0f)
    }

    @Test
    fun wrapIndexCyclesWithoutEscapingTheList() {
        val size = 7
        assertEquals(0, ShelfMath.wrapIndex(0, size))
        assertEquals(6, ShelfMath.wrapIndex(-1, size))
        assertEquals(2, ShelfMath.wrapIndex(9, size))
        assertEquals(0, ShelfMath.wrapIndex(0, 0))
        (-50..50).forEach { i ->
            val idx = ShelfMath.wrapIndex(i, size)
            assertTrue("index $idx out of bounds", idx in 0 until size)
        }
    }

    @Test
    fun rowsOfRoundsUp() {
        assertEquals(0, ShelfMath.rowsOf(0, 12))
        assertEquals(1, ShelfMath.rowsOf(1, 12))
        assertEquals(1, ShelfMath.rowsOf(12, 12))
        assertEquals(2, ShelfMath.rowsOf(13, 12))
        assertEquals(0, ShelfMath.rowsOf(13, 0))
    }
}
