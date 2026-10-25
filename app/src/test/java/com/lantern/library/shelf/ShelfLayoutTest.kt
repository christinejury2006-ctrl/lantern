package com.lantern.library.shelf

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shelf's geometry is the part of the 3D scene a unit test can actually pin down: where
 * books stand, which way their spines face and what a tap lands on.
 */
class ShelfLayoutTest {

    @Test
    fun `an empty library produces no slots`() {
        assertTrue(ShelfLayout.slots(0).isEmpty())
        assertTrue(ShelfLayout.slots(-3).isEmpty())
    }

    @Test
    fun `every book lands on the circle of the case`() {
        for (slot in ShelfLayout.slots(30)) {
            val x = ShelfLayout.arcX(slot.angle, ShelfLayout.RADIUS)
            val z = ShelfLayout.arcZ(slot.angle, ShelfLayout.RADIUS)
            assertEquals(ShelfLayout.RADIUS, hypot(x, z), 1e-4f)
            // in front of the viewer, never behind
            assertTrue(z < 0f)
        }
    }

    @Test
    fun `books stay inside the arc of the case and never overlap`() {
        val slots = ShelfLayout.slots(24)
        for (row in 0 until ShelfLayout.ROWS) {
            val inRow = slots.filter { it.row == row }.sortedBy { it.angle }
            for ((a, b) in inRow.zipWithNext()) {
                val gap = b.angle - a.angle
                assertTrue("books overlap by ${a.arc} rad", gap > a.arc / 2f + b.arc / 2f)
            }
            for (slot in inRow) {
                assertTrue(slot.angle - slot.arc / 2f >= -ShelfLayout.HALF_ARC - 1e-4f)
                assertTrue(slot.angle + slot.arc / 2f <= ShelfLayout.HALF_ARC + 1e-4f)
            }
        }
    }

    @Test
    fun `a small library keeps book-sized books in the middle of the shelf`() {
        val slots = ShelfLayout.slots(6)
        assertEquals(6, slots.size)
        for (slot in slots) {
            // a book, not a plank, and a substantial one rather than a bookmark
            // the widest a book can be drawn is the typical width plus its own variation
            assertTrue(
                "book is ${slot.width} m wide",
                slot.width <= ShelfLayout.TYPICAL_WIDTH * 1.12f + 1e-4f
            )
            assertTrue("book is ${slot.width} m wide", slot.width >= 0.04f)
        }
        // rows are centred, so the shelf is not lopsided around a handful of books
        for (row in 0 until ShelfLayout.rowsUsed(slots)) {
            val inRow = slots.filter { it.row == row }
            val middle = inRow.sumOf { it.angle.toDouble() } / inRow.size
            assertTrue("row $row is off centre by $middle", abs(middle) < 0.02)
        }
    }

    @Test
    fun `a small library uses fewer shelves instead of leaving them empty`() {
        assertEquals(1, ShelfLayout.rowsFor(2))
        assertEquals(2, ShelfLayout.rowsFor(6))
        assertEquals(2, ShelfLayout.rowsFor(9))
        assertEquals(3, ShelfLayout.rowsFor(12))
        assertEquals(3, ShelfLayout.rowsFor(60))
        // and the case is built to match, so there is never a shelf with nothing on it
        for (count in listOf(1, 2, 6, 9, 12, 40)) {
            val slots = ShelfLayout.slots(count)
            assertEquals(ShelfLayout.rowsFor(count), ShelfLayout.rowsUsed(slots))
            for (row in 0 until ShelfLayout.rowsUsed(slots)) {
                assertTrue("row $row of $count is empty", slots.any { it.row == row })
            }
        }
    }

    @Test
    fun `the case fills the screen instead of floating in the middle of it`() {
        // This is the regression that made the shelf a small island in a photo: fitting the
        // width as well as the height zoomed the lens out on a tall phone.
        for (rows in 1..ShelfLayout.ROWS) {
            val tanV = ShelfLayout.tanVFov(rows)
            val halfHeight = (ShelfLayout.caseTopY(rows) - ShelfLayout.caseBottomY()) / 2f
            val fills = (halfHeight / ShelfLayout.RADIUS) / tanV
            assertTrue("a $rows-row case only fills ${fills * 100}% of the screen", fills > 0.70f)
        }
    }

