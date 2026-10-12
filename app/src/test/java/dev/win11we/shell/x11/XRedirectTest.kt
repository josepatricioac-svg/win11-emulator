package dev.win11we.shell.x11

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Wire builder for the D1b tests (same conventions as XProtocolTest). */
private class Requests {
    fun build(opcode: Int, data: Int = 0, body: (XWriter) -> Unit): ByteArray {
        val bodyWriter = XWriter(true)
        body(bodyWriter)
        val bodyBytes = bodyWriter.toByteArray()
        val writer = XWriter(true)
        writer.byte(opcode)
        writer.byte(data)
        val total = 4 + bodyBytes.size
        val padded = X11.pad4(total)
        writer.short(padded / 4)
        writer.bytes(bodyBytes)
        writer.pad(padded - total)
        return writer.toByteArray()
    }

    fun createWindow(id: Int, parent: Int, x: Int, y: Int, w: Int, h: Int, eventMask: Int = 0) =
        build(X11.REQ_CREATE_WINDOW, 24) { writer ->
            writer.int(id); writer.int(parent)
            writer.short(x); writer.short(y); writer.short(w); writer.short(h)
            writer.short(0); writer.short(0); writer.int(0x21)
            writer.int(if (eventMask == 0) 0 else 0x00000800)
            if (eventMask != 0) writer.int(eventMask)
        }

    fun changeAttributes(id: Int, eventMask: Int) = build(X11.REQ_CHANGE_WINDOW_ATTRIBUTES) { writer ->
        writer.int(id); writer.int(0x00000800); writer.int(eventMask)
    }

    fun mapWindow(id: Int) = build(X11.REQ_MAP_WINDOW) { it.int(id) }
    fun configureWindow(id: Int, mask: Int, vararg values: Int) = build(X11.REQ_CONFIGURE_WINDOW) { writer ->
        writer.int(id); writer.int(mask); values.forEach { writer.int(it) }
    }

    fun createGc(id: Int, drawable: Int, foreground: Int) = build(X11.REQ_CREATE_GC) { writer ->
        writer.int(id); writer.int(drawable); writer.int(0x00000004); writer.int(foreground)
    }

    fun polyFillRectangle(drawable: Int, gc: Int, x: Int, y: Int, w: Int, h: Int) =
        build(X11.REQ_POLY_FILL_RECTANGLE) { writer ->
            writer.int(drawable); writer.int(gc)
            writer.short(x); writer.short(y); writer.short(w); writer.short(h)
        }

    /** Real PutImage wire layout: format in the data byte, depth at offset 21. */
    fun putImage(drawable: Int, gc: Int, w: Int, h: Int, dstX: Int, dstY: Int, depth: Int, pixels: ByteArray) =
        build(X11.REQ_PUT_IMAGE, 2 /* ZPixmap */) { writer ->
            writer.int(drawable); writer.int(gc)
            writer.short(w); writer.short(h); writer.short(dstX); writer.short(dstY)
            writer.byte(0); writer.byte(depth); writer.pad(2)
            writer.bytes(pixels)
        }

    fun copyArea(src: Int, dst: Int, gc: Int, sx: Int, sy: Int, dx: Int, dy: Int, w: Int, h: Int) =
        build(X11.REQ_COPY_AREA) { writer ->
            writer.int(src); writer.int(dst); writer.int(gc)
            writer.short(sx); writer.short(sy); writer.short(dx); writer.short(dy)
            writer.short(w); writer.short(h)
        }
}

private fun ByteArray.intLE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or ((this[offset + 3].toInt() and 0xFF) shl 24)

private fun ByteArray.shortLE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

class XDisplayListTest {

    private val lists = XDisplayList()

    @Test
    fun `ops are routed per drawable and bump the revision`() {
        val before = lists.revision
        lists.fillRect(10, 0, 0, 4, 4, 0xFF0000)
        lists.fillRect(20, 1, 1, 2, 2, 0x00FF00)
        assertEquals(1, lists.ops(10).size)
        assertEquals(1, lists.ops(20).size)
        assertEquals(before + 2, lists.revision)
        val fill = lists.ops(10).single() as DrawOp.Fill
        assertEquals(0xFFFF0000.toInt(), fill.argb) // depth-24 pixel forced opaque
    }

