package dev.win11we.shell.runtime

import java.io.File

/**
 * The ONE lifecycle contract that ties the embedded X server to the emulator.
 *
 * The X server is not a separate app and not a separate window: it is the
 * in-process [dev.win11we.shell.x11.XServer] singleton, and its whole lifetime
 * belongs to this process. What was missing is the *contract* - who starts it,
 * who stops it, and in which order the pieces come down so nothing is orphaned
 * and no Wine prefix is corrupted on the way out. That contract lives here, as
 * pure logic with injected seams, so the ordering is unit-tested without
 * Android, sockets or real processes.
 *
 * START (emulator start -> X server starts):
 *   [boot] ensures the display exists BEFORE any guest is spawned, so a guest
 *   never races a server that is not listening yet. The X Desktop surface
 *   calls [dev.win11we.shell.x11.XServer.start] too; both paths are idempotent.
 *
 * STOP (emulator stop -> X server stops), in this exact order:
 *   1. ask every RUNNING Wine session's wineserver to terminate (`wineserver
 *      -k`, best effort, bounded): wineserver flushes the prefix registry when
 *      it exits gracefully, and a SIGKILLed wineserver is the classic source
 *      of half-written `system.reg`/`user.reg` - the "corrupted prefix".
 *   2. stop the guest processes: proot runs with `--kill-on-exit`, so killing
 *      the proot child takes the whole guest tree (box64, wine, firefox,
 *      openbox) with it - no orphans can survive this step.
 *   3. only then close the X server socket: guests are already gone, so no
 *      client can reconnect into a half-torn-down display, and the next boot
 *      starts from a clean slate (stale grabs/selections are released by
 *      `XServer.stop` itself).
 *
 * The same process, the same task: Android kills the whole app cgroup when the
 * process dies, and `ContainerService.onDestroy` runs the guest half of this
 * contract for the "Stop all" notification action (which deliberately keeps
 * the shell's X server alive - the emulator is still open in that case).
 */
object SessionLifecycle {

    /** What [boot] did, for the caller's status line/log. */
    data class BootReport(val success: Boolean, val message: String)

    /** Per-prefix outcome of the graceful `wineserver -k` step. */
    data class WineKill(val prefixDir: File, val killed: Boolean, val detail: String)

    /** What [shutdown] did, step by step - every step always runs (rule: never
     *  let one failing step strand the ones after it). */
    data class ShutdownReport(
        val wineKills: List<WineKill>,
        val guestsStopped: Boolean,
        val xServerStopped: Boolean,
        val guestError: String? = null,
        val xError: String? = null,
    ) {
        fun summary(): String = buildString {
            append("guests stopped: ")
            append(if (guestsStopped) "yes" else "NO (${guestError ?: "?"})")
            append(", X server stopped: ")
            append(if (xServerStopped) "yes" else "NO (${xError ?: "?"})")
            if (wineKills.isNotEmpty()) {
                append(", wineserver kills: ")
                append(wineKills.count { it.killed })
                append('/')
                append(wineKills.size)
            }
        }
    }

    /**
     * Emulator start -> X server start. [ensureXServer] is
     * `XServer::ensureRunning`; it is idempotent, so callers may boot from
     * several entry points (the desktop surface, the browser launcher, the
     * settings-driven auto-open) without coordinating.
     */
    fun boot(
        ensureXServer: () -> Boolean,
        lastXError: () -> String? = { null },
    ): BootReport = try {
        if (ensureXServer()) {
            BootReport(true, "X server ready (embedded, in-process)")
        } else {
            BootReport(false, "X server failed to start: ${lastXError() ?: "unknown error"}")
        }
    } catch (error: Throwable) {
        // Crash-safety rule 1: a lifecycle hook must never take the process down.
        BootReport(false, "X server boot failed: ${error.javaClass.simpleName}: ${error.message}")
    }

    /**
     * Emulator stop -> guests stop -> X server stops, in the documented order.
     * Every seam is called exactly once and every failure is captured in the
     * report instead of aborting the teardown.
     *
     * @param winePrefixes prefixes of the Wine sessions that are RUNNING right
     *   now (the only ones whose wineserver can be asked to flush); the caller
     *   derives them from [ContainerLauncher.runningIds].
     * @param killWineserver best-effort graceful kill of one prefix's
     *   wineserver (see [WinePrefixManager]); returns false + reason when it
     *   could not run - a failure here must NOT block the teardown, the
     *   `--kill-on-exit` in step 2 is the backstop.
     * @param stopGuests destroys every tracked guest process
     *   (`ContainerLauncher.stopAll` / `RuntimeHolder.launcher`).
     * @param stopXServer closes the display (`XServer::stop`).
     */
    fun shutdown(
        winePrefixes: List<File> = emptyList(),
        killWineserver: (File) -> Pair<Boolean, String> = { Pair(false, "no wineserver killer wired") },
        stopGuests: () -> Unit = {},
        stopXServer: () -> Unit = {},
    ): ShutdownReport {
        // 1. Graceful wineserver exits first - the registry flush that keeps
        //    prefixes un-corrupted. Bounded and best effort by contract.
        val kills = winePrefixes.map { prefix ->
            val (killed, detail) = runCatching { killWineserver(prefix) }
                .getOrElse { error -> Pair(false, "${error.javaClass.simpleName}: ${error.message}") }
            WineKill(prefix, killed, detail)
        }
        // 2. Guests: proot's --kill-on-exit takes the whole guest tree down.
        var guestError: String? = null
        val guestsStopped = runCatching { stopGuests() }
            .onFailure { guestError = "${it.javaClass.simpleName}: ${it.message}" }
            .isSuccess
        // 3. The display last: no client is left that could reconnect.
        var xError: String? = null
        val xStopped = runCatching { stopXServer() }
            .onFailure { xError = "${it.javaClass.simpleName}: ${it.message}" }
            .isSuccess
        return ShutdownReport(kills, guestsStopped, xStopped, guestError, xError)
    }
}
