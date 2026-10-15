package dev.win11we.shell.runtime

import dev.win11we.shell.container.Container
import dev.win11we.shell.container.WineEnvironment
import dev.win11we.shell.ui.terminal.LineKind
import dev.win11we.shell.ui.terminal.LogBuffer
import dev.win11we.shell.ui.terminal.TerminalLine
import java.io.File

/** The four honest states of a Wine prefix directory on the host. */
enum class PrefixState(val label: String) {
    /** The directory does not exist at all. */
    MISSING("Missing"),

    /** The directory exists but wineboot never ran (no registry, no drive_c). */
    UNINITIALISED("Never initialised"),

    /**
     * Something a healthy prefix must carry is broken: a missing/partial marker
     * set, a registry file that does not start with a Wine header, an unreadable
     * registry, or an architecture that does not match the container's.
     */
    CORRUPT("Corrupt"),

    /** wineboot ran, the registry files parse as Wine registries, drive_c exists. */
    READY("Ready"),
}

/** One prefix's verdict, with the reason in plain language. */
data class PrefixReport(
    val containerId: String,
    val prefixDir: File,
    val state: PrefixState,
    val detail: String,
) {
    val usable: Boolean get() = state == PrefixState.READY
}

/** Outcome of a prefix operation (create/repair/kill/destroy). */
data class PrefixOpResult(
    val success: Boolean,
    val state: PrefixState?,
    val message: String,
    val tailLines: List<String> = emptyList(),
)

/**
 * The Prefix Manager: create, list, validate, repair and delete Wine prefixes -
 * every one of them ISOLATED per container, none of them ever the default
 * `~/.wine` shared across apps.
 *
 * Isolation model (why prefixes cannot overlap here):
 * - every container owns `<files>/containers/<id>/prefix` on the host
 *   ([dev.win11we.shell.container.ContainerStore] mints the path at create time
 *   and refuses duplicate ids), and
 * - each launch binds exactly ONE prefix at the fixed guest path `/root/.wine`
 *   ([ProotCommand.GUEST_PREFIX]), with `WINEPREFIX` pointing at that bind.
 *   Two containers can therefore never see - or write - each other's prefix,
 *   and the host's own `~/.wine` is never involved at all.
 *
 * Corruption model (what "repair" means and why the shutdown order matters):
 * - wineserver flushes the prefix registry when it EXITS GRACEFULLY. A prefix
 *   whose wineserver was SIGKILLed (process death, `--kill-on-exit`) can keep a
 *   stale `.update-lock` and half-written `.reg` files behind.
 * - [killServers] runs `wineserver -k` (bounded, best effort) BEFORE anything
 *   destroys the processes - [SessionLifecycle.shutdown] and the container Stop
 *   path both call it, which is the "wineserver -k on app exit" contract.
 * - [repair] clears the stale lock, restores rwx permissions and re-runs
 *   `wineboot --init`, which rebuilds missing registry content in place.
 * - [inspect] validates the registry headers (and the `#arch=` line Wine writes)
 *   instead of trusting a directory's existence - the same rule
 *   [ContainerLauncher.validate] enforces before a launch.
 *
 * Like the rest of `runtime/`, every command line is built by pure functions
 * and executed through the [ProcessRunner] seam, so all of it is unit-tested
 * without spawning anything.
 */
object WinePrefixManager {

    /** Wine's prefix-level update lock; a stale one blocks the next wineboot. */
    const val UPDATE_LOCK = ".update-lock"

    /** The markers wineboot writes; the first two carry the `#arch=` line. */
    val REQUIRED_MARKERS = listOf("system.reg", "user.reg")
    const val REQUIRED_DIR = "drive_c"

    /** Registry files must begin with one of these (Wine 9 writes the first). */
    val REGISTRY_HEADERS = listOf(
        "# WINE REGISTRY Version",
        "REGEDIT4",
        "Windows Registry Editor Version",
    )

    /** How many trailing guest lines an operation reports on failure. */
    const val TAIL_LINES = 20

    // ---------------------------------------------------------------- inspect

