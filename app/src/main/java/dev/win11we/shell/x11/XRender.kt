package dev.win11we.shell.x11

/**
 * The RENDER extension: wire facts, Porter-Duff maths and glyph decoding - all
 * pure, all unit-tested. Layouts are taken from xorgproto's `renderproto.h`
 * (struct sizes/field order) and xcbproto's `render.xml` (minor opcodes); both
 * are the authoritative protocol sources.
 *
 * **Version policy (deliberate, keep it):** [QueryVersion] answers **0.4**, not
 * the 0.11 this server could claim once everything exists. RENDER clients gate
 * their request choice on the answered version: cairo uses AddTraps only from
 * 0.8 (and falls back to a8-mask rectangles - fully implemented here),
 * XRenderCreateSolidFill from 0.10 (falls back to a repeated 1x1 pixmap), and
 * Xcursor's ARGB cursors from 0.5 (falls back to core font cursors). Claiming a
 * version whose optional requests we would answer with BadRequest kills GTK
 * clients (their error handler `_exit(1)`s), so the claim only rises when the
 * corresponding requests are implemented. Everything a 0.4 client can send -
 * QueryPictFormats, pictures, clips, Composite, FillRectangles and the
 * CompositeGlyphs family - is implemented.
 */
object XRender {
    const val EXT_NAME = "RENDER"

    /** Free extension opcode (XTEST holds 130); clients learn it via QueryExtension. */
    const val MAJOR_OPCODE = 129

    /** Error base handed out by QueryExtension; RENDER errors are base+0..4 (render.h). */
    const val FIRST_ERROR = 160
    const val ERR_PICT_FORMAT = FIRST_ERROR + 0
    const val ERR_PICTURE = FIRST_ERROR + 1
    const val ERR_PICT_OP = FIRST_ERROR + 2
    const val ERR_GLYPH_SET = FIRST_ERROR + 3
    const val ERR_GLYPH = FIRST_ERROR + 4

    const val VERSION_MAJOR = 0
    const val VERSION_MINOR = 4

    // --- minor opcodes (xcbproto 1.15.2 render.xml) ---------------------------
    const val MINOR_QUERY_VERSION = 0
    const val MINOR_QUERY_PICT_FORMATS = 1
    const val MINOR_QUERY_PICT_INDEX_VALUES = 2
    const val MINOR_CREATE_PICTURE = 4
    const val MINOR_CHANGE_PICTURE = 5
    const val MINOR_SET_PICTURE_CLIP_RECTANGLES = 6
    const val MINOR_FREE_PICTURE = 7
    const val MINOR_COMPOSITE = 8
    const val MINOR_TRAPEZOIDS = 10
    const val MINOR_TRIANGLES = 11
    const val MINOR_TRISTRIP = 12
    const val MINOR_TRIFAN = 13
    const val MINOR_CREATE_GLYPH_SET = 17
    const val MINOR_REFERENCE_GLYPH_SET = 18
    const val MINOR_FREE_GLYPH_SET = 19
    const val MINOR_ADD_GLYPHS = 20
    const val MINOR_FREE_GLYPHS = 22
    const val MINOR_COMPOSITE_GLYPHS_8 = 23
    const val MINOR_COMPOSITE_GLYPHS_16 = 24
    const val MINOR_COMPOSITE_GLYPHS_32 = 25
    const val MINOR_FILL_RECTANGLES = 26
    const val MINOR_CREATE_CURSOR = 27
    const val MINOR_SET_PICTURE_TRANSFORM = 28
    const val MINOR_QUERY_FILTERS = 29
    const val MINOR_SET_PICTURE_FILTER = 30
    const val MINOR_CREATE_ANIM_CURSOR = 31
    const val MINOR_ADD_TRAPS = 32
    const val MINOR_CREATE_SOLID_FILL = 33
    const val MINOR_CREATE_LINEAR_GRADIENT = 34
    const val MINOR_CREATE_RADIAL_GRADIENT = 35
    const val MINOR_CREATE_CONICAL_GRADIENT = 36

