package dev.win11we.shell.x11

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The coordinate model the pass-through desktop session lives on: X child
 * windows are PARENT-RELATIVE, so hit-testing, input event coordinates, warp
 * clamping and the XFIXES region/shape conversions must resolve absolute
 * origins through the parent chain - openbox reparents every client into a
 * frame, and Wine's `explorer.exe /desktop` reparents the same way.
 *
 * Plus the two exposure rules a WM-less (or guest-WM) session needs: a direct
 * MapWindow generates the Expose a GTK3 client waits for before its first
 * paint, and mapping a parent exposes its already-mapped children.
 */
class XChildCoordinatesTest {

    private val rootId = X11.RESOURCE_ID_BASE
    private lateinit var document: XDocument
    private lateinit var connection: XConnection

    private val parent = rootId + 300
    private val child = rootId + 301
    private val frame = rootId + 302

    private fun req(opcode: Int, data: Int = 0, body: (XWriter) -> Unit = {}): ByteArray {
        val bw = XWriter(true)
        body(bw)
        val bb = bw.toByteArray()
        val w = XWriter(true)
        w.byte(opcode)
        w.byte(data)
        val total = 4 + bb.size
        val padded = X11.pad4(total)
        w.short(padded / 4)
        w.bytes(bb)
        w.pad(padded - total)
        return w.toByteArray()
    }

    private fun createWindow(id: Int, p: Int, x: Int, y: Int, w: Int, h: Int, mask: Int = 0, value: Int = 0) =
        req(X11.REQ_CREATE_WINDOW, 24) { writer ->
            writer.int(id); writer.int(p)
            writer.short(x); writer.short(y); writer.short(w); writer.short(h)
            writer.short(0); writer.short(0); writer.int(0x21); writer.int(mask)
            if (mask != 0) writer.int(value)
        }

    private fun mapWindow(id: Int) = req(X11.REQ_MAP_WINDOW) { it.int(id) }

    private fun reparentWindow(id: Int, newParent: Int, x: Int, y: Int) =
        req(X11.REQ_REPARENT_WINDOW) { writer ->
            writer.int(id); writer.int(newParent); writer.short(x); writer.short(y)
        }

    private fun getGeometry(id: Int) = req(X11.REQ_GET_GEOMETRY) { it.int(id) }

    @Before
    fun setUp() {
        document = XServer.defaultDocument(1280, 800)
        connection = XConnection(document)
        // parent at (100,100) 200x200, child at (10,20) inside it -> absolute (110,120).
        send(
            createWindow(parent, rootId, 100, 100, 200, 200),
            mapWindow(parent),
            createWindow(child, parent, 10, 20, 50, 50),
            mapWindow(child),
        )
        drain()
    }

    private fun send(vararg requests: ByteArray) {
        requests.forEach { connection.onBytes(it) }
    }

    private fun drain(): List<ByteArray> = connection.drainOutbox()
    private fun List<ByteArray>.events(type: Int) = filter { it[0].toInt() == type }
    private fun List<ByteArray>.replies() = filter { it[0].toInt() == 1 }

