package dev.win11we.shell.runtime

import android.content.Context
import dev.win11we.shell.container.Container
import dev.win11we.shell.ui.containers.PrefixCallback
import dev.win11we.shell.ui.containers.PrefixOps
import dev.win11we.shell.ui.terminal.SharedLog
import java.io.File

/**
 * The production [PrefixOps]: the Container Manager's Prefix Manager verbs on
 * guarded worker threads, backed by [WinePrefixManager] (pure command/verdict
 * logic) and [WineProvisioner] (the payload install). Every verb answers
 * through its [PrefixCallback] exactly once - success OR failure - so the UI's
 * busy state can never get stuck, and nothing here runs on the main thread
 * (wineboot/apt are minutes-long; crash-safety rules 1 and 2).
 *
 * Isolation reminder: [WinePrefixManager.create] binds ONLY this container's
 * prefix at the guest's `/root/.wine` and sets WINEPREFIX/WINEARCH/DISPLAY
 * explicitly - two containers can never touch the same prefix, and the host's
 * `~/.wine` is never involved.
 */
class WinePrefixOps(context: Context, private val rootfsOverride: String?) : PrefixOps {

    private val appContext = context.applicationContext

    /** The same rootfs resolution every other component uses (one truth). */
    private fun rootfs(): File = RuntimePaths.configuredRootfs(appContext.filesDir, rootfsOverride)

    override fun report(container: Container): PrefixReport =
        WinePrefixManager.inspect(container.id, File(container.prefixPath), container.arch.wineArch)

    override fun wineInstalled(): Boolean = WinePayload.isProvisioned(rootfs())

    override fun initialize(container: Container, onDone: PrefixCallback) {
        worker("prefix-init-${container.id}", onDone) {
            val launcher = RuntimeHolder.init(appContext, rootfsOverride)
            val result = WinePrefixManager.create(
                binaries = launcher.binaries,
                rootfsDir = rootfs(),
                container = container,
                xDisplay = RuntimePaths.X_DISPLAY,
                binds = sessionBinds(),
                log = SharedLog.buffer,
            )
            onDone.result(result.success, result.message)
        }
    }

    override fun repair(container: Container, onDone: PrefixCallback) {
        worker("prefix-repair-${container.id}", onDone) {
            val launcher = RuntimeHolder.init(appContext, rootfsOverride)
            val binds = sessionBinds()
            // 1. Ask a wineserver that still holds this prefix to let go
            //    (graceful, bounded - it also flushes the registry).
            WinePrefixManager.killServers(
                binaries = launcher.binaries,
                rootfsDir = rootfs(),
                container = container,
                binds = binds,
                log = SharedLog.buffer,
            )
            // 2. Host side: stale lock files + directory permissions.
            val cleaned = WinePrefixManager.repairFiles(File(container.prefixPath))
            cleaned.forEach { line -> SharedLog.info("prefix[${container.id}]: $line") }
            // 3. wineboot again: it tops up whatever the corruption took.
            val result = WinePrefixManager.create(
                binaries = launcher.binaries,
                rootfsDir = rootfs(),
                container = container,
                xDisplay = RuntimePaths.X_DISPLAY,
                binds = binds,
                log = SharedLog.buffer,
            )
            val prefix = if (cleaned.isEmpty()) "" else "Repair (${cleaned.joinToString()}). "
            onDone.result(result.success, prefix + result.message)
        }
    }

    override fun installWine(onDone: PrefixCallback) {
        worker("wine-install", onDone) {
            val launcher = RuntimeHolder.init(appContext, rootfsOverride)
            val filesDir = appContext.filesDir
            val result = WineProvisioner.provision(
                binaries = launcher.binaries,
                rootfsDir = rootfs(),
                filesDir = filesDir,
                downloadDir = RuntimePaths.downloadDir(filesDir),
                log = SharedLog.buffer,
            )
            onDone.result(result.success, result.message)
        }
    }

    /** DNS + /dev/shm for prefix operations - the same binds a session gets. */
    private fun sessionBinds(): List<Bind> {
        val filesDir = appContext.filesDir
        return WinePrefixManager.operationBinds(
            runCatching { RuntimePaths.ensureResolvConf(filesDir) }.getOrNull(),
            runCatching { RuntimePaths.ensureShmDir(filesDir) }.getOrNull(),
        )
    }

    /**
     * Guarded worker (crash-safety rule 1): if [block] throws before answering,
     * the callback gets the failure - the UI busy state must never leak.
     */
    private fun worker(name: String, onDone: PrefixCallback, block: () -> Unit) {
        Thread {
            runCatching { block() }.onFailure { error ->
                SharedLog.error("$name failed: ${error.javaClass.simpleName}: ${error.message}")
                runCatching { onDone.result(false, "${error.javaClass.simpleName}: ${error.message}") }
            }
        }.apply {
            this.name = name
            isDaemon = true
            start()
        }
    }
}
