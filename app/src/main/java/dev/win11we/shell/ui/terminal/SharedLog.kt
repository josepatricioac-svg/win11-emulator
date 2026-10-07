package dev.win11we.shell.ui.terminal

/**
 * The shell-wide log. Terminal transcripts, ContainerService output and the
 * Diagnostics screen all read/write this one buffer, which is what R4 displays
 * next to a failing command.
 */
object SharedLog {
    val buffer = LogBuffer(capacity = 8000)

    fun system(text: String) = buffer.add(TerminalLine(LineKind.SYSTEM, text))
    fun info(text: String) = buffer.add(TerminalLine(LineKind.INFO, text))
    fun output(text: String) = buffer.add(TerminalLine(LineKind.OUTPUT, text))
    fun error(text: String) = buffer.add(TerminalLine(LineKind.ERROR, text))

    fun excerpt(lines: Int = 20): String = buffer.tail(lines)
}