    /** Human-readable name for the protocol trace and Diagnostics. */
    fun minorName(minor: Int): String = when (minor) {
        MINOR_QUERY_VERSION -> "QueryVersion"
        MINOR_QUERY_PICT_FORMATS -> "QueryPictFormats"
        MINOR_QUERY_PICT_INDEX_VALUES -> "QueryPictIndexValues"
        MINOR_CREATE_PICTURE -> "CreatePicture"
        MINOR_CHANGE_PICTURE -> "ChangePicture"
        MINOR_SET_PICTURE_CLIP_RECTANGLES -> "SetPictureClipRectangles"
        MINOR_FREE_PICTURE -> "FreePicture"
        MINOR_COMPOSITE -> "Composite"
        MINOR_TRAPEZOIDS -> "Trapezoids"
        MINOR_TRIANGLES -> "Triangles"
        MINOR_TRISTRIP -> "TriStrip"
        MINOR_TRIFAN -> "TriFan"
        MINOR_CREATE_GLYPH_SET -> "CreateGlyphSet"
        MINOR_REFERENCE_GLYPH_SET -> "ReferenceGlyphSet"
        MINOR_FREE_GLYPH_SET -> "FreeGlyphSet"
        MINOR_ADD_GLYPHS -> "AddGlyphs"
        MINOR_FREE_GLYPHS -> "FreeGlyphs"
        MINOR_COMPOSITE_GLYPHS_8 -> "CompositeGlyphs8"
        MINOR_COMPOSITE_GLYPHS_16 -> "CompositeGlyphs16"
        MINOR_COMPOSITE_GLYPHS_32 -> "CompositeGlyphs32"
        MINOR_FILL_RECTANGLES -> "FillRectangles"
        MINOR_CREATE_CURSOR -> "CreateCursor"
        MINOR_SET_PICTURE_TRANSFORM -> "SetPictureTransform"
        MINOR_QUERY_FILTERS -> "QueryFilters"
        MINOR_SET_PICTURE_FILTER -> "SetPictureFilter"
        MINOR_CREATE_ANIM_CURSOR -> "CreateAnimCursor"
        MINOR_ADD_TRAPS -> "AddTraps"
        MINOR_CREATE_SOLID_FILL -> "CreateSolidFill"
        MINOR_CREATE_LINEAR_GRADIENT -> "CreateLinearGradient"
        MINOR_CREATE_RADIAL_GRADIENT -> "CreateRadialGradient"
        MINOR_CREATE_CONICAL_GRADIENT -> "CreateConicalGradient"
        else -> "minor$minor"
    }

    // --- PictOp codes (render.h) ---------------------------------------------
    const val OP_CLEAR = 0
    const val OP_SRC = 1
    const val OP_DST = 2
    const val OP_OVER = 3
    const val OP_OVER_REVERSE = 4
    const val OP_IN = 5
    const val OP_IN_REVERSE = 6
    const val OP_OUT = 7
    const val OP_OUT_REVERSE = 8
    const val OP_ATOP = 9
    const val OP_ATOP_REVERSE = 10
    const val OP_XOR = 11
    const val OP_ADD = 12
    const val OP_SATURATE = 13

    fun opName(op: Int): String = when (op) {
        OP_CLEAR -> "Clear"; OP_SRC -> "Src"; OP_DST -> "Dst"; OP_OVER -> "Over"
        OP_OVER_REVERSE -> "OverReverse"; OP_IN -> "In"; OP_IN_REVERSE -> "InReverse"
        OP_OUT -> "Out"; OP_OUT_REVERSE -> "OutReverse"; OP_ATOP -> "Atop"
        OP_ATOP_REVERSE -> "AtopReverse"; OP_XOR -> "Xor"; OP_ADD -> "Add"
        OP_SATURATE -> "Saturate"; else -> "op$op"
    }

