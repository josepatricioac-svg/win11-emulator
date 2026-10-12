package dev.win11we.shell.x11

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * BIG-REQUESTS and the Generic Event Extension: the two pieces of transport the
 * other extensions ride on. Wire layouts from xorgproto 2024.1 (`bigreqsproto.h`,
 * `geproto.h`, `xGenericEvent` in `Xproto.h`).
 *
 * Why these are load-bearing rather than nice-to-have:
 *
 * - cairo/GTK exceed the 64 KiB CARD16 request ceiling with a single window-sized
 *   `PutImage`; libX11 then re-sends it in the extended form (CARD16 length 0,
 *   CARD32 length at offset 4). A server that parses that as an ordinary request
 *   desynchronises the stream, and GTK's error handler `_exit(1)`s without a word.
 * - XInput2's device events do not fit in 32 bytes, so they travel as
 *   GenericEvents. Getting this framing wrong means a client that has selected XI2
 *   events silently receives garbage.
 */
private class BigRequests {
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

    /** The extended request form: CARD16 length 0, CARD32 length in units, body at 8. */
    fun big(opcode: Int, data: Int = 0, body: (XWriter) -> Unit = {}): ByteArray {
        val bodyWriter = XWriter(true)
        body(bodyWriter)
        val bodyBytes = bodyWriter.toByteArray()
        val total = XBigRequests.HEADER_BYTES + bodyBytes.size
        val padded = X11.pad4(total)
        val writer = XWriter(true)
        writer.byte(opcode)
        writer.byte(data)
        writer.short(0) // the BIG-REQUESTS marker
        writer.int(padded / 4)
        writer.bytes(bodyBytes)
        writer.pad(padded - total)
        return writer.toByteArray()
    }

    /** A big request that claims [units] without actually carrying the bytes. */
    fun bigWithClaimedLength(opcode: Int, units: Int): ByteArray {
        val writer = XWriter(true)
        writer.byte(opcode)
        writer.byte(0)
        writer.short(0)
        writer.int(units)
        return writer.toByteArray()
    }

    fun queryExtension(name: String) = build(X11.REQ_QUERY_EXTENSION) { writer ->
        writer.short(name.length); writer.short(0); writer.paddedString(name)
    }

    fun listExtensions() = build(X11.REQ_LIST_EXTENSIONS)

    fun bigReqEnable() = build(XBigRequests.MAJOR_OPCODE, XBigRequests.MINOR_ENABLE)
    fun geQueryVersion() = build(XGenericEvent.MAJOR_OPCODE, XGenericEvent.MINOR_QUERY_VERSION)

    fun createWindow(id: Int, parent: Int) = build(X11.REQ_CREATE_WINDOW, 24) { writer ->
        writer.int(id); writer.int(parent)
        writer.short(0); writer.short(0); writer.short(64); writer.short(48)
        writer.short(0); writer.short(0); writer.int(0x21); writer.int(0)
    }

    /** ChangeProperty with an n-byte STRING8 payload; [big] picks the request form. */
    fun changeProperty(window: Int, property: Int, type: Int, data: ByteArray, big: Boolean): ByteArray {
        val body: (XWriter) -> Unit = { writer ->
            writer.int(window); writer.int(property); writer.int(type)
            writer.byte(8); writer.pad(3) // format 8
            writer.int(data.size) // nItems
            writer.bytes(data)
            writer.pad(X11.padded(data.size))
        }
        return if (big) big(X11.REQ_CHANGE_PROPERTY, 0 /* Replace */, body) else build(X11.REQ_CHANGE_PROPERTY, 0, body)
    }

    fun getProperty(window: Int, property: Int, longLength: Int) =
        build(X11.REQ_GET_PROPERTY) { writer ->
            writer.int(window); writer.int(property); writer.int(0 /* AnyPropertyType */)
            writer.int(0); writer.int(longLength); writer.byte(0); writer.pad(3)
        }

    fun mapWindow(id: Int) = build(X11.REQ_MAP_WINDOW) { it.int(id) }
}

private fun ByteArray.intLE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

private fun ByteArray.shortLE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

class XBigRequestsTest {

    private lateinit var document: XDocument
    private lateinit var sink: RecordingSink
    private val r = BigRequests()
    private val rootId = X11.RESOURCE_ID_BASE
    private val win = rootId + 1
    private val atomProperty = 400
    private val atomType = 401

    @Before
    fun setUp() {
        document = XServer.defaultDocument(1280, 800)
        sink = RecordingSink()
    }

    private fun connection() = XConnection(document, sink)

    private fun send(connection: XConnection, vararg bytes: ByteArray): List<ByteArray> {
        bytes.forEach { connection.onBytes(it) }
        return connection.drainOutbox()
    }

