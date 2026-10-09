package dev.win11we.shell.x11

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * One recorded drawing request. Coordinates are relative to the drawable, as in
 * the X protocol. Pure Kotlin: the Android renderer (D1b) replays these onto a
 * per-window Bitmap; the unit tests inspect them directly; [XDisplayList.snapshot]
 * rasterises them without Android for GetImage.
 */
sealed class DrawOp {
    class Fill(val x: Int, val y: Int, val width: Int, val height: Int, val argb: Int) : DrawOp()

    /** A converted ZPixmap: [argb] is width*height pixels, row-major, or empty when unsupported. */
    class Image(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val depth: Int,
        val argb: IntArray,
    ) : DrawOp()

    /**
     * ImageText8/16 or a PolyText run. [background] non-null means ImageText
     * semantics: paint the background box first (X draws it in the GC background).
     */
    class Text(val x: Int, val y: Int, val text: String, val argb: Int, val background: Int? = null) : DrawOp()

    /** width/height of 0 mean "to the bottom/right edge", as ClearArea does in X. */
    class Clear(val x: Int, val y: Int, val width: Int, val height: Int) : DrawOp()

    class Copy(
        val srcDrawable: Int,
        val srcX: Int,
        val srcY: Int,
        val dstX: Int,
        val dstY: Int,
        val width: Int,
        val height: Int,
    ) : DrawOp()

    /** Discrete points (PolyPoint): [coords] is x0,y0,x1,y1,... */
    class Points(val coords: IntArray, val argb: Int) : DrawOp()

    /**
     * A connected run of lines. [coords] is x0,y0,x1,y1,...; when [pairs] is true
     * the points are independent segments (PolySegment), else one polyline
     * (PolyLine).
     */
    class Line(val coords: IntArray, val argb: Int, val pairs: Boolean) : DrawOp()

    /**
     * An elliptical arc on the bounding box (x,y,width,height). Angles are in
     * 1/64ths of a degree, counter-clockwise from the 3 o'clock position, as in
     * PolyArc/PolyFillArc.
     */
    class Arc(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val angle1: Int,
        val angle2: Int,
        val filled: Boolean,
        val argb: Int,
    ) : DrawOp()

    /** A filled polygon (FillPoly): [coords] is x0,y0,x1,y1,... */
    class Polygon(val coords: IntArray, val argb: Int) : DrawOp()
}

/**
 * Where drawing requests end up. An interface so the protocol core has no Android
 * dependency: the Compose/Bitmap renderer implements it (D1b), tests record into it.
 * New D1c primitives have no-op defaults so older sinks keep working.
 */
interface XSink {
    fun fillRect(drawable: Int, x: Int, y: Int, width: Int, height: Int, pixel: Int)
    fun putImage(drawable: Int, x: Int, y: Int, width: Int, height: Int, depth: Int, data: ByteArray)
    fun imageText(drawable: Int, x: Int, y: Int, text: String, foreground: Int, background: Int?)
    fun clearArea(drawable: Int, x: Int, y: Int, width: Int, height: Int)
    fun copyArea(
        srcDrawable: Int, srcX: Int, srcY: Int,
        dstDrawable: Int, dstX: Int, dstY: Int,
        width: Int, height: Int,
    )

    /** Records a drawable's size when it is created (pixmaps have no window entry). */
    fun setSize(drawable: Int, width: Int, height: Int) {}

    fun points(drawable: Int, coords: IntArray, pixel: Int) {}
    fun lines(drawable: Int, coords: IntArray, pixel: Int, pairs: Boolean) {}
    fun arc(
        drawable: Int, x: Int, y: Int, width: Int, height: Int,
        angle1: Int, angle2: Int, filled: Boolean, pixel: Int,
    ) {}

    fun polygon(drawable: Int, coords: IntArray, pixel: Int) {}
}

/** Records what a client drew. */
class RecordingSink : XSink {
    val fills = mutableListOf<String>()
    val images = mutableListOf<String>()
    val texts = mutableListOf<String>()
    val clears = mutableListOf<String>()
    val copies = mutableListOf<String>()
    val primitives = mutableListOf<String>()
    override fun fillRect(drawable: Int, x: Int, y: Int, width: Int, height: Int, pixel: Int) {
        fills.add("$x,$y ${width}x$height #${pixel.toString(16)}")
    }

