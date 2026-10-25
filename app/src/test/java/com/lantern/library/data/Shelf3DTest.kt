package com.lantern.library.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Geometry for the 3D bookcase, pinned down so the projection cannot silently break. */
class Shelf3DTest {

    private fun camera() = Shelf3D.Camera(
        focal = 1200f,
        centerX = 540f,
        centerY = 400f,
        radius = 1020f
    )

    @Test
    fun centreOfTheShelfProjectsToTheCentreOfTheScreen() {
        val cam = camera()
        val p = Shelf3D.project(0f, cam.radius, 0f, cam)
        assertEquals(cam.centerX, p.x, 0.01f)
        assertEquals(cam.centerY, p.y, 0.01f)
    }

    @Test
    fun pointsAboveTheEyeProjectAboveItsCentre() {
        val cam = camera()
        val above = Shelf3D.project(0f, cam.radius, -300f, cam)
        assertTrue("negative y must be higher on screen", above.y < cam.centerY)
    }

    @Test
    fun theShelfCurvesSoEdgeBooksGrow() {
        val cam = camera()
        val centre = Shelf3D.face(-0.04f, 0.04f, cam.radius, -400f, 0f, cam)
        val edge = Shelf3D.face(0.34f, 0.42f, cam.radius, -400f, 0f, cam)
        val centreHeight = centre.bottom() - centre.top()
        val edgeHeight = edge.bottom() - edge.top()
        assertTrue("books at the edge should be taller: $edgeHeight vs $centreHeight", edgeHeight > centreHeight)
    }

    @Test
    fun projectionIsSymmetricAboutTheCentre() {
        val cam = camera()
        val left = Shelf3D.project(-0.3f, cam.radius, -100f, cam)
        val right = Shelf3D.project(0.3f, cam.radius, -100f, cam)
        assertEquals(cam.centerX - left.x, right.x - cam.centerX, 0.5f)
        assertEquals(left.y, right.y, 0.01f)
    }

    @Test
    fun projectionNeverProducesNaNEvenBehindTheCamera() {
        val cam = camera()
        for (a in listOf(-3f, -1.6f, -1.55f, 0f, 1.55f, 1.6f, 3f)) {
            val p = Shelf3D.project(a, cam.radius, -200f, cam)
            assertFalse("NaN at angle $a", p.x.isNaN() || p.y.isNaN())
            assertTrue("infinity at angle $a", p.x.isFinite() && p.y.isFinite())
        }
    }

    @Test
    fun booksAreLaidOutInOrderAlongTheArc() {
        val widths = floatArrayOf(70f, 80f, 60f)
        val starts = Shelf3D.startAngles(widths, gap = 4f, radius = 1020f)
        assertEquals(0f, starts[0], 0.0001f)
        assertTrue(starts[1] > starts[0])
        assertTrue(starts[2] > starts[1])
        val total = Shelf3D.totalAngle(widths, gap = 4f, radius = 1020f)
        assertTrue(total > starts[2])
    }

    @Test
    fun emptyRowHasNoArc() {
        assertEquals(0f, Shelf3D.totalAngle(FloatArray(0), 4f, 1020f), 0.0001f)
        assertEquals(0, Shelf3D.startAngles(FloatArray(0), 4f, 1020f).size)
    }

    @Test
    fun shortRowsAreCentredAndLongRowsWrap() {
        val cam = camera()
        val half = Shelf3D.halfVisibleAngle(1080f, cam)
        assertTrue(Shelf3D.fitsOnScreen(floatArrayOf(60f), 4f, cam.radius, half))
        val many = FloatArray(40) { 60f }
        assertFalse(Shelf3D.fitsOnScreen(many, 4f, cam.radius, half))
    }

    @Test
    fun hitTestingFindsTheBookUnderAFinger() {
        val cam = camera()
        val quad = Shelf3D.face(-0.05f, 0.05f, cam.radius, -400f, 0f, cam)
        val midX = (quad.left() + quad.right()) / 2f
        val midY = (quad.top() + quad.bottom()) / 2f
        assertTrue(Shelf3D.contains(quad, midX, midY))
        assertFalse(Shelf3D.contains(quad, midX, quad.top() - 40f))
        assertFalse(Shelf3D.contains(quad, quad.right() + 80f, midY))
    }

    @Test
    fun shadingStaysInRangeAndDarkensAwayFromTheLight() {
        assertEquals(1f, Shelf3D.shadeVertical(1f), 0.001f)
        assertTrue(Shelf3D.shadeVertical(-1f) < Shelf3D.shadeVertical(0.5f))
        (-30..30).forEach { i ->
            val s = Shelf3D.shadeVertical(i / 30f)
            assertTrue("shade out of range: $s", s in 0.42f..1f)
        }
    }
}
