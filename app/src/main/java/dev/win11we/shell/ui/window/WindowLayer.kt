package dev.win11we.shell.ui.window

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.win11we.shell.window.SnapZone
import dev.win11we.shell.window.WindowManager

/**
 * Renders every non-minimized window, bottom of the z-stack first, plus the
 * snap preview while a title bar is being dragged.
 */
@Composable
fun WindowLayer(
    manager: WindowManager,
    desktopSize: DpSize,
    modifier: Modifier = Modifier,
) {
    var snapPreview by remember { mutableStateOf(SnapZone.NONE) }
    val ordered = manager.windows.sortedBy { it.zIndex }

    Box(modifier = modifier.fillMaxSize()) {
        for (window in ordered) {
            if (window.minimized) continue
            WindowFrame(
                window = window,
                desktopSize = desktopSize,
                focused = manager.focusedId == window.id,
                manager = manager,
                onSnapPreview = { snapPreview = it },
            )
        }
        if (snapPreview != SnapZone.NONE) {
            SnapOverlay(zone = snapPreview, desktopSize = desktopSize)
        }
    }
}

/** Maps a pointer position (fractions of the desktop) to a snap target. */
internal fun snapZoneFor(fractionX: Float, fractionY: Float): SnapZone = when {
    fractionY <= 0.035f -> SnapZone.MAXIMIZE
    fractionX <= 0.06f -> SnapZone.LEFT
    fractionX >= 0.94f -> SnapZone.RIGHT
    else -> SnapZone.NONE
}

@Composable
private fun SnapOverlay(zone: SnapZone, desktopSize: DpSize) {
    val density = LocalDensity.current
    val inset = 6.dp
    val topLeft: DpOffset
    val area: DpSize
    when (zone) {
        SnapZone.MAXIMIZE -> {
            topLeft = DpOffset.Zero
            area = desktopSize
        }
        SnapZone.LEFT -> {
            topLeft = DpOffset.Zero
            area = DpSize(desktopSize.width / 2f, desktopSize.height)
        }
        SnapZone.RIGHT -> {
            topLeft = DpOffset(desktopSize.width / 2f, 0.dp)
            area = DpSize(desktopSize.width / 2f, desktopSize.height)
        }
        SnapZone.NONE -> return
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val pad = with(density) { inset.toPx() }
        val left = with(density) { topLeft.x.toPx() } + pad
        val top = with(density) { topLeft.y.toPx() } + pad
        val width = with(density) { area.width.toPx() } - pad * 2f
        val height = with(density) { area.height.toPx() } - pad * 2f
        val radius = with(density) { CornerRadius(10.dp.toPx()) }
        val rectSize = Size(width, height)
        drawRoundRect(color = Color(0x4D4CC2FF), topLeft = Offset(left, top), size = rectSize, cornerRadius = radius)
        drawRoundRect(
            color = Color(0xB34CC2FF),
            topLeft = Offset(left, top),
            size = rectSize,
            cornerRadius = radius,
            style = Stroke(width = with(density) { 1.5.dp.toPx() }),
        )
    }
}
