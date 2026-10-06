package dev.win11we.shell.ui.terminal

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * State of one Terminal window: the transcript, the input draft, the working
 * directory and the command history. Everything but the UI, so it is testable.
 */
@Stable
class TerminalState(
    val executor: CommandExecutor,
    val startDirectory: File,
    val log: LogBuffer = LogBuffer(),
) {
    /** Wired by the window body once the window exists: `exit` closes the window. */
    var onClose: () -> Unit = {}

    var lines by mutableStateOf(listOf<TerminalLine>())
        private set
    var draft by mutableStateOf("")
    var cwd by mutableStateOf(startDirectory)
        private set

    private val history = mutableListOf<String>()
    private var historyCursor = -1

    /** Prompt shown in the input row, Windows-cmd style. */
    val prompt: String
        get() = if (cwd == startDirectory) "${cwd.name}> " else "${cwd.name.ifBlank { cwd.absolutePath }}> "

    init {
        banner()
    }

    private fun banner() {
        append(
            listOf(
                TerminalLine(LineKind.INFO, "Win11 Winlator Edition - Terminal (N2)"),
                TerminalLine(
                    LineKind.INFO,
                    "Built-in local shell. Windows binaries (wine/box64) arrive with R1-R3; " +
                        "this window then talks to the container pty.",
                ),
                TerminalLine(LineKind.OUTPUT, "Type 'help' for the command list."),
            ),
        )
    }

    fun append(newLines: List<TerminalLine>) {
        lines = lines + newLines
        log.addAll(newLines)
    }

    fun clear() {
        lines = emptyList()
    }

    /** Runs [input]; empty lines are ignored, `clear` and `exit` are handled here. */
    fun submit(input: String = draft) {
        val trimmed = input.trim()
        draft = ""
        if (trimmed.isEmpty()) return

        history.add(trimmed)
        historyCursor = history.size
        append(listOf(TerminalLine(LineKind.PROMPT, prompt + trimmed)))

        val argv = Argv.split(trimmed)
        when (argv.firstOrNull()?.lowercase()) {
            "clear", "cls" -> clear()
            "exit" -> {
                append(listOf(TerminalLine(LineKind.SYSTEM, "Terminal closed.")))
                onClose()
                return
            }
        }

        val result = try {
            executor.execute(argv, cwd)
        } catch (error: Exception) {
            CommandResult.error("${error.javaClass.simpleName}: ${error.message}")
        }
        if (result.output.isNotEmpty()) append(result.output)
        if (result.exitCode != 0) {
            append(listOf(TerminalLine(LineKind.ERROR, "exit code ${result.exitCode}")))
        }
        // `cd` reports the new directory as a SYSTEM line.
        result.output.firstOrNull { it.kind == LineKind.SYSTEM }?.let { line ->
            val target = File(line.text)
            if (target.isDirectory) cwd = target
        }
    }

    /** Arrow-up / arrow-down through history, like any console. */
    fun historyPrevious() {
        if (history.isEmpty()) return
        historyCursor = (historyCursor - 1).coerceAtLeast(0)
        draft = history[historyCursor]
    }

    fun historyNext() {
        if (history.isEmpty()) return
        historyCursor = (historyCursor + 1).coerceAtMost(history.size)
        draft = if (historyCursor >= history.size) "" else history[historyCursor]
    }

    fun historySnapshot(): List<String> = history.toList()

    /** The transcript as text, for sharing/copying (full clipboard sync is P1). */
    fun transcript(): String = lines.joinToString("\n") { it.text }
}
