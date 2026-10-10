package dev.win11we.shell.x11

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Wire builders for the selection/clipboard tests (same conventions as XProtocolTest). */
private class Sel {
    fun build(opcode: Int, data: Int = 0, body: (XWriter) -> Unit = {}): ByteArray {
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

    fun mapWindow(id: Int) = build(X11.REQ_MAP_WINDOW) { it.int(id) }
    fun destroyWindow(id: Int) = build(X11.REQ_DESTROY_WINDOW) { it.int(id) }

    fun setSelectionOwner(window: Int, selection: Int) = build(X11.REQ_SET_SELECTION_OWNER) { writer ->
        writer.int(window); writer.int(selection); writer.int(0)
    }

    fun getSelectionOwner(selection: Int) = build(X11.REQ_GET_SELECTION_OWNER) { it.int(selection) }

    fun convertSelection(requestor: Int, selection: Int, target: Int, property: Int) =
        build(X11.REQ_CONVERT_SELECTION) { writer ->
            writer.int(requestor); writer.int(selection); writer.int(target); writer.int(property); writer.int(0)
        }

    fun changeProperty(window: Int, property: Int, type: Int, format: Int, data: ByteArray) =
        build(X11.REQ_CHANGE_PROPERTY) { writer ->
            writer.int(window); writer.int(property); writer.int(type)
            writer.byte(format); writer.pad(3)
            writer.int(if (format == 0) 0 else data.size / (format / 8))
            writer.bytes(data)
            writer.pad(X11.pad4(data.size) - data.size)
        }

    /** The ICCCM idiom: XSendEvent(dest, False, 0, event) - empty mask, no propagate. */
    fun sendEvent(dest: Int, event: ByteArray) = build(X11.REQ_SEND_EVENT, 0) { writer ->
        writer.int(dest); writer.int(0); writer.bytes(event)
    }

    fun selectionNotify(requestor: Int, selection: Int, target: Int, property: Int): ByteArray =
        XWriter(true).apply {
            byte(X11.EV_SELECTION_NOTIFY); byte(0); short(0)
            int(0) // time
            int(requestor); int(selection); int(target); int(property)
            pad(8)
        }.toByteArray().copyOf(32)

    fun clientMessage(window: Int, atom: Int): ByteArray = XWriter(true).apply {
        byte(X11.EV_CLIENT_MESSAGE); byte(32); short(0)
        int(window); int(atom); pad(20)
    }.toByteArray().copyOf(32)
}

private fun ByteArray.intLE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

/**
 * P1 clipboard plumbing at the wire level: server-wide selection ownership,
 * cross-client SelectionRequest routing, the ICCCM mask-0 SendEvent idiom, the
 * shell bridge's inline conversions (text + TARGETS + honest None), ownership
 * lifecycle, and the XServer-side guest-copy push through a fake bridge.
 */
class XSelectionsTest {

    private lateinit var document: XDocument
    private val s = Sel()
    private val rootId = X11.RESOURCE_ID_BASE
    private var clipboard = 0
    private var utf8 = 0
    private var targets = 0
    private var string = 0

    @Before
    fun setUp() {
        document = XServer.defaultDocument(1280, 800)
        clipboard = document.atomId("CLIPBOARD")!!
        utf8 = document.atomId("UTF8_STRING")!!
        targets = document.atomId("TARGETS")!!
        string = document.atomId("STRING")!!
    }

    @After
    fun tearDown() {
        XServer.setClipboardBridge(null)
    }

    private fun send(connection: XConnection, vararg bytes: ByteArray): List<ByteArray> {
        bytes.forEach { connection.onBytes(it) }
        return connection.drainOutbox()
    }

    private fun createMapped(connection: XConnection, id: Int): Int {
        send(connection, s.createWindow(id, rootId, 0, 0, 100, 100), s.mapWindow(id))
        connection.drainOutbox()
        return id
    }

