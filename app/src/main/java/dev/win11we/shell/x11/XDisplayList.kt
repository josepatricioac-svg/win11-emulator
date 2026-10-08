package dev.win11we.shell.x11

/**
 * One recorded drawing request. Coordinates are relative to the drawable, as in
 * the X protocol. Pure Kotlin: the Android renderer (D1b) replays these onto a
 * per-window Bitmap; the unit tests inspect them directly.
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

    class Text(val x: Int, val y: Int, val text: String, val argb: Int) : DrawOp()

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
        append(drawable, DrawOp.Text(x, y, text, opaque(foreground)))

    override fun clearArea(drawable: Int, x: Int, y: Int, width: Int, height: Int) =
        append(drawable, DrawOp.Clear(x, y, width, height))

    override fun copyArea(
        srcDrawable: Int, srcX: Int, srcY: Int,
        dstDrawable: Int, dstX: Int, dstY: Int,
        width: Int, height: Int,
    ) = append(dstDrawable, DrawOp.Copy(srcDrawable, srcX, srcY, dstX, dstY, width, height))

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
