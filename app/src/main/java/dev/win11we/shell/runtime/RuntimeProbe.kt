package dev.win11we.shell.runtime

import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Process execution seam. R2's ContainerService uses the real [ProcessRunner];
 * unit tests use a fake, so command construction and exit-code handling are
 * testable without spawning anything.
 */
interface ProcessRunner {
    fun run(command: List<String>, environment: Map<String, String>, workingDir: File?, timeoutMillis: Long): RunOutcome
}

data class RunOutcome(val exitCode: Int?, val stdout: String, val stderr: String, val error: String? = null) {
    val succeeded: Boolean get() = exitCode == 0 && error == null
}

/** Real implementation: [ProcessBuilder] with an explicit environment (nothing inherited). */
class JvmProcessRunner : ProcessRunner {
    override fun run(
        command: List<String>,
        environment: Map<String, String>,
        workingDir: File?,
        timeoutMillis: Long,
    ): RunOutcome {
        if (command.isEmpty()) return RunOutcome(null, "", "", "No command given")
        return try {
            val builder = ProcessBuilder(command)
            builder.environment().clear()
            builder.environment().putAll(environment)
            if (workingDir != null) builder.directory(workingDir)
            builder.redirectErrorStream(false)
            val process = builder.start()
            // Both pipes drain concurrently on daemon threads. Sequential reads
            // deadlock as soon as the child fills the 64 KiB stderr buffer while
            // we block on stdout - exactly what apt-get does mid-provision - and
            // even a killed child only EOFs its pipes when EVERY holder of the
            // write end exits, so orphaned grandchildren must never block the
            // caller: the joins below are bounded and partial output is honest.
            val stdout = StreamDrain(process.inputStream)
            val stderr = StreamDrain(process.errorStream)
            val finished =
                if (timeoutMillis > 0) process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) else process.waitFor().let { true }
            if (!finished) {
                process.destroyForcibly()
                stdout.joinFor(DRAIN_GRACE_MILLIS)
                stderr.joinFor(DRAIN_GRACE_MILLIS)
                return RunOutcome(null, stdout.text(), stderr.text(), "Timed out after ${timeoutMillis}ms")
            }
            stdout.joinFor(DRAIN_GRACE_MILLIS)
            stderr.joinFor(DRAIN_GRACE_MILLIS)
            RunOutcome(process.exitValue(), stdout.text(), stderr.text())
        } catch (error: Exception) {
            RunOutcome(null, "", "", "${error.javaClass.simpleName}: ${error.message}")
        }
    }

    /** Reads one stream to EOF on a daemon thread; [text] stays available (partial) if the reader is still blocked. */
    private class StreamDrain(stream: InputStream) {
        private val buffer = StringBuffer()
        private val thread = Thread {
            runCatching { stream.bufferedReader().forEachLine { buffer.append(it).append('\n') } }
        }.apply {
            isDaemon = true
            name = "process-drain"
            start()
        }

        fun joinFor(millis: Long) = runCatching { thread.join(millis) }

        fun text(): String = buffer.toString()
    }

    private companion object {
        /** How long pipe EOFs may lag the child's exit before we report partial output. */
        const val DRAIN_GRACE_MILLIS = 2_000L
    }
}

/** One self-test row: what we ran, and whether it answered. */
data class ProbeResult(
    val id: String,
    val command: List<String>,
    val exitCode: Int?,
    val output: String,
    val error: String?,
    val durationMillis: Long,
) {
    val passed: Boolean get() = exitCode == 0 && error == null
    val summary: String get() = when {
        error != null -> error
        exitCode == null -> "no exit code"
        else -> "exit $exitCode"
    }
}

/**
 * Runs the shipped binaries just far enough to prove they are executable and the
 * right architecture. R4 extends this with wine64 inside the rootfs; the shape of
 * the result (command + exit code + log excerpt) is already what R4 displays.
 */
class RuntimeProbe(
    private val binaries: NativeBinaries,
    private val runner: ProcessRunner = JvmProcessRunner(),
    private val timeoutMillis: Long = 8_000L,
) {
    /**
     * Minimal explicit environment: these probes do not need Wine or the rootfs.
     * LD_LIBRARY_PATH is NOT optional here - the vendored proot links libtalloc.so
     * and the loader paths let proot find its ptrace loaders.
     */
    private val environment: Map<String, String> = mapOf(
        "PATH" to "/system/bin:/vendor/bin:/usr/bin:/bin",
        "HOME" to "/data/local/tmp",
        "TMPDIR" to "/data/local/tmp",
        "LANG" to "C.UTF-8",
        "BOX64_NOBANNER" to "1",
        "LD_LIBRARY_PATH" to binaries.nativeLibraryDir.absolutePath,
    ) + binaries.loaderPaths()

    fun runAll(): List<ProbeResult> = listOf(runBox64(), runProot())

    fun runBox64(): ProbeResult {
        val binary = binaries.record(BinaryRole.BOX64)
        return run("box64-version", box64Command(binary.file))
    }

    fun runProot(): ProbeResult {
        val binary = binaries.record(BinaryRole.PROOT)
        return run("proot-version", prootCommand(binary.file))
    }

    private fun run(id: String, command: List<String>): ProbeResult {
        val started = System.nanoTime()
        val outcome = runner.run(command, environment, workingDir = null, timeoutMillis = timeoutMillis)
        val millis = (System.nanoTime() - started) / 1_000_000L
        val output = (outcome.stdout + outcome.stderr).trim().lineSequence()
            .filter { it.isNotBlank() }
            .take(6)
            .joinToString("\n")
        return ProbeResult(
            id = id,
            command = command,
            exitCode = outcome.exitCode,
            output = output,
            // `error` is only for transport failures (spawn, timeout); a non-zero exit
            // code is reported as "exit N" and the program's stderr stays in the excerpt.
            error = outcome.error,
            durationMillis = millis,
        )
    }

    companion object {
        /** `box64 --version` - the R4 self-test's first row. */
        fun box64Command(box64: File): List<String> = listOf(box64.absolutePath, "--version")

        /** `proot --version`; proot exits non-zero for --help on some forks, so keep it simple. */
        fun prootCommand(proot: File): List<String> = listOf(proot.absolutePath, "--version")
    }
}
