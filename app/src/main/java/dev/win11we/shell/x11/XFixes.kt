package dev.win11we.shell.x11

/**
 * XFIXES (D1f) - the small extension GTK3 uses to watch the world change.
 *
 * Why it exists here: GDK3 queries XFIXES at display-open and, when present,
 * switches clipboard handling to event-driven ownership tracking
 * (`XFixesSelectSelectionInput` on PRIMARY/CLIPBOARD - the `owner-change` signal
 * GtkClipboard exposes) and gains cursor introspection. Without XFIXES a GTK3
 * clipboard degrades to "whatever we were told at the last explicit convert";
 * with it, the shell's Android-clipboard reclaims (which change selection
 * ownership from under the guest) become visible to Firefox exactly the way a
 * real X server reports them.
 *
 * Version claim: **5.0, not 6.0** (the wire header's XFIXES_MAJOR). Same rule
 * as RENDER's 0.4 (see [XRender]'s KDoc): 6.0 adds pointer barriers and
 * SetClientDisconnectMode, which we refuse - and a BadRequest on something a
 * client believed available is how GTK dies. At 5.0 everything a client can
 * send is implemented below; the deliberately-refused tail (barriers, v6) is
 * unreachable for a client that negotiated 5.0, and is named in the protocol
 * trace if one sends it anyway.
 *
 * Region algebra is real (rectangle sets with normalisation), not a bounding-box
 * approximation: [XRegionOps] is pure and unit-tested like the rest of x11.
 */
object XFixes {

    /** The name clients query (`XQueryExtension("XFIXES")`); canonical Xorg spelling. */
    const val NAME = "XFIXES"

    /** Dynamically assigned major opcode (after BIG-REQUESTS' 132). */
    const val MAJOR_OPCODE = 133

    /** Event base: SelectionNotify = +0, CursorNotify = +1 (after XI2's 65). */
    const val FIRST_EVENT = 66
    const val EV_SELECTION_NOTIFY = FIRST_EVENT + 0
    const val EV_CURSOR_NOTIFY = FIRST_EVENT + 1

    /** Error base (after RENDER's five and XI2's five): BadRegion, BadBarrier. */
    const val FIRST_ERROR = 170
    const val ERR_BAD_REGION = FIRST_ERROR + 0
    const val ERR_BAD_BARRIER = FIRST_ERROR + 1

    /** The version we claim - see the KDoc: 5.0 keeps the v6 tail out of reach. */
    const val VERSION_MAJOR = 5
    const val VERSION_MINOR = 0

    // --- minor opcodes (xorgproto xfixeswire.h) -----------------------------
    const val MINOR_QUERY_VERSION = 0
    const val MINOR_CHANGE_SAVE_SET = 1
    const val MINOR_SELECT_SELECTION_INPUT = 2
    const val MINOR_SELECT_CURSOR_INPUT = 3
    const val MINOR_GET_CURSOR_IMAGE = 4
    const val MINOR_CREATE_REGION = 5
    const val MINOR_CREATE_REGION_FROM_BITMAP = 6
    const val MINOR_CREATE_REGION_FROM_WINDOW = 7
    const val MINOR_CREATE_REGION_FROM_GC = 8
    const val MINOR_CREATE_REGION_FROM_PICTURE = 9
    const val MINOR_DESTROY_REGION = 10
    const val MINOR_SET_REGION = 11
    const val MINOR_COPY_REGION = 12
    const val MINOR_UNION_REGION = 13
    const val MINOR_INTERSECT_REGION = 14
    const val MINOR_SUBTRACT_REGION = 15
    const val MINOR_INVERT_REGION = 16
    const val MINOR_TRANSLATE_REGION = 17
    const val MINOR_REGION_EXTENTS = 18
    const val MINOR_FETCH_REGION = 19
    const val MINOR_SET_GC_CLIP_REGION = 20
    const val MINOR_SET_WINDOW_SHAPE_REGION = 21
    const val MINOR_SET_PICTURE_CLIP_REGION = 22
    const val MINOR_SET_CURSOR_NAME = 23
    const val MINOR_GET_CURSOR_NAME = 24
    const val MINOR_GET_CURSOR_IMAGE_AND_NAME = 25
    const val MINOR_CHANGE_CURSOR = 26
    const val MINOR_CHANGE_CURSOR_BY_NAME = 27
    const val MINOR_EXPAND_REGION = 28
    const val MINOR_HIDE_CURSOR = 29
    const val MINOR_SHOW_CURSOR = 30
    const val MINOR_CREATE_POINTER_BARRIER = 31
    const val MINOR_DESTROY_POINTER_BARRIER = 32

