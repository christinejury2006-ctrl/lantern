package com.lantern.library.data

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * A tiny 3D projection for the bookcase.
 *
 * The shelf is a curved bookcase: books stand on a cylinder of radius [Camera.radius] that
 * wraps around the viewer, which is what makes the boards bow and the books at the edges
 * turn away and grow slightly. Everything here is pure maths over that cylinder, so the
 * geometry can be unit tested without a device.
 *
 * Axes: x right, y down, z away from the viewer. A point on the shelf is given by the angle
 * it sits at on the cylinder, its radius, and its height relative to the eye.
 */
object Shelf3D {

    data class Pt(val x: Float, val y: Float)

    /** A projected quad, wound top-left, top-right, bottom-right, bottom-left. */
    data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {
        fun points(): List<Pt> = listOf(tl, tr, br, bl)

        /** Widest vertical extent, used for cheap bounds checks. */
        fun top(): Float = minOf(tl.y, tr.y)
        fun bottom(): Float = maxOf(bl.y, br.y)
        fun left(): Float = minOf(tl.x, bl.x)
        fun right(): Float = maxOf(tr.x, br.x)
    }

    data class Camera(
        val focal: Float,
        val centerX: Float,
        val centerY: Float,
        val radius: Float
    )

    /** Projects a point on the cylinder to the screen. */
    fun project(angle: Float, radius: Float, y: Float, cam: Camera): Pt {
        val z = radius * cos(angle)
        // never divide by a vanishing depth: keep the point behind the camera finite
        val depth = if (z < cam.focal * 0.06f) cam.focal * 0.06f else z
        return Pt(
            cam.centerX + cam.focal * radius * sin(angle) / depth,
            cam.centerY + cam.focal * y / depth
        )
    }

    /** The flat face of a book spine (or the front of a board) at [radius]. */
    fun face(a0: Float, a1: Float, radius: Float, yTop: Float, yBottom: Float, cam: Camera): Quad =
        Quad(
            project(a0, radius, yTop, cam),
            project(a1, radius, yTop, cam),
            project(a1, radius, yBottom, cam),
            project(a0, radius, yBottom, cam)
        )

    /** A horizontal face, such as the page block on top of a book or a shelf board. */
    fun flat(a0: Float, a1: Float, radiusFront: Float, radiusBack: Float, y: Float, cam: Camera): Quad =
        Quad(
            project(a0, radiusFront, y, cam),
            project(a1, radiusFront, y, cam),
            project(a1, radiusBack, y, cam),
            project(a0, radiusBack, y, cam)
        )

    /** A side face of a book standing at [angle]. */
    fun side(angle: Float, radiusFront: Float, radiusBack: Float, yTop: Float, yBottom: Float, cam: Camera): Quad =
        Quad(
            project(angle, radiusFront, yTop, cam),
            project(angle, radiusBack, yTop, cam),
            project(angle, radiusBack, yBottom, cam),
            project(angle, radiusFront, yBottom, cam)
        )

    /** Screen angle for a horizontal offset from the centre of the screen. */
    fun angleForScreenX(x: Float, cam: Camera): Float = atan((x - cam.centerX) / cam.focal)

    /** Screen x of the middle of the visible arc. */
    fun halfVisibleAngle(width: Float, cam: Camera): Float =
        atan((width / 2f) / cam.focal)

    /** Point-in-quad test, used for tapping a spine. */
    fun contains(quad: Quad, x: Float, y: Float): Boolean {
        if (x < quad.left() - 2f || x > quad.right() + 2f) return false
        if (y < quad.top() - 2f || y > quad.bottom() + 2f) return false
        val pts = quad.points()
        var inside = false
        var j = pts.lastIndex
        for (i in pts.indices) {
            val pi = pts[i]
            val pj = pts[j]
            if ((pi.y > y) != (pj.y > y)) {
                val crossX = (pj.x - pi.x) * (y - pi.y) / (pj.y - pi.y) + pi.x
                if (x < crossX) inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * Start angle of each book, given their widths in world units, the gap between them and
     * the radius they stand at. Books are laid along the arc, so a wide book takes up more
     * of the curve than a narrow one.
     */
    fun startAngles(widths: FloatArray, gap: Float, radius: Float): FloatArray {
        val out = FloatArray(widths.size)
        var angle = 0f
        for (i in widths.indices) {
            out[i] = angle
            angle += (widths[i] + gap) / radius
        }
        return out
    }

    /** Total arc the whole row occupies, in radians. */
    fun totalAngle(widths: FloatArray, gap: Float, radius: Float): Float {
        if (widths.isEmpty()) return 0f
        var angle = 0f
        for (w in widths) angle += (w + gap) / radius
        return angle - gap / radius
    }

    /**
     * How much light a face catches. Faces turned away from the viewer sit in shadow, which
     * is what gives the row its round, lit-from-the-front feel.
     */
    fun facing(a0: Float, a1: Float): Float = cos((a0 + a1) / 2f)

    /** Diffuse shading for a vertical face at [facing] (-1..1). */
    fun shadeVertical(facing: Float): Float {
        val f = facing.coerceIn(-1f, 1f)
        return (0.62f + 0.38f * f).coerceIn(0.42f, 1f)
    }

    /** Diffuse shading for an upward face. */
    fun shadeUpward(facing: Float): Float = (0.92f - 0.22f * abs(facing)).coerceIn(0.55f, 1f)

    /** True when the wheel/scroll is short enough that the row should sit centred. */
    fun fitsOnScreen(widths: FloatArray, gap: Float, radius: Float, halfVisible: Float): Boolean {
        val total = totalAngle(widths, gap, radius)
        return total <= halfVisible * 2f * 0.98f
    }
}
