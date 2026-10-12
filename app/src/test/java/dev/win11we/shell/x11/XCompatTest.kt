package dev.win11we.shell.x11

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * D1c wire tests: the requests a real libX11/Wine client sends that were
 * missing or mis-numbered before (opcode tail block 92+, focus, grabs, warps,
 * fonts, colours, cursors, SendEvent, GetImage, XTest, the control replies).
 * Layouts verified against xorgproto 2024.1 (Xproto.h, xtestproto.h).
 */
private class Compat {
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
    fun changeAttributes(id: Int, mask: Int, vararg values: Int) =
        build(X11.REQ_CHANGE_WINDOW_ATTRIBUTES) { writer ->
            writer.int(id); writer.int(mask); values.forEach { writer.int(it) }
        }

    fun setSelection(window: Int, atom: Int) = build(X11.REQ_SET_SELECTION_OWNER) { writer ->
        writer.int(window); writer.int(atom); writer.int(0)
    }

    fun setInputFocus(window: Int, revertTo: Int = 1) = build(X11.REQ_SET_INPUT_FOCUS, revertTo) { writer ->
        writer.int(window); writer.int(0)
    }

    fun getInputFocus() = build(X11.REQ_GET_INPUT_FOCUS)

    fun warpPointer(dstX: Int, dstY: Int, src: Int = 0, dst: Int = 0) = build(X11.REQ_WARP_POINTER) { writer ->
        writer.int(src); writer.int(dst)
        writer.short(0); writer.short(0); writer.short(0); writer.short(0)
        writer.short(dstX); writer.short(dstY)
    }

    fun queryKeymap() = build(X11.REQ_QUERY_KEYMAP)

    fun grabPointer(window: Int, mask: Int, ownerEvents: Boolean = false) =
        build(X11.REQ_GRAB_POINTER, if (ownerEvents) 1 else 0) { writer ->
            writer.int(window); writer.short(mask); writer.byte(0); writer.byte(0)
            writer.int(0); writer.int(0); writer.int(0) // confine, cursor, time
        }

    fun ungrabPointer() = build(X11.REQ_UNGRAB_POINTER) { it.int(0) }

    fun grabKeyboard(window: Int, ownerEvents: Boolean = false) =
        build(X11.REQ_GRAB_KEYBOARD, if (ownerEvents) 1 else 0) { writer ->
            writer.int(window); writer.int(0); writer.byte(0); writer.byte(0); writer.short(0)
        }

    fun ungrabKeyboard() = build(X11.REQ_UNGRAB_KEYBOARD) { it.int(0) }

    fun grabButton(window: Int, mask: Int, button: Int, modifiers: Int = PassiveGrab.ANY_MODIFIER) =
        build(X11.REQ_GRAB_BUTTON, 0) { writer ->
            writer.int(window); writer.short(mask); writer.byte(0); writer.byte(0)
            writer.int(0); writer.int(0)
            writer.byte(button); writer.pad(1); writer.short(modifiers)
        }

    fun openFont(fid: Int, name: String) = build(X11.REQ_OPEN_FONT) { writer ->
        writer.int(fid); writer.short(name.length); writer.pad(2); writer.paddedString(name)
    }

    fun queryFont(fid: Int) = build(X11.REQ_QUERY_FONT) { it.int(fid) }
    fun closeFont(fid: Int) = build(X11.REQ_CLOSE_FONT) { it.int(fid) }

    fun queryTextExtents(fid: Int, chars: Int) = build(X11.REQ_QUERY_TEXT_EXTENTS, chars and 1) { writer ->
        writer.int(fid)
        repeat(chars) { writer.byte(0); writer.byte('x'.code) } // CHAR2B: byte1, byte2
    }

    fun listFonts(pattern: String) = build(X11.REQ_LIST_FONTS) { writer ->
        writer.short(100); writer.short(pattern.length); writer.paddedString(pattern)
    }

    fun lookupColor(cmap: Int, name: String) = build(X11.REQ_LOOKUP_COLOR) { writer ->
        writer.int(cmap); writer.short(name.length); writer.pad(2); writer.paddedString(name)
    }

    fun allocNamedColor(cmap: Int, name: String) = build(X11.REQ_ALLOC_NAMED_COLOR) { writer ->
        writer.int(cmap); writer.short(name.length); writer.pad(2); writer.paddedString(name)
    }

    fun createGlyphCursor(cid: Int, font: Int, glyph: Int) = build(X11.REQ_CREATE_GLYPH_CURSOR) { writer ->
        writer.int(cid); writer.int(font); writer.int(0)
        writer.short(glyph); writer.short(0)
        writer.short(0); writer.short(0); writer.short(0)
        writer.short(0xFFFF); writer.short(0xFFFF); writer.short(0xFFFF)
    }

    fun queryBestSize(klass: Int, drawable: Int, w: Int, h: Int) = build(X11.REQ_QUERY_BEST_SIZE, klass) { writer ->
        writer.int(drawable); writer.short(w); writer.short(h)
    }

    fun createGc(id: Int, drawable: Int, foreground: Int) = build(X11.REQ_CREATE_GC) { writer ->
        writer.int(id); writer.int(drawable); writer.int(0x00000004); writer.int(foreground)
    }

    fun polyFillRectangle(drawable: Int, gc: Int, x: Int, y: Int, w: Int, h: Int) =
        build(X11.REQ_POLY_FILL_RECTANGLE) { writer ->
            writer.int(drawable); writer.int(gc)
            writer.short(x); writer.short(y); writer.short(w); writer.short(h)
        }

