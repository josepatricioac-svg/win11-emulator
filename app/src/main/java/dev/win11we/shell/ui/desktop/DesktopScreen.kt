package dev.win11we.shell.ui.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.win11we.shell.ui.start.StartMenuPanel
import dev.win11we.shell.ui.taskbar.Taskbar
import dev.win11we.shell.ui.window.WindowLayer
import dev.win11we.shell.window.rememberWindowManager

/**
 * The shell: wallpaper, window layer, Start panel and taskbar.
 * [desktopSize] is the area windows live in (everything above the taskbar).
 */
@Composable
fun DesktopScreen() {
    val manager = rememberWindowManager()
    var startOpen by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        val taskbarHeight = 56.dp
        val desktopSize = DpSize(maxWidth, maxHeight - taskbarHeight)

        BloomWallpaper(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = startOpen,
                    onClick = { startOpen = false },
                ),
        )

        Box(modifier = Modifier.fillMaxWidth().height(desktopSize.height)) {
            WindowLayer(manager = manager, desktopSize = desktopSize, modifier = Modifier.fillMaxSize())
        }

        StartMenuPanel(
            open = startOpen,
            onDismiss = { startOpen = false },
            onLaunch = { app ->
                manager.open(app, desktopSize)
                startOpen = false
            },
            modifier = Modifier.fillMaxSize(),
        )

        Taskbar(
            manager = manager,
            desktopSize = desktopSize,
            startOpen = startOpen,
            onStartClick = { startOpen = !startOpen },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        )
    }
}
