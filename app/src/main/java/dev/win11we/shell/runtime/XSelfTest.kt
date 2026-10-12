package dev.win11we.shell.runtime

import java.io.File

/**
 * The X self-test: proof of X11 contact with the simplest real clients before
 * betting Firefox on the handshake. Two kinds of steps, both inside proot
 * against the shell's own server on `:100` with the same X11 socket-dir bind
 * the Firefox session uses:
 *
 * - **Probes** (`xdpyinfo -ext RENDER`, `xwininfo -root`, `gtk-query-settings`):
 *   they exit on their own, so they answer quickly and their *output* is the
 *   verdict - a connected-but-RENDER-less server is caught here, not by a dead
 *   browser. The GTK3 probe is the one that matters for Firefox: xeyes/xclock
 *   are core X11 and Xaw, so they can pass while GTK3 still dies opening the
 *   display, and GTK's error handler names the request that killed it.
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
/** Id of the GTK3 display probe, and how long it may take (first-run font cache). */
const val GTK_PROBE_ID = "gtk3-display"
const val GTK_PROBE_MILLIS = 90_000L

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

    /**
     * Probes: id, path inside the rootfs, extra args, and the token a healthy
     * answer contains (null = "exit 0 is the verdict").
     *
     * The last one is the probe that matters for the browser: xeyes and xclock
     * are core X11 and Xaw, so they can pass while GTK3 - the toolkit Firefox is
     * built on - still dies opening the display. `gtk-query-settings` calls
     * `gtk_init()`, which opens a GDK display (XI2, the keymap, XSETTINGS, the
     * font stack) and aborts with GTK's own message if it cannot. When our server
     * answers a request GTK cannot live with, GDK's error handler prints
     * "(Details: serial N error_code X request_code Y (...) minor_code Z)" before
     * `_exit(1)` - which names the exact request to implement next.
     */
    val probes: List<Probe> = listOf(
        Probe("xdpyinfo", "usr/bin/xdpyinfo", listOf("-ext", "RENDER"), "RENDER"),
        Probe("xwininfo", "usr/bin/xwininfo", listOf("-root"), "Window id"),
        Probe(
            id = "gtk3-display",
            path = "usr/bin/gtk-query-settings",
            args = emptyList(),
            expectedOutput = null,
            // GTK parses --display=, not Xlib's -display; DISPLAY is in the
            // environment, which is what gtk_init reads anyway.
            displayFlag = false,
            pkg = "libgtk-3-bin",
            // The first GTK run builds the fontconfig cache inside the rootfs,
            // which on a phone can take tens of seconds. A healthy second run is
            // a couple of seconds.
            timeoutMillis = GTK_PROBE_MILLIS,
        ),
    )

    /** Survivors: id, path inside the rootfs, and what staying alive proves. */
    val clients: List<Triple<String, String, String>> = listOf(
        Triple("xeyes", "usr/bin/xeyes", "core X11 handshake (window, expose, pointer)"),
        Triple("xclock", "usr/bin/xclock", "Xaw + Xft, i.e. the RENDER glyph path"),
    )

    data class Probe(
        val id: String,
        val path: String,
        val args: List<String>,
        /** Text a healthy answer must contain; null means "exit 0 is enough". */
        val expectedOutput: String?,
        /** X clients take `-display :100`; GTK programs read $DISPLAY instead. */
        val displayFlag: Boolean = true,
        /** The package to install when the binary is missing (named in the SKIP). */
        val pkg: String = "x11-utils",
        /** Per-probe timeout; the handshake probes answer in well under a second. */
        val timeoutMillis: Long? = null,
    )

    /** Host environment; proot passes it into the guest, so DISPLAY travels. */
    fun environment(): Map<String, String> =
        (
            mapOf(
                // The guest root's PATH (same reason as provision: dpkg and the
                // X clients live under /usr/bin and /sbin, and host paths are dead
                // entries inside the jail), plus PROOT_TMP_DIR - the variable the
                // vendored fork actually reads for its temp directory; without it
                // every run warns "can't canonicalize /tmp/" on Android.
                "PATH" to RootfsProvisioner.GUEST_PATH,
                "HOME" to "/root",
                "LANG" to "C.UTF-8",
                "DISPLAY" to RuntimePaths.X_DISPLAY,
                "PROOT_NO_SECCOMP" to "1",
                "LD_LIBRARY_PATH" to binaries.nativeLibraryDir.absolutePath,
                "TMPDIR" to File(filesDir, "tmp").absolutePath,
                "PROOT_TMP_DIR" to File(filesDir, "tmp").absolutePath,
            ) + binaries.loaderPaths()
            ).toSortedMap()

    /**
     * The exact proot command line for one guest program (plus its arguments).
     * [displayFlag] adds Xlib's `-display :100`; GTK programs refuse that flag and
     * read `DISPLAY` from the environment ([environment] always sets it).
     */
    fun command(guestBinary: String, vararg args: String, displayFlag: Boolean = true): List<String> {
        File(filesDir, "x11").mkdirs() // the scratch prefix ProotCommand binds at /root/.wine
        val display = if (displayFlag) listOf("-display", RuntimePaths.X_DISPLAY) else emptyList()
        return ProotCommand.build(
            prootBinary = File(binaries.nativeLibraryDir, BinaryRole.PROOT.fileName),
            rootfsDir = rootfsDir,
            prefixDir = File(filesDir, "x11"),
            binds = listOf(
                Bind(RuntimePaths.xSocketUnixDir(filesDir).absolutePath, FirefoxSession.GUEST_X11_DIR),
            ),
            guestCommand = listOf(guestBinary) + display + args,
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
            val command = command("/${probe.path}", *probe.args.toTypedArray(), displayFlag = probe.displayFlag)
            when {
                blocker != null -> skipped(probe.id, command, blocker, probe.displayFlag)
                !File(rootfsDir, probe.path).isFile ->
                    skipped(probe.id, command, missing(probe.path, probe.pkg), probe.displayFlag)
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

    /**
     * A probe's output IS its verdict; a hang is a failure, not a survival.
     * [Probe.expectedOutput] == null means the exit code alone decides (the GTK3
     * probe: `gtk-query-settings` either prints settings or dies in `gtk_init`).
     */
    private fun judgeProbe(probe: Probe, command: List<String>): SelfTestStep {
        val startedAt = System.nanoTime()
        val outcome = runner.run(
            command,
            environment(),
            workingDir = null,
            timeoutMillis = probe.timeoutMillis ?: probeMillis,
        )
        val millis = (System.nanoTime() - startedAt) / 1_000_000L
        val expected = probe.expectedOutput
        val verdict: StepVerdict
        val reason: String
        when {
            outcome.exitCode == null && outcome.error?.contains("Timed out") == true -> {
                verdict = StepVerdict.FAIL
                reason = "${probe.id} hung: the socket connected but the server never answered - " +
                    "check the X Desktop window and the shared log." +
                    if (probe.id == GTK_PROBE_ID) {
                        " A first run also builds the fontconfig cache, so give it a minute and re-run."
                    } else ""
            }
            outcome.exitCode != 0 -> {
                verdict = StepVerdict.FAIL
                reason = when (probe.id) {
                    GTK_PROBE_ID ->
                        "GTK3 could not open the display " +
                            "(${outcome.error ?: "exit ${outcome.exitCode}"}). Its own message is in the " +
                            "output below: a line like \"(Details: serial ... error_code ... request_code ...)\" " +
                            "names the exact X request the server answered with an error - that is the next thing " +
                            "to implement. \"Cannot open display\" instead means the socket/DISPLAY path is broken."
                    else -> outcome.error ?: "exit ${outcome.exitCode} - could not reach DISPLAY=${RuntimePaths.X_DISPLAY}"
                }
            }
            expected != null && !outcome.stdout.contains(expected) -> {
                verdict = StepVerdict.FAIL
                reason = when (probe.id) {
                    "xdpyinfo" ->
                        "connected, but the server did not advertise RENDER - GTK3 cannot render without it."
                    else ->
                        "connected, but '$expected' is missing from the output - " +
                            "the reply was not what the client asked for."
                }
            }
            else -> {
                verdict = StepVerdict.PASS
                reason = when (probe.id) {
                    "xdpyinfo" -> "handshake OK and RENDER is advertised"
                    GTK_PROBE_ID -> "GTK3 opened the display and read its settings - " +
                        "the toolkit Firefox is built on can talk to this server"
                    else -> "handshake OK ('$expected' present)"
                }
            }
        }
        return step(probe.id, command, verdict, outcome, reason, millis, label(probe.id, probe.displayFlag))
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
        label: String = label(id, displayFlag = true),
    ): SelfTestStep {
        var output = excerpt(outcome)
        val trace = runCatching { traceProvider() }.getOrNull()
        if (!trace.isNullOrBlank()) output = "$output\nx11 trace: $trace".trim()
        return SelfTestStep(
            id = id,
            label = label,
            command = command,
            verdict = verdict,
            exitCode = outcome.exitCode,
            output = output,
            reason = reason,
            durationMillis = millis,
        )
    }

    private fun skipped(id: String, command: List<String>, reason: String, displayFlag: Boolean = true) = SelfTestStep(
        id = id,
        label = label(id, displayFlag),
        command = command,
        verdict = StepVerdict.SKIPPED,
        exitCode = null,
        output = "",
        reason = reason,
        durationMillis = 0L,
    )

    /** What the row calls itself: the guest program and how it finds the display. */
    private fun label(id: String, displayFlag: Boolean): String =
        if (displayFlag) "proot -> $id -display ${RuntimePaths.X_DISPLAY}"
        else "proot -> $id (DISPLAY=${RuntimePaths.X_DISPLAY})"

    private fun excerpt(outcome: RunOutcome): String =
        (outcome.stdout + "\n" + outcome.stderr).lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .take(6)
            .joinToString("\n")
}
