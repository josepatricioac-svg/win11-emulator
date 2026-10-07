package dev.win11we.shell.ui.diagnostics

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.win11we.shell.ui.terminal.LineKind
import dev.win11we.shell.runtime.PrepareStatus
import dev.win11we.shell.ui.terminal.LogBuffer
import dev.win11we.shell.ui.terminal.TerminalLine
import dev.win11we.shell.runtime.NativeBinaries
import dev.win11we.shell.runtime.PrepareResult
import dev.win11we.shell.runtime.ProbeResult
import dev.win11we.shell.runtime.RuntimeProbe
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

    /** Inventory + permission fix, without spawning a process. */
    fun check(): PrepareResult {
        val result = binaries.prepare()
        prepareResult = result
        log.add(TerminalLine(LineKind.SYSTEM, "prepare: ${result.status} - ${result.message}"))
        return result
    }

    /** Runs `box64 --version` and `proot --version`; green/red per row. */
    fun runProbes(): List<ProbeResult> {
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

    fun logExcerpt(lines: Int = 20): String = log.tail(lines)
}