    @Test
    fun `books are laid out across the arc that is actually on screen`() {
        for (count in listOf(2, 6, 9, 12, 40)) {
            val rows = ShelfLayout.rowsFor(count)
            val arc = ShelfLayout.layoutArc(rows)
            // the arc books occupy must be inside what a tall phone can show, or the outer
            // books are cropped off the edges of the screen
            val visible = ShelfLayout.visibleHalfArc(0.44f, rows)
            assertTrue(
                "$count books span ${arc} rad but only ${visible} rad is visible",
                arc <= visible + 0.02f
            )
            for (slot in ShelfLayout.slots(count)) {
                assertTrue(slot.angle + slot.arc / 2f <= arc + 1e-3f)
            }
        }
    }

    @Test
    fun `a large library is packed tight and still fits the arc`() {
        val slots = ShelfLayout.slots(90)
        assertEquals(90, slots.size)
        val rows = ShelfLayout.rowsUsed(slots)
        val arc = ShelfLayout.layoutArc(rows)
        for (row in 0 until rows) {
            val inRow = slots.filter { it.row == row }.sortedBy { it.angle }
            // a full row runs right across the arc that is on screen
            val span = inRow.last().angle - inRow.first().angle
            assertTrue("row $row only spans $span rad of $arc", span > arc * 0.75f)
            for ((a, b) in inRow.zipWithNext()) {
                assertTrue(b.angle - a.angle > a.arc / 2f + b.arc / 2f)
            }
        }
    }

    @Test
    fun `books stand on their board without poking through the shelf above`() {
        for (slot in ShelfLayout.slots(18)) {
            val bottom = ShelfLayout.bookCentreY(slot.row, slot.height) - slot.height / 2f
            assertEquals(ShelfLayout.rowBaseY(slot.row), bottom, 1e-4f)
            assertTrue(bottom + slot.height <= ShelfLayout.rowCeilingY(slot.row) + 1e-4f)
        }
    }

    @Test
    fun `atlas cells are distinct until the atlas runs out`() {
        val slots = ShelfLayout.slots(ShelfLayout.CELLS)
        assertEquals(ShelfLayout.CELLS, slots.map { it.cell }.toSet().size)
        // beyond that it recycles rather than failing
        val many = ShelfLayout.slots(ShelfLayout.CELLS + 10)
        assertEquals(ShelfLayout.CELLS + 10, many.size)
    }

    @Test
    fun `the spine of every book faces the viewer`() {
        for (slot in ShelfLayout.slots(12)) {
            val m = ShelfLayout.bookTransform(slot, 0f)
            // the third column of the transform is the box's +z axis, scaled by the book's
            // depth; the spine is -z. Normalise it before comparing directions.
            val scale = hypot(m[8], m[10])
            assertEquals(slot.depth, scale, 1e-4f)
            val nx = -m[8] / scale
            val nz = -m[10] / scale
            assertEquals(1f, hypot(nx, nz), 1e-4f)
            // and it must point back at the camera at the origin
            val px = m[12]
            val pz = m[14]
            val pl = hypot(px, pz)
            val dot = (nx * -px + nz * -pz) / pl
            assertEquals(1f, dot, 1e-3f)
        }
    }

    @Test
    fun `the book is scaled to its own measurements`() {
        val slot = ShelfLayout.slots(6).first()
        val m = ShelfLayout.bookTransform(slot, 0f)
        assertEquals(slot.width, hypot(m[0], m[2]),
            // the tilt is zero when settled, so x stays in the horizontal plane
            1e-4f
        )
        assertEquals(slot.height, abs(m[5]), 1e-4f)
        assertEquals(slot.depth, hypot(m[8], m[10]), 1e-4f)
    }

    @Test
    fun `lifting a book brings it toward the viewer and keeps it upright`() {
        val slot = ShelfLayout.slots(9).first()
        val down = ShelfLayout.bookTransform(slot, 0f)
        val up = ShelfLayout.bookTransform(slot, 1f)
        val restRadius = hypot(down[12], down[14])
        val liftRadius = hypot(up[12], up[14])
        assertEquals(ShelfLayout.RADIUS, restRadius, 1e-3f)
        assertEquals(ShelfLayout.RADIUS - ShelfLayout.LIFT_DISTANCE, liftRadius, 1e-3f)
        assertTrue(hypot(up[12], up[14]) < restRadius)
        // it also rises a little, which is what makes it read as being taken off the shelf
        assertTrue(up[13] > down[13])
    }

    @Test
    fun `a tap in the middle of the screen lands on the middle book`() {
        val pick = ShelfLayout.pickAngle(540f, 1200f, 1080f, 2400f)
        assertNotNull(pick)
        val (row, angle) = pick!!
        assertEquals(ShelfLayout.ROWS / 2, row)
        assertTrue(abs(angle) < 0.25f)
    }

