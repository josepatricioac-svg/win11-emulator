package dev.win11we.shell.x11

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * D1f XFIXES wire tests. Requests are built byte-for-byte per xorgproto's
 * `xfixesproto.h`/`xfixeswire.h`; replies and events are checked field by
 * field - a reply one byte off desynchronises the connection and GTK exits.
 *
 * The version claim is 5.0 (see [XFixes] KDoc): the v6 tail (pointer barriers,
 * client-disconnect mode) is refused and named, and a test pins that refusal.
 */
private class Xf {
    fun ext(minor: Int, body: (XWriter) -> Unit = {}): ByteArray =
        build(XFixes.MAJOR_OPCODE, minor, body)

    fun build(major: Int, minor: Int, body: (XWriter) -> Unit = {}): ByteArray {
        val bodyWriter = XWriter(true)
        body(bodyWriter)
        val bodyBytes = bodyWriter.toByteArray()
        val writer = XWriter(true)
        writer.byte(major)
        writer.byte(minor)
        val total = 4 + bodyBytes.size
        val padded = X11.pad4(total)
        writer.short(padded / 4)
        writer.bytes(bodyBytes)
        writer.pad(padded - total)
        return writer.toByteArray()
    }

    fun core(opcode: Int, data: Int = 0, body: (XWriter) -> Unit = {}): ByteArray =
        build(opcode, data, body)

    // --- core scaffolding the XFIXES tests need --------------------------------
    fun createWindow(id: Int, parent: Int, x: Int, y: Int, w: Int, h: Int) =
        core(X11.REQ_CREATE_WINDOW, 24) { writer ->
            writer.int(id); writer.int(parent)
            writer.short(x); writer.short(y); writer.short(w); writer.short(h)
            writer.short(0); writer.short(0); writer.int(0x21); writer.int(0)
        }

    fun mapWindow(id: Int) = core(X11.REQ_MAP_WINDOW) { it.int(id) }
    fun destroyWindow(id: Int) = core(X11.REQ_DESTROY_WINDOW) { it.int(id) }

    fun changeCursorAttr(window: Int, cursor: Int) =
        core(X11.REQ_CHANGE_WINDOW_ATTRIBUTES) { writer ->
            writer.int(window); writer.int(0x4000); writer.int(cursor)
        }

    fun openFont(fid: Int, name: String) = core(X11.REQ_OPEN_FONT) { writer ->
        writer.int(fid); writer.short(name.length); writer.pad(2); writer.paddedString(name)
    }

    fun createGlyphCursor(cid: Int, font: Int, glyph: Int) =
        core(X11.REQ_CREATE_GLYPH_CURSOR) { writer ->
            writer.int(cid); writer.int(font); writer.int(0)
            writer.short(glyph); writer.short(0)
            writer.short(0); writer.short(0); writer.short(0)
            writer.short(0xFFFF); writer.short(0xFFFF); writer.short(0xFFFF)
        }

    fun setSelectionOwner(window: Int, selection: Int) =
        core(X11.REQ_SET_SELECTION_OWNER) { writer ->
            writer.int(window); writer.int(selection); writer.int(0)
        }

    fun createPixmap(id: Int, drawable: Int, w: Int, h: Int, depth: Int) =
        core(X11.REQ_CREATE_PIXMAP, depth) { writer ->
            writer.int(id); writer.int(drawable); writer.short(w); writer.short(h)
        }

    fun createGc(id: Int, drawable: Int, foreground: Int) = core(X11.REQ_CREATE_GC) { writer ->
        writer.int(id); writer.int(drawable); writer.int(0x00000004); writer.int(foreground)
    }

    fun polyFillRectangle(drawable: Int, gc: Int, x: Int, y: Int, w: Int, h: Int) =
        core(X11.REQ_POLY_FILL_RECTANGLE) { writer ->
            writer.int(drawable); writer.int(gc)
            writer.short(x); writer.short(y); writer.short(w); writer.short(h)
        }

    fun createPicture(pid: Int, drawable: Int, format: Int) =
        build(XRender.MAJOR_OPCODE, XRender.MINOR_CREATE_PICTURE) { writer ->
            writer.int(pid); writer.int(drawable); writer.int(format); writer.int(0)
        }

    fun queryExtension(name: String) = core(X11.REQ_QUERY_EXTENSION) { writer ->
        writer.short(name.length); writer.pad(2); writer.paddedString(name)
    }

    fun listExtensions() = core(X11.REQ_LIST_EXTENSIONS)

    // --- XFIXES ------------------------------------------------------------------
    fun queryVersion(clientMajor: Int = 5, clientMinor: Int = 0) =
        ext(XFixes.MINOR_QUERY_VERSION) { writer ->
            writer.int(clientMajor); writer.int(clientMinor)
        }