    private fun List<ByteArray>.replies() = filter { it[0].toInt() == 1 }
    private fun List<ByteArray>.errors() = filter { it[0].toInt() == 0 }
    private fun List<ByteArray>.reply(): ByteArray = replies().single()

    // --- advertisement -------------------------------------------------------

    @Test
    fun `both transport extensions are advertised with their own major opcodes`() {
        val client = connection()
        val out = send(
            client,
            r.queryExtension(XBigRequests.NAME),
            r.queryExtension(XGenericEvent.NAME),
        )
        val replies = out.replies()
        assertEquals(2, replies.size)
        val bigReq = replies[0]
        assertEquals("present is the reply's data byte", 1, bigReq[1].toInt())
        assertEquals(XBigRequests.MAJOR_OPCODE, bigReq[8].toInt() and 0xFF)
        val ge = replies[1]
        assertEquals(1, ge[1].toInt())
        assertEquals(XGenericEvent.MAJOR_OPCODE, ge[8].toInt() and 0xFF)
        assertTrue("the two opcodes must not collide", XBigRequests.MAJOR_OPCODE != XGenericEvent.MAJOR_OPCODE)
        assertTrue("neither may collide with RENDER or XTEST",
            setOf(XBigRequests.MAJOR_OPCODE, XGenericEvent.MAJOR_OPCODE, XRender.MAJOR_OPCODE, X11.EXT_XTEST_OPCODE).size == 4)
    }

    @Test
    fun `the extension lookup is case-insensitive but ListExtensions keeps canonical spelling`() {
        val client = connection()
        val out = send(client, r.queryExtension("big-requests"), r.queryExtension("GENERIC EVENT EXTENSION"))
        out.replies().forEach { assertEquals(1, it[1].toInt()) }

        val names = send(client, r.listExtensions()).reply()
        val count = names[1].toInt() and 0xFF
        var offset = 32
        val listed = mutableListOf<String>()
        repeat(count) {
            val length = names[offset].toInt() and 0xFF
            listed += String(names.copyOfRange(offset + 1, offset + 1 + length), Charsets.US_ASCII)
            // One entry is a length byte + the name, and the WHOLE entry is
            // padded to a 4-byte boundary: pad4(length + 1) bytes in total.
            offset += X11.pad4(length + 1)
        }
        assertTrue(listed.toString(), listed.contains("BIG-REQUESTS"))
        assertTrue(listed.toString(), listed.contains("Generic Event Extension"))
        assertEquals("the reply must end exactly where its entries do", names.size, offset)
    }

    // --- BIG-REQUESTS --------------------------------------------------------

    @Test
    fun `Enable answers with the maximum request length it will accept`() {
        val client = connection()
        val reply = send(client, r.bigReqEnable()).reply()
        assertEquals(XBigRequests.MAX_REQUEST_UNITS, reply.intLE(8))
        assertTrue(client.bigRequestsEnabled)
        // The reply is 32 bytes with a CARD32 at offset 8 and nothing after it.
        assertEquals(0, reply.intLE(4))
        assertEquals(32, reply.size)
    }

    @Test
    fun `a big request is refused before Enable and the trace says why`() {
        val client = connection()
        send(client, r.createWindow(win, rootId))
        val out = send(client, r.big(X11.REQ_NO_OPERATION))
        val error = out.errors().single()
        assertEquals(X11.ERR_BAD_LENGTH, error[1].toInt() and 0xFF)
        assertFalse(client.bigRequestsEnabled)
        assertTrue(
            "the report must name the violation, not just an opcode: ${client.trace.summary()}",
            client.trace.summary().contains("BIG-REQUESTS Enable"),
        )
    }

    @Test
    fun `after Enable a request larger than 64 KiB is parsed whole`() {
        val client = connection()
        send(client, r.createWindow(win, rootId), r.bigReqEnable())
        client.drainOutbox()
        // 70 000 bytes: impossible in the CARD16 form (64 KiB ceiling).
        val payload = ByteArray(70_000) { (it % 251).toByte() }
        val out = send(client, r.changeProperty(win, atomProperty, atomType, payload, big = true))
        assertTrue("no error for a legal big request: ${out.errors()}", out.errors().isEmpty())
        assertEquals(0, client.trace.replyLengthMismatches)

        val reply = send(client, r.getProperty(win, atomProperty, payload.size / 4 + 1)).reply()
        // xGetPropertyReply: propertyType@8, bytesAfter@12, value-length@16.
        assertEquals(0, reply.intLE(12))
        val items = reply.intLE(16)
        assertEquals(payload.size, items)
        val returned = reply.copyOfRange(32, 32 + items)
        assertArrayEquals("the whole payload must survive the extended framing", payload, returned)
    }