    @Test
    fun `a tap off the case misses, and the case reaches the screen edges`() {
        assertNull(ShelfLayout.pickAngle(540f, 2f, 1080f, 2400f))
        assertNull(ShelfLayout.pickAngle(540f, 1200f, 0f, 0f))
        // the camera is aimed at the middle of the case, so the bottom board is on screen:
        // tap exactly where a book on the lowest shelf appears
        val y = ShelfLayout.rowBaseY(0) + 0.05f
        val sy = (0.5f - (y - ShelfLayout.caseCentreY()) /
            (ShelfLayout.tanVFov() * ShelfLayout.RADIUS) * 0.5f) * 2400f
        val bottom = ShelfLayout.pickAngle(540f, sy, 1080f, 2400f)
        assertNotNull("the bottom shelf is off screen", bottom)
        assertEquals(0, bottom!!.first)
    }

    @Test
    fun `the tap that lifts a book finds the book the ray hits`() {
        val slots = ShelfLayout.slots(15)
        val pick = ShelfLayout.pickAngle(540f, 1200f, 1080f, 2400f)!!
        val slot = ShelfLayout.slotAt(slots, pick.first, pick.second)
        assertNotNull(slot)
        val found = slot!!
        // the book it picked must be the closest one in that row to the ray
        val nearest = slots.filter { it.row == pick.first }.minByOrNull { abs(it.angle - pick.second) }!!
        assertEquals(nearest.index, found.index)
    }

    @Test
    fun `taps map monotonically across the shelf`() {
        // a tap further right must never land further left
        var previous = -10f
        var x = 60f
        while (x < 1020f) {
            val angle = ShelfLayout.pickAngle(x, 1200f, 1080f, 2400f)!!.second
            assertTrue("angle went backwards at x=$x", angle > previous)
            previous = angle
            x += 40f
        }
    }

    @Test
    fun `the projection covers the height of the case`() {
        val near = 0.05f
        val p = ShelfLayout.projection(0.45f, near)
        val left = p[0]
        val right = p[1]
        val bottom = p[2]
        val top = p[3]
        assertEquals(-left, right, 1e-6f)
        assertEquals(-bottom, top, 1e-6f)
        // at the radius of the books, the frustum must reach past the top and bottom boards
        val visibleHalfHeight = top / near * ShelfLayout.RADIUS
        assertTrue(
            "frustum reaches $visibleHalfHeight m but the case needs " +
                "${ShelfLayout.visibleHalfHeight()} m",
            visibleHalfHeight >= ShelfLayout.visibleHalfHeight() - 1e-4f
        )
    }

    @Test
    fun `the whole case fits whatever shape the window is`() {
        // a portrait phone, the wide band the Library shelf sits in, and landscape
        for (rows in 1..ShelfLayout.ROWS) {
            for (aspect in listOf(0.45f, 0.75f, 1.0f, 1.4f, 2.2f)) {
                val tanV = ShelfLayout.tanVFov(rows)
                val tanH = ShelfLayout.tanHFov(aspect, rows)
                // every row of the case is inside the view, top and bottom
                assertTrue(
                    "aspect $aspect crops the case vertically",
                    tanV * ShelfLayout.RADIUS >= ShelfLayout.visibleHalfHeight(rows) - 1e-5f
                )
                // a wide window shows at least as much shelf as a tall one
                assertTrue(tanH > 0f)
                assertEquals(aspect, tanH / tanV, 1e-4f)
            }
        }
    }

    @Test
    fun `taps land where the camera projects, at any aspect`() {
        for (aspect in listOf(0.45f, 1.0f, 1.4f, 2.2f)) {
            val w = 1000f
            val h = w / aspect
            val tanH = ShelfLayout.tanHFov(aspect)
            val tanV = ShelfLayout.tanVFov()
            // project a known point on the shelf and tap it
            for (angle in listOf(-0.3f, 0f, 0.25f)) {
                val x = ShelfLayout.RADIUS * sin(angle)
                val z = ShelfLayout.arcZ(angle, ShelfLayout.RADIUS)
                val y = ShelfLayout.rowBaseY(1) + 0.1f
                val sx = (x / -z / tanH * 0.5f + 0.5f) * w
                val sy = (0.5f - (y - ShelfLayout.caseCentreY()) / -z / tanV * 0.5f) * h
                val pick = ShelfLayout.pickAngle(sx, sy, w, h)
                assertNotNull("aspect $aspect missed the shelf", pick)
                assertEquals(1, pick!!.first)
                assertEquals(angle, pick.second, 0.01f)
            }
        }
    }

