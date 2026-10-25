package com.lantern.library.shelf

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The bookcase as world-space geometry.
 *
 * The viewer sits at the origin looking down -z. Books stand on the inside of a cylinder of
 * radius [RADIUS] that wraps around them: that curve is what makes the case bow away at the
 * edges and turns the far books so less of their spines faces you. Everything here is plain
 * maths with no Android types, so the whole layout is unit tested on the JVM.
 *
 * Units are metres. Axes: x right, y up, z toward the viewer, so the shelf sits at z = -RADIUS.
 */
object ShelfLayout {

    /** Radius of the inside of the case, i.e. how far the books are from the eye. */
    const val RADIUS = 1.15f

    /** Half the arc the case is built over. Wider than any screen, so the case never ends. */
    const val HALF_ARC = 0.86f

    /**
     * Half the arc books are laid out over, per row count.
     *
     * The camera fits the case vertically, so the wider slice of shelf it can show depends on
     * how tall the case is: a one-row case fills the screen with a narrow arc visible, a
     * three-row case fills it with a wider one. Laying books out to the arc that will actually
     * be on screen is what keeps them a believable size instead of bunched in the middle, and
     * the case itself continues past the edges as real joinery would.
     */
    fun layoutArc(rows: Int): Float = when (rows) {
        1 -> 0.085f
        2 -> 0.150f
        else -> 0.210f
    }

    /** Thickness of a shelf board. */
    const val BOARD = 0.022f

    /** Height of the gap a row of books stands in, board top to next board underside. */
    const val ROW_GAP = 0.32f

    /** y of the top of the lowest board. Rows climb from here. */
    const val BASE_Y = -0.62f

    /** Books never stand taller than this, leaving air above every row. */
    const val BOOK_MAX_H = 0.288f

    /** Deepest a book can be. */
    const val BOOK_MAX_D = 0.20f

    /** How far a lifted book comes out of the shelf toward the viewer. */
    const val LIFT_DISTANCE = 0.26f

    /** The most rows the case will ever have. Small libraries use fewer. */
    const val ROWS = 3

    /** Width a book is drawn at when there is room for it, in metres. */
    const val TYPICAL_WIDTH = 0.105f

    /** The gap left between neighbouring books. */
    const val TYPICAL_GAP = 0.012f

    /** Atlas grid: 8 x 8 cells of spine art. */
    const val ATLAS_COLS = 8
    const val ATLAS_ROWS = 8
    const val CELLS = ATLAS_COLS * ATLAS_ROWS

    /**
     * Regions inside one atlas cell, in cell-local uv with v running down the image.
     * A cell holds the spine, the cover and the page block of a single book.
     */
    const val SPINE_U1 = 0.42f
    const val COVER_V1 = 0.62f

    /** Radius of the painted room behind the case. */
    const val ROOM_RADIUS = RADIUS + 0.95f

    /**
     * How many rows a library of [count] books needs.
     *
     * Two books on each of three shelves looks like an abandoned shop. A small library gets
     * one or two shelves and wider spacing; the case only grows as the library does.
     */
    fun rowsFor(count: Int): Int = when {
        count <= 3 -> 1
        count <= 9 -> 2
        else -> ROWS
    }

    /** How many books a comfortable row holds. */
    private fun perRowTarget(): Int = 4

    /** Top of the board that [row] stands on. */
    fun rowBaseY(row: Int): Float = BASE_Y + row * ROW_GAP

    /** y where the board above [row] begins. */
    fun rowCeilingY(row: Int): Float = rowBaseY(row) + ROW_GAP - BOARD

    /** Centre y of a book of [height] standing in [row]. */
    fun bookCentreY(row: Int, height: Float): Float = rowBaseY(row) + height / 2f

    /** y of the top of a case with [rows] shelves. */
    fun caseTopY(rows: Int = ROWS): Float = rowBaseY(rows - 1) + ROW_GAP

    /** y of the underside of the lowest board. */
    fun caseBottomY(): Float = BASE_Y - BOARD

    /** x of a point on the cylinder at [angle]. Angle 0 is straight ahead. */
    fun arcX(angle: Float, radius: Float): Float = radius * sin(angle)

    /** z of a point on the cylinder at [angle]. Always negative: the shelf is in front. */
    fun arcZ(angle: Float, radius: Float): Float = -radius * cos(angle)

    /**
     * A book's place on the shelf.
     *
     * [angle] is the centre of the book on the cylinder, [arc] the width it takes along the
     * curve, [cell] its tile in the spine atlas.
     */
    data class Slot(
        val row: Int,
        val index: Int,
        val angle: Float,
        val arc: Float,
        val width: Float,
        val height: Float,
        val depth: Float,
        val cell: Int
    )