    fun getImage(drawable: Int, x: Int, y: Int, w: Int, h: Int) = build(X11.REQ_GET_IMAGE, 2) { writer ->
        writer.int(drawable); writer.short(x); writer.short(y); writer.short(w); writer.short(h); writer.int(-1)
    }

    fun sendEvent(dest: Int, mask: Int, propagate: Boolean, event: ByteArray) =
        build(X11.REQ_SEND_EVENT, if (propagate) 1 else 0) { writer ->
            writer.int(dest); writer.int(mask); writer.bytes(event)
        }

    fun clientMessage(window: Int, atom: Int) = XWriter(true).apply {
        byte(X11.EV_CLIENT_MESSAGE); byte(32); short(0)
        int(window); int(atom); pad(20)
    }.toByteArray().copyOf(32)

    fun listProperties(window: Int) = build(X11.REQ_LIST_PROPERTIES) { it.int(window) }

    fun changeProperty(window: Int, atom: Int, value: ByteArray) = build(X11.REQ_CHANGE_PROPERTY) { writer ->
        writer.int(window); writer.int(atom); writer.int(4); writer.byte(8); writer.pad(3)
        writer.int(value.size); writer.bytes(value); writer.pad(X11.pad4(value.size) - value.size)
    }

    fun rotateProperties(window: Int, delta: Int, vararg atoms: Int) = build(X11.REQ_ROTATE_PROPERTIES) { writer ->
        writer.int(window); writer.short(atoms.size); writer.short(delta and 0xFFFF)
        atoms.forEach { writer.int(it) }
    }

    fun queryExtension(name: String) = build(X11.REQ_QUERY_EXTENSION) { writer ->
        writer.short(name.length); writer.pad(2); writer.paddedString(name)
    }

    fun listExtensions() = build(X11.REQ_LIST_EXTENSIONS)

    fun xtest(minor: Int, body: (XWriter) -> Unit = {}) = build(X11.EXT_XTEST_OPCODE, minor, body)

    fun xtestFakeInput(eventType: Int, detail: Int, root: Int, rootX: Int, rootY: Int) = xtest(2) { writer ->
        writer.byte(eventType); writer.byte(detail); writer.pad(2)
        writer.int(0) // time: CurrentTime
        writer.int(root); writer.pad(8)
        writer.short(rootX); writer.short(rootY)
        writer.pad(7); writer.byte(0)
    }

    fun getKeyboardControl() = build(X11.REQ_GET_KEYBOARD_CONTROL)
    fun getPointerControl() = build(X11.REQ_GET_POINTER_CONTROL)
    fun setScreenSaver(timeout: Int, interval: Int) = build(X11.REQ_SET_SCREEN_SAVER) { writer ->
        writer.short(timeout); writer.short(interval); writer.byte(1); writer.byte(1); writer.pad(2)
    }

    fun getScreenSaver() = build(X11.REQ_GET_SCREEN_SAVER)
    fun getPointerMapping() = build(X11.REQ_GET_POINTER_MAPPING)
    fun getModifierMapping() = build(X11.REQ_GET_MODIFIER_MAPPING)
    fun listHosts() = build(X11.REQ_LIST_HOSTS)
    fun getMotionEvents(window: Int) = build(X11.REQ_GET_MOTION_EVENTS) { writer ->
        writer.int(window); writer.int(0); writer.int(-1)
    }

    fun circulateWindow(window: Int, place: Int) = build(X11.REQ_CIRCULATE_WINDOW, place) { it.int(window) }

    fun polyLine(drawable: Int, gc: Int, vararg coords: Int) = build(X11.REQ_POLY_LINE) { writer ->
        writer.int(drawable); writer.int(gc); coords.forEach { writer.short(it) }
    }

    fun polySegment(drawable: Int, gc: Int, vararg coords: Int) = build(X11.REQ_POLY_SEGMENT) { writer ->
        writer.int(drawable); writer.int(gc); coords.forEach { writer.short(it) }
    }

    fun polyPoint(drawable: Int, gc: Int, vararg coords: Int) = build(X11.REQ_POLY_POINT) { writer ->
        writer.int(drawable); writer.int(gc); coords.forEach { writer.short(it) }
    }

    fun polyFillArc(drawable: Int, gc: Int, x: Int, y: Int, w: Int, h: Int, a1: Int, a2: Int) =
        build(X11.REQ_POLY_FILL_ARC) { writer ->
            writer.int(drawable); writer.int(gc)
            writer.short(x); writer.short(y); writer.short(w); writer.short(h)
            writer.short(a1); writer.short(a2)
        }

    fun fillPoly(drawable: Int, gc: Int, vararg coords: Int) = build(X11.REQ_FILL_POLY) { writer ->
        writer.int(drawable); writer.int(gc)
        writer.byte(0); writer.byte(0); writer.pad(2) // shape, coord-mode
        coords.forEach { writer.short(it) }
    }

    fun imageText16(drawable: Int, gc: Int, x: Int, y: Int, text: String) =
        build(X11.REQ_IMAGE_TEXT16, text.length) { writer ->
            writer.int(drawable); writer.int(gc); writer.short(x); writer.short(y)
            text.forEach { writer.byte(0); writer.byte(it.code) }
        }