    // --- SelectionNotify subtypes + SelectSelectionInput mask bits -------------
    const val SUBTYPE_SELECTION_SET = 0
    const val SUBTYPE_SELECTION_DESTROY = 1
    const val SUBTYPE_CLIENT_CLOSE = 2
    const val MASK_SET_SELECTION_OWNER = 1
    const val MASK_SELECTION_WINDOW_DESTROY = 2
    const val MASK_SELECTION_CLIENT_CLOSE = 4

    /** Mask bit the subtype maps to; 0 when the subtype is unknown. */
    fun maskForSubtype(subtype: Int): Int = when (subtype) {
        SUBTYPE_SELECTION_SET -> MASK_SET_SELECTION_OWNER
        SUBTYPE_SELECTION_DESTROY -> MASK_SELECTION_WINDOW_DESTROY
        SUBTYPE_CLIENT_CLOSE -> MASK_SELECTION_CLIENT_CLOSE
        else -> 0
    }

    // --- CursorNotify ---------------------------------------------------------
    const val SUBTYPE_DISPLAY_CURSOR = 0
    const val MASK_DISPLAY_CURSOR = 1

    // --- SetWindowShapeRegion kinds (shapeconst.h) -----------------------------
    const val SHAPE_BOUNDING = 0
    const val SHAPE_CLIP = 1
    const val SHAPE_INPUT = 2

    // --- CreateRegionFromWindow kinds ------------------------------------------
    const val WINDOW_REGION_BOUNDING = 0
    const val WINDOW_REGION_CLIP = 1

    fun minorName(minor: Int): String = when (minor) {
        MINOR_QUERY_VERSION -> "QueryVersion"
        MINOR_CHANGE_SAVE_SET -> "ChangeSaveSet"
        MINOR_SELECT_SELECTION_INPUT -> "SelectSelectionInput"
        MINOR_SELECT_CURSOR_INPUT -> "SelectCursorInput"
        MINOR_GET_CURSOR_IMAGE -> "GetCursorImage"
        MINOR_CREATE_REGION -> "CreateRegion"
        MINOR_CREATE_REGION_FROM_BITMAP -> "CreateRegionFromBitmap"
        MINOR_CREATE_REGION_FROM_WINDOW -> "CreateRegionFromWindow"
        MINOR_CREATE_REGION_FROM_GC -> "CreateRegionFromGC"
        MINOR_CREATE_REGION_FROM_PICTURE -> "CreateRegionFromPicture"
        MINOR_DESTROY_REGION -> "DestroyRegion"
        MINOR_SET_REGION -> "SetRegion"
        MINOR_COPY_REGION -> "CopyRegion"
        MINOR_UNION_REGION -> "UnionRegion"
        MINOR_INTERSECT_REGION -> "IntersectRegion"
        MINOR_SUBTRACT_REGION -> "SubtractRegion"
        MINOR_INVERT_REGION -> "InvertRegion"
        MINOR_TRANSLATE_REGION -> "TranslateRegion"
        MINOR_REGION_EXTENTS -> "RegionExtents"
        MINOR_FETCH_REGION -> "FetchRegion"
        MINOR_SET_GC_CLIP_REGION -> "SetGCClipRegion"
        MINOR_SET_WINDOW_SHAPE_REGION -> "SetWindowShapeRegion"
        MINOR_SET_PICTURE_CLIP_REGION -> "SetPictureClipRegion"
        MINOR_SET_CURSOR_NAME -> "SetCursorName"
        MINOR_GET_CURSOR_NAME -> "GetCursorName"
        MINOR_GET_CURSOR_IMAGE_AND_NAME -> "GetCursorImageAndName"
        MINOR_CHANGE_CURSOR -> "ChangeCursor"
        MINOR_CHANGE_CURSOR_BY_NAME -> "ChangeCursorByName"
        MINOR_EXPAND_REGION -> "ExpandRegion"
        MINOR_HIDE_CURSOR -> "HideCursor"
        MINOR_SHOW_CURSOR -> "ShowCursor"
        MINOR_CREATE_POINTER_BARRIER -> "CreatePointerBarrier"
        MINOR_DESTROY_POINTER_BARRIER -> "DestroyPointerBarrier"
        else -> "minor$minor"
    }
}

