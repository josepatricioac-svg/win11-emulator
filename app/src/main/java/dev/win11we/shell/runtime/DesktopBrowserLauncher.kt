package dev.win11we.shell.runtime

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import dev.win11we.shell.ui.browser.DesktopLaunchOutcome
import dev.win11we.shell.x11.XServer

/** What the shell needs to know before it may promise a desktop-engine launch. */
data class EngineReadiness(val ready: Boolean, val missing: String?)

/**
 * Readiness for the desktop-browser catalogue. The Browser window and Settings
 * read [snapshot]; because it is Compose state written under a mutable snapshot
 * from a guarded low-priority worker (crash-safety rules 1 and 2: no disk I/O
 * during composition, no bare threads), the UI updates as pieces land without
 * ever blocking a frame. Until every check passes, engines stay gated and every
 * "no" names the step that unlocks it - the roadmap-gating contract.
 */
object DesktopEngineReadiness {

    var snapshot: Map<String, EngineReadiness> by mutableStateOf(emptyMap())
        private set

    fun ready(engineId: String): Boolean = snapshot[engineId]?.ready == true

    /** The named missing step, for status lines and toasts; null when ready/unknown. */
    fun missing(engineId: String): String? = snapshot[engineId]?.missing

    fun refreshAsync(context: Context, onDone: () -> Unit = {}) {
        val appContext = context.applicationContext
        Thread {
            runCatching {
                val result = evaluate(appContext)
                Snapshot.withMutableSnapshot { snapshot = result }
            }
            runCatching { onDone() }
        }.apply {
            name = "desktop-engine-readiness"
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /**
     * The checks themselves - only ever called from the worker (it stats files
     * and may hash binaries through the stamp cache).
     */
    fun evaluate(context: Context): Map<String, EngineReadiness> {
        val appContext = context.applicationContext
        val binaries = NativeBinaries.from(
            nativeLibraryDirPath = appContext.applicationInfo.nativeLibraryDir,
            pins = NativeBinaries.loadPinsFromAssets(appContext.assets),
        )
        val rootfsDir = RuntimeHolder.rootfsDir ?: RuntimePaths.defaultRootfs(appContext.filesDir)
        // The browser session is native arm64: it needs proot's set, not box64.
        val prepare = binaries.prepare(NativeBinaries.REQUIRED_PROOT_ONLY)
        val firefox = when {
            prepare.status != PrepareStatus.READY ->
                EngineReadiness(false, "R1 native binaries missing or unpinned: ${prepare.message}")
            !RuntimePaths.rootfsExtracted(rootfsDir) ->
                EngineReadiness(false, "R3 rootfs not extracted (Settings \u25B8 Storage \u25B8 Runtime download)")
            !FirefoxSession.isInstalled(rootfsDir) ->
                EngineReadiness(false, "Firefox ESR not installed (download + extract it in Runtime download)")
            !FirefoxSession.guiLibrariesPresent(rootfsDir) ->
                EngineReadiness(
                    false,
                    "Rootfs GUI libraries (libgtk-3) not provisioned - run Provision (Settings \u25B8 Storage \u25B8 Runtime download)",
                )
            else -> EngineReadiness(true, null)
        }
        // The other catalogue engines keep their documented gates until their steps land.
        return mapOf(
            FirefoxSession.ENGINE_ID to firefox,
            "desktop:edge-arm64" to EngineReadiness(false, "Not built yet: needs the Edge arm64 payload (licence accept) and D2 per-window surfaces"),
            "desktop:firefox-wine" to EngineReadiness(
                false,
                "Bring-your-own Windows Firefox inside a container prefix: install the Wine " +
                    "payload, Initialize prefix, then run firefox.exe through WineBrowserLaunch's " +
                    "hardened plan (box64 is vendored; no UI wiring yet)",
            ),
            "desktop:chromium-arm64" to EngineReadiness(false, "Not built yet: no pinned Chromium arm64 artifact in the R3 manifest"),
        )
    }
}

/**
 * Launches Firefox ESR through R2's machinery: the shell's X server is ensured
 * first (guests connect to `:100`), then the session goes through
 * [ContainerLauncher] - the same validate/spawn/stream/status path containers
 * use - and the foreground service is started so the process outlives the
 * window and dies with "Stop all". Every failure names its reason; nothing is
 * attempted silently, and every throwable is caught (crash-safety rule 1).
 */
object DesktopBrowserLauncher {

    /**
     * Launch lifecycle for the X Desktop window's loading indicator (Goal 2
     * step 2): STARTING covers the gap between the proot spawn and the first
     * guest X connection - up to a minute on a first run while fontconfig
     * builds its cache - so the window says what is happening instead of
     * looking dead. Compose state; written from launch (any thread) under a
     * mutable snapshot, read by the X Desktop window.
     */
    enum class LaunchPhase { IDLE, STARTING, RUNNING, FAILED }

    var launchPhase: LaunchPhase by mutableStateOf(LaunchPhase.IDLE)
        private set

    var launchMessage: String? by mutableStateOf(null)
        private set

    private fun setPhase(phase: LaunchPhase, message: String?) {
        Snapshot.withMutableSnapshot {
            launchPhase = phase
            launchMessage = message
        }
    }

    /** Called by the X Desktop window when a guest client has connected. */
    fun noteClientConnected() {
        if (launchPhase == LaunchPhase.STARTING) setPhase(LaunchPhase.RUNNING, null)
    }

    fun launch(context: Context, engineId: String, url: String, rootfsOverride: String? = null): DesktopLaunchOutcome {
        if (engineId != FirefoxSession.ENGINE_ID) {
            return DesktopLaunchOutcome(
                false,
                "Only Firefox ESR (Linux arm64) is launchable so far; $engineId waits on its roadmap step.",
            )
        }
        return try {
            val appContext = context.applicationContext
            val xReady = XServer.ensureRunning()
            if (!xReady) {
                setPhase(LaunchPhase.FAILED, "X server failed to start: ${XServer.lastError ?: "unknown error"}")
                return DesktopLaunchOutcome(false, "X server failed to start: ${XServer.lastError ?: "unknown error"}")
            }
            val readiness = DesktopEngineReadiness.ready(engineId)
            val launcher = RuntimeHolder.init(appContext, rootfsOverride)
            val request = RuntimeHolder.firefoxRequest(appContext, url, rootfsOverride)
            if (!readiness) {
                // The launcher validates binaries/rootfs/prefix itself; this adds the
                // firefox-specific pieces so the refusal names them precisely.
                val missing = DesktopEngineReadiness.missing(engineId) ?: "runtime not ready"
                setPhase(LaunchPhase.FAILED, missing)
                return DesktopLaunchOutcome(false, "Firefox ESR is not launchable yet: $missing")
            }
            if (launcher.isRunning(FirefoxSession.SESSION_ID)) {
                if (url.isBlank()) {
                    return DesktopLaunchOutcome(
                        false,
                        "Firefox ESR is already running (no URL to hand over) - shown in the X Desktop window.",
                    )
                }
                // Hand the URL to the RUNNING instance through its D-Bus session:
                // the same wrapped command, tracked under its own id; firefox's
                // remote client forwards and exits. Its output streams to the
                // shared log, so a failed handoff is visible, not silent.
                val handoff = RuntimeHolder.firefoxRequest(appContext, url, rootfsOverride, handoff = true)
                val handoffResult = launcher.start(handoff)
                return if (handoffResult.success) {
                    DesktopLaunchOutcome(
                        true,
                        "Handing \"$url\" to the running Firefox over its D-Bus session - " +
                            "the outcome lands in Terminal \u25B8 log.",
                    )
                } else {
                    DesktopLaunchOutcome(
                        false,
                        "Firefox ESR is running, but the URL handoff was refused " +
                            "[${handoffResult.failure}]: ${handoffResult.message}",
                    )
                }
            }
            setPhase(
                LaunchPhase.STARTING,
                "Starting Firefox ESR on ${RuntimePaths.X_DISPLAY}\u2026\n" +
                    "First run extracts caches and builds the fontconfig cache - up to a minute.\n" +
                    "Output streams to Terminal \u25B8 log.",
            )
            val result = launcher.start(request)
            if (result.success) {
                ContainerService.start(appContext)
                DesktopLaunchOutcome(
                    true,
                    "Firefox ESR starting on ${RuntimePaths.X_DISPLAY} - open Start \u25B8 X Desktop to see it.",
                )
            } else {
                setPhase(LaunchPhase.FAILED, "[${result.failure}] ${result.message}")
                DesktopLaunchOutcome(false, "Firefox ESR refused to start [${result.failure}]: ${result.message}")
            }
        } catch (error: Throwable) {
            DesktopLaunchOutcome(false, "Launch failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    /** Whether the desktop-browser session is alive (status lines, Task Manager later). */
    fun isRunning(): Boolean = RuntimeHolder.launcher?.isRunning(FirefoxSession.SESSION_ID) == true

    fun stop(): String {
        setPhase(LaunchPhase.IDLE, null)
        return RuntimeHolder.launcher
            ?.stop(FirefoxSession.SESSION_ID, reason = "desktop browser stopped by user")?.message
            ?: "Not running."
    }
}
