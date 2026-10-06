package dev.win11we.shell.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

class BrowserStateTest {

    private fun browser(packageName: String, label: String, isDefault: Boolean = false) =
        BrowserApp(packageName = packageName, label = label, icon = null, isDefault = isDefault)

    @Test
    fun `firefox is preferred when installed and the user has not chosen`() {
        val state = BrowserState(
            installed = listOf(
                browser("com.android.chrome", "Chrome", isDefault = true),
                browser("org.mozilla.firefox", "Firefox"),
            ),
        )
        assertEquals("org.mozilla.firefox", state.targetPackage())
        assertEquals("Firefox", state.targetLabel())
    }

    @Test
    fun `an explicit user choice wins over the firefox preference`() {
        val state = BrowserState(
            installed = listOf(
                browser("org.mozilla.firefox", "Firefox"),
                browser("com.brave.browser", "Brave"),
            ),
            preferredPackage = "com.brave.browser",
        )
        assertEquals("com.brave.browser", state.targetPackage())
    }

    @Test
    fun `a stale choice falls back to firefox then to the system default`() {
        val withFirefox = BrowserState(
            installed = listOf(
                browser("com.android.chrome", "Chrome", isDefault = true),
                browser("org.mozilla.firefox", "Firefox"),
            ),
            preferredPackage = "com.opera.browser", // uninstalled
        )
        assertEquals("org.mozilla.firefox", withFirefox.targetPackage())

        val withoutFirefox = BrowserState(
            installed = listOf(browser("com.android.chrome", "Chrome", isDefault = true)),
            preferredPackage = "com.opera.browser",
        )
        assertEquals("com.android.chrome", withoutFirefox.targetPackage())
    }

    @Test
    fun `with no browser installed the system default intent is used`() {
        val state = BrowserState(installed = emptyList())
        assertNull(state.targetPackage())
        assertEquals("System default browser", state.targetLabel())
    }

    @Test
    fun `resolve records the url and target, empty input does nothing`() {
        val state = BrowserState(installed = listOf(browser("org.mozilla.firefox", "Firefox")))
        assertNull(state.resolve())
        assertNull(state.lastUrl)

        state.query = "mozilla.org"
        assertEquals("https://mozilla.org", state.resolve())
        assertEquals("https://mozilla.org", state.lastUrl)
        assertEquals("org.mozilla.firefox", state.lastTarget)
        assertEquals(listOf("https://mozilla.org"), state.history())
    }

    @Test
    fun `chooseBrowser switches the target immediately`() {
        val state = BrowserState(
            installed = listOf(
                browser("org.mozilla.firefox", "Firefox"),
                browser("com.vivaldi.browser", "Vivaldi"),
            ),
        )
        state.chooseBrowser("com.vivaldi.browser")
        assertEquals("com.vivaldi.browser", state.lastTarget)
    }

    @Test
    fun `a failed launch is surfaced to the ui`() {
        val state = BrowserState()
        assertTrue(!state.launchFailed)
        state.markLaunchFailed()
        assertTrue(state.launchFailed)
        state.query = "example.org"
        state.resolve()
        assertTrue(!state.launchFailed) // cleared by the next attempt
    }
}