    @Test
    fun `zpixmap conversion is BGRX to ARGB`() {
        // Two pixels: blue then green, depth 24 (pad byte must be ignored).
        val data = byteArrayOf(0xFF.toByte(), 0, 0, 0x7F, 0, 0xFF.toByte(), 0, 0x11)
        val argb = XDisplayList.toArgb(data, 24, 2, 1)
        assertArrayEquals(intArrayOf(0xFF0000FF.toInt(), 0xFF00FF00.toInt()), argb)
    }

    @Test
    fun `depth 32 keeps alpha and short data pads with zeros`() {
        val data = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        // Two pixels requested but only one pixel of data: the second pads to 0.
        assertArrayEquals(intArrayOf(0x40302010, 0), XDisplayList.toArgb(data, 32, 2, 1))
        // Depth 1 decodes as a bitmap since D1d (a1 RENDER masks): LSB is the
        // leftmost pixel, scanlines pad to 32 bits.
        val bits = byteArrayOf(0b10, 0, 0, 0)
        assertArrayEquals(intArrayOf(0, 0xFF000000.toInt()), XDisplayList.toArgb(bits, 1, 2, 1))
        assertEquals(0, XDisplayList.toArgb(data, 16, 2, 1).size) // other depths: unsupported
    }

    @Test
    fun `remove drops a drawable's list`() {
        lists.clearArea(5, 0, 0, 0, 0)
        assertEquals(1, lists.ops(5).size)
        lists.remove(5)
        assertEquals(0, lists.ops(5).size)
    }
}

class XRedirectTest {

    private lateinit var document: XDocument
    private val requests = Requests()
    private val rootId = X11.RESOURCE_ID_BASE

    @Before
    fun setUp() {
        document = XServer.defaultDocument(1280, 800)
    }

    @After
    fun tearDown() {
        XServer.attachWindowManager(null)
    }

    private fun send(connection: XConnection, vararg bytes: ByteArray): List<ByteArray> {
        bytes.forEach { connection.onBytes(it) }
        return connection.drainOutbox()
    }

    // --- SubstructureRedirect held by a real client --------------------------

    @Test
    fun `map is redirected to the selecting client as MapRequest`() {
        val wm = XConnection(document)
        val client = XConnection(document)
        val mask = EventMask.SUBSTRUCTURE_REDIRECT or EventMask.SUBSTRUCTURE_NOTIFY
        send(wm, requests.changeAttributes(rootId, mask))

        val win = rootId + 100
        send(client, requests.createWindow(win, rootId, 10, 10, 200, 100))
        send(client, requests.mapWindow(win))

        assertFalse("window must stay unmapped until the WM acts", document.window(win)!!.mapped)
        val event = wm.drainOutbox().single { it[0].toInt() == X11.EV_MAP_REQUEST }
        assertEquals(rootId, event.intLE(4))
        assertEquals(win, event.intLE(8))

        // The WM's own map request is never redirected back at it.
        send(wm, requests.configureWindow(win, 0x000F, 40, 40, 300, 200), requests.mapWindow(win))
        assertTrue(document.window(win)!!.mapped)
        assertEquals(40, document.window(win)!!.x)
    }

    @Test
    fun `a second redirect selection is an Access error`() {
        val wm1 = XConnection(document)
        val wm2 = XConnection(document)
        send(wm1, requests.changeAttributes(rootId, EventMask.SUBSTRUCTURE_REDIRECT))
        val out = send(wm2, requests.changeAttributes(rootId, EventMask.SUBSTRUCTURE_REDIRECT))
        val error = out.single { it[0].toInt() == 0 }
        assertEquals(X11.ERR_ACCESS, error[1].toInt() and 0xFF)
    }

    @Test
    fun `override-redirect windows bypass the WM`() {
        val wm = XConnection(document)
        val client = XConnection(document)
        send(wm, requests.changeAttributes(rootId, EventMask.SUBSTRUCTURE_REDIRECT))
        // CreateWindow with CWOverrideRedirect (0x200) + CWEventMask (0x800): value order is override first.
        val win = rootId + 7
        val create = requests.build(X11.REQ_CREATE_WINDOW, 24) { writer ->
            writer.int(win); writer.int(rootId)
            writer.short(0); writer.short(0); writer.short(50); writer.short(50)
            writer.short(0); writer.short(0); writer.int(0x21)
            writer.int(0x00000A00); writer.int(1); writer.int(0)
        }
        send(client, create, requests.mapWindow(win))
        assertTrue(document.window(win)!!.mapped)
        assertTrue(wm.drainOutbox().none { it[0].toInt() == X11.EV_MAP_REQUEST })
    }