    fun selectSelectionInput(window: Int, selection: Int, mask: Int) =
        ext(XFixes.MINOR_SELECT_SELECTION_INPUT) { writer ->
            writer.int(window); writer.int(selection); writer.int(mask)
        }

    fun selectCursorInput(window: Int, mask: Int) =
        ext(XFixes.MINOR_SELECT_CURSOR_INPUT) { writer ->
            writer.int(window); writer.int(mask)
        }

    fun getCursorImage() = ext(XFixes.MINOR_GET_CURSOR_IMAGE)
    fun getCursorImageAndName() = ext(XFixes.MINOR_GET_CURSOR_IMAGE_AND_NAME)

    fun createRegion(id: Int, vararg rects: IntArray) = ext(XFixes.MINOR_CREATE_REGION) { writer ->
        writer.int(id)
        rects.forEach { r ->
            writer.short(r[0]); writer.short(r[1]); writer.short(r[2]); writer.short(r[3])
        }
    }

    fun createRegionFromBitmap(id: Int, pixmap: Int) =
        ext(XFixes.MINOR_CREATE_REGION_FROM_BITMAP) { writer -> writer.int(id); writer.int(pixmap) }

    fun createRegionFromWindow(id: Int, window: Int, kind: Int) =
        ext(XFixes.MINOR_CREATE_REGION_FROM_WINDOW) { writer ->
            writer.int(id); writer.int(window); writer.byte(kind); writer.pad(3)
        }

    fun createRegionFromGc(id: Int, gc: Int) =
        ext(XFixes.MINOR_CREATE_REGION_FROM_GC) { writer -> writer.int(id); writer.int(gc) }

    fun createRegionFromPicture(id: Int, picture: Int) =
        ext(XFixes.MINOR_CREATE_REGION_FROM_PICTURE) { writer -> writer.int(id); writer.int(picture) }

    fun destroyRegion(id: Int) = ext(XFixes.MINOR_DESTROY_REGION) { it.int(id) }

    fun copyRegion(source: Int, dest: Int) = ext(XFixes.MINOR_COPY_REGION) { writer ->
        writer.int(source); writer.int(dest)
    }

    fun combine(minor: Int, s1: Int, s2: Int, dest: Int) = ext(minor) { writer ->
        writer.int(s1); writer.int(s2); writer.int(dest)
    }

    fun invertRegion(region: Int, x: Int, y: Int, w: Int, h: Int, dest: Int) =
        ext(XFixes.MINOR_INVERT_REGION) { writer ->
            writer.int(region)
            writer.short(x); writer.short(y); writer.short(w); writer.short(h)
            writer.int(dest)
        }

    fun translateRegion(id: Int, dx: Int, dy: Int) = ext(XFixes.MINOR_TRANSLATE_REGION) { writer ->
        writer.int(id); writer.short(dx); writer.short(dy)
    }

    fun regionExtents(source: Int, dest: Int) = ext(XFixes.MINOR_REGION_EXTENTS) { writer ->
        writer.int(source); writer.int(dest)
    }

    fun expandRegion(source: Int, dest: Int, left: Int, right: Int, top: Int, bottom: Int) =
        ext(XFixes.MINOR_EXPAND_REGION) { writer ->
            writer.int(source); writer.int(dest)
            writer.short(left); writer.short(right); writer.short(top); writer.short(bottom)
        }

    fun fetchRegion(id: Int) = ext(XFixes.MINOR_FETCH_REGION) { it.int(id) }

    fun setGcClipRegion(gc: Int, region: Int, xOrigin: Int, yOrigin: Int) =
        ext(XFixes.MINOR_SET_GC_CLIP_REGION) { writer ->
            writer.int(gc); writer.int(region); writer.short(xOrigin); writer.short(yOrigin)
        }

    fun setWindowShapeRegion(window: Int, kind: Int, xOff: Int, yOff: Int, region: Int) =
        ext(XFixes.MINOR_SET_WINDOW_SHAPE_REGION) { writer ->
            writer.int(window); writer.byte(kind); writer.pad(3)
            writer.short(xOff); writer.short(yOff); writer.int(region)
        }

    fun setPictureClipRegion(picture: Int, region: Int, xOrigin: Int, yOrigin: Int) =
        ext(XFixes.MINOR_SET_PICTURE_CLIP_REGION) { writer ->
            writer.int(picture); writer.int(region); writer.short(xOrigin); writer.short(yOrigin)
        }

    fun setCursorName(cursor: Int, name: String) = ext(XFixes.MINOR_SET_CURSOR_NAME) { writer ->
        writer.int(cursor); writer.short(name.length); writer.pad(2)
        val bytes = name.toByteArray(Charsets.ISO_8859_1)
        writer.bytes(bytes)
        writer.pad(X11.pad4(bytes.size) - bytes.size)
    }

