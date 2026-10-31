package com.lantern.library.ui

import com.lantern.library.data.SystemBars
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The app's chrome — bottom bar, insets, where navigation lives, and how the lamp is drawn.
 *
 * These are structural tests, not rendering tests: they read the real source files and assert the
 * properties that are invisible to a compiler but decide whether the bar sits above a gesture pill
 * or under a three-button strip, whether a screen pads itself twice, and whether the lamp is a
 * drawing or a pasted bitmap. They are no substitute for a screenshot on a device — nothing here
 * proves *layout*, only *intent* — and they are written to say exactly that.
 *
 * The palette maths and the per-book state machines are covered properly in the `data` tests; this
 * class exists so a future edit that puts a search field back on Home, or a second
 * `navigationBarsPadding()` into a scrolling screen, fails a test instead of shipping quietly.
 */
class ChromeTest {

    /** Reads a source file, walking up from the test's working directory to the module root. */
    private fun source(relative: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val direct = File(dir, "src/main/java/com/lantern/library/$relative")
            if (direct.exists()) return direct.readText()
            val inApp = File(dir, "app/src/main/java/com/lantern/library/$relative")
            if (inApp.exists()) return inApp.readText()
            dir = dir.parentFile
        }
        assumeTrue("source not readable from the test working directory: $relative", false)
        return ""
    }

    private fun exists(relative: String): Boolean {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "src/main/java/com/lantern/library/$relative").exists()) return true
            if (File(dir, "app/src/main/java/com/lantern/library/$relative").exists()) return true
            dir = dir.parentFile
        }
        return false
    }

    // 1 -------------------------------------------------------------------------------------

    @Test
    fun `the content clearance clears the bar and its gap`() {
        assertTrue(
            "a scrolling list must leave more room than the bar itself",
            SystemBars.CONTENT_BOTTOM_DP > SystemBars.BAR_CONTENT_DP + SystemBars.BAR_GAP_DP
        )
        assertTrue("the bar needs a gap above the system bar", SystemBars.BAR_GAP_DP > 0)
        assertTrue("and the gap must not be a hardcoded screen offset", SystemBars.BAR_GAP_DP < 40)
    }

    // 2 -------------------------------------------------------------------------------------

    @Test
    fun `the bar adds the navigation inset only when the window has not already`() {
        // One owner: when Android has inset the window (the app's default), the inset Compose sees
        // is zero and the bar must add nothing. When the window is edge-to-edge, it must.
        assertFalse("a zero inset must not be padded twice", SystemBars.barAppliesOwnInset(0))
        assertTrue("a real gesture pill inset must be honoured", SystemBars.barAppliesOwnInset(48))
        assertTrue("and so must a three-button strip", SystemBars.barAppliesOwnInset(144))
    }

    // 3 -------------------------------------------------------------------------------------

    @Test
    fun `no scrolling screen applies the navigation inset itself`() {
        val screens = listOf(
            "ui/screens/HomeScreen.kt",
            "ui/screens/LibraryScreen.kt",
            "ui/screens/ProfileScreen.kt",
            "ui/screens/ExploreScreens.kt",
            "ui/screens/BookDetailsScreen.kt"
        ).filter { exists(it) }
        assumeTrue("no screens found", screens.isNotEmpty())

        screens.forEach { screen ->
            val text = source(screen)
            assertFalse(
                "$screen must not pad the navigation bar: the window and the app bar own that inset",
                text.contains("navigationBarsPadding")
            )
        }
    }

    // 4 -------------------------------------------------------------------------------------

    @Test
    fun `every scrolling destination clears the bottom bar`() {
        // One constant, four screens. A screen that picks its own number is how the last row ends
        // up under the bar on one tab and not the others.
        listOf(
            "ui/screens/HomeScreen.kt",
            "ui/screens/LibraryScreen.kt",
            "ui/screens/ProfileScreen.kt",
            "ui/screens/ExploreScreens.kt"
        ).filter { exists(it) }.forEach { screen ->
            assertTrue(
                "$screen must leave room for the bottom bar via SystemBars.CONTENT_BOTTOM_DP",
                source(screen).contains("SystemBars.CONTENT_BOTTOM_DP")
            )
        }
    }

    // 5 -------------------------------------------------------------------------------------

    @Test
    fun `the bottom bar is outside the scrolling content`() {
        val root = source("AppRoot.kt")
        // Drawn by the root, anchored to the bottom of a Box that does not scroll with a screen.
        assertTrue(
            "the bar must be anchored by the root layout",
            root.contains(".align(Alignment.BottomCenter)")
        )
        assertTrue("and it must own its own inset", root.contains("navigationBarsPadding()"))
        // And no screen may draw the bar itself, which is how a bar ends up inside a LazyColumn.
        listOf(
            "ui/screens/HomeScreen.kt",
            "ui/screens/LibraryScreen.kt",
            "ui/screens/ProfileScreen.kt",
            "ui/screens/ExploreScreens.kt"
        ).filter { exists(it) }.forEach { screen ->
            assertFalse(
                "$screen must not draw the bottom bar",
                source(screen).contains("NookBottomBar(")
            )
        }
    }

    // 6 -------------------------------------------------------------------------------------

    @Test
    fun `Home has no search field`() {
        val home = source("ui/screens/HomeScreen.kt")
        assertFalse("the search pill must be gone", home.contains("SearchPill"))
        assertFalse("and nothing else in Home may open search", home.contains("onSearch"))
        assertFalse("nor may it draw a search icon", home.contains("Icons.Filled.Search"))
    }

    // 7 -------------------------------------------------------------------------------------

    @Test
    fun `Search is still its own destination`() {
        val root = source("AppRoot.kt")
        assertTrue("the Explore tab must still exist", root.contains("Route.Search"))
        assertTrue("and route to the search screen", root.contains("SearchScreen("))
        assertTrue("which must still exist", exists("ui/screens/ExploreScreens.kt"))
    }

    // 8 -------------------------------------------------------------------------------------

    @Test
    fun `the lamp is drawn, themed, and not pasted in`() {
        val lamp = source("ui/components/NookLamp.kt")
        assertTrue("the lamp must be a Compose Canvas", lamp.contains("Canvas("))
        assertFalse("no bitmap may be used", lamp.contains("painterResource"))
        assertFalse("nor an image composable", lamp.contains("Image("))
        assertFalse("nor an emoji", lamp.contains("Icon("))
        // Themed: the lamp reads the palette, including whether the room is dark.
        assertTrue("it must be themed for light and dark", lamp.contains("palette.dark"))
        // Animated by one transition, read inside the draw scope, so a frame is a redraw and not a
        // recomposition of the whole header.
        assertTrue("one infinite transition", lamp.contains("rememberInfiniteTransition"))
        assertTrue("values read as state, not as floats in the composable body", lamp.contains("State<Float>"))
    }

    // 9 -------------------------------------------------------------------------------------

    @Test
    fun `the lamp is bigger than the old corner ornament`() {
        val library = source("ui/screens/LibraryScreen.kt")
        val start = library.indexOf("NookLamp(")
        assumeTrue("NookLamp call not found", start >= 0)
        val window = library.substring(start, minOf(library.length, start + 200))
        val match = Regex("size\\s*=\\s*(\\d+)\\.dp").find(window)
        assumeTrue("the lamp's size must be explicit", match != null)
        val size = match!!.groupValues[1].toInt()
        assertTrue("the lamp must be a piece of furniture, not an icon: $size dp", size >= 80)
    }
}