    @Test
    fun `configure is redirected as ConfigureRequest`() {
        val wm = XConnection(document)
        val client = XConnection(document)
        send(wm, requests.changeAttributes(rootId, EventMask.SUBSTRUCTURE_REDIRECT))
        val win = rootId + 11
        send(
            client,
            requests.createWindow(win, rootId, 0, 0, 100, 100),
            requests.mapWindow(win),
        )
        send(wm, requests.mapWindow(win)) // WM maps it first
        send(client, requests.configureWindow(win, 0x000F, 5, 6, 640, 480))

        val window = document.window(win)!!
        assertEquals("geometry must not change before the WM approves", 100, window.width)
        val event = wm.drainOutbox().single { it[0].toInt() == X11.EV_CONFIGURE_REQUEST }
        assertEquals(win, event.intLE(8))
        assertEquals(5, event.shortLE(12))
        assertEquals(6, event.shortLE(14))
        assertEquals(640, event.shortLE(16))
        assertEquals(480, event.shortLE(18))
        assertEquals(0x000F, event.shortLE(24))
    }

    // --- the in-process shell WM ---------------------------------------------

    private class FakeWM : XWindowManager {
        val maps = mutableListOf<Int>()
        val configures = mutableListOf<String>()
        var autoMap = true
        var document: XDocument? = null
        override fun mapRequested(windowId: Int) {
            maps.add(windowId)
            if (autoMap) document?.mapWindow(windowId)
        }

        override fun configureRequested(windowId: Int, x: Int?, y: Int?, width: Int?, height: Int?, borderWidth: Int?) {
            configures.add("$windowId $x,$y ${width}x$height")
            document?.configure(windowId, x, y, width, height, borderWidth)
        }
    }

    @Test
    fun `the in-process WM receives map and configure requests`() {
        val wm = FakeWM().also { it.document = document }
        document.windowManager = wm
        val root = document.window(rootId)!!
        root.eventMask = root.eventMask or EventMask.SUBSTRUCTURE_REDIRECT

        val client = XConnection(document)
        val win = rootId + 21
        send(client, requests.createWindow(win, rootId, 0, 0, 300, 200), requests.mapWindow(win))
        assertEquals(listOf(win), wm.maps)
        assertTrue(document.window(win)!!.mapped)

        send(client, requests.configureWindow(win, 0x000F, 1, 2, 400, 250))
        assertEquals(listOf("$win 1,2 400x250"), wm.configures)
        assertEquals(400, document.window(win)!!.width)
    }

    // --- drawing reaches the display list ------------------------------------

    @Test
    fun `putImage carries the wire depth, not the format byte`() {
        val lists = XDisplayList()
        val client = XConnection(document, lists)
        val win = rootId + 31
        send(client, requests.createWindow(win, rootId, 0, 0, 2, 1), requests.mapWindow(win))
        // One red pixel (ZPixmap BGRX) + one green pixel.
        val pixels = byteArrayOf(0, 0, 0xFF.toByte(), 0, 0, 0xFF.toByte(), 0, 0)
        val gc = rootId + 900
        send(client, requests.createGc(gc, win, 0), requests.putImage(win, gc, 2, 1, 0, 0, 24, pixels))
        val image = lists.ops(win).filterIsInstance<DrawOp.Image>().single()
        assertEquals(24, image.depth)
        assertArrayEquals(intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt()), image.argb)
    }

    @Test
    fun `fills and copies land on their drawables`() {
        val lists = XDisplayList()
        val client = XConnection(document, lists)
        val win = rootId + 41
        val pixmap = rootId + 42
        send(client, requests.createWindow(win, rootId, 0, 0, 64, 64), requests.mapWindow(win))
        val gc = rootId + 901
        send(
            client,
            requests.createGc(gc, win, 0x112233),
            requests.polyFillRectangle(pixmap, gc, 1, 2, 3, 4),
            requests.copyArea(pixmap, win, gc, 1, 2, 10, 20, 3, 4),
        )
        val fill = lists.ops(pixmap).filterIsInstance<DrawOp.Fill>().single()
        assertEquals(0xFF112233.toInt(), fill.argb)
        val copy = lists.ops(win).filterIsInstance<DrawOp.Copy>().single()
        assertEquals(pixmap, copy.srcDrawable)
        assertEquals(10, copy.dstX)
        assertEquals(4, copy.height)
    }
}

