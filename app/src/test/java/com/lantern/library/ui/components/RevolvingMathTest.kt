package com.lantern.library.ui.components

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The revolving shelf's illusion is entirely these two functions, so they are pinned here:
 * a flipped sign curves the shelf the wrong way and an unclamped value turns a spine inside
 * out as it leaves the screen.
 */
class RevolvingMathTest {

    @Test
    fun `a spine in the middle of the screen is not turned`() {
        assertEquals(0f, RevolvingMath.offsetFraction(500f, 500f, 1000f), 1e-6f)
        assertEquals(0f, RevolvingMath.rotationY(0f), 1e-6f)
        assertEquals(0f, RevolvingMath.arcDrop(0f, 40f), 1e-6f)
        assertEquals(1f, RevolvingMath.scale(0f), 1e-6f)
        assertEquals(1f, RevolvingMath.alpha(0f), 1e-6f)
    }

    @Test
    fun `position is measured in half-viewports and clamped`() {
        // one half-viewport right of centre is +1
        assertEquals(1f, RevolvingMath.offsetFraction(1000f, 500f, 1000f), 1e-6f)
        assertEquals(-1f, RevolvingMath.offsetFraction(0f, 500f, 1000f), 1e-6f)
        assertEquals(0.5f, RevolvingMath.offsetFraction(750f, 500f, 1000f), 1e-6f)
        // a spine scrolled off screen reports more than one half-viewport away; clamp it
        assertEquals(1f, RevolvingMath.offsetFraction(5000f, 500f, 1000f), 1e-6f)
        assertEquals(-1f, RevolvingMath.offsetFraction(-5000f, 500f, 1000f), 1e-6f)
        // and a degenerate viewport must not divide by zero
        assertEquals(0f, RevolvingMath.offsetFraction(10f, 0f, 0f), 1e-6f)
    }

    @Test
    fun `spines turn away from the viewer, mirrored either side of the middle`() {
        // Right of centre turns its right edge back, which is a negative rotation about Y.
        assertTrue("right-hand spines must rotate negatively", RevolvingMath.rotationY(0.5f) < 0f)
        assertTrue("left-hand spines must rotate positively", RevolvingMath.rotationY(-0.5f) > 0f)
        // the two sides are mirror images
        assertEquals(RevolvingMath.rotationY(0.7f), -RevolvingMath.rotationY(-0.7f), 1e-6f)
    }

    @Test
    fun `the turn tops out at 35 degrees`() {
        assertEquals(-35f, RevolvingMath.rotationY(1f), 1e-4f)
        assertEquals(35f, RevolvingMath.rotationY(-1f), 1e-4f)
        // clamped, so an item far off screen cannot turn past the limit
        assertEquals(-35f, RevolvingMath.rotationY(9f), 1e-4f)
        assertEquals(35f, RevolvingMath.rotationY(-9f), 1e-4f)
        assertEquals(17.5f, RevolvingMath.rotationY(-0.5f), 1e-4f)
    }

    @Test
    fun `the arch drops at the edges and stays flat through the middle`() {
        val drop = 40f
        assertEquals(0f, RevolvingMath.arcDrop(0f, drop), 1e-6f)
        // symmetric: both edges fall by the same amount
        assertEquals(RevolvingMath.arcDrop(0.6f, drop), RevolvingMath.arcDrop(-0.6f, drop), 1e-6f)
        assertEquals(drop, RevolvingMath.arcDrop(1f, drop), 1e-4f)
        assertEquals(drop, RevolvingMath.arcDrop(-1f, drop), 1e-4f)
        // squared, so the centre is genuinely flat rather than a V
        val quarter = RevolvingMath.arcDrop(0.25f, drop)
        assertTrue("a quarter out should drop far less than a quarter of the way", quarter < drop * 0.10f)
        // and the drop grows as a spine leaves the centre
        var previous = -1f
        var f = 0f
        while (f <= 1f) {
            val d = RevolvingMath.arcDrop(f, drop)
            assertTrue("the drop must increase with distance", d >= previous)
            previous = d
            f += 0.05f
        }
    }

    @Test
    fun `spines shrink and fade toward the edges but never vanish`() {
        assertEquals(1f, RevolvingMath.scale(0f), 1e-6f)
        assertTrue(RevolvingMath.scale(1f) < 1f)
        assertTrue(RevolvingMath.scale(1f) > 0.85f)
        assertEquals(RevolvingMath.scale(0.5f), RevolvingMath.scale(-0.5f), 1e-6f)

        assertEquals(1f, RevolvingMath.alpha(0f), 1e-6f)
        assertEquals(0.55f, RevolvingMath.alpha(1f), 1e-4f)
        assertEquals(0.55f, RevolvingMath.alpha(-7f), 1e-4f)
        // still visible: a book must not fade out completely at the edge of the shelf
        assertTrue(RevolvingMath.alpha(1f) >= 0.5f)
    }

    @Test
    fun `the camera is twelve times the display density`() {
        assertEquals(12f, RevolvingMath.cameraDistance(1f), 1e-6f)
        assertEquals(12f * 2.75f, RevolvingMath.cameraDistance(2.75f), 1e-6f)
        assertEquals(12f * 3f, RevolvingMath.cameraDistance(3f), 1e-6f)
    }

    @Test
    fun `the offered book is the one nearest the middle`() {
        assertEquals(-1, RevolvingMath.nearestIndex(emptyList()))
        assertEquals(0, RevolvingMath.nearestIndex(listOf(4f)))
        // signed distances: -12 is nearer the centre than +40
        assertEquals(1, RevolvingMath.nearestIndex(listOf(-300f, -12f, 40f, 280f)))
        assertEquals(2, RevolvingMath.nearestIndex(listOf(-300f, -90f, 4f, 280f)))
    }

    @Test
    fun `a fully swept row still looks like a shelf`() {
        // walk a book from one edge to the other and check the pose is continuous and sane
        var previousRotation = Float.NaN
        var f = -1f
        while (f <= 1.0001f) {
            val rotation = RevolvingMath.rotationY(f)
            val drop = RevolvingMath.arcDrop(f, 34f)
            assertTrue("tilt out of range at $f: $rotation", abs(rotation) <= 35.001f)
            assertTrue("drop out of range at $f: $drop", drop >= 0f && drop <= 34.001f)
            assertTrue("alpha out of range at $f", RevolvingMath.alpha(f) in 0.55f..1f)
            assertTrue("scale out of range at $f", RevolvingMath.scale(f) in 0.9f..1.001f)
            if (!previousRotation.isNaN()) {
                // crossing the middle is the only place the tilt changes sign, and it must do
                // so smoothly rather than jumping
                assertTrue("tilt jumped at $f", abs(rotation - previousRotation) < 4f)
            }
            previousRotation = rotation
            f += 0.02f
        }
    }

    @Test
    fun `the arch falls away steadily from the middle`() {
        // measured in distance from the centre, which is the property that matters: both
        // halves of the shelf must sag by the same amount at the same distance out
        var previous = -1f
        var f = 0f
        while (f <= 1.0001f) {
            val drop = RevolvingMath.arcDrop(f, 34f)
            assertTrue("the arch rose between ${f - 0.02f} and $f", drop >= previous - 1e-4f)
            assertEquals(
                "the two halves of the arch must match at $f",
                drop,
                RevolvingMath.arcDrop(-f, 34f),
                1e-5f
            )
            previous = drop
            f += 0.02f
        }
    }
}
