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
    // THE desktop's X layer state: one server, one screen, rendered full-bleed
    // behind the shell windows (there is no X Desktop app/window anymore).
    val xState = remember { dev.win11we.shell.ui.xdesktop.XDesktopState() }
    // Lifecycle: emulator start -> embedded X server start, before any guest is
    // spawned (guests connect to :100 the moment proot execs them). Idempotent;
    // a bind failure is named in the shared log, never thrown (crash rule 1).
    LaunchedEffect(Unit) {
        val boot = dev.win11we.shell.runtime.SessionLifecycle.boot(
            ensureXServer = { dev.win11we.shell.x11.XServer.ensureRunning() },
            lastXError = { dev.win11we.shell.x11.XServer.lastError },
        )
        if (!boot.success) {
            dev.win11we.shell.ui.terminal.SharedLog.error("session boot: ${boot.message}")
        }
    }

    FluentTheme(dark = store.themeMode == ThemeMode.DARK) {
    // P1: bridge X selections <-> the Android clipboard while the desktop lives
    // (guest copies push out; Android-side changes reclaim selection ownership).
    dev.win11we.shell.ui.xdesktop.ClipboardBridgeEffect()
    // Warm the caches that opening a window would otherwise pay for on the main
    // thread: the SHA-256 of the shipped binaries. (The PackageManager browser
    // query is gone with Android-browser detection - nothing to warm.)
    LaunchedEffect(Unit) {
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
            // The guest desktop IS this layer: transparent while no guest window
            // is mapped (the wallpaper shows through), the X screen - root
            // background, openbox frames, Firefox, wine apps, software cursor -
            // the moment a session maps one. Desktop taps route into X and also
            // dismiss the Start menu, exactly like a real desktop click.
            dev.win11we.shell.ui.xdesktop.XDesktopSurface(
                state = xState,
                modifier = Modifier.fillMaxSize(),
                onDesktopTap = { startOpen = false },
            )
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
