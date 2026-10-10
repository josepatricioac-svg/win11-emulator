package dev.win11we.shell.runtime

import dev.win11we.shell.container.Container
import java.io.File

/** What a self-test row is allowed to conclude. */
enum class StepVerdict { PASS, FAIL, SKIPPED }

/**
 * One row of the self-test: the exact command, its verdict, and enough output to
 * diagnose a failure without leaving the device.
 */
data class SelfTestStep(
    val id: String,
    val label: String,
    val command: List<String>,
    val verdict: StepVerdict,
    val exitCode: Int?,
    val output: String,
    val reason: String?,
    val durationMillis: Long,
) {
    val passed: Boolean get() = verdict == StepVerdict.PASS

    /** The failing command plus a log excerpt - exactly what the plan asks the UI to show. */
    fun report(): String = buildString {
        append(label)
        append(": ")
        append(
            when (verdict) {
                StepVerdict.PASS -> "PASS"
                StepVerdict.SKIPPED -> "SKIPPED"
                StepVerdict.FAIL -> "FAIL"
            },
        )
        if (exitCode != null) append(" (exit $exitCode)")
        reason?.let { append(" - "); append(it) }
        if (output.isNotBlank()) {
            append("\n  $ ")
            append(command.joinToString(" "))
            append("\n  ")
        }
        append(output.lines().take(6).joinToString("\n  "))
    }
}

data class SelfTestReport(
    val steps: List<SelfTestStep>,
    val startedMillis: Long,
    val durationMillis: Long,
) {
    val passed: Int get() = steps.count { it.verdict == StepVerdict.PASS }
    val failed: Int get() = steps.count { it.verdict == StepVerdict.FAIL }
    val skipped: Int get() = steps.count { it.verdict == StepVerdict.SKIPPED }
    val allGreen: Boolean get() = failed == 0 && skipped == 0 && steps.isNotEmpty()

    /** The first failing row, or null - what Diagnostics highlights. */
    val firstFailure: SelfTestStep? get() = steps.firstOrNull { it.verdict == StepVerdict.FAIL }

    fun summary(): String = "$passed passed, $failed failed, $skipped skipped"

    fun logExcerpt(lines: Int = 12): String =
        steps.filter { it.output.isNotBlank() }.joinToString("\n") { step ->
            step.output.lineSequence().take(lines).joinToString("\n")
        }
}

/**
 * R4: runs the runtime self-test.
 *
 * 1. `box64 --version`              - the shipped translator answers
 * 2. `wine64 --version` in proot    - rootfs + Wine are usable
 * 3. `wine64 cmd /c echo hello`     - a real Windows process runs and prints
 *
 * A step whose prerequisites are missing is **SKIPPED with the reason named**, never
 * passed: the point of the screen is to tell the truth about what works.
 */
