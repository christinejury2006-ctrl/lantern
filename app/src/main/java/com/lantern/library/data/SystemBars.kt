package com.lantern.library.data

/**
 * How the bottom navigation bar and the system's own bar share the bottom of the screen.
 *
 * ## The bug this describes
 *
 * The bar was placed at `BottomCenter` with no idea how much room Android's gesture bar or
 * three-button navigation needs, and the window's own edge-to-edge state changed depending on
 * where the reader had been in the app (the reader used to switch the window to edge-to-edge and
 * never switch it back). So the same bar was sometimes inset twice and sometimes not at all.
 *
 * ## The model now
 *
 * One owner: the **window**. The activity keeps the default fitting behaviour, so Android insets
 * the whole app by exactly the height of the bar the device is showing — a thin gesture pill or a
 * tall three-button strip — and that is respected in both modes without a single hardcoded screen
 * offset.
 *
 * The Compose bar still asks for its own inset padding, because that is the correct thing to do if
 * the window is ever edge-to-edge, and it is exactly zero when the window has already been inset.
 * Neither path can therefore apply the inset twice.
 *
 * The numbers below are only about how much *visual* room the app's own bar needs, and how much
 * bottom padding a scrolling list needs so its last row is not hidden behind it. They are plain
 * Kotlin so the relationship (content padding must clear the bar) can be asserted in a test rather
 * than hoped for in three screens that each picked their own number.
 */
object SystemBars {

    /**
     * The glass bar's own height: a 21 dp icon, a 10 sp label, and the paddings around them.
     *
     * Measured from `NookBottomBar`: 10 dp of outer vertical padding on each side, 10 dp inside the
     * glass, 21 dp icon, 8 dp between icon and label, and a 10 sp label. Rounded up, so a modest
     * font-scale change cannot leave a list's last row peeking under the bar.
     */
    const val BAR_CONTENT_DP = 86

    /** The visual gap between the bar and whatever is below it (system bar or screen edge). */
    const val BAR_GAP_DP = 10

    /** How much clear space a scrolling list leaves under its last row. */
    const val CONTENT_CLEARANCE_DP = 24

    /**
     * Bottom padding for a scrollable screen's content.
     *
     * A list that ends exactly at the bar's top edge looks like it has been cut off, and on a long
     * list the reader cannot tell whether they reached the end. Bar + gap + clearance guarantees
     * the last row can be scrolled fully above the bar.
     */
    const val CONTENT_BOTTOM_DP = BAR_CONTENT_DP + BAR_GAP_DP + CONTENT_CLEARANCE_DP

    /**
     * Whether the app's own bar must add the system navigation inset itself.
     *
     * True only when the window is edge-to-edge and therefore has not consumed the inset. When the
     * window fits system windows, the inset reported to Compose is already zero and this is false,
     * which is what stops the padding being applied twice.
     */
    fun barAppliesOwnInset(navigationInsetPx: Int): Boolean = navigationInsetPx > 0
}