    @Test
    fun `small and big requests interleave on one connection`() {
        val client = connection()
        send(client, r.createWindow(win, rootId), r.bigReqEnable())
        val small = ByteArray(64) { 0x41 }
        val big = ByteArray(70_000) { 0x42 }
        val out = send(
            client,
            r.changeProperty(win, atomProperty, atomType, small, big = false),
            r.changeProperty(win, atomProperty, atomType, big, big = true),
            r.changeProperty(win, atomProperty, atomType, small, big = false),
            r.mapWindow(win),
        )
        assertTrue("nothing may error: ${out.errors()}", out.errors().isEmpty())
        val reply = send(client, r.getProperty(win, atomProperty, 64)).reply()
        assertEquals("the last write wins, so the stream stayed in sync", small.size, reply.intLE(16))
    }

    @Test
    fun `a big request that exceeds the advertised maximum is refused, not buffered`() {
        val client = connection()
        send(client, r.bigReqEnable())
        val out = send(client, r.bigWithClaimedLength(X11.REQ_CHANGE_PROPERTY, XBigRequests.MAX_REQUEST_UNITS + 1))
        assertEquals(X11.ERR_BAD_LENGTH, out.errors().single()[1].toInt() and 0xFF)
        assertTrue(client.trace.summary().contains("exceeds the advertised maximum"))
    }

    @Test
    fun `a big request split across socket reads is reassembled`() {
        val client = connection()
        send(client, r.createWindow(win, rootId), r.bigReqEnable())
        client.drainOutbox()
        val payload = ByteArray(70_000) { (it % 199).toByte() }
        val whole = r.changeProperty(win, atomProperty, atomType, payload, big = true)
        // Fed in awkward chunks, the way a LocalSocket delivers it.
        var offset = 0
        while (offset < whole.size) {
            val end = minOf(whole.size, offset + 4093)
            client.onBytes(whole.copyOfRange(offset, end))
            offset = end
        }
        val reply = send(client, r.getProperty(win, atomProperty, payload.size / 4 + 1)).reply()
        assertEquals(payload.size, reply.intLE(16))
        assertArrayEquals(payload, reply.copyOfRange(32, 32 + payload.size))
    }

    // --- Generic Event framing ----------------------------------------------

    @Test
    fun `a generic event carries the extension opcode, its evtype and its length past byte 32`() {
        val client = connection()
        client.deliverGenericEvent(X11.EXT_XTEST_OPCODE, 42) { writer ->
            writer.int(0x11223344)
            writer.int(0x55667788)
        }
        val event = client.drainOutbox().single()
        assertEquals(XGenericEvent.EVENT_TYPE, event[0].toInt() and 0xFF)
        assertEquals(X11.EXT_XTEST_OPCODE, event[1].toInt() and 0xFF)
        assertEquals(42, event.shortLE(8))
        // 10 bytes of header + 8 bytes of body = 18 -> padded to 32, so nothing
        // follows the first 32 bytes and the length field must say 0.
        assertEquals(0, event.intLE(4))
        assertEquals(32, event.size)
        assertEquals(0x11223344, event.intLE(10))
        assertEquals(0x55667788, event.intLE(14))
    }

    @Test
    fun `a generic event longer than 32 bytes declares exactly the units it carries`() {
        val client = connection()
        val body = ByteArray(58) { (it + 1).toByte() }
        client.deliverGenericEvent(XRender.MAJOR_OPCODE, 7) { it.bytes(body) }
        val event = client.drainOutbox().single()
        val units = event.intLE(4)
        assertEquals("total size must equal 32 + units*4", 32 + units * 4, event.size)
        assertEquals(7, event.shortLE(8))
        assertArrayEquals(body, event.copyOfRange(10, 10 + body.size))
        assertTrue("trailing bytes are padding", event.drop(10 + body.size).all { it.toInt() == 0 })
    }

    @Test
    fun `an extension event body that is not 4-byte aligned is padded, not truncated`() {
        val client = connection()
        client.deliverGenericEvent(XGenericEvent.MAJOR_OPCODE, 1) { writer ->
            writer.int(1); writer.short(2); writer.byte(3) // 7 bytes
        }
        val event = client.drainOutbox().single()
        assertEquals(0, event.size % 4)
        assertEquals((event.size - 32) / 4, event.intLE(4))
        assertEquals(1, event.intLE(10))
        assertEquals(2, event.shortLE(14))
        assertEquals(3, event[16].toInt() and 0xFF)
    }

    @Test
    fun `the registry never hands out an extension major opcode below 128`() {
        XConnection.EXTENSIONS.forEach { (name, info) ->
            assertTrue("$name: extension opcodes live in 128-255", info[0] in 128..255)
        }
        val opcodes = XConnection.EXTENSIONS.values.map { it[0] }
        assertEquals("each extension needs its own opcode", opcodes.size, opcodes.distinct().size)
        assertNotNull(XConnection.extension("render"))
        assertEquals(null, XConnection.extension("COMPOSITE"))
    }
}