    override fun putImage(drawable: Int, x: Int, y: Int, width: Int, height: Int, depth: Int, data: ByteArray) {
        images.add("$x,$y ${width}x$height depth$depth ${data.size}B")
    }

    override fun imageText(drawable: Int, x: Int, y: Int, text: String, foreground: Int, background: Int?) {
        texts.add("$x,$y \"$text\"")
    }

    override fun clearArea(drawable: Int, x: Int, y: Int, width: Int, height: Int) {
        clears.add("$x,$y ${width}x$height")
    }

    override fun copyArea(
        srcDrawable: Int, srcX: Int, srcY: Int,
        dstDrawable: Int, dstX: Int, dstY: Int,
        width: Int, height: Int,
    ) {
        copies.add("$srcDrawable->$dstDrawable $srcX,$srcY=>$dstX,$dstY ${width}x$height")
    }

    override fun points(drawable: Int, coords: IntArray, pixel: Int) {
        primitives.add("points ${coords.size / 2} #${pixel.toString(16)}")
    }

    override fun lines(drawable: Int, coords: IntArray, pixel: Int, pairs: Boolean) {
        primitives.add("${if (pairs) "segments" else "lines"} ${coords.size / 2} #${pixel.toString(16)}")
    }

    override fun arc(
        drawable: Int, x: Int, y: Int, width: Int, height: Int,
        angle1: Int, angle2: Int, filled: Boolean, pixel: Int,
    ) {
        primitives.add("arc $x,$y ${width}x$height $angle1+$angle2 filled=$filled")
    }

    override fun polygon(drawable: Int, coords: IntArray, pixel: Int) {
        primitives.add("polygon ${coords.size / 2} #${pixel.toString(16)}")
    }
}

/**
 * The server's pixel memory: an append-only display list per drawable. Drawing
 * requests land here from any client thread; [revision] changes on every append
 * so the Compose surface knows when to redraw.
 */
class XDisplayList : XSink {
    private val lists = LinkedHashMap<Int, MutableList<DrawOp>>()

    @Volatile
    var revision: Long = 0
        private set

    private fun append(drawable: Int, op: DrawOp) {
        synchronized(lists) { lists.getOrPut(drawable) { mutableListOf() }.add(op) }
        revision++
    }

    /** A stable snapshot: lists only grow, so index-based incremental replay is safe. */
    fun ops(drawable: Int): List<DrawOp> =
        synchronized(lists) { lists[drawable]?.toList() ?: emptyList() }

    fun drawableIds(): Set<Int> = synchronized(lists) { lists.keys.toSet() }

    private val sizes = HashMap<Int, IntArray>()

    /** Size recorded at CreatePixmap time; null for drawables we never sized. */
    fun sizeOf(drawable: Int): IntArray? = synchronized(sizes) { sizes[drawable]?.copyOf() }

    override fun setSize(drawable: Int, width: Int, height: Int) {
        synchronized(sizes) { sizes[drawable] = intArrayOf(width, height) }
    }

    fun remove(drawable: Int) {
        synchronized(lists) { lists.remove(drawable) }
        revision++
    }

    override fun fillRect(drawable: Int, x: Int, y: Int, width: Int, height: Int, pixel: Int) =
        append(drawable, DrawOp.Fill(x, y, width, height, opaque(pixel)))

    override fun putImage(drawable: Int, x: Int, y: Int, width: Int, height: Int, depth: Int, data: ByteArray) =
        append(drawable, DrawOp.Image(x, y, width, height, depth, toArgb(data, depth, width, height)))

    override fun imageText(drawable: Int, x: Int, y: Int, text: String, foreground: Int, background: Int?) =
        append(drawable, DrawOp.Text(x, y, text, opaque(foreground), background?.let { opaque(it) }))

    override fun clearArea(drawable: Int, x: Int, y: Int, width: Int, height: Int) =
        append(drawable, DrawOp.Clear(x, y, width, height))

    override fun copyArea(
        srcDrawable: Int, srcX: Int, srcY: Int,
        dstDrawable: Int, dstX: Int, dstY: Int,
        width: Int, height: Int,
    ) = append(dstDrawable, DrawOp.Copy(srcDrawable, srcX, srcY, dstX, dstY, width, height))