    fun polyText8(drawable: Int, gc: Int, x: Int, y: Int, vararg runs: String) =
        build(X11.REQ_POLY_TEXT8) { writer ->
            writer.int(drawable); writer.int(gc); writer.short(x); writer.short(y)
            runs.forEach { run ->
                writer.byte(run.length); writer.byte(0)
                writer.bytes(run.toByteArray(Charsets.US_ASCII))
            }
        }
}

private fun ByteArray.intLE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

private fun ByteArray.shortLE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

class XCompatTest {

    private lateinit var document: XDocument
    private lateinit var sink: RecordingSink
    private val c = Compat()
    private val rootId = X11.RESOURCE_ID_BASE

    @Before
    fun setUp() {
        document = XServer.defaultDocument(1280, 800)
        sink = RecordingSink()
    }

    private fun connection(sink: XSink = this.sink) = XConnection(document, sink)

    private fun send(connection: XConnection, vararg bytes: ByteArray): List<ByteArray> {
        bytes.forEach { connection.onBytes(it) }
        return connection.drainOutbox()
    }

    private fun List<ByteArray>.replies() = filter { it[0].toInt() == 1 }
    private fun List<ByteArray>.errors() = filter { it[0].toInt() == 0 }
    private fun List<ByteArray>.reply(): ByteArray = replies().single()
    private fun List<ByteArray>.events(code: Int) = filter { (it[0].toInt() and 0x7F) == code }

    private fun createMapped(
        connection: XConnection,
        id: Int,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        mask: Int = 0,
        parent: Int = rootId,
    ) {
        send(connection, c.createWindow(id, parent, x, y, w, h, mask), c.mapWindow(id))
        connection.drainOutbox()
    }

    // --- focus ----------------------------------------------------------------

    @Test
    fun `focus defaults to PointerRoot and keys follow the pointer`() {
        val client = connection()
        val win = rootId + 200
        createMapped(client, win, 10, 10, 100, 100, EventMask.KEY_PRESS)
        assertEquals(XDocument.FOCUS_POINTER_ROOT, document.focus)
        client.onPointerMove(50, 50)
        client.drainOutbox()
        client.onKey(38, 'a'.code, true, 0)
        val press = client.drainOutbox().single { it[0].toInt() == X11.EV_KEY_PRESS }
        assertEquals(win, press.intLE(12))
        assertEquals(38, press[1].toInt()) // detail is the keycode
    }

    @Test
    fun `SetInputFocus moves keys and emits FocusIn and FocusOut`() {
        val client = connection()
        val win = rootId + 201
        createMapped(client, win, 10, 10, 100, 100, EventMask.KEY_PRESS or EventMask.FOCUS_CHANGE)
        val out = send(client, c.setInputFocus(win, revertTo = 1))
        assertEquals(win, document.focus)
        val focusIn = out.single { it[0].toInt() == X11.EV_FOCUS_IN }
        assertEquals(win, focusIn.intLE(8))
        assertEquals(6, focusIn[1].toInt()) // detail: NotifyPointerRoot (came from PointerRoot)

        // The pointer is far away, yet the key still lands on the focus window.
        client.onPointerMove(900, 700)
        client.drainOutbox()
        client.onKey(38, 'a'.code, true, 0)
        assertEquals(win, client.drainOutbox().single { it[0].toInt() == X11.EV_KEY_PRESS }.intLE(12))

        val back = send(client, c.setInputFocus(XDocument.FOCUS_POINTER_ROOT))
        val focusOut = back.single { it[0].toInt() == X11.EV_FOCUS_OUT }
        assertEquals(win, focusOut.intLE(8))
        val focus = send(client, c.getInputFocus()).reply()
        assertEquals(XDocument.FOCUS_POINTER_ROOT, focus.intLE(8))
        assertEquals(1, focus[1].toInt()) // revert-to
    }

    @Test
    fun `destroying the focused window reverts focus`() {
        val client = connection()
        val win = rootId + 202
        createMapped(client, win, 0, 0, 50, 50, EventMask.FOCUS_CHANGE)
        send(client, c.setInputFocus(win, revertTo = 1))
        assertEquals(win, document.focus)
        send(client, c.build(X11.REQ_DESTROY_WINDOW) { it.int(win) })
        assertEquals(XDocument.FOCUS_POINTER_ROOT, document.focus)
    }

    @Test
    fun `QueryKeymap reports the physical key state`() {
        val client = connection()
        val win = rootId + 203
        createMapped(client, win, 0, 0, 200, 200, EventMask.KEY_PRESS)
        client.onPointerMove(10, 10)
        client.onKey(38, 'a'.code, true, 0)
        val map = send(client, c.queryKeymap()).reply()
        assertEquals(40, map.size)
        assertEquals(0x40, map[8 + 4].toInt() and 0xFF) // keycode 38 -> byte 4, bit 6
        client.onKey(38, 'a'.code, false, 0)
        val cleared = send(client, c.queryKeymap()).reply()
        assertEquals(0, cleared[8 + 4].toInt() and 0xFF)
    }

    // --- grabs ------------------------------------------------------------------