    fun getCursorName(cursor: Int) = ext(XFixes.MINOR_GET_CURSOR_NAME) { it.int(cursor) }

    fun changeCursor(source: Int, dest: Int) = ext(XFixes.MINOR_CHANGE_CURSOR) { writer ->
        writer.int(source); writer.int(dest)
    }

    fun changeCursorByName(source: Int, name: String) =
        ext(XFixes.MINOR_CHANGE_CURSOR_BY_NAME) { writer ->
            writer.int(source); writer.short(name.length); writer.pad(2)
            val bytes = name.toByteArray(Charsets.ISO_8859_1)
            writer.bytes(bytes)
            writer.pad(X11.pad4(bytes.size) - bytes.size)
        }

    fun hideCursor(window: Int) = ext(XFixes.MINOR_HIDE_CURSOR) { it.int(window) }
    fun showCursor(window: Int) = ext(XFixes.MINOR_SHOW_CURSOR) { it.int(window) }
    fun createPointerBarrier() = ext(XFixes.MINOR_CREATE_POINTER_BARRIER) { writer ->
        repeat(6) { writer.int(0) }
    }
}

private fun ByteArray.intLE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or ((this[offset + 3].toInt() and 0xFF) shl 24)

private fun ByteArray.shortLE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.shortLESigned(offset: Int): Int = shortLE(offset).toShort().toInt()

class XFixesTest {

    private val x = Xf()
    private val rootId = X11.RESOURCE_ID_BASE
    private lateinit var document: XDocument
    private lateinit var sink: RecordingSink
    private lateinit var connection: XConnection
    private var clipboard = 0

    private class RecordingSink : XSink {
        override fun fillRect(drawable: Int, x: Int, y: Int, width: Int, height: Int, pixel: Int) {}
        override fun putImage(drawable: Int, x: Int, y: Int, width: Int, height: Int, depth: Int, data: ByteArray) {}
        override fun imageText(drawable: Int, x: Int, y: Int, text: String, foreground: Int, background: Int?) {}
        override fun clearArea(drawable: Int, x: Int, y: Int, width: Int, height: Int) {}
        override fun copyArea(
            srcDrawable: Int, srcX: Int, srcY: Int,
            dstDrawable: Int, dstX: Int, dstY: Int,
            width: Int, height: Int,
        ) {}
    }

    @Before
    fun setUp() {
        document = XServer.defaultDocument(1280, 800)
        sink = RecordingSink()
        connection = XConnection(document, sink)
        clipboard = document.atomId("CLIPBOARD")!!
        send(x.createWindow(rootId + 1, rootId, 0, 0, 60, 40), x.mapWindow(rootId + 1))
        drain()
    }

    private fun send(vararg requests: ByteArray) {
        requests.forEach { connection.onBytes(it) }
    }

    private fun drain(): List<ByteArray> = connection.drainOutbox()
    private fun List<ByteArray>.replies() = filter { it[0].toInt() == 1 }
    private fun List<ByteArray>.errors() = filter { it[0].toInt() == 0 }
    private fun List<ByteArray>.events(type: Int) = filter { it[0].toInt() == type }

    private fun region(id: Int): XRegion? = fetchRegionRects(id)?.let { XRegion(it) }
    private fun fetchRegionRects(id: Int): List<IntArray>? {
        send(x.fetchRegion(id))
        val reply = drain().replies().singleOrNull() ?: return null
        val count = reply.intLE(4) * 4 / 8
        return List(count) { i ->
            intArrayOf(
                reply.shortLESigned(32 + i * 8), reply.shortLESigned(34 + i * 8),
                reply.shortLE(36 + i * 8), reply.shortLE(38 + i * 8),
            )
        }
    }

    // --- negotiation -----------------------------------------------------------

    @Test
    fun `QueryExtension hands out XFIXES bases`() {
        send(x.queryExtension("XFIXES"))
        val reply = drain().replies().single()
        assertEquals(1, reply[1].toInt())
        assertEquals(XFixes.MAJOR_OPCODE, reply[8].toInt() and 0xFF)
        assertEquals(XFixes.FIRST_EVENT, reply[9].toInt() and 0xFF)
        assertEquals(XFixes.FIRST_ERROR, reply[10].toInt() and 0xFF)
    }

    @Test
    fun `ListExtensions advertises XFIXES and SYNC`() {
        send(x.listExtensions())
        val reply = drain().replies().single()
        val count = reply[1].toInt() and 0xFF
        val names = mutableListOf<String>()
        var offset = 32
        repeat(count) {
            val len = reply[offset].toInt() and 0xFF
            names += String(reply.copyOfRange(offset + 1, offset + 1 + len), Charsets.US_ASCII)
            offset += X11.pad4(1 + len)
        }
        assertTrue(names.contains("XFIXES"))
        assertTrue(names.contains("SYNC"))
    }

