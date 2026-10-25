package com.lantern.library.shelf3d

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * The geometry of a cylindrical revolving bookcase.
 *
 * Books stand on the OUTSIDE of a drum whose axis runs vertically in front of the reader, so
 * their spines face outward, towards the reader. Turning the drum sweeps them past.
 *
 *        reader
 *          |
 *          v            (axis at z = -AXIS_DISTANCE)
 *      (  o  )          books at drum radius DRUM_RADIUS
 *
 * Kept free of Android and Filament types so the trigonometry — the part that is easy to get
 * subtly wrong and impossible to debug on a phone — is unit tested on the JVM.
 *
 * Conventions, all in radians and metres:
 * - The camera sits at the origin looking down -z.
 * - `angle` is measured around the drum's axis. 0 is the book nearest the reader; positive
 *   angles swing to the reader's right.
 * - Axes: x right, y up, z towards the reader.
 */
object CylinderMath {

    /**
     * The smallest drum the shelf is ever built on, and the radius used when nothing says
     * otherwise.
     *
     * The scene is sized from one requirement: a book should fill a good part of a phone held in
     * portrait. With the nearest spine [SPINE_DISTANCE] from the camera, a 0.31 m book covers
     * 41% of the screen height.
     *
     * The real radius comes from [radiusFor], which grows the drum to fit the library.
     */
    const val DRUM_RADIUS = 0.60f

    /**
     * How much room each book gets on the drum, as a multiple of its spine width.
     *
     * 1.15 leaves a 15% gap: enough that spines read as separate books, close enough to look
     * like a packed shelf.
     */
    const val BOOK_GAP = 1.15f

    /** A book's centre height, just below the reader's eye line. */
    const val BOOK_Y = -0.05f

    /** Book dimensions in metres. */
    const val BOOK_WIDTH = 0.115f
    const val BOOK_HEIGHT = 0.31f
    const val BOOK_DEPTH = 0.18f

    /** Distance from the camera to the spine of the book at the front of the drum. */
    const val SPINE_DISTANCE = 0.72f

    /** How many spines the screen should show across, at most. */
    const val BOOKS_ACROSS = 14f

    /**
     * The lens the scene is composed for: a phone in portrait. Scene scale is worked out for
     * this lens and not for the live one, so the shelf does not rebuild itself when the phone
     * turns; a wider lens in landscape simply shows more of it.
     */
    val REFERENCE_HALF_FOV_H: Float = halfFovH(aspect = 690f / 1376f)

    /**
     * Distance from the camera to the spine of the front book, for a library of [count] books.
     *
     * This is the knob that decides how many spines fill the screen. The visible width at the
     * shelf is `2 * d * tan(halfFov)`, so putting `min(count, BOOKS_ACROSS)` books in that width
     * makes the shelf show a fixed number of spines however large the library is — three or four
     * for a handful of books, a full run of fourteen once the library is big.
     *
     * Never closer than [SPINE_DISTANCE], or a one-book library would fill the screen with a
     * single giant spine.
     */
    fun spineDistanceFor(count: Int): Float {
        if (count <= 0) return SPINE_DISTANCE
        val wanted = minOf(count.toFloat(), BOOKS_ACROSS) * BOOK_WIDTH * BOOK_GAP /
            (2f * tan(REFERENCE_HALF_FOV_H))
        return if (wanted > SPINE_DISTANCE) wanted else SPINE_DISTANCE
    }

    /**
     * How far in front of the reader the drum's axis sits, for a drum of [radius] holding the
     * default spine distance.
     */
    fun axisFor(radius: Float): Float = radius + SPINE_DISTANCE + BOOK_DEPTH / 2f

    /** The axis distance for a library of [count] books: radius plus its sight line. */
    fun axisForLibrary(count: Int): Float =
        radiusFor(count) + spineDistanceFor(count) + BOOK_DEPTH / 2f

    /** The axis distance for the smallest drum. */
    const val AXIS_DISTANCE = DRUM_RADIUS + SPINE_DISTANCE + BOOK_DEPTH / 2f

    /**
     * The drum radius a library of [count] books needs.
     *
     * Books spread evenly around a full circle get `360/count` degrees of drum each, while a
     * spine is [BOOK_WIDTH] wide; once the library is big enough those two collide and the
     * books interpenetrate. A hundred books on the smallest drum would each get 3.6 degrees
     * where they need 11, so they would overlap three deep. Growing the drum with the library
     * keeps every book's slot wider than the book, and because [axisFor] follows the radius,
     * the front book stays the same distance from the camera — so the shelf simply gets wider
     * and flatter as it fills, instead of the books getting smaller.
     */
    fun radiusFor(count: Int): Float {
        if (count <= 0) return DRUM_RADIUS
        val needed = count * BOOK_WIDTH * BOOK_GAP / TWO_PI
        return if (needed > DRUM_RADIUS) needed else DRUM_RADIUS
    }

    /** The camera's vertical field of view, in degrees. A phone-camera-like lens. */
    const val FOV_VERTICAL_DEGREES = 55f

