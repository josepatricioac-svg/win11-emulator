package dev.win11we.shell.ui.window

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.win11we.shell.settings.ShellSettings
import dev.win11we.shell.window.SnapZone
import dev.win11we.shell.window.WindowManager
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** How far along the open/close transition a window is, and what to draw. */
class WindowVisual(
    var progress: Float = 1f,
    var alpha: Float = 1f,
    var scale: Float = 1f,
    var shrink: Float = 1f,
) {
    fun copyFrom(other: WindowVisual) {
        progress = other.progress
        alpha = other.alpha
        scale = other.scale
        shrink = other.shrink
    }

    /** Values for the frame: [enter] ramps 0->1, [leave] ramps 1->0. */
    fun apply(enter: Float, leave: Float) {
        progress = enter
        alpha = enter * leave
        scale = 0.94f + 0.06f * enter
        shrink = leave
    }
}

private enum class Phase { ENTERING, STEADY, LEAVING }

/** Where a window is in its enter/leave transition, and when it started. */
private class Lifecycle(val phase: Phase, val sinceNanos: Long)

private const val OPEN_CLOSE_MILLIS = 220L
private const val MINIMIZE_MILLIS = 140L

/** Decelerating ramp, close to Windows' own window animation feel. */
internal fun easeOut(t: Float): Float = 1f - (1f - t.coerceIn(0f, 1f)).pow(3f)

/**
 * Maps the dragged pointer position (fractions of the desktop) to a snap target.
 * The top edge wins over the side edges, like Windows 11.
 */
internal fun snapZoneFor(fractionX: Float, fractionY: Float): SnapZone = when {
    fractionY <= SNAP_TOP_FRACTION -> SnapZone.MAXIMIZE
    fractionX <= SNAP_SIDE_FRACTION -> SnapZone.LEFT
    fractionX >= 1f - SNAP_SIDE_FRACTION -> SnapZone.RIGHT
    else -> SnapZone.NONE
}

internal const val SNAP_TOP_FRACTION = 0.035f
internal const val SNAP_SIDE_FRACTION = 0.06f

/**
 * Renders every window bottom-of-stack first and drives their enter/leave
 * animations with a single frame loop (no per-window coroutines).
 */
@Composable
fun WindowLayer(
    manager: WindowManager,
    desktopSize: DpSize,
    modifier: Modifier = Modifier,
) {
    var snapPreview by remember { mutableStateOf(SnapZone.NONE) }
    val lifecycles = remember { SnapshotStateMap<Int, Lifecycle>() }
    val visuals = remember { SnapshotStateMap<Int, WindowVisual>() }

    val animated = ShellSettings.get().store.windowAnimations
    val openCloseMillis = if (animated) OPEN_CLOSE_MILLIS else 1L
    val minimizeMillis = if (animated) MINIMIZE_MILLIS else 1L
    // Keyed on the manager only: the loop reads the live window list every frame,
    // so a window opened after this composition still gets its enter animation.
    LaunchedEffect(manager, animated) {
        while (true) {
            val now = System.nanoTime()
            val alive = HashSet<Int>(manager.windows.size).apply { manager.windows.forEach { w -> add(w.id) } }
            lifecycles.keys.filterNot { it in alive }.forEach { id ->
                lifecycles.remove(id)
                visuals.remove(id)
            }
            for (window in manager.windows.toList()) {
                val visual = visuals.getOrPut(window.id) { WindowVisual(progress = 0f, alpha = 0f, scale = 0.94f, shrink = 1f) }
                when (val lifecycle = lifecycles[window.id]) {
                    null -> lifecycles[window.id] = Lifecycle(Phase.ENTERING, now)
                    else -> {
                        val phase = lifecycle.phase
                        val elapsed = (now - lifecycle.sinceNanos) / 1_000_000L
                        when {
                            window.closing -> {
                                val leave = if (phase == Phase.LEAVING) {
                                    1f - easeOut(elapsed / openCloseMillis.toFloat())
                                } else {
                                    1f
                                }
                                visual.apply(enter = 1f, leave = leave)
                                if (phase != Phase.LEAVING) {
                                    lifecycles[window.id] = Lifecycle(Phase.LEAVING, now)
                                } else if (elapsed >= openCloseMillis) {
                                    manager.close(window)
                                }
                            }
                            phase == Phase.ENTERING -> {
                                val enter = easeOut(elapsed / openCloseMillis.toFloat())
                                visual.apply(enter = enter, leave = 1f)
                                if (elapsed >= openCloseMillis) lifecycles[window.id] = Lifecycle(Phase.STEADY, lifecycle.sinceNanos)
                            }
                            else -> {
                                // Minimizing/restore animates the window down/up in place.
                                val target = if (window.minimized) 0f else 1f
                                val current = visual.shrink
                                if (current != target) {
                                    val step = 1f / (minimizeMillis / 16f)
                                    val next = if (target > current) min(target, current + step) else max(target, current - step)
                                    visual.apply(enter = 1f, leave = 1f)
                                    visual.shrink = next
                                    visual.alpha = next
                                    visual.scale = 0.94f + 0.06f * next
                                }
                            }
                        }
                    }
                }
            }
            withFrameNanos { }
        }
    }

    // Read the live list, not a snapshot taken during composition, otherwise a
    // window opened by this very recomposition would not be drawn at all.
    // Reading the live list (not a snapshot taken during composition) keeps newly opened
    // windows visible; `visuals` is only consulted for membership, so the per-frame
    // animation values are read in the draw phase, never in composition.
    val ordered = manager.windows.filter { visuals.containsKey(it.id) }.sortedBy { it.zIndex }
    Box(modifier = modifier.fillMaxSize()) {
        for (window in ordered) {
            val visual = visuals[window.id] ?: continue
            WindowFrame(
                window = window,
                desktopSize = desktopSize,
                focused = manager.focusedId == window.id && !window.closing,
                manager = manager,
                visual = visual,
                onSnapPreview = { snapPreview = it },
            )
        }
        SnapOverlay(zone = snapPreview, desktopSize = desktopSize)
    }
}

/** Snap preview: fades and settles into the target rect. */
@Composable
private fun SnapOverlay(zone: SnapZone, desktopSize: DpSize) {
    val density = LocalDensity.current
    val visible = zone != SnapZone.NONE
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(140),
        label = "snapAlpha",
    )
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
        SnapZone.NONE -> {
            topLeft = DpOffset.Zero
            area = desktopSize
        }
    }
    if (alpha <= 0.01f) return
    Canvas(modifier = Modifier.fillMaxSize()) {
        val pad = with(density) { inset.toPx() }
        val left = with(density) { topLeft.x.toPx() } + pad
        val top = with(density) { topLeft.y.toPx() } + pad
        val width = with(density) { area.width.toPx() } - pad * 2f
        val height = with(density) { area.height.toPx() } - pad * 2f
        val radius = with(density) { CornerRadius(10.dp.toPx()) }
        val rectSize = Size(width, height)
        drawRoundRect(
            color = Color(0x4D4CC2FF).copy(alpha = 0.30f * alpha),
            topLeft = Offset(left, top),
            size = rectSize,
            cornerRadius = radius,
        )
        drawRoundRect(
            color = Color(0xB34CC2FF).copy(alpha = alpha),
            topLeft = Offset(left, top),
            size = rectSize,
            cornerRadius = radius,
            style = Stroke(width = with(density) { 1.5.dp.toPx() }),
        )
    }
}