    override fun points(drawable: Int, coords: IntArray, pixel: Int) =
        append(drawable, DrawOp.Points(coords, opaque(pixel)))

    override fun lines(drawable: Int, coords: IntArray, pixel: Int, pairs: Boolean) =
        append(drawable, DrawOp.Line(coords, opaque(pixel), pairs))

    override fun arc(
        drawable: Int, x: Int, y: Int, width: Int, height: Int,
        angle1: Int, angle2: Int, filled: Boolean, pixel: Int,
    ) = append(drawable, DrawOp.Arc(x, y, width, height, angle1, angle2, filled, opaque(pixel)))

    override fun polygon(drawable: Int, coords: IntArray, pixel: Int) =
        append(drawable, DrawOp.Polygon(coords, opaque(pixel)))

    // --- pure-Kotlin rasteriser (GetImage, thumbnails, tests) ----------------

    /**
     * Rasterises a drawable's ops into ARGB pixels without Android, so GetImage
     * can answer with real data. [resolveSize] supplies Copy sources' dimensions
     * (windows come from XDocument, pixmaps from [sizeOf]). Text ops are skipped:
     * glyph rasterisation belongs to the platform renderer. The surface starts
     * opaque black; anything the client never painted reads back as such.
     */
    fun snapshot(
        drawable: Int,
        width: Int,
        height: Int,
        resolveSize: (Int) -> IntArray? = { sizeOf(it) },
    ): IntArray {
        if (width <= 0 || height <= 0) return IntArray(0)
        val pixels = IntArray(width * height)
        pixels.fill(0xFF000000.toInt())
        rasterise(pixels, width, height, ops(drawable), resolveSize, setOf(drawable))
        return pixels
    }

    private fun rasterise(
        pixels: IntArray,
        width: Int,
        height: Int,
        ops: List<DrawOp>,
        resolveSize: (Int) -> IntArray?,
        stack: Set<Int>,
    ) {
        fun put(x: Int, y: Int, argb: Int) {
            if (x in 0 until width && y in 0 until height) pixels[y * width + x] = argb
        }

        fun fillRect(x: Int, y: Int, w: Int, h: Int, argb: Int) {
            val x0 = max(0, x); val y0 = max(0, y)
            val x1 = min(width, x + w); val y1 = min(height, y + h)
            for (py in y0 until y1) for (px in x0 until x1) pixels[py * width + px] = argb
        }

        fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int, argb: Int) {
            // Bresenham, clipped by put().
            var dx = abs(x1 - x0); var dy = -abs(y1 - y0)
            val sx = if (x0 < x1) 1 else -1
            val sy = if (y0 < y1) 1 else -1
            var err = dx + dy
            var cx = x0; var cy = y0
            var guard = 0
            while (guard++ < 65536) {
                put(cx, cy, argb)
                if (cx == x1 && cy == y1) break
                val e2 = 2 * err
                if (e2 >= dy) { err += dy; cx += sx }
                if (e2 <= dx) { err += dx; cy += sy }
            }
        }

