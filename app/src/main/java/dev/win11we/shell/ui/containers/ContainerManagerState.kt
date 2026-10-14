package dev.win11we.shell.ui.containers

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import dev.win11we.shell.container.Container
import dev.win11we.shell.container.ContainerActionResult
import dev.win11we.shell.container.ContainerRuntime
import dev.win11we.shell.container.ContainerStore
import dev.win11we.shell.container.ContainerStatus
import dev.win11we.shell.container.DriveMapping
import dev.win11we.shell.runtime.PrefixReport

/**
 * The Prefix Manager seam (Phase 2B). The production implementation is
 * `runtime/WinePrefixOps` (Context + RuntimeHolder + guarded workers); tests
 * and previews pass null and every prefix action then says exactly that it is
 * not wired, instead of pretending. All heavy verbs run OFF the calling
 * thread and answer through [PrefixCallback] - the state only ever shows the
 * verdict (crash-safety rules 1 and 2).
 */
interface PrefixOps {
    /** Cheap file inspection of one prefix (runs on a worker via [ContainerManagerState.refreshPrefixes]). */
    fun report(container: Container): PrefixReport

    /** Whether the rootfs carries a Wine payload (WineProvisioner installs it). */
    fun wineInstalled(): Boolean

    /** `wineboot --init && wineserver -w` for THIS container's isolated prefix. */
    fun initialize(container: Container, onDone: PrefixCallback)

    /** Clear stale locks/permissions, then re-run wineboot to top the prefix up. */
    fun repair(container: Container, onDone: PrefixCallback)

    /** Install the pinned Wine debs (or apt fallback) into the rootfs. */
    fun installWine(onDone: PrefixCallback)
}

/** (success, user-visible message). Called on a worker thread. */
fun interface PrefixCallback {
    fun result(success: Boolean, message: String)
}

