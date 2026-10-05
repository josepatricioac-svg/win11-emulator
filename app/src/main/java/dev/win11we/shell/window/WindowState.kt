package dev.win11we.shell.window

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

enum class WindowMode { NORMAL, MAXIMIZED, SNAP_LEFT, SNAP_RIGHT }

/** Mutable state of one top-level window. Geometry is in desktop coordinates (dp). */
@Stable
class WindowState(
    val id: Int,
    val app: AppSpec,
    initialOffset: DpOffset,
    initialSize: DpSize,
    val minSize: DpSize = DpSize(340.dp, 220.dp),
) {
    var mode by mutableStateOf(WindowMode.NORMAL)
    var minimized by mutableStateOf(false)
    var zIndex by mutableIntStateOf(0)

    /** Floating geometry; only meaningful while [mode] == NORMAL. */
    var offset by mutableStateOf(initialOffset)
    var size by mutableStateOf(initialSize)

    /** Floating geometry remembered before maximizing or snapping. */
    private var savedOffset = initialOffset
    private var savedSize = initialSize

    fun bounds(desktop: DpSize): WindowBounds = when (mode) {
        WindowMode.NORMAL -> WindowBounds(offset, size)
        WindowMode.MAXIMIZED -> WindowBounds(DpOffset.Zero, desktop)
        WindowMode.SNAP_LEFT -> WindowBounds(DpOffset.Zero, DpSize(desktop.width / 2f, desktop.height))
        WindowMode.SNAP_RIGHT -> WindowBounds(
            DpOffset(desktop.width / 2f, 0.dp),
            DpSize(desktop.width / 2f, desktop.height),
        )
    }

    /** Float -> maximize (remembering the floating rect); anything else -> float. */
    fun toggleMaximize() {
        if (mode == WindowMode.NORMAL) {
            savedOffset = offset
            savedSize = size
            mode = WindowMode.MAXIMIZED
        } else {
            mode = WindowMode.NORMAL
            offset = savedOffset
            size = savedSize
        }
    }

    fun snap(target: WindowMode, desktop: DpSize) {
        if (mode == WindowMode.NORMAL) {
            savedOffset = offset
            savedSize = size
        }
        if (target == WindowMode.NORMAL) {
            size = savedSize
            offset = savedOffset.coerceIn(desktop, size)
        }
        mode = target
    }

    /** Drag the floating rect; at least 64dp of it stays reachable on screen. */
    fun moveBy(delta: DpOffset, desktop: DpSize) {
        if (mode != WindowMode.NORMAL) return
        offset = (offset + delta).coerceIn(desktop, size)
    }

    /**
     * Resize a floating window: [dx]/[dy] are the drag deltas in dp and the
     * `moves*` flags say which edges the handle owns. The opposite corner stays put.
     */
    fun resizeEdges(
        dx: androidx.compose.ui.unit.Dp,
        dy: androidx.compose.ui.unit.Dp,
        movesLeft: Boolean,
        movesTop: Boolean,
        movesRight: Boolean,
        movesBottom: Boolean,
        desktop: DpSize,
    ) {
        if (mode != WindowMode.NORMAL) return
        val left = offset.x + if (movesLeft) dx else 0.dp
        val top = offset.y + if (movesTop) dy else 0.dp
        val right = offset.x + size.width + if (movesRight) dx else 0.dp
        val bottom = offset.y + size.height + if (movesBottom) dy else 0.dp
        val width = (right - left).coerceAtLeast(minSize.width)
        val height = (bottom - top).coerceAtLeast(minSize.height)
        size = DpSize(width, height)
        offset = DpOffset(left, top).coerceIn(desktop, size)
    }
}

data class WindowBounds(val offset: DpOffset, val size: DpSize)

private fun DpOffset.coerceIn(desktop: DpSize, size: DpSize): DpOffset {
    val margin = 64.dp
    val maxX = (desktop.width - margin).coerceAtLeast(0.dp)
    val maxY = (desktop.height - margin).coerceAtLeast(0.dp)
    val minX = -(size.width - margin).coerceAtLeast(0.dp)
    return DpOffset(x.coerceIn(minX, maxX), y.coerceIn(0.dp, maxY))
}