    @Test
    fun `an active pointer grab redirects events away from other clients`() {
        val grabber = connection()
        val victim = connection()
        val gw = rootId + 210
        val vw = rootId + 211
        val mask = EventMask.POINTER_MOTION or EventMask.BUTTON_PRESS or EventMask.BUTTON_RELEASE
        createMapped(grabber, gw, 10, 10, 100, 100, mask)
        createMapped(victim, vw, 10, 10, 100, 100, mask)

        val out = send(grabber, c.grabPointer(gw, mask))
        assertEquals(0, out.reply()[1].toInt()) // GrabSuccess
        assertNotNull(document.pointerGrab)

        // The victim's connection sees nothing while the grab is active.
        grabber.drainOutbox(); victim.drainOutbox()
        grabber.onPointerMove(50, 50)
        victim.onPointerMove(50, 50)
        assertEquals(1, grabber.drainOutbox().count { it[0].toInt() == X11.EV_MOTION_NOTIFY })
        assertTrue(victim.drainOutbox().isEmpty())

        // A second grab is refused.
        val second = send(victim, c.grabPointer(vw, mask)).reply()
        assertEquals(1, second[1].toInt()) // AlreadyGrabbed

        send(grabber, c.ungrabPointer())
        assertNull(document.pointerGrab)
        victim.onPointerMove(60, 60)
        assertEquals(1, victim.drainOutbox().count { it[0].toInt() == X11.EV_MOTION_NOTIFY })
    }

    @Test
    fun `a passive button grab activates on press and ends on release`() {
        val client = connection()
        val win = rootId + 220
        val mask = EventMask.BUTTON_PRESS or EventMask.BUTTON_RELEASE or EventMask.POINTER_MOTION
        createMapped(client, win, 0, 0, 100, 100, mask)
        send(client, c.grabButton(win, mask, 1))
        assertNull(document.pointerGrab) // not active yet

        client.onPointerMove(90, 90) // inside the window
        client.drainOutbox()
        client.onButton(90, 90, 1, true, X11.MASK_BUTTON1)
        assertNotNull("press must activate the implicit grab", document.pointerGrab)
        val press = client.drainOutbox().single { it[0].toInt() == X11.EV_BUTTON_PRESS }
        assertEquals(win, press.intLE(12))

        // While grabbed, motion outside the window still goes to the grab window.
        client.onPointerMove(500, 500)
        val motion = client.drainOutbox().single { it[0].toInt() == X11.EV_MOTION_NOTIFY }
        assertEquals(win, motion.intLE(12))

        client.onButton(500, 500, 1, false, 0)
        client.drainOutbox()
        assertNull("release must end the implicit grab", document.pointerGrab)
    }

    @Test
    fun `a keyboard grab redirects keys to the grab window`() {
        val client = connection()
        val win = rootId + 230
        createMapped(client, win, 0, 0, 50, 50, EventMask.KEY_PRESS)
        send(client, c.grabKeyboard(win))
        client.onPointerMove(900, 700) // far outside
        client.drainOutbox()
        client.onKey(38, 'a'.code, true, 0)
        assertEquals(win, client.drainOutbox().single { it[0].toInt() == X11.EV_KEY_PRESS }.intLE(12))
        send(client, c.ungrabKeyboard())
        assertNull(document.keyboardGrab)
    }

    // --- pointer warps ----------------------------------------------------------

    @Test
    fun `WarpPointer moves the pointer and produces a MotionNotify`() {
        val client = connection()
        val win = rootId + 240
        createMapped(client, win, 80, 40, 100, 100, EventMask.POINTER_MOTION)
        val out = send(client, c.warpPointer(100, 50))
        assertEquals(100, client.rootPointerX)
        assertEquals(50, client.rootPointerY)
        val motion = out.single { it[0].toInt() == X11.EV_MOTION_NOTIFY }
        assertEquals(win, motion.intLE(12))
        assertEquals(100, motion.shortLE(20))
    }

    @Test
    fun `a sourced warp is a no-op when the pointer is outside the source rect`() {
        val client = connection()
        val src = rootId + 241
        createMapped(client, src, 0, 0, 50, 50, EventMask.POINTER_MOTION)
        client.onPointerMove(900, 700)
        client.drainOutbox()
        val warp = c.build(X11.REQ_WARP_POINTER) { writer ->
            writer.int(src); writer.int(0)
            writer.short(0); writer.short(0); writer.short(0); writer.short(0)
            writer.short(10); writer.short(10)
        }
        send(client, warp)
        assertEquals("pointer must not move", 900, client.rootPointerX)
        assertTrue(client.drainOutbox().none { it[0].toInt() == X11.EV_MOTION_NOTIFY })
    }

    // --- fonts -------------------------------------------------------------------

    @Test
    fun `OpenFont QueryFont and QueryTextExtents serve the built-in fixed font`() {
        val client = connection()
        val font = rootId + 300
        send(client, c.openFont(font, "-misc-fixed-medium-r-*"))
        val reply = send(client, c.queryFont(font)).reply()
        assertEquals(60, reply.size) // sz_xQueryFontReply with no props/charinfos
        assertEquals(7, reply.intLE(4)) // additional length in 4-byte units
        assertEquals(6, reply.shortLE(12)) // maxBounds characterWidth
        assertEquals(0x20, reply.shortLE(40)) // minCharOrByte2
        assertEquals(11, reply.shortLE(52)) // fontAscent
        assertEquals(2, reply.shortLE(54)) // fontDescent
        assertEquals(0, reply.intLE(56)) // nCharInfos

        val extents = send(client, c.queryTextExtents(font, 5)).reply()
        assertEquals(32, extents.size)
        assertEquals(30, extents.intLE(16)) // overallWidth = 5 * 6px
        assertEquals(30, extents.intLE(24)) // overallRight

        // An unknown font id is BadFont, not a crash.
        val bad = send(client, c.queryFont(rootId + 999)).errors().single()
        assertEquals(X11.ERR_BAD_FONT, bad[1].toInt() and 0xFF)
    }

