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
import dev.win11we.shell.settings.SettingsStore
import dev.win11we.shell.settings.TaskbarAlignment
import dev.win11we.shell.ui.theme.Fluent
import dev.win11we.shell.ui.theme.ShellSurfaces
import dev.win11we.shell.window.AppSpec
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
    store: SettingsStore,
    startOpen: Boolean,
    onStartClick: () -> Unit,
    onOpenApp: (AppSpec) -> Unit = { app -> manager.toggle(app, desktopSize) },
    modifier: Modifier = Modifier,
) {
    val pinned = ShellApps.filter { app ->
        app.pinned && when (app.id) {
            "browser" -> store.showBrowserPin
            "containers" -> store.showContainersPin
            else -> true
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .background(ShellSurfaces.taskbar)
            .navigationBarsPadding()
            .height(Fluent.Size.taskbarHeight),
    ) {
        // Hairline separating the bar from the desktop.
        Box(Modifier.fillMaxWidth().height(1.dp).background(ShellSurfaces.stroke))

        Row(
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WidgetsChip()
        }

        Row(
            modifier = Modifier.align(
                if (store.taskbarAlignment == TaskbarAlignment.CENTER) Alignment.Center else Alignment.CenterStart,
            ).padding(start = if (store.taskbarAlignment == TaskbarAlignment.LEFT) 120.dp else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TaskbarButton(
                iconRes = R.drawable.ic_start,
                label = "Start",
                running = startOpen,
                focused = startOpen,
                onClick = onStartClick,
                menuEntries = listOf(
                    PinMenuEntry("Settings") { onOpenApp(ShellApps.first { it.id == "settings" }) },
                    PinMenuEntry("Task Manager") { onOpenApp(ShellApps.first { it.id == "taskmgr" }) },
                ),
            )
            pinned.forEach { app ->
                // Its own composable: the window-list reads stay scoped to this pin, so
                // dragging or resizing a window does not recompose the whole taskbar.
                AppPin(app = app, manager = manager, desktopSize = desktopSize, onOpenApp = onOpenApp)
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

@Composable
private fun AppPin(
    app: AppSpec,
    manager: WindowManager,
    desktopSize: DpSize,
    onOpenApp: (AppSpec) -> Unit,
) {
    val instances = manager.windows.filter { it.app.id == app.id }
    val running = instances.isNotEmpty()
    val focused = instances.any { manager.focusedId == it.id && !it.minimized }
    TaskbarButton(
        iconRes = app.iconRes,
        label = app.label,
        running = running,
        focused = focused,
        onClick = { manager.toggle(app, desktopSize) },
        menuEntries = buildList {
            instances.filterNot { it.closing }.forEachIndexed { index, window ->
                val name = "${app.label}${if (instances.size > 1) " ${index + 1}" else ""}"
                add(PinMenuEntry(if (window.minimized) "Restore  ·  $name" else name) { manager.focus(window) })
            }
            add(PinMenuEntry("New window") { manager.open(app, desktopSize) })
            add(PinMenuEntry("Task Manager") { onOpenApp(ShellApps.first { it.id == "taskmgr" }) })
        },
    )
}
