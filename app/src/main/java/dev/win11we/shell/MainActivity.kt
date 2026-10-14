package dev.win11we.shell

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.win11we.shell.runtime.RuntimeHolder
import dev.win11we.shell.runtime.SessionLifecycle
import dev.win11we.shell.runtime.WinePrefixManager
import dev.win11we.shell.settings.rememberSettingsStore
import dev.win11we.shell.ui.desktop.DesktopScreen
import dev.win11we.shell.ui.terminal.SharedLog
import dev.win11we.shell.x11.XServer
import java.io.File

/**
 * The single Activity of the shell - the ONE window of the emulator. N0 renders
 * one screen: wallpaper + taskbar. N1 adds the Start menu and the window manager
 * on top of this surface; the X Desktop session renders INSIDE this Activity as
 * a shell window (never a second Activity, never another app).
 *
 * LIFECYCLE CONTRACT (see [SessionLifecycle] for the ordering rules):
 * - start: the desktop boots the embedded X server before any guest is spawned
 *   (DesktopScreen's boot effect and DesktopBrowserLauncher both call
 *   `XServer.ensureRunning`, which is idempotent).
 * - stop: `onDestroy` runs the full teardown - graceful `wineserver -k` for the
 *   running Wine sessions (registry flush: the prefix-corruption fix), then all
 *   guest processes (proot's `--kill-on-exit` takes the whole guest tree down),
 *   then the X server socket. Nothing outlives the emulator: the X server is
 *   in-process, the guests are children of this process, and Android reaps the
 *   whole cgroup if the process itself is killed.
 *
 * Rotation/resize do NOT destroy this Activity (the manifest declares the
 * configChanges), so onDestroy here means "the emulator is really closing".
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DesktopScreen(store = rememberSettingsStore())
        }
    }

    override fun onDestroy() {
        // Emulator stop -> X server stop, with the guests in between. Runs on
        // the main thread; the wineserver kills are bounded (see KILL_TIMEOUT)
        // so teardown cannot stall, and every step is guarded inside
        // SessionLifecycle - a failure is reported, never thrown (crash rule 1).
        val launcher = RuntimeHolder.launcher
        val wineRequests = launcher?.runningWineRequests() ?: emptyList()
        val binaries = launcher?.binaries
        val report = SessionLifecycle.shutdown(
            winePrefixes = wineRequests.map { File(it.container.prefixPath) },
            killWineserver = { prefixDir ->
                val request = wineRequests.firstOrNull { File(it.container.prefixPath) == prefixDir }
                if (request == null || binaries == null) {
                    Pair(false, "no runtime context for ${prefixDir.name}")
                } else {
                    WinePrefixManager.killServers(
                        binaries = binaries,
                        rootfsDir = request.rootfsDir,
                        container = request.container,
                        binds = request.binds,
                        runner = dev.win11we.shell.runtime.JvmProcessRunner(),
                        timeoutMillis = KILL_TIMEOUT_MILLIS,
                        log = SharedLog.buffer,
                    )
                }
            },
            stopGuests = { launcher?.stopAll(reason = "emulator stopped") },
            stopXServer = { XServer.stop() },
        )
        // The shared log dies with the process; the framework log survives in logcat.
        android.util.Log.i("SessionLifecycle", "teardown: ${report.summary()}")
        super.onDestroy()
    }

    private companion object {
        /** Per-prefix bound for the graceful wineserver exit during teardown. */
        const val KILL_TIMEOUT_MILLIS = 2_500L
    }
}
