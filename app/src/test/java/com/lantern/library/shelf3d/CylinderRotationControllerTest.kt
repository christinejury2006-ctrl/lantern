package com.lantern.library.shelf3d

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drum's feel: drag tracking, momentum, settling, and — most important for battery — that
 * it reports when it has stopped so the render loop can shut down.
 */
class CylinderRotationControllerTest {

    /** A portrait phone, which is what the shelf is framed for. */
    private val width = 690
    private val viewportHalfFov = CylinderMath.halfFovH(aspect = 690f / 1376f)

    private fun controller(books: Int = 6) = CylinderRotationController().apply {
        // without this the controller has no idea how pixels map to the drum
        trackViewport(width, viewportHalfFov)
        setBookCount(books)
    }

    @Test
    fun `dragging turns the drum with the finger`() {
        val c = controller()
        val moved = c.onDrag(100f)
        assertTrue(moved)
        // dragging right rolls the surface right, which turns the drum the other way
        assertTrue("a rightward drag must turn the drum anticlockwise", c.rotation < 0f)
        assertEquals(-100f * c.radiansPerPixel(), c.rotation, 1e-6f)
    }

    @Test
    fun `the drum keeps up with the finger`() {
        // The whole point: a swipe across the screen must move the shelf's surface by as much
        // as the finger travelled, not ten times more. The first device build used a
        // hand-picked constant and ran 9.7x too fast, which felt like "it jumps to the next
        // book" because any drag overshot a slot.
        val c = controller()
        assertEquals(
            "tracking gain should be R / (axis - R)",
            CylinderMath.DRUM_RADIUS / (CylinderMath.AXIS_DISTANCE - CylinderMath.DRUM_RADIUS),
            CylinderMath.trackingGain(),
            1e-4f
        )
        // one pixel should be worth roughly one milliradian of drum at this viewport
        assertEquals(1.02f, c.radiansPerPixel() * 1000f, 0.05f)
        // a full-width swipe turns the drum by the view angle divided by the gain, and nothing
        // near a whole revolution
        c.onDrag(width.toFloat())
        val expected = -2f * kotlin.math.tan(viewportHalfFov) / CylinderMath.trackingGain()
        assertEquals(expected, c.rotation, 1e-4f)
        assertTrue("a full swipe turned the drum too far", abs(c.rotation) < 1f)
    }

    @Test
    fun `a small drag does not skip a book`() {
        // with 10 books a slot is 36 degrees; a 60 px drag is about 3.5 degrees and must not
        // cross one
        val c = controller(books = 10)
        c.onDrag(60f)
        val slot = CylinderMath.angleStep(10)
        assertTrue("a short drag crossed ${abs(c.rotation) / slot} of a slot", abs(c.rotation) < slot * 0.2f)
    }

    @Test
    fun `an unknown viewport still turns the drum a sane amount`() {
        // before a surface exists the gain is a fallback; it must not be violent
        val c = CylinderRotationController()
        c.setBookCount(8)
        c.onDrag(500f)
        assertTrue("fallback gain turned the drum ${c.rotation} rad", abs(c.rotation) < 1.5f)
    }

    @Test
    fun `a drag that does not move is ignored`() {
        val c = controller()
        assertFalse(c.onDrag(0f))
        assertFalse(c.onDrag(Float.NaN))
        assertEquals(0f, c.rotation, 0f)
    }

    @Test
    fun `dragging either way wraps instead of running away`() {
        val c = controller()
        repeat(400) { c.onDrag(37f) }
        assertTrue(abs(c.rotation) <= Math.PI.toFloat() + 1e-4f)
        repeat(800) { c.onDrag(-37f) }
        assertTrue(abs(c.rotation) <= Math.PI.toFloat() + 1e-4f)
    }