    // --- CP value-mask bits (render.h); values follow in ascending bit order --
    const val CP_REPEAT = 1 shl 0
    const val CP_ALPHA_MAP = 1 shl 1
    const val CP_ALPHA_X_ORIGIN = 1 shl 2
    const val CP_ALPHA_Y_ORIGIN = 1 shl 3
    const val CP_CLIP_X_ORIGIN = 1 shl 4
    const val CP_CLIP_Y_ORIGIN = 1 shl 5
    const val CP_CLIP_MASK = 1 shl 6
    const val CP_GRAPHICS_EXPOSURE = 1 shl 7
    const val CP_SUBWINDOW_MODE = 1 shl 8
    const val CP_POLY_EDGE = 1 shl 9
    const val CP_POLY_MODE = 1 shl 10
    const val CP_DITHER = 1 shl 11
    const val CP_COMPONENT_ALPHA = 1 shl 12

    // --- repeat modes (render.h RepeatNone..) --------------------------------
    const val REPEAT_NONE = 0
    const val REPEAT_NORMAL = 1
    const val REPEAT_PAD = 2
    const val REPEAT_REFLECT = 3

    // --- picture formats ------------------------------------------------------
    /**
     * One PICTFORMAT as QueryPictFormats reports it (xPictFormInfo, 28 bytes on
     * the wire: id, type, depth, pad2, xDirectFormat{red,redMask,green,greenMask,
     * blue,blueMask,alpha,alphaMask} = 16 bytes, colormap). All are Direct
     * (type 1); ids are server-defined, so these are our stable choice.
     */
    data class Format(
        val id: Int,
        val depth: Int,
        val alphaShift: Int, val alphaMask: Int,
        val redShift: Int, val redMask: Int,
        val greenShift: Int, val greenMask: Int,
        val blueShift: Int, val blueMask: Int,
    )

    const val FORMAT_A1 = 1
    const val FORMAT_A4 = 2
    const val FORMAT_A8 = 3
    const val FORMAT_R5G6B5 = 4
    const val FORMAT_R8G8B8 = 5
    const val FORMAT_X8R8G8B8 = 6 // PictStandardRGB24 shape
    const val FORMAT_B8G8R8 = 7
    const val FORMAT_X8B8G8R8 = 8
    const val FORMAT_A8R8G8B8 = 9 // PictStandardARGB32 shape
    const val FORMAT_A8B8G8R8 = 10

    val FORMATS: List<Format> = listOf(
        Format(FORMAT_A1, 1, 0, 0x1, 0, 0, 0, 0, 0, 0),
        Format(FORMAT_A4, 4, 0, 0xF, 0, 0, 0, 0, 0, 0),
        Format(FORMAT_A8, 8, 0, 0xFF, 0, 0, 0, 0, 0, 0),
        Format(FORMAT_R5G6B5, 16, 0, 0, 11, 0x1F, 5, 0x3F, 0, 0x1F),
        Format(FORMAT_R8G8B8, 24, 0, 0, 16, 0xFF, 8, 0xFF, 0, 0xFF),
        Format(FORMAT_X8R8G8B8, 24, 0, 0, 16, 0xFF, 8, 0xFF, 0, 0xFF),
        Format(FORMAT_B8G8R8, 24, 0, 0, 0, 0xFF, 8, 0xFF, 16, 0xFF),
        Format(FORMAT_X8B8G8R8, 24, 0, 0, 0, 0xFF, 8, 0xFF, 16, 0xFF),
        Format(FORMAT_A8R8G8B8, 32, 24, 0xFF, 16, 0xFF, 8, 0xFF, 0, 0xFF),
        Format(FORMAT_A8B8G8R8, 32, 24, 0xFF, 0, 0xFF, 8, 0xFF, 16, 0xFF),
    )

    fun format(id: Int): Format? = FORMATS.firstOrNull { it.id == id }

    /**
     * The screen block of QueryPictFormats: every depth the server's pixmap
     * formats allow, visuals mapped at depth 24 (the screen's visuals are
     * TrueColor-24), everything else with zero visuals. Fallback = RGB24 shape.
     */
    val SCREEN_DEPTHS: List<Int> = listOf(1, 4, 8, 16, 24, 32)

