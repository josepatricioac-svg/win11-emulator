package dev.win11we.shell.x11

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure-Kotlin rasteriser behind GetImage (D1c): fills, images, clears,
 * copies, lines, points, polygons and arcs must land on the right pixels
 * without any Android graphics involved.
 */
class XSnapshotTest {

    private val lists = XDisplayList()

    private fun pixel(snapshot: IntArray, width: Int, x: Int, y: Int): Int = snapshot[y * width + x]

    @Test
    fun `fills and clears rasterise onto the surface`() {
        val drawable = 100
        lists.fillRect(drawable, 2, 3, 4, 5, 0xFF0000)
        var snapshot = lists.snapshot(drawable, 16, 16)
        assertEquals(0xFFFF0000.toInt(), pixel(snapshot, 16, 2, 3))
        assertEquals(0xFFFF0000.toInt(), pixel(snapshot, 16, 5, 7))
        assertEquals(0xFF000000.toInt(), pixel(snapshot, 16, 1, 3)) // untouched stays black
        assertEquals(0xFF000000.toInt(), pixel(snapshot, 16, 6, 3)) // one past the fill

        lists.clearArea(drawable, 2, 3, 0, 0) // 0x0 = to the edges
        snapshot = lists.snapshot(drawable, 16, 16)
        assertEquals(0xFFFFFFFF.toInt(), pixel(snapshot, 16, 15, 15))
        assertEquals(0xFF000000.toInt(), pixel(snapshot, 16, 1, 1))
    }

    @Test
    fun `images blit with clipping`() {
        val drawable = 110
        // A 2x2 green image placed at (-1, 0): the left column clips away.
        val data = ByteArray(16) { 0 }
        for (i in 0 until 4) {
            data[i * 4] = 0 // B
            data[i * 4 + 1] = 0xFF.toByte() // G
            data[i * 4 + 2] = 0 // R
        }
        lists.putImage(drawable, -1, 0, 2, 2, 24, data)
        val snapshot = lists.snapshot(drawable, 8, 8)
        assertEquals(0xFF00FF00.toInt(), pixel(snapshot, 8, 0, 0)) // only the in-bounds column
        assertEquals(0xFF000000.toInt(), pixel(snapshot, 8, 1, 0))
    }

    @Test
    fun `lines use bresenham and points are single pixels`() {
        val drawable = 120
        lists.lines(drawable, intArrayOf(0, 0, 4, 4), 0x0000FF, pairs = false)
        val snapshot = lists.snapshot(drawable, 8, 8)
        for (i in 0..4) assertEquals("diagonal pixel $i", 0xFF0000FF.toInt(), pixel(snapshot, 8, i, i))
        assertEquals(0xFF000000.toInt(), pixel(snapshot, 8, 1, 2))

        lists.points(drawable, intArrayOf(6, 1), 0x00FF00)
        val withPoint = lists.snapshot(drawable, 8, 8)
        assertEquals(0xFF00FF00.toInt(), pixel(withPoint, 8, 6, 1))
    }

    @Test
    fun `polygons fill their spans`() {
        val drawable = 130
        // A triangle covering the middle of a 16x16 surface.
        lists.polygon(drawable, intArrayOf(8, 2, 14, 12, 2, 12), 0x0000FF)
        val snapshot = lists.snapshot(drawable, 16, 16)
        assertEquals(0xFF0000FF.toInt(), pixel(snapshot, 16, 8, 10)) // inside
        assertEquals(0xFF000000.toInt(), pixel(snapshot, 16, 1, 1)) // outside
    }

    @Test
    fun `arcs sample the ellipse`() {
        val drawable = 140
        // Full circle on a 20x20 box at (0,0): the topmost point must be painted.
        lists.arc(drawable, 0, 0, 20, 20, 0, 360 * 64, filled = false, pixel = 0xFF0000)
        val snapshot = lists.snapshot(drawable, 24, 24)
        assertEquals(0xFFFF0000.toInt(), pixel(snapshot, 24, 10, 0)) // top of the circle
        assertEquals(0xFF000000.toInt(), pixel(snapshot, 24, 10, 10)) // centre stays empty
    }

    @Test
    fun `copies composite other drawables`() {
        val src = 150
        val dst = 151
        lists.setSize(src, 4, 4)
        lists.fillRect(src, 0, 0, 4, 4, 0x00FF00)
        lists.copyArea(src, 0, 0, dst, 2, 2, 4, 4)
        val snapshot = lists.snapshot(dst, 8, 8, resolveSize = { lists.sizeOf(it) })
        assertEquals(0xFF00FF00.toInt(), pixel(snapshot, 8, 2, 2))
        assertEquals(0xFF00FF00.toInt(), pixel(snapshot, 8, 5, 5))
        assertEquals(0xFF000000.toInt(), pixel(snapshot, 8, 1, 1))
    }

    @Test
    fun `self copies do not recurse forever`() {
        val drawable = 160
        lists.setSize(drawable, 8, 8)
        lists.fillRect(drawable, 0, 0, 2, 2, 0xFF0000)
        lists.copyArea(drawable, 0, 0, drawable, 4, 4, 2, 2) // self-referential: guarded
        val snapshot = lists.snapshot(drawable, 8, 8, resolveSize = { lists.sizeOf(it) })
        assertEquals(0xFFFF0000.toInt(), pixel(snapshot, 8, 0, 0))
        assertTrue(snapshot.size == 64)
    }

    @Test
    fun `empty and zero-sized snapshots stay safe`() {
        assertEquals(0, lists.snapshot(999, 0, 0).size)
        assertEquals(0, lists.snapshot(999, -4, 8).size)
        val untouched = lists.snapshot(999, 2, 2)
        assertEquals(4, untouched.size)
        assertEquals(0xFF000000.toInt(), untouched[0]) // opaque black by default
    }
}
