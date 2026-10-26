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
    fun `the case fills the phone however long it is`() {
        // One row of books is a band across the middle of any screen. The number of tiers
        // follows the phone, and the camera distance follows the tiers, so the case occupies the
        // same share of the screen on a short phone as on a tall one.
        val tall = CylinderMath.rowsFor(900f)
        val short = CylinderMath.rowsFor(430f)
        assertTrue("a tall phone must carry more tiers than a short one", tall > short)
        assertEquals(1, CylinderMath.rowsFor(320f))
        assertEquals(4, CylinderMath.rowsFor(1000f))

        val halfFovV = Math.toRadians(CylinderMath.FOV_VERTICAL_DEGREES / 2.0).toFloat()
        for (rows in 1..4) {
            val distance = CylinderMath.caseDistanceFor(rows)
            val caseHeight = rows * CylinderMath.ROW_PITCH
            val visibleHeight = 2f * distance * kotlin.math.tan(halfFovV)
            val fill = caseHeight / visibleHeight
            assertEquals("$rows tiers should fill 86% of the height", 0.86f, fill, 0.01f)
        }
    }

    @Test
    fun `books are split over the tiers and stay in their slots`() {
        for (count in listOf(1, 5, 12, 31, 100, 201)) {
            for (rows in 1..4) {
                val perRow = CylinderMath.perRowFor(count, rows)
                assertTrue("$count books over $rows rows leaves an empty row", perRow > 0)
                assertTrue("$rows rows must hold all $count books", perRow * rows >= count)
                // every book has a tier and a slot inside that tier's drum
                for (index in 0 until count) {
                    val row = index / perRow
                    val slot = index % perRow
                    assertTrue("book $index lands on row $row", row in 0 until rows)
                    assertTrue("book $index lands on slot $slot", slot in 0 until perRow)
                }
                // and the tiers do not overlap vertically
                if (rows > 1) {
                    val gap = CylinderMath.rowY(1, rows) - CylinderMath.rowY(0, rows)
                    assertTrue("rows are $gap apart", gap >= CylinderMath.BOOK_HEIGHT)
                }
            }
        }
        // one tier holds everything
        assertEquals(37, CylinderMath.perRowFor(37, 1))
        assertEquals(0, CylinderMath.perRowFor(0, 3))
    }

    @Test
    fun `a tap's height picks the tier, since every tier shares its slots`() {
        // Four tiers: tapping the top of the screen must reach the top row, the middle the
        // middle, and the bottom the bottom, or a tap would always open a book on one shelf.
        val rows = 4
        val distance = CylinderMath.caseDistanceFor(rows)
        val height = 2160f
        val top = CylinderMath.rowAtScreenY(height * 0.08f, height, rows, distance)
        val middle = CylinderMath.rowAtScreenY(height * 0.5f, height, rows, distance)
        val bottom = CylinderMath.rowAtScreenY(height * 0.92f, height, rows, distance)
        assertEquals(rows - 1, top)
        assertEquals(0, bottom)
        assertTrue("the middle of four rows should be one of the two middle ones",
            middle == 1 || middle == 2)
        // outside the case, the nearest tier is still the best answer rather than an exception
        assertEquals(rows - 1, CylinderMath.rowAtScreenY(-500f, height, rows, distance))
        assertEquals(0, CylinderMath.rowAtScreenY(height + 500f, height, rows, distance))
        // a one-tier case has nowhere else to be
        assertEquals(0, CylinderMath.rowAtScreenY(height * 0.1f, height, 1, distance))
        assertEquals(0, CylinderMath.rowAtScreenY(0f, 0f, rows, distance))
    }

    @Test
    fun `the drum grows with the library so books never overlap`() {
        // A book is 0.115 m of spine; spread evenly round a circle of radius R it occupies
        // BOOK_WIDTH/R radians, while its slot is 2*pi/count. Once the slot is narrower than the
        // book the books interpenetrate, which is what 100 books on the smallest drum would do
        // three times over.
        for (count in 1..200) {
            val radius = CylinderMath.radiusFor(count)
            val arc = CylinderMath.bookArcRadians(radius)
            val slot = CylinderMath.angleStep(count)
            assertTrue(
                "with $count books the spine is ${Math.toDegrees(arc.toDouble())} deg of a " +
                    "${Math.toDegrees(slot.toDouble())} deg slot",
                arc <= slot + 1e-5f
            )
            assertTrue("radius must never shrink below the smallest drum",
                radius >= CylinderMath.MIN_DRUM_RADIUS - 1e-6f)
        }
        // A small drum is a small drum. Holding every library on the old 0.6 m minimum left a
        // nine-slot tier with three times more empty arc than book on it, and the books read as
        // flat cards rather than a shelf.
        assertEquals(10 * CylinderMath.BOOK_WIDTH * CylinderMath.BOOK_GAP /
            (2f * Math.PI.toFloat()), CylinderMath.radiusFor(10), 1e-5f)
        // a large one does
        assertTrue(CylinderMath.radiusFor(100) > 1.9f)
    }

    @Test
    fun `a large library shows a full run of spines, not three`() {
        // The camera distance is chosen so that min(count, BOOKS_ACROSS) spines span the width
        // the lens can see. Three spines across a 100-book library would mean an absurd amount
        // of spinning to find anything.
        val halfFov = CylinderMath.REFERENCE_HALF_FOV_H
        for (count in listOf(20, 60, 100, 400)) {
            val distance = CylinderMath.spineDistanceFor(count)
            val visibleWidth = 2f * distance * kotlin.math.tan(halfFov)
            val across = visibleWidth / CylinderMath.BOOK_WIDTH
            assertTrue("$count books show only $across spines across", across >= 10f)
            assertTrue("$count books show $across spines across", across <= 20f)
        }
        // and a three-book library shows its three books, close up
        assertEquals(
            CylinderMath.SPINE_DISTANCE,
            CylinderMath.spineDistanceFor(1),
            1e-5f
        )
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

    @Test
    fun `the picks stand first, then the niche, then the library`() {
        val placement = CylinderMath.placementFor(count = 5, slots = 8, rows = 1, picks = listOf(2))
        assertEquals(
            listOf(2, CylinderMath.GAP, 0, 1, 3, 4, CylinderMath.EMPTY, CylinderMath.EMPTY),
            placement.toList()
        )
    }

    @Test
    fun `the picks and the niche never fill a tier`() {
        val slots = 4
        val placement = CylinderMath.placementFor(
            count = 6, slots = slots, rows = 1, picks = listOf(0, 1, 2, 3, 4)
        )
        // Positions are filled a column at a time, so the first tier's picks are NOT the first
        // few entries: three picks, then the niche, then the rest. What matters is that only
        // slots-1 picks can be picked up, that the niche exists, and that no book is lost.
        val order = ArrayList<Int>()
        for (slot in 0 until slots) {
            for (position in placement.indices) {
                if (position % slots != slot) continue
                order.add(placement[position])
            }
        }
        assertEquals(listOf(0, 1, 2), order.take(3))
        assertEquals(CylinderMath.GAP, order[3])
        assertEquals(listOf(0, 1, 2, 3, 4, 5), placement.filter { it >= 0 }.sorted())
    }

    @Test
    fun `a small library stands in runs down the tiers`() {
        // Six books over four tiers used to be one book per tier, adrift in the middle of the
        // screen. Filling a column at a time, the same six books stand two deep on three slots,
        // which is a case with room to spare rather than a carousel.
        val slots = CylinderMath.MIN_SLOTS_PER_ROW
        val rows = 4
        val placement = CylinderMath.placementFor(count = 6, slots = slots, rows = rows, picks = emptyList())
        // Books are in the first six positions of the fill order: slot 0 rows 0..3, then slot 1
        // rows 0..1.
        assertEquals(0, placement[0 * slots + 0])
        assertEquals(3, placement[3 * slots + 0])
        assertEquals(4, placement[0 * slots + 1])
        assertEquals(5, placement[1 * slots + 1])
        // and the rest of the drum is empty rather than full of books stacked on top of each other
        assertEquals(CylinderMath.EMPTY, placement[0 * slots + 2])
        assertEquals(6, placement.count { it >= 0 })
    }

    @Test
    fun `every book is placed exactly once`() {
        for (count in listOf(1, 3, 6, 12, 40, 100)) {
            for (rows in 1..4) {
                val slots = maxOf(CylinderMath.perRowFor(count, rows), CylinderMath.MIN_SLOTS_PER_ROW)
                val placement = CylinderMath.placementFor(count, slots, rows, picks = listOf(0))
                val books = placement.filter { it >= 0 }.sorted()
                assertEquals("count $count rows $rows", (0 until count).toList(), books)
                assertTrue(placement.size >= slots * rows)
            }
        }
    }

    @Test
    fun `a tier that is too small borrows another rather than losing books`() {
        // The bay, the niche and the picks all take positions; if a caller under-counts the slots,
        // books must not quietly fall off the end of the drum.
        val placement = CylinderMath.placementFor(count = 6, slots = 4, rows = 1, picks = emptyList())
        assertEquals((0..5).toList(), placement.filter { it >= 0 }.sorted())
        assertEquals(8, placement.size)
    }

    @Test
    fun `a tier never has fewer slots than it takes to see a curve`() {
        for (count in 1..30) {
            for (rows in 1..4) {
                val slots = maxOf(CylinderMath.perRowFor(count, rows), CylinderMath.MIN_SLOTS_PER_ROW)
                assertTrue("count $count rows $rows gave $slots slots",
                    slots >= CylinderMath.MIN_SLOTS_PER_ROW)
                // and the drum is packed: a slot is never much wider than a book
                val arc = CylinderMath.bookArcRadians(CylinderMath.radiusFor(slots))
                assertTrue(arc <= CylinderMath.angleStep(slots) + 1e-5f)
            }
        }
    }

    @Test
    fun `a book's slot is where it stands, not where it sits in the library`() {
        val slots = 4
        val placement = CylinderMath.placementFor(count = 9, slots = slots, rows = 3, picks = listOf(7))
        assertEquals(0, CylinderMath.slotOfBook(7, placement, slots))
        assertEquals(-1, CylinderMath.slotOfBook(99, placement, slots))
    }

    @Test
    fun `without picks there is no niche`() {
        val placement = CylinderMath.placementFor(count = 6, slots = 3, rows = 1, picks = emptyList())
        assertTrue(placement.none { it == CylinderMath.GAP })
        assertEquals((0..5).toList(), placement.filter { it >= 0 }.sorted())
    }
}