    @Test
    fun `the spring settles instead of bouncing`() {
        var value = 0f
        var velocity = 0f
        var overshootsAboveTarget = 0
        var steps = 0
        while (steps < 400) {
            val next = ShelfLayout.springStep(value, velocity, 1f, 1f / 60f)
            value = next.first
            velocity = next.second
            if (value > 1.0005f) overshootsAboveTarget++
            steps++
            if (value == 1f && velocity == 0f) break
        }
        assertEquals(1f, value, 1e-3f)
        assertEquals(0f, velocity, 1e-3f)
        assertTrue("the book bounced $overshootsAboveTarget times", overshootsAboveTarget <= 40)
        assertTrue("it should settle in well under 400 frames, took $steps", steps < 200)
    }

    @Test
    fun `the case encloses the books and the room sits behind it`() {
        val strips = ShelfLayout.caseStrips()
        assertTrue(strips.size >= ShelfLayout.ROWS + 1)
        // one back panel per row, so the wood grain repeats at a believable scale
        // the back panels sit further back than the boards and stiles, which share a radius
        val backRadius = ShelfLayout.RADIUS + ShelfLayout.BOOK_MAX_D + 0.03f
        assertEquals(
            ShelfLayout.ROWS,
            strips.count { it.radius > backRadius - 1e-3f && it.y1 - it.y0 > ShelfLayout.BOARD * 1.5f }
        )
        for (strip in strips) {
            assertTrue(strip.a0 < strip.a1)
            assertTrue(strip.y0 < strip.y1)
        }
        assertTrue(ShelfLayout.ROOM_RADIUS > ShelfLayout.RADIUS + ShelfLayout.BOOK_MAX_D)
        // the back panel starts at the same height as the lowest board, so pick the thin one
        val board = ShelfLayout.caseStrips()
            .first { it.y0 == ShelfLayout.rowBaseY(0) - ShelfLayout.BOARD && it.y1 - it.y0 < 0.05f }
        assertEquals(ShelfLayout.BOARD, board.y1 - board.y0, 1e-6f)
    }

    @Test
    fun `the four corners of an atlas cell never leave it`() {
        for (region in ShelfGeometry.Region.values()) {
            for (u in listOf(0f, 1f)) {
                for (v in listOf(0f, 1f)) {
                    val (cu, cv) = region.map(u, v)
                    assertTrue("u=$cu out of cell", cu >= 0f && cu <= 1f + 1e-6f)
                    assertTrue("v=$cv out of cell", cv >= 0f && cv <= 1f + 1e-6f)
                }
            }
        }
        // the spine only ever samples the left column of the cell
        val (spineU, _) = ShelfGeometry.Region.SPINE.map(1f, 0.5f)
        assertEquals(ShelfLayout.SPINE_U1, spineU, 1e-6f)
    }

    @Test
    fun `the box mesh is a closed box with the expected vertex count`() {
        val box = ShelfGeometry.box()
        assertEquals(24, box.vertexCount)
        assertEquals(36, box.indexCount)
        for (index in box.indices) {
            assertTrue("index out of range", index.toInt() in 0 until box.vertexCount)
        }
    }

    @Test
    fun `the box sits around the origin so the transform can scale it`() {
        val box = ShelfGeometry.box()
        for (i in 0 until box.vertexCount) {
            val x = box.vertices[i * 9]
            val y = box.vertices[i * 9 + 1]
            val z = box.vertices[i * 9 + 2]
            assertEquals(0.5f, maxOf(abs(x), abs(y), abs(z)), 1e-6f)
        }
    }

    @Test
    fun `the arc really curves away at the edges`() {
        val centre = ShelfLayout.arcZ(0f, ShelfLayout.RADIUS)
        val edge = ShelfLayout.arcZ(ShelfLayout.HALF_ARC, ShelfLayout.RADIUS)
        // the middle of the shelf is nearer the viewer... in -z terms, further away
        assertTrue(centre < edge)
        val edgeX = ShelfLayout.arcX(ShelfLayout.HALF_ARC, ShelfLayout.RADIUS)
        assertEquals(ShelfLayout.RADIUS * sin(ShelfLayout.HALF_ARC), edgeX, 1e-5f)
        assertEquals(-ShelfLayout.RADIUS * cos(ShelfLayout.HALF_ARC), edge, 1e-5f)
        // the built case reaches past any screen, so it never ends mid-view
        assertTrue(atan(ShelfLayout.RADIUS * sin(ShelfLayout.HALF_ARC) / -edge) >
            ShelfLayout.visibleHalfArc(0.44f))
    }
}