    @Test
    fun `QueryVersion negotiates down from 6 and never up`() {
        send(x.queryVersion(6, 0))
        var reply = drain().replies().single()
        assertEquals(5, reply.intLE(8))
        assertEquals(0, reply.intLE(12))
        send(x.queryVersion(1, 0))
        reply = drain().replies().single()
        assertEquals(1, reply.intLE(8))
        assertEquals(0, reply.intLE(12))
        send(x.queryVersion(5, 3))
        reply = drain().replies().single()
        assertEquals(5, reply.intLE(8))
        assertEquals(0, reply.intLE(12)) // server minor caps the answer
    }

    // --- regions ----------------------------------------------------------------

    @Test
    fun `CreateRegion normalises and FetchRegion round-trips`() {
        // Two vertically adjacent 4x2 strips merge into one 4x4 rect.
        send(x.createRegion(rootId + 10, intArrayOf(0, 0, 4, 2), intArrayOf(0, 2, 4, 2)))
        assertTrue(drain().errors().isEmpty())
        val rects = fetchRegionRects(rootId + 10)!!
        assertEquals(1, rects.size)
        assertArrayEquals(intArrayOf(0, 0, 4, 4), rects[0])
    }

    @Test
    fun `FetchRegion carries the extents in its reply header`() {
        send(x.createRegion(rootId + 11, intArrayOf(2, 3, 4, 5), intArrayOf(20, 30, 2, 2)))
        send(x.fetchRegion(rootId + 11))
        val reply = drain().replies().single()
        assertEquals(2, reply.shortLESigned(8)) // x
        assertEquals(3, reply.shortLESigned(10)) // y
        assertEquals(20, reply.shortLE(12)) // width: 22-2
        assertEquals(29, reply.shortLE(14)) // height: 32-3
        assertEquals(2 * 8 / 4, reply.intLE(4)) // length in 4-byte units
    }

    @Test
    fun `union intersect subtract invert translate extents expand are real set algebra`() {
        val a = rootId + 20
        val b = rootId + 21
        val d = rootId + 22
        send(x.createRegion(a, intArrayOf(0, 0, 10, 10)))
        send(x.createRegion(b, intArrayOf(5, 5, 10, 10)))
        send(x.createRegion(d))
        // union
        send(x.combine(XFixes.MINOR_UNION_REGION, a, b, d))
        var rects = fetchRegionRects(d)!!
        assertTrue(XRegionOps.contains(rects, 0, 0) && XRegionOps.contains(rects, 14, 14))
        assertArrayEquals(intArrayOf(0, 0, 15, 15), XRegionOps.extents(rects))
        // intersect = the 5x5 overlap
        send(x.combine(XFixes.MINOR_INTERSECT_REGION, a, b, d))
        rects = fetchRegionRects(d)!!
        assertEquals(1, rects.size)
        assertArrayEquals(intArrayOf(5, 5, 5, 5), rects[0])
        // subtract = a minus the overlap
        send(x.combine(XFixes.MINOR_SUBTRACT_REGION, a, b, d))
        rects = fetchRegionRects(d)!!
        assertTrue(!XRegionOps.contains(rects, 7, 7))
        assertTrue(XRegionOps.contains(rects, 1, 1) && XRegionOps.contains(rects, 9, 2))
        // invert inside a bounding rect
        send(x.invertRegion(a, 0, 0, 12, 12, d))
        rects = fetchRegionRects(d)!!
        assertTrue(!XRegionOps.contains(rects, 5, 5))
        assertTrue(XRegionOps.contains(rects, 11, 11) && XRegionOps.contains(rects, 0, 11))
        // translate
        send(x.translateRegion(d, 100, -50))
        rects = fetchRegionRects(d)!!
        // d was the inverted band pair; (11,1) + (100,-50) lands inside the
        // translated right band (110,-50,2,12).
        assertTrue(XRegionOps.contains(rects, 111, -45))
        // extents into a single rect (destroy first: a CreateRegion on a live
        // id is BadIDChoice and must NOT touch the old region)
        send(x.destroyRegion(a))
        send(x.createRegion(a, intArrayOf(0, 0, 3, 3), intArrayOf(10, 10, 3, 3)))
        send(x.regionExtents(a, d))
        rects = fetchRegionRects(d)!!
        assertEquals(1, rects.size)
        assertArrayEquals(intArrayOf(0, 0, 13, 13), rects[0])
        // expand grows each rect by the margins
        send(x.destroyRegion(a))
        send(x.createRegion(a, intArrayOf(5, 5, 2, 2)))
        send(x.expandRegion(a, d, 1, 2, 3, 4))
        rects = fetchRegionRects(d)!!
        assertEquals(1, rects.size)
        assertArrayEquals(intArrayOf(4, 2, 5, 9), rects[0])
        // copy duplicates into an existing region
        send(x.copyRegion(a, d))
        assertArrayEquals(intArrayOf(5, 5, 2, 2), fetchRegionRects(d)!!.single())
    }

