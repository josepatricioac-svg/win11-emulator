package dev.win11we.shell.ui.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.win11we.shell.ui.taskbar.Taskbar
import dev.win11we.shell.ui.taskbar.rememberTaskbarItems

/**
 * Root of the shell: wallpaper at the bottom, desktop content in the middle
 * (N1 will host the window manager here), taskbar on top.
 */
@Composable
fun DesktopScreen() {
    var startOpen by remember { mutableStateOf(false) }
    val items = rememberTaskbarItems { startOpen = !startOpen }

    Box(modifier = Modifier.fillMaxSize()) {
        BloomWallpaper(modifier = Modifier.fillMaxSize())

        // N1: WindowLayer goes here (draggable/resizable windows, z-order, snapping).

        Taskbar(
            items = items,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
