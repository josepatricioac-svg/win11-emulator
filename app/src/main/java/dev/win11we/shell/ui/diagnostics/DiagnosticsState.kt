package dev.win11we.shell.ui.diagnostics

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import dev.win11we.shell.ui.terminal.LineKind
import dev.win11we.shell.runtime.PrepareStatus
import dev.win11we.shell.ui.terminal.LogBuffer
import dev.win11we.shell.ui.terminal.TerminalLine
import dev.win11we.shell.runtime.JvmProcessRunner
import dev.win11we.shell.runtime.NativeBinaries
import dev.win11we.shell.runtime.ProcessRunner
import dev.win11we.shell.runtime.PrepareResult
import dev.win11we.shell.runtime.ProbeResult
import dev.win11we.shell.runtime.RuntimeProbe
import dev.win11we.shell.runtime.SelfTestReport
import dev.win11we.shell.runtime.SelfTestRunner
import dev.win11we.shell.runtime.XSelfTest
import dev.win11we.shell.runtime.SelfTestStep
import dev.win11we.shell.runtime.StepVerdict
import java.io.File

/**
 * Per-window state of the Diagnostics app: what is shipped, whether it can be
 * exec'd, and what each probe answered. R4 extends [probe] with wine64 inside the
 * rootfs; the display contract (command + exit code + log excerpt) is already here.
 */