    /**
     * Deterministic per-book variation: heights, widths and depths all drift a little on a
     * real shelf. A hash keeps that stable across recompositions, with no Random going stale.
     */
    fun wobble(seed: Int, salt: Int): Float {
        var h = seed * -1640531535 + salt * -2048144777
        h = h xor (h ushr 15)
        h *= -1028477379
        h = h xor (h ushr 13)
        return ((h ushr 8) and 0xFFFF) / 65535f
    }

    /**
     * Lays [count] books across as many rows as they need, filling the arc that will be on screen.
     *
     * Books are spread evenly across the arc so a row reads as one run of books with even air
     * between them, rather than a clump in the middle. Width is capped so a nearly empty
     * shelf gives ordinary books with space around them, not planks.
     */
    fun slots(count: Int): List<Slot> {
        if (count <= 0) return emptyList()
        val rows = rowsFor(count)
        val arc = layoutArc(rows)
        val perRow = (count + rows - 1) / rows
        val out = ArrayList<Slot>(count)
        var placed = 0
        for (row in 0 until rows) {
            val inThisRow = minOf(perRow, count - placed)
            if (inThisRow <= 0) break
            // Book centres stop half a book short of the arc, otherwise the outer books hang
            // off the edge of the screen by half their width.
            val maxHalfBook = TYPICAL_WIDTH * 1.12f / 2f / RADIUS
            val centreSpan = (arc - maxHalfBook).coerceAtLeast(0f)
            val stride = if (inThisRow == 1) 0f else (centreSpan * 2f) / (inThisRow - 1)
            for (i in 0 until inThisRow) {
                val n = placed + i
                val h = BOOK_MAX_H * (0.76f + 0.24f * wobble(n, 1))
                // never wider than its stride allows, never wider than a real hardback
                val room = if (inThisRow == 1) arc * 2f else stride * RADIUS * 0.86f
                val w = minOf(TYPICAL_WIDTH * (0.82f + 0.30f * wobble(n, 2)), room)
                val d = BOOK_MAX_D * (0.66f + 0.30f * wobble(n, 3))
                val centre = if (inThisRow == 1) 0f else -centreSpan + stride * i
                out.add(
                    Slot(
                        row = row,
                        index = n,
                        angle = centre,
                        arc = w / RADIUS,
                        width = w,
                        height = h,
                        depth = d,
                        cell = n % CELLS
                    )
                )
            }
            placed += inThisRow
        }
        return out
    }

    /** How many rows the given books actually occupy. */
    fun rowsUsed(slots: List<Slot>): Int =
        if (slots.isEmpty()) ROWS else (slots.maxOf { it.row } + 1)

    /**
     * Column-major 4x4 transform placing a book's unit box on the shelf.
     *
     * The box is scaled to the book's size, turned so its spine (the box's -z face) points
     * back at the viewer, then moved onto the arc. A lifted book slides out along its own
     * spine normal and tips back slightly, the way a hand pulls one off a shelf.
     *
     * Writes into [out] so the render loop allocates nothing.
     */
    fun bookTransform(slot: Slot, lift: Float, out: FloatArray) {
        val l = lift.coerceIn(0f, 1f)
        // ease out: quick release, gentle settle
        val ease = 1f - (1f - l) * (1f - l)
        val radius = RADIUS - LIFT_DISTANCE * ease
        val px = arcX(slot.angle, radius)
        val pz = arcZ(slot.angle, radius)
        val cy = bookCentreY(slot.row, slot.height) + 0.04f * ease
        // A spin of (PI - angle) aims the box's -z face back at the camera.
        val spin = Math.PI.toFloat() - slot.angle
        val c = cos(spin)
        val s = sin(spin)
        val tilt = -0.20f * ease
        val ct = cos(tilt)
        val st = sin(tilt)
        // R = Ry(spin) * Rx(tilt), written out to keep this allocation free.
        val sx = slot.width
        val sy = slot.height
        val sz = slot.depth
        out[0] = c * sx;        out[1] = 0f;         out[2] = -s * sx;       out[3] = 0f
        out[4] = s * st * sy;   out[5] = ct * sy;    out[6] = c * st * sy;   out[7] = 0f
        out[8] = s * ct * sz;   out[9] = -st * sz;   out[10] = c * ct * sz;  out[11] = 0f
        out[12] = px;           out[13] = cy;        out[14] = pz;           out[15] = 1f
    }

    /** Convenience for tests and one-off use. */
    fun bookTransform(slot: Slot, lift: Float = 0f): FloatArray {
        val out = FloatArray(16)
        bookTransform(slot, lift, out)
        return out
    }

    /**
     * A strip of the case around the arc: [a0]..[a1] at [radius], from [y0] to [y1].
     * Boards, the back panel and the stiles are all built from these.
     */
    data class Strip(
        val a0: Float,
        val a1: Float,
        val radius: Float,
        val y0: Float,
        val y1: Float,
        /** Brightness multiplier for the strip, standing in for the light it catches. */
        val shade: Float
    )