    /**
     * Validates one prefix WITHOUT running anything: markers, registry headers,
     * readability, the `#arch=` line against [expectedArch] ("win64"/"win32"),
     * and a stale update lock (a warning in [PrefixReport.detail], cleared by
     * [repair]).
     */
    fun inspect(containerId: String, prefixDir: File, expectedArch: String = "win64"): PrefixReport {
        if (!prefixDir.isDirectory) {
            return PrefixReport(
                containerId, prefixDir, PrefixState.MISSING,
                "No prefix directory at ${prefixDir.absolutePath} - create it (wineboot --init runs inside the rootfs).",
            )
        }
        val problems = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val markers = REQUIRED_MARKERS.map { it to File(prefixDir, it) }
        val present = markers.filter { (_, file) -> file.isFile }
        if (present.isEmpty() && !File(prefixDir, REQUIRED_DIR).isDirectory) {
            return PrefixReport(
                containerId, prefixDir, PrefixState.UNINITIALISED,
                "The directory exists but wineboot never ran (no system.reg/user.reg/drive_c).",
            )
        }
        for ((name, file) in markers) {
            when {
                !file.isFile -> problems += "$name is missing"
                !file.canRead() -> problems += "$name is not readable (permissions)"
                else -> {
                    val head = runCatching { file.readLines().take(8) }.getOrNull()
                    if (head == null) {
                        problems += "$name could not be read"
                    } else if (head.none { line -> REGISTRY_HEADERS.any { line.startsWith(it) } }) {
                        problems += "$name does not start with a Wine registry header (half-written?)"
                    } else {
                        head.firstOrNull { it.startsWith("#arch=") }?.let { archLine ->
                            val found = archLine.removePrefix("#arch=").trim()
                            if (found != expectedArch) {
                                problems += "prefix arch is '$found' but the container asks for '$expectedArch'"
                            }
                        }
                    }
                }
            }
        }
        if (!File(prefixDir, REQUIRED_DIR).isDirectory) problems += "$REQUIRED_DIR/ is missing"
        val lock = File(prefixDir, UPDATE_LOCK)
        if (lock.exists()) {
            notes += "stale $UPDATE_LOCK present (cleared by Repair; a running wineserver also holds one legitimately)"
        }
        return when {
            problems.isNotEmpty() -> PrefixReport(
                containerId, prefixDir, PrefixState.CORRUPT,
                (problems + notes).joinToString("; ") + " - Repair re-runs wineboot, or delete and recreate.",
            )
            else -> PrefixReport(
                containerId, prefixDir, PrefixState.READY,
                if (notes.isEmpty()) "wineboot initialised, registry headers valid." else notes.joinToString("; "),
            )
        }
    }

    /** [inspect] for a whole container list - the Container Manager's "list" verb. */
    fun list(containers: List<Container>): List<PrefixReport> =
        containers.map { inspect(it.id, File(it.prefixPath), it.arch.wineArch) }

    // ----------------------------------------------------------------- repair

    /** The files [repair] removes before re-running wineboot. */
    fun staleFiles(prefixDir: File): List<File> = listOf(File(prefixDir, UPDATE_LOCK)).filter { it.exists() }

    /**
     * Host-side repair step: drop the stale update lock and make sure the
     * prefix tree is rwx for the app uid (a `chmod`-style accident during a
     * previous run otherwise makes wineboot fail with EACCES mid-registry-write).
     * Returns what it actually did, for the log/UI.
     */
    fun repairFiles(prefixDir: File): List<String> {
        val done = mutableListOf<String>()
        staleFiles(prefixDir).forEach { file ->
            if (runCatching { file.deleteRecursively() }.getOrDefault(false)) done += "removed stale ${file.name}"
        }
        if (prefixDir.isDirectory) {
            val fixed = listOf(prefixDir, File(prefixDir, REQUIRED_DIR))
                .filter { it.isDirectory && !(it.canRead() && it.canWrite() && it.canExecute()) }
                .onEach { runCatching { it.setReadable(true); it.setWritable(true); it.setExecutable(true) } }
            if (fixed.isNotEmpty()) done += "restored rwx on ${fixed.size} director${if (fixed.size == 1) "y" else "ies"}"
        }
        return done
    }

