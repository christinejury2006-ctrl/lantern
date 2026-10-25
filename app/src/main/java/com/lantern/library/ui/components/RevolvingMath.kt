package com.lantern.library.ui.components

import kotlin.math.abs

/**
 * The maths that turns "where a spine sits on screen" into its pose.
 *
 * Kept separate from the composable so it can be unit tested: the tilt and the arc are the
 * whole illusion, and they are easy to get subtly wrong (a sign flip makes the shelf curve the
 * wrong way; a missing clamp flips a spine inside out at the edges).
 *
 * A spine's [fraction] is its position relative to the centre of the viewport:
 * `0` is dead centre, `+1` is one half-viewport to the right, `-1` one half to the left.
 */
object RevolvingMath {

    /** How far a spine tilts at the edge of the screen, in degrees. */
    const val MAX_ROTATION = 35f

    /**
     * Camera distance as a multiple of display density.
     *
     * Compose's default (8x) is shallow enough that a 35 degree turn looks fish-eyed. Twelve
     * gives a long lens: spines still visibly turn, but the perspective stays believable and
     * type on the spines does not smear.
     */
    const val CAMERA_DISTANCE_FACTOR = 12f

    /**
     * Where [itemCentre] sits relative to [viewportCentre], as -1..1.
     *
     * Clamped because an item scrolled just off screen still reports a position, and an
     * unclamped value would tilt it past the intended limit.
     */
    fun offsetFraction(itemCentre: Float, viewportCentre: Float, viewportWidth: Float): Float {
        if (viewportWidth <= 0f) return 0f
        val half = viewportWidth / 2f
        val raw = (itemCentre - viewportCentre) / half
        if (raw.isNaN()) return 0f
        return raw.coerceIn(-1f, 1f)
    }

    /**
     * Tilt for a spine at [fraction], in degrees.
     *
     * Negative on the right on purpose. A positive rotation about Y would swing an item's
     * right edge *toward* the viewer, which reads as a convex fan pointing at you. The shelf
     * has to curve away - right-hand spines turning their right edge back, left-hand spines
     * mirrored - or the whole row looks inside out.
     */
    fun rotationY(fraction: Float, maxRotation: Float = MAX_ROTATION): Float {
        val f = fraction.coerceIn(-1f, 1f)
        return -f * maxRotation
    }

    /**
     * How far a spine drops below the centre of the arch, in pixels.
     *
     * Squared rather than linear so the middle of the shelf stays flat and only the edges fall
     * away; a linear drop pivots every book and the row reads as a V.
     */
    fun arcDrop(fraction: Float, dropPx: Float): Float {
        val f = fraction.coerceIn(-1f, 1f)
        return f * f * dropPx
    }

    /** Slight shrink toward the edges, so the ends sit back against the room. */
    fun scale(fraction: Float, amount: Float = 0.06f): Float {
        val f = abs(fraction.coerceIn(-1f, 1f))
        return 1f - amount * f
    }

    /** Fade toward the edges so books leave the shelf instead of being cut off. */
    fun alpha(fraction: Float, minimum: Float = 0.55f): Float {
        val f = abs(fraction.coerceIn(-1f, 1f))
        return (1f - (1f - minimum) * f).coerceIn(minimum, 1f)
    }

    /** Camera distance in pixels for a given display density. */
    fun cameraDistance(density: Float): Float = CAMERA_DISTANCE_FACTOR * density

    /**
     * The index nearest the centre of the viewport given each item's distance in pixels, or
     * -1 when the list is empty. Used to decide which book the shelf is "offering".
     */
    fun nearestIndex(distances: List<Float>): Int {
        if (distances.isEmpty()) return -1
        var best = 0
        var bestDistance = abs(distances[0])
        for (i in 1 until distances.size) {
            val d = abs(distances[i])
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }
}