    /**
     * The true horizon of a drum of [radius]: beyond it a book has turned past the drum's own
     * silhouette and the carcass hides it. `acos(R / axis)`.
     *
     * This is not cosmetic. Past the horizon a book's screen position stops moving outward and
     * starts coming back, so the angle-to-pixel mapping stops being one-to-one and hit testing
     * would select the wrong book. The visible arc is defined from it.
     */
    fun horizonDegrees(
        radius: Float = DRUM_RADIUS,
        axis: Float = axisFor(radius)
    ): Float = Math.toDegrees(kotlin.math.acos((radius / axis).toDouble())).toFloat()

    /**
     * Half-angle of the drum worth drawing: just inside the horizon, so every book drawn can
     * also be hit-tested unambiguously and none is half-cut by the edge.
     */
    fun visibleArcDegrees(
        radius: Float = DRUM_RADIUS,
        axis: Float = axisFor(radius)
    ): Float = horizonDegrees(radius, axis) - 3f

    /** The horizon of the smallest drum. */
    val HORIZON_DEGREES: Float = horizonDegrees(DRUM_RADIUS)

    /** The visible arc of the smallest drum. */
    val VISIBLE_ARC_DEGREES: Float = visibleArcDegrees(DRUM_RADIUS)

    /**
     * Half the horizontal field of view, in radians.
     *
     * This must be computed from the viewport, never assumed. A phone in portrait with a 55
     * degree vertical lens has only about 29 degrees of horizontal view, and every hit test and
     * every drag distance depends on knowing that exactly.
     */
    fun halfFovH(verticalFovDegrees: Float = FOV_VERTICAL_DEGREES, aspect: Float): Float =
        atan2(tan(Math.toRadians(verticalFovDegrees / 2.0).toFloat()) * aspect, 1f)

    /**
     * How fast the drum must turn for its surface to keep up with a finger, in drum radians per
     * radian of view angle.
     *
     * A book at the front sits [DRUM_RADIUS] from the axis and the camera is
     * `AXIS_DISTANCE - DRUM_RADIUS` from the axis, so a small turn dθ moves the book sideways
     * by `R dθ` while appearing to move by `(A - R) dθ`. Without this correction the surface
     * races the finger: with a hand-picked constant the drum turned about ten times too far for
     * a given swipe.
     */
    fun trackingGain(
        radius: Float = DRUM_RADIUS,
        axis: Float = axisFor(radius)
    ): Float = radius / (axis - radius)

    /** The angular width of one book's spine on a drum of [radius], in radians. */
    fun bookArcRadians(radius: Float = DRUM_RADIUS): Float = BOOK_WIDTH / radius

    private const val TWO_PI = (PI * 2.0).toFloat()

    /** Angle between neighbouring books when [count] books share the drum evenly. */
    fun angleStep(count: Int): Float = if (count <= 0) TWO_PI else TWO_PI / count

    /**
     * Angle of book [index] with the drum turned by [rotation].
     *
     * This is the whole of the infinite wrapping: rotation is unbounded, and each book's angle
     * is folded into a single turn, so the drum can be spun forever without drifting.
     */
    fun angleOf(index: Int, count: Int, rotation: Float): Float =
        wrapSigned(rotation + index * angleStep(count))

    /** Folds [angle] into (-PI, PI]. */
    fun wrapSigned(angle: Float): Float {
        if (angle.isNaN() || angle.isInfinite()) return 0f
        var a = angle % TWO_PI
        if (a <= -PI) a += TWO_PI
        if (a > PI) a -= TWO_PI
        return a
    }

    /** Folds [angle] into [0, 2PI). */
    fun wrapPositive(angle: Float): Float {
        val a = wrapSigned(angle)
        return if (a < 0f) a + TWO_PI else a
    }

    /** True when a book at [angle] is on the reader's side of the drum. */
    fun isVisible(angle: Float, arcDegrees: Float = VISIBLE_ARC_DEGREES): Boolean =
        abs(wrapSigned(angle)) <= Math.toRadians(arcDegrees.toDouble()).toFloat()

    /** x of a book on the drum. */
    fun positionX(angle: Float, radius: Float = DRUM_RADIUS): Float = radius * sin(angle)

    /** z of a book on the drum. Negative: in front of the reader. */
    fun positionZ(
        angle: Float,
        radius: Float = DRUM_RADIUS,
        axis: Float = axisFor(radius)
    ): Float = -axis + radius * cos(angle)

    /**
     * Rotation about y, in degrees, that makes a book's spine face outward.
     *
     * A book is a box whose spine is its local -z face. Rotating by (angle + 180) degrees
     * turns that face to point along the drum's outward normal (sin θ, 0, cos θ):
     *
     *     R_y(a) · (0,0,-1) = (-sin a, 0, -cos a),  with a = θ + π this is (sin θ, 0, cos θ)
     *
     * The half turn is why the spine ends up on the outside rather than pointing at the axis.
     */
    fun facingDegrees(angle: Float): Float = Math.toDegrees(angle.toDouble()).toFloat() + 180f