/**
 * D1b smoke test: scripts what `explorer.exe /desktop` does over the wire -
 * connect, create a top-level, ask the WM to map it, receive Expose, paint the
 * desktop with PutImage - and asserts the shell side ends up correct.
 */
class ExplorerSmokeTest {

    private val requests = Requests()
    private val rootId = X11.RESOURCE_ID_BASE

    @After
    fun tearDown() {
        XServer.attachWindowManager(null)
    }

    @Test
    fun `explorer startup sequence reaches the screen`() {
        val server = XServer
        val lists = server.displayList
        server.attachWindowManager(ShellWindowManager.forServer(server))
        val document = server.document
        val connection = XConnection(document, lists)
        server.register(connection)
        try {
            explorerStartup(server, document, lists, connection)
        } finally {
            server.unregister(connection)
        }
    }

    private fun explorerStartup(
        server: XServer,
        document: XDocument,
        lists: XDisplayList,
        connection: XConnection,
    ) {
        val clientMask = EventMask.EXPOSURE or EventMask.STRUCTURE_NOTIFY or
            EventMask.KEY_PRESS or EventMask.KEY_RELEASE or
            EventMask.BUTTON_PRESS or EventMask.BUTTON_RELEASE or EventMask.POINTER_MOTION
        val desktop = document.newId()
        val gc = document.newId()

        fun send(vararg bytes: ByteArray): List<ByteArray> {
            bytes.forEach { connection.onBytes(it) }
            return connection.drainOutbox()
        }

        // 1. Explorer creates its desktop window and asks to map it.
        send(requests.createWindow(desktop, rootId, 0, 0, 1024, 768, clientMask))
        assertFalse(document.window(desktop)!!.mapped) // WM holds the map
        val mapped = send(requests.mapWindow(desktop))

        // 2. The shell WM placed and mapped it; the client sees MapNotify + Expose.
        val window = document.window(desktop)!!
        assertTrue(window.mapped)
        assertTrue("WM must place the window on screen", window.x in 0..document.screen.widthPx)
        assertNotNull("mapped client must receive Expose", mapped.firstOrNull { it[0].toInt() == X11.EV_EXPOSE })
        assertNotNull("WM map must announce MapNotify", mapped.firstOrNull { it[0].toInt() == X11.EV_MAP_NOTIFY })

        // 3. Explorer paints: background fill + a ZPixmap blit of its rendered desktop.
        send(requests.createGc(gc, desktop, 0x008080))
        val pixels = ByteArray(4 * 4 * 2) // 4x2 pixels, teal in BGRX
        for (i in pixels.indices step 4) {
            pixels[i] = 0x80.toByte(); pixels[i + 1] = 0x80.toByte(); pixels[i + 2] = 0
        }
        send(
            requests.polyFillRectangle(desktop, gc, 0, 0, 1024, 768),
            requests.putImage(desktop, gc, 4, 2, 100, 50, 24, pixels),
        )
        val ops = lists.ops(desktop)
        val fill = ops.filterIsInstance<DrawOp.Fill>().single()
        assertEquals(0xFF008080.toInt(), fill.argb)
        val image = ops.filterIsInstance<DrawOp.Image>().single()
        assertEquals(100, image.x)
        assertEquals(0xFF008080.toInt(), image.argb[0])

        // 4. Pointer and keyboard input reach the client through the server dispatch.
        val px = window.x + 20
        val py = window.y + 20
        server.dispatchPointerMove(px, py)
        server.dispatchButton(px, py, 1, true, X11.MASK_BUTTON1)
        server.dispatchKey(38, 0x61, true, 0) // 'a'
        val input = connection.drainOutbox()
        assertTrue(input.any { it[0].toInt() == X11.EV_MOTION_NOTIFY })
        assertTrue(input.any { it[0].toInt() == X11.EV_BUTTON_PRESS })
        assertTrue(input.any { it[0].toInt() == X11.EV_KEY_PRESS })

        // 5. The taskbar view of the world: one mapped top-level window.
        assertEquals(listOf(desktop), document.topLevelWindows().map { it.id })

        // 6. Close like the shell's title bar does, then clean up.
        server.requestWindowClose(desktop)
        val delete = connection.drainOutbox().single { it[0].toInt() == X11.EV_CLIENT_MESSAGE }
        assertEquals(desktop, delete.intLE(4))
        send(requests.build(X11.REQ_DESTROY_WINDOW) { it.int(desktop) })
        assertNull(document.window(desktop))
    }
}