    @Test
    fun `a flick keeps the drum coasting and then stops it`() {
        val c = controller()
        c.onDrag(-100f)
        c.onRelease(4000f)
        assertTrue("a flick should keep the drum moving", c.isMoving)
        var frames = 0
        while (c.tick(1f / 60f) && frames < 600) frames++
        assertFalse(c.isMoving)
        assertTrue("the drum should coast for a while", frames > 5)
        assertTrue("the drum should not coast forever", frames < 600)
        // and it must finish settled exactly on a book, ready for the next tap: the rotation
        // is a whole number of steps, however many steps away that book is
        val step = CylinderMath.angleStep(6)
        val ratio = c.rotation / step
        assertTrue("settled ${abs(ratio - Math.round(ratio))} of a step off a book",
            abs(ratio - Math.round(ratio)) < 0.02f)
    }

    @Test
    fun `a slow release settles onto the nearest book`() {
        val c = controller()
        c.onDrag(-52f) // a third of a book's step
        c.onRelease(0f)
        var frames = 0
        while (c.tick(1f / 60f) && frames < 600) frames++
        assertFalse(c.isMoving)
        val ratio = c.rotation / CylinderMath.angleStep(6)
        assertTrue("did not settle on a book", abs(ratio - Math.round(ratio)) < 0.02f)
    }

    @Test
    fun `a tick while stopped costs nothing`() {
        val c = controller()
        // nothing has happened yet, so the loop must not ask for frames
        assertFalse(c.tick(1f / 60f))
        assertFalse(c.isMoving)
    }

    @Test
    fun `the drum never snaps when settling`() {
        val c = controller()
        c.onDrag(-40f)
        c.onRelease(0f)
        var previous = c.rotation
        var largestStep = 0f
        var frames = 0
        while (c.tick(1f / 60f) && frames < 600) {
            val step = abs(CylinderMath.wrapSigned(c.rotation - previous))
            largestStep = maxOf(largestStep, step)
            previous = c.rotation
            frames++
        }
        // one frame must never jump more than a small fraction of a book
        assertTrue("the drum jumped ${largestStep} rad in one frame", largestStep < 0.25f)
    }

    @Test
    fun `revealing a book animates there and stops`() {
        val c = controller(8)
        c.revealBook(5)
        assertTrue(c.isMoving)
        var frames = 0
        while (c.tick(1f / 60f) && frames < 600) frames++
        assertFalse(c.isMoving)
        assertEquals(5, CylinderMath.frontIndex(8, c.rotation))
    }

    @Test
    fun `revealing without animation lands immediately`() {
        val c = controller(8)
        c.revealBook(3, animate = false)
        assertFalse(c.isMoving)
        assertEquals(3, CylinderMath.frontIndex(8, c.rotation))
    }

    @Test
    fun `changing the book count stops any motion`() {
        val c = controller(4)
        c.onRelease(3000f)
        assertTrue(c.isMoving)
        c.setBookCount(9)
        assertFalse("a new library must not leave the drum spinning", c.isMoving)
    }

    @Test
    fun `an empty library cannot be revealed or settled`() {
        val c = CylinderRotationController()
        c.setBookCount(0)
        c.revealBook(2)
        assertFalse(c.isMoving)
        c.onRelease(200f)
        var frames = 0
        // coasting still has to bleed off, even with nothing to settle onto
        while (c.tick(1f / 60f) && frames < 900) frames++
        assertFalse(c.isMoving)
    }

    @Test
    fun `pause resets the drum cleanly`() {
        val c = controller()
        c.onRelease(2500f)
        c.reset()
        assertFalse(c.isMoving)
        assertFalse(c.isDragging())
        assertFalse(c.tick(1f / 60f))
    }

    @Test
    fun `a flick is speed limited`() {
        val c = controller()
        c.onRelease(50_000f)
        assertTrue("velocity must be capped", abs(c.velocity) <= 4f + 1e-4f)
    }

    @Test
    fun `a huge frame gap cannot fling the drum`() {
        val c = controller()
        c.onRelease(9000f)
        // a stall (backgrounding the app, a GC pause) must not be treated as elapsed motion
        val before = c.rotation
        c.tick(10f)
        val moved = abs(CylinderMath.wrapSigned(c.rotation - before))
        assertTrue("one frame moved $moved rad", moved < 0.35f)
    }
}
