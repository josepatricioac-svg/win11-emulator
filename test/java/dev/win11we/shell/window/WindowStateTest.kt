package dev.win11we.shell.window

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.win11we.shell.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Geometry maths of the window manager, unit-tested on the JVM (no Robolectric). */
class WindowStateTest {

    private val desktop = DpSize(1200.dp, 700.dp)
    private val app = AppSpec("notepad", "Notepad", R.drawable.ic_app_notepad)

    private fun window(
        offset: DpOffset = DpOffset(100.dp, 80.dp),
        size: DpSize = DpSize(400.dp, 300.dp),
    ) = WindowState(id = 1, app = app, initialOffset = offset, initialSize = size)

    @Test
    fun `maximize fills the desktop and restore brings the floating rect back`() {
        val w = window()
        w.toggleMaximize()
        assertEquals(WindowMode.MAXIMIZED, w.mode)
        assertEquals(WindowBounds(DpOffset.Zero, desktop), w.bounds(desktop))

        w.toggleMaximize()
        assertEquals(WindowMode.NORMAL, w.mode)
        assertEquals(DpOffset(100.dp, 80.dp), w.offset)
        assertEquals(DpSize(400.dp, 300.dp), w.size)
    }

    @Test
    fun `snap left and right cover exactly one half`() {
        val w = window()
        w.snap(WindowMode.SNAP_LEFT, desktop)
        assertEquals(WindowBounds(DpOffset.Zero, DpSize(600.dp, 700.dp)), w.bounds(desktop))

        w.snap(WindowMode.SNAP_RIGHT, desktop)
        assertEquals(WindowBounds(DpOffset(600.dp, 0.dp), DpSize(600.dp, 700.dp)), w.bounds(desktop))
    }

    @Test
    fun `snapping back to normal restores the floating rect`() {
        val w = window()
        w.snap(WindowMode.SNAP_LEFT, desktop)
        w.snap(WindowMode.NORMAL, desktop)
        assertEquals(WindowMode.NORMAL, w.mode)
        assertEquals(DpOffset(100.dp, 80.dp), w.offset)
        assertEquals(DpSize(400.dp, 300.dp), w.size)
    }

    @Test
    fun `dragging the right edge grows the width and keeps the left edge`() {
        val w = window()
        w.resizeEdges(50.dp, 0.dp, movesLeft = false, movesTop = false, movesRight = true, movesBottom = false, desktop = desktop)
        assertEquals(DpOffset(100.dp, 80.dp), w.offset)
        assertEquals(DpSize(450.dp, 300.dp), w.size)
    }

    @Test
    fun `dragging the left edge moves the offset and keeps the right edge fixed`() {
        val w = window()
        w.resizeEdges(-40.dp, 0.dp, movesLeft = true, movesTop = false, movesRight = false, movesBottom = false, desktop = desktop)
        assertEquals(DpOffset(60.dp, 80.dp), w.offset)
        assertEquals(DpSize(440.dp, 300.dp), w.size)
    }

    @Test
    fun `resize never goes below the minimum size`() {
        val w = window()
        w.resizeEdges(-300.dp, -300.dp, movesLeft = true, movesTop = true, movesRight = false, movesBottom = false, desktop = desktop)
        assertTrue(w.size.width >= w.minSize.width)
        assertTrue(w.size.height >= w.minSize.height)
    }

    @Test
    fun `moving keeps at least part of the window on screen`() {
        val w = window()
        w.moveBy(DpOffset(5000.dp, 5000.dp), desktop)
        assertTrue(w.offset.x <= desktop.width)
        assertTrue(w.offset.y <= desktop.height)
        w.moveBy(DpOffset(-9000.dp, -9000.dp), desktop)
        assertTrue(w.offset.y >= 0.dp)
    }
}