    private fun ByteArray.intLE(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or ((this[offset + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.shortLESigned(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)).toShort().toInt()

    @Test
    fun `absoluteOrigin resolves through the parent chain`() {
        val childWindow = document.window(child)!!
        assertEquals(listOf(110, 120), document.absoluteOrigin(childWindow).toList())
        assertEquals(listOf(100, 100), document.absoluteOrigin(document.window(parent)!!).toList())
    }

    @Test
    fun `hit-testing finds children at their ABSOLUTE position`() {
        assertEquals(child, document.windowAt(115, 125)?.id) // inside child (abs 110..159, 120..169)
        assertEquals(parent, document.windowAt(105, 115)?.id) // parent, outside child
        assertEquals(parent, document.windowAt(180, 200)?.id) // parent to the right of child
        assertEquals(rootId, document.windowAt(5, 5)?.id) // only the root
    }

    @Test
    fun `a ButtonPress on a child carries child-relative event coordinates`() {
        // The child selects for button presses (ButtonPressMask = 1<<2 = 4).
        send(
            req(X11.REQ_CHANGE_WINDOW_ATTRIBUTES) { writer ->
                writer.int(child); writer.int(0x800); writer.int(4)
            },
        )
        drain()
        connection.onButton(120, 130, 1, true, 0)
        val event = drain().events(X11.EV_BUTTON_PRESS).single()
        assertEquals(child, event.intLE(12)) // event window: the child
        assertEquals(rootId, event.intLE(8)) // root
        assertEquals(120, event.shortLESigned(20)) // root-x
        assertEquals(130, event.shortLESigned(22)) // root-y
        assertEquals(10, event.shortLESigned(24)) // event-x: 120 - 110 (absolute child origin)
        assertEquals(10, event.shortLESigned(26)) // event-y: 130 - 120
    }

    @Test
    fun `ReparentWindow moves the absolute origin and hit-testing follows`() {
        send(
            createWindow(frame, rootId, 300, 300, 100, 100),
            mapWindow(frame),
            reparentWindow(child, frame, 5, 25),
        )
        drain() // ReparentNotify routing is structure-event territory (XCompatTest)
        // The child's absolute origin is now frame(300,300) + (5,25).
        assertEquals(listOf(305, 325), document.absoluteOrigin(document.window(child)!!).toList())
        assertEquals(child, document.windowAt(310, 330)?.id)
        assertEquals(frame, document.windowAt(302, 305)?.id) // frame border area
        assertEquals(parent, document.windowAt(115, 125)?.id) // old spot: parent only now
        // GetGeometry stays PARENT-relative, per spec.
        send(getGeometry(child))
        val reply = drain().replies().single()
        assertEquals(frame, reply.intLE(8))
        assertEquals(5, reply.shortLESigned(12))
        assertEquals(25, reply.shortLESigned(14))
    }

    @Test
    fun `a direct map generates Expose - the first-paint trigger with no shell WM`() {
        val exposed = rootId + 310
        // CWEventMask (0x800) with Exposure (1<<15 = 0x8000).
        send(createWindow(exposed, rootId, 10, 10, 40, 30, mask = 0x800, value = 0x8000))
        drain()
        send(mapWindow(exposed))
        val expose = drain().events(X11.EV_EXPOSE).single()
        assertEquals(exposed, expose.intLE(4))
        // The expose rect is WINDOW-relative: 0,0 + full size.
        assertEquals(0, expose.shortLESigned(8))
        assertEquals(0, expose.shortLESigned(10))
        assertEquals(40, expose.shortLESigned(12))
        assertEquals(30, expose.shortLESigned(14))
    }

    @Test
    fun `mapping a parent exposes its already-mapped children`() {
        val p2 = rootId + 320
        val c2 = rootId + 321
        send(
            createWindow(p2, rootId, 400, 10, 80, 80, mask = 0x800, value = 0x8000),
            createWindow(c2, p2, 5, 5, 20, 20, mask = 0x800, value = 0x8000),
            mapWindow(c2), // mapped while the parent is not: not viewable, no Expose yet
        )
        assertTrue(drain().events(X11.EV_EXPOSE).isEmpty())
        send(mapWindow(p2)) // the parent becomes viewable -> both get Expose
        val exposedWindows = drain().events(X11.EV_EXPOSE).map { it.intLE(4) }.toSet()
        assertTrue(exposedWindows.contains(p2))
        assertTrue(exposedWindows.contains(c2))
    }

    @Test
    fun `XFIXES input shapes on children use absolute coordinates`() {
        val region = rootId + 330
        fun xfixes(minor: Int, body: (XWriter) -> Unit) = req(XFixes.MAJOR_OPCODE, minor, body)
        send(xfixes(XFixes.MINOR_CREATE_REGION) { writer ->
            writer.int(region)
            writer.short(0); writer.short(0); writer.short(20); writer.short(20)
        })
        send(xfixes(XFixes.MINOR_SET_WINDOW_SHAPE_REGION) { writer ->
            writer.int(child); writer.byte(XFixes.SHAPE_INPUT); writer.pad(3)
            writer.short(0); writer.short(0); writer.int(region)
        })
        assertTrue(drain().replies().isEmpty())
        // The shape covers absolute (110..129, 120..139) - the child's real position.
        assertEquals(child, document.windowAt(115, 125)?.id)
        // (150,160) is inside the child's rect but outside its shape -> the parent.
        assertEquals(parent, document.windowAt(150, 160)?.id)
    }

    @Test
    fun `CreateRegionFromWindow reports the child's absolute bounds`() {
        val region = rootId + 340
        send(
            req(XFixes.MAJOR_OPCODE, XFixes.MINOR_CREATE_REGION_FROM_WINDOW) { writer ->
                writer.int(region); writer.int(child)
                writer.byte(XFixes.WINDOW_REGION_BOUNDING); writer.pad(3)
            },
            req(XFixes.MAJOR_OPCODE, XFixes.MINOR_FETCH_REGION) { it.int(region) },
        )
        val reply = drain().replies().single()
        assertEquals(110, reply.shortLESigned(32))
        assertEquals(120, reply.shortLESigned(34))
        assertEquals(50, reply.shortLESigned(36))
        assertEquals(50, reply.shortLESigned(38))
        assertNotNull(document.window(child))
    }
}
