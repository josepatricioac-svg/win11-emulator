package dev.win11we.shell.x11

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Builds real wire-format requests, so the trace is exercised the way a client exercises it. */
private class TraceRequests {
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

    // --- the requests the trace cares about ---------------------------------
    fun queryExtension(name: String) = build(X11.REQ_QUERY_EXTENSION) { writer ->
        writer.short(name.length); writer.pad(2); writer.paddedString(name)
    }

    fun listExtensions() = build(X11.REQ_LIST_EXTENSIONS)
    fun unknown(opcode: Int = 200) = build(opcode) { it.pad(4) }
    fun xtest(minor: Int) = build(X11.EXT_XTEST_OPCODE, minor)

    /** GetProperty truncated on purpose: the reader runs off the end and throws. */
    fun truncatedGetProperty(window: Int) = build(X11.REQ_GET_PROPERTY) { it.int(window) }

    // --- the rest of the reply-producing surface ----------------------------
    fun createWindow(id: Int, parent: Int) = build(X11.REQ_CREATE_WINDOW, 24) { writer ->
        writer.int(id); writer.int(parent)
        writer.short(0); writer.short(0); writer.short(64); writer.short(48)
        writer.short(0); writer.short(0); writer.int(0x21); writer.int(0)
    }

    fun mapWindow(id: Int) = build(X11.REQ_MAP_WINDOW) { it.int(id) }
    fun getGeometry(id: Int) = build(X11.REQ_GET_GEOMETRY) { it.int(id) }
    fun getWindowAttributes(id: Int) = build(X11.REQ_GET_WINDOW_ATTRIBUTES) { it.int(id) }
    fun queryTree(id: Int) = build(X11.REQ_QUERY_TREE) { it.int(id) }
    fun internAtom(name: String) = build(X11.REQ_INTERN_ATOM) { writer ->
        writer.short(name.length); writer.pad(2); writer.paddedString(name)
    }

    fun getAtomName(atom: Int) = build(X11.REQ_GET_ATOM_NAME) { it.int(atom) }
    fun listProperties(id: Int) = build(X11.REQ_LIST_PROPERTIES) { it.int(id) }
    fun getProperty(window: Int, atom: Int) = build(X11.REQ_GET_PROPERTY) { writer ->
        writer.int(window); writer.int(atom); writer.int(0)
        writer.int(0); writer.int(64); writer.byte(0); writer.pad(3)
    }

    fun getSelectionOwner(atom: Int) = build(X11.REQ_GET_SELECTION_OWNER) { it.int(atom) }
    fun queryPointer(id: Int) = build(X11.REQ_QUERY_POINTER) { it.int(id) }
    fun getMotionEvents(id: Int) = build(X11.REQ_GET_MOTION_EVENTS) { writer ->
        writer.int(id); writer.int(0); writer.int(-1)
    }

    fun translateCoordinates(src: Int, dst: Int) = build(X11.REQ_TRANSLATE_COORDINATES) { writer ->
        writer.int(src); writer.int(dst); writer.short(0); writer.short(0)
    }

    fun getInputFocus() = build(X11.REQ_GET_INPUT_FOCUS)
    fun queryKeymap() = build(X11.REQ_QUERY_KEYMAP)
    fun openFont(fid: Int, name: String) = build(X11.REQ_OPEN_FONT) { writer ->
        writer.int(fid); writer.short(name.length); writer.pad(2); writer.paddedString(name)
    }

    fun queryFont(fid: Int) = build(X11.REQ_QUERY_FONT) { it.int(fid) }
    fun queryTextExtents(fid: Int) = build(X11.REQ_QUERY_TEXT_EXTENTS, 1) { writer ->
        writer.int(fid); writer.short(65); writer.short(0)
    }

    fun listFonts(pattern: String) = build(X11.REQ_LIST_FONTS) { writer ->
        writer.short(10); writer.short(pattern.length); writer.paddedString(pattern)
    }

    fun getFontPath() = build(X11.REQ_GET_FONT_PATH)

