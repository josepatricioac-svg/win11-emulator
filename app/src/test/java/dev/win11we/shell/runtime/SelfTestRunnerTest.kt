package dev.win11we.shell.runtime

import dev.win11we.shell.container.Container
import dev.win11we.shell.ui.diagnostics.DiagnosticsState
import dev.win11we.shell.ui.terminal.LineKind
import dev.win11we.shell.ui.terminal.LogBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Answers from a script keyed on the *last* argument of each command. */
private class ScriptedRunner(private val script: Map<String, RunOutcome>) : ProcessRunner {
    val calls = mutableListOf<List<String>>()
    override fun run(command: List<String>, environment: Map<String, String>, workingDir: File?, timeoutMillis: Long): RunOutcome {
        calls.add(command)
        val key = command.last()
        return script[key] ?: RunOutcome(127, "", "$key: not found")
    }
}

class SelfTestRunnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var nativeDir: File
    private lateinit var rootfs: File
    private lateinit var prefix: File

    @Before
    fun setUp() {
        nativeDir = tmp.newFolder("lib")
        rootfs = tmp.newFolder("rootfs")
        File(rootfs, "bin").mkdirs()
        File(rootfs, "usr").mkdirs()
        prefix = tmp.newFolder("prefix")
        listOf(BinaryRole.BOX64, BinaryRole.PROOT, BinaryRole.PROOT_LOADER).forEach { role ->
            File(nativeDir, role.fileName).writeBytes(ByteArray(1024) { 0x42 })
        }
    }

    private fun pins() = BinaryRole.entries
        .filter { File(nativeDir, it.fileName).isFile }
        .associate { it.fileName to NativeBinaries.sha256(File(nativeDir, it.fileName))!! }

    private fun runner(script: Map<String, RunOutcome>, container: Container? = null) = SelfTestRunner(
        binaries = NativeBinaries(nativeDir, pins()),
        rootfsDir = rootfs,
        container = container,
        runner = ScriptedRunner(script),
    )

    private val green = mapOf(
        "--version" to RunOutcome(0, "box64 v0.4.5\n", ""),
        "hello" to RunOutcome(0, "hello\r\n", ""),
    )

    @Test
    fun `three green steps when everything answers`() {
        val report = runner(green).run()
        assertEquals(3, report.steps.size)
        assertTrue(report.allGreen)
        assertEquals(3, report.passed)
        assertEquals(0, report.failed)
        assertEquals("3 passed, 0 failed, 0 skipped", report.summary())
        assertNull(report.firstFailure)
    }

    @Test
    fun `the three required commands are run in order, wine inside proot`() {
        val scripted = ScriptedRunner(green)
        SelfTestRunner(NativeBinaries(nativeDir, pins()), rootfs, null, scripted).run()
        assertEquals(3, scripted.calls.size)

        assertEquals(listOf(File(nativeDir, "libbox64.so").absolutePath, "--version"), scripted.calls[0])
        // steps 2 and 3 go through proot with the fixed prefix bind
        listOf(1, 2).forEach { index ->
            val command = scripted.calls[index]
            assertEquals(File(nativeDir, "libproot.so").absolutePath, command.first())
            assertTrue(command.containsAll(listOf("--rootfs", rootfs.absolutePath)))
            assertTrue(command.contains("--kill-on-exit"))
        }
        assertTrue(scripted.calls[1].containsAll(listOf("wine64", "--version")))
        assertTrue(scripted.calls[2].containsAll(listOf("wine64", "cmd", "/c", "echo", "hello")))
    }

    @Test
    fun `a container prefix is bound instead of the fallback one`() {
        val container = Container.defaults("office", "Office", prefix.absolutePath)
        val scripted = ScriptedRunner(green)
        SelfTestRunner(NativeBinaries(nativeDir, pins()), rootfs, container, scripted).run()
        assertTrue(scripted.calls[1].contains(prefix.absolutePath + ":/root/.wine"))
    }

    @Test
    fun `box64 not answering is a red row with its command`() {
        val report = runner(mapOf("--version" to RunOutcome(126, "", "Permission denied"), "hello" to RunOutcome(0, "hello", ""))).run()
        val step = report.steps.first()
        assertEquals(StepVerdict.FAIL, step.verdict)
        assertEquals(126, step.exitCode)
        assertTrue(step.report().contains("Permission denied"))
        assertFalse(report.allGreen)
        assertEquals(step, report.firstFailure)
    }

    @Test
    fun `wine running but not printing hello is a failure, not a pass`() {
        val report = runner(
            mapOf("--version" to RunOutcome(0, "wine-9.0", ""), "hello" to RunOutcome(0, "cmd: not found", "")),
        ).run()
        val echo = report.steps.last()
        assertEquals(StepVerdict.FAIL, echo.verdict)
        assertEquals(0, echo.exitCode) // exit code alone would have looked fine
        assertTrue(echo.reason!!.contains("did not print"))
    }

    @Test
    fun `without a rootfs the wine steps are skipped with the reason, never passed`() {
        val empty = File(tmp.root, "no-rootfs")
        val report = SelfTestRunner(NativeBinaries(nativeDir, pins()), empty, null, ScriptedRunner(green)).run()
        assertEquals(StepVerdict.PASS, report.steps[0].verdict)
        assertEquals(StepVerdict.SKIPPED, report.steps[1].verdict)
        assertEquals(StepVerdict.SKIPPED, report.steps[2].verdict)
        assertTrue(report.steps[1].reason!!.contains("R3"))
        assertFalse(report.allGreen)
        assertEquals(1, report.passed)
        assertEquals(2, report.skipped)
    }

    @Test
    fun `an unpacked archive is not mistaken for a rootfs`() {
        File(rootfs, "bin").deleteRecursively()
        val report = runner(green).run()
        assertTrue(report.steps[1].reason!!.contains("not unpacked"))
    }

    @Test
    fun `without proot the wine steps are skipped and the reason names R1`() {
        File(nativeDir, BinaryRole.PROOT.fileName).delete()
        val report = runner(green).run()
        assertEquals(StepVerdict.SKIPPED, report.steps[1].verdict)
        assertTrue(report.steps[1].reason!!.contains("libproot.so"))
    }

    @Test
    fun `without box64 the first step is skipped rather than exec'd`() {
        File(nativeDir, BinaryRole.BOX64.fileName).delete()
        val scripted = ScriptedRunner(green)
        val report = SelfTestRunner(NativeBinaries(nativeDir, pinsOfProotOnly()), rootfs, null, scripted).run()
        assertEquals(StepVerdict.SKIPPED, report.steps[0].verdict)
        assertTrue(report.steps[0].reason!!.contains("libbox64.so"))
        assertTrue("no box64 process may be spawned", scripted.calls.none { it.first().contains("box64") })
    }

    private fun pinsOfProotOnly() = listOf(BinaryRole.PROOT, BinaryRole.PROOT_LOADER)
        .filter { File(nativeDir, it.fileName).isFile }
        .associate { it.fileName to NativeBinaries.sha256(File(nativeDir, it.fileName))!! }

    @Test
    fun `a crash while running a step is reported, not thrown`() {
        val crashing = object : ProcessRunner {
            override fun run(command: List<String>, environment: Map<String, String>, workingDir: File?, timeoutMillis: Long) =
                RunOutcome(null, "", "", "IOException: exec failed")
        }
        val report = SelfTestRunner(NativeBinaries(nativeDir, pins()), rootfs, null, crashing).run()
        assertEquals(StepVerdict.FAIL, report.steps[0].verdict)
        assertTrue(report.steps[0].reason!!.contains("exec failed"))
        assertEquals(3, report.failed)
    }

    @Test
    fun `the report carries a log excerpt and the failing command for the UI`() {
        val report = runner(mapOf("--version" to RunOutcome(1, "", "box64: bad CPU feature"), "hello" to RunOutcome(0, "hello", ""))).run()
        val failure = report.firstFailure
        assertNotNull(failure)
        assertTrue(report.logExcerpt().contains("bad CPU feature"))
        assertTrue(failure!!.report().contains("box64 --version".replace("box64 ", "")) || failure.report().contains("--version"))
        assertTrue(failure.command.first().endsWith("libbox64.so"))
    }
}

class DiagnosticsSelfTestTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `the diagnostics screen records the report and logs the failing command`() {
        val nativeDir = tmp.newFolder("lib")
        val rootfs = tmp.newFolder("rootfs")
        File(rootfs, "bin").mkdirs()
        File(rootfs, "usr").mkdirs()
        listOf(BinaryRole.BOX64, BinaryRole.PROOT, BinaryRole.PROOT_LOADER).forEach { role ->
            File(nativeDir, role.fileName).writeBytes(ByteArray(512) { 7 })
        }
        val pins = BinaryRole.entries
            .filter { File(nativeDir, it.fileName).isFile }
            .associate { it.fileName to NativeBinaries.sha256(File(nativeDir, it.fileName))!! }
        val log = LogBuffer()
        val state = DiagnosticsState(
            binaries = NativeBinaries(nativeDir, pins),
            log = log,
            rootfsDir = rootfs,
            runner = object : ProcessRunner {
                override fun run(
                    command: List<String>,
                    environment: Map<String, String>,
                    workingDir: File?,
                    timeoutMillis: Long,
                ) = RunOutcome(1, "", "wine64: not found")
            },
        )

        assertFalse(state.selfTestRunning)
        val report = state.runSelfTest()
        assertEquals(report, state.selfTest)
        assertEquals(3, state.selfTestSteps().size)
        val text = log.tail(20)
        assertTrue(text.contains("self-test:"))
        assertTrue("the failing command must be in the log", text.contains("failing command:"))
        assertTrue(text.contains("log excerpt:"))
        assertTrue(log.filter(LineKind.ERROR).isNotEmpty())
    }
}
