package com.lantern.library.reader

/**
 * Which two pages a turning leaf is showing, and how far round it has gone.
 *
 * This is deliberately a plain data class computed by a plain function: the crash that brought the
 * reader down happened inside the composition, and composition is the worst possible place to keep
 * arithmetic that can go out of range. Everything that decides *which page is drawn* now happens
 * here, before a single composable runs, and every index it hands out is clamped against the page
 * count that exists at that moment.
 *
 * Two rules matter:
 *
 * 1. **An index is never out of range**, whatever combination of a shrinking page list, a font
 *    change, a restored position and a drag in flight arrives in the same frame.
 * 2. **A turn that has nowhere to go does not start.** Dragging forward off the last page, or back
 *    off the first, yields a window that is not drawing a curl at all, so the pager composes plain
 *    pages instead of animating a sheet over content that does not exist.
 */
data class LeafWindow(
    /** Whether a curl is in progress and should be drawn. */
    val drawing: Boolean,
    /** 0 = the sheet lies flat over the whole page; 1 = it has gone entirely. */
    val curl: Float,
    /** True when the sheet is being turned forward (next page is revealed under it). */
    val forward: Boolean,
    /** The page on the sheet: its front faces the reader, its back is the mirror image. */
    val sheetPage: Int,
    /** The page underneath, i.e. what the reader will be looking at when the turn finishes. */
    val basePage: Int,
    /** The clamped page count this window was computed against (always at least 1). */
    val count: Int
)

object LeafWindowMath {
    /** Below this the sheet is flat enough to draw as a plain page. */
    const val IDLE_EPSILON = 0.0005f

    /**
     * @param pageCount number of pages that exist *now*; 0 is tolerated and treated as 1.
     * @param page the reader's page, which may be stale after a re-pagination.
     * @param turn the raw drag/animation value in -1..1, where positive means forwards.
     */
    fun window(pageCount: Int, page: Int, turn: Float): LeafWindow {
        val count = if (pageCount > 0) pageCount else 1
        val current = page.coerceIn(0, count - 1)
        val t = if (turn.isNaN()) 0f else turn.coerceIn(-1f, 1f)
        val forward = t >= 0f
        val canForward = current + 1 <= count - 1
        val canBackward = current - 1 >= 0
        val allowed = if (forward) canForward else canBackward
        if (!allowed) {
            // No sheet to turn: the reader stays on the page they are on.
            return LeafWindow(
                drawing = false,
                curl = 0f,
                forward = true,
                sheetPage = current,
                basePage = current,
                count = count
            )
        }
        val dragged = if (t < 0f) -t else t
        val curl = if (forward) dragged else 1f - dragged
        val sheet = if (forward) current else current - 1
        val base = if (forward) current + 1 else current
        val drawing = curl > IDLE_EPSILON && curl < 1f - IDLE_EPSILON
        return LeafWindow(
            drawing = drawing,
            curl = curl.coerceIn(0f, 1f),
            forward = forward,
            sheetPage = sheet.coerceIn(0, count - 1),
            basePage = base.coerceIn(0, count - 1),
            count = count
        )
    }

    /** The page a completed turn lands on, or `null` if that direction has no page. */
    fun targetAfterTurn(count: Int, page: Int, forward: Boolean): Int? {
        val safeCount = if (count > 0) count else 1
        val current = page.coerceIn(0, safeCount - 1)
        val target = if (forward) current + 1 else current - 1
        return if (target in 0 until safeCount) target else null
    }

    /** Clamps a remembered page index into a page list that may have changed size. */
    fun clampPage(page: Int, pageCount: Int): Int =
        page.coerceIn(0, (if (pageCount > 0) pageCount else 1) - 1)
}