    fun listFontsWithInfo(pattern: String) = build(X11.REQ_LIST_FONTS_WITH_INFO) { writer ->
        writer.short(10); writer.short(pattern.length); writer.paddedString(pattern)
    }
    fun createColormap(mid: Int, window: Int) = build(X11.REQ_CREATE_COLORMAP, 0) { writer ->
        writer.int(mid); writer.int(0x21); writer.int(window)
    }

    fun listInstalledColormaps(window: Int) = build(X11.REQ_LIST_INSTALLED_COLORMAPS) { it.int(window) }
    fun allocColor(cmap: Int) = build(X11.REQ_ALLOC_COLOR) { writer ->
        writer.int(cmap); writer.short(0xFFFF); writer.short(0); writer.short(0); writer.pad(2)
    }

    fun allocNamedColor(cmap: Int, name: String) = build(X11.REQ_ALLOC_NAMED_COLOR) { writer ->
        writer.int(cmap); writer.short(name.length); writer.pad(2); writer.paddedString(name)
    }

    fun lookupColor(cmap: Int, name: String) = build(X11.REQ_LOOKUP_COLOR) { writer ->
        writer.int(cmap); writer.short(name.length); writer.pad(2); writer.paddedString(name)
    }

    fun queryColors(cmap: Int, pixels: List<Int>) = build(X11.REQ_QUERY_COLORS, pixels.size) { writer ->
        writer.int(cmap); pixels.forEach { writer.int(it) }
    }

    fun queryBestSize(klass: Int, drawable: Int) = build(X11.REQ_QUERY_BEST_SIZE, klass) { writer ->
        writer.int(drawable); writer.short(8); writer.short(8)
    }

    fun createGc(cid: Int, drawable: Int) = build(X11.REQ_CREATE_GC) { writer ->
        writer.int(cid); writer.int(drawable); writer.int(0x00000004); writer.int(0xFF0000)
    }

    fun getImage(drawable: Int) = build(X11.REQ_GET_IMAGE, 2) { writer ->
        writer.int(drawable); writer.short(0); writer.short(0)
        writer.short(4); writer.short(4); writer.int(-1)
    }

    fun getKeyboardMapping(first: Int, count: Int) = build(X11.REQ_GET_KEYBOARD_MAPPING) { writer ->
        writer.byte(first); writer.byte(count); writer.pad(2)
    }

    fun getKeyboardControl() = build(X11.REQ_GET_KEYBOARD_CONTROL)
    fun getPointerControl() = build(X11.REQ_GET_POINTER_CONTROL)
    fun getScreenSaver() = build(X11.REQ_GET_SCREEN_SAVER)
    fun listHosts() = build(X11.REQ_LIST_HOSTS)
    fun getPointerMapping() = build(X11.REQ_GET_POINTER_MAPPING)
    fun getModifierMapping() = build(X11.REQ_GET_MODIFIER_MAPPING)
}

/**
 * The X server's observability, and the invariant that keeps a connection in sync.
 *
 * Both halves exist because of the same failure mode: GTK installs an X error
 * handler that calls `_exit(1)`, so a client that hits anything we get wrong
 * simply disappears with no stderr. The trace is what turns that into a line in
 * the shell log naming the request; the length invariant is what stops one bad
 * reply from corrupting every message after it.
 */
class XProtocolTraceTest {

    private lateinit var document: XDocument
    private lateinit var sink: RecordingSink
    private lateinit var connection: XConnection
    private val client = TraceRequests()
    private val rootId = X11.RESOURCE_ID_BASE
    private val win = X11.RESOURCE_ID_BASE + 400
    private val gc = X11.RESOURCE_ID_BASE + 401
    private val font = X11.RESOURCE_ID_BASE + 402
    private val cmap = X11.RESOURCE_ID_BASE + 403

    @Before
    fun setUp() {
        document = XServer.defaultDocument(1280, 800)
        sink = RecordingSink()
        connection = XConnection(document, sink)
        connection.trace.label = "client#1"
    }

    private fun send(vararg requests: ByteArray): List<ByteArray> {
        requests.forEach { connection.onBytes(it) }
        return connection.drainOutbox()
    }