    @Test
    fun `the bridge atoms are pre-interned and stable across connections`() {
        assertTrue(clipboard > 8) // beyond the eight protocol-numbered atoms
        val a = XConnection(document)
        val out = send(a, s.build(X11.REQ_INTERN_ATOM) { writer ->
            writer.short("CLIPBOARD".length); writer.pad(2); writer.paddedString("CLIPBOARD")
        })
        val reply = out.single { it[0].toInt() == 1 }
        assertEquals(clipboard, reply.intLE(8)) // the same id every client gets
        assertNotNull(document.atomId("TARGETS"))
        assertNotNull(document.atomId("TEXT"))
    }

    @Test
    fun `selection ownership is server-wide`() {
        val a = XConnection(document)
        val b = XConnection(document)
        val winA = createMapped(a, rootId + 1001)
        createMapped(b, rootId + 1002)
        send(a, s.setSelectionOwner(winA, clipboard))
        val owner = send(b, s.getSelectionOwner(clipboard)).single { it[0].toInt() == 1 }
        assertEquals(winA, owner.intLE(8))
        // None clears.
        send(b, s.setSelectionOwner(0, clipboard))
        val cleared = send(b, s.getSelectionOwner(clipboard)).single { it[0].toInt() == 1 }
        assertEquals(0, cleared.intLE(8))
    }

    @Test
    fun `a conversion is routed to the owning client and the answer reaches the requestor`() {
        val owner = XConnection(document)
        val requestor = XConnection(document)
        val winO = createMapped(owner, rootId + 1010)
        val winR = createMapped(requestor, rootId + 1011)
        send(owner, s.setSelectionOwner(winO, clipboard))
        owner.drainOutbox(); requestor.drainOutbox()

        // The requestor converts; the REQUEST lands on the owner's connection.
        requestor.onBytes(s.convertSelection(winR, clipboard, utf8, winR))
        val routed = owner.drainOutbox().single { it[0].toInt() == X11.EV_SELECTION_REQUEST }
        assertEquals(winR, routed.intLE(8)) // requestor
        assertEquals(clipboard, routed.intLE(12))
        assertEquals(utf8, routed.intLE(16))
        assertTrue("the requestor must not be answered by the ownerless path",
            requestor.drainOutbox().none { it[0].toInt() == X11.EV_SELECTION_NOTIFY })

        // The owner answers the way libX11 does: property on the requestor window,
        // then SelectionNotify via SendEvent with an EMPTY mask (the ICCCM idiom).
        val text = "hello clipboard".toByteArray()
        send(
            owner,
            s.changeProperty(winR, utf8, utf8, 8, text),
            s.sendEvent(winR, s.selectionNotify(winR, clipboard, utf8, utf8)),
        )
        val notify = requestor.drainOutbox().single { (it[0].toInt() and 0x7F) == X11.EV_SELECTION_NOTIFY }
        assertEquals(0x80 or X11.EV_SELECTION_NOTIFY, notify[0].toInt() and 0xFF) // send-event flag
        assertEquals(utf8, notify.intLE(20)) // property carrying the data
        assertArrayEquals(text, document.property(winR, utf8)!!.data)
    }

    @Test
    fun `the shell bridge answers conversions inline with clipboard text`() {
        val client = XConnection(document)
        val win = createMapped(client, rootId + 1020)
        document.clipboardTextProvider = { atom ->
            when (document.atomName(atom)) {
                "UTF8_STRING", "TEXT" -> "from android".toByteArray(Charsets.UTF_8)
                "STRING" -> "from android".toByteArray(Charsets.ISO_8859_1)
                else -> null
            }
        }
        document.reclaimSelectionsToShell()

        val out = send(client, s.convertSelection(win, clipboard, utf8, win))
        val notify = out.single { it[0].toInt() == X11.EV_SELECTION_NOTIFY }
        assertEquals(win, notify.intLE(20)) // property = the requested one
        val property = document.property(win, win)!!
        assertEquals(utf8, property.type)
        assertEquals(8, property.format)
        assertEquals("from android", String(property.data, Charsets.UTF_8))
    }

