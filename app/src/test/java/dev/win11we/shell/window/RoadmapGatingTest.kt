package dev.win11we.shell.window

import dev.win11we.shell.R
import dev.win11we.shell.container.ContainerStatus
import dev.win11we.shell.container.NotInstalledRuntime
import dev.win11we.shell.ui.browser.BrowserApp
import dev.win11we.shell.ui.browser.BrowserEngine
import dev.win11we.shell.ui.browser.BrowserState
import dev.win11we.shell.ui.browser.DesktopBrowserCatalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the roadmap gating in place: everything that needs the container runtime
 * (R1 binaries, R2 service, R3 rootfs) or the X server (D1/D2) must stay reachable
 * in the UI but must refuse to pretend it works. If a future step makes one of
 * these available, this test has to be updated deliberately - not by accident.
 */
class RoadmapGatingTest {

    @Test
    fun `every shell app opens a real window - nothing is left as a stub`() {
        val ids = ShellApps.map { it.id }
        assertEquals(
            listOf("explorer", "notepad", "browser", "settings", "terminal", "taskmgr", "containers", "diagnostics"),
            ids,
        )
        assertTrue("all N2 apps must be enabled", ShellApps.all { it.enabled })
        assertTrue("Diagnostics stays out of the taskbar, reachable from All apps",
            ShellApps.first { it.id == "diagnostics" }.pinned.not())
        assertTrue(ShellApps.all { it.iconRes != 0 && it.label.isNotBlank() })
        assertEquals(R.drawable.ic_app_container, ShellApps.first { it.id == "containers" }.iconRes)
    }

    @Test
    fun `the container runtime reports exactly which steps are missing`() {
        val runtime = NotInstalledRuntime()
        val container = dev.win11we.shell.container.Container.defaults("office", "Office", "/tmp/prefix")
        assertFalse(runtime.ready)
        assertEquals(ContainerStatus.STOPPED, runtime.statusOf(container))
        listOf(runtime.start(container), runtime.stop(container), runtime.restart(container)).forEach { result ->
            assertFalse("must not claim success without binaries", result.success)
            assertTrue(result.message.contains("R1"))
            assertTrue(result.message.contains("R2"))
        }
    }

    @Test
    fun `no desktop browser engine is available until the runtime and X server exist`() {
        assertTrue(DesktopBrowserCatalogue.ALL.isNotEmpty())
        DesktopBrowserCatalogue.ALL.forEach { engine ->
            assertFalse("${engine.id} must stay gated", engine.available)
            assertTrue(engine.requirement.isNotBlank())
        }
        val firefoxEsr = DesktopBrowserCatalogue.byId("desktop:firefox-esr")!!
        assertTrue(firefoxEsr.requirement.contains("R3"))
        assertTrue(firefoxEsr.requirement.contains("D1"))
        val edge = DesktopBrowserCatalogue.byId("desktop:edge-arm64")!!
        assertTrue(edge.requirement.contains("D2"))
    }

    @Test
    fun `an unavailable desktop engine falls back to an android browser`() {
        val firefox = BrowserApp("org.mozilla.firefox", "Firefox", null, false)
        val state = BrowserState(installed = listOf(firefox), chosenEngineId = "desktop:firefox-esr")
        assertFalse(state.engineBlocked())
        assertEquals("org.mozilla.firefox", state.targetPackage())
    }

    @Test
    fun `a ready desktop engine has no intent target and is reported as blocked`() {
        val state = BrowserState(installed = emptyList(), chosenEngineId = "desktop:edge-arm64", desktopEngineReady = { true })
        assertTrue(state.engine() is BrowserEngine.DesktopBrowser)
        assertTrue("R2 must launch it inside the container, the UI has to say so", state.engineBlocked())
        assertNull(state.targetPackage())
        assertEquals("Microsoft Edge (Windows arm64)", state.engine().label)
    }
}