    // --- extension queries --------------------------------------------------

    @Test
    fun `every extension a client queries is recorded with the answer we gave`() {
        send(
            client.queryExtension("BIG-REQUESTS"),
            client.queryExtension("COMPOSITE"),
            client.queryExtension("XKEYBOARD"),
            client.queryExtension("XTEST"),
            client.queryExtension("xtest"),
        )
        val asked = connection.trace.extensionsAsked()
        assertEquals(true, asked["BIG-REQUESTS"])
        assertEquals(false, asked["COMPOSITE"])
        assertEquals(false, asked["XKEYBOARD"])
        assertEquals(true, asked["XTEST"])
        val summary = connection.trace.summary()
        assertTrue(summary, summary.contains("XTEST=yes"))
        assertTrue(summary, summary.contains("XKEYBOARD=no"))
        assertTrue(summary, summary.contains("client#1"))
    }

    @Test
    fun `the summary stays bounded no matter how many extensions are queried`() {
        repeat(400) { index -> send(client.queryExtension("EXT$index")) }
        assertEquals(48, connection.trace.extensionsAsked().size)
        assertEquals(400, connection.trace.requests)
        assertTrue(connection.trace.summary().length < 4000)
    }

    // --- unimplemented requests ---------------------------------------------

    @Test
    fun `an opcode we cannot serve is recorded before it is refused`() {
        val errors = send(client.unknown(200), client.unknown(201), client.unknown(200))
            .filter { it[0].toInt() == 0 }
        assertEquals(3, errors.size)
        errors.forEach { assertEquals(X11.ERR_BAD_REQUEST, it[1].toInt() and 0xFF) }
        assertEquals(mapOf("200" to 2L, "201" to 1L), connection.trace.unimplementedOpcodes())
        assertTrue(connection.trace.hasProblems())
        val summary = connection.trace.summary()
        assertTrue(summary, summary.contains("200x2"))
        assertTrue(summary, summary.contains("201x1"))
    }

    @Test
    fun `an unsupported XTest minor opcode is recorded as major dot minor`() {
        send(client.xtest(9))
        assertEquals(mapOf("${X11.EXT_XTEST_OPCODE}.9" to 1L), connection.trace.unimplementedOpcodes())
        // The raw map keeps "major.minor"; the summary resolves known extensions
        // to names so device logs say which request was missing, not just numbers.
        assertTrue(connection.trace.summary().contains("XTEST.minor9x1"))
    }

    @Test
    fun `a dispatch that throws is recorded with its exception`() {
        send(client.truncatedGetProperty(win))
        val failures = connection.trace.failureCounts()
        assertEquals(1, failures.size)
        val key = failures.keys.single()
        assertTrue(key, key.startsWith("opcode ${X11.REQ_GET_PROPERTY}:"))
        assertEquals(1L, failures[key])
        assertNotNull(connection.lastFailure)
        assertTrue(connection.trace.hasProblems())
    }

    @Test
    fun `a clean session reports no problems`() {
        send(client.internAtom("WM_NAME"), client.getGeometry(rootId), client.queryKeymap())
        assertFalse(connection.trace.hasProblems())
        assertEquals(3, connection.trace.requests)
        assertEquals(0, connection.trace.errorsSent)
        val summary = connection.trace.summary()
        assertTrue(summary, summary.contains("3 requests, 0 errors sent"))
        assertFalse(summary.contains("unimplemented"))
    }

    // --- the reply length invariant -----------------------------------------