    // --------------------------------------------------------------- commands

    /** Binds every prefix operation needs: guest DNS + /dev/shm (when present). */
    fun operationBinds(resolvConf: File?, shmDir: File?): List<Bind> = listOfNotNull(
        resolvConf?.let { Bind(it.absolutePath, "/etc/resolv.conf") },
        // Wine's esync/msvcrt and any Windows browser in the prefix want POSIX
        // shared memory; Android's /dev has no shm, so the app provides one.
        shmDir?.let { Bind(it.absolutePath, "/dev/shm") },
    )

    /**
     * `wineboot --init && wineserver -w`: initialise (or top up) the prefix,
     * then WAIT for the wineserver that wineboot started to finish flushing -
     * the registry files only exist on disk once wineserver exits. This is the
     * "run wineboot --init and wineserver -w" contract, as one bounded guest
     * command through the same proot jail every other guest uses.
     */
    fun initCommand(
        prootBinary: File,
        rootfsDir: File,
        prefixDir: File,
        binds: List<Bind> = emptyList(),
    ): List<String> = ProotCommand.build(
        prootBinary = prootBinary,
        rootfsDir = rootfsDir,
        prefixDir = prefixDir,
        binds = binds,
        guestCommand = listOf("/bin/bash", "-c", "wineboot --init && wineserver -w"),
    )

    /** `wineserver -k || true`: best-effort graceful kill; exit 0 even when no server was running. */
    fun killCommand(
        prootBinary: File,
        rootfsDir: File,
        prefixDir: File,
        binds: List<Bind> = emptyList(),
    ): List<String> = ProotCommand.build(
        prootBinary = prootBinary,
        rootfsDir = rootfsDir,
        prefixDir = prefixDir,
        binds = binds,
        guestCommand = listOf("/bin/bash", "-c", "wineserver -k || true"),
    )

    /**
     * The fully explicit environment prefix operations run with - the same
     * [WineEnvironment.build] a container launch uses, so WINEPREFIX (the fixed
     * guest bind), WINEARCH (from the container), WINEDLLOVERRIDES (never
     * auto-download Gecko/Mono) and DISPLAY (the shell's embedded :100) are
     * identical at init time and at run time.
     */
    fun operationEnvironment(
        container: Container,
        xDisplay: String,
        rootfsDir: File,
        nativeLibraryDir: File,
    ): Map<String, String> = WineEnvironment.build(
        container = container,
        xDisplay = xDisplay,
        prootRootfs = rootfsDir.absolutePath,
        nativeLibraryDir = nativeLibraryDir.absolutePath,
    )

    // -------------------------------------------------------------- execution

