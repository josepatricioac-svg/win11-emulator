package dev.win11we.shell.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopEngineLabelsTest {
    private val firefox = DesktopBrowserCatalogue.byId("desktop:firefox-esr")!!

    @Test
    fun `a ready engine says Ready instead of a stale roadmap label`() {
        assertEquals("Ready", DesktopEngineLabels.status(firefox, ready = true, missing = null))
        assertEquals("Ready", DesktopEngineLabels.badge(true))
    }

    @Test
    fun `the exact missing step wins over the static requirement`() {
        val step = "Firefox ESR not installed (download + extract it in Runtime download)"
        assertEquals(step, DesktopEngineLabels.status(firefox, ready = false, missing = step))
        assertEquals("Not ready", DesktopEngineLabels.badge(false))
    }

    @Test
    fun `before the readiness worker reports the catalogue requirement is the fallback`() {
        val text = DesktopEngineLabels.status(firefox, ready = false, missing = null)
        assertTrue(text.startsWith("Needs "))
        assertTrue(text.contains("R3"))
    }
}