class SelfTestRunner(
    private val binaries: NativeBinaries,
    private val rootfsDir: File,
    private val container: Container? = null,
    private val runner: ProcessRunner = JvmProcessRunner(),
    private val timeoutMillis: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Minimal explicit environment: the guest brings its own, the host needs
     * almost nothing - except LD_LIBRARY_PATH (the vendored proot links
     * libtalloc.so) and the proot loader paths.
     */
    private val environment: Map<String, String> = mapOf(
        "PATH" to "/system/bin:/vendor/bin:/usr/bin:/bin",
        "HOME" to "/data/local/tmp",
        "TMPDIR" to "/data/local/tmp",
        "LANG" to "C.UTF-8",
        "BOX64_NOBANNER" to "1",
        "PROOT_NO_SECCOMP" to "1",
        "LD_LIBRARY_PATH" to binaries.nativeLibraryDir.absolutePath,
    ) + binaries.loaderPaths()

    fun steps(): List<SelfTestStep> {
        val results = mutableListOf<SelfTestStep>()

        // --- 1. box64 ---------------------------------------------------------
        val box64 = binaries.record(BinaryRole.BOX64)
        results += timed("box64-version", "box64 --version") {
            if (!box64.present) {
                skipped(RuntimeProbe.box64Command(box64.file), "libbox64.so is not packaged in this APK (R1).")
            } else {
                judge(RuntimeProbe.box64Command(box64.file)) { outcome ->
                    if (outcome.succeeded) null else "box64 did not answer (exit ${outcome.exitCode})"
                }
            }
        }

        val proot = binaries.record(BinaryRole.PROOT)
        val loader = binaries.record(BinaryRole.PROOT_LOADER)

        // --- 2/3 need proot, its loader and an unpacked rootfs -----------------
        val rootfsProblem = when {
            !proot.present -> "libproot.so is not packaged in this APK (R1)."
            !loader.present -> "libproot-loader.so is not packaged in this APK (R1)."
            !rootfsDir.isDirectory -> "No rootfs at ${rootfsDir.absolutePath} - download and extract it (R3)."
            !File(rootfsDir, "bin").isDirectory -> "The rootfs at ${rootfsDir.absolutePath} is not unpacked (R3)."
            else -> null
        }

        results += timed("wine-version", "wine64 --version (in proot)") {
            if (rootfsProblem != null) {
                skipped(wineCommand("wine64", "--version"), rootfsProblem)
            } else {
                judge(wineCommand("wine64", "--version")) { outcome ->
                    if (outcome.succeeded) null else "wine64 --version failed inside proot"
                }
            }
        }

        results += timed("wine-echo", "wine64 cmd /c echo hello (in proot)") {
            val command = wineCommand("wine64", "cmd", "/c", "echo", "hello")
            if (rootfsProblem != null) {
                skipped(command, rootfsProblem)
            } else {
                judge(command) { outcome ->
                    when {
                        !outcome.succeeded -> null
                        outcome.stdout.contains("hello", ignoreCase = true) -> null
                        else -> "wine64 ran but did not print \"hello\" (got: ${outcome.stdout.trim().take(80)})"
                    }
                }
            }
        }

        return results
    }

    fun run(): SelfTestReport {
        val steps = steps()
        return SelfTestReport(steps, clock(), steps.sumOf { it.durationMillis })
    }

    /** The full proot command line for a guest command, reusing R2's builder. */
    fun wineCommand(vararg guest: String): List<String> = ProotCommand.build(
        prootBinary = File(binaries.nativeLibraryDir, BinaryRole.PROOT.fileName),
        rootfsDir = rootfsDir,
        prefixDir = File(container?.prefixPath ?: File(rootfsDir, "prefix").absolutePath),
        guestCommand = guest.toList(),
    )

    private fun judge(command: List<String>, failureReason: (RunOutcome) -> String?): SelfTestStep {
        val outcome = runner.run(command, environment, workingDir = null, timeoutMillis = timeoutMillis)
        val output = excerpt(outcome)
        val reason = outcome.error ?: failureReason(outcome)
        return SelfTestStep(
            id = command.first().substringAfterLast('/'),
            label = command.joinToString(" ").take(80),
            command = command,
            verdict = if (reason == null) StepVerdict.PASS else StepVerdict.FAIL,
            exitCode = outcome.exitCode,
            output = output,
            reason = reason ?: (if (outcome.exitCode != 0) "exit ${outcome.exitCode}" else null),
            durationMillis = 0L,
        )
    }

    private fun skipped(command: List<String>, reason: String) = SelfTestStep(
        id = command.first().substringAfterLast('/'),
        label = command.joinToString(" ").take(80),
        command = command,
        verdict = StepVerdict.SKIPPED,
        exitCode = null,
        output = "",
        reason = reason,
        durationMillis = 0L,
    )

    private fun timed(id: String, label: String, build: () -> SelfTestStep): SelfTestStep {
        val startedAt = System.nanoTime()
        val step = build()
        val millis = (System.nanoTime() - startedAt) / 1_000_000L
        return step.copy(id = id, label = label, durationMillis = millis)
    }

    private fun excerpt(outcome: RunOutcome): String =
        (outcome.stdout + "\n" + outcome.stderr).lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .take(6)
            .joinToString("\n")
}