    /** visualId -> format id for the screen's visuals (ScreenConfig.rootVisual = 0x21). */
    fun visualFormats(): List<Pair<Int, Int>> = listOf(0x21 to FORMAT_X8R8G8B8, 0x22 to FORMAT_X8R8G8B8)

    // --- Porter-Duff blending (render spec table) ------------------------------
    /**
     * Blends [src] over/into [dst] (both ARGB) with PictOp [op]; [maskAlpha]
     * (0..255) scales the source's coverage exactly like a RENDER mask picture
     * or one glyph's alpha does. Non-premultiplied spec maths in doubles -
     * this path serves the JVM snapshot rasteriser and the wire tests, where
     * correctness beats throughput; the Android renderer uses native
     * PorterDuff xfermodes for the same ops.
     */
    fun blend(op: Int, src: Int, dst: Int, maskAlpha: Int = 255): Int {
        if (op == OP_ADD) {
            // Plus is saturating premultiplied addition in every real server
            // (fb/pixman); the spec's /Ra normalisation would average instead.
            val sa8 = ((src ushr 24) and 0xFF) * maskAlpha.coerceIn(0, 255) / 255
            val da8 = (dst ushr 24) and 0xFF
            val a = minOf(255, sa8 + da8)
            fun addChan(shift: Int): Int {
                val cs = ((src ushr shift) and 0xFF) * sa8 / 255
                val cd = ((dst ushr shift) and 0xFF) * da8 / 255
                return minOf(255, cs + cd)
            }
            return (a shl 24) or (addChan(16) shl 16) or (addChan(8) shl 8) or addChan(0)
        }
        val sa = ((src ushr 24) and 0xFF) / 255.0 * (maskAlpha.coerceIn(0, 255) / 255.0)
        val da = ((dst ushr 24) and 0xFF) / 255.0
        val sr = ((src ushr 16) and 0xFF) / 255.0
        val sg = ((src ushr 8) and 0xFF) / 255.0
        val sb = (src and 0xFF) / 255.0
        val dr = ((dst ushr 16) and 0xFF) / 255.0
        val dg = ((dst ushr 8) and 0xFF) / 255.0
        val db = (dst and 0xFF) / 255.0
        // Fa/Fb per the RENDER spec's operator table.
        val fa: Double
        val fb: Double
        when (op) {
            OP_CLEAR -> { fa = 0.0; fb = 0.0 }
            OP_SRC -> { fa = 1.0; fb = 0.0 }
            OP_DST -> return dst
            OP_OVER -> { fa = 1.0; fb = 1.0 - sa }
            OP_OVER_REVERSE -> { fa = 1.0 - da; fb = 1.0 }
            OP_IN -> { fa = da; fb = 0.0 }
            OP_IN_REVERSE -> { fa = 0.0; fb = sa }
            OP_OUT -> { fa = 1.0 - da; fb = 0.0 }
            OP_OUT_REVERSE -> { fa = 0.0; fb = 1.0 - sa }
            OP_ATOP -> { fa = da; fb = 1.0 - sa }
            OP_ATOP_REVERSE -> { fa = 1.0 - da; fb = sa }
            OP_XOR -> { fa = 1.0 - da; fb = 1.0 - sa }
            OP_ADD -> { fa = 1.0; fb = 1.0 }
            OP_SATURATE -> {
                val f = if (sa > 0.0) minOf(1.0, (1.0 - da) / sa) else 1.0
                fa = 1.0; fb = f
            }
            else -> { fa = 1.0; fb = 1.0 - sa } // unknown ops behave like Over
        }
        val ra = sa * fa + da * fb
        if (ra <= 0.0) return 0
        fun chan(cs: Double, cd: Double): Int {
            val v = (cs * sa * fa + cd * da * fb) / ra
            return (v * 255.0).toInt().coerceIn(0, 255)
        }
        val outA = (ra * 255.0).toInt().coerceIn(0, 255)
        return (outA shl 24) or (chan(sr, dr) shl 16) or (chan(sg, dg) shl 8) or chan(sb, db)
    }

