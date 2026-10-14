package dev.win11we.shell.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlsTest {

    @Test
    fun `blank input resolves to nothing`() {
        assertNull(Urls.parse(""))
        assertNull(Urls.parse("   "))
    }

    @Test
    fun `absolute urls pass through untouched`() {
        assertEquals("https://example.org/a?b=c", Urls.parse("https://example.org/a?b=c"))
        assertEquals("http://10.0.0.5:8080/", Urls.parse("http://10.0.0.5:8080/"))
        assertEquals("HTTPS://Example.org", Urls.parse("HTTPS://Example.org"))
        assertEquals("file:///sdcard/doc.pdf", Urls.parse("file:///sdcard/doc.pdf"))
        assertEquals("about:blank", Urls.parse("about:blank"))
    }

    @Test
    fun `bare hostnames get https`() {
        assertEquals("https://example.org", Urls.parse("example.org"))
        assertEquals("https://mozilla.org/firefox", Urls.parse("mozilla.org/firefox"))
        assertEquals("https://intranet.local", Urls.parse("intranet.local"))
    }

    @Test
    fun `localhost stays http so local servers work`() {
        assertEquals("http://localhost", Urls.parse("localhost"))
        assertEquals("http://localhost:8080/wine", Urls.parse("localhost:8080/wine"))
    }

    @Test
    fun `anything else becomes a search query`() {
        assertEquals("https://duckduckgo.com/?q=firefox+release+notes", Urls.parse("firefox release notes"))
        assertEquals("https://duckduckgo.com/?q=winelib", Urls.parse("winelib")) // no dot -> not a host
        assertEquals(
            "https://duckduckgo.com/?q=50%25+off+laptop",
            Urls.parse("50% off laptop"),
        )
    }

    @Test
    fun `the search engine can be swapped`() {
        assertEquals(
            "https://search.example/?q=firefox",
            Urls.parse("firefox", searchTemplate = "https://search.example/?q=%s"),
        )
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals("https://example.org", Urls.parse("  example.org  "))
    }
}

/**
 * The desktop-only [BrowserState] basics. Android-browser detection is gone
 * (owner decision): the state has no installed list, no target package and no
 * intent path - only the desktop engine, its readiness and its launcher.
 */
class BrowserStateTest {

    @Test
    fun `the default engine is firefox esr and unknown choices fall back to it`() {
        assertEquals("desktop:firefox-esr", BrowserState().engine().id)
        assertEquals("desktop:firefox-esr", BrowserState(chosenEngineId = "desktop:nope").engine().id)
        assertEquals("desktop:firefox-esr", BrowserState(chosenEngineId = "android:com.opera.browser").engine().id)
        assertEquals("desktop:edge-arm64", BrowserState(chosenEngineId = "desktop:edge-arm64").engine().id)
    }

    @Test
    fun `resolve records the url and the engine id, empty input does nothing`() {
        val state = BrowserState()
        assertNull(state.resolve())
        assertNull(state.lastUrl)

        state.query = "mozilla.org"
        assertEquals("https://mozilla.org", state.resolve())
        assertEquals("https://mozilla.org", state.lastUrl)
        assertEquals("desktop:firefox-esr", state.lastTarget)
        assertEquals(listOf("https://mozilla.org"), state.history())
    }

    @Test
    fun `chooseEngine records the selection immediately`() {
        val state = BrowserState()
        state.chooseEngine("desktop:chromium-arm64")
        assertEquals("desktop:chromium-arm64", state.lastTarget)
    }

    @Test
    fun `without readiness nothing is launchable and the blocker is named`() {
        val state = BrowserState()
        assertTrue(state.engineBlocked())
        assertFalse(state.desktopLaunchable())
        assertFalse(state.launchDesktop())
        assertTrue(state.blockingNote()!!.startsWith("needs "))
    }

    @Test
    fun `a failed launch flag is cleared by the next resolve`() {
        val state = BrowserState(
            initialQuery = "https://example.org",
            desktopEngineReady = { true },
            launchDesktopBrowser = { _, _ -> DesktopLaunchOutcome(false, "refused") },
        )
        assertFalse(state.launchDesktop())
        assertTrue(state.launchFailed)
        state.query = "example.org"
        state.resolve()
        assertFalse(state.launchFailed) // cleared by the next attempt
    }
}
