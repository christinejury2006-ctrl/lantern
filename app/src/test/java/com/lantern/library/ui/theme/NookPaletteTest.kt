package com.lantern.library.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.lantern.library.data.ReaderTheme
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The palette is a set of contrast promises, and this is the test that keeps them.
 *
 * The Reading Nook has two voices — cream and navy — and every screen reads text out of the same
 * three tokens. A hand-picked colour that looks fine on the designer's monitor can leave `mute`
 * text at 2:1 against the backdrop on a phone in daylight, which does not look like a design
 * problem, it looks like a broken app. So the promises are asserted here: text tokens must clear
 * a readable ratio against the backdrop they sit on, in both voices.
 */
class NookPaletteTest {

    /** WCAG relative-luminance contrast ratio, 1.0 (identical) to 21.0 (black on white). */
    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun palettes() = listOf(
        "light" to nookPalette(ReaderTheme.LIGHT),
        "dark" to nookPalette(ReaderTheme.DARK)
    )

    @Test
    fun `body text is readable on the backdrop in both voices`() {
        for ((name, palette) in palettes()) {
            for (backdrop in palette.backdrop) {
                val ratio = contrast(palette.ink, backdrop)
                assertTrue(
                    "$name: ink on the backdrop is only ${"%.1f".format(ratio)}:1",
                    ratio >= 7f
                )
            }
        }
    }

    @Test
    fun `secondary text clears the large-text minimum in both voices`() {
        for ((name, palette) in palettes()) {
            for (backdrop in palette.backdrop) {
                val ratio = contrast(palette.mute, backdrop)
                assertTrue(
                    "$name: mute on the backdrop is only ${"%.1f".format(ratio)}:1",
                    ratio >= 3f
                )
            }
        }
    }

    @Test
    fun `a filled button's label is readable on its fill`() {
        for ((name, palette) in palettes()) {
            val ratio = contrast(palette.accentInk, palette.accent)
            assertTrue("$name: accentInk on accent is only ${"%.1f".format(ratio)}:1", ratio >= 4.5f)
        }
    }

    @Test
    fun `the accent works as a button fill and as words on the page`() {
        // It is used both ways: a filled primary button with accentInk on top, and small
        // accent-coloured labels ("Import a book", "Refresh") sitting on the backdrop.
        for ((name, palette) in palettes()) {
            for (backdrop in palette.backdrop) {
                val ratio = contrast(palette.accent, backdrop)
                assertTrue("$name: accent on the backdrop is ${"%.1f".format(ratio)}:1", ratio >= 4.5f)
            }
        }
    }

    @Test
    fun `the reader's page is readable and is not the room`() {
        // The reader paints its page from the palette, and a page is paper, not backdrop. Two
        // things have to hold: the type is comfortable on it, and it is visibly a page rather
        // than the rose of the room showing through.
        for ((name, palette) in palettes()) {
            val ratio = contrast(palette.ink, palette.page)
            assertTrue("$name: type on the page is only ${"%.1f".format(ratio)}:1", ratio >= 7f)
        }
        // In daylight the page is paper lying on the rose room, so it is the brighter of the two;
        // at night it merges into the room, which is what a page at 2 a.m. should do.
        val light = nookPalette(ReaderTheme.LIGHT)
        assertTrue(
            "the day page is darker than the room behind it",
            light.page.luminance() >= light.backdrop.first().luminance()
        )
    }

    @Test
    fun `a night page is dark and a day page is light`() {
        val light = nookPalette(ReaderTheme.LIGHT).page
        val dark = nookPalette(ReaderTheme.DARK).page
        assertTrue("the day page is not light", light.luminance() > 0.85f)
        assertTrue("the night page is not dark", dark.luminance() < 0.05f)
    }

    @Test
    fun `the two voices are actually different`() {
        // A regression that made both palettes the same would pass every contrast check above.
        val light = nookPalette(ReaderTheme.LIGHT)
        val dark = nookPalette(ReaderTheme.DARK)
        assertTrue(!light.dark && dark.dark)
        assertTrue("light backdrop is too dark", light.backdrop.first().luminance() > 0.7f)
        assertTrue("dark backdrop is too light", dark.backdrop.first().luminance() < 0.1f)
        assertTrue("ink is not inverted", light.ink.luminance() < dark.ink.luminance())
    }

    @Test
    fun `every accent in the aurora is visible against its own backdrop`() {
        for ((name, palette) in palettes()) {
            palette.aurora.forEachIndexed { i, color ->
                val ratio = contrast(color, palette.backdrop.first())
                        // Decoration, not text: enough presence to read as light, not a contrast target.
                assertTrue(
                    "$name: aurora[$i] vanishes into the backdrop (${"%.1f".format(ratio)}:1)",
                    ratio >= 1.25f
                )
            }
        }
    }
}