    @Test
    fun `unknown regions answer BadRegion with the extension error base`() {
        send(x.destroyRegion(999999))
        val error = drain().errors().single()
        assertEquals(XFixes.ERR_BAD_REGION, error[1].toInt() and 0xFF)
        assertEquals(XFixes.MAJOR_OPCODE, error[10].toInt() and 0xFF)
        assertEquals(XFixes.MINOR_DESTROY_REGION, error.shortLE(8))
    }

    @Test
    fun `duplicate CreateRegion answers BadIDChoice`() {
        send(x.createRegion(rootId + 30, intArrayOf(0, 0, 1, 1)))
        drain()
        send(x.createRegion(rootId + 30, intArrayOf(0, 0, 1, 1)))
        val error = drain().errors().single()
        assertEquals(X11.ERR_BAD_ID_CHOICE, error[1].toInt() and 0xFF)
    }

    @Test
    fun `DestroyRegion frees the id for reuse`() {
        send(x.createRegion(rootId + 31, intArrayOf(0, 0, 1, 1)))
        send(x.destroyRegion(rootId + 31))
        assertTrue(drain().errors().isEmpty())
        send(x.createRegion(rootId + 31, intArrayOf(2, 2, 2, 2)))
        assertTrue(drain().errors().isEmpty())
        assertArrayEquals(intArrayOf(2, 2, 2, 2), fetchRegionRects(rootId + 31)!!.single())
    }

    @Test
    fun `CreateRegionFromWindow answers bounding and clip kinds`() {
        val lower = rootId + 40
        val upper = rootId + 41
        send(
            x.createWindow(lower, rootId, 0, 0, 100, 100),
            x.mapWindow(lower),
            x.createWindow(upper, rootId, 50, 0, 100, 100),
            x.mapWindow(upper),
        )
        drain()
        // Bounding of the lower window = its full rect.
        send(x.createRegionFromWindow(rootId + 42, lower, XFixes.WINDOW_REGION_BOUNDING))
        assertArrayEquals(intArrayOf(0, 0, 100, 100), fetchRegionRects(rootId + 42)!!.single())
        // Clip = bounding minus the mapped sibling above (x 50..99 occluded).
        send(x.createRegionFromWindow(rootId + 43, lower, XFixes.WINDOW_REGION_CLIP))
        val clip = fetchRegionRects(rootId + 43)!!
        assertTrue(XRegionOps.contains(clip, 10, 10))
        assertTrue(!XRegionOps.contains(clip, 60, 10))
    }

    @Test
    fun `SetGCClipRegion round-trips through CreateRegionFromGC`() {
        val gc = rootId + 50
        send(x.createGc(gc, rootId + 1, 0xFF00FF00.toInt()))
        send(x.createRegion(rootId + 51, intArrayOf(1, 1, 2, 2)))
        send(x.setGcClipRegion(gc, rootId + 51, 10, 20))
        assertTrue(drain().errors().isEmpty())
        send(x.createRegionFromGc(rootId + 52, gc))
        assertArrayEquals(intArrayOf(11, 21, 2, 2), fetchRegionRects(rootId + 52)!!.single())
    }

    @Test
    fun `CreateRegionFromGC without a clip answers the screen extent`() {
        val gc = rootId + 55
        send(x.createGc(gc, rootId + 1, 0))
        send(x.createRegionFromGc(rootId + 56, gc))
        assertArrayEquals(intArrayOf(0, 0, 1280, 800), fetchRegionRects(rootId + 56)!!.single())
    }

    @Test
    fun `SetPictureClipRegion lands in the RENDER clip and round-trips`() {
        val pixmap = rootId + 60
        val picture = rootId + 61
        send(x.createPixmap(pixmap, rootId + 1, 8, 8, 24))
        send(x.createPicture(picture, pixmap, XRender.FORMAT_X8R8G8B8))
        send(x.createRegion(rootId + 62, intArrayOf(0, 0, 3, 3)))
        send(x.setPictureClipRegion(picture, rootId + 62, 2, 4))
        assertTrue(drain().errors().isEmpty())
        send(x.createRegionFromPicture(rootId + 63, picture))
        assertArrayEquals(intArrayOf(2, 4, 3, 3), fetchRegionRects(rootId + 63)!!.single())
    }

