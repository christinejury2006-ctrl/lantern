package com.lantern.library.shelf3d

import kotlin.math.abs
import kotlin.math.sign

/**
 * Turns horizontal drags into drum rotation, with momentum and settling.
 *
 * Plain Kotlin with a `tick(dt)` clock rather than coroutines or Animatable, for three
 * reasons: it runs on whichever thread the renderer calls it from, it can be unit tested with
 * a synthetic clock, and it lets the renderer know precisely when motion has finished — which
 * is what allows the frame loop to stop completely instead of spinning at 60 fps on a shelf
 * nobody is touching.
 *
 * All angles are radians. Drag distances are pixels.
 */
class CylinderRotationController(
    /** Angle turned per pixel dragged. Tuned so a full-screen swipe crosses several books. */
    private val radiansPerPixel: Float = 0.006f,
    /** Fraction of velocity kept per second: how quickly a spun drum coasts to a stop. */
    private val frictionPerSecond: Float = 0.06f,
    /** Angular speed below which a coasting drum is considered stopped. */
    private val stopThreshold: Float = 0.02f,
    /**
     * Ceiling on angular speed, in radians per second.
     *
     * A hard flick can hand over tens of radians per second, which looks like a glitch rather
     * than a throw, and after a stall it would jump the drum most of the way round in a single
     * frame. Eight radians a second is about one and a quarter books, which reads as a brisk
     * spin and stays legible.
     */
    private val maxVelocity: Float = 8f,
    /** Whether a released drum snaps the nearest book to the front. */
    private val snapToBooks: Boolean = true,
    /** Rate at which the drum eases onto a book. Higher settles faster. */
    private val snapRate: Float = 14f,
    /** Hard cap on how far the drum may move in one frame while settling, in radians. */
    private val maxSnapStep: Float = 0.12f
) {

    /** Current drum rotation in radians. Unbounded; the geometry wraps it. */
    var rotation: Float = 0f
        private set

    /** Angular velocity in radians per second. */
    var velocity: Float = 0f
        private set

    /** True while the drum is moving under its own momentum or settling onto a book. */
    var isMoving: Boolean = false
        private set

    private var totalBooks: Int = 0
    private var snapTarget: Float? = null
    private var dragging = false

    /** Books on the drum. Snap targets depend on this, so it is kept in step with the data. */
    fun setBookCount(count: Int) {
        if (count == totalBooks) return
        totalBooks = count
        snapTarget = null
        velocity = 0f
        isMoving = false
    }

    /** A drag by [dx] pixels. Returns true if the drum moved, i.e. a frame is needed. */
    fun onDrag(dx: Float): Boolean {
        if (dx.isNaN() || dx == 0f) return false
        dragging = true
        // A finger moving right rolls the drum right, which brings the book on the left into
        // view: the surface moves with the finger, so the drum turns the opposite way.
        rotation -= dx * radiansPerPixel
        rotation = CylinderMath.wrapSigned(rotation)
        velocity = 0f
        snapTarget = null
        isMoving = false
        return true
    }

    /**
     * The finger lifted, having been moving at [velocityPxPerSecond].
     *
     * A real throw keeps coasting; a gentle release settles onto the nearest book instead.
     */
    fun onRelease(velocityPxPerSecond: Float) {
        dragging = false
        velocity = (-velocityPxPerSecond * radiansPerPixel).coerceIn(-maxVelocity, maxVelocity)
        // A tiny flick, or a release after holding still, should not throw the drum.
        if (abs(velocity) < 0.35f) velocity = 0f
        isMoving = velocity != 0f || snapToBooks
        snapTarget = null
    }

    /** Advances the drum by [dt] seconds. Returns true while it is still moving. */
    fun tick(dt: Float): Boolean {
        if (!isMoving) return false
        // Clamped hard: if the process was suspended, the elapsed time is meaningless and
        // integrating it would teleport the drum.
        val step = dt.coerceIn(0f, 1f / 30f)

        if (abs(velocity) > stopThreshold) {
            // Coasting: exponential decay, so it slows like a heavy object rather than a line.
            rotation = CylinderMath.wrapSigned(rotation + velocity * step)
            velocity *= Math.pow(frictionPerSecond.toDouble(), step.toDouble()).toFloat()
            return true
        }

        velocity = 0f

        if (!snapToBooks || totalBooks <= 0) {
            isMoving = false
            return false
        }

        // Settle onto the nearest book, the way a physical rack drops into its detents.
        val target = snapTarget ?: nearestBookRotation().also { snapTarget = it }
        var delta = CylinderMath.wrapSigned(target - rotation)
        if (abs(delta) < 0.002f) {
            rotation = CylinderMath.wrapSigned(target)
            snapTarget = null
            isMoving = false
            return false
        }
        // Ease onto the book. Exponential smoothing, not a fixed fraction: the drum moves a
        // real distance per second rather than per frame, so it settles identically at 60 or
        // 120 Hz. The step is also capped, so a big correction glides instead of teleporting.
        val ease = 1f - kotlin.math.exp(-snapRate * step)
        val move = (delta * ease).coerceIn(-maxSnapStep, maxSnapStep)
        rotation = CylinderMath.wrapSigned(rotation + move)
        return true
    }

    /** Jumps straight to a book, for selecting one from a list or on first layout. */
    fun revealBook(index: Int, animate: Boolean = true) {
        if (totalBooks <= 0) return
        val target = CylinderMath.rotationToReveal(index, totalBooks, rotation)
        if (animate) {
            snapTarget = target
            velocity = 0f
            isMoving = true
        } else {
            rotation = CylinderMath.wrapSigned(target)
            velocity = 0f
            snapTarget = null
            isMoving = false
        }
    }

    /** True while a finger is down. The renderer keeps drawing throughout. */
    fun isDragging(): Boolean = dragging

    /** Cancels any motion, e.g. when the surface goes away. */
    fun reset() {
        velocity = 0f
        snapTarget = null
        dragging = false
        isMoving = false
    }

    /**
     * Rotation that would put the nearest book at the front.
     *
     * Taken from the current position rather than recomputed from zero, so the shelf settles
     * onto the nearest book instead of jumping back to the start.
     */
    private fun nearestBookRotation(): Float {
        val step = CylinderMath.angleStep(totalBooks)
        val indexAtFront = -rotation / step
        val nearest = Math.round(indexAtFront).toFloat()
        // Rotating to `-nearest * step` lines that book up with the front; take the short way.
        val candidate = CylinderMath.wrapSigned(-nearest * step)
        val current = CylinderMath.wrapSigned(rotation)
        var delta = CylinderMath.wrapSigned(candidate - current)
        if (delta > Math.PI.toFloat()) delta -= (Math.PI * 2f).toFloat()
        if (delta < -Math.PI.toFloat()) delta += (Math.PI * 2f).toFloat()
        // Keep the total continuous: add the correction to where we already are.
        return current + delta
    }

    /** Direction of travel, for tests and for shading an edge glow. */
    fun direction(): Float = sign(velocity)
}