    /**
     * Horizontal angle from the camera axis at which a book at [angle] appears on screen.
     *
     * Exact, not an approximation, because hit testing compares the angle of a tap against
     * these: a book's apparent position depends on both its angle and its distance.
     */
    fun screenAngle(
        angle: Float,
        radius: Float = DRUM_RADIUS,
        axis: Float = axisFor(radius)
    ): Float {
        val x = positionX(angle, radius)
        val depth = -positionZ(angle, radius, axis)
        if (depth <= 0.01f) return atan2(x.coerceIn(-1f, 1f), 0.01f)
        return atan2(x, depth)
    }

    /**
     * Angle of the drum a tap at [screenX] points at, or null if it is outside the lens.
     */
    fun angleForScreenX(
        screenX: Float,
        viewWidth: Float,
        halfFovH: Float = halfFovH(aspect = 0.5f),
        radius: Float = DRUM_RADIUS,
        axis: Float = axisFor(radius)
    ): Float? {
        if (viewWidth <= 0f) return null
        val nx = (screenX / viewWidth - 0.5f) * 2f
        val screenAngle = atan2(nx * tan(halfFovH), 1f)
        // Strictly outside the lens: there is nothing there to touch. A tolerance here would
        // make a tap on the bezel select a book at the very edge of the shelf.
        if (abs(screenAngle) > halfFovH) return null
        // Invert screenAngle() by bisection. This is only valid inside the drum's horizon: up
        // to the tangent point the mapping increases, and after it the drum curves away and
        // screen positions come back inward. Bracket exactly the monotonic stretch.
        val limit = Math.toRadians(horizonDegrees(radius, axis).toDouble()).toFloat()
        var low = -limit
        var high = limit
        repeat(24) {
            val mid = (low + high) / 2f
            if (screenAngle(mid, radius, axis) < screenAngle) low = mid else high = mid
        }
        return (low + high) / 2f
    }

    /**
     * Whether a tap at drum angle [tapAngle] actually lands on book [index], rather than on the
     * bare shelf beside it.
     *
     * When books are spaced apart — a small library spread round a whole drum — most of the
     * shelf between them is empty. Without this, a tap anywhere on the wood would open whichever
     * book happened to be closest.
     */
    fun isTapOnBook(
        tapAngle: Float,
        index: Int,
        count: Int,
        rotation: Float,
        slop: Float = 1.25f,
        radius: Float = DRUM_RADIUS
    ): Boolean {
        if (count <= 0 || index < 0 || index >= count) return false
        val centre = angleOf(index, count, rotation)
        val off = abs(wrapSigned(tapAngle - centre))
        val halfBook = bookArcRadians(radius) / 2f * slop
        val halfSlot = angleStep(count) / 2f
        return off <= minOf(halfBook, halfSlot)
    }

    /** The book nearest [angle] on the drum, given the current [rotation]. */
    fun bookNearAngle(count: Int, rotation: Float, angle: Float): Int {
        if (count <= 0) return -1
        val step = angleStep(count)
        val raw = (angle - rotation) / step
        val nearest = raw.roundToInt()
        return ((nearest % count) + count) % count
    }

    /**
     * Rotation that turns book [index] to the front, taking the shorter way round.
     *
     * Whichever direction is nearer, so a book just behind the front does not spin the whole
     * drum the long way to reach it.
     */
    fun rotationToReveal(index: Int, count: Int, rotation: Float): Float {
        if (count <= 0) return rotation
        val target = -index * angleStep(count)
        var delta = wrapSigned(target - rotation)
        if (delta > PI) delta -= TWO_PI
        if (delta < -PI) delta += TWO_PI
        return rotation + delta
    }

    /** The book nearest the front of the drum. */
    fun frontIndex(count: Int, rotation: Float): Int = bookNearAngle(count, rotation, 0f)

    /** Shortest signed distance from book [index] to the front, in radians. */
    fun offsetFromFront(index: Int, count: Int, rotation: Float): Float =
        wrapSigned(rotation + index * angleStep(count))

    /** Indices within the visible arc, nearest the front first. */
    fun visibleIndices(
        count: Int,
        rotation: Float,
        arcDegrees: Float = VISIBLE_ARC_DEGREES
    ): List<Int> {
        if (count <= 0) return emptyList()
        val front = frontIndex(count, rotation)
        val half = Math.toRadians(arcDegrees.toDouble()).toFloat() / angleStep(count)
        val span = kotlin.math.ceil(half).toInt().coerceAtMost(count)
        val out = ArrayList<Int>(span * 2 + 1)
        for (offset in -span..span) {
            val index = ((front + offset) % count + count) % count
            if (isVisible(angleOf(index, count, rotation), arcDegrees) && index !in out) {
                out.add(index)
            }
        }
        // nearest the front first, so the closest books are the ones kept if a budget applies
        out.sortBy { abs(offsetFromFront(it, count, rotation)) }
        return out
    }
}