    @Test
    fun `TARGETS lists the text targets and unknown targets convert to None`() {
        val client = XConnection(document)
        val win = createMapped(client, rootId + 1030)
        document.clipboardTextProvider = { atom ->
            if (document.atomName(atom) == "UTF8_STRING") "x".toByteArray() else null
        }
        document.reclaimSelectionsToShell()

        val prop = rootId + 1031
        val out = send(client, s.convertSelection(win, clipboard, targets, prop))
        val notify = out.single { it[0].toInt() == X11.EV_SELECTION_NOTIFY }
        assertEquals(prop, notify.intLE(20))
        val data = document.property(win, prop)!!
        assertEquals(32, data.format)
        assertEquals(4, data.data.size / 4)
        assertEquals(targets, data.data.intLE(0))
        assertEquals(string, data.data.intLE(4))
        assertEquals(utf8, data.data.intLE(8))
        assertEquals(document.atomId("TEXT"), data.data.intLE(12))

        // An unsupported rich target is refused honestly (property None), not guessed at.
        val html = document.internAtom("text/html")
        val refused = send(client, s.convertSelection(win, clipboard, html, prop))
            .single { it[0].toInt() == X11.EV_SELECTION_NOTIFY }
        assertEquals(0, refused.intLE(20))
    }

    @Test
    fun `without an owner a conversion answers None instead of hanging`() {
        val client = XConnection(document)
        val win = createMapped(client, rootId + 1040)
        val out = send(client, s.convertSelection(win, clipboard, utf8, win))
        assertEquals(0, out.single { it[0].toInt() == X11.EV_SELECTION_NOTIFY }.intLE(20))
    }

    @Test
    fun `ownership dies with the window and the shell reclaims when bridged`() {
        val client = XConnection(document)
        val win = createMapped(client, rootId + 1050)
        send(client, s.setSelectionOwner(win, clipboard))
        assertEquals(win, document.selectionOwner(clipboard)?.window)

        send(client, s.destroyWindow(win))
        assertNull("no bridge attached: ownership simply ends", document.selectionOwner(clipboard))

        // With the bridge attached, the shell takes the selection back instead.
        val win2 = createMapped(client, rootId + 1051)
        document.clipboardTextProvider = { null }
        document.reclaimSelectionsToShell()
        send(client, s.setSelectionOwner(win2, clipboard))
        assertEquals(win2, document.selectionOwner(clipboard)?.window)
        send(client, s.destroyWindow(win2))
        assertEquals(XDocument.SHELL_WINDOW_ID, document.selectionOwner(clipboard)?.window)
        document.clipboardTextProvider = null
    }

    @Test
    fun `a mask-0 SendEvent delivers ClientMessages to windows that selected nothing`() {
        val sender = XConnection(document)
        val target = XConnection(document)
        val win = createMapped(target, rootId + 1060) // no event mask at all
        val atom = document.internAtom("WM_DELETE_WINDOW")
        send(sender, s.sendEvent(win, s.clientMessage(win, atom)))
        val message = target.drainOutbox().single { (it[0].toInt() and 0x7F) == X11.EV_CLIENT_MESSAGE }
        assertEquals(win, message.intLE(4))
        assertEquals(atom, message.intLE(8))
    }

    // --- the server-side push: guest copy -> Android clipboard -----------------

    private class FakeBridge : XClipboardBridge {
        val written = mutableListOf<String>()
        var text: String? = null
        override fun readText(): String? = text
        override fun writeText(text: String) {
            written.add(text)
        }
    }

