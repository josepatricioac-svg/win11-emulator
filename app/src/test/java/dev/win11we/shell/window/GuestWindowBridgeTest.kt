package dev.win11we.shell.window

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.win11we.shell.x11.X11
import dev.win11we.shell.x11.XServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * D2: the shell IS the guest window manager. Every mapped guest top-level
 * becomes a regular shell window (floating, resizable, closable), the X
 * window's geometry follows the shell window's on-screen rect, and the two
 * sides are reconciled - dead guest windows close their shell window, closed
 * shell windows close (and if needed destroy) the guest window.
 */
class GuestWindowBridgeTest {

    private val rootId = X11.RESOURCE_ID_BASE
    private val top = rootId + 100
    private val desktop = DpSize(1000.dp, 800.dp)

    private lateinit var manager: WindowManager
    private lateinit var bridge: GuestWindowBridge
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        XServer.setDocumentForTest(XServer.defaultDocument(1000, 800))
        manager = WindowManager { null }
        logs.clear()
        bridge = GuestWindowBridge(
            manager = manager,
            desktopSizeDp = { desktop },
            density = { 2f },
            log = { logs += it },
        )
    }

    @After
    fun tearDown() {
        XServer.attachWindowManager(null)
        XServer.setDocumentForTest(XServer.defaultDocument(1280, 800))
    }

    private fun createGuestWindow(
        id: Int = top,
        w: Int = 800,
        h: Int = 600,
        title: String? = null,
        overrideRedirect: Boolean = false,
    ): Int {
        val doc = XServer.document
        val window = doc.createWindow(id, doc.rootId, 0, 0, w, h)!!
        if (overrideRedirect) window.overrideRedirect = true
        if (title != null) {
            val atom = doc.internAtom("WM_NAME")
            doc.setProperty(id, atom, 0, 8, title.toByteArray(Charsets.UTF_8))
        }
        return id
    }

    // --- map: guest window -> shell window ------------------------------------

    @Test
    fun `a guest top-level opens as a shell window with its payload and px-to-dp size`() {
        val xid = createGuestWindow(w = 800, h = 600, title = "Mozilla Firefox")
        bridge.mapRequested(xid)

        assertEquals(1, manager.windows.size)
        val shell = manager.windows.single()
        assertEquals("guest:$xid", shell.app.id)
        assertEquals("Mozilla Firefox", shell.app.label)
        assertFalse("guest apps are never taskbar pins", shell.app.pinned)
        val payload = shell.payload as? GuestWindowPayload
        assertNotNull(payload)
        assertEquals(xid, payload!!.xid)
        // density 2: 800x600 px -> 400x300 dp.
        assertEquals(400.dp, shell.size.width)
        assertEquals(300.dp, shell.size.height)
        // The X window is NOT mapped yet - the surface maps it at its real rect.
        assertFalse(XServer.document.window(xid)!!.mapped)
        assertEquals(1, bridge.hostedCount)
        assertTrue(bridge.isHosted(xid))
        assertTrue(logs.any { it.contains("shell window") && it.contains("Mozilla Firefox") })
    }

    @Test
    fun `the initial size is clamped into the desktop and to sane minimums`() {
        val huge = createGuestWindow(id = rootId + 101, w = 5000, h = 4000)
        bridge.mapRequested(huge)
        val shell = manager.windows.single()
        // 95% of the 1000x800dp desktop (float Dp: assert the range, not the bit).
        assertTrue(shell.size.width <= 950.dp && shell.size.width > 949.dp)
        assertTrue(shell.size.height <= 760.dp && shell.size.height > 759.dp)

        val tiny = createGuestWindow(id = rootId + 102, w = 20, h = 10)
        bridge.mapRequested(tiny)
        val small = manager.windows.last()
        assertEquals(GuestWindowBridge.MIN_WIDTH_DP, small.size.width)
        assertEquals(GuestWindowBridge.MIN_HEIGHT_DP, small.size.height)
    }

    @Test
    fun `an untitled guest falls back to a generic caption`() {
        val xid = createGuestWindow()
        bridge.mapRequested(xid)
        assertEquals("Guest application", manager.windows.single().app.label)
    }

    // --- surface geometry: the X window follows the shell window ----------------

    @Test
    fun `the first surface geometry MAPS the X window at the shell window's rect`() {
        val xid = createGuestWindow()
        bridge.mapRequested(xid)
        bridge.surfaceGeometry(xid, xPx = 40, yPx = 60, wPx = 800, hPx = 600)

        val window = XServer.document.window(xid)!!
        assertTrue("the surface report is what maps the guest window", window.mapped)
        assertEquals(40, window.x)
        assertEquals(60, window.y)
        assertEquals(800, window.width)
        assertEquals(600, window.height)
    }

    @Test
    fun `later surface geometry moves and resizes the mapped X window`() {
        val xid = createGuestWindow()
        bridge.mapRequested(xid)
        bridge.surfaceGeometry(xid, 0, 0, 800, 600)
        bridge.surfaceGeometry(xid, 120, 80, 640, 480)
        val window = XServer.document.window(xid)!!
        assertEquals(120, window.x)
        assertEquals(80, window.y)
        assertEquals(640, window.width)
        assertEquals(480, window.height)
    }

    @Test
    fun `a zero-sized report is ignored, not mapped`() {
        val xid = createGuestWindow()
        bridge.mapRequested(xid)
        bridge.surfaceGeometry(xid, 0, 0, 0, 0)
        assertFalse(XServer.document.window(xid)!!.mapped)
    }

    @Test
    fun `a client resize request updates the floating shell window size`() {
        val xid = createGuestWindow()
        bridge.mapRequested(xid)
        val shell = manager.windows.single()
        bridge.configureRequested(xid, null, null, 1200, 900, null)
        assertEquals(600.dp, shell.size.width) // density 2
        assertEquals(450.dp, shell.size.height)
    }

    @Test
    fun `a maximize owns the geometry - client resize requests do not shrink it`() {
        val xid = createGuestWindow()
        bridge.mapRequested(xid)
        val shell = manager.windows.single()
        shell.mode = WindowMode.MAXIMIZED
        val before = shell.size
        bridge.configureRequested(xid, null, null, 100, 100, null)
        assertEquals(before, shell.size)
    }

    // --- reconcile: dead guest window closes the shell window --------------------

    @Test
    fun `a guest window destroyed by its app closes the shell window`() {
        val xid = createGuestWindow()
        bridge.mapRequested(xid)
        bridge.surfaceGeometry(xid, 0, 0, 800, 600)
        assertEquals(1, manager.windows.size)

        XServer.document.destroyWindow(xid)
        bridge.sync()

        assertTrue("the shell window must go with the guest window", manager.windows.isEmpty())
        assertEquals(0, bridge.hostedCount)
        assertTrue(logs.any { it.contains("closing shell window") })
    }

    @Test
    fun `a guest window unmapped by its app closes the shell window too`() {
        val xid = createGuestWindow()
        bridge.mapRequested(xid)
        bridge.surfaceGeometry(xid, 0, 0, 800, 600)
        XServer.document.unmapWindow(xid)
        bridge.sync()
        assertTrue(manager.windows.isEmpty())
    }

    // --- reconcile: closed shell window closes the guest -------------------------

    @Test
    fun `closing the shell window asks the guest politely, then destroys after the grace`() {
        val xid = createGuestWindow()
        bridge.mapRequested(xid)
        bridge.surfaceGeometry(xid, 0, 0, 800, 600)
        val shell = manager.windows.single()

        manager.close(shell) // user closed the shell window; the guest ignores WM_DELETE
        bridge.sync()
        assertNotNull("first sync only requests the close", XServer.document.window(xid))
        repeat(GuestWindowBridge.CLOSE_GRACE_FRAMES.toInt()) { bridge.sync() }
        assertNotNull("still inside the grace period", XServer.document.window(xid))
        bridge.sync()
        assertNull("past the grace the window is destroyed - no zombies", XServer.document.window(xid))
        assertEquals(0, bridge.hostedCount)
        assertTrue(logs.any { it.contains("ignored WM_DELETE") })
    }

    @Test
    fun `a pending guest window whose shell window closed before mapping is destroyed`() {
        val xid = createGuestWindow()
        bridge.mapRequested(xid)
        val shell = manager.windows.single()
        manager.close(shell) // surface never reported geometry
        bridge.sync()
        assertNull("no zombie unmapped X window", XServer.document.window(xid))
        assertEquals(0, bridge.hostedCount)
    }

    // --- misc --------------------------------------------------------------------

    @Test
    fun `a remap of an already-hosted window does not open a second shell window`() {
        val xid = createGuestWindow()
        bridge.mapRequested(xid)
        bridge.surfaceGeometry(xid, 0, 0, 800, 600)
        XServer.document.unmapWindow(xid)
        // The client maps again (bridge still hosts it): map in place.
        bridge.mapRequested(xid)
        assertEquals(1, manager.windows.size)
        assertTrue(XServer.document.window(xid)!!.mapped)
    }

    @Test
    fun `detach clears all bookkeeping`() {
        val xid = createGuestWindow()
        bridge.mapRequested(xid)
        bridge.detach()
        assertEquals(0, bridge.hostedCount)
        bridge.sync() // must not throw or resurrect anything
        assertTrue(manager.windows.isNotEmpty()) // the shell window itself stays; detach only forgets
    }
}