    // --- repeat sampling -------------------------------------------------------
    /**
     * Maps a source coordinate into the source rectangle under repeat [mode].
     * @return null when REPEAT_NONE and the coordinate is outside the source.
     */
    fun repeatCoord(v: Int, size: Int, mode: Int): Int? {
        if (size <= 0) return null
        return when (mode) {
            REPEAT_NORMAL -> ((v % size) + size) % size
            REPEAT_PAD -> v.coerceIn(0, size - 1)
            REPEAT_REFLECT -> {
                val period = size * 2
                var m = ((v % period) + period) % period
                if (m >= size) m = period - 1 - m
                m
            }
            else -> if (v in 0 until size) v else null
        }
    }

    // --- glyphs -----------------------------------------------------------------
    /**
     * One decoded glyph: 8-bit alpha bitmap plus the metrics from xGlyphInfo
     * (width/height, x/y = offset from the pen to the bitmap's top-left,
     * xOff/yOff = the pen advance). Alpha is always expanded to one byte per
     * pixel so the rasterisers never re-decode a1/a4 at draw time.
     */
    class Glyph(
        val width: Int,
        val height: Int,
        val x: Int,
        val y: Int,
        val xOff: Int,
        val yOff: Int,
        val alpha: ByteArray,
    )

    /** Shared glyph storage; ReferenceGlyphSet maps another id to the same store. */
    class GlyphStore(val formatId: Int) {
        val glyphs = HashMap<Int, Glyph>()
    }

    /**
     * Expands one glyph's bitmap from [data] at [offset] to 8-bit alpha.
     * Scanlines pad to 32 bits, as the wire format requires. Depth 1 puts the
     * leftmost pixel in the LSB (fb order); depth 4 the leftmost in the low
     * nibble. @return null when [data] is too short (a BadLength on the wire).
     */
    fun expandAlpha(depth: Int, width: Int, height: Int, data: ByteArray, offset: Int): ByteArray? {
        if (width <= 0 || height <= 0) return ByteArray(0)
        val bitsPerPixel = when (depth) { 1 -> 1; 4 -> 4; 8 -> 8; else -> return null }
        val rowBytes = X11.pad4((width * bitsPerPixel + 7) / 8)
        val needed = offset.toLong() + rowBytes.toLong() * height
        if (needed > data.size || width.toLong() * height > Int.MAX_VALUE) return null
        val out = ByteArray(width * height)
        for (row in 0 until height) {
            val rowStart = offset + row * rowBytes
            for (col in 0 until width) {
                out[row * width + col] = when (depth) {
                    8 -> data[rowStart + col]
                    4 -> {
                        val b = data[rowStart + col / 2].toInt() and 0xFF
                        (if (col % 2 == 0) b and 0x0F else (b shr 4) and 0x0F).let { (it * 17).toByte() }
                    }
                    else -> { // depth 1: LSB first, 0 -> 0, 1 -> 0xFF
                        val b = data[rowStart + col / 8].toInt() and 0xFF
                        if ((b shr (col % 8)) and 1 != 0) 0xFF.toByte() else 0
                    }
                }
            }
        }
        return out
    }

    /** Bytes one glyph's bitmap occupies on the wire (scanlines pad to 32 bits). */
    fun glyphDataBytes(depth: Int, width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 0
        val bitsPerPixel = when (depth) { 1 -> 1; 4 -> 4; 8 -> 8; else -> return -1 }
        return X11.pad4((width * bitsPerPixel + 7) / 8) * height
    }

    /** One parsed xGlyphElt: pen delta plus the run's glyph ids. */
    class GlyphElt(val dx: Int, val dy: Int, val ids: IntArray)