    @Test
    fun `a guest copy is fetched over the wire and pushed to the bridge`() {
        val server = XServer
        val doc = server.document
        val bridge = FakeBridge()
        server.setClipboardBridge(bridge)
        val guest = XConnection(doc, RecordingSink())
        server.register(guest)
        val win = rootId + 1070
        try {
            val clip = doc.atomId("CLIPBOARD")!!
            val utf8Atom = doc.atomId("UTF8_STRING")!!
            send(guest, s.createWindow(win, doc.rootId, 0, 0, 50, 50), s.mapWindow(win))
            guest.drainOutbox()

            // 1. The guest claims CLIPBOARD (what Firefox does on Ctrl+C).
            //    The claim synchronously triggers the server's internal fetch, so
            //    the SelectionRequest is already in this send()'s drain.
            val claimed = send(guest, s.setSelectionOwner(win, clip))
            // 2. The server's internal connection asked the guest for UTF8_STRING.
            val request = claimed.single { it[0].toInt() == X11.EV_SELECTION_REQUEST }
            assertEquals(XDocument.SHELL_WINDOW_ID, request.intLE(8)) // requestor
            assertEquals(utf8Atom, request.intLE(16)) // target

            // 3. The guest answers: property on the shell window + mask-0 notify.
            send(
                guest,
                s.changeProperty(XDocument.SHELL_WINDOW_ID, utf8Atom, utf8Atom, 8, "guest text".toByteArray()),
                s.sendEvent(XDocument.SHELL_WINDOW_ID,
                    s.selectionNotify(XDocument.SHELL_WINDOW_ID, clip, utf8Atom, utf8Atom)),
            )
            assertEquals(listOf("guest text"), bridge.written)
            assertEquals("guest text", server.lastGuestClipboardText())
            // The one-shot property is consumed, not left on the shell window.
            assertNull(doc.property(XDocument.SHELL_WINDOW_ID, utf8Atom))

            // 4. Dedup: the same text claimed again (PRIMARY+CLIPBOARD) pushes once.
            val claimedPrimary = send(guest, s.setSelectionOwner(win, doc.atomId("PRIMARY")!!))
            val request2 = claimedPrimary.single { it[0].toInt() == X11.EV_SELECTION_REQUEST }
            send(
                guest,
                s.changeProperty(XDocument.SHELL_WINDOW_ID, utf8Atom, utf8Atom, 8, "guest text".toByteArray()),
                s.sendEvent(XDocument.SHELL_WINDOW_ID,
                    s.selectionNotify(XDocument.SHELL_WINDOW_ID, doc.atomId("PRIMARY")!!, utf8Atom, utf8Atom)),
            )
            assertEquals("an identical re-push must not spam the clipboard", listOf("guest text"), bridge.written)
            @Suppress("UNUSED_EXPRESSION")
            request2

            // 5. A guest paste is answered inline from the bridge.
            bridge.text = "android text"
            server.androidClipboardChanged() // the listener path reclaims ownership
            val prop = rootId + 1071
            val out = send(guest, s.convertSelection(win, clip, utf8Atom, prop))
            val notify = out.single { it[0].toInt() == X11.EV_SELECTION_NOTIFY }
            assertEquals(prop, notify.intLE(20))
            assertEquals("android text", String(doc.property(win, prop)!!.data, Charsets.UTF_8))
        } finally {
            // Keep the shared server document clean for the other suites
            // (ExplorerSmokeTest asserts on its top-level window list).
            send(guest, s.destroyWindow(win))
            server.unregister(guest)
            server.setClipboardBridge(null)
        }
    }

    @Test
    fun `detaching the bridge leaves no provider and no shell ownership`() {
        val server = XServer
        val bridge = FakeBridge()
        server.setClipboardBridge(bridge)
        assertNotNull(server.document.clipboardTextProvider)
        assertEquals(XDocument.SHELL_WINDOW_ID, server.document.selectionOwner(
            server.document.atomId("CLIPBOARD")!!)?.window)
        server.setClipboardBridge(null)
        assertNull(server.document.clipboardTextProvider)
        assertNull(server.lastGuestClipboardText())
        assertNull("detaching must not leave shell-owned selections behind",
            server.document.selectionOwner(server.document.atomId("CLIPBOARD")!!))
    }
}