    @Test
    fun `ListFonts answers with the built-in font`() {
        val client = connection()
        val reply = send(client, c.listFonts("*")).reply()
        assertEquals(1, reply.shortLE(8))
        assertEquals(5, reply[32].toInt()) // name length
        assertEquals("fixed", String(reply.copyOfRange(33, 38), Charsets.US_ASCII))
        assertEquals(0, reply.size % 4)
        // The length byte and the 5-character name are ONE 4-byte-aligned entry, so
        // the reply is 32 + 8 bytes and no more: a 41-byte reply (name padded on its
        // own) is one byte longer than the length field advertises, which puts every
        // later message out of step.
        assertEquals(32 + reply.intLE(4) * 4, reply.size)
    }

    @Test
    fun `ListFontsWithInfo sends one info reply and a terminator`() {
        val client = connection()
        val out = send(client, c.build(X11.REQ_LIST_FONTS_WITH_INFO) { writer ->
            writer.short(10); writer.short(1); writer.paddedString("*")
        })
        val replies = out.replies()
        assertEquals(2, replies.size)
        val info = replies[0]
        assertEquals(5, info[1].toInt()) // nameLength "fixed"
        assertEquals("fixed", String(info.copyOfRange(60, 65), Charsets.US_ASCII))
        assertEquals(0, replies[1][1].toInt()) // terminator: nameLength 0
        assertEquals(replies[0].shortLE(2), replies[1].shortLE(2)) // same sequence number
    }

    // --- colours ------------------------------------------------------------------

    @Test
    fun `LookupColor resolves names and rgb strings`() {
        val client = connection()
        val cmap = document.screen.defaultColormap
        val red = send(client, c.lookupColor(cmap, "red")).reply()
        assertEquals(0xFFFF, red.shortLE(8)) // exact red, 16-bit
        assertEquals(0, red.shortLE(10))

        val hex = send(client, c.lookupColor(cmap, "rgb:1a/2b/3c")).reply()
        assertEquals(0x1A1A, hex.shortLE(8) and 0xFFFF) // 0x1A widened to 16 bits
        assertEquals(0x2B2B, hex.shortLE(10))
        assertEquals(0x3C3C, hex.shortLE(12))

        val bad = send(client, c.lookupColor(cmap, "nosuchcolor")).errors().single()
        assertEquals(X11.ERR_BAD_NAME, bad[1].toInt() and 0xFF)
    }

    @Test
    fun `AllocNamedColor returns the TrueColor pixel`() {
        val client = connection()
        val reply = send(client, c.allocNamedColor(document.screen.defaultColormap, "SteelBlue")).reply()
        assertEquals(0x4682B4, reply.intLE(8)) // pixel
        assertEquals(0x4646, reply.shortLE(12)) // exact red
    }

    @Test
    fun `QueryColors echoes TrueColor pixels as 16-bit rgb`() {
        val client = connection()
        val out = send(client, c.build(X11.REQ_QUERY_COLORS) { writer ->
            writer.int(document.screen.defaultColormap)
            writer.int(0xFF8000)
        })
        val reply = out.reply()
        assertEquals(1, reply.shortLE(8))
        assertEquals(0xFFFF, reply.shortLE(32)) // red
        assertEquals(0x8080, reply.shortLE(34)) // green
        assertEquals(0, reply.shortLE(36)) // blue
    }

    // --- cursors -------------------------------------------------------------------

    @Test
    fun `glyph cursors attach to windows and resolve through the tree`() {
        val client = connection()
        val font = rootId + 400
        val cursor = rootId + 401
        val parent = rootId + 402
        val child = rootId + 403
        send(client, c.openFont(font, "cursor"))
        send(client, c.createGlyphCursor(cursor, font, CursorGlyph.XTERM))
        assertEquals(CursorGlyph.XTERM, document.cursor(cursor)?.glyph)

        createMapped(client, parent, 0, 0, 100, 100)
        send(client, c.changeAttributes(parent, 0x4000, cursor)) // CWCursor
        createMapped(client, child, 10, 10, 20, 20, parent = parent)

        assertEquals(CursorGlyph.XTERM, document.effectiveCursorGlyph(15, 15)) // child inherits
        assertEquals(CursorGlyph.XTERM, document.effectiveCursorGlyph(90, 90)) // parent itself
        assertEquals(CursorGlyph.LEFT_PTR, document.effectiveCursorGlyph(500, 500)) // root default

        // Unknown cursor ids and fonts are rejected.
        val badCursor = send(client, c.changeAttributes(parent, 0x4000, rootId + 777)).errors().single()
        assertEquals(X11.ERR_BAD_CURSOR, badCursor[1].toInt() and 0xFF)
        val badFont = send(client, c.createGlyphCursor(rootId + 404, rootId + 777, 68)).errors().single()
        assertEquals(X11.ERR_BAD_FONT, badFont[1].toInt() and 0xFF)
    }

    @Test
    fun `QueryBestSize clamps cursors and passes tiles through`() {
        val client = connection()
        val cursor = send(client, c.queryBestSize(0, rootId, 200, 100)).reply()
        assertEquals(64, cursor.shortLE(8))
        assertEquals(64, cursor.shortLE(10))
        val tile = send(client, c.queryBestSize(1, rootId, 17, 9)).reply()
        assertEquals(17, tile.shortLE(8))
        assertEquals(9, tile.shortLE(10))
    }

    // --- SendEvent / properties ------------------------------------------------------