    /**
     * Parses the CompositeGlyphs8/16/32 element stream starting at [from]
     * (offset 28 on the wire): elements are {len u8, pad u8+u16, dx i16, dy i16}
     * followed by [idWidth]-byte ids; the id run pads to a 4-byte boundary;
     * len==0 ends the list; len==255 escapes to a CARD32 count. Malformed data
     * yields null (a BadLength on the wire), never an exception.
     */
    fun parseGlyphElements(request: ByteArray, littleEndian: Boolean, idWidth: Int, from: Int): List<GlyphElt>? {
        fun u16(at: Int): Int {
            val a = request[at].toInt() and 0xFF
            val b = request[at + 1].toInt() and 0xFF
            return if (littleEndian) a or (b shl 8) else (b or (a shl 8))
        }

        fun u32(at: Int): Int {
            return if (littleEndian) {
                (request[at].toInt() and 0xFF) or ((request[at + 1].toInt() and 0xFF) shl 8) or
                    ((request[at + 2].toInt() and 0xFF) shl 16) or ((request[at + 3].toInt() and 0xFF) shl 24)
            } else {
                ((request[at].toInt() and 0xFF) shl 24) or ((request[at + 1].toInt() and 0xFF) shl 16) or
                    ((request[at + 2].toInt() and 0xFF) shl 8) or (request[at + 3].toInt() and 0xFF)
            }
        }

        val elements = mutableListOf<GlyphElt>()
        var pos = from
        while (true) {
            if (pos + 8 > request.size) return null
            var len = request[pos].toInt() and 0xFF
            val dx = u16(pos + 4).toShort().toInt()
            val dy = u16(pos + 6).toShort().toInt()
            pos += 8
            if (len == 255) {
                if (pos + 4 > request.size) return null
                len = u32(pos)
                pos += 4
            }
            if (len == 0) return elements
            val bytes = len.toLong() * idWidth
            if (bytes > Int.MAX_VALUE || pos + bytes > request.size) return null
            val ids = IntArray(len)
            for (i in 0 until len) {
                ids[i] = when (idWidth) {
                    1 -> request[pos + i].toInt() and 0xFF
                    2 -> u16(pos + i * 2)
                    else -> u32(pos + i * 4)
                }
            }
            pos += bytes.toInt()
            // The id run of an 8-bit element pads to 4 bytes (xorg glyph.c).
            val misalign = (bytes % 4).toInt()
            if (misalign != 0) {
                pos += 4 - misalign
                if (pos > request.size) return null
            }
            elements.add(GlyphElt(dx, dy, ids))
        }
    }

    /** Packs a 4xCARD16 render colour (as on the wire, already read) into ARGB8888. */
    fun color16ToArgb(red16: Int, green16: Int, blue16: Int, alpha16: Int): Int {
        val a = (alpha16 ushr 8) and 0xFF
        val r = (red16 ushr 8) and 0xFF
        val g = (green16 ushr 8) and 0xFF
        val b = (blue16 ushr 8) and 0xFF
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}

/** One RENDER Composite/FillRectangles blend, resolved to drawables/colours. */
class BlendSpec(
    val op: Int,
    /** 0 when the source is the solid [srcColor], else the source drawable. */
    val srcDrawable: Int,
    val srcColor: Int,
    val srcX: Int,
    val srcY: Int,
    /** 0 when unmasked, else the mask drawable (its alpha channel masks the source). */
    val maskDrawable: Int,
    val maskX: Int,
    val maskY: Int,
    /** Constant mask coverage from a solid-fill mask picture, or null for drawable/none. */
    val maskColor: Int? = null,
    val dstX: Int,
    val dstY: Int,
    val width: Int,
    val height: Int,
    val srcRepeat: Int,
    /** Destination-space clip rectangles (x,y,w,h), or null when unclipped. */
    val clip: List<IntArray>?,
)

/** One glyph blit inside a [GlyphRunSpec]. */
class GlyphPlacement(val dstX: Int, val dstY: Int, val glyph: XRender.Glyph)

/** A RENDER CompositeGlyphs run, resolved to placements of decoded glyphs. */
class GlyphRunSpec(
    val op: Int,
    /** Solid colour source (ARGB); [srcDrawable] non-zero means sample an image instead. */
    val srcColor: Int,
    val srcDrawable: Int,
    val srcX: Int,
    val srcY: Int,
    /** Repeat mode of the source picture (a repeated 1x1 pixmap IS cairo's pre-0.10 solid). */
    val srcRepeat: Int,
    val placements: List<GlyphPlacement>,
)