    @Test
    fun `CreateRegionFromBitmap scans the rasterised pixmap spans`() {
        val lists = XDisplayList()
        val listsConnection = XConnection(document, lists)
        listsConnection.onBytes(x.createPixmap(rootId + 70, rootId + 1, 6, 4, 1))
        listsConnection.onBytes(x.createGc(rootId + 71, rootId + 70, -1)) // white foreground
        listsConnection.onBytes(x.polyFillRectangle(rootId + 70, rootId + 71, 2, 1, 3, 2))
        listsConnection.drainOutbox()
        listsConnection.onBytes(x.createRegionFromBitmap(rootId + 72, rootId + 70))
        val errors = listsConnection.drainOutbox().filter { it[0].toInt() == 0 }
        assertTrue(errors.isEmpty())
        listsConnection.onBytes(x.fetchRegion(rootId + 72))
        val reply = listsConnection.drainOutbox().replies().single()
        val count = reply.intLE(4) * 4 / 8
        assertEquals(1, count) // the two 1px rows merged by normalisation
        assertEquals(2, reply.shortLESigned(32))
        assertEquals(1, reply.shortLESigned(34))
        assertEquals(3, reply.shortLE(36))
        assertEquals(2, reply.shortLE(38))
        // "Bitmap" means depth 1: a colour pixmap is BadMatch, per spec.
        listsConnection.onBytes(x.createPixmap(rootId + 75, rootId + 1, 4, 4, 24))
        listsConnection.onBytes(x.createRegionFromBitmap(rootId + 76, rootId + 75))
        val match = listsConnection.drainOutbox().filter { it[0].toInt() == 0 }.single()
        assertEquals(X11.ERR_BAD_MATCH, match[1].toInt() and 0xFF)
    }

    // --- selection watches -------------------------------------------------------

    @Test
    fun `SelectSelectionInput plus SetSelectionOwner delivers SelectionNotify`() {
        send(x.selectSelectionInput(rootId + 1, clipboard, XFixes.MASK_SET_SELECTION_OWNER))
        drain()
        send(x.setSelectionOwner(rootId + 1, clipboard))
        val event = drain().events(XFixes.EV_SELECTION_NOTIFY).single()
        assertEquals(XFixes.SUBTYPE_SELECTION_SET, event[1].toInt())
        assertEquals(rootId + 1, event.intLE(4)) // watching window
        assertEquals(rootId + 1, event.intLE(8)) // new owner
        assertEquals(clipboard, event.intLE(12)) // selection atom
        assertTrue(event.intLE(16) != 0) // timestamp filled
        assertTrue(event.intLE(20) != 0) // selection timestamp filled
    }

    @Test
    fun `a root-window watch sees ANOTHER client claim the selection - GTK's clipboard idiom`() {
        val watcher = XConnection(document, sink)
        val claimer = XConnection(document, sink)
        val watcherWindow = rootId + 80
        val claimerWindow = rootId + 81
        watcher.onBytes(x.createWindow(watcherWindow, rootId, 0, 0, 10, 10))
        claimer.onBytes(x.createWindow(claimerWindow, rootId, 0, 0, 10, 10))
        watcher.drainOutbox(); claimer.drainOutbox()
        // The watch sits on the ROOT window (id 0 allowed? X uses root) - use rootId.
        watcher.onBytes(x.selectSelectionInput(rootId, clipboard, XFixes.MASK_SET_SELECTION_OWNER))
        watcher.drainOutbox()
        claimer.onBytes(x.setSelectionOwner(claimerWindow, clipboard))
        claimer.drainOutbox()
        val event = watcher.drainOutbox().events(XFixes.EV_SELECTION_NOTIFY).single()
        assertEquals(rootId, event.intLE(4)) // the watched window (root)
        assertEquals(claimerWindow, event.intLE(8)) // the claimer's window
    }

    @Test
    fun `destroying the owner window delivers the WindowDestroy subtype`() {
        val watcher = XConnection(document, sink)
        val owner = XConnection(document, sink)
        val ownerWindow = rootId + 90
        watcher.onBytes(x.createWindow(rootId + 91, rootId, 0, 0, 10, 10))
        owner.onBytes(x.createWindow(ownerWindow, rootId, 0, 0, 10, 10))
        watcher.drainOutbox(); owner.drainOutbox()
        watcher.onBytes(x.selectSelectionInput(rootId + 91, clipboard, 0b111))
        owner.onBytes(x.setSelectionOwner(ownerWindow, clipboard))
        watcher.drainOutbox(); owner.drainOutbox()
        owner.onBytes(x.destroyWindow(ownerWindow))
        owner.drainOutbox()
        val event = watcher.drainOutbox().events(XFixes.EV_SELECTION_NOTIFY).single()
        assertEquals(XFixes.SUBTYPE_SELECTION_DESTROY, event[1].toInt())
    }

