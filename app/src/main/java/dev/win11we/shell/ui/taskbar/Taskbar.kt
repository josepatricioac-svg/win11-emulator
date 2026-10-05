package dev.win11we.shell.ui.taskbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.win11we.shell.R
import dev.win11we.shell.ui.theme.StartSurface
import dev.win11we.shell.ui.theme.StrokeSubtle
import dev.win11we.shell.window.ShellApps
import dev.win11we.shell.window.WindowManager

/**
 * Windows 11-style taskbar: widgets chip on the left, centred pins,
 * tray + clock on the right. 56dp tall above the navigation inset.
 */
@Composable
fun Taskbar(
    manager: WindowManager,
    desktopSize: DpSize,
    startOpen: Boolean,
    onStartClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pinned = ShellApps.filter { it.pinned }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .background(StartSurface)
            .navigationBarsPadding()
            .height(56.dp),
    ) {
        // Hairline separating the bar from the desktop.
        Box(Modifier.fillMaxWidth().height(1.dp).background(StrokeSubtle))

        Row(
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WidgetsChip()
        }

        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TaskbarButton(
                iconRes = R.drawable.ic_start,
                label = "Start",
                running = startOpen,
                focused = startOpen,
                onClick = onStartClick,
            )
            pinned.forEach { app ->
                val instances = manager.windows.filter { it.app.id == app.id }
                val running = instances.isNotEmpty()
                val focused = instances.any { manager.focusedId == it.id && !it.minimized }
                TaskbarButton(
                    iconRes = app.iconRes,
                    label = app.label,
                    running = running,
                    focused = focused,
                    onClick = { manager.toggle(app, desktopSize) },
                )
            }
        }

        Row(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrayIcons()
            Spacer(Modifier.width(4.dp))
            ClockButton()
        }
    }
}
