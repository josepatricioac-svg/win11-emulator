package dev.win11we.shell.runtime

import java.io.File

/**
 * The X self-test: proof of X11 contact with the simplest real clients before
 * betting Firefox on the handshake. Two kinds of steps, both inside proot
 * against the shell's own server on `:100` with the same X11 socket-dir bind
 * the Firefox session uses:
 *
 * - **Probes** (`xdpyinfo -ext RENDER`, `xwininfo -root`): they exit on their
 *   own, so they answer in about a second and their *output* is the verdict -
 *   a connected-but-RENDER-less server is caught here, not by a dead browser.
 * - **Survivors** (`xeyes` = core X11 only, `xclock` = Xaw+Xft = the RENDER
 *   glyph path): a healthy X client never exits by itself, so **PASS means the
 *   client stayed connected for the whole window** and ANY exit code is a FAIL.
 *
 * Failures carry the client's output and - through the injected
 * [traceProvider] - the server's [XProtocolTrace] summary for that connection,
 * so the failing request is named, not guessed.
 *
 * Pure command planning over the [ProcessRunner] seam (unit-tested without
 * spawning anything); the Android-side facts (is the server bound, what did
 * the connection trace say) are injected.
 */
class XSelfTest(
    private val binaries: NativeBinaries,
    private val rootfsDir: File,
    private val filesDir: File,
    private val runner: ProcessRunner = JvmProcessRunner(),
    /** How long a survivor must stay alive to count as a working handshake. */
    private val clientMillis: Long = 6_000L,
    /** The probes answer immediately; anything slower is already wrong. */
    private val probeMillis: Long = 10_000L,
    private val xServerRunning: () -> Boolean = { false },
    private val traceProvider: () -> String? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Probes: id, path inside the rootfs, extra args, and the token a healthy answer contains. */
    val probes: List<Probe> = listOf(
        Probe("xdpyinfo", "usr/bin/xdpyinfo", listOf("-ext", "RENDER"), "RENDER"),
        Probe("xwininfo", "usr/bin/xwininfo", listOf("-root"), "Window id"),
    )

    /** Survivors: id, path inside the rootfs, and what staying alive proves. */
    val clients: List<Triple<String, String, String>> = listOf(
        Triple("xeyes", "usr/bin/xeyes", "core X11 handshake (window, expose, pointer)"),
        Triple("xclock", "usr/bin/xclock", "Xaw + Xft, i.e. the RENDER glyph path"),
    )

    data class Probe(val id: String, val path: String, val args: List<String>, val expectedOutput: String)

    /** Host environment; proot passes it into the guest, so DISPLAY travels. */
    fun environment(): Map<String, String> =
        (
            mapOf(
                "PATH" to "/usr/bin:/bin:/system/bin:/vendor/bin",
                "HOME" to "/root",
                "LANG" to "C.UTF-8",
                "DISPLAY" to RuntimePaths.X_DISPLAY,
                "PROOT_NO_SECCOMP" to "1",
                "LD_LIBRARY_PATH" to binaries.nativeLibraryDir.absolutePath,
                "TMPDIR" to File(filesDir, "tmp").absolutePath,
            ) + binaries.loaderPaths()
            ).toSortedMap()

    /** The exact proot command line for one guest program (plus its arguments). */
    fun command(guestBinary: String, vararg args: String): List<String> {
        File(filesDir, "x11").mkdirs() // the scratch prefix ProotCommand binds at /root/.wine
        return ProotCommand.build(
            prootBinary = File(binaries.nativeLibraryDir, BinaryRole.PROOT.fileName),
            rootfsDir = rootfsDir,
            prefixDir = File(filesDir, "x11"),
            binds = listOf(
                Bind(RuntimePaths.xSocketUnixDir(filesDir).absolutePath, FirefoxSession.GUEST_X11_DIR),
            ),
            guestCommand = listOf(guestBinary, "-display", RuntimePaths.X_DISPLAY) + args,
        )
    }

    fun steps(): List<SelfTestStep> {
        val prepare = binaries.prepare(NativeBinaries.REQUIRED_PROOT_ONLY)
        val blocker = when {
            prepare.status != PrepareStatus.READY ->
                "Runtime binaries not ready: ${prepare.message}"
            !RuntimePaths.rootfsExtracted(rootfsDir) ->
                "Rootfs not extracted at ${rootfsDir.absolutePath} - Runtime download first."
            !xServerRunning() ->
                "The shell's X server is not running - open Start ▸ X Desktop once " +
                    "(it binds ${RuntimePaths.X_DISPLAY}), then re-run."
            else -> null
        }
        val missing = { path: String, pkg: String ->
            "$path is not in the rootfs - install $pkg: the Provision card includes it, " +
                "or run 'apt-get install -y $pkg' inside the rootfs."
        }
        // Probes first: they fail fast, and their verdicts separate
        // "socket/proot path broken" from "clients die on a missing request".
        val probeSteps = probes.map { probe ->
            val command = command("/${probe.path}", *probe.args.toTypedArray())
            when {
                blocker != null -> skipped(probe.id, command, blocker)
                !File(rootfsDir, probe.path).isFile ->
                    skipped(probe.id, command, missing(probe.path, "x11-utils"))
                else -> judgeProbe(probe, command)
            }
        }
        val survivors = clients.map { (id, path, proves) ->
            val command = command("/$path")
            when {
                blocker != null -> skipped(id, command, blocker)
                !File(rootfsDir, path).isFile -> skipped(id, command, missing(path, "x11-apps"))
                else -> judgeSurvivor(id, proves, command)
            }
        }
        return probeSteps + survivors
    }

    fun run(): SelfTestReport {
        val steps = steps()
        return SelfTestReport(steps, clock(), steps.sumOf { it.durationMillis })
    }

    /** A probe's output IS its verdict; a hang is a failure, not a survival. */
    private fun judgeProbe(probe: Probe, command: List<String>): SelfTestStep {
        val startedAt = System.nanoTime()
        val outcome = runner.run(command, environment(), workingDir = null, timeoutMillis = probeMillis)
        val millis = (System.nanoTime() - startedAt) / 1_000_000L
        val verdict: StepVerdict
        val reason: String
        when {
            outcome.exitCode == null && outcome.error?.contains("Timed out") == true -> {
                verdict = StepVerdict.FAIL
                reason = "${probe.id} hung: the socket connected but the server never answered - " +
                    "check the X Desktop window and the shared log."
            }
            outcome.exitCode != 0 -> {
                verdict = StepVerdict.FAIL
                reason = outcome.error ?: "exit ${outcome.exitCode} - could not reach DISPLAY=${RuntimePaths.X_DISPLAY}"
            }
            !outcome.stdout.contains(probe.expectedOutput) -> {
                verdict = StepVerdict.FAIL
                reason = when (probe.id) {
                    "xdpyinfo" ->
                        "connected, but the server did not advertise RENDER - GTK3 cannot render without it."
                    else ->
                        "connected, but '${probe.expectedOutput}' is missing from the output - " +
                            "the reply was not what the client asked for."
                }
            }
            else -> {
                verdict = StepVerdict.PASS
                reason = when (probe.id) {
                    "xdpyinfo" -> "handshake OK and RENDER is advertised"
                    else -> "handshake OK ('${probe.expectedOutput}' present)"
                }
            }
        }
        return step(probe.id, command, verdict, outcome, reason, millis)
    }

    /** A survivor passes by NOT exiting; every exit code fails, with the trace attached. */
    private fun judgeSurvivor(id: String, proves: String, command: List<String>): SelfTestStep {
        val startedAt = System.nanoTime()
        val outcome = runner.run(command, environment(), workingDir = null, timeoutMillis = clientMillis)
        val millis = (System.nanoTime() - startedAt) / 1_000_000L
        val survivedTimeout = outcome.exitCode == null && outcome.error?.contains("Timed out") == true
        val verdict: StepVerdict
        val reason: String
        when {
            survivedTimeout -> {
                verdict = StepVerdict.PASS
                reason = "still connected after ${clientMillis / 1000}s - $proves"
            }
            outcome.exitCode == 0 -> {
                verdict = StepVerdict.FAIL
                reason = "exited immediately with 0 - did it ever reach DISPLAY=${RuntimePaths.X_DISPLAY}?"
            }
            else -> {
                verdict = StepVerdict.FAIL
                reason = outcome.error ?: "exit ${outcome.exitCode} while proving: $proves"
            }
        }
        return step(id, command, verdict, outcome, reason, millis)
    }

    private fun step(
        id: String,
        command: List<String>,
        verdict: StepVerdict,
        outcome: RunOutcome,
        reason: String,
        millis: Long,
    ): SelfTestStep {
        var output = excerpt(outcome)
        val trace = runCatching { traceProvider() }.getOrNull()
        if (!trace.isNullOrBlank()) output = "$output\nx11 trace: $trace".trim()
        return SelfTestStep(
            id = id,
            label = "proot -> $id -display ${RuntimePaths.X_DISPLAY}",
            command = command,
            verdict = verdict,
            exitCode = outcome.exitCode,
            output = output,
            reason = reason,
            durationMillis = millis,
        )
    }

    private fun skipped(id: String, command: List<String>, reason: String) = SelfTestStep(
        id = id,
        label = "proot -> $id -display ${RuntimePaths.X_DISPLAY}",
        command = command,
        verdict = StepVerdict.SKIPPED,
        exitCode = null,
        output = "",
        reason = reason,
        durationMillis = 0L,
    )

    private fun excerpt(outcome: RunOutcome): String =
        (outcome.stdout + "\n" + outcome.stderr).lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .take(6)
            .joinToString("\n")
}
