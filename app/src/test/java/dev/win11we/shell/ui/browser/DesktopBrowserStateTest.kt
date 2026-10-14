package dev.win11we.shell.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desktop-engine launch seam of [BrowserState] (D1/R3 plumbing): a ready
 * engine with a launcher becomes launchable; everything else keeps the exact
 * gated behaviour the roadmap contract (and RoadmapGatingTest) requires. Since
 * Android-browser detection was removed, "blocked" is the ONLY non-launchable
 * state - there is no silent intent fallback to test anymore, by design.
 */
class DesktopBrowserStateTest {

    private val firefoxId = "desktop:firefox-esr"

    private fun state(
        ready: Boolean,
        launcher: ((String, String) -> DesktopLaunchOutcome)? = null,
        query: String = "example.com",
    ) = BrowserState(
        initialQuery = query,
        chosenEngineId = firefoxId,
        desktopEngineReady = { id -> ready && id == firefoxId },
        launchDesktopBrowser = launcher,
    )

    @Test
    fun `a ready engine with a launcher is launchable and not blocked`() {
        var captured: Pair<String, String>? = null
        val browser = state(ready = true, launcher = { id, url ->
            captured = id to url
            DesktopLaunchOutcome(true, "Firefox ESR starting on :100")
        })
        assertTrue(browser.desktopLaunchable())
        assertFalse(browser.engineBlocked())
        assertNull(browser.blockingNote())
        assertTrue(browser.launchDesktop())
        assertEquals(firefoxId, captured?.first)
        assertTrue("the resolved URL must be handed over", captured!!.second.startsWith("https://example.com"))
        assertEquals("Firefox ESR starting on :100", browser.desktopMessage)
        assertFalse(browser.launchFailed)
        assertEquals(firefoxId, browser.lastTarget)
    }

    @Test
    fun `a failing launch records the named refusal and the failure flag`() {
        val browser = state(ready = true, launcher = { _, _ ->
            DesktopLaunchOutcome(false, "Firefox ESR refused to start [ROOTFS_MISSING]: Rootfs not found")
        })
        assertFalse(browser.launchDesktop())
        assertTrue(browser.launchFailed)
        assertTrue(browser.desktopMessage!!.contains("ROOTFS_MISSING"))
    }

    @Test
    fun `a ready engine without a launcher stays blocked - the gating contract`() {
        val browser = state(ready = true, launcher = null)
        assertFalse(browser.desktopLaunchable())
        assertTrue(browser.engineBlocked())
        assertFalse(browser.launchDesktop())
    }

    @Test
    fun `an unready engine is blocked, named and never launched - no silent reroute`() {
        var calls = 0
        val browser = state(ready = false, launcher = { _, _ -> calls++; DesktopLaunchOutcome(true, "nope") })
        assertTrue(browser.engineBlocked())
        assertFalse(browser.desktopLaunchable())
        assertFalse(browser.launchDesktop())
        assertEquals("the launcher must never fire while blocked", 0, calls)
        // The blocker is named - first by the readiness worker's exact step...
        assertEquals("Firefox ESR not installed", browser.blockingNote { "Firefox ESR not installed" })
        // ...and before any readiness info exists, by the catalogue requirement.
        assertTrue(browser.blockingNote { null }!!.contains("R3"))
    }

    @Test
    fun `an empty address bar launches nothing`() {
        val browser = state(ready = true, launcher = { _, _ -> DesktopLaunchOutcome(true, "started") }, query = "")
        assertFalse(browser.launchDesktop())
        assertNull(browser.desktopMessage)
    }

    @Test
    fun `resolve records the url and the engine id`() {
        val browser = state(ready = true, launcher = { _, _ -> DesktopLaunchOutcome(true, "ok") })
        assertEquals("https://example.com", browser.resolve())
        assertEquals("https://example.com", browser.lastUrl)
        assertEquals(firefoxId, browser.lastTarget)
        assertEquals(listOf("https://example.com"), browser.history())
    }

    @Test
    fun `a stale android choice resolves to the default engine and stays gated`() {
        // Settings may still hold an "android:..." id from before detection was
        // removed; it must resolve safely to the default desktop engine.
        val browser = BrowserState(chosenEngineId = "android:com.brave.browser")
        assertEquals(firefoxId, browser.engine().id)
        assertTrue(browser.engineBlocked())
    }
}