/**
 * One XFIXES region: a set of [x, y, width, height] rectangles. Regions are
 * coordinate-free until applied (SetWindowShapeRegion/SetPictureClipRegion add
 * their origin); the algebra below is pure so it is unit-testable without a
 * connection.
 */
class XRegion(initial: List<IntArray> = emptyList()) {

    val rects: MutableList<IntArray> =
        initial.filter { it.isNonEmpty() }.map { it.copyOf() }.toMutableList()

    fun replace(newRects: List<IntArray>) {
        rects.clear()
        newRects.filter { it.isNonEmpty() }.forEach { rects.add(it.copyOf()) }
        XRegionOps.normalizeInPlace(rects)
    }

    fun copy(): XRegion = XRegion(rects)

    /** The bounding box, or null for an empty region (FetchRegion answers 0s). */
    fun extents(): IntArray? = XRegionOps.extents(rects)

    fun contains(px: Int, py: Int): Boolean = XRegionOps.contains(rects, px, py)

    private fun IntArray.isNonEmpty(): Boolean = size == 4 && this[2] > 0 && this[3] > 0
}

/**
 * Rectangle-set algebra for [XRegion]. Deliberately simple and exact rather
 * than fast: regions in this app are menu shapes and clip lists (tens of
 * rectangles), where O(n*m) splitting is invisible.
 *
 * Correctness contract (pinned by `XFixesTest`):
 * - every result is *normalised*: no empty rects, no duplicates, and vertical
 *   neighbours with identical x/width merged, so equal regions have equal
 *   rectangle lists and FetchRegion answers are stable;
 * - the set identities (union/intersect/subtract/invert against [contains])
 *   hold pixel-wise on small fixtures.
 */
object XRegionOps {

    fun union(a: List<IntArray>, b: List<IntArray>): List<IntArray> {
        val out = ArrayList<IntArray>(a.size + b.size)
        out += a.filter { it.nonEmpty() }
        out += b.filter { it.nonEmpty() }
        return normalize(out)
    }

    fun intersect(a: List<IntArray>, b: List<IntArray>): List<IntArray> {
        val out = mutableListOf<IntArray>()
        for (r in a) for (s in b) intersection(r, s)?.let { out += it }
        return normalize(out)
    }

    fun subtract(a: List<IntArray>, b: List<IntArray>): List<IntArray> {
        var out = a.filter { it.nonEmpty() }
        for (s in b) {
            if (!s.nonEmpty()) continue
            val next = mutableListOf<IntArray>()
            for (r in out) next += subtractOne(r, s)
            out = next
        }
        return normalize(out)
    }

    /** [bounds] minus [a] - what InvertRegion means by "the rest of the plane". */
    fun invert(a: List<IntArray>, bounds: IntArray): List<IntArray> =
        subtract(listOf(bounds), a)

    fun translate(a: List<IntArray>, dx: Int, dy: Int): List<IntArray> =
        a.filter { it.nonEmpty() }.map { intArrayOf(it[0] + dx, it[1] + dy, it[2], it[3]) }

