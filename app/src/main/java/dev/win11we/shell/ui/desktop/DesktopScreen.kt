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
import androidx.compose.runtime.CompositionLocalProvider
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
import dev.win11we.shell.ui.theme.Fluent
import dev.win11we.shell.ui.theme.FluentTheme
import dev.win11we.shell.ui.theme.fluentPalette
import dev.win11we.shell.ui.desktop.FluentBackdrop
import dev.win11we.shell.ui.desktop.LocalFluentBackdrop
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

    FluentTheme(dark = store.themeMode == ThemeMode.DARK) {
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
        val taskbarHeight = Fluent.Size.taskbarHeight
        val desktopSize = DpSize(maxWidth, maxHeight - taskbarHeight)
        val palette = fluentPalette()

        // "Start the emulator -> the desktop is inside it" (Phase 1): when the
        // setting is on (the default), boot the embedded X server and open the
        // X Desktop session MAXIMIZED as soon as the desktop is measured - one
        // window (this Activity), the guest desktop rendered inside it. The
        // shell stays fully reachable: close/minimize that window, or turn the
        // setting off in Settings ▸ Containers for the shell-first boot. Runs
        // once per process (sessionBooted), never on recomposition.
        var sessionBooted by remember { mutableStateOf(false) }
        LaunchedEffect(desktopSize, store.bootToDesktopSession) {
            if (sessionBooted || !store.bootToDesktopSession) return@LaunchedEffect
            sessionBooted = true
            // SessionLifecycle.boot: the display must exist BEFORE any guest is
            // spawned (guests connect to :100 the moment proot execs them).
            val boot = dev.win11we.shell.runtime.SessionLifecycle.boot(
                ensureXServer = { dev.win11we.shell.x11.XServer.ensureRunning() },
                lastXError = { dev.win11we.shell.x11.XServer.lastError },
            )
            if (!boot.success) {
                dev.win11we.shell.ui.terminal.SharedLog.error("session boot: ${boot.message}")
            }
            val spec = dev.win11we.shell.window.ShellApps.firstOrNull { it.id == "xdesktop" }
            if (spec != null && manager.windows.none { it.app.id == "xdesktop" && !it.closing }) {
                manager.open(spec, desktopSize).mode = dev.win11we.shell.window.WindowMode.MAXIMIZED
            }
        }

        // Mica/acrylic source: the bloom wallpaper pre-rendered tiny, blurred by
        // its own downscale and saturated. Once per desktop size and theme, in a
        // remember - never per frame, never from disk.
        val backdrop = remember(desktopSize, palette) {
            FluentBackdrop.create(desktopSize.width.value, desktopSize.height.value, palette.isDark)
        }

        CompositionLocalProvider(LocalFluentBackdrop provides backdrop) {
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
}