    @Test
    fun `a closing client delivers the ClientClose subtype`() {
        val owner = XConnection(document, sink)
        val ownerWindow = rootId + 95
        owner.onBytes(x.createWindow(ownerWindow, rootId, 0, 0, 10, 10))
        owner.onBytes(x.setSelectionOwner(ownerWindow, clipboard))
        owner.drainOutbox()
        send(x.selectSelectionInput(rootId + 1, clipboard, XFixes.MASK_SELECTION_CLIENT_CLOSE))
        drain()
        document.releaseSelections(owner) // what XServer calls when the socket dies
        val event = drain().events(XFixes.EV_SELECTION_NOTIFY).single()
        assertEquals(XFixes.SUBTYPE_CLIENT_CLOSE, event[1].toInt())
    }

    @Test
    fun `the event mask filters subtypes and a zero mask unregisters`() {
        send(x.selectSelectionInput(rootId + 1, clipboard, XFixes.MASK_SELECTION_WINDOW_DESTROY))
        drain()
        send(x.setSelectionOwner(rootId + 1, clipboard))
        assertTrue(drain().events(XFixes.EV_SELECTION_NOTIFY).isEmpty()) // SET not selected
        send(x.selectSelectionInput(rootId + 1, clipboard, 0))
        drain()
        document.setSelectionOwner(clipboard, rootId + 1, null)
        send(x.setSelectionOwner(0, clipboard)) // a clear: fires DESTROY-shaped notify? (SET subtype, owner 0)
        assertTrue(drain().events(XFixes.EV_SELECTION_NOTIFY).isEmpty()) // mask 0 removed the watch
    }

    @Test
    fun `destroying the WATCHED window stops its events`() {
        send(x.selectSelectionInput(rootId + 1, clipboard, 0b111))
        drain()
        send(x.destroyWindow(rootId + 1))
        drain()
        val other = XConnection(document, sink)
        other.onBytes(x.createWindow(rootId + 99, rootId, 0, 0, 10, 10))
        other.onBytes(x.setSelectionOwner(rootId + 99, clipboard))
        other.drainOutbox()
        assertTrue(drain().events(XFixes.EV_SELECTION_NOTIFY).isEmpty())
    }

    // --- cursor watches, names, images --------------------------------------------

    @Test
    fun `CWCursor on a watched window delivers CursorNotify`() {
        val font = rootId + 100
        val cursor = rootId + 101
        send(x.openFont(font, "cursor"), x.createGlyphCursor(cursor, font, CursorGlyph.XTERM))
        send(x.selectCursorInput(rootId + 1, XFixes.MASK_DISPLAY_CURSOR))
        drain()
        send(x.changeCursorAttr(rootId + 1, cursor))
        val event = drain().events(XFixes.EV_CURSOR_NOTIFY).single()
        assertEquals(XFixes.SUBTYPE_DISPLAY_CURSOR, event[1].toInt())
        assertEquals(rootId + 1, event.intLE(4))
        assertTrue(event.intLE(8) >= 1) // cursor serial
        assertTrue(event.intLE(16) == 0 || event.intLE(16) > 0) // name atom (may be None)
    }

    @Test
    fun `SetCursorName and GetCursorName round-trip through an atom`() {
        val font = rootId + 110
        val cursor = rootId + 111
        send(x.openFont(font, "cursor"), x.createGlyphCursor(cursor, font, CursorGlyph.LEFT_PTR))
        send(x.setCursorName(cursor, "my-pointer"))
        send(x.getCursorName(cursor))
        val reply = drain().replies().single()
        val atom = reply.intLE(8)
        assertEquals(document.atomId("my-pointer"), atom)
        assertEquals(10, reply.shortLE(12))
        val name = String(reply.copyOfRange(32, 42), Charsets.ISO_8859_1)
        assertEquals("my-pointer", name)
    }

    @Test
    fun `GetCursorImage answers the built-in 16x16 raster`() {
        send(x.getCursorImage())
        val reply = drain().replies().single()
        assertEquals(16, reply.shortLE(12))
        assertEquals(16, reply.shortLE(14))
        assertEquals(256 * 4 / 4, reply.intLE(4)) // length = 256 pixels
        assertTrue(reply.size >= 32 + 1024)
    }

    @Test
    fun `GetCursorImageAndName puts the name before the pixels`() {
        val font = rootId + 120
        val cursor = rootId + 121
        send(x.openFont(font, "cursor"), x.createGlyphCursor(cursor, font, CursorGlyph.LEFT_PTR))
        send(x.changeCursorAttr(rootId + 1, cursor))
        send(x.setCursorName(cursor, "ptr"))
        drain()
        send(x.getCursorImageAndName())
        val reply = drain().replies().single()
        // reply layout: x,y,w,h,xhot,yhot (8..19), serial (20), name atom (24),
        // nbytes (28), then name + pixels in the extra body.
        assertEquals(document.atomId("ptr"), reply.intLE(24))
        assertEquals(3, reply.shortLE(28))
        assertEquals("ptr", String(reply.copyOfRange(32, 35), Charsets.ISO_8859_1))
        val expectedLength = (X11.pad4(3) + 256 * 4) / 4
        assertEquals(expectedLength, reply.intLE(4))
    }