    /**
     * The case: a back panel behind every row, a board under every row plus one on top, and
     * two vertical stiles at the ends to close it off.
     *
     * The back panel is split per row on purpose: one panel for the whole case would stretch
     * a single photograph of wood across three shelves and the grain would be a smear.
     */
    fun caseStrips(rows: Int = ROWS): List<Strip> {
        val strips = ArrayList<Strip>()
        val back = RADIUS + BOOK_MAX_D + 0.03f
        val top = caseTopY(rows)
        for (row in 0 until rows) {
            // one back panel per row, so the wood grain repeats at a believable scale
            strips.add(
                Strip(
                    -HALF_ARC, HALF_ARC, back,
                    rowBaseY(row) - BOARD, rowBaseY(row) + ROW_GAP,
                    0.52f
                )
            )
        }
        for (row in 0..rows) {
            val y = if (row == rows) top else rowBaseY(row) - BOARD
            strips.add(Strip(-HALF_ARC, HALF_ARC, RADIUS + BOOK_MAX_D + 0.012f, y, y + BOARD, 0.98f))
        }
        // stiles: narrow vertical posts at both ends of the arc
        val stile = 0.055f
        strips.add(Strip(-HALF_ARC, -HALF_ARC + stile, RADIUS + BOOK_MAX_D + 0.012f, caseBottomY(), top, 0.78f))
        strips.add(Strip(HALF_ARC - stile, HALF_ARC, RADIUS + BOOK_MAX_D + 0.012f, caseBottomY(), top, 0.78f))
        return strips
    }

    /**
     * Half the height the camera must show: the whole case plus a margin of air.
     */
    fun visibleHalfHeight(rows: Int = ROWS): Float =
        (caseTopY(rows) - caseBottomY()) / 2f + 0.055f

    /**
     * Tangent of the half vertical field of view.
     *
     * This is derived purely from the height of the case, and it is deliberately the ONLY
     * constraint. Fitting the width as well forced a very wide lens on a tall phone, which
     * shrank the case to a third of the screen and left the painted room filling the rest.
     * Horizontal cropping is fine: the case is built wider than any screen, so it simply
     * continues past the edges like real shelving.
     */
    fun tanVFov(rows: Int = ROWS): Float = visibleHalfHeight(rows) / RADIUS

    /** Tangent of the half horizontal field of view, straight from the window's aspect. */
    fun tanHFov(aspect: Float, rows: Int = ROWS): Float =
        tanVFov(rows) * aspect.coerceAtLeast(0.2f)

    /** The half angle the screen actually covers, for laying things out. */
    fun visibleHalfArc(aspect: Float, rows: Int = ROWS): Float = atan(tanHFov(aspect, rows))

    /**
     * Perspective projection terms: left, right, bottom, top, near, far.
     */
    fun projection(aspect: Float, near: Float, rows: Int = ROWS): FloatArray {
        val th = tanHFov(aspect, rows) * near
        val tv = tanVFov(rows) * near
        return floatArrayOf(-th, th, -tv, tv, near, 8f)
    }

    /**
     * Where a tap at ([screenX], [screenY]) lands: (row, angle), or null if it misses the
     * shelf. Mirrors the projection the camera is set up with, so no picking pass is needed.
     */
    fun pickAngle(
        screenX: Float,
        screenY: Float,
        viewW: Float,
        viewH: Float,
        rows: Int = ROWS
    ): Pair<Int, Float>? {
        if (viewW <= 0f || viewH <= 0f) return null
        val aspect = viewW / viewH
        val nx = (screenX / viewW - 0.5f) * 2f
        val angle = atan(nx * tanHFov(aspect, rows))
        val ny = (0.5f - screenY / viewH) * 2f
        // where that ray crosses the plane of the books
        val y = ny * tanVFov(rows) * RADIUS
        for (row in 0 until rows) {
            if (y >= rowBaseY(row) && y <= rowCeilingY(row) + BOARD) return Pair(row, angle)
        }
        return null
    }

    /** The book nearest [angle] in [row], or null if nothing is close enough. */
    fun slotAt(slots: List<Slot>, row: Int, angle: Float): Slot? =
        slots.asSequence()
            .filter { it.row == row }
            .minByOrNull { abs(it.angle - angle) }
            ?.takeIf { abs(it.angle - angle) <= it.arc / 2f + 0.055f }

    /**
     * Critically damped spring, one step. Every bit of motion uses this so nothing snaps
     * and nothing oscillates: a lifted book settles instead of bouncing.
     */
    fun springStep(current: Float, velocity: Float, target: Float, dt: Float): Pair<Float, Float> {
        val stiffness = 190f
        val damping = 2f * sqrt(stiffness)
        val accel = (target - current) * stiffness - velocity * damping
        val v = velocity + accel * dt
        val c = current + v * dt
        if (abs(target - c) < 0.0004f && abs(v) < 0.008f) return Pair(target, 0f)
        return Pair(c, v)
    }
}