    @Test
    fun `SendEvent delivers to selecting windows with the synthesised flag`() {
        val client = connection()
        val win = rootId + 500
        createMapped(client, win, 0, 0, 50, 50, EventMask.SUBSTRUCTURE_NOTIFY)
        val atom = document.internAtom("_TEST_MESSAGE")
        val message = c.clientMessage(win, atom)
        val out = send(client, c.sendEvent(win, EventMask.SUBSTRUCTURE_NOTIFY, propagate = false, event = message))
        val delivered = out.single { (it[0].toInt() and 0x7F) == X11.EV_CLIENT_MESSAGE }
        assertEquals(0x80 or X11.EV_CLIENT_MESSAGE, delivered[0].toInt() and 0xFF) // send-event flag
        assertEquals(win, delivered.intLE(4))
        assertEquals(atom, delivered.intLE(8))

        // A window that did not select for the mask gets nothing.
        val other = rootId + 501
        createMapped(client, other, 60, 0, 50, 50, EventMask.EXPOSURE)
        val quiet = send(client, c.sendEvent(other, EventMask.SUBSTRUCTURE_NOTIFY, false, message))
        assertTrue(quiet.none { (it[0].toInt() and 0x7F) == X11.EV_CLIENT_MESSAGE })
    }

    @Test
    fun `ListProperties and RotateProperties work on the property store`() {
        val client = connection()
        val win = rootId + 510
        createMapped(client, win, 0, 0, 10, 10, EventMask.PROPERTY_CHANGE)
        val atomA = document.internAtom("PROP_A")
        val atomB = document.internAtom("PROP_B")
        send(client, c.changeProperty(win, atomA, "aaa".toByteArray()), c.changeProperty(win, atomB, "b".toByteArray()))
        client.drainOutbox()

        val list = send(client, c.listProperties(win)).reply()
        assertEquals(2, list.shortLE(8))

        send(client, c.rotateProperties(win, 1, atomA, atomB))
        assertEquals("b", String(document.property(win, atomA)!!.data))
        assertEquals("aaa", String(document.property(win, atomB)!!.data))
        val notifies = send(client, c.listProperties(win)) // drain pending PropertyNotify
        assertTrue(document.window(win) != null)
        @Suppress("UNUSED_EXPRESSION")
        notifies
    }

    // --- GetImage ----------------------------------------------------------------

    @Test
    fun `GetImage returns the painted pixels as BGRX`() {
        val lists = XDisplayList()
        val client = connection(lists)
        val win = rootId + 600
        val gc = rootId + 601
        createMapped(client, win, 0, 0, 4, 2)
        send(client, c.createGc(gc, win, 0xFF0000), c.polyFillRectangle(win, gc, 0, 0, 4, 2))
        val reply = send(client, c.getImage(win, 0, 0, 4, 2)).reply()
        assertEquals(24, reply[1].toInt() and 0xFF) // depth in the data byte
        assertEquals(8, reply.intLE(4)) // 32 bytes of pixels beyond the 32-byte header
        // Pure red in ZPixmap byte order: B=0, G=0, R=FF, X=FF.
        assertArrayEquals(
            byteArrayOf(0, 0, 0xFF.toByte(), 0xFF.toByte()),
            reply.copyOfRange(32, 36),
        )
    }

    @Test
    fun `GetImage clamps to the drawable and rejects unknown ids`() {
        val lists = XDisplayList()
        val client = connection(lists)
        val win = rootId + 610
        createMapped(client, win, 0, 0, 2, 2)
        val reply = send(client, c.getImage(win, 0, 0, 100, 100)).reply()
        assertEquals(4, reply.intLE(4)) // clamped to 2x2 = 4 pixels = 16 bytes = 4 units
        val bad = send(client, c.getImage(rootId + 12345, 0, 0, 1, 1)).errors().single()
        assertEquals(X11.ERR_BAD_DRAWABLE, bad[1].toInt() and 0xFF)
    }

    // --- XTest ---------------------------------------------------------------------

    @Test
    fun `QueryExtension reports XTEST with its major opcode`() {
        val client = connection()
        val xtest = send(client, c.queryExtension("XTEST")).reply()
        // xQueryExtensionReply: present is the data byte (1), then major opcode,
        // first event and first error are payload bytes 8..10.
        assertEquals(1, xtest[1].toInt()) // present
        assertEquals(X11.EXT_XTEST_OPCODE, xtest[8].toInt() and 0xFF)
        assertEquals(0, xtest[9].toInt()) // first event
        assertEquals(0, xtest[10].toInt()) // first error
        // RENDER is served since D1d: present, with its own opcode and error base.
        val render = send(client, c.queryExtension("RENDER")).reply()
        assertEquals(1, render[1].toInt())
        assertEquals(XRender.MAJOR_OPCODE, render[8].toInt() and 0xFF)
        assertEquals(XRender.FIRST_ERROR, render[10].toInt() and 0xFF)
        // Something genuinely unimplemented stays honestly absent.
        val xkb = send(client, c.queryExtension("XKEYBOARD")).reply()
        assertEquals(0, xkb[1].toInt()) // not present
        assertEquals(0, xkb[8].toInt()) // and therefore no major opcode
        val names = send(client, c.listExtensions()).reply()
        // RENDER + XTEST + the two transport extensions (BIG-REQUESTS and the
        // Generic Event Extension) that XI2's big requests and events ride on,
        // plus XInput2 itself.
        assertEquals(5, names[1].toInt())
        assertEquals(32 + names.intLE(4) * 4, names.size) // ... and no stray byte
        // Registry order: RENDER, XTEST, BIG-REQUESTS, Generic Event Extension,
        // XInputExtension; each entry is a length byte + name, padded to a 4-byte
        // boundary as a whole.
        assertEquals("RENDER", String(names.copyOfRange(33, 39), Charsets.US_ASCII))
        assertEquals("XTEST", String(names.copyOfRange(41, 46), Charsets.US_ASCII))
        assertEquals("BIG-REQUESTS", String(names.copyOfRange(49, 61), Charsets.US_ASCII))
        assertEquals("Generic Event Extension", String(names.copyOfRange(65, 88), Charsets.US_ASCII))
        assertEquals("XInputExtension", String(names.copyOfRange(89, 104), Charsets.US_ASCII))
    }

