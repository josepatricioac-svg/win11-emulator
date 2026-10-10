package dev.win11we.shell.runtime

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import dev.win11we.shell.container.Container
import dev.win11we.shell.container.ContainerStatus
import dev.win11we.shell.container.WineEnvironment
import dev.win11we.shell.ui.terminal.LineKind
import dev.win11we.shell.ui.terminal.LogBuffer
import dev.win11we.shell.ui.terminal.TerminalLine
import java.io.File

/** Everything needed to launch one container; paths are host paths. */
data class RuntimeRequest(
    val container: Container,
    val rootfsDir: File,
    val xDisplay: String,
    val audioSocket: String? = null,
    val locale: String = "en_US.UTF-8",
    /** Guest command, e.g. ["wine64", "cmd"] - the launcher adds box64 when needed. */
    val guestCommand: List<String>,
    val binds: List<Bind> = emptyList(),
    /** Bind target of the container's state directory (`/root/.wine` for Wine). */
    val guestPrefix: String = ProotCommand.GUEST_PREFIX,
    /**
     * Fully explicit environment for non-Wine guests (e.g. [FirefoxEnvironment]);
     * null builds the Wine environment from the container settings.
     */
    val environment: Map<String, String>? = null,
)

enum class LaunchFailure {
    BINARIES_NOT_READY,
    ROOTFS_MISSING,
    PREFIX_MISSING,
    NO_COMMAND,
    ALREADY_RUNNING,
    SPAWN_FAILED,
}

data class LaunchResult(val success: Boolean, val failure: LaunchFailure?, val message: String) {
    companion object {
        fun ok(message: String) = LaunchResult(true, null, message)
        fun fail(failure: LaunchFailure, message: String) = LaunchResult(false, failure, message)
    }
}

/**
 * R2's launch logic: validate, build the exact `proot -> box64 -> wine` command line and
 * environment, start it, stream its output into [log], and track status per container.
 *
 * No Android types in here, so the whole flow is unit-tested with a fake launcher.
 */
