package com.lantern.library.ui

import com.lantern.library.data.SystemBars
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The bottom bar belongs to the layout, not to the content.
 *
 * These are structural tests: they read the real source and assert the shape that makes the bar
 * impossible to move. They do not render anything and they do not prove pixel positions — that
 * needs a device — but they do fail if the bar goes back to being an overlay inside the same
 * stacking box as a scrolling screen, which is the bug this round is about, and if a second owner
 * of the navigation inset appears.
 */
class ShellNavigationTest {

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

    private fun shell() = source("AppRoot.kt")

    /** The text of the `LanternTheme { ... }` block: the app's whole chrome lives in there. */
    private fun shellChrome(): String {
        val text = shell()
        val start = text.indexOf("LanternTheme(theme) {")
        assertTrue("the shell must exist", start >= 0)
        return text.substring(start)
    }

    // 1 -----------------------------------------------------------------------------------------

    @Test
    fun `1 the bar is a layout sibling of the content, not an overlay on it`() {
        val chrome = shellChrome()
        assertTrue(
            "the shell must be a Column: content and bar in separate regions",
            chrome.contains("Column(Modifier.fillMaxSize())")
        )
        assertTrue(
            "the scrolling screen must live in a weighted content region",
            chrome.contains(".weight(1f)")
        )
        assertFalse(
            "the bar must not be positioned over the content with align(BottomCenter)",
            chrome.contains("align(Alignment.BottomCenter)")
        )
        // The bar is drawn inside the Column, after the content region closes.
        val barAt = chrome.indexOf("BottomBar(")
        val columnAt = chrome.indexOf("Column(Modifier.fillMaxSize())")
        assertTrue("the bar must be declared inside the shell", barAt > columnAt)
        assertFalse(
            "no scrollable container may hold the bar",
            chrome.substring(columnAt, minOf(barAt, chrome.length)).contains("LazyColumn")
        )
    }

    @Test
    fun `1 the bar cannot scroll because nothing around it does`() {
        val bar = source("ui/components/Nook.kt")
        // The bar itself is a fixed-height surface with its own background; a `scroll` or a lazy
        // list inside it would be the other way this bug returns.
        val start = bar.indexOf("fun NookBottomBar(")
        assertTrue(start >= 0)
        val body = bar.substring(start, minOf(start + 4000, bar.length))
        assertFalse("the bar must not scroll", body.contains("verticalScroll"))
        assertFalse("the bar must not be a lazy list", body.contains("LazyColumn"))
        assertFalse("the bar must not be a lazy row", body.contains("LazyRow"))
    }

    @Test
    fun `1 a scrolling screen still clears the bar without knowing its height`() {
        // The screens keep their own bottom padding from the single constant, which the existing
        // chrome tests assert; here the point is that no screen reaches for a nav-bar inset itself.
        listOf(
            "ui/screens/HomeScreen.kt",
            "ui/screens/LibraryScreen.kt",
            "ui/screens/ExploreScreens.kt",
            "ui/screens/ProfileScreen.kt"
        ).forEach { path ->
            val text = source(path)
            assertFalse(
                "$path must not apply the navigation inset itself",
                text.contains("navigationBarsPadding")
            )
            assertTrue(
                "$path must clear the bar via SystemBars",
                text.contains("SystemBars.CONTENT_BOTTOM_DP")
            )
        }
    }

    // 2 and 3 -----------------------------------------------------------------------------------

    @Test
    fun `2 and 3 one owner of the navigation inset, and it applies in both system modes`() {
        // The window is the owner: the activity keeps the default fitting behaviour, so Android
        // insets the app by the gesture pill or the three-button strip and Compose reports zero.
        val activity = source("MainActivity.kt")
        assertTrue(
            "the window must stay non-edge-to-edge in the shell",
            activity.contains("setDecorFitsSystemWindows(window, true)")
        )
        // The bar still asks for its inset, which is zero in that mode and correct if the window is
        // ever edge-to-edge: one place, never two.
        val chrome = shellChrome()
        val barAt = chrome.indexOf("BottomBar(")
        val barCall = chrome.substring(barAt, minOf(barAt + 700, chrome.length))
        assertTrue("the bar owns its inset", barCall.contains("navigationBarsPadding()"))
        assertTrue(
            "the bar keeps the app's own gap above the system bar",
            barCall.contains("SystemBars.BAR_GAP_DP")
        )
        assertEqualsOnce("the shell must not add a second nav-bar padding", chrome, "navigationBarsPadding()")
        // The helper that decides whether to add it is still honest about the zero case.
        assertTrue(SystemBars.barAppliesOwnInset(48))
        assertTrue(SystemBars.barAppliesOwnInset(144))
        assertFalse(SystemBars.barAppliesOwnInset(0))
    }

    private fun assertEqualsOnce(what: String, text: String, needle: String) {
        val count = Regex(Regex.escape(needle)).findAll(text).count()
        assertTrue("$what (found $count)", count == 1)
    }

    // 4 -----------------------------------------------------------------------------------------

    @Test
    fun `4 the reader hides the bar and restores the window when it closes`() {
        val chrome = shellChrome()
        assertTrue(
            "the bar must be hidden for the reader",
            chrome.contains("tab !is Route.Reader")
        )
        val reader = source("ui/screens/ReaderScreen.kt")
        // The reader is the only edge-to-edge screen: it takes the window over and must give it
        // back, or the next screen inherits the wrong inset story.
        assertTrue(reader.contains("setDecorFitsSystemWindows(window, false)"))
        assertTrue(
            "closing the reader must restore the window",
            reader.contains("setDecorFitsSystemWindows(window, true)")
        )
        val restoreAt = reader.indexOf("setDecorFitsSystemWindows(window, true)")
        val disposeAt = reader.lastIndexOf("onDispose", restoreAt)
        assertTrue("the restore must happen in onDispose, not on a timer", disposeAt > 0)
    }

    // 5 -----------------------------------------------------------------------------------------

    @Test
    fun `5 the bar is driven by the route, never by scroll state`() {
        val chrome = shellChrome()
        val barAt = chrome.indexOf("BottomBar(")
        val barCall = chrome.substring(barAt, minOf(barAt + 700, chrome.length))
        assertFalse("the bar must not animate with scroll", barCall.contains("animate"))
        assertFalse("nor be offset by a scroll position", barCall.contains("scrollState"))
        assertFalse("nor be positioned from a list's offset", barCall.contains("offset"))
        // It is placed by the layout, with nothing but the app's own gap under it.
        assertFalse(
            "no arbitrary screen-edge padding",
            barCall.contains("padding(bottom = 48") || barCall.contains("padding(bottom = 96")
        )
    }

    @Test
    fun `the shell keeps no decorative padding that would move the bar`() {
        // A `padding(bottom = ...)` on the Column would shift both regions and reintroduce the
        // "double inset" symptom in a way no screen-level test would catch.
        val chrome = shellChrome()
        val columnAt = chrome.indexOf("Column(Modifier.fillMaxSize())")
        val columnLine = chrome.substring(columnAt, chrome.indexOf("\n", columnAt))
        assertFalse("the shell column must not pad the bottom", columnLine.contains("padding"))
    }
}
