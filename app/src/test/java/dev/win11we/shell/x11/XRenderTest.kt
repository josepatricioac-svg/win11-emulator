package dev.win11we.shell.x11

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * D1d RENDER wire tests. Every request is built byte-for-byte the way
 * xorgproto's `renderproto.h` lays it out, and every reply is checked field by
 * field, because a reply that is one byte off desynchronises the connection and
 * GTK `_exit(1)`s without a word on stderr.
 *
 * The version answer matters as much as the layouts: we claim 0.4 so clients
 * never reach for AddTraps/CreateSolidFill/ARGB cursors we do not implement
 * (see XRender's KDoc). A client asking for 0.11 must be told 0.4.
 */
private class RenderRequests {
    fun build(major: Int = XRender.MAJOR_OPCODE, minor: Int, body: (XWriter) -> Unit = {}): ByteArray {
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

    fun core(opcode: Int, data: Int = 0, body: (XWriter) -> Unit = {}): ByteArray {
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

    fun createWindow(id: Int, parent: Int, width: Int, height: Int) =
        core(X11.REQ_CREATE_WINDOW, 24) { writer ->
            writer.int(id); writer.int(parent)
            writer.short(0); writer.short(0); writer.short(width); writer.short(height)
            writer.short(0); writer.short(0); writer.int(0x21); writer.int(0)
        }

    fun createPixmap(id: Int, drawable: Int, width: Int, height: Int, depth: Int = 24) =
        core(X11.REQ_CREATE_PIXMAP, depth) { writer ->
            writer.int(id); writer.int(drawable); writer.short(width); writer.short(height)
        }

    // --- RENDER -------------------------------------------------------------
    fun queryVersion(clientMajor: Int = 0, clientMinor: Int = 11) =
        build(minor = XRender.MINOR_QUERY_VERSION) { writer ->
            writer.int(clientMajor); writer.int(clientMinor)
        }

    fun queryPictFormats() = build(minor = XRender.MINOR_QUERY_PICT_FORMATS)

    fun createPicture(
        pid: Int,
        drawable: Int,
        format: Int,
        mask: Int = 0,
        vararg values: Int,
    ) = build(minor = XRender.MINOR_CREATE_PICTURE) { writer ->
        writer.int(pid); writer.int(drawable); writer.int(format); writer.int(mask)
        values.forEach { writer.int(it) }
    }

    fun freePicture(pid: Int) = build(minor = XRender.MINOR_FREE_PICTURE) { it.int(pid) }

    fun setClipRectangles(pid: Int, xOrigin: Int, yOrigin: Int, rects: List<IntArray>) =
        build(minor = XRender.MINOR_SET_PICTURE_CLIP_RECTANGLES) { writer ->
            writer.int(pid); writer.short(xOrigin); writer.short(yOrigin)
            rects.forEach { r -> writer.short(r[0]); writer.short(r[1]); writer.short(r[2]); writer.short(r[3]) }
        }

    fun composite(
        op: Int,
        src: Int,
        mask: Int,
        dst: Int,
        xSrc: Int = 0,
        ySrc: Int = 0,
        xMask: Int = 0,
        yMask: Int = 0,
        xDst: Int = 0,
        yDst: Int = 0,
        width: Int = 1,
        height: Int = 1,
    ) = build(minor = XRender.MINOR_COMPOSITE) { writer ->
        writer.byte(op); writer.pad(3)
        writer.int(src); writer.int(mask); writer.int(dst)
        writer.short(xSrc); writer.short(ySrc)
        writer.short(xMask); writer.short(yMask)
        writer.short(xDst); writer.short(yDst)
        writer.short(width); writer.short(height)
    }

    fun fillRectangles(op: Int, dst: Int, color: IntArray, rects: List<IntArray>) =
        build(minor = XRender.MINOR_FILL_RECTANGLES) { writer ->
            writer.byte(op); writer.pad(3)
            writer.int(dst)
            color.forEach { writer.short(it) } // red, green, blue, alpha as CARD16
            rects.forEach { r -> writer.short(r[0]); writer.short(r[1]); writer.short(r[2]); writer.short(r[3]) }
        }

    fun createGlyphSet(gsid: Int, format: Int) =
        build(minor = XRender.MINOR_CREATE_GLYPH_SET) { writer -> writer.int(gsid); writer.int(format) }

    /** One 2x2 glyph, id 65: alpha rows {0x00,0xFF}, {0x80,0x40}, scanlines padded to 4 bytes. */
    fun addGlyphs(gsid: Int, glyphId: Int = 65, depth: Int = 8) =
        build(minor = XRender.MINOR_ADD_GLYPHS) { writer ->
            writer.int(gsid); writer.int(1) // glyphset, nglyphs
            writer.int(glyphId)
            // xGlyphInfo: width, height, x, y, xOff, yOff (12 bytes)
            writer.short(2); writer.short(2)
            writer.short(0); writer.short(0)
            writer.short(2); writer.short(0)
            // image data: 4 bytes per padded scanline, two scanlines
            if (depth == 8) {
                writer.byte(0x00); writer.byte(0xFF); writer.pad(2)
                writer.byte(0x80); writer.byte(0x40); writer.pad(2)
            } else {
                // depth 1: row0 = bits (LSB first) 0,1 -> 0b10 = 0x02; row1 = 1,0 -> 0b01 = 0x01
                writer.byte(0x02); writer.pad(3)
                writer.byte(0x01); writer.pad(3)
            }
        }

    fun addGlyphsTruncated(gsid: Int) = build(minor = XRender.MINOR_ADD_GLYPHS) { writer ->
        writer.int(gsid); writer.int(1)
        writer.int(65)
        writer.short(2); writer.short(2); writer.short(0); writer.short(0); writer.short(2); writer.short(0)
        writer.byte(0x00) // one byte of a promised 8: BadLength
    }

    fun compositeGlyphs8(op: Int, src: Int, dst: Int, glyphset: Int, dx: Int = 10, dy: Int = 20, vararg glyphIds: Int) =
        build(minor = XRender.MINOR_COMPOSITE_GLYPHS_8) { writer ->
            writer.byte(op); writer.pad(3)
            writer.int(src); writer.int(dst); writer.int(XRender.FORMAT_A8); writer.int(glyphset)
            writer.short(0); writer.short(0) // xSrc, ySrc
            // one glyph element: len, pad(3), deltax, deltay, then the ids
            writer.byte(glyphIds.size); writer.pad(3)
            writer.short(dx); writer.short(dy)
            glyphIds.forEach { writer.byte(it) }
            writer.pad(X11.padded(glyphIds.size)) // the id run pads to 4 bytes
            writer.byte(0); writer.pad(7) // terminating element (len 0)
        }

    fun setPictureTransform(pid: Int) = build(minor = XRender.MINOR_SET_PICTURE_TRANSFORM) { writer ->
        writer.int(pid)
        repeat(9) { writer.int(0) } // any matrix; we refuse the request itself
    }
}

class XRenderTest {

    private val r = RenderRequests()
    private val rootId = X11.RESOURCE_ID_BASE
    private lateinit var document: XDocument
    private lateinit var sink: RecordingSink
    private lateinit var connection: XConnection

    private val windowId = rootId + 1
    private val pixmapId = rootId + 2
    private val srcPicture = rootId + 10
    private val dstPicture = rootId + 11

    @Before
    fun setUp() {
        document = XServer.defaultDocument(1280, 800)
        sink = RecordingSink()
        connection = XConnection(document, sink)
        send(r.createWindow(windowId, rootId, 8, 8))
        send(r.createPixmap(pixmapId, windowId, 2, 2))
        drain()
    }

    private fun send(vararg requests: ByteArray) {
        requests.forEach { connection.onBytes(it) }
    }

    private fun drain(): List<ByteArray> = connection.drainOutbox()

    private fun List<ByteArray>.replies() = filter { it[0].toInt() == 1 }
    private fun List<ByteArray>.errors() = filter { it[0].toInt() == 0 }
    private fun ByteArray.intLE(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or ((this[offset + 3].toInt() and 0xFF) shl 24)

    /** A picture on the 2x2 pixmap in RGB24 shape; used as a Composite source. */
    private fun createSrcPicture(format: Int = XRender.FORMAT_X8R8G8B8) {
        send(r.createPicture(srcPicture, pixmapId, format))
        drain()
    }

    // --- negotiation ----------------------------------------------------------

    @Test
    fun `QueryExtension hands out RENDER's major opcode and error base`() {
        drain()
        send(
            r.core(X11.REQ_QUERY_EXTENSION) { writer ->
                writer.short(6); writer.pad(2); writer.paddedString("RENDER")
            },
        )
        val reply = drain().replies().single()
        assertEquals(1, reply[1].toInt()) // present
        assertEquals(XRender.MAJOR_OPCODE, reply[8].toInt() and 0xFF)
        assertEquals(0, reply[9].toInt() and 0xFF) // no events
        assertEquals(XRender.FIRST_ERROR, reply[10].toInt() and 0xFF)
    }

    @Test
    fun `QueryExtension says no for extensions we do not implement`() {
        send(
            r.core(X11.REQ_QUERY_EXTENSION) { writer ->
                writer.short(3); writer.pad(2); writer.paddedString("XKB")
            },
        )
        val reply = drain().replies().single()
        assertEquals(0, reply[1].toInt())
        assertEquals(0, reply[8].toInt() and 0xFF)
        assertTrue(connection.trace.extensionsAsked().getValue("XKB").not())
    }

    @Test
    fun `QueryVersion answers 0_4 and never more than the client asked`() {
        send(r.queryVersion(0, 11))
        var reply = drain().replies().single()
        assertEquals(0, reply.intLE(8))
        assertEquals(4, reply.intLE(12))
        assertEquals(0, reply.intLE(4)) // no extra data

        send(r.queryVersion(0, 1))
        reply = drain().replies().single()
        assertEquals(1, reply.intLE(12)) // capped at the client's own minor

        send(r.queryVersion(2, 0))
        reply = drain().replies().single()
        assertEquals(0, reply.intLE(8)) // our major, never the client's
        assertEquals(4, reply.intLE(12))
    }

    @Test
    fun `QueryPictFormats reports the formats, the screen and the visual mapping`() {
        send(r.queryPictFormats())
        val reply = drain().replies().single()
        val numFormats = reply.intLE(8)
        val numScreens = reply.intLE(12)
        val numDepths = reply.intLE(16)
        val numVisuals = reply.intLE(20)
        val numSubpixel = reply.intLE(24)
        assertEquals(XRender.FORMATS.size, numFormats)
        assertEquals(1, numScreens)
        assertEquals(XRender.SCREEN_DEPTHS.size, numDepths)
        assertEquals(2, numVisuals)
        assertEquals(1, numSubpixel)

        // The declared length must match the body exactly (mismatch = desync = dead GTK).
        assertEquals(0, connection.trace.replyLengthMismatches)
        val declaredExtra = reply.intLE(4) * 4
        assertEquals(reply.size - 32, declaredExtra)

        // Walk the format table: 28 bytes per xPictFormInfo.
        val argb = XRender.FORMATS.first { it.id == XRender.FORMAT_A8R8G8B8 }
        var foundArgb = false
        var foundA8 = false
        for (index in 0 until numFormats) {
            val base = 32 + index * 28
            val id = reply.intLE(base)
            val type = reply[base + 4].toInt() and 0xFF
            val depth = reply[base + 5].toInt() and 0xFF
            assertEquals("every format is Direct", 1, type)
            if (id == argb.id) {
                foundArgb = true
                assertEquals(32, depth)
                assertEquals(16, reply.intLE(base + 8) and 0xFFFF) // red shift
                assertEquals(0xFF, reply.intLE(base + 10) and 0xFFFF) // red mask
                assertEquals(8, reply.intLE(base + 12) and 0xFFFF) // green shift
                assertEquals(0, reply.intLE(base + 16) and 0xFFFF) // blue shift
                assertEquals(24, reply.intLE(base + 20) and 0xFFFF) // alpha shift
                assertEquals(0xFF, reply.intLE(base + 22) and 0xFFFF)
                assertEquals(0, reply.intLE(base + 24)) // colormap: None
            }
            if (id == XRender.FORMAT_A8) {
                foundA8 = true
                assertEquals(8, depth)
                assertEquals(0, reply.intLE(base + 20) and 0xFFFF)
                assertEquals(0xFF, reply.intLE(base + 22) and 0xFFFF)
            }
        }
        assertTrue(foundArgb && foundA8)

        // Screen block: nDepth + fallback, then per-depth {depth, nPictVisuals},
        // and the depth-24 visual list mapping 0x21 -> RGB24.
        var cursor = 32 + numFormats * 28
        assertEquals(numDepths, reply.intLE(cursor)); cursor += 4
        assertEquals(XRender.FORMAT_X8R8G8B8, reply.intLE(cursor)); cursor += 4
        var visualsSeen = 0
        repeat(numDepths) {
            val depth = reply[cursor].toInt() and 0xFF
            val nVisuals = reply.intLE(cursor + 2) and 0xFFFF
            cursor += 8
            assertTrue("depth $depth is one we announced", depth in XRender.SCREEN_DEPTHS)
            if (nVisuals > 0) {
                assertEquals(24, depth)
                repeat(nVisuals) {
                    val visualId = reply.intLE(cursor)
                    val formatId = reply.intLE(cursor + 4)
                    cursor += 8
                    visualsSeen++
                    assertTrue(XRender.visualFormats().contains(visualId to formatId))
                }
            }
        }
        assertEquals(numVisuals, visualsSeen)
        assertEquals(0, reply.intLE(cursor)) // subpixel order: Unknown
        assertEquals(reply.size, cursor + 4)
    }

    // --- pictures -------------------------------------------------------------

    @Test
    fun `CreatePicture refuses an unknown format, a bad drawable and a depth mismatch`() {
        send(r.createPicture(srcPicture, pixmapId, 0x9999))
        var error = drain().errors().single()
        assertEquals(XRender.ERR_PICT_FORMAT, error[1].toInt() and 0xFF)
        assertEquals(0x9999, error.intLE(4))
        assertEquals(XRender.MAJOR_OPCODE, error[10].toInt() and 0xFF)
        assertEquals(XRender.MINOR_CREATE_PICTURE, error.intLE(8) and 0xFFFF) // minor opcode

        send(r.createPicture(srcPicture, rootId + 999, XRender.FORMAT_X8R8G8B8))
        error = drain().errors().single()
        assertEquals(X11.ERR_BAD_DRAWABLE, error[1].toInt() and 0xFF)

        // The 2x2 pixmap is depth 24; an a8 picture on it is a BadMatch.
        send(r.createPicture(srcPicture, pixmapId, XRender.FORMAT_A8))
        error = drain().errors().single()
        assertEquals(X11.ERR_BAD_MATCH, error[1].toInt() and 0xFF)
        assertTrue("refusals must be counted", connection.trace.errorsSent >= 3)
    }

    @Test
    fun `FreePicture on an unknown id is BadPicture`() {
        send(r.freePicture(0x5150))
        val error = drain().errors().single()
        assertEquals(XRender.ERR_PICTURE, error[1].toInt() and 0xFF)
        assertEquals(XRender.MINOR_FREE_PICTURE, error.intLE(8) and 0xFFFF)
    }

    @Test
    fun `CreatePicture consumes value masks in bit order`() {
        // CPRepeat | CPClipXOrigin | CPClipMask | CPComponentAlpha: 4 values in
        // ascending bit order. Reading them positionally would corrupt the stream,
        // so the following request must still be answered correctly.
        send(
            r.createPicture(
                srcPicture, pixmapId, XRender.FORMAT_X8R8G8B8,
                mask = XRender.CP_REPEAT or XRender.CP_CLIP_X_ORIGIN or
                    XRender.CP_CLIP_MASK or XRender.CP_COMPONENT_ALPHA,
                values = intArrayOf(XRender.REPEAT_NORMAL, 3, pixmapId, 1),
            ),
        )
        send(r.queryVersion(0, 11))
        val out = drain()
        assertTrue("no error for a valid value mask", out.errors().isEmpty())
        val reply = out.replies().single()
        assertEquals(4, reply.intLE(12))
    }

    // --- Composite / FillRectangles -------------------------------------------

    @Test
    fun `Composite records the blend the rasterisers will replay`() {
        createSrcPicture()
        send(r.createPicture(dstPicture, windowId, XRender.FORMAT_X8R8G8B8))
        send(r.composite(XRender.OP_OVER, srcPicture, 0, dstPicture, xDst = 1, yDst = 2, width = 2, height = 2))
        val out = drain()
        assertTrue(out.errors().joinToString { it.toString() }, out.errors().isEmpty())
        val spec = sink.blends.single()
        assertEquals(XRender.OP_OVER, spec.op)
        assertEquals(pixmapId, spec.srcDrawable)
        assertEquals(0, spec.maskDrawable)
        assertNull(spec.maskColor)
        assertEquals(1, spec.dstX)
        assertEquals(2, spec.dstY)
        assertEquals(2, spec.width)
        assertEquals(2, spec.height)
        assertEquals(XRender.REPEAT_NONE, spec.srcRepeat)
        assertNull(spec.clip)
    }

    @Test
    fun `Composite refuses an unknown picture and an unknown operator`() {
        createSrcPicture()
        send(r.composite(XRender.OP_OVER, srcPicture, 0, 0xDEAD, width = 1, height = 1))
        var error = drain().errors().single()
        assertEquals(XRender.ERR_PICTURE, error[1].toInt() and 0xFF)
        assertEquals(0xDEAD, error.intLE(4))

        send(r.createPicture(dstPicture, windowId, XRender.FORMAT_X8R8G8B8))
        // 0x20 is DisjointClear: beyond the 14 core ops we implement.
        send(r.composite(0x20, srcPicture, 0, dstPicture, width = 1, height = 1))
        error = drain().errors().single()
        assertEquals(XRender.ERR_PICT_OP, error[1].toInt() and 0xFF)
        assertEquals(0x20, error.intLE(4))
        assertTrue(sink.blends.isEmpty())
    }

    @Test
    fun `clip rectangles reach the blend spec translated by the clip origin`() {
        createSrcPicture()
        send(r.createPicture(dstPicture, windowId, XRender.FORMAT_X8R8G8B8))
        send(r.setClipRectangles(dstPicture, 5, 7, listOf(intArrayOf(1, 1, 3, 3))))
        send(r.composite(XRender.OP_SRC, srcPicture, 0, dstPicture, width = 2, height = 2))
        val out = drain()
        assertTrue(out.errors().joinToString { String(it) }, out.errors().isEmpty())
        val clip = sink.blends.single().clip
        assertNotNull(clip)
        assertEquals(1, clip!!.size)
        assertEquals(6, clip[0][0]) // 1 + xOrigin 5
        assertEquals(8, clip[0][1]) // 1 + yOrigin 7
        assertEquals(3, clip[0][2])
        assertEquals(3, clip[0][3])
    }

    @Test
    fun `FillRectangles converts CARD16 colours to ARGB and keeps the op`() {
        send(r.createPicture(dstPicture, windowId, XRender.FORMAT_X8R8G8B8))
        send(
            r.fillRectangles(
                XRender.OP_SRC, dstPicture,
                color = intArrayOf(0xFFFF, 0x8080, 0x0000, 0xFFFF), // red, half green, no blue
                rects = listOf(intArrayOf(0, 0, 4, 4), intArrayOf(5, 5, 1, 1)),
            ),
        )
        val out = drain()
        assertTrue(out.errors().joinToString { String(it) }, out.errors().isEmpty())
        assertEquals(2, sink.blends.size)
        val first = sink.blends[0]
        assertEquals(XRender.OP_SRC, first.op)
        assertEquals(0, first.srcDrawable)
        assertEquals(0xFFFF8000.toInt(), first.srcColor)
        assertEquals(4, first.width)
        assertEquals(5, sink.blends[1].dstX)
        assertEquals(5, sink.blends[1].dstY)
    }

    @Test
    fun `a clip-mask pixmap becomes the blend mask at the clip origin`() {
        createSrcPicture()
        send(r.createPicture(dstPicture, windowId, XRender.FORMAT_X8R8G8B8))
        // CPRepeat | CPClipXOrigin | CPClipYOrigin | CPClipMask, values in bit order.
        send(
            r.createPicture(
                dstPicture, windowId, XRender.FORMAT_X8R8G8B8,
                mask = XRender.CP_CLIP_X_ORIGIN or XRender.CP_CLIP_Y_ORIGIN or XRender.CP_CLIP_MASK,
                values = intArrayOf(2, 3, pixmapId),
            ),
        )
        send(r.composite(XRender.OP_OVER, srcPicture, 0, dstPicture, xDst = 10, yDst = 10, width = 2, height = 2))
        val out = drain()
        assertTrue(out.errors().joinToString { String(it) }, out.errors().isEmpty())
        val spec = sink.blends.single()
        assertEquals(pixmapId, spec.maskDrawable)
        // mask coordinate = dst coordinate - clip origin
        assertEquals(10 - 2, spec.maskX)
        assertEquals(10 - 3, spec.maskY)
    }

    // --- glyphs ---------------------------------------------------------------

    @Test
    fun `AddGlyphs decodes a8 bitmaps into 8-bit alpha`() {
        send(r.createGlyphSet(0xA11CE, XRender.FORMAT_A8))
        send(r.addGlyphs(0xA11CE))
        val out = drain()
        assertTrue(out.errors().joinToString { String(it) }, out.errors().isEmpty())
        // Prove the decode through a CompositeGlyphs8 that draws it.
        createSrcPicture()
        send(r.createPicture(dstPicture, windowId, XRender.FORMAT_X8R8G8B8))
        send(r.compositeGlyphs8(XRender.OP_OVER, srcPicture, dstPicture, 0xA11CE, 10, 20, 65))
        val out2 = drain()
        assertTrue(out2.errors().joinToString { String(it) }, out2.errors().isEmpty())
        val run = sink.glyphRuns.single()
        val placement = run.placements.single()
        assertEquals(10, placement.dstX) // pen delta 10 + glyph.x 0
        assertEquals(20, placement.dstY)
        val glyph = placement.glyph
        assertEquals(2, glyph.width)
        assertEquals(2, glyph.height)
        assertEquals(2, glyph.xOff) // the pen advance
        assertEquals(byteArrayOf(0x00, 0xFF.toByte(), 0x80.toByte(), 0x40).toList(), glyph.alpha.toList())
    }

    @Test
    fun `AddGlyphs decodes a1 bitmaps LSB-first into 0 and 255`() {
        send(r.createGlyphSet(0xA11C1, XRender.FORMAT_A1))
        send(r.addGlyphs(0xA11C1, depth = 1))
        createSrcPicture()
        send(r.createPicture(dstPicture, windowId, XRender.FORMAT_X8R8G8B8))
        send(r.compositeGlyphs8(XRender.OP_OVER, srcPicture, dstPicture, 0xA11C1, 10, 20, 65))
        val out = drain()
        assertTrue(out.errors().joinToString { String(it) }, out.errors().isEmpty())
        assertEquals(
            byteArrayOf(0, 0xFF.toByte(), 0xFF.toByte(), 0).toList(),
            sink.glyphRuns.single().placements.single().glyph.alpha.toList(),
        )
    }

    @Test
    fun `truncated glyph bitmaps are BadLength, not a crash`() {
        send(r.createGlyphSet(0xA11CE, XRender.FORMAT_A8))
        send(r.addGlyphsTruncated(0xA11CE))
        val error = drain().errors().single()
        assertEquals(X11.ERR_BAD_LENGTH, error[1].toInt() and 0xFF)
        assertEquals(XRender.MAJOR_OPCODE, error[10].toInt() and 0xFF)
        assertNull(connection.lastFailure) // handled, not thrown
    }

    @Test
    fun `an unknown glyph is BadGlyph and draws nothing`() {
        send(r.createGlyphSet(0xA11CE, XRender.FORMAT_A8))
        send(r.addGlyphs(0xA11CE))
        createSrcPicture()
        send(r.createPicture(dstPicture, windowId, XRender.FORMAT_X8R8G8B8))
        send(r.compositeGlyphs8(XRender.OP_OVER, srcPicture, dstPicture, 0xA11CE, 10, 20, 65, 66))
        val out = drain()
        val error = out.errors().single()
        assertEquals(XRender.ERR_GLYPH, error[1].toInt() and 0xFF)
        assertEquals(66, error.intLE(4))
        assertTrue("a failed glyph run must not half-render", sink.glyphRuns.isEmpty())
    }

    @Test
    fun `an unknown glyphset is BadGlyphSet`() {
        createSrcPicture()
        send(r.createPicture(dstPicture, windowId, XRender.FORMAT_X8R8G8B8))
        send(r.compositeGlyphs8(XRender.OP_OVER, srcPicture, dstPicture, 0xBAD5E7, 10, 20, 65))
        val error = drain().errors().single()
        assertEquals(XRender.ERR_GLYPH_SET, error[1].toInt() and 0xFF)
    }

    @Test
    fun `CreateGlyphSet refuses a non-alpha format`() {
        send(r.createGlyphSet(0xA11CE, XRender.FORMAT_X8R8G8B8))
        val error = drain().errors().single()
        assertEquals(XRender.ERR_PICT_FORMAT, error[1].toInt() and 0xFF)
    }

    @Test
    fun `ReferenceGlyphSet shares the glyphs and FreeGlyphSet drops the reference`() {
        send(r.createGlyphSet(0xA11CE, XRender.FORMAT_A8))
        send(r.addGlyphs(0xA11CE))
        send(r.build(minor = XRender.MINOR_REFERENCE_GLYPH_SET) { writer ->
            writer.int(0xBEEF); writer.int(0xA11CE)
        })
        createSrcPicture()
        send(r.createPicture(dstPicture, windowId, XRender.FORMAT_X8R8G8B8))
        send(r.compositeGlyphs8(XRender.OP_OVER, srcPicture, dstPicture, 0xBEEF, 10, 20, 65))
        assertTrue(drain().errors().isEmpty())
        assertEquals(1, sink.glyphRuns.single().placements.size)

        send(r.build(minor = XRender.MINOR_FREE_GLYPH_SET) { it.int(0xA11CE) })
        // The reference still resolves; freeing it too makes the next run BadGlyphSet.
        send(r.build(minor = XRender.MINOR_FREE_GLYPH_SET) { it.int(0xBEEF) })
        send(r.compositeGlyphs8(XRender.OP_OVER, srcPicture, dstPicture, 0xBEEF, 10, 20, 65))
        assertEquals(XRender.ERR_GLYPH_SET, drain().errors().single()[1].toInt() and 0xFF)
    }

    @Test
    fun `glyph elements honour the 255 escape and 4-byte id alignment`() {
        // parseGlyphElements is pure, so the escape sequence is tested directly:
        // len=255 means "a CARD32 count follows", and 8-bit id runs pad to 4 bytes.
        val writer = XWriter(true)
        writer.byte(255); writer.pad(3); writer.short(1); writer.short(2) // delta 1,2
        writer.int(3); writer.byte(7); writer.byte(8); writer.byte(9) // 3 ids, 1 byte pad
        writer.pad(1)
        writer.byte(2); writer.pad(3); writer.short(-1); writer.short(0) // len 2, delta -1,0
        writer.byte(11); writer.byte(12); writer.pad(2)
        writer.byte(0); writer.pad(7) // terminator
        val request = writer.toByteArray()

        val elements = XRender.parseGlyphElements(request, littleEndian = true, idWidth = 1, from = 0)
        assertNotNull(elements)
        assertEquals(2, elements!!.size)
        assertEquals(1, elements[0].dx)
        assertEquals(2, elements[0].dy)
        assertEquals(listOf(7, 8, 9), elements[0].ids.toList())
        assertEquals(-1, elements[1].dx)
        assertEquals(listOf(11, 12), elements[1].ids.toList())
    }

    @Test
    fun `a truncated glyph element stream is null, not an exception`() {
        val writer = XWriter(true)
        writer.byte(4); writer.pad(3); writer.short(0); writer.short(0) // promises 4 ids...
        writer.byte(1) // ...and delivers one byte
        assertNull(XRender.parseGlyphElements(writer.toByteArray(), littleEndian = true, idWidth = 1, from = 0))
    }

    // --- version policy and unimplemented minors -------------------------------

    @Test
    fun `requests beyond the claimed 0_4 are refused by name in the trace`() {
        send(r.createPicture(dstPicture, windowId, XRender.FORMAT_X8R8G8B8))
        drain()
        // SetPictureTransform is 0.6+, AddTraps 0.8+, CreateSolidFill 0.10+.
        send(r.setPictureTransform(dstPicture))
        send(r.build(minor = XRender.MINOR_ADD_TRAPS))
        send(r.build(minor = XRender.MINOR_CREATE_SOLID_FILL))
        val errors = drain().errors()
        assertEquals(3, errors.size)
        errors.forEach { error ->
            assertEquals(X11.ERR_BAD_REQUEST, error[1].toInt() and 0xFF)
            assertEquals(XRender.MAJOR_OPCODE, error[10].toInt() and 0xFF)
        }
        val summary = connection.trace.summary()
        assertTrue(summary, summary.contains("RENDER.SetPictureTransform"))
        assertTrue(summary, summary.contains("RENDER.AddTraps"))
        assertTrue(summary, summary.contains("RENDER.CreateSolidFill"))
    }

    // --- rasterisation ----------------------------------------------------------

    @Test
    fun `FillRectangles Src then Composite Over blend to the exact spec colours`() {
        val lists = XDisplayList()
        val listsConnection = XConnection(document, lists)
        fun sendTo(request: ByteArray) {
            listsConnection.onBytes(request)
            listsConnection.drainOutbox()
        }
        sendTo(r.createWindow(windowId, rootId, 8, 8))
        sendTo(r.createPixmap(pixmapId, windowId, 1, 1))
        val srcPic = rootId + 20
        val dstPic = rootId + 21
        // CPRepeat=Pad: the 1x1 source covers the whole 2x2 composite.
        sendTo(
            r.createPicture(
                srcPic, pixmapId, XRender.FORMAT_X8R8G8B8,
                mask = XRender.CP_REPEAT, values = intArrayOf(XRender.REPEAT_PAD),
            ),
        )
        sendTo(r.createPicture(dstPic, windowId, XRender.FORMAT_X8R8G8B8))
        // An opaque red 1x1 source pixmap.
        sendTo(r.fillRectangles(XRender.OP_SRC, srcPic, intArrayOf(0xFFFF, 0, 0, 0xFFFF), listOf(intArrayOf(0, 0, 1, 1))))
        // Paint the window opaque blue, then Over-blend the red source across 2x2.
        sendTo(r.fillRectangles(XRender.OP_SRC, dstPic, intArrayOf(0, 0, 0xFFFF, 0xFFFF), listOf(intArrayOf(0, 0, 8, 8))))
        sendTo(r.composite(XRender.OP_OVER, srcPic, 0, dstPic, width = 2, height = 2))

        val pixels = lists.snapshot(windowId, 8, 8) { id ->
            when (id) {
                windowId -> intArrayOf(8, 8)
                else -> lists.sizeOf(id)
            }
        }
        // Opaque red Over opaque blue = opaque red.
        assertEquals(0xFFFF0000.toInt(), pixels[0])
        assertEquals(0xFFFF0000.toInt(), pixels[9])
        // Outside the composite the blue survives.
        assertEquals(0xFF0000FF.toInt(), pixels[7])
    }

    @Test
    fun `glyph coverage blends the solid source into the destination`() {
        val lists = XDisplayList()
        val listsConnection = XConnection(document, lists)
        fun sendTo(request: ByteArray) {
            listsConnection.onBytes(request)
            listsConnection.drainOutbox()
        }
        sendTo(r.createWindow(windowId, rootId, 8, 8))
        sendTo(r.createPixmap(pixmapId, windowId, 1, 1))
        val srcPic = rootId + 30
        val dstPic = rootId + 31
        // CPRepeat=Pad so the 1x1 white source covers every glyph pixel.
        sendTo(
            r.createPicture(
                srcPic, pixmapId, XRender.FORMAT_X8R8G8B8,
                mask = XRender.CP_REPEAT, values = intArrayOf(XRender.REPEAT_PAD),
            ),
        )
        sendTo(r.createPicture(dstPic, windowId, XRender.FORMAT_X8R8G8B8))
        sendTo(r.fillRectangles(XRender.OP_SRC, srcPic, intArrayOf(0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF), listOf(intArrayOf(0, 0, 1, 1))))
        sendTo(r.fillRectangles(XRender.OP_SRC, dstPic, intArrayOf(0, 0, 0, 0xFFFF), listOf(intArrayOf(0, 0, 8, 8))))
        sendTo(r.createGlyphSet(0xA11CE, XRender.FORMAT_A8))
        sendTo(r.addGlyphs(0xA11CE))
        // Pen at (2,2): inside the window, so the coverage maths is observable.
        sendTo(r.compositeGlyphs8(XRender.OP_OVER, srcPic, dstPic, 0xA11CE, 2, 2, 65))

        val pixels = lists.snapshot(windowId, 8, 8) { id ->
            when (id) {
                windowId -> intArrayOf(8, 8)
                else -> lists.sizeOf(id)
            }
        }
        // Glyph alpha rows are {0x00,0xFF}, {0x80,0x40}; white Over black at
        // coverage c lands exactly on c: 0 stays black, 0xFF is white, 0x80/0x40
        // are the proportional greys.
        assertEquals(0xFF000000.toInt(), pixels[2 * 8 + 2]) // alpha 0x00
        assertEquals(0xFFFFFFFF.toInt(), pixels[2 * 8 + 3]) // alpha 0xFF
        assertEquals(0xFF808080.toInt(), pixels[3 * 8 + 2]) // alpha 0x80
        assertEquals(0xFF404040.toInt(), pixels[3 * 8 + 3]) // alpha 0x40
        assertEquals(0xFF000000.toInt(), pixels[0]) // untouched outside the glyph
        assertEquals(1, lists.ops(windowId).count { it is DrawOp.GlyphRun })
    }

    @Test
    fun `blend maths matches the Porter-Duff definitions`() {
        // Src replaces the destination wholesale, alpha included.
        assertEquals(0x80112233.toInt(), XRender.blend(XRender.OP_SRC, 0x80112233.toInt(), 0xFFFF0000.toInt()))
        // Clear wipes to fully transparent.
        assertEquals(0, XRender.blend(XRender.OP_CLEAR, 0xFFFF0000.toInt(), 0xFFFF0000.toInt()))
        // Dst is a no-op.
        assertEquals(0xFF0000FF.toInt(), XRender.blend(XRender.OP_DST, 0xFFFF0000.toInt(), 0xFF0000FF.toInt()))
        // Fully transparent source Over leaves the destination untouched.
        assertEquals(0xFF00FF00.toInt(), XRender.blend(XRender.OP_OVER, 0x00FF0000, 0xFF00FF00.toInt()))
        // Opaque white Over anything is white.
        assertEquals(0xFFFFFFFF.toInt(), XRender.blend(XRender.OP_OVER, 0xFFFFFFFF.toInt(), 0xFF123456.toInt()))
        // Half-covering white over opaque black: ~50% grey, still opaque.
        val grey = XRender.blend(XRender.OP_OVER, 0x80FFFFFF.toInt(), 0xFF000000.toInt())
        assertEquals(0xFF, (grey ushr 24) and 0xFF)
        val channel = (grey ushr 16) and 0xFF
        assertTrue("got ${grey.toString(16)}", channel in 126..129)
        // A zero mask alpha makes the source contribute nothing.
        assertEquals(0xFF000000.toInt(), XRender.blend(XRender.OP_OVER, 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0))
        // Add saturates instead of blending.
        val added = XRender.blend(XRender.OP_ADD, 0xFF404040.toInt(), 0xFF404040.toInt())
        assertEquals(0xFF, (added ushr 24) and 0xFF)
        assertEquals(0x80, (added ushr 16) and 0xFF)
    }

    @Test
    fun `repeat sampling wraps, clamps, mirrors and gives up correctly`() {
        assertEquals(0, XRender.repeatCoord(4, 4, XRender.REPEAT_NORMAL))
        assertEquals(3, XRender.repeatCoord(-1, 4, XRender.REPEAT_NORMAL))
        assertEquals(3, XRender.repeatCoord(99, 4, XRender.REPEAT_PAD))
        assertEquals(0, XRender.repeatCoord(-99, 4, XRender.REPEAT_PAD))
        assertEquals(3, XRender.repeatCoord(4, 4, XRender.REPEAT_REFLECT))
        assertEquals(0, XRender.repeatCoord(7, 4, XRender.REPEAT_REFLECT))
        assertNull(XRender.repeatCoord(4, 4, XRender.REPEAT_NONE))
        assertEquals(2, XRender.repeatCoord(2, 4, XRender.REPEAT_NONE))
    }

    @Test
    fun `minorName covers every opcode we dispatch`() {
        listOf(
            XRender.MINOR_QUERY_VERSION, XRender.MINOR_QUERY_PICT_FORMATS,
            XRender.MINOR_QUERY_PICT_INDEX_VALUES, XRender.MINOR_CREATE_PICTURE,
            XRender.MINOR_CHANGE_PICTURE, XRender.MINOR_SET_PICTURE_CLIP_RECTANGLES,
            XRender.MINOR_FREE_PICTURE, XRender.MINOR_COMPOSITE, XRender.MINOR_CREATE_GLYPH_SET,
            XRender.MINOR_REFERENCE_GLYPH_SET, XRender.MINOR_FREE_GLYPH_SET, XRender.MINOR_ADD_GLYPHS,
            XRender.MINOR_FREE_GLYPHS, XRender.MINOR_COMPOSITE_GLYPHS_8,
            XRender.MINOR_COMPOSITE_GLYPHS_16, XRender.MINOR_COMPOSITE_GLYPHS_32,
            XRender.MINOR_FILL_RECTANGLES, XRender.MINOR_ADD_TRAPS, XRender.MINOR_CREATE_SOLID_FILL,
        ).forEach { minor ->
            assertTrue("minor $minor has a name", !XRender.minorName(minor).startsWith("minor"))
        }
    }
}
