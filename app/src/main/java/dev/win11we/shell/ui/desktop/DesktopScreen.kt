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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.win11we.shell.settings.SettingsStore
import dev.win11we.shell.ui.start.StartMenuPanel
import dev.win11we.shell.settings.ThemeMode
import dev.win11we.shell.ui.taskbar.Taskbar
import dev.win11we.shell.ui.theme.Win11Theme
import dev.win11we.shell.ui.window.WindowLayer
import dev.win11we.shell.window.rememberWindowManager

/**
 * The shell: wallpaper, window layer, Start panel and taskbar.
 * [desktopSize] is the area windows live in (everything above the taskbar).
 */
@Composable
fun DesktopScreen(store: SettingsStore) {
    val context = LocalContext.current
    val manager = rememberWindowManager(context, store)
    var startOpen by remember { mutableStateOf(false) }

    Win11Theme(darkTheme = store.themeMode == ThemeMode.DARK) {
    // P1: bridge X selections <-> the Android clipboard while the desktop lives
    // (guest copies push out; Android-side changes reclaim selection ownership).
    dev.win11we.shell.ui.xdesktop.ClipboardBridgeEffect()
    // Warm the caches that opening a window would otherwise pay for on the main thread:
    // the PackageManager browser query and the SHA-256 of the shipped binaries.
    LaunchedEffect(Unit) {
        dev.win11we.shell.ui.browser.Browsers.warmUp(context)
        val launcher = dev.win11we.shell.runtime.RuntimeHolder.init(
            context,
            dev.win11we.shell.runtime.RuntimePaths
                .configuredRootfs(context.filesDir, store.rootfsLocation).absolutePath,
        )
        launcher.binaries.warmChecksums()
        // Pay the first-window costs (class loading, PackageManager queries, disk reads,
        // container list parsing) now, in the background, instead of on the first tap.
        Thread {
            runCatching {
                dev.win11we.shell.window.ShellApps.forEach { app -> manager.warmPayload(app) }
            }
        }.apply {
            name = "shell-warmup"
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

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
            store = store,
            startOpen = startOpen,
            onStartClick = { startOpen = !startOpen },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
        )
    }
    }
}