    @Test
    fun `no reply disagrees with the length field it advertises`() {
        // Every reply-producing request the server implements, in one session.
        // A body that is even one byte longer or shorter than `32 + length*4`
        // desynchronises the connection: the client parses the tail of one reply
        // as the head of the next and GTK dies on the nonsense.
        send(
            client.createWindow(win, rootId),
            client.mapWindow(win),
            client.openFont(font, "fixed"),
            client.createGc(gc, win),
            client.createColormap(cmap, rootId),
        )
        send(
            client.getGeometry(win),
            client.getWindowAttributes(win),
            client.queryTree(rootId),
            client.internAtom("WM_NAME"),
            client.getAtomName(document.atomId("WM_NAME")!!),
            client.listProperties(win),
            client.getProperty(win, document.atomId("WM_NAME")!!),
            client.getSelectionOwner(document.atomId("PRIMARY")!!),
            client.queryPointer(win),
            client.getMotionEvents(win),
            client.translateCoordinates(rootId, win),
            client.getInputFocus(),
            client.queryKeymap(),
            client.queryFont(font),
            client.queryTextExtents(font),
            client.listFonts("*"),
            client.listFontsWithInfo("*"),
            client.getFontPath(),
            client.listInstalledColormaps(rootId),
            client.allocColor(cmap),
            client.allocNamedColor(cmap, "red"),
            client.lookupColor(cmap, "red"),
            client.queryColors(cmap, listOf(0, 1, 2)),
            client.queryBestSize(0, win),
            client.getImage(win),
            client.getKeyboardMapping(8, 4),
            client.getKeyboardControl(),
            client.getPointerControl(),
            client.getScreenSaver(),
            client.listHosts(),
            client.getPointerMapping(),
            client.getModifierMapping(),
            client.listExtensions(),
            client.queryExtension("XTEST"),
            client.queryExtension("RENDER"),
        )
        assertEquals(
            "a reply body disagreed with its declared length: ${connection.trace.summary()}",
            0, connection.trace.replyLengthMismatches,
        )
    }

    @Test
    fun `the outbound stream of a full session is walkable from its own length fields`() {
        val out = send(
            client.createWindow(win, rootId),
            client.mapWindow(win),
            client.openFont(font, "fixed"),
            client.createGc(gc, win),
            client.listExtensions(),
            client.getPointerMapping(),
            client.getModifierMapping(),
            client.queryKeymap(),
            client.getKeyboardMapping(8, 2),
            client.getKeyboardControl(),
            client.internAtom("_NET_WM_NAME"),
            client.getImage(win),
            client.queryTree(rootId),
            client.unknown(200),
        )
        val stream = out.fold(ByteArray(0)) { acc, message -> acc + message }
        var offset = 0
        var messages = 0
        while (offset < stream.size) {
            val type = stream[offset].toInt()
            val size = if (type == 1) {
                32 + ((stream[offset + 4].toInt() and 0xFF) or
                    ((stream[offset + 5].toInt() and 0xFF) shl 8) or
                    ((stream[offset + 6].toInt() and 0xFF) shl 16) or
                    ((stream[offset + 7].toInt() and 0xFF) shl 24)) * 4
            } else {
                32 // errors and core events are fixed size
            }
            assertTrue(
                "message #$messages (type $type) at $offset claims $size bytes, ${stream.size - offset} left",
                offset + size <= stream.size,
            )
            offset += size
            messages++
        }
        assertEquals(out.size, messages)
        assertEquals(0, connection.trace.replyLengthMismatches)
    }

    // --- the report hook ----------------------------------------------------

    @Test
    fun `XServer hands a connection's report to the reporter the shell installed`() {
        val received = mutableListOf<String>()
        val previous = XServer.protocolReporter
        XServer.protocolReporter = { line -> received.add(line) }
        try {
            send(client.queryExtension("XTEST"), client.unknown(200))
            XServer.report(connection)
            assertEquals(1, received.size)
            assertTrue(received.single(), received.single().contains("XTEST=yes"))
            assertTrue(received.single(), received.single().contains("200x1"))
            assertEquals(received.single(), XServer.lastProtocolReport)
        } finally {
            XServer.protocolReporter = previous
        }
    }

    @Test
    fun `reporting without a reporter installed is a no-op that still caches the report`() {
        val previous = XServer.protocolReporter
        XServer.protocolReporter = null
        try {
            send(client.queryExtension("SHAPE"))
            XServer.report(connection)
            assertNotNull(XServer.lastProtocolReport)
            assertTrue(XServer.lastProtocolReport!!.contains("SHAPE=no"))
        } finally {
            XServer.protocolReporter = previous
        }
    }
}
