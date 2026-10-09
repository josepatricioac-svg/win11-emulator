package dev.win11we.shell.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desktop-engine launch seam of [BrowserState] (D1/R3 plumbing): a ready
 * engine with a launcher becomes launchable; everything else keeps the exact
 * gated behaviour the roadmap contract (and RoadmapGatingTest) requires.
 */
class DesktopBrowserStateTest {

    private val firefoxId = "desktop:firefox-esr"

    private fun state(
        ready: Boolean,
        launcher: ((String, String) -> DesktopLaunchOutcome)? = null,
        query: String = "example.com",
    ) = BrowserState(
        initialQuery = query,
        installed = listOf(BrowserApp("org.mozilla.firefox", "Firefox", null, false)),
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
        assertTrue(browser.launchDesktop())
        assertEquals(firefoxId, captured?.first)
        assertTrue("the resolved URL must be handed over", captured!!.second.startsWith("https://example.com"))
        assertEquals("Firefox ESR starting on :100", browser.desktopMessage)
        assertFalse(browser.launchFailed)
        assertEquals(firefoxId, browser.lastTarget)
        // A desktop engine never resolves to an Android intent target.
        assertNull(browser.targetPackage())
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
    fun `an unready desktop choice falls back to the android browser and launches nothing`() {
        var calls = 0
        val browser = state(ready = false, launcher = { _, _ -> calls++; DesktopLaunchOutcome(true, "nope") })
        // The resolver's designed fallback: an unready desktop choice resolves to the
        // installed Android Firefox, so the Open button keeps working - not blocked,
        // but also never pretending to launch the container engine.
        assertTrue(browser.engine() is BrowserEngine.AndroidBrowser)
        assertFalse(browser.desktopLaunchable())
        assertFalse(browser.engineBlocked())
        assertFalse(browser.launchDesktop())
        assertEquals(0, calls)
        assertEquals("org.mozilla.firefox", browser.targetPackage())
    }

    @Test
    fun `an empty address bar launches nothing`() {
        val browser = state(ready = true, launcher = { _, _ -> DesktopLaunchOutcome(true, "started") }, query = "")
        assertFalse(browser.launchDesktop())
        assertNull(browser.desktopMessage)
    }

    @Test
    fun `android engines are untouched by the desktop seam`() {
        val browser = BrowserState(
            initialQuery = "https://example.org",
            installed = listOf(BrowserApp("org.mozilla.firefox", "Firefox", null, true)),
            desktopEngineReady = { true },
            launchDesktopBrowser = { _, _ -> DesktopLaunchOutcome(true, "must not be called") },
        )
        assertFalse(browser.desktopLaunchable())
        assertFalse(browser.engineBlocked())
        assertEquals("org.mozilla.firefox", browser.targetPackage())
        assertEquals("https://example.org", browser.resolve())
    }

    @Test
    fun `choosing a still-gated desktop engine keeps the android fallback`() {
        val browser = BrowserState(
            initialQuery = "example.org",
            installed = listOf(BrowserApp("org.mozilla.firefox", "Firefox", null, false)),
            chosenEngineId = "desktop:edge-arm64",
            desktopEngineReady = { false },
        )
        assertTrue("edge waits on D2 - resolver must fall back", browser.engine() is BrowserEngine.AndroidBrowser)
        assertFalse(browser.desktopLaunchable())
    }
}