@Stable
class DiagnosticsState(
    val binaries: NativeBinaries,
    val probe: RuntimeProbe = RuntimeProbe(binaries),
    val log: LogBuffer = LogBuffer(),
    val rootfsDir: File? = null,
    val filesDir: File? = null,
    /** Injectable so the self-test is testable without spawning anything. */
    private val runner: ProcessRunner = JvmProcessRunner(),
    /** Android-side facts for the X self-test; defaults keep it honest ("not running"). */
    private val xServerRunning: () -> Boolean = { false },
    private val xTraceProvider: () -> String? = { null },
) {
    var prepareResult by mutableStateOf<PrepareResult?>(null)
        private set
    var probes by mutableStateOf<List<ProbeResult>>(emptyList())
        private set
    var lastRunMillis by mutableStateOf(0L)
        private set
    var running by mutableStateOf(false)
        private set

    val nativeLibraryDir: File get() = binaries.nativeLibraryDir

    val ready: Boolean get() = prepareResult?.status == PrepareStatus.READY

    init {
        check()
    }

    /**
     * Runs [work] on a guarded daemon worker. Every Diagnostics action execs
     * something (proot, box64, wine64, a GTK first run that builds the font
     * cache), and the chips call these from a click handler - i.e. the UI thread.
     * Blocking there for the tens of seconds a self-test takes is an ANR, so the
     * work moves off it: state writes go through `Snapshot.withMutableSnapshot`
     * so Compose sees them from the worker (crash-safety rules 1 and 2), any
     * Throwable becomes a log line instead of taking the process down, and the
     * busy flag is set on the calling thread so the chip flips to "Testing..."
     * immediately. [busy] is also what stops a second click starting a second run.
     */
    private fun startWorker(name: String, busy: (Boolean) -> Unit, work: () -> Unit) {
        Snapshot.withMutableSnapshot { busy(true) }
        Thread {
            try {
                Snapshot.withMutableSnapshot { work() }
            } catch (error: Throwable) {
                Snapshot.withMutableSnapshot {
                    log.add(
                        TerminalLine(
                            LineKind.ERROR,
                            "$name failed: ${error.javaClass.simpleName}: ${error.message}",
                        ),
                    )
                }
            } finally {
                Snapshot.withMutableSnapshot { busy(false) }
            }
        }.apply {
            this.name = "diagnostics-$name"
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /** Inventory + permission fix, without spawning a process. */
    fun check(): PrepareResult {
        val result = binaries.prepare()
        prepareResult = result
        log.add(TerminalLine(LineKind.SYSTEM, "prepare: ${result.status} - ${result.message}"))
        return result
    }

    /** UI entry point: the probes run on a worker, so the chip never blocks. */
    fun runProbes() {
        if (running) return
        startWorker("--version probes", { running = it }) { runProbesSync() }
    }

    /** Runs `box64 --version` and `proot --version`; green/red per row. Blocking. */
    fun runProbesSync(): List<ProbeResult> {
        running = true
        val results = try {
            probe.runAll()
        } finally {
            running = false
        }
        probes = results
        lastRunMillis = System.currentTimeMillis()
        results.forEach { result ->
            log.add(
                TerminalLine(
                    if (result.passed) LineKind.INFO
                    else LineKind.ERROR,
                    "${result.id}: ${result.summary}",
                ),
            )
        }
        return results
    }

    var selfTest by mutableStateOf<SelfTestReport?>(null)
        private set
    var selfTestRunning by mutableStateOf(false)
        private set

    var xSelfTest by mutableStateOf<SelfTestReport?>(null)
        private set
    var xSelfTestRunning by mutableStateOf(false)
        private set

    /**
     * D1d/D1e: probes the handshake (xdpyinfo/xwininfo), asks GTK3 to open the
     * display (gtk-query-settings - the toolkit Firefox is built on), then
     * launches xeyes (core X11) and xclock (Xaw+Xft = the RENDER path) inside
     * proot against the shell's own X server. PASS for a survivor = it stayed
     * connected; failures carry the exit code, the client's own output and the
     * connection's protocol trace, so the missing request is named.
     */
    /** UI entry point: the X self-test runs on a worker (it can take a minute). */
    fun runXSelfTest() {
        if (xSelfTestRunning) return
        startWorker("x-self-test", { xSelfTestRunning = it }) { runXSelfTestSync() }
    }

    /** Blocking form; [runXSelfTest] is what the UI calls. */
    fun runXSelfTestSync(): SelfTestReport {
        xSelfTestRunning = true
        val test = XSelfTest(
            binaries = binaries,
            rootfsDir = rootfsDir ?: File("/nonexistent"),
            filesDir = filesDir ?: File("/nonexistent"),
            runner = runner,
            xServerRunning = xServerRunning,
            traceProvider = xTraceProvider,
        )
        val report = try {
            test.run()
        } finally {
            xSelfTestRunning = false
        }
        xSelfTest = report
        log.add(TerminalLine(LineKind.SYSTEM, "x self-test: ${report.summary()}"))
        report.steps.forEach { step ->
            log.add(
                TerminalLine(
                    when (step.verdict) {
                        StepVerdict.PASS -> LineKind.INFO
                        StepVerdict.FAIL -> LineKind.ERROR
                        StepVerdict.SKIPPED -> LineKind.SYSTEM
                    },
                    "${step.id} ${step.verdict}${step.reason?.let { " - $it" } ?: ""}",
                ),
            )
        }
        report.firstFailure?.let { failure ->
            log.add(TerminalLine(LineKind.ERROR, "x self-test first failure:\n${failure.report()}"))
        }
        return report
    }

    /**
     * R4: box64 --version, wine64 --version and `wine64 cmd /c echo hello` through the
     * real proot command line. Steps whose prerequisites are missing are skipped with
     * the reason named, so the screen never claims a green it did not earn.
     */
    /** UI entry point: the R4 self-test runs on a worker (wine64 can take minutes). */
    fun runSelfTest() {
        if (selfTestRunning) return
        startWorker("self-test", { selfTestRunning = it }) { runSelfTestSync() }
    }

    /** Blocking form; [runSelfTest] is what the UI calls. */
    fun runSelfTestSync(): SelfTestReport {
        selfTestRunning = true
        val selfTestRunner = SelfTestRunner(
            binaries = binaries,
            rootfsDir = rootfsDir ?: File("/nonexistent"),
            runner = runner,
        )
        val report = try {
            selfTestRunner.run()
        } finally {
            selfTestRunning = false
        }
        selfTest = report
        log.add(TerminalLine(LineKind.SYSTEM, "self-test: ${report.summary()}"))
        report.steps.forEach { step ->
            log.add(
                TerminalLine(
                    when (step.verdict) {
                        StepVerdict.PASS -> LineKind.INFO
                        StepVerdict.FAIL -> LineKind.ERROR
                        StepVerdict.SKIPPED -> LineKind.SYSTEM
                    },
                    "${step.id} ${step.verdict}${step.reason?.let { " - $it" } ?: ""}",
                ),
            )
        }
        report.firstFailure?.let { failure ->
            log.add(TerminalLine(LineKind.ERROR, "failing command: ${failure.command.joinToString(" ")}"))
            log.add(TerminalLine(LineKind.ERROR, "log excerpt:\n${failure.output.ifBlank { "(no output)" }}"))
        }
        return report
    }

    fun selfTestSteps(): List<SelfTestStep> = selfTest?.steps ?: emptyList()

    fun logExcerpt(lines: Int = 20): String = log.tail(lines)
}
