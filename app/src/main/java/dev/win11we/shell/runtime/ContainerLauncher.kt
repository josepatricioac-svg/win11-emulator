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
    /**
     * Guest command, e.g. ["wine64", "cmd"] - symbolic names are fine: the launcher
     * resolves a bare `wine64` to the provisioned payload's real loader path and adds
     * box64 only when that payload is x86_64 (see [ContainerLauncher.needsBox64]).
     */
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
    private val requests = HashMap<String, RuntimeRequest>()

    fun statusOf(id: String): ContainerStatus = statuses[id] ?: ContainerStatus.STOPPED

    fun isRunning(id: String): Boolean = statusOf(id) == ContainerStatus.RUNNING

    fun commandOf(id: String): List<String>? = commands[id]

    /** The request a session was started with; kept after exit for diagnostics. */
    fun requestOf(id: String): RuntimeRequest? = requests[id]

    fun runningIds(): Set<String> = handles.keys.toSet()

    /**
     * The RUNNING Wine sessions, for the shutdown path's graceful `wineserver -k`
     * sweep. A Wine session is recognised STRUCTURALLY - by the fixed prefix bind
     * target (`/root/.wine`) - not by the box64 rule: a native arm64 Wine payload
     * runs WITHOUT box64 and still has a wineserver whose registry must be flushed.
     * The Firefox ESR session binds its profile at `/root/.mozilla` and is
     * deliberately not included - it has no wineserver.
     */
    fun runningWineRequests(): List<RuntimeRequest> =
        handles.keys.mapNotNull { requests[it] }.filter { it.guestPrefix == ProotCommand.GUEST_PREFIX }

    /** Validates everything *before* spawning, so failures name the missing piece. */
    fun validate(request: RuntimeRequest): LaunchResult {
        if (request.guestCommand.isEmpty()) {
            return LaunchResult.fail(LaunchFailure.NO_COMMAND, "No guest command given.")
        }
        if (isRunning(request.container.id)) {
            return LaunchResult.fail(LaunchFailure.ALREADY_RUNNING, "\"${request.container.name}\" is already running.")
        }
        val prepare = binaries.prepare(requiredFor(request.guestCommand, request.rootfsDir))
        if (prepare.status != PrepareStatus.READY) {
            return LaunchResult.fail(LaunchFailure.BINARIES_NOT_READY, prepare.message)
        }
        if (!request.rootfsDir.isDirectory) {
            return LaunchResult.fail(
                LaunchFailure.ROOTFS_MISSING,
                "Rootfs not found at ${request.rootfsDir.absolutePath} - download it in R3.",
            )
        }
        // The Wine-prefix checks apply to WINE requests ONLY. The structural
        // signal is the bind target: a Wine prefix always lands at the fixed
        // guest path /root/.wine, while a session that brings its own state dir
        // (Firefox ESR binds its profile at /root/.mozilla) has no prefix and
        // must never be judged by wineboot's markers. This gate was missing and
        // sent the desktop browser to a bogus PREFIX_MISSING refusal on device
        // ("desktop session failed to start ... wine prefix").
        val isWinePrefix = request.guestPrefix == ProotCommand.GUEST_PREFIX
        val prefix = File(request.container.prefixPath)
        if (isWinePrefix && !prefix.isDirectory) {
            // Name the whole chain, because "prefix missing" reads like a bug the
            // user can fix by retrying. It is not: a prefix is populated by running
            // wineboot inside the rootfs, wineboot needs a Wine payload, and no
            // payload is provisioned yet (Provision installs the browser's GTK3
            // stack, not Wine). That is Goal 2 step 2 / R4 - see docs/NATIVE_RUNTIME.md.
            val wine = WinePayload.launcherIn(request.rootfsDir)
            val cause = if (wine == null) {
                "the rootfs has no Wine payload (${WinePayload.CANDIDATES.first()} absent), " +
                    "so wineboot could not have created one"
            } else {
                "Wine exists at ${wine.absolutePath} but wineboot has never been run for this container"
            }
            return LaunchResult.fail(
                LaunchFailure.PREFIX_MISSING,
                "Wine prefix not found at ${prefix.absolutePath}: $cause. A prefix is built by " +
                    "wineboot inside the rootfs - it is NOT created on first launch. Container " +
                    "Manager ▸ Initialize prefix runs wineboot for exactly this container (isolated " +
                    "prefix, own WINEPREFIX/WINEARCH) once a Wine payload is installed there " +
                    "(Install Wine payload). Diagnostics ▸ Run self-test shows exactly " +
                    "where the chain stops (box64 --version, wine64 --version, wine64 cmd).",
            )
        }
        if (isWinePrefix && !WinePayload.prefixLooksInitialised(prefix)) {
            // An empty directory is not a prefix: wine would treat it as a fresh
            // one and try to initialise it mid-launch, half-way through a ptrace
            // session. Refuse first, with the reason.
            return LaunchResult.fail(
                LaunchFailure.PREFIX_MISSING,
                "Wine prefix at ${prefix.absolutePath} exists but was never initialised " +
                    "(no system.reg/user.reg/drive_c) - wineboot has not run for it. Use " +
                    "Container Manager ▸ Initialize prefix (it runs wineboot --init and waits " +
                    "for wineserver to flush the registry), or Repair if a previous run died.",
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
        requests[request.container.id] = request
        statuses[request.container.id] = ContainerStatus.RUNNING
        if (streamThreads) stream(request.container.id, handle)
        return LaunchResult.ok("${request.container.name} started (pid stream attached)")
    }

    /** `proot -r … -b <prefix>:<guestPrefix> … [box64] <resolved guest command>` */
    fun buildCommand(request: RuntimeRequest): List<String> {
        val proot = File(binaries.nativeLibraryDir, BinaryRole.PROOT.fileName)
        // Bare `wine64` is not on Ubuntu's PATH (no alternatives entry - see
        // WinePayload.guestLauncherPath); resolve it to the provisioned payload's
        // real loader path. Without a payload the command stays unchanged and
        // validate() has already refused with the missing link named.
        val resolved = WinePayload.resolveGuestCommand(request.guestCommand, request.rootfsDir)
        val guest = if (needsBox64(resolved, request.rootfsDir)) {
            listOf(File(binaries.nativeLibraryDir, BinaryRole.BOX64.fileName).absolutePath) + resolved
        } else {
            resolved
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

    /**
     * Whether box64 goes in front of this guest command.
     *
     * PAYLOAD-DRIVEN, not name-driven - the old name rule ("anything starting with
     * `wine` gets box64") was wrong for the pinned payload in both directions:
     * Ubuntu's Wine 9.0 loader is a NATIVE arm64 ELF, and box64 (an x86_64->arm64
     * translator) cannot exec it. So for `wine*` commands the answer comes from the
     * ELF header of the loader actually provisioned in [rootfsDir]
     * ([WinePayload.launcherArch]): x86_64 -> box64 (the decision-3b x86_64
     * payload story, engaged automatically the day such a payload is pinned);
     * arm64 -> run natively ("box64 + Wine only on demand"); unknown/absent ->
     * run natively rather than prepend a translator on a guess - validate() names
     * a missing payload before spawn, so the guess is never load-bearing.
     *
     * A bare `.exe` token keeps the legacy rule. It is documented honestly: no
     * Linux kernel and no box64 can exec a Windows PE directly - real Windows
     * apps always travel as `wine <app.exe>`, whose first token is the loader.
     */
    fun needsBox64(guestCommand: List<String>, rootfsDir: File? = null): Boolean {
        val first = guestCommand.firstOrNull()?.substringAfterLast('/')?.lowercase() ?: return false
        return when {
            first == "box64" -> false // caller already asked for it
            first.startsWith("wine") -> WinePayload.launcherArch(rootfsDir) == PayloadArch.X86_64
            first.endsWith(".exe") -> true
            else -> false
        }
    }

    /**
     * The binaries THIS guest needs: box64 only carries x86_64 payloads, so
     * neither a native arm64 guest (Firefox ESR) nor the pinned native arm64
     * Wine loader may be refused for box64's absence - only an x86_64 payload is.
     */
    fun requiredFor(guestCommand: List<String>, rootfsDir: File? = null): Set<BinaryRole> =
        BinaryRole.entries.filter { it.required }
            .filter { it != BinaryRole.BOX64 || needsBox64(guestCommand, rootfsDir) }
            .toSet()

    fun stop(id: String, reason: String = "stopped by user"): LaunchResult {
        val handle = handles.remove(id) ?: return LaunchResult.ok("Not running.")
        commands.remove(id)
        // NOTE: this is the FAST path - destroy() SIGKILLs proot, whose
        // --kill-on-exit takes the whole guest tree (incl. wineserver) down.
        // A wineserver killed like that can leave a stale `.update-lock` and
        // lose the last registry writes; WinePrefixManager.repair clears the
        // lock, and the graceful `wineserver -k` runs where waiting is allowed:
        // SessionLifecycle.shutdown (app exit) - see its KDoc for the order.
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