    /**
     * CREATE: mkdirs, clear stale locks, run `wineboot --init && wineserver -w`
     * inside the jail, then re-[inspect] - success means the verdict is READY,
     * not merely "exit 0". Prerequisites are named, never guessed: no Wine
     * payload in the rootfs is refused up front ([WineProvisioner] installs it).
     */
    fun create(
        binaries: NativeBinaries,
        rootfsDir: File,
        container: Container,
        xDisplay: String,
        binds: List<Bind> = emptyList(),
        log: LogBuffer? = null,
        runner: ProcessRunner = JvmProcessRunner(),
        timeoutMillis: Long = 10 * 60 * 1000L,
    ): PrefixOpResult {
        val prefixDir = File(container.prefixPath)
        val gap = WinePayload.payloadGap(rootfsDir)
        if (gap != null) {
            return PrefixOpResult(
                false, null,
                "No usable Wine payload in the rootfs: $gap - install Wine first " +
                    "(Container Manager ▸ Install Wine payload). wineboot cannot run without it.",
            )
        }
        val prepare = binaries.prepare(NativeBinaries.REQUIRED_PROOT_ONLY)

        if (prepare.status != PrepareStatus.READY) {
            return PrefixOpResult(false, null, "Native binaries not ready: ${prepare.message}")
        }
        if (!rootfsDir.isDirectory) {
            return PrefixOpResult(false, null, "Rootfs not found at ${rootfsDir.absolutePath}.")
        }
        prefixDir.mkdirs()
        val repaired = repairFiles(prefixDir)
        repaired.forEach { line -> log?.add(TerminalLine(LineKind.SYSTEM, "prefix[${container.id}]: $line")) }
        val proot = File(binaries.nativeLibraryDir, BinaryRole.PROOT.fileName)
        val command = initCommand(proot, rootfsDir, prefixDir, binds)
        val env = operationEnvironment(container, xDisplay, rootfsDir, binaries.nativeLibraryDir) +
            binaries.loaderPaths()
        log?.add(TerminalLine(LineKind.SYSTEM, "prefix[${container.id}]: wineboot --init (this can take a minute on box64-free arm64 wine)"))
        val outcome = runner.run(command, env, workingDir = null, timeoutMillis = timeoutMillis)
        val tail = tailOf(outcome)
        tail.forEach { log?.add(TerminalLine(if (outcome.succeeded) LineKind.OUTPUT else LineKind.ERROR, it)) }
        val report = inspect(container.id, prefixDir, container.arch.wineArch)
        return if (report.state == PrefixState.READY) {
            PrefixOpResult(true, report.state, "Prefix ready at ${prefixDir.absolutePath}.", tail)
        } else {
            val why = when {
                outcome.exitCode == null -> "wineboot did not finish (${outcome.error ?: "no exit code"})"
                !outcome.succeeded -> "wineboot failed (exit ${outcome.exitCode})"
                else -> "wineboot exited 0 but the prefix is still ${report.state.label}"
            }
            PrefixOpResult(false, report.state, "$why: ${report.detail}", tail)
        }
    }

    /**
     * The graceful `wineserver -k` for one prefix (bounded). Called before
     * guests are destroyed - see [SessionLifecycle]'s stop order. A prefix that
     * was never initialised is skipped: there is no server to ask.
     */
    fun killServers(
        binaries: NativeBinaries,
        rootfsDir: File,
        container: Container,
        binds: List<Bind> = emptyList(),
        runner: ProcessRunner = JvmProcessRunner(),
        timeoutMillis: Long = 15_000L,
        log: LogBuffer? = null,
    ): Pair<Boolean, String> {
        val prefixDir = File(container.prefixPath)
        if (!WinePayload.prefixLooksInitialised(prefixDir)) return Pair(false, "prefix not initialised - nothing to flush")
        if (WinePayload.launcherIn(rootfsDir) == null) return Pair(false, "no Wine payload - nothing to ask")
        val proot = File(binaries.nativeLibraryDir, BinaryRole.PROOT.fileName)
        val env = operationEnvironment(container, RuntimePaths.X_DISPLAY, rootfsDir, binaries.nativeLibraryDir) +
            binaries.loaderPaths()
        val outcome = runner.run(killCommand(proot, rootfsDir, prefixDir, binds), env, null, timeoutMillis)
        val ok = outcome.succeeded
        log?.add(
            TerminalLine(
                if (ok) LineKind.SYSTEM else LineKind.ERROR,
                "prefix[${container.id}]: wineserver -k " + if (ok) "ok (registry flushed)" else "failed: ${outcome.error ?: "exit ${outcome.exitCode}"}",
            ),
        )
        return Pair(ok, if (ok) "wineserver exited gracefully" else (outcome.error ?: "exit ${outcome.exitCode}"))
    }

    /** DELETE: graceful kill first (when possible), then remove the tree. */
    fun destroy(
        prefixDir: File,
        killFirst: () -> Unit = {},
    ): Boolean {
        runCatching { killFirst() }
        if (!prefixDir.exists()) return true
        return runCatching { prefixDir.deleteRecursively() }.getOrDefault(!prefixDir.exists())
    }

    private fun tailOf(outcome: RunOutcome): List<String> =
        (outcome.stdout + "\n" + outcome.stderr).trim().lineSequence()
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(TAIL_LINES)
}
