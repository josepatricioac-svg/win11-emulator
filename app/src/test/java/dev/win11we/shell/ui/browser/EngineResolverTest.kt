package dev.win11we.shell.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineResolverTest {

    @Test
    fun `no choice means the default desktop engine`() {
        assertEquals("desktop:firefox-esr", EngineResolver.resolve(null).id)
        assertEquals("desktop:firefox-esr", EngineResolver.resolve("").id)
        assertEquals(EngineResolver.DEFAULT_ENGINE_ID, EngineResolver.DEFAULT.id)
    }

    @Test
    fun `a catalogued choice is honoured whatever its readiness`() {
        // Readiness gates the LAUNCH (BrowserState.engineBlocked), never the
        // choice: an unready engine stays selected and is named as blocked -
        // the resolver no longer substitutes anything silently.
        assertEquals("desktop:edge-arm64", EngineResolver.resolve("desktop:edge-arm64").id)
        assertEquals("desktop:chromium-arm64", EngineResolver.resolve("desktop:chromium-arm64").id)
    }

    @Test
    fun `stale android choices and unknown ids fall back to the default engine`() {
        // A stored "android:..." preference predates the removal of Android
        // browser detection; it must resolve safely, not crash or hand a URL
        // to the system.
        assertEquals(EngineResolver.DEFAULT_ENGINE_ID, EngineResolver.resolve("android:org.mozilla.firefox").id)
        assertEquals(EngineResolver.DEFAULT_ENGINE_ID, EngineResolver.resolve("android:").id)
        assertEquals(EngineResolver.DEFAULT_ENGINE_ID, EngineResolver.resolve("nonsense").id)
        assertEquals(EngineResolver.DEFAULT_ENGINE_ID, EngineResolver.resolve("desktop:does-not-exist").id)
    }

    @Test
    fun `every catalogued desktop engine declares its licence and gating step`() {
        assertTrue(DesktopBrowserCatalogue.ALL.isNotEmpty())
        DesktopBrowserCatalogue.ALL.forEach { engine ->
            assertTrue(engine.id.startsWith("desktop:"))
            assertTrue(engine.license.isNotBlank())
            assertTrue(engine.requirement.isNotBlank())
            assertTrue(engine.detail.length > 20)
            assertEquals(false, engine.available) // flips to true in R4
            assertEquals(engine, DesktopBrowserCatalogue.byId(engine.id))
        }
        assertEquals("desktop:firefox-esr", DesktopBrowserCatalogue.ALL.first().id)
    }
}
