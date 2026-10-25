package com.lantern.library.shelf3d

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drum's trigonometry, pinned. This is the part of a 3D scene a unit test can actually
 * prove: a wrong sign here tilts every book the wrong way, and a wrong wrap makes the shelf
 * jump when it comes back round.
 */
class CylinderMathTest {

    @Test
    fun `books are spread evenly around one full turn`() {
        assertEquals(6.2831855f, CylinderMath.angleStep(1), 1e-4f)
        assertEquals(3.1415927f, CylinderMath.angleStep(2), 1e-4f)
        assertEquals(1.0471976f, CylinderMath.angleStep(6), 1e-4f)
        // an empty drum must not divide by zero
        assertEquals(6.2831855f, CylinderMath.angleStep(0), 1e-4f)
        for (count in listOf(2, 5, 12, 40)) {
            val step = CylinderMath.angleStep(count)
            assertEquals(6.2831855f, step * count, 1e-4f)
        }
    }

    @Test
    fun `every book sits on the drum at the right radius`() {
        for (count in listOf(1, 3, 8, 17)) {
            for (rotation in listOf(0f, 0.4f, -2.2f, 3.0f)) {
                for (i in 0 until count) {
                    val angle = CylinderMath.angleOf(i, count, rotation)
                    val x = CylinderMath.positionX(angle)
                    val z = CylinderMath.positionZ(angle) + CylinderMath.AXIS_DISTANCE
                    // distance from the drum's axis must be exactly the drum radius
                    assertEquals(CylinderMath.DRUM_RADIUS, sqrt(x * x + z * z), 1e-4f)
                    // and always in front of the reader
                    assertTrue(CylinderMath.positionZ(angle) < 0f)
                }
            }
        }
    }

    @Test
    fun `the front book stands nearest the reader`() {
        // The camera sits at the origin looking down -z, so a book's depth is -z: the further
        // away it is, the MORE NEGATIVE its z. The book at zero degrees therefore has the
        // largest z on the drum, not the smallest.
        val nearest = CylinderMath.positionZ(0f)
        assertEquals(
            "the front book should be the closest thing on the drum",
            -CylinderMath.AXIS_DISTANCE + CylinderMath.DRUM_RADIUS,
            nearest,
            1e-4f
        )
        for (degrees in listOf(10, 45, 90, 150, 180)) {
            val angle = Math.toRadians(degrees.toDouble()).toFloat()
            assertTrue(
                "a book at $degrees degrees must be further away, i.e. deeper: " +
                    "z ${CylinderMath.positionZ(angle)} vs front $nearest",
                CylinderMath.positionZ(angle) < nearest
            )
        }
        // and the back of the drum is the furthest thing of all
        assertEquals(
            -CylinderMath.AXIS_DISTANCE - CylinderMath.DRUM_RADIUS,
            CylinderMath.positionZ(Math.PI.toFloat()),
            1e-4f
        )
    }

    @Test
    fun `the spine of every book faces outward, away from the drum axis`() {
        // Filament rotates with a right-handed Y rotation: R_y(a)·v = (x cos a + z sin a,
        // y, -x sin a + z cos a). A book's spine is its local -z face, so its world normal is
        // R_y(yaw)·(0,0,-1) = (-sin yaw, 0, -cos yaw).
        for (i in 0 until 12) {
            val angle = i * CylinderMath.angleStep(12)
            val yaw = Math.toRadians(CylinderMath.facingDegrees(angle).toDouble())
            val nx = -sin(yaw).toFloat()
            val nz = -cos(yaw).toFloat()
            // outward normal of the drum at that angle
            val ox = sin(angle)
            val oz = cos(angle)
            val dot = nx * ox + nz * oz
            assertEquals("book $i points the wrong way", 1f, dot, 1e-3f)
        }
    }

    @Test
    fun `rotation wraps without ever jumping`() {
        // a drum spun several turns must report the same angle as one spun none
        assertEquals(0f, CylinderMath.wrapSigned(0f), 1e-6f)
        assertEquals(0.5f, CylinderMath.wrapSigned(0.5f + 2f * Math.PI.toFloat()), 1e-4f)
        assertEquals(-0.5f, CylinderMath.wrapSigned(-0.5f - 4f * Math.PI.toFloat()), 1e-4f)
        // and it must stay inside (-PI, PI]
        var angle = -20f
        while (angle < 20f) {
            val wrapped = CylinderMath.wrapSigned(angle)
            assertTrue("$wrapped out of range for $angle", abs(wrapped) <= Math.PI.toFloat() + 1e-5f)
            angle += 0.37f
        }
        // positive wrap is in [0, 2PI)
        val positive = CylinderMath.wrapPositive(-0.5f)
        assertTrue(positive >= 0f && positive < 2f * Math.PI.toFloat())
        assertEquals(2f * Math.PI.toFloat() - 0.5f, positive, 1e-4f)
    }