    @Test
    fun `XTest GetVersion answers version 2 dot 1`() {
        val client = connection()
        val reply = send(client, c.xtest(0) { writer -> writer.byte(2); writer.pad(1); writer.short(1) }).reply()
        assertEquals(2, reply[1].toInt()) // server major
        assertEquals(1, reply.shortLE(8)) // server minor
    }

    @Test
    fun `XTest FakeInput synthesises button key and motion events`() {
        val client = connection()
        val win = rootId + 700
        createMapped(
            client, win, 0, 0, 200, 200,
            EventMask.BUTTON_PRESS or EventMask.KEY_PRESS or EventMask.POINTER_MOTION,
        )

        send(client, c.xtestFakeInput(X11.EV_MOTION_NOTIFY, 0, rootId, 30, 40))
        assertEquals(30, client.rootPointerX)
        assertEquals(40, client.rootPointerY)
        client.drainOutbox()

        val button = send(client, c.xtestFakeInput(X11.EV_BUTTON_PRESS, 1, rootId, 30, 40))
        val press = button.single { it[0].toInt() == X11.EV_BUTTON_PRESS }
        assertEquals(1, press[1].toInt()) // detail = button
        assertEquals(win, press.intLE(12))
        assertEquals(30, press.shortLE(20))

        val key = send(client, c.xtestFakeInput(X11.EV_KEY_PRESS, 38, rootId, 30, 40))
        val keyPress = key.single { it[0].toInt() == X11.EV_KEY_PRESS }
        assertEquals(38, keyPress[1].toInt())
        assertEquals(win, keyPress.intLE(12))
    }

    @Test
    fun `XTest CompareCursor checks the window cursor`() {
        val client = connection()
        val font = rootId + 710
        val cursor = rootId + 711
        val win = rootId + 712
        send(client, c.openFont(font, "cursor"), c.createGlyphCursor(cursor, font, CursorGlyph.LEFT_PTR))
        createMapped(client, win, 0, 0, 50, 50)
        val no = send(client, c.xtest(1) { writer -> writer.int(win); writer.int(0) }).reply()
        assertEquals(1, no[1].toInt()) // window has the default (None) cursor
        send(client, c.changeAttributes(win, 0x4000, cursor))
        val yes = send(client, c.xtest(1) { writer -> writer.int(win); writer.int(cursor) }).reply()
        assertEquals(1, yes[1].toInt())
    }

    // --- control replies -------------------------------------------------------------

    @Test
    fun `keyboard pointer and screensaver control replies are spec-shaped`() {
        val client = connection()

        val kbd = send(client, c.getKeyboardControl()).reply()
        assertEquals(52, kbd.size) // sz_xGetKeyboardControlReply
        assertEquals(1, kbd[1].toInt()) // global auto-repeat
        assertEquals(0xFF, kbd[20].toInt() and 0xFF) // auto-repeats bitmap starts here

        val ptr = send(client, c.getPointerControl()).reply()
        assertEquals(2, ptr.shortLE(8)) // acceleration numerator
        assertEquals(4, ptr.shortLE(12)) // threshold

        send(client, c.setScreenSaver(300, 120))
        val saver = send(client, c.getScreenSaver()).reply()
        assertEquals(300, saver.shortLE(8))
        assertEquals(120, saver.shortLE(10))

        val hosts = send(client, c.listHosts()).reply()
        assertEquals(0, hosts[1].toInt()) // mode: disabled
        assertEquals(0, hosts.shortLE(8)) // no hosts

        val motion = send(client, c.getMotionEvents(rootId)).reply()
        assertEquals(0, motion.intLE(8)) // no buffered history
    }

    @Test
    fun `pointer and modifier mappings follow the standard layout`() {
        val client = connection()
        val map = send(client, c.getPointerMapping()).reply()
        assertEquals(3, map[1].toInt()) // three buttons
        assertEquals(1, map[32].toInt())
        assertEquals(2, map[33].toInt())
        assertEquals(3, map[34].toInt())
        assertEquals(36, map.size)
        // ... and the length field must agree, or the client reads 32 bytes and the
        // next four are parsed as a message header of their own.
        assertEquals(32 + map.intLE(4) * 4, map.size)

        val mods = send(client, c.getModifierMapping()).reply()
        assertEquals(1, mods[1].toInt()) // one keycode per modifier
        assertEquals(40, mods.size) // 8 + 24 fixed + 8 keycodes
        assertEquals(XKeyboard.XKEY_SHIFT_L, mods[32].toInt())
        assertEquals(XKeyboard.XKEY_CAPS_LOCK, mods[33].toInt())
        assertEquals(XKeyboard.XKEY_CONTROL_L, mods[34].toInt())
        assertEquals(XKeyboard.XKEY_ALT_L, mods[35].toInt())
        assertEquals(XKeyboard.XKEY_SUPER_L, mods[38].toInt() and 0xFF)

        val setPointer = send(client, c.build(X11.REQ_SET_POINTER_MAPPING, 3) { writer ->
            writer.byte(1); writer.byte(2); writer.byte(3); writer.pad(1)
        }).reply()
        assertEquals(0, setPointer[1].toInt()) // MappingSuccess
        val setModifier = send(client, c.build(X11.REQ_SET_MODIFIER_MAPPING) { it.pad(8) }).reply()
        assertEquals(0, setModifier[1].toInt())
    }

