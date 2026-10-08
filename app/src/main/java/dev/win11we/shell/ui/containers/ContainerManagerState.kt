package dev.win11we.shell.ui.containers

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.win11we.shell.container.Container
import dev.win11we.shell.container.ContainerActionResult
import dev.win11we.shell.container.ContainerRuntime
import dev.win11we.shell.container.ContainerStore
import dev.win11we.shell.container.ContainerStatus
import dev.win11we.shell.container.DriveMapping

/** Per-window state of the Container Manager: selection, dialogs and the status line. */
@Stable
class ContainerManagerState(
    val store: ContainerStore,
    val runtime: ContainerRuntime,
    /** Starts the foreground service that owns the processes (R2). No-op in tests. */
    val onStartRuntime: () -> Unit = {},
    /** Stops it again once nothing is running. No-op in tests. */
    val onStopRuntime: () -> Unit = {},
) {
    var expandedId by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var creating by mutableStateOf(false)
    var renaming: Container? by mutableStateOf<Container?>(null)
    var deleting: Container? by mutableStateOf<Container?>(null)
    var editingDrive: Pair<Container, DriveMapping?>? by mutableStateOf<Pair<Container, DriveMapping?>?>(null)

    init {
        if (store.containers.isEmpty()) store.load()
        expandedId = store.defaultId
    }

    val containers: List<Container> get() = store.containers

    /** One-line runtime verdict, straight from the launcher's own validation. */
    val runtimeStatus: String
        get() = if (runtime.ready) {
            "Runtime ready - Start launches proot -> box64 -> wine as a foreground service."
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
            "Created \"${created.name}\". Its Wine prefix is built on first start (R2/R3)."
        }
        if (created != null) expandedId = created.id
        return created != null
    }

    fun rename(container: Container, name: String): Boolean {
        val updated = store.rename(container.id, name)
        message = if (updated == null) "Rename failed - empty or duplicate name." else "Renamed to \"${updated.name}\"."
        return updated != null
    }

    fun delete(container: Container): Boolean {
        val removed = store.delete(container.id)
        message = if (removed) "Deleted \"${container.name}\" and its files." else "Delete failed."
        if (expandedId == container.id) expandedId = null
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
}