/** Per-window state of the Container Manager: selection, dialogs and the status line. */
@Stable
class ContainerManagerState(
    val store: ContainerStore,
    val runtime: ContainerRuntime,
    /** Starts the foreground service that owns the processes (R2). No-op in tests. */
    val onStartRuntime: () -> Unit = {},
    /** Stops it again once nothing is running. No-op in tests. */
    val onStopRuntime: () -> Unit = {},
    /** The Prefix Manager verbs; null until the shell wires the production one. */
    val prefixOps: PrefixOps? = null,
) {
    var expandedId by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var creating by mutableStateOf(false)
    var renaming: Container? by mutableStateOf<Container?>(null)
    var deleting: Container? by mutableStateOf<Container?>(null)
    var editingDrive: Pair<Container, DriveMapping?>? by mutableStateOf<Pair<Container, DriveMapping?>?>(null)

    // --- Prefix Manager state (Phase 2B) --------------------------------------
    /** The id a prefix operation (wineboot/repair/install) is running for; null = idle. */
    var prefixBusyId by mutableStateOf<String?>(null)
        private set

    /** Latest inspection per container id; refreshed on a worker, never in composition. */
    var prefixReports by mutableStateOf<Map<String, PrefixReport>>(emptyMap())
        private set

    /** Rootfs Wine payload state; null = not checked yet. */
    var winePayloadInstalled by mutableStateOf<Boolean?>(null)
        private set

    /** Last-request-wins guard for [refreshPrefixes]; declared before init uses it. */
    private val refreshGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    init {
        if (store.containers.isEmpty()) store.load()
        expandedId = store.defaultId
        refreshPrefixes()
    }

    val containers: List<Container> get() = store.containers

    fun reportFor(container: Container): PrefixReport? = prefixReports[container.id]

    /** True while ANY prefix operation runs (buttons disable); per-container via [prefixBusyId]. */
    val prefixBusy: Boolean get() = prefixBusyId != null

    fun isBusy(container: Container): Boolean = prefixBusyId == container.id

    /**
     * Re-inspects every prefix + the Wine payload on a guarded worker (disk
     * reads stay off the UI thread). Results land through a mutable snapshot
     * so Compose sees them. A generation counter makes concurrent refreshes
     * last-request-wins: a slow older worker can never overwrite the newer
     * list (the container set may have changed while it was reading).
     */
    fun refreshPrefixes() {
        val ops = prefixOps ?: return
        val targets = store.containers.toList()
        val generation = refreshGeneration.incrementAndGet()
        worker("prefix-refresh") {
            val reports = targets.associate { it.id to ops.report(it) }
            val installed = ops.wineInstalled()
            Snapshot.withMutableSnapshot {
                if (generation == refreshGeneration.get()) {
                    prefixReports = reports
                    winePayloadInstalled = installed
                }
            }
        }
    }

    /** Create/refresh THIS container's isolated prefix (wineboot --init + wineserver -w). */
    fun initializePrefix(container: Container) {
        runPrefixOp(container, "initialising prefix of") { ops, target, done -> ops.initialize(target, done) }
    }

    /** Clear stale locks + permissions, then re-run wineboot (the corruption fix). */
    fun repairPrefix(container: Container) {
        runPrefixOp(container, "repairing prefix of") { ops, target, done -> ops.repair(target, done) }
    }

    /** Install the Wine payload into the rootfs (pinned debs, apt fallback). */
    fun installWinePayload() {
        val ops = prefixOps
        if (ops == null) {
            message = "Prefix operations are not wired in this build."
            return
        }
        if (!claimBusy(container = null, label = "installing the Wine payload")) return
        // The install is long (apt): the foreground service keeps the process alive.
        runCatching { onStartRuntime() }
        ops.installWine { ok, text ->
            finishBusy(ok, text)
            refreshPrefixes()
            // The foreground service was started to keep the long install alive;
            // release it again when no guest is actually running.
            if (store.containers.none { runtime.statusOf(it) == ContainerStatus.RUNNING }) {
                runCatching { onStopRuntime() }
            }
        }
    }

    private fun runPrefixOp(
        container: Container,
        label: String,
        block: (PrefixOps, Container, PrefixCallback) -> Unit,
    ) {
        val ops = prefixOps
        if (ops == null) {
            message = "Prefix operations are not wired in this build."
            return
        }
        if (!claimBusy(container, label)) return
        val callback = PrefixCallback { ok, text ->
            finishBusy(ok, text)
            refreshPrefixes()
        }
        try {
            block(ops, container, callback)
        } catch (error: Throwable) {
            // Crash-safety rule 1: a worker failure becomes a message, never a crash.
            callback.result(false, "${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun claimBusy(container: Container?, label: String): Boolean {
        if (prefixBusyId != null) {
            message = "Another prefix operation is still running - wait for it to finish."
            return false
        }
        prefixBusyId = container?.id ?: INSTALL_ID
        message = "Prefix: $label " +
            (container?.let { "\"${it.name}\" " } ?: "") +
            "(output streams to Terminal ▸ log)…"
        return true
    }

    private fun finishBusy(ok: Boolean, text: String) {
        Snapshot.withMutableSnapshot {
            prefixBusyId = null
            message = if (ok) text else "Failed: $text"
        }
    }

    /** Guarded background worker (crash-safety rule 1: never a bare Thread without a catch-all). */
    private fun worker(name: String, block: () -> Unit) {
        Thread {
            runCatching { block() }
        }.apply {
            this.name = name
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /** One-line runtime verdict, straight from the launcher's own validation. */
    val runtimeStatus: String
        get() = if (runtime.ready) {
            "Runtime binaries ready. Start validates each container; a Wine prefix is built by " +
                "Initialize prefix (wineboot inside the rootfs) - every container keeps its OWN " +
                "isolated prefix, and Repair fixes a prefix a killed wineserver left dirty."
        } else {
            containers.firstOrNull()?.let { runtime.logExcerpt(it, 1) } ?: ""
        }

    fun toggle(container: Container) {
        expandedId = if (expandedId == container.id) null else container.id
    }

    fun create(name: String): Boolean {
        val created = store.create(name)
        message = if (created == null) {
            "Could not create \"$name\" - the name is empty or already used."
        } else {
            "Created \"${created.name}\" with its OWN isolated prefix directory. The prefix is " +
                "NOT built on first start - use Initialize prefix (runs wineboot inside the " +
                "rootfs; needs the Wine payload installed) and Start refuses until it is ready."
        }
        if (created != null) {
            expandedId = created.id
            refreshPrefixes()
        }
        return created != null
    }

    fun rename(container: Container, name: String): Boolean {
        val updated = store.rename(container.id, name)
        message = if (updated == null) "Rename failed - empty or duplicate name." else "Renamed to \"${updated.name}\"."
        return updated != null
    }

    fun delete(container: Container): Boolean {
        // Never delete files out from under a running guest: stop it first (the
        // fast SIGKILL path - proot's --kill-on-exit takes the guest tree down),
        // then remove the record and the prefix tree.
        if (runtime.statusOf(container) == ContainerStatus.RUNNING) {
            runCatching { runtime.stop(container) }
        }
        val removed = store.delete(container.id)
        message = if (removed) "Deleted \"${container.name}\" and its files." else "Delete failed."
        if (expandedId == container.id) expandedId = null
        if (removed) refreshPrefixes()
        return removed
    }

    fun makeDefault(container: Container) {
        store.setDefault(container.id)
        message = "\"${container.name}\" is now the default container."
    }

    /** Start/Stop/Restart are the R2 hooks; today they report what is missing. */
    fun run(action: (ContainerRuntime, Container) -> ContainerActionResult, container: Container) {
        val wasRunning = runningIds()
        val result = runCatching { action(runtime, container) }.getOrElse { error ->
            ContainerActionResult.fail("${error.javaClass.simpleName}: ${error.message}")
        }
        if (result.success) {
            store.update(container.id) { it.withStatus(runtime.statusOf(it)) }
        } else {
            store.update(container.id) { it.withStatus(ContainerStatus.ERROR, result.message) }
        }
        message = result.message.ifBlank { if (result.success) "Done." else "Failed." }

        // Start the foreground service only when something actually started, and stop it
        // only when nothing is left running. Starting and stopping it back-to-back (what
        // the old code did on every action) risks a foreground-service start violation.
        val nowRunning = runningIds()
        if (nowRunning.isNotEmpty() && wasRunning.isEmpty()) {
            runCatching { onStartRuntime() }.onFailure { message = "Could not start the runtime service: ${it.message}" }
        } else if (nowRunning.isEmpty() && wasRunning.isNotEmpty()) {
            runCatching { onStopRuntime() }
        }
    }

    private fun runningIds(): Set<String> =
        store.containers.filter { runtime.statusOf(it) == ContainerStatus.RUNNING }.map { it.id }.toSet()

    /** The service's own log tail, shown in the container card (R4 expands this). */
    fun logExcerpt(container: Container, lines: Int = 12): String = runtime.logExcerpt(container, lines)

    fun start(container: Container) = run(ContainerRuntime::start, container)
    fun stop(container: Container) = run(ContainerRuntime::stop, container)
    fun restart(container: Container) = run(ContainerRuntime::restart, container)

    fun addDrive(container: Container, letter: Char, hostPath: String): Boolean {
        if (!letter.isLetter() || hostPath.isBlank()) {
            message = "A drive needs a letter and a host path."
            return false
        }
        val mapping = DriveMapping(letter.uppercaseChar(), hostPath.trim())
        val drives = container.drives.filterNot { it.letter.uppercaseChar() == mapping.letter } + mapping
        return commit(container, container.copy(drives = drives), "Drive ${mapping.guestRoot} -> ${mapping.hostPath}")
    }

    fun removeDrive(container: Container, letter: Char): Boolean {
        val drives = container.drives.filterNot { it.letter.uppercaseChar() == letter.uppercaseChar() }
        return commit(container, container.copy(drives = drives), "Removed drive $letter:")
    }

    fun save(container: Container, updated: Container, what: String): Boolean = commit(container, updated, what)

    private fun commit(container: Container, updated: Container, what: String): Boolean {
        val saved = store.update(container.id) { updated }
        message = if (saved == null) "Save failed." else what
        return saved != null
    }

    private companion object {
        /** [prefixBusyId] sentinel while the rootfs-wide Wine install runs (no single container owns it). */
        const val INSTALL_ID = "__wine_payload_install__"
    }
}