    @Test
    fun `ChangeCursor copies the source resource into the destination`() {
        val font = rootId + 130
        val src = rootId + 131
        val dst = rootId + 132
        send(
            x.openFont(font, "cursor"),
            x.createGlyphCursor(src, font, CursorGlyph.XTERM),
            x.createGlyphCursor(dst, font, CursorGlyph.CROSSHAIR),
        )
        send(x.changeCursor(src, dst))
        assertTrue(drain().errors().isEmpty())
        assertEquals(CursorGlyph.XTERM, document.cursor(dst)?.glyph)
    }

    @Test
    fun `ChangeCursorByName finds the destination by its XFIXES name`() {
        val font = rootId + 140
        val src = rootId + 141
        val dst = rootId + 142
        send(
            x.openFont(font, "cursor"),
            x.createGlyphCursor(src, font, CursorGlyph.WATCH),
            x.createGlyphCursor(dst, font, CursorGlyph.LEFT_PTR),
        )
        send(x.setCursorName(dst, "dest-cursor"))
        send(x.changeCursorByName(src, "dest-cursor"))
        assertTrue(drain().errors().isEmpty())
        assertEquals(CursorGlyph.WATCH, document.cursor(dst)?.glyph)
        // An unknown name is BadName, not a silent no-op.
        send(x.changeCursorByName(src, "no-such-cursor"))
        assertEquals(X11.ERR_BAD_NAME, drain().errors().single()[1].toInt() and 0xFF)
    }

    @Test
    fun `HideCursor makes the software cursor disappear and ShowCursor brings it back`() {
        send(x.hideCursor(rootId + 1))
        assertTrue(drain().errors().isEmpty())
        assertEquals(XDocument.CURSOR_HIDDEN, document.effectiveCursorGlyph(10, 10))
        send(x.showCursor(rootId + 1))
        assertTrue(document.effectiveCursorGlyph(10, 10) >= 0)
    }

    // --- window shapes -------------------------------------------------------------

    @Test
    fun `an INPUT shape removes the window from hit-testing outside its region`() {
        val lower = rootId + 150
        val upper = rootId + 151
        send(
            x.createWindow(lower, rootId, 0, 0, 100, 100),
            x.mapWindow(lower),
            x.createWindow(upper, rootId, 0, 0, 100, 100),
            x.mapWindow(upper),
        )
        drain()
        assertEquals(upper, document.windowAt(50, 50)?.id)
        // Shape the top window to its left half only.
        send(x.createRegion(rootId + 152, intArrayOf(0, 0, 50, 100)))
        send(x.setWindowShapeRegion(upper, XFixes.SHAPE_INPUT, 0, 0, rootId + 152))
        assertTrue(drain().errors().isEmpty())
        assertEquals(upper, document.windowAt(25, 50)?.id) // inside the shape
        assertEquals(lower, document.windowAt(75, 50)?.id) // outside: the window below
        // Region 0 (None) clears the shape.
        send(x.setWindowShapeRegion(upper, XFixes.SHAPE_INPUT, 0, 0, 0))
        assertEquals(upper, document.windowAt(75, 50)?.id)
    }

    @Test
    fun `SetWindowShapeRegion validates window, region and kind`() {
        send(x.setWindowShapeRegion(424242, XFixes.SHAPE_INPUT, 0, 0, 0))
        assertEquals(X11.ERR_BAD_WINDOW, drain().errors().single()[1].toInt() and 0xFF)
        send(x.createRegion(rootId + 160, intArrayOf(0, 0, 1, 1)))
        send(x.setWindowShapeRegion(rootId + 1, 9, 0, 0, rootId + 160))
        assertEquals(X11.ERR_BAD_VALUE, drain().errors().single()[1].toInt() and 0xFF)
        send(x.setWindowShapeRegion(rootId + 1, XFixes.SHAPE_BOUNDING, 0, 0, 555555))
        val error = drain().errors().single()
        assertEquals(XFixes.ERR_BAD_REGION, error[1].toInt() and 0xFF)
    }

    // --- the refused v6 tail -------------------------------------------------------

    @Test
    fun `pointer barriers are refused BadRequest and named in the trace`() {
        send(x.createPointerBarrier())
        val error = drain().errors().single()
        assertEquals(X11.ERR_BAD_REQUEST, error[1].toInt() and 0xFF)
        assertEquals(XFixes.MAJOR_OPCODE, error[10].toInt() and 0xFF)
        assertEquals(XFixes.MINOR_CREATE_POINTER_BARRIER, error.shortLE(8))
        assertTrue(connection.trace.unimplementedOpcodes().containsKey("133.31"))
        assertTrue(connection.trace.summary().contains("XFIXES.CreatePointerBarrier"))
    }
}
