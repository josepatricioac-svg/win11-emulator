package dev.win11we.shell.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The X self-test's command planning and verdict logic through the
 * [ProcessRunner] seam. The verdict rule is the point: a healthy X client
 * never exits by itself, so "still running at the timeout" is PASS and every
 * exit code is FAIL with the trace attached - never the other way round.
 */
class XSelfTestTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Fixture names must be unique: several tests build two rootfs/binaries sets. */
    private var fixtureSeq = 0

    private class FakeRunner(private val responder: (List<String>) -> RunOutcome) : ProcessRunner {
        val commands = mutableListOf<List<String>>()
        val environments = mutableListOf<Map<String, String>>()
        override fun run(command: List<String>, environment: Map<String, String>, workingDir: File?, timeoutMillis: Long): RunOutcome {
            commands.add(command)
            environments.add(environment)
            return responder(command)
        }
    }

    private val timedOut = RunOutcome(null, "", "", "Timed out after 6000ms")
    private val xdpyinfoOk = RunOutcome(
        0,
        "name of display:    :100\nnumber of extensions: 4\n    RENDER\n    XTEST\nRENDER (opcode 129), version 0.4\n",
        "",
    )
    private val xwininfoOk = RunOutcome(0, "xwininfo: Window id: 0x200000 (root window)", "")

    /** Survivors stay alive (timeout = PASS); the probes answer immediately. */
    private fun healthyRunner() = FakeRunner { command ->
        when {
            command.any { it.contains("xdpyinfo") } -> xdpyinfoOk
            command.any { it.contains("xwininfo") } -> xwininfoOk
            else -> timedOut
        }
    }

    private fun shippedBinaries(): NativeBinaries {
        val dir = tmp.newFolder("nativelib${fixtureSeq++}")
        listOf(BinaryRole.PROOT, BinaryRole.PROOT_LOADER, BinaryRole.TALLOC).forEach { role ->
            val file = File(dir, role.fileName)
            file.writeBytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) + ByteArray(2044))
        }
        val pins = BinaryRole.entries
            .filter { File(dir, it.fileName).isFile }
            .associate { it.fileName to NativeBinaries.sha256(File(dir, it.fileName))!! }
        return NativeBinaries(dir, pins)
    }

    private fun rootfs(withClients: Boolean = true): File {
        val rootfs = tmp.newFolder("rootfs${fixtureSeq++}")
        File(rootfs, "bin").mkdirs()
        File(rootfs, "usr/bin").mkdirs()
        if (withClients) {
            listOf("xeyes", "xclock", "xdpyinfo", "xwininfo").forEach { name ->
                File(rootfs, "usr/bin/$name").writeText("#!/bin/sh\n")
            }
        }
        return rootfs
    }

    private fun test(
        runner: ProcessRunner,
        binaries: NativeBinaries = shippedBinaries(),
        rootfsDir: File = rootfs(),
        serverRunning: Boolean = true,
        trace: String? = "x11[client#1] 42 requests, 0 errors sent",
    ) = XSelfTest(
        binaries = binaries,
        rootfsDir = rootfsDir,
        filesDir = tmp.root,
        runner = runner,
        clientMillis = 6_000L,
        xServerRunning = { serverRunning },
        traceProvider = { trace },
    )

    @Test
    fun `surviving until the timeout is PASS and carries the trace`() {
        val report = test(healthyRunner()).run()
        assertEquals(4, report.steps.size)
        assertEquals(listOf("xdpyinfo", "xwininfo", "xeyes", "xclock"), report.steps.map { it.id })
        val probeStep = report.steps[0]
        assertEquals(probeStep.reason ?: "", StepVerdict.PASS, probeStep.verdict)
        assertTrue(probeStep.reason!!.contains("RENDER is advertised"))
        assertTrue("the trace must ride along", probeStep.output.contains("x11 trace: x11[client#1]"))
        assertEquals(StepVerdict.PASS, report.steps[1].verdict)
        report.steps.drop(2).forEach { step ->
            assertEquals(step.reason ?: "", StepVerdict.PASS, step.verdict)
            assertTrue(step.reason!!.contains("still connected"))
            assertTrue(step.output.contains("x11 trace: x11[client#1]"))
        }
        assertTrue(report.allGreen)
    }

    @Test
    fun `the probe separates a missing RENDER from a broken socket`() {
        val noRender = FakeRunner { command ->
            when {
                command.any { it.contains("xdpyinfo") } ->
                    RunOutcome(0, "name of display:    :100\nnumber of extensions: 1\n    XTEST\n", "")
                command.any { it.contains("xwininfo") } -> xwininfoOk
                else -> timedOut
            }
        }
        val report = test(noRender, trace = null).run()
        assertEquals(StepVerdict.FAIL, report.steps[0].verdict)
        assertTrue(report.steps[0].reason!!.contains("did not advertise RENDER"))
        // the other probe and the survivors still pass: the socket path itself works
        assertEquals(StepVerdict.PASS, report.steps[1].verdict)
        assertEquals(StepVerdict.PASS, report.steps[2].verdict)

        val hung = FakeRunner { RunOutcome(null, "", "", "Timed out after 10000ms") }
        val hungReport = test(hung, trace = null).run()
        assertEquals(StepVerdict.FAIL, hungReport.steps[0].verdict)
        assertTrue(hungReport.steps[0].reason!!.contains("hung"))
        assertEquals(StepVerdict.FAIL, hungReport.steps[1].verdict)
        // a timeout is a PASS for the survivors but a FAIL for the probes
        assertEquals(StepVerdict.PASS, hungReport.steps[2].verdict)
    }

    @Test
    fun `any exit code is FAIL - even zero`() {
        val crashed = FakeRunner { RunOutcome(1, "", "XIO: fatal IO error 104\nError: can't open display :100") }
        val crashReport = test(crashed, trace = null).run()
        crashReport.steps.forEach { step ->
            assertEquals(StepVerdict.FAIL, step.verdict)
            assertTrue(step.reason!!.contains("exit 1"))
            assertTrue(step.output.contains("can't open display"))
        }

        // Survivors exiting cleanly is a failure; a probe's exit-0 verdict is
        // decided by its output: "goodbye RENDER" satisfies xdpyinfo's token but
        // not xwininfo's "Window id", so the two probes disagree - both honest.
        val cleanExit = test(FakeRunner { RunOutcome(0, "goodbye RENDER", "") }, trace = null).run()
        assertEquals(StepVerdict.PASS, cleanExit.steps[0].verdict)
        assertEquals(StepVerdict.FAIL, cleanExit.steps[1].verdict)
        assertTrue(cleanExit.steps[1].reason!!.contains("Window id"))
        cleanExit.steps.drop(2).forEach { step ->
            assertEquals(StepVerdict.FAIL, step.verdict)
            assertTrue(step.reason!!.contains("exited immediately"))
        }
    }

    @Test
    fun `missing prerequisites skip every client with the reason named`() {
        val serverDown = test(healthyRunner(), serverRunning = false).run()
        serverDown.steps.forEach { step ->
            assertEquals(StepVerdict.SKIPPED, step.verdict)
            assertTrue(step.reason!!.contains("X Desktop"))
        }

        val noClients = test(healthyRunner(), rootfsDir = rootfs(withClients = false)).run()
        assertEquals(4, noClients.steps.size)
        noClients.steps.take(2).forEach { step ->
            assertEquals(StepVerdict.SKIPPED, step.verdict)
            assertTrue(step.reason!!.contains("x11-utils"))
        }
        noClients.steps.drop(2).forEach { step ->
            assertEquals(StepVerdict.SKIPPED, step.verdict)
            assertTrue(step.reason!!.contains("x11-apps"))
        }

        val noRootfs = test(healthyRunner(), rootfsDir = File(tmp.root, "missing")).run()
        assertTrue(noRootfs.steps.all { it.verdict == StepVerdict.SKIPPED })
        assertTrue(noRootfs.steps.first().reason!!.contains("Rootfs not extracted"))
    }

    @Test
    fun `the command is proot with the X socket bind and DISPLAY`() {
        val runner = healthyRunner()
        val binaries = shippedBinaries()
        val selfTest = test(runner, binaries = binaries)
        val report = selfTest.run()
        assertEquals(StepVerdict.PASS, report.steps.first().verdict)
        // The first two commands are the probes; xeyes is the third.
        assertTrue(runner.commands[0].any { it.contains("xdpyinfo") })
        assertTrue(runner.commands[0].containsAll(listOf("-ext", "RENDER")))
        assertTrue(runner.commands[1].any { it.contains("xwininfo") })
        assertTrue(runner.commands[1].contains("-root"))
        val command = runner.commands[2]
        assertEquals(File(binaries.nativeLibraryDir, BinaryRole.PROOT.fileName).absolutePath, command.first())
        assertTrue(command.contains("-r"))
        assertTrue(command.contains("--link2symlink"))
        // The X socket dir bind is what makes /tmp/.X11-unix exist inside the guest.
        val bindIndex = command.indexOf("${tmp.root.absolutePath}/tmp/.X11-unix:/tmp/.X11-unix")
        assertTrue("x11 socket dir bind missing: $command", bindIndex >= 0)
        assertTrue(command.containsAll(listOf("/usr/bin/xeyes", "-display", ":100")))
        val env = runner.environments.first()
        assertEquals(":100", env["DISPLAY"])
        assertEquals("1", env["PROOT_NO_SECCOMP"])
        assertTrue(env.containsKey("PROOT_LOADER"))
        assertEquals(env.keys.toList(), env.keys.sorted())
        assertTrue("no box64 in this chain", command.none { it.contains("box64") })
        // The fixed /root/.wine bind is R2's contract (harmless scratch prefix here),
        // but no wine/box64 binary may ever appear in the guest command.
        assertFalse(
            "clients must never be launched through wine",
            command.any { it.endsWith("/wine") || it.endsWith("/wine64") || it.contains("box64") },
        )
    }

    @Test
    fun `a non-timeout runner error is a FAIL with the error text`() {
        val report = test(FakeRunner { RunOutcome(null, "", "", "IOException: exec failed") }, trace = null).run()
        report.steps.forEach { step ->
            assertEquals(StepVerdict.FAIL, step.verdict)
            assertTrue(step.reason!!.contains("exec failed"))
        }
    }
}