    /** Grows every rect by the four margins, then normalises (ExpandRegion). */
    fun expand(a: List<IntArray>, left: Int, right: Int, top: Int, bottom: Int): List<IntArray> {
        val grown = a.filter { it.nonEmpty() }.map {
            intArrayOf(it[0] - left, it[1] - top, it[2] + left + right, it[3] + top + bottom)
        }.filter { it.nonEmpty() }
        return normalize(grown)
    }

    fun extents(a: List<IntArray>): IntArray? {
        val live = a.filter { it.nonEmpty() }
        if (live.isEmpty()) return null
        val x0 = live.minOf { it[0] }
        val y0 = live.minOf { it[1] }
        val x1 = live.maxOf { it[0] + it[2] }
        val y1 = live.maxOf { it[1] + it[3] }
        return intArrayOf(x0, y0, x1 - x0, y1 - y0)
    }

    fun contains(a: List<IntArray>, px: Int, py: Int): Boolean =
        a.any { it.nonEmpty() && px in it[0] until it[0] + it[2] && py in it[1] until it[1] + it[3] }

    private fun intersection(r: IntArray, s: IntArray): IntArray? {
        val x0 = maxOf(r[0], s[0])
        val y0 = maxOf(r[1], s[1])
        val x1 = minOf(r[0] + r[2], s[0] + s[2])
        val y1 = minOf(r[1] + r[3], s[1] + s[3])
        return if (x1 > x0 && y1 > y0) intArrayOf(x0, y0, x1 - x0, y1 - y0) else null
    }

    /** [r] minus [s]: the up-to-four slabs of [r] that survive the cut. */
    private fun subtractOne(r: IntArray, s: IntArray): List<IntArray> {
        intersection(r, s) ?: return listOf(r)
        val out = mutableListOf<IntArray>()
        val sx1 = s[0] + s[2]
        val sy1 = s[1] + s[3]
        val rx1 = r[0] + r[2]
        val ry1 = r[1] + r[3]
        if (s[0] > r[0]) out += intArrayOf(r[0], r[1], s[0] - r[0], r[3]) // left slab
        if (sx1 < rx1) out += intArrayOf(sx1, r[1], rx1 - sx1, r[3]) // right slab
        val midX = maxOf(r[0], s[0])
        val midW = minOf(rx1, sx1) - midX
        if (midW > 0) {
            if (s[1] > r[1]) out += intArrayOf(midX, r[1], midW, s[1] - r[1]) // top slab
            if (sy1 < ry1) out += intArrayOf(midX, sy1, midW, ry1 - sy1) // bottom slab
        }
        return out
    }

    fun normalize(a: List<IntArray>): List<IntArray> {
        val out = a.filter { it.nonEmpty() }.map { it.copyOf() }.toMutableList()
        normalizeInPlace(out)
        return out
    }

    fun normalizeInPlace(rects: MutableList<IntArray>) {
        // Sort into scanline order, dedupe, then merge vertical neighbours with
        // identical x/width. Small lists; a fixpoint loop is fine and stays
        // obviously correct.
        val order = compareBy<IntArray> { it[1] }.thenBy { it[0] }.thenBy { it[3] }.thenBy { it[2] }
        rects.sortWith(order)
        var changed = true
        while (changed) {
            changed = false
            val deduped = mutableListOf<IntArray>()
            for (r in rects) {
                val last = deduped.lastOrNull()
                when {
                    last == null -> deduped += r
                    same(last, r) -> changed = true // drop duplicate
                    last[0] == r[0] && last[2] == r[2] && last[1] + last[3] == r[1] -> {
                        last[3] += r[3] // vertical merge
                        changed = true
                    }
                    else -> deduped += r
                }
            }
            if (changed) {
                rects.clear()
                rects += deduped
                rects.sortWith(order)
            }
        }
    }

    private fun same(a: IntArray, b: IntArray) =
        a[0] == b[0] && a[1] == b[1] && a[2] == b[2] && a[3] == b[3]