    @Test
    fun `requests without replies stay silent instead of erroring`() {
        val client = connection()
        val win = rootId + 800
        createMapped(client, win, 0, 0, 50, 50)
        val out = send(
            client,
            c.build(X11.REQ_BELL) { it.byte(50) },
            c.build(X11.REQ_CHANGE_SAVE_SET, 0) { it.int(win) },
            c.build(X11.REQ_ALLOW_EVENTS) { it.byte(0); it.int(0) },
            c.build(X11.REQ_GRAB_SERVER),
            c.build(X11.REQ_UNGRAB_SERVER),
            c.build(X11.REQ_FORCE_SCREEN_SAVER, 0),
            c.build(X11.REQ_SET_CLOSE_DOWN_MODE, 0),
            c.build(X11.REQ_KILL_CLIENT) { it.int(win) },
            c.build(X11.REQ_NO_OPERATION),
            c.build(X11.REQ_CHANGE_KEYBOARD_CONTROL) { it.int(0) },
            c.build(X11.REQ_CHANGE_POINTER_CONTROL, 1) { writer ->
                writer.short(2); writer.short(1); writer.short(4); writer.pad(2)
            },
            c.build(X11.REQ_SET_FONT_PATH) { writer -> writer.short(0) },
            c.build(X11.REQ_CHANGE_HOSTS, 0) { writer -> writer.byte(0); writer.short(0); writer.pad(1) },
            c.build(X11.REQ_SET_ACCESS_CONTROL, 0),
        )
        assertTrue("these requests must not error: ${out.errors().map { it[1].toInt() }}", out.errors().isEmpty())
        assertTrue("and must not invent replies", out.replies().isEmpty())
    }

    // --- stacking, structure ----------------------------------------------------------

    @Test
    fun `CirculateWindow restacks children`() {
        val client = connection()
        val parent = rootId + 900
        val a = rootId + 901
        val b = rootId + 902
        createMapped(client, parent, 0, 0, 200, 200)
        createMapped(client, a, 0, 0, 50, 50, parent = parent)
        createMapped(client, b, 0, 0, 50, 50, parent = parent)
        assertEquals(listOf(a, b), document.window(parent)!!.children)
        send(client, c.circulateWindow(a, 0)) // raise to top
        assertEquals(listOf(b, a), document.window(parent)!!.children)
        send(client, c.circulateWindow(a, 1)) // lower to bottom
        assertEquals(listOf(a, b), document.window(parent)!!.children)
    }

    @Test
    fun `EnterNotify and LeaveNotify fire on window crossings`() {
        val client = connection()
        val left = rootId + 910
        val right = rootId + 911
        val mask = EventMask.ENTER_WINDOW or EventMask.LEAVE_WINDOW
        createMapped(client, left, 0, 0, 100, 100, mask)
        createMapped(client, right, 100, 0, 100, 100, mask)
        client.onPointerMove(50, 50)
        client.drainOutbox()
        val out = send(client, c.build(X11.REQ_NO_OPERATION)) // drain helper
        client.onPointerMove(150, 50)
        val events = client.drainOutbox()
        val leave = events.single { it[0].toInt() == X11.EV_LEAVE_NOTIFY }
        val enter = events.single { it[0].toInt() == X11.EV_ENTER_NOTIFY }
        assertEquals(left, leave.intLE(8))
        assertEquals(right, enter.intLE(8))
        assertTrue(out.isEmpty())
    }

    // --- new drawing primitives ---------------------------------------------------------

    @Test
    fun `vector primitives reach the sink`() {
        val client = connection()
        val win = rootId + 920
        val gc = rootId + 921
        createMapped(client, win, 0, 0, 100, 100)
        send(
            client,
            c.createGc(gc, win, 0x00FF00),
            c.polyPoint(win, gc, 1, 2, 3, 4),
            c.polyLine(win, gc, 0, 0, 10, 10, 20, 0),
            c.polySegment(win, gc, 0, 0, 5, 5, 8, 8, 9, 9),
            c.polyFillArc(win, gc, 10, 10, 20, 20, 0, 23040),
            c.fillPoly(win, gc, 0, 0, 10, 0, 5, 10),
        )
        assertEquals(
            listOf("points 2 #ff00", "lines 3 #ff00", "segments 4 #ff00",
                "arc 10,10 20x20 0+23040 filled=true", "polygon 3 #ff00"),
            sink.primitives,
        )
    }

    @Test
    fun `ImageText16 and PolyText8 render their text`() {
        val client = connection()
        val win = rootId + 930
        val gc = rootId + 931
        createMapped(client, win, 0, 0, 200, 50)
        send(client, c.createGc(gc, win, 0), c.imageText16(win, gc, 5, 15, "Hi"), c.polyText8(win, gc, 5, 30, "ab", "cd"))
        assertEquals(listOf("5,15 \"Hi\"", "5,30 \"ab\"", "17,30 \"cd\""), sink.texts)
    }
}