    @Test
    fun `spinning the drum brings books round in order and repeats`() {
        val count = 7
        // front book advances by one each time the drum turns by one step
        for (turn in 0 until 3 * count) {
            val rotation = CylinderMath.wrapSigned(-turn * CylinderMath.angleStep(count))
            assertEquals(turn % count, CylinderMath.frontIndex(count, rotation))
        }
        // and turning the other way walks the other way
        for (turn in 0 until 10) {
            val rotation = CylinderMath.wrapSigned(turn * CylinderMath.angleStep(count))
            assertEquals(((count - turn % count) % count), CylinderMath.frontIndex(count, rotation))
        }
    }

    @Test
    fun `the drum can be spun forever without losing precision`() {
        var rotation = 0f
        val step = 0.031f
        var turns = 0
        while (turns < 2000) {
            rotation = CylinderMath.wrapSigned(rotation + step)
            turns++
            assertTrue(abs(rotation) <= Math.PI.toFloat() + 1e-4f)
        }
        // after 2000 × 0.031 rad the drum should be back where it started, wrapped
        val expected = CylinderMath.wrapSigned(2000 * step)
        assertEquals(expected, rotation, 1e-2f)
    }

    @Test
    fun `revealing a book takes the short way round`() {
        val count = 5
        // from a standing start, book 4 is one step anticlockwise, not four clockwise
        val target = CylinderMath.rotationToReveal(4, count, 0f)
        val delta = abs(CylinderMath.wrapSigned(target - 0f))
        // exactly one step: book 4 of 5 is one place anticlockwise from the front
        assertEquals(
            "reveal should be a single step",
            CylinderMath.angleStep(count),
            delta,
            1e-4f
        )
        // the revealed book really is at the front afterwards
        assertEquals(4, CylinderMath.frontIndex(count, CylinderMath.wrapSigned(target)))
        // and from anywhere else it still lands on the book
        for (start in listOf(0.3f, 1.7f, -2.4f, 3.0f)) {
            val r = CylinderMath.rotationToReveal(2, count, start)
            assertEquals(2, CylinderMath.frontIndex(count, CylinderMath.wrapSigned(r)))
        }
    }

    @Test
    fun `only books on the readers side of the drum are visible`() {
        val count = 24
        val visible = CylinderMath.visibleIndices(count, 0f)
        assertTrue("nothing visible", visible.isNotEmpty())
        // the front book is always included
        assertTrue(0 in visible)
        for (index in visible) {
            val angle = CylinderMath.angleOf(index, count, 0f)
            assertTrue(
                "book $index at $angle should not be visible",
                abs(CylinderMath.wrapSigned(angle)) <=
                    Math.toRadians(CylinderMath.VISIBLE_ARC_DEGREES.toDouble()).toFloat() + 1e-4f
            )
        }
        // books on the far side are excluded
        val behind = CylinderMath.angleOf(count / 2, count, 0f)
        assertTrue(abs(behind) > 3f)
        assertTrue(count / 2 !in visible)
        // and nothing is listed twice
        assertEquals(visible.size, visible.toSet().size)
    }

    @Test
    fun `screen projection and hit testing agree`() {
        // Counts chosen so several books land on screen at once: the screen spans about four
        // book widths, so a library of 20 or 31 puts two or three in view.
        val halfFov = CylinderMath.halfFovH(aspect = 1080f / 2160f)
        var onScreen = 0
        for (count in listOf(16, 20, 31)) {
            for (i in 0 until count) {
                val angle = CylinderMath.angleOf(i, count, 0f)
                // where that book appears on screen
                val screenAngle = CylinderMath.screenAngle(angle)
                // Two gates, and both matter. The book must be in front of the drum's horizon,
                // because a book on the far side curves back into the lens geometrically while
                // the carcass hides it. And it must actually be inside the lens.
                if (!CylinderMath.isVisible(angle)) continue
                if (abs(screenAngle) > halfFov * 0.98f) continue
                onScreen++
                val tanHalf = kotlin.math.tan(halfFov)
                val nx = kotlin.math.tan(screenAngle) / tanHalf
                val x = (nx * 0.5f + 0.5f) * 1080f
                // and the inverse must recover the angle, hence the book
                val recovered = CylinderMath.angleForScreenX(x, 1080f, halfFov)
                assertNotNull("could not hit-test book $i", recovered)
                assertEquals(
                    "book $i was hit as the wrong one",
                    i,
                    CylinderMath.bookNearAngle(count, 0f, recovered!!)
                )
            }
        }
        assertTrue("the test should have exercised several books, not $onScreen", onScreen >= 3)
    }

