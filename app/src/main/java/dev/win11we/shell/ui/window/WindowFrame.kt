package dev.win11we.shell.ui.window

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.win11we.shell.R
import dev.win11we.shell.ui.theme.StrokeSubtle
import dev.win11we.shell.ui.theme.WindowContentDark
import dev.win11we.shell.ui.theme.WindowDark
import dev.win11we.shell.window.SnapZone
import dev.win11we.shell.window.WindowManager
import dev.win11we.shell.window.WindowMode
import dev.win11we.shell.window.WindowState

private val FrameShape = RoundedCornerShape(10.dp)
private val TitleBarDark = Color(0xFF252B36)
private val TitleHeight = 40.dp
private val HandleEdge = 10.dp
private val HandleCorner = 22.dp

/** One framed window: title bar (drag / double-tap), content, 8 resize handles. */
@Composable
fun WindowFrame(
    window: WindowState,
    desktopSize: DpSize,
    focused: Boolean,
    manager: WindowManager,
    onSnapPreview: (SnapZone) -> Unit,
) {
    val bounds = remember(window.mode, window.offset, window.size, desktopSize) { window.bounds(desktopSize) }
    val lightContent = window.app.id == "notepad"

    BoxWithConstraints(
        modifier = Modifier
            .offset { IntOffset(bounds.offset.x.roundToPx(), bounds.offset.y.roundToPx()) }
            .size(bounds.size)
            .shadow(
                elevation = if (focused) 26.dp else 10.dp,
                shape = FrameShape,
                spotColor = Color.Black,
                ambientColor = Color.Black,
            )
            .clip(FrameShape)
            .background(if (lightContent) Color(0xFFF7F9FC) else WindowDark)
            .border(1.dp, if (focused) Color(0x594CC2FF) else StrokeSubtle, FrameShape)
            .pointerInput(window.id) { detectTapGestures(onTap = { manager.focus(window) }) },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TitleHeight)
                    .background(if (lightContent) Color(0xFFEFF4FA) else TitleBarDark),
            ) {
                TitleBar(
                    window = window,
                    focused = focused,
                    manager = manager,
                    desktopSize = desktopSize,
                    onSnapPreview = onSnapPreview,
                    modifier = Modifier.fillMaxSize(),
                )
                // Hairline under the caption, like a real window frame.
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(if (lightContent) Color(0x14000000) else StrokeSubtle),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp))
                    .background(if (lightContent) Color(0xFFF7F9FC) else WindowContentDark),
            ) {
                WindowContent(appId = window.app.id, label = window.app.label, enabled = window.app.enabled)
            }
        }

        if (window.mode == WindowMode.NORMAL) {
            ResizeHandles(window = window, desktopSize = desktopSize)
        }
        // maxWidth/maxHeight come from BoxWithConstraintsScope (used by the handles).
    }
}

@Composable
private fun TitleBar(
    window: WindowState,
    focused: Boolean,
    manager: WindowManager,
    desktopSize: DpSize,
    onSnapPreview: (SnapZone) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .pointerInput(window.id, desktopSize) {
                detectDragGestures(
                    onDragStart = { manager.focus(window) },
                    onDragEnd = { onSnapPreview(SnapZone.NONE) },
                    onDragCancel = { onSnapPreview(SnapZone.NONE) },
                ) { change, dragAmount ->
                    change.consume()
                    if (window.mode != WindowMode.NORMAL) window.snap(WindowMode.NORMAL, desktopSize)
                    window.moveBy(DpOffset(dragAmount.x.toDp(), dragAmount.y.toDp()), desktopSize)
                    val pointerX = window.offset.x + change.position.x.toDp()
                    val pointerY = window.offset.y + change.position.y.toDp()
                    onSnapPreview(
                        snapZoneFor(
                            fractionX = (pointerX / desktopSize.width).coerceIn(0f, 1f),
                            fractionY = (pointerY / desktopSize.height).coerceIn(0f, 1f),
                        ),
                    )
                }
            }
            .pointerInput(window.id) {
                detectTapGestures(
                    onTap = { manager.focus(window) },
                    onDoubleTap = { window.toggleMaximize() },
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(12.dp))
        Icon(
            painter = painterResource(window.app.iconRes),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = Color.Unspecified,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = window.app.label,
            color = if (focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.5.sp,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        WindowControlButton(R.drawable.ic_minimize, "Minimize") { manager.minimize(window) }
        WindowControlButton(
            iconRes = if (window.mode == WindowMode.NORMAL) R.drawable.ic_maximize else R.drawable.ic_restore,
            description = if (window.mode == WindowMode.NORMAL) "Maximize" else "Restore",
        ) { window.toggleMaximize() }
        WindowControlButton(R.drawable.ic_close, "Close", close = true) { manager.close(window) }
    }
}

/** Invisible hit areas on the 4 edges and 4 corners; only while floating. */
@Composable
private fun BoxWithConstraintsScope.ResizeHandles(window: WindowState, desktopSize: DpSize) {
    Handle(window, desktopSize, Alignment.TopStart, HandleCorner, HandleEdge, left = true, top = true)
    Handle(window, desktopSize, Alignment.TopCenter, null, HandleEdge, top = true)
    Handle(window, desktopSize, Alignment.TopEnd, HandleCorner, HandleEdge, right = true, top = true)
    Handle(window, desktopSize, Alignment.CenterStart, HandleEdge, null, left = true)
    Handle(window, desktopSize, Alignment.CenterEnd, HandleEdge, null, right = true)
    Handle(window, desktopSize, Alignment.BottomStart, HandleCorner, HandleEdge, left = true, bottom = true)
    Handle(window, desktopSize, Alignment.BottomCenter, null, HandleEdge, bottom = true)
    Handle(window, desktopSize, Alignment.BottomEnd, HandleCorner, HandleEdge, right = true, bottom = true)
}

@Composable
private fun BoxWithConstraintsScope.Handle(
    window: WindowState,
    desktopSize: DpSize,
    alignment: Alignment,
    handleWidth: Dp?,
    handleHeight: Dp?,
    left: Boolean = false,
    top: Boolean = false,
    right: Boolean = false,
    bottom: Boolean = false,
) {
    val modifier = Modifier
        .align(alignment)
        .then(if (handleWidth != null) Modifier.width(handleWidth) else Modifier.fillMaxWidth())
        .then(if (handleHeight != null) Modifier.height(handleHeight) else Modifier.fillMaxHeight())
        .pointerInput(window.id, left, top, right, bottom) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                window.resizeEdges(
                    dx = dragAmount.x.toDp(),
                    dy = dragAmount.y.toDp(),
                    movesLeft = left,
                    movesTop = top,
                    movesRight = right,
                    movesBottom = bottom,
                    desktop = desktopSize,
                )
            }
        }
    Box(modifier = modifier)
}
