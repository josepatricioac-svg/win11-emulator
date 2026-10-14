package dev.win11we.shell.x11

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The document helpers the D2 per-window model runs on: subtree paint order
 * (what one shell-hosted window renders), the popup layer (override-redirect
 * top-levels the desktop surface composites), the top-level walk, and WM_NAME
 * titles (what the shell window caption shows).
 */
class XGuestWindowTest {

    private val rootId = X11.RESOURCE_ID_BASE
    private lateinit var doc: XDocument

    private val top = rootId + 100
    private val child = rootId + 101
    private val grandChild = rootId + 102
    private val popup = rootId + 200

    @Before
    fun setUp() {
        doc = XDocument(
            ScreenConfig(rootId = rootId, widthPx = 1000, heightPx = 800, defaultColormap = rootId + 1),
        )
    }

    private fun create(id: Int, parent: Int = rootId, x: Int = 0, y: Int = 0, w: Int = 100, h: Int = 100): XWindow {
        val window = doc.createWindow(id, parent, x, y, w, h)
        assertTrue(window != null)
        return window!!
    }

    @Test
    fun `subtreePaintOrder returns the root of the subtree first, children back-to-front`() {
        create(top, w = 300, h = 200)
        create(child, parent = top, x = 10, y = 10)
        create(grandChild, parent = child, x = 5, y = 5)
        doc.mapWindow(top)
        doc.mapWindow(child)
        doc.mapWindow(grandChild)
        assertEquals(listOf(top, child, grandChild), doc.subtreePaintOrder(top).map { it.id })
    }

    @Test
    fun `an unmapped subtree root hides everything below it`() {
        create(top)
        create(child, parent = top)
        doc.mapWindow(child) // mapped, but its parent is not: not viewable
        assertTrue(doc.subtreePaintOrder(top).isEmpty())
    }

    @Test
    fun `an unmapped child stops its own subtree but not its siblings`() {
        val sibling = rootId + 103
        create(top)
        create(child, parent = top)
        create(grandChild, parent = child)
        create(sibling, parent = top)
        doc.mapWindow(top)
        doc.mapWindow(child)
        doc.mapWindow(grandChild)
        doc.mapWindow(sibling)
        doc.unmapWindow(child)
        // grandChild is hidden with its parent; the sibling still paints.
        assertEquals(listOf(top, sibling), doc.subtreePaintOrder(top).map { it.id })
    }

    @Test
    fun `popupTopLevels lists mapped override-redirect top-levels only`() {
        val menu = create(popup)
        menu.overrideRedirect = true
        val normal = create(top)
        doc.mapWindow(top)
        assertTrue("nothing mapped as a popup yet", doc.popupTopLevels().isEmpty())
        doc.mapWindow(popup)
        assertEquals(listOf(popup), doc.popupTopLevels().map { it.id })
        // A normal mapped top-level is NOT a popup (the shell WM hosts it).
        @Suppress("UNUSED_EXPRESSION")
        normal
        doc.unmapWindow(popup)
        assertTrue(doc.popupTopLevels().isEmpty())
    }

    @Test
    fun `topLevelOf walks up to the child of the root`() {
        create(top)
        create(child, parent = top)
        create(grandChild, parent = child)
        assertEquals(top, doc.topLevelOf(doc.window(grandChild)!!).id)
        assertEquals(top, doc.topLevelOf(doc.window(child)!!).id)
        assertEquals(top, doc.topLevelOf(doc.window(top)!!).id)
        // The root maps to itself without looping forever.
        assertEquals(rootId, doc.topLevelOf(doc.window(rootId)!!).id)
    }

    @Test
    fun `windowTitle reads WM_NAME as UTF-8 and tolerates the NUL pad`() {
        create(top)
        assertNull(doc.windowTitle(top))
        val wmName = doc.internAtom("WM_NAME")
        doc.setProperty(top, wmName, 0 /* STRING */, 8, "Mozilla Firefox\u0000".toByteArray(Charsets.UTF_8))
        assertEquals("Mozilla Firefox", doc.windowTitle(top))
        // UTF-8 round-trips too (UTF8_STRING clients).
        doc.setProperty(top, wmName, 0, 8, "ファイル - Firefox".toByteArray(Charsets.UTF_8))
        assertEquals("ファイル - Firefox", doc.windowTitle(top))
        // A blank title is reported as absent, not as "".
        doc.setProperty(top, wmName, 0, 8, "   ".toByteArray(Charsets.UTF_8))
        assertNull(doc.windowTitle(top))
    }
}