    private fun IntArray.nonEmpty(): Boolean = size == 4 && this[2] > 0 && this[3] > 0
}

/**
 * Pixel data for GetCursorImage/GetCursorImageAndName: the built-in glyph
 * shapes the software cursor draws (XDesktop's renderer), rasterised to ARGB at
 * 16x16. Honest approximation, same contract as the renderer's glyph drawing:
 * '#' is the foreground (black), 'o' the background (white outline), ' '
 * transparent. Glyphs without art (pixmap cursors, exotic XC_ names) fall back
 * to the arrow, exactly like the renderer does.
 */
object XFixesCursorArt {

    const val SIZE = 16

    private val LEFT_PTR = arrayOf(
        "#               ",
        "##              ",
        "#o#             ",
        "#o#             ",
        "#o##            ",
        "#o o#           ",
        "#o  #           ",
        "#o  o#          ",
        "#o   #          ",
        "#o  ##          ",
        "#o o #          ",
        "##o  o#         ",
        "# o   #         ",
        "  #   o#        ",
        "   o#o#         ",
        "    ##          ",
    )
    private val XTERM = arrayOf(
        "  oooooooooo    ",
        "   ########     ",
        "      ##        ",
        "      ##        ",
        "      ##        ",
        "      ##        ",
        "      ##        ",
        "      ##        ",
        "      ##        ",
        "      ##        ",
        "      ##        ",
        "      ##        ",
        "      ##        ",
        "   ########     ",
        "  oooooooooo    ",
        "                ",
    )
    private val CROSSHAIR = arrayOf(
        "       ##       ",
        "       ##       ",
        "       ##       ",
        "       ##       ",
        "       ##       ",
        "                ",
        "                ",
        "################",
        "################",
        "                ",
        "                ",
        "       ##       ",
        "       ##       ",
        "       ##       ",
        "       ##       ",
        "       ##       ",
    )
    private val WATCH = arrayOf(
        "   ##########   ",
        "  #oooooooooo#  ",
        " #oo########oo# ",
        " #o#ooooooo#o#  ",
        "##o#ooooooo#o## ",
        "#o#ooooooooo#o# ",
        "#o####ooo####o# ",
        "#oo  #ooo#  oo# ",
        "#oo  #ooo#  oo# ",
        "#o####ooo####o# ",
        "#o#ooooooooo#o# ",
        "##o#ooooooo#o## ",
        " #o#ooooooo#o#  ",
        " #oo########oo# ",
        "  #oooooooooo#  ",
        "   ##########   ",
    )

    data class CursorImage(
        val width: Int,
        val height: Int,
        val xhot: Int,
        val yhot: Int,
        val pixels: IntArray,
    )

    /** [glyph] is an XC_* number (see [CursorGlyph]); fg/bg default to black/white. */
    fun image(glyph: Int, fg: Int = 0xFF000000.toInt(), bg: Int = 0xFFFFFFFF.toInt()): CursorImage {
        val art = when (glyph) {
            CursorGlyph.XTERM -> XTERM
            CursorGlyph.CROSSHAIR, CursorGlyph.PENCIL -> CROSSHAIR
            CursorGlyph.WATCH -> WATCH
            else -> LEFT_PTR
        }
        val hot = when (glyph) {
            CursorGlyph.XTERM -> intArrayOf(8, 8)
            CursorGlyph.CROSSHAIR, CursorGlyph.PENCIL -> intArrayOf(8, 8)
            CursorGlyph.WATCH -> intArrayOf(8, 8)
            else -> intArrayOf(1, 1)
        }
        val pixels = IntArray(SIZE * SIZE)
        for (y in 0 until SIZE) {
            val row = art[y]
            for (x in 0 until SIZE) {
                pixels[y * SIZE + x] = when (row[x]) {
                    '#' -> fg
                    'o' -> bg
                    else -> 0
                }
            }
        }
        return CursorImage(SIZE, SIZE, hot[0], hot[1], pixels)
    }
}
