package dev.win11we.shell.ui.terminal

import androidx.compose.runtime.Immutable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Result of one command: output lines plus an exit code, like a real shell. */
@Immutable
data class CommandResult(val output: List<TerminalLine>, val exitCode: Int = 0) {
    companion object {
        fun out(vararg text: String) = CommandResult(text.map { TerminalLine(LineKind.OUTPUT, it) })
        fun info(vararg text: String) = CommandResult(text.map { TerminalLine(LineKind.INFO, it) })
        fun error(vararg text: String) = CommandResult(text.map { TerminalLine(LineKind.ERROR, it) }, 1)
        val EMPTY = CommandResult(emptyList())
    }
}

/**
 * Command execution. Today the shell interprets a built-in set locally; in R2 a
 * [ContainerCommandExecutor] takes over and forwards the line to
 * `proot -> box64 -> wine` over a pty, with the same interface.
 */
interface CommandExecutor {
    val prompt: String
    fun execute(argv: List<String>, cwd: File): CommandResult
    /** True when the executor can run Windows binaries right now. */
    val runtimeReady: Boolean get() = false
}

/** Built-in commands over the Android file system - the honest N2 terminal. */
class LocalCommandExecutor(
    private val startDirectory: File,
    private val appVersion: String,
    /** Injected so `uname` stays testable on the JVM (no android.os.Build). */
    private val deviceLine: String = "Android",
    private val shellLog: LogBuffer? = null,
) : CommandExecutor {

    override val prompt: String get() = "${startDirectory.name}> "

    override fun execute(argv: List<String>, cwd: File): CommandResult {
        val command = argv.firstOrNull()?.lowercase(Locale.ROOT) ?: return CommandResult.EMPTY
        val args = argv.drop(1)
        return when (command) {
            "help", "?" -> help()
            "version", "ver" -> CommandResult.info("Win11 Winlator Edition $appVersion (N2 terminal)")
            "pwd" -> CommandResult.out(cwd.absolutePath)
            "echo" -> CommandResult.out(args.joinToString(" "))
            "date", "time" -> CommandResult.out(
                SimpleDateFormat("EEE dd MMM yyyy  HH:mm:ss", Locale.getDefault()).format(Date()),
            )
            "whoami" -> CommandResult.out("user")
            "uname" -> CommandResult.out(deviceLine)
            "ls", "dir" -> ls(cwd, args)
            "cd" -> cd(cwd, args)
            "cat", "type" -> cat(cwd, args)
            "free" -> free()
            "env" -> env()
            "log" -> log(args)
            "clear", "cls" -> CommandResult.EMPTY // handled by the terminal state
            "wine", "wine64", "box64", "proot" -> notInstalled(command)
            "container", "win", "cmd", "start" -> notInstalled(command)
            "exit" -> CommandResult.info("Use the window's close button; 'exit' closes this terminal in N2.")
            else -> CommandResult.error(
                "'$command' is not recognised as a built-in command.",
                "Type 'help' for the list. Windows binaries need the container runtime (R1-R3).",
            )
        }
    }

    private fun help() = CommandResult.info(
        "Built-in commands (local file system, no Windows binaries yet):",
        "  help            this list",
        "  pwd             current directory",
        "  ls | dir [-a]   list a directory",
        "  cd <dir>        change directory (.. and absolute paths work)",
        "  cat | type <f>  print a text file (first 200 lines)",
        "  echo <text>     print arguments",
        "  date | time     current date and time",
        "  free            device memory",
        "  env             shell environment",
        "  log [tail]      what the shell logged (R2 pipes container logs here)",
        "  clear | cls     clear the screen",
        "  version         shell version",
        "",
        "Not available yet: wine, wine64, box64, proot, container, cmd, start.",
        "Those arrive with R1 (binaries), R2 (ContainerService) and R3 (rootfs).",
    )

    private fun ls(cwd: File, args: List<String>): CommandResult {
        val showHidden = args.any { it == "-a" || it == "/a" }
        val target = resolve(cwd, args.firstOrNull { !it.startsWith("-") && !it.startsWith("/") })
        if (!target.isDirectory) return CommandResult.error("Directory not found: ${target.path}")
        val children = target.listFiles()?.toList().orEmpty()
            .filter { showHidden || !it.name.startsWith(".") }
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })
        if (children.isEmpty()) return CommandResult.out("(empty)")
        return CommandResult.out(
            *children.map { file ->
                val size = if (file.isDirectory) "<DIR>" else "%,d".format(file.length())
                "%-28s %12s".format(file.name + if (file.isDirectory) "/" else "", size)
            }.toTypedArray(),
        )
    }

    private fun cd(cwd: File, args: List<String>): CommandResult {
        val target = resolve(cwd, args.firstOrNull())
        return when {
            !target.exists() -> CommandResult.error("Path not found: ${target.path}")
            !target.isDirectory -> CommandResult.error("Not a directory: ${target.path}")
            !target.canRead() -> CommandResult.error("Permission denied: ${target.path}")
            else -> CommandResult(output = listOf(TerminalLine(LineKind.SYSTEM, target.absolutePath)), exitCode = 0)
        }
    }

    private fun cat(cwd: File, args: List<String>): CommandResult {
        val name = args.firstOrNull() ?: return CommandResult.error("Usage: cat <file>")
        val file = resolve(cwd, name)
        if (!file.isFile) return CommandResult.error("File not found: ${file.path}")
        return try {
            val lines = file.useLines { it.take(200).toList() }
            CommandResult.out(*lines.toTypedArray())
        } catch (error: Exception) {
            CommandResult.error("Cannot read ${file.name}: ${error.message}")
        }
    }

    private fun free(): CommandResult {
        val runtime = Runtime.getRuntime()
        val totalMb = runtime.totalMemory() / 1024 / 1024
        val freeMb = runtime.freeMemory() / 1024 / 1024
        return CommandResult.out(
            "%-14s %10s".format("", "bytes"),
            "%-14s %10d".format("JVM total:", runtime.totalMemory()),
            "%-14s %10d".format("JVM free:", runtime.freeMemory()),
            "%-14s %10d".format("JVM used:", runtime.totalMemory() - runtime.freeMemory()),
            "(${totalMb}MB total, ${freeMb}MB free in this process)",
        )
    }

    private fun env() = CommandResult.out(
        "SHELL=win11we-terminal (built-in, no pty yet)",
        "PWD=${startDirectory.absolutePath}",
        "HOME=${startDirectory.absolutePath}",
        "LANG=${Locale.getDefault().toLanguageTag()}",
        "WINEPREFIX=(not created until R2/R3)",
        "DISPLAY=(the app's own X server, D1)",
    )

    private fun log(args: List<String>): CommandResult {
        val buffer = shellLog ?: return CommandResult.info("No shell log attached.")
        val count = args.firstOrNull()?.toIntOrNull() ?: 20
        val lines = buffer.snapshot().takeLast(count)
        if (lines.isEmpty()) return CommandResult.info("(log is empty)")
        return CommandResult.out(*lines.map { "${it.time()}  ${it.text}" }.toTypedArray())
    }

    private fun notInstalled(command: String) = CommandResult.error(
        "'$command' is not available yet.",
        "proot/box64 ship as lib*.so in R1, the container service starts them in R2,",
        "and the Ubuntu rootfs + Wine are downloaded in R3. Diagnostics follows in R4.",
    )

    /** Resolves a path against [cwd]; no argument means [cwd] itself. */
    private fun resolve(cwd: File, path: String?): File = when {
        path.isNullOrBlank() -> cwd
        path == "~" -> startDirectory
        File(path).isAbsolute -> File(path)
        else -> File(cwd, path).canonicalFile
    }
}
