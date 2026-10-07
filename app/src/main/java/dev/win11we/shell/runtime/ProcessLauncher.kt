package dev.win11we.shell.runtime

import java.io.File
import java.io.InputStream

/** A started process the launcher can stream from and destroy. */
interface ProcessHandle {
    val isAlive: Boolean
    fun stdout(): InputStream
    fun stderr(): InputStream
    fun waitFor(timeoutMillis: Long): Int?
    fun destroy()
}

/** Starts a long-lived process with an explicit environment (nothing inherited). */
interface ProcessLauncher {
    /** Returns null plus [LaunchFailure] when the process could not be started at all. */
    fun start(command: List<String>, environment: Map<String, String>, workingDir: File?): LaunchAttempt
}

data class LaunchAttempt(val handle: ProcessHandle?, val failure: String?) {
    val started: Boolean get() = handle != null
}

/** Real launcher: [ProcessBuilder]. Kept thin so the logic around it stays testable. */
class RealProcessLauncher : ProcessLauncher {
    override fun start(command: List<String>, environment: Map<String, String>, workingDir: File?): LaunchAttempt {
        if (command.isEmpty()) return LaunchAttempt(null, "No command given")
        val binary = File(command.first())
        if (!binary.isFile) return LaunchAttempt(null, "Binary not found: ${command.first()}")
        if (!binary.canExecute()) {
            val fixed = binary.setExecutable(true, false)
            if (!fixed && !binary.canExecute()) {
                return LaunchAttempt(null, "Not executable and +x could not be set: ${binary.absolutePath}")
            }
        }
        return try {
            val builder = ProcessBuilder(command)
            builder.environment().clear()
            builder.environment().putAll(environment)
            if (workingDir != null) builder.directory(workingDir)
            builder.redirectErrorStream(false)
            val process = builder.start()
            LaunchAttempt(ProcessHandleImpl(process), null)
        } catch (error: Exception) {
            LaunchAttempt(null, "${error.javaClass.simpleName}: ${error.message}")
        }
    }
}

private class ProcessHandleImpl(private val process: Process) : ProcessHandle {
    override val isAlive: Boolean get() = process.isAlive
    override fun stdout(): InputStream = process.inputStream
    override fun stderr(): InputStream = process.errorStream
    override fun waitFor(timeoutMillis: Long): Int? = try {
        if (process.waitFor(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) process.exitValue() else null
    } catch (error: InterruptedException) {
        null
    }

    override fun destroy() {
        runCatching { process.destroy() }
        // give it a moment, then force: a stuck wine process must not outlive the container
        if (process.isAlive) runCatching { process.destroyForcibly() }
    }
}
