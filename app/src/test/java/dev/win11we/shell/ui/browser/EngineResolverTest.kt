package dev.win11we.shell.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineResolverTest {

    private val firefox = BrowserEngine.AndroidBrowser("org.mozilla.firefox", "Firefox")
    private val chrome = BrowserEngine.AndroidBrowser("com.android.chrome", "Chrome", isSystemDefault = true)
    private val brave = BrowserEngine.AndroidBrowser("com.brave.browser", "Brave")
    private val android = listOf(chrome, firefox, brave)

    @Test
    fun `no choice means firefox when it is installed`() {
        assertEquals(firefox, EngineResolver.resolve(null, android))
        assertEquals(firefox, EngineResolver.resolve("", android))
    }

    @Test
    fun `without firefox the system default wins, then anything installed`() {
        assertEquals(chrome, EngineResolver.resolve(null, listOf(chrome, brave)))
        assertEquals(brave, EngineResolver.resolve(null, listOf(brave)))
    }

    @Test
    fun `with nothing installed we fall back to the system handler`() {
        assertEquals(BrowserEngine.SystemDefault, EngineResolver.resolve(null, emptyList()))
        assertNull(EngineResolver.targetPackage(BrowserEngine.SystemDefault))
    }

    @Test
    fun `an explicit android choice is honoured`() {
        assertEquals(brave, EngineResolver.resolve("android:com.brave.browser", android))
        assertEquals("com.brave.browser", EngineResolver.targetPackage(brave))
    }

    @Test
    fun `a stale android choice falls back to firefox`() {
        assertEquals(firefox, EngineResolver.resolve("android:com.opera.browser", android))
    }

    @Test
    fun `the system default engine can be chosen explicitly`() {
        assertEquals(BrowserEngine.SystemDefault, EngineResolver.resolve("android:", android))
    }

    @Test
    fun `desktop engines stay blocked until the container runtime is ready`() {
        val engine = EngineResolver.resolve("desktop:firefox-esr", android)
        assertTrue("must fall back while the runtime is missing", engine is BrowserEngine.AndroidBrowser)
        assertEquals(firefox, engine)

        val ready = EngineResolver.resolve("desktop:firefox-esr", android) { id -> id == "desktop:firefox-esr" }
        assertTrue(ready is BrowserEngine.DesktopBrowser)
        assertEquals("Firefox ESR (Linux arm64)", ready.label)
        assertNull("desktop engines launch inside the container, not via an intent", EngineResolver.targetPackage(ready))
    }

    @Test
    fun `unknown engine ids fall back instead of crashing`() {
        assertEquals(firefox, EngineResolver.resolve("desktop:does-not-exist", android))
        assertEquals(firefox, EngineResolver.resolve("nonsense", android))
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