        for (op in ops) {
            when (op) {
                is DrawOp.Fill -> fillRect(op.x, op.y, op.width, op.height, op.argb)
                is DrawOp.Clear -> {
                    val w = if (op.width == 0) width - op.x else op.width
                    val h = if (op.height == 0) height - op.y else op.height
                    fillRect(op.x, op.y, w, h, 0xFFFFFFFF.toInt())
                }
                is DrawOp.Image -> {
                    if (op.argb.isEmpty()) continue
                    for (row in 0 until op.height) {
                        val src = row * op.width
                        if (src + op.width > op.argb.size) break
                        for (col in 0 until op.width) put(op.x + col, op.y + row, op.argb[src + col])
                    }
                }
                is DrawOp.Copy -> {
                    val size = resolveSize(op.srcDrawable)
                    if (size == null || op.srcDrawable in stack) continue
                    val srcPixels = IntArray(max(1, size[0]) * max(1, size[1]))
                    rasterise(srcPixels, size[0], size[1], ops(op.srcDrawable), resolveSize, stack + op.srcDrawable)
                    for (row in 0 until op.height) {
                        for (col in 0 until op.width) {
                            val sx = op.srcX + col; val sy = op.srcY + row
                            if (sx in 0 until size[0] && sy in 0 until size[1]) {
                                put(op.dstX + col, op.dstY + row, srcPixels[sy * size[0] + sx])
                            }
                        }
                    }
                }
                is DrawOp.Points -> {
                    var i = 0
                    while (i + 1 < op.coords.size) { put(op.coords[i], op.coords[i + 1], op.argb); i += 2 }
                }
                is DrawOp.Line -> {
                    val c = op.coords
                    if (op.pairs) {
                        var i = 0
                        while (i + 3 < c.size) { drawLine(c[i], c[i + 1], c[i + 2], c[i + 3], op.argb); i += 4 }
                    } else {
                        for (i in 0 until c.size - 3 step 2) drawLine(c[i], c[i + 1], c[i + 2], c[i + 3], op.argb)
                    }
                }
                is DrawOp.Arc -> {
                    val steps = min(4096, max(16, abs(op.angle2) / 2))
                    val cx = op.x + op.width / 2.0
                    val cy = op.y + op.height / 2.0
                    val rx = op.width / 2.0
                    val ry = op.height / 2.0
                    var prevX: Int? = null; var prevY: Int? = null
                    for (s in 0..steps) {
                        // X angles: 1/64 degree, CCW from 3 o'clock; screen y grows down.
                        val t = Math.toRadians((op.angle1 + op.angle2.toLong() * s / steps) / 64.0)
                        val px = (cx + rx * cos(t)).toInt()
                        val py = (cy - ry * sin(t)).toInt()
                        if (op.filled) drawLine(op.x + op.width / 2, op.y + op.height / 2, px, py, op.argb)
                        if (prevX != null) drawLine(prevX!!, prevY!!, px, py, op.argb)
                        prevX = px; prevY = py
                    }
                }
                is DrawOp.Polygon -> {
                    // Even-odd scanline fill.
                    val n = op.coords.size / 2
                    if (n < 3) continue
                    var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
                    for (i in 0 until n) { minY = min(minY, op.coords[i * 2 + 1]); maxY = max(maxY, op.coords[i * 2 + 1]) }
                    val xs = IntArray(n)
                    for (y in max(0, minY) until min(height, maxY + 1)) {
                        var count = 0
                        for (i in 0 until n) {
                            val j = (i + 1) % n
                            val y0 = op.coords[i * 2 + 1]; val y1 = op.coords[j * 2 + 1]
                            if ((y0 <= y && y1 > y) || (y1 <= y && y0 > y)) {
                                val x0 = op.coords[i * 2]; val x1 = op.coords[j * 2]
                                xs[count++] = x0 + ((y - y0).toLong() * (x1 - x0) / (y1 - y0)).toInt()
                            }
                        }
                        val spans = xs.copyOf(count).also { it.sort() }
                        for (k in 0 until spans.size - 1 step 2) {
                            fillRect(spans[k], y, spans[k + 1] - spans[k] + 1, 1, op.argb)
                        }
                    }
                }
                is DrawOp.Text -> Unit // glyph rasterisation belongs to the platform renderer
            }
        }
    }

    companion object {
        /** X depth-24 pixel values carry no alpha; force them opaque. */
        fun opaque(pixel: Int): Int = pixel or 0xFF000000.toInt()

        /**
         * Converts ZPixmap data (4 bytes/pixel, byte order B,G,R,[A]) to ARGB ints.
         * Depth 24 forces alpha to 0xFF (clients leave the pad byte undefined);
         * depth 32 keeps it. Anything else (XYBitmap, depth 1) is not renderable yet.
         */
        fun toArgb(data: ByteArray, depth: Int, width: Int, height: Int): IntArray {
            if (depth != 24 && depth != 32) return IntArray(0)
            val count = width.toLong() * height.toLong()
            if (count <= 0 || count > Int.MAX_VALUE) return IntArray(0)
            val total = count.toInt()
            val available = minOf(total, data.size / 4)
            val out = IntArray(total)
            for (i in 0 until available) {
                val o = i * 4
                val b = data[o].toInt() and 0xFF
                val g = data[o + 1].toInt() and 0xFF
                val r = data[o + 2].toInt() and 0xFF
                val a = if (depth == 32) data[o + 3].toInt() and 0xFF else 0xFF
                out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            return out
        }
    }
}
