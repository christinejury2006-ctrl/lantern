package com.lantern.library.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic that decides which pages a turning leaf shows.
 *
 * This is the part of the reader's crash that can be pinned down without a device: the exception
 * came out of the composition, and the only way a composition can be asked to draw a page that
 * does not exist is if the index arithmetic allowed it. So the cases that used to be dangerous are
 * enumerated here — an empty book, a single page, the first and last page, a page count that
 * shrinks under a drag, a stale page number, a turn that has nowhere to go — and each one asserts
 * that the two indices handed to the composition are real pages.
 */
class LeafWindowTest {

    private fun window(pageCount: Int, page: Int, turn: Float) =
        LeafWindowMath.window(pageCount, page, turn)

    @Test
    fun `an empty book still yields a valid single page`() {
        val w = window(pageCount = 0, page = 0, turn = 0f)
        assertEquals(1, w.count)
        assertEquals(0, w.sheetPage)
        assertEquals(0, w.basePage)
        assertFalse(w.drawing)
    }

    @Test
    fun `a one-page book never draws a sheet, in either direction`() {
        for (turn in listOf(-1f, -0.5f, 0f, 0.5f, 1f)) {
            val w = window(pageCount = 1, page = 0, turn = turn)
            assertFalse("turn $turn should not animate", w.drawing)
            assertEquals(0, w.sheetPage)
            assertEquals(0, w.basePage)
        }
    }

    @Test
    fun `a forward turn shows the current page on the sheet and the next one underneath`() {
        val w = window(pageCount = 10, page = 3, turn = 0.5f)
        assertTrue(w.drawing)
        assertTrue(w.forward)
        assertEquals(3, w.sheetPage)
        assertEquals(4, w.basePage)
        assertEquals(0.5f, w.curl, 0.0001f)
    }

    @Test
    fun `a backward turn shows the previous page on the sheet and the current one underneath`() {
        val w = window(pageCount = 10, page = 3, turn = -0.5f)
        assertTrue(w.drawing)
        assertFalse(w.forward)
        assertEquals(2, w.sheetPage)
        assertEquals(3, w.basePage)
        // Dragging back means the sheet is already half gone.
        assertEquals(0.5f, w.curl, 0.0001f)
    }

    @Test
    fun `dragging forward off the last page does nothing`() {
        val w = window(pageCount = 10, page = 9, turn = 0.6f)
        assertFalse(w.drawing)
        assertEquals(9, w.sheetPage)
        assertEquals(9, w.basePage)
        assertNull(LeafWindowMath.targetAfterTurn(10, 9, forward = true))
    }

    @Test
    fun `dragging back off the first page does nothing`() {
        val w = window(pageCount = 10, page = 0, turn = -0.6f)
        assertFalse(w.drawing)
        assertEquals(0, w.sheetPage)
        assertEquals(0, w.basePage)
        assertNull(LeafWindowMath.targetAfterTurn(10, 0, forward = false))
    }

    @Test
    fun `a page list that shrinks under a drag cannot produce an index past the end`() {
        // The reader was on page 40 of 50 with a turn in flight; a font change re-paginated the
        // book down to 12 pages. Both indices must still be real pages.
        for (count in listOf(12, 5, 2, 1)) {
            for (turn in listOf(-0.9f, -0.3f, 0.3f, 0.9f)) {
                val w = window(pageCount = count, page = 40, turn = turn)
                assertTrue("count=$count sheet=${w.sheetPage}", w.sheetPage in 0 until count)
                assertTrue("count=$count base=${w.basePage}", w.basePage in 0 until count)
            }
        }
    }

    @Test
    fun `a stale page number is clamped rather than trusted`() {
        assertEquals(6, LeafWindowMath.clampPage(99, 7))
        assertEquals(0, LeafWindowMath.clampPage(-4, 7))
        assertEquals(0, LeafWindowMath.clampPage(3, 0))
    }

    @Test
    fun `a completed turn lands on the page the base slot was showing`() {
        // Whatever the sheet looked like at the end of the animation, the page the reader is left
        // with is the one that was underneath it — otherwise a forward turn would jump backwards.
        for (count in 2..12) {
            for (page in 0 until count - 1) {
                val mid = window(count, page, turn = 0.5f)
                assertEquals(mid.basePage, LeafWindowMath.targetAfterTurn(count, page, true))
            }
        }
    }

    @Test
    fun `a finished turn is not drawn as a curl`() {
        val forwardDone = window(pageCount = 10, page = 4, turn = 1f)
        assertFalse(forwardDone.drawing)
        val backDone = window(pageCount = 10, page = 4, turn = -1f)
        assertFalse(backDone.drawing)
    }

    @Test
    fun `nonsense turn values are clamped, not propagated`() {
        val huge = window(pageCount = 10, page = 4, turn = 99f)
        assertEquals(1f, huge.curl, 0.0001f)
        val nan = window(pageCount = 10, page = 4, turn = Float.NaN)
        assertFalse(nan.drawing)
        assertEquals(4, nan.sheetPage)
    }
}
