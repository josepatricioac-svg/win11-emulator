package dev.win11we.shell.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArgvTest {

    @Test
    fun `plain words split on whitespace`() {
        assertEquals(listOf("ls", "-a", "Docs"), Argv.split("ls   -a\tDocs"))
    }

    @Test
    fun `quotes group arguments and can express empties`() {
        assertEquals(listOf("cat", "my file.txt"), Argv.split("cat \"my file.txt\""))
        assertEquals(listOf("echo", ""), Argv.split("echo \"\""))
        assertEquals(listOf("echo", "a b"), Argv.split("echo \"a b\""))
    }

    @Test
    fun `backslash escapes the next character`() {
        assertEquals(listOf("cat", "a b"), Argv.split("cat a\\ b"))
        assertEquals(listOf("echo", "say \"hi\""), Argv.split("echo say\\ \\\"hi\\\""))
    }

    @Test
    fun `empty and blank input produce no arguments`() {
        assertEquals(emptyList<String>(), Argv.split(""))
        assertEquals(emptyList<String>(), Argv.split("   "))
    }

    @Test
    fun `an unterminated quote still yields the token`() {
        assertEquals(listOf("cat", "oops"), Argv.split("cat \"oops"))
    }
}

class LogBufferTest {

    @Test
    fun `appends keep order and are readable as a transcript`() {
        val buffer = LogBuffer()
        buffer.add(TerminalLine(LineKind.PROMPT, "C> dir"))
        buffer.addAll(
            listOf(
                TerminalLine(LineKind.OUTPUT, "file1.txt"),
                TerminalLine(LineKind.OUTPUT, "file2.txt"),
            ),
        )
        assertEquals(3, buffer.size)
        assertEquals("C> dir\nfile1.txt\nfile2.txt", buffer.tail())
        assertEquals(1, buffer.filter(LineKind.PROMPT).size)
        assertEquals(2, buffer.filter(LineKind.OUTPUT).size)
    }

    @Test
    fun `the buffer is bounded and drops the oldest lines`() {
        val buffer = LogBuffer(capacity = 5)
        (1..12).forEach { buffer.add(TerminalLine(LineKind.OUTPUT, "line $it")) }
        assertEquals(5, buffer.size)
        assertEquals(listOf("line 8", "line 9", "line 10", "line 11", "line 12"), buffer.snapshot().map { it.text })
    }

    @Test
    fun `tail returns at most the requested number of lines`() {
        val buffer = LogBuffer()
        (1..10).forEach { buffer.add(TerminalLine(LineKind.OUTPUT, "l$it")) }
        assertEquals("l9\nl10", buffer.tail(2))
        buffer.clear()
        assertEquals(0, buffer.size)
        assertEquals("", buffer.tail())
    }
}

class LocalCommandExecutorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun executor(log: LogBuffer? = null) = LocalCommandExecutor(
        startDirectory = tmp.root,
        appVersion = "0.1.0-test",
        deviceLine = "Android 14 (API 34) arm64-v8a",
        shellLog = log,
    )

    private fun text(result: CommandResult) = result.output.joinToString("\n") { it.text }

    @Test
    fun `unknown commands fail with guidance instead of throwing`() {
        val result = executor().execute(listOf("winget", "install"), tmp.root)
        assertEquals(1, result.exitCode)
        assertTrue(text(result).contains("not recognised"))
        assertTrue(text(result).contains("help"))
    }

    @Test
    fun `pwd echo version and uname`() {
        assertEquals(tmp.root.absolutePath, text(executor().execute(listOf("pwd"), tmp.root)))
        assertEquals("hello world", text(executor().execute(listOf("echo", "hello", "world"), tmp.root)))
        assertTrue(text(executor().execute(listOf("version"), tmp.root)).contains("0.1.0-test"))
        assertEquals("Android 14 (API 34) arm64-v8a", text(executor().execute(listOf("uname"), tmp.root)))
    }

    @Test
    fun `ls lists folders first and -a reveals dotfiles`() {
        File(tmp.root, "zulu").mkdir()
        File(tmp.root, "alpha.txt").writeText("x")
        File(tmp.root, ".hidden").writeText("x")
        val plain = text(executor().execute(listOf("ls"), tmp.root))
        assertTrue(plain.indexOf("zulu") < plain.indexOf("alpha.txt"))
        assertFalse(plain.contains(".hidden"))
        assertTrue(text(executor().execute(listOf("ls", "-a"), tmp.root)).contains(".hidden"))
    }

    @Test
    fun `ls of a missing directory is an error`() {
        val result = executor().execute(listOf("ls", "nope"), tmp.root)
        assertEquals(1, result.exitCode)
        assertTrue(text(result).contains("Directory not found"))
    }

    @Test
    fun `cd resolves relative, parent and absolute paths`() {
        File(tmp.root, "Docs/inner").mkdirs()
        val intoDocs = executor().execute(listOf("cd", "Docs"), tmp.root)
        val docs = File(tmp.root, "Docs")
        assertEquals(docs.absolutePath, text(intoDocs))
        assertEquals(LineKind.SYSTEM, intoDocs.output.first().kind)

        val up = executor().execute(listOf("cd", ".."), docs)
        assertEquals(docs.parentFile.absolutePath, text(up))

        val absolute = executor().execute(listOf("cd", docs.absolutePath), tmp.root)
        assertEquals(docs.absolutePath, text(absolute))
    }

    @Test
    fun `cd rejects missing paths and files`() {
        File(tmp.root, "file.txt").writeText("x")
        assertEquals(1, executor().execute(listOf("cd", "missing"), tmp.root).exitCode)
        assertEquals(1, executor().execute(listOf("cd", "file.txt"), tmp.root).exitCode)
    }

    @Test
    fun `cat prints a file and reports missing ones`() {
        File(tmp.root, "notes.txt").writeText("first\nsecond")
        assertEquals("first\nsecond", text(executor().execute(listOf("cat", "notes.txt"), tmp.root)))
        assertEquals(1, executor().execute(listOf("cat", "gone.txt"), tmp.root).exitCode)
        assertEquals(1, executor().execute(listOf("cat"), tmp.root).exitCode)
    }

    @Test
    fun `windows commands explain what is missing instead of lying`() {
        for (command in listOf("wine", "wine64", "box64", "proot", "cmd", "container")) {
            val result = executor().execute(listOf(command, "--version"), tmp.root)
            assertEquals(command, 1, result.exitCode)
            assertTrue(text(result).contains("not available yet"))
            assertTrue(text(result).contains("R1"))
        }
        assertFalse(executor().runtimeReady)
    }

    @Test
    fun `log replays the shell log when one is attached`() {
        val log = LogBuffer()
        log.add(TerminalLine(LineKind.OUTPUT, "container started"))
        val withLog = text(executor(log).execute(listOf("log"), tmp.root))
        assertTrue(withLog.contains("container started"))
        assertTrue(text(executor(null).execute(listOf("log"), tmp.root)).contains("No shell log"))
    }

    @Test
    fun `help and free and env produce output without failing`() {
        assertEquals(0, executor().execute(listOf("help"), tmp.root).exitCode)
        assertTrue(text(executor().execute(listOf("free"), tmp.root)).contains("JVM total:"))
        assertTrue(text(executor().execute(listOf("env"), tmp.root)).contains("WINEPREFIX"))
    }
}

class TerminalStateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun state(onClose: () -> Unit = {}): TerminalState {
        val log = LogBuffer()
        val terminal = TerminalState(
            executor = LocalCommandExecutor(tmp.root, "0.1.0-test", "Android test", log),
            startDirectory = tmp.root,
            log = log,
        )
        terminal.onClose = onClose
        return terminal
    }

    @Test
    fun `the banner is shown before any command`() {
        val terminal = state()
        assertTrue(terminal.lines.any { it.text.contains("Terminal (N2)") })
        assertTrue(terminal.lines.none { it.kind == LineKind.PROMPT })
    }

    @Test
    fun `submit echoes the prompt line and appends the output`() {
        val terminal = state()
        terminal.draft = "echo hi"
        terminal.submit()
        assertEquals("", terminal.draft) // the input box is cleared
        val prompts = terminal.lines.filter { it.kind == LineKind.PROMPT }
        assertEquals(1, prompts.size)
        assertTrue(prompts.single().text.endsWith("echo hi"))
        assertTrue(terminal.lines.any { it.kind == LineKind.OUTPUT && it.text == "hi" })
    }

    @Test
    fun `blank input is ignored`() {
        val terminal = state()
        val before = terminal.lines.size
        terminal.draft = "   "
        terminal.submit()
        assertEquals(before, terminal.lines.size)
        assertEquals(0, terminal.historySnapshot().size)
    }

    @Test
    fun `a failing command reports its exit code`() {
        val terminal = state()
        terminal.submit("nosuchcommand")
        assertTrue(terminal.lines.any { it.kind == LineKind.ERROR && it.text.contains("exit code 1") })
    }

    @Test
    fun `cd changes the working directory and the prompt`() {
        File(tmp.root, "Docs").mkdir()
        val terminal = state()
        val before = terminal.prompt
        terminal.submit("cd Docs")
        assertEquals(File(tmp.root, "Docs"), terminal.cwd)
        assertEquals("Docs> ", terminal.prompt)
        assertFalse(before == terminal.prompt)
    }

    @Test
    fun `clear empties the transcript but keeps the log buffer`() {
        val terminal = state()
        terminal.submit("echo one")
        assertTrue(terminal.lines.isNotEmpty())
        terminal.submit("clear")
        assertEquals(0, terminal.lines.size)
        assertTrue("the shared log survives a screen clear", terminal.log.size > 0)
    }

    @Test
    fun `exit closes the window`() {
        var closed = 0
        val terminal = state(onClose = { closed++ })
        terminal.submit("exit")
        assertEquals(1, closed)
    }

    @Test
    fun `history walks backwards and forwards`() {
        val terminal = state()
        terminal.submit("echo one")
        terminal.submit("echo two")
        terminal.historyPrevious()
        assertEquals("echo two", terminal.draft)
        terminal.historyPrevious()
        assertEquals("echo one", terminal.draft)
        terminal.historyPrevious() // clamps at the oldest entry
        assertEquals("echo one", terminal.draft)
        terminal.historyNext()
        assertEquals("echo two", terminal.draft)
        terminal.historyNext() // past the newest -> empty draft
        assertEquals("", terminal.draft)
        assertEquals(listOf("echo one", "echo two"), terminal.historySnapshot())
    }

    @Test
    fun `the transcript is plain text for copying and sharing`() {
        val terminal = state()
        terminal.submit("echo copy-me")
        assertTrue(terminal.transcript().contains("copy-me"))
    }
}