    @Test
    fun `a tap outside the lens hits nothing`() {
        val halfFov = CylinderMath.halfFovH(aspect = 0.5f)
        assertEquals(null, CylinderMath.angleForScreenX(-40f, 1080f, halfFov))
        assertEquals(null, CylinderMath.angleForScreenX(1120f, 1080f, halfFov))
        assertEquals(null, CylinderMath.angleForScreenX(500f, 0f, halfFov))
    }

    @Test
    fun `the lens is computed from the viewport, never assumed`() {
        // a phone in portrait: a 55 degree vertical lens is only about 29 degrees horizontally
        val portrait = CylinderMath.halfFovH(aspect = 690f / 1376f)
        assertEquals(0.2554f, portrait, 0.002f)
        // half-angle of 14.6 degrees: a 29 degree lens in total
        assertEquals(14.63f, Math.toDegrees(portrait.toDouble()).toFloat(), 0.1f)
        assertEquals(29.3f, Math.toDegrees(portrait.toDouble()).toFloat() * 2f, 0.2f)
        // and a landscape screen is much wider
        val landscape = CylinderMath.halfFovH(aspect = 1376f / 690f)
        assertTrue("landscape must be wider than portrait", landscape > portrait)
        // a square viewport is exactly the vertical half-angle
        assertEquals(
            Math.toRadians(CylinderMath.FOV_VERTICAL_DEGREES / 2.0).toFloat(),
            CylinderMath.halfFovH(aspect = 1f),
            1e-4f
        )
    }

    @Test
    fun `a tap on the bare shelf between books opens nothing`() {
        // with 4 books they are 90 degrees apart and a book is only 11 degrees wide, so most of
        // the shelf is empty and must not respond
        val count = 4
        val bookWidth = CylinderMath.bookArcRadians()
        assertTrue(
            "a tap on the middle of book 0",
            CylinderMath.isTapOnBook(0f, 0, count, 0f)
        )
        assertTrue(
            "a tap just inside the spine's edge",
            CylinderMath.isTapOnBook(bookWidth * 0.49f, 0, count, 0f)
        )
        assertTrue(
            "a tap well clear of any book",
            !CylinderMath.isTapOnBook(bookWidth * 1.5f, 0, count, 0f)
        )
        // half a slot away is the empty middle: nothing there
        assertTrue(
            "half a slot from book 0 is empty shelf",
            !CylinderMath.isTapOnBook(CylinderMath.angleStep(count) / 2f, 0, count, 0f)
        )
        // out of range indices are never a hit
        assertTrue(!CylinderMath.isTapOnBook(0f, -1, count, 0f))
        assertTrue(!CylinderMath.isTapOnBook(0f, count, count, 0f))
        assertTrue(!CylinderMath.isTapOnBook(0f, 0, 0, 0f))
    }

    @Test
    fun `a book fills a sensible part of a portrait screen`() {
        // The whole scene's scale exists to satisfy this. A book should span a good fraction of
        // the screen height, and the camera must sit where the geometry says it does.
        val aspect = 690f / 1376f
        val halfFovV = Math.toRadians(CylinderMath.FOV_VERTICAL_DEGREES / 2.0).toFloat()
        val visibleHeight = 2f * CylinderMath.SPINE_DISTANCE * kotlin.math.tan(halfFovV)
        val fraction = CylinderMath.BOOK_HEIGHT / visibleHeight
        assertTrue("a book covers only ${fraction * 100}% of the screen", fraction > 0.35f)
        assertTrue("a book overflows the screen", fraction < 0.55f)
        // and the camera really is that far from the front book's spine
        // the spine is the book's front face, half its depth nearer the reader than its centre
        val spineZ = CylinderMath.positionZ(0f) + CylinderMath.BOOK_DEPTH / 2f
        assertEquals(CylinderMath.SPINE_DISTANCE, -spineZ, 1e-4f)
        // the lens is not so narrow that only one book is ever visible
        val halfFovH = CylinderMath.halfFovH(aspect = aspect)
        assertTrue("horizontal lens is too narrow", halfFovH > 0.2f)
    }

    @Test
    fun `book indices are always in range`() {
        for (count in listOf(1, 2, 7, 33)) {
            for (rotation in listOf(0f, 1.1f, -3.4f, 12.9f, -40f)) {
                val front = CylinderMath.frontIndex(count, rotation)
                assertTrue("front $front out of range for $count", front in 0 until count)
                for (i in 0 until count) {
                    val near = CylinderMath.bookNearAngle(count, rotation, 0.3f)
                    assertTrue(near in 0 until count)
                }
                val visible = CylinderMath.visibleIndices(count, rotation)
                assertTrue(visible.all { it in 0 until count })
            }
        }
        assertEquals(-1, CylinderMath.frontIndex(0, 0f))
        assertEquals(-1, CylinderMath.bookNearAngle(0, 0f, 0f))
        assertTrue(CylinderMath.visibleIndices(0, 0f).isEmpty())
    }
}
