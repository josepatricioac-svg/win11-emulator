package dev.win11we.shell.ui.terminal

import androidx.compose.runtime.Stable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LineKind { PROMPT, OUTPUT, ERROR, INFO, SYSTEM }

/** One line of console output, tagged so the UI can colour it and tests can filter it. */
data class TerminalLine(
    val kind: LineKind,
    val text: String,
    val millis: Long = System.currentTimeMillis(),
) {
    fun time(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))
}

/**
 * Bounded, observable line buffer. This is the shell's log sink: R2's
 * ContainerService and R4's Diagnostics screen read the same buffer.
 */
@Stable
class LogBuffer(val capacity: Int = 4000) {
    private val lines = mutableListOf<TerminalLine>()

    fun add(line: TerminalLine) {
        lines.add(line)
        val overflow = lines.size - capacity
        if (overflow > 0) {
            // Drop whole physical lines, not list entries: a pasted block stays readable.
            var removed = 0
            while (removed < overflow && lines.isNotEmpty()) {
                lines.removeAt(0)
                removed++
            }
        }
    }

    fun addAll(newLines: List<TerminalLine>) = newLines.forEach(::add)

    fun snapshot(): List<TerminalLine> = synchronized(lines) { lines.toList() }

    fun filter(kind: LineKind): List<TerminalLine> = snapshot().filter { it.kind == kind }

    /** Last [count] lines as plain text - what Diagnostics shows next to a failure. */
    fun tail(count: Int = 20): String = snapshot().takeLast(count).joinToString("\n") { it.text }

    val size: Int get() = synchronized(lines) { lines.size }

    fun clear() = synchronized(lines) { lines.clear() }
}

/**
 * Splits a command line the way a shell does: runs of whitespace separate
 * arguments, double quotes group, backslash escapes the next character.
 * Pure function, unit-tested.
 */
object Argv {
    fun split(input: String): List<String> {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var hasToken = false
        var index = 0
        while (index < input.length) {
            val char = input[index]
            when {
                char == '\\' && index + 1 < input.length -> {
                    current.append(input[index + 1])
                    hasToken = true
                    index++
                }
                char == '"' -> {
                    inQuotes = !inQuotes
                    hasToken = true
                }
                char.isWhitespace() && !inQuotes -> {
                    if (hasToken) {
                        args.add(current.toString())
                        current.setLength(0)
                        hasToken = false
                    }
                }
                else -> {
                    current.append(char)
                    hasToken = true
                }
            }
            index++
        }
        if (hasToken) args.add(current.toString())
        return args
    }
}