@Stable
class ContainerLauncher(
    val binaries: NativeBinaries,
    val log: LogBuffer,
    private val launcher: ProcessLauncher = RealProcessLauncher(),
    private val streamThreads: Boolean = true,
) {
    /** Live status per container id; the UI and Task Manager read this. */
    val statuses = mutableStateMapOf<String, ContainerStatus>()
    private val handles = HashMap<String, ProcessHandle>()
    private val commands = HashMap<String, List<String>>()

    fun statusOf(id: String): ContainerStatus = statuses[id] ?: ContainerStatus.STOPPED

    fun isRunning(id: String): Boolean = statusOf(id) == ContainerStatus.RUNNING

    fun commandOf(id: String): List<String>? = commands[id]

    fun runningIds(): Set<String> = handles.keys.toSet()

    /** Validates everything *before* spawning, so failures name the missing piece. */
    fun validate(request: RuntimeRequest): LaunchResult {
        if (request.guestCommand.isEmpty()) {
            return LaunchResult.fail(LaunchFailure.NO_COMMAND, "No guest command given.")
        }
        if (isRunning(request.container.id)) {
            return LaunchResult.fail(LaunchFailure.ALREADY_RUNNING, "\"${request.container.name}\" is already running.")
        }
        val prepare = binaries.prepare(requiredFor(request.guestCommand))
        if (prepare.status != PrepareStatus.READY) {
            return LaunchResult.fail(LaunchFailure.BINARIES_NOT_READY, prepare.message)
        }
        if (!request.rootfsDir.isDirectory) {
            return LaunchResult.fail(
                LaunchFailure.ROOTFS_MISSING,
                "Rootfs not found at ${request.rootfsDir.absolutePath} - download it in R3.",
            )
        }
        val prefix = File(request.container.prefixPath)
        if (!prefix.isDirectory) {
            return LaunchResult.fail(
                LaunchFailure.PREFIX_MISSING,
                "Wine prefix not found at ${prefix.absolutePath} - it is created on first launch (R3/R4).",
            )
        }
        return LaunchResult.ok("Validated")
    }

    fun start(request: RuntimeRequest): LaunchResult {
        val validation = validate(request)
        if (!validation.success) {
            log.add(TerminalLine(LineKind.ERROR, "start refused [${validation.failure}]: ${validation.message}"))
            statuses[request.container.id] = ContainerStatus.ERROR
            return validation
        }

        val command = buildCommand(request)
        val environment = buildEnvironment(request)
        statuses[request.container.id] = ContainerStatus.STARTING
        log.add(TerminalLine(LineKind.SYSTEM, "starting ${request.container.name}: ${command.joinToString(" ")}"))

        val attempt = launcher.start(command, environment, workingDir = null)
        val handle = attempt.handle
        if (handle == null) {
            statuses[request.container.id] = ContainerStatus.ERROR
            val message = attempt.failure ?: "Could not start the process"
            log.add(TerminalLine(LineKind.ERROR, "spawn failed: $message"))
            return LaunchResult.fail(LaunchFailure.SPAWN_FAILED, message)
        }

        handles[request.container.id] = handle
        commands[request.container.id] = command
        statuses[request.container.id] = ContainerStatus.RUNNING
        if (streamThreads) stream(request.container.id, handle)
        return LaunchResult.ok("${request.container.name} started (pid stream attached)")
    }

    /** `proot --rootfs … --bind <prefix>:<guestPrefix> … -- [box64] <guest command>` */
    fun buildCommand(request: RuntimeRequest): List<String> {
        val proot = File(binaries.nativeLibraryDir, BinaryRole.PROOT.fileName)
        val guest = if (needsBox64(request.guestCommand)) {
            listOf(File(binaries.nativeLibraryDir, BinaryRole.BOX64.fileName).absolutePath) + request.guestCommand
        } else {
            request.guestCommand
        }
        return ProotCommand.build(
            prootBinary = proot,
            rootfsDir = request.rootfsDir,
            prefixDir = File(request.container.prefixPath),
            binds = request.binds + request.container.drives.map { Bind(it.hostPath, "/mnt/${it.letter.lowercaseChar()}") },
            guestCommand = guest,
            guestPrefix = request.guestPrefix,
        )
    }

    /** The fully explicit environment, with this session's proot loader paths. */
    fun buildEnvironment(request: RuntimeRequest): Map<String, String> {
        val env = (
            request.environment ?: WineEnvironment.build(
                container = request.container,
                xDisplay = request.xDisplay,
                prootRootfs = request.rootfsDir.absolutePath,
                nativeLibraryDir = binaries.nativeLibraryDir.absolutePath,
                locale = request.locale,
                audioSocket = request.audioSocket,
            )
            ).toMutableMap()
        env.putAll(binaries.loaderPaths())
        // proot itself links the vendored libtalloc.so: the Android linker only
        // finds it through LD_LIBRARY_PATH, so the host dir goes first; guest
        // library paths stay reachable behind it (host dirs do not exist inside
        // the jail and vice versa, so neither side shadows the other).
        val hostDir = binaries.nativeLibraryDir.absolutePath
        env["LD_LIBRARY_PATH"] = (listOf(hostDir) + (env["LD_LIBRARY_PATH"]?.split(":") ?: emptyList()))
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(":")
        return env.toSortedMap()
    }

    /** Windows binaries are x86/x64 PE, so they always go through box64. */
    fun needsBox64(guestCommand: List<String>): Boolean {
        val first = guestCommand.firstOrNull()?.substringAfterLast('/')?.lowercase() ?: return false
        return when {
            first == "box64" -> false // caller already asked for it
            first.startsWith("wine") -> true
            first.endsWith(".exe") -> true
            else -> false
        }
    }

    /**
     * The binaries THIS guest needs: box64 only carries x86/x64 payloads, so a
     * native arm64 guest (Firefox ESR) must not be refused for box64's absence.
     */
    fun requiredFor(guestCommand: List<String>): Set<BinaryRole> =
        BinaryRole.entries.filter { it.required }
            .filter { it != BinaryRole.BOX64 || needsBox64(guestCommand) }
            .toSet()

    fun stop(id: String, reason: String = "stopped by user"): LaunchResult {
        val handle = handles.remove(id) ?: return LaunchResult.ok("Not running.")
        commands.remove(id)
        handle.destroy()
        statuses[id] = ContainerStatus.STOPPED
        log.add(TerminalLine(LineKind.SYSTEM, "$id $reason"))
        return LaunchResult.ok(reason)
    }

    fun restart(request: RuntimeRequest): LaunchResult {
        stop(request.container.id, reason = "restarting")
        return start(request)
    }

    fun stopAll(reason: String = "service stopped") {
        handles.keys.toList().forEach { stop(it, reason) }
    }

    /** Reads both streams until the process exits, then records the exit code. */
    private fun stream(id: String, handle: ProcessHandle) {
        val pump = { stream: java.io.InputStream, kind: LineKind ->
            Thread {
                runCatching {
                    stream.bufferedReader().forEachLine { line ->
                        if (line.isNotBlank()) log.add(TerminalLine(kind, line))
                    }
                }
            }.apply { isDaemon = true; start() }
        }
        pump(handle.stdout(), LineKind.OUTPUT)
        pump(handle.stderr(), LineKind.ERROR)
        Thread {
            onProcessExited(id, handle.waitFor(EXIT_TIMEOUT_MILLIS))
        }.apply { isDaemon = true; start() }
    }

    /** Recorded when the guest process ends; public so tests can drive it directly. */
    fun onProcessExited(id: String, exitCode: Int?) {
        handles.remove(id)
        commands.remove(id)
        statuses[id] = if (exitCode == 0) ContainerStatus.STOPPED else ContainerStatus.ERROR
        log.add(
            TerminalLine(
                if (exitCode == 0) LineKind.SYSTEM else LineKind.ERROR,
                "$id exited with " + if (exitCode == null) "no code (timed out)" else "code $exitCode",
            ),
        )
    }

    companion object {
        const val EXIT_TIMEOUT_MILLIS = 12 * 60 * 60 * 1000L
    }
}
