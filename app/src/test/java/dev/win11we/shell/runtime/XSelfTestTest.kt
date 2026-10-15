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
        val timeouts = mutableListOf<Long>()
        override fun run(command: List<String>, environment: Map<String, String>, workingDir: File?, timeoutMillis: Long): RunOutcome {
            commands.add(command)
            environments.add(environment)
            timeouts.add(timeoutMillis)
            return responder(command)
        }

        /** The timeout a given guest program was run with. */
        fun timeoutFor(fragment: String): Long? =
            commands.indexOfFirst { it.any { part -> part.contains(fragment) } }.takeIf { it >= 0 }?.let { timeouts[it] }
    }

    private val timedOut = RunOutcome(null, "", "", "Timed out after 6000ms")
    private val xdpyinfoOk = RunOutcome(
        0,
        "name of display:    :100\nnumber of extensions: 4\n    RENDER\n    XTEST\nRENDER (opcode 129), version 0.4\n",
        "",
    )
    private val xwininfoOk = RunOutcome(0, "xwininfo: Window id: 0x200000 (root window)", "")
    private val gtkOk = RunOutcome(0, "gtk-theme-name: Adwaita\ngtk-icon-theme-name: Adwaita\n", "")

    /** Survivors stay alive (timeout = PASS); the probes answer immediately. */
    private fun healthyRunner() = FakeRunner { command ->
        when {
            command.any { it.contains("xdpyinfo") } -> xdpyinfoOk
            command.any { it.contains("xwininfo") } -> xwininfoOk
            command.any { it.contains("gtk-query-settings") } -> gtkOk
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
            listOf("xeyes", "xclock", "xdpyinfo", "xwininfo", "gtk-query-settings").forEach { name ->
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
        assertEquals(5, report.steps.size)
        assertEquals(
            listOf("xdpyinfo", "xwininfo", "gtk3-display", "xeyes", "xclock"),
            report.steps.map { it.id },
        )
        val probeStep = report.steps[0]
        assertEquals(probeStep.reason ?: "", StepVerdict.PASS, probeStep.verdict)
        assertTrue(probeStep.reason!!.contains("RENDER is advertised"))
        assertTrue("the trace must ride along", probeStep.output.contains("x11 trace: x11[client#1]"))
        assertEquals(StepVerdict.PASS, report.steps[1].verdict)
        assertTrue(report.steps[2].reason!!.contains("GTK3 opened the display"))
        report.steps.drop(3).forEach { step ->
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
                command.any { it.contains("gtk-query-settings") } -> gtkOk
                else -> timedOut
            }
        }
        val report = test(noRender, trace = null).run()
        assertEquals(StepVerdict.FAIL, report.steps[0].verdict)
        assertTrue(report.steps[0].reason!!.contains("did not advertise RENDER"))
        // the other probe and the survivors still pass: the socket path itself works
        assertEquals(StepVerdict.PASS, report.steps[1].verdict)
        assertEquals(StepVerdict.PASS, report.steps[3].verdict)

        val hung = FakeRunner { RunOutcome(null, "", "", "Timed out after 10000ms") }
        val hungReport = test(hung, trace = null).run()
        assertEquals(StepVerdict.FAIL, hungReport.steps[0].verdict)
        assertTrue(hungReport.steps[0].reason!!.contains("hung"))
        assertEquals(StepVerdict.FAIL, hungReport.steps[1].verdict)
        // a timeout is a PASS for the survivors but a FAIL for the probes
        assertEquals(StepVerdict.PASS, hungReport.steps[3].verdict)
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
        assertEquals("the GTK probe's verdict is the exit code alone",
            StepVerdict.PASS, cleanExit.steps[2].verdict)
        cleanExit.steps.drop(3).forEach { step ->
            assertEquals(StepVerdict.FAIL, step.verdict)
            assertTrue(step.reason!!.contains("exited immediately"))
        }
    }

    @Test
    fun `missing prerequisites skip every client with the reason named`() {
        val serverDown = test(healthyRunner(), serverRunning = false).run()
        serverDown.steps.forEach { step ->
            assertEquals(StepVerdict.SKIPPED, step.verdict)
            // The viewer app is gone: the skip reason names the server + the desktop boot.
            assertTrue(step.reason!!.contains("X server"))
        }

        val noClients = test(healthyRunner(), rootfsDir = rootfs(withClients = false)).run()
        assertEquals(5, noClients.steps.size)
        noClients.steps.take(2).forEach { step ->
            assertEquals(StepVerdict.SKIPPED, step.verdict)
            assertTrue(step.reason!!.contains("x11-utils"))
        }
        assertEquals("the GTK probe names its own package, not x11-utils",
            StepVerdict.SKIPPED, noClients.steps[2].verdict)
        assertTrue(noClients.steps[2].reason!!.contains("libgtk-3-bin"))
        noClients.steps.drop(3).forEach { step ->
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
        // The first three commands are the probes; xeyes is the fourth.
        assertTrue(runner.commands[0].any { it.contains("xdpyinfo") })
        assertTrue(runner.commands[0].containsAll(listOf("-ext", "RENDER")))
        assertTrue(runner.commands[1].any { it.contains("xwininfo") })
        assertTrue(runner.commands[1].contains("-root"))
        // GTK parses --display=, not Xlib's -display, so the flag must be absent -
        // DISPLAY travels in the environment instead.
        val gtk = runner.commands[2]
        assertTrue(gtk.any { it.endsWith("/usr/bin/gtk-query-settings") })
        assertFalse("gtk-query-settings would refuse an Xlib flag: $gtk", gtk.contains("-display"))
        assertEquals(":100", runner.environments[2]["DISPLAY"])
        val command = runner.commands[3]
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

    // --- the GTK3 probe: the one that decides whether Firefox can start ------

    @Test
    fun `the GTK3 probe gets its own longer timeout for the first-run font cache`() {
        val runner = healthyRunner()
        test(runner).run()
        assertEquals("the handshake probes answer in seconds", 10_000L, runner.timeoutFor("xdpyinfo"))
        assertEquals("the survivors get their 6s window", 6_000L, runner.timeoutFor("xeyes"))
        assertEquals(
            "GTK's first run builds the fontconfig cache inside the rootfs",
            GTK_PROBE_MILLIS, runner.timeoutFor("gtk-query-settings"),
        )
    }

    @Test
    fun `a GTK3 failure quotes GTK's own diagnosis instead of a bare exit code`() {
        val gtkDied = FakeRunner { command ->
            when {
                command.any { it.contains("xdpyinfo") } -> xdpyinfoOk
                command.any { it.contains("xwininfo") } -> xwininfoOk
                command.any { it.contains("gtk-query-settings") } -> RunOutcome(
                    1,
                    "",
                    "The XInput2 extension is missing\n" +
                        "X Window System error of type 128:\n" +
                        "  (Details: serial 412 error_code 1 request_code 131 (XInputExtension) minor_code 43)\n",
                )
                else -> timedOut
            }
        }
        val report = test(gtkDied, trace = "x11[client#3] 412 requests, 1 errors sent | unimplemented opcodes: XI2.ChangeHierarchy x1").run()
        val gtk = report.steps.first { it.id == GTK_PROBE_ID }
        assertEquals(StepVerdict.FAIL, gtk.verdict)
        assertTrue(gtk.reason!!.contains("GTK3 could not open the display"))
        assertTrue("the reason must tell the owner what to look for", gtk.reason!!.contains("names the exact X request"))
        assertTrue("GTK's own text is the evidence", gtk.output.contains("request_code 131"))
        assertTrue("and the server's trace rides along", gtk.output.contains("XI2.ChangeHierarchy"))
        assertFalse("the toolkit probe failing must not read as all-green", report.allGreen)
    }

    @Test
    fun `a hung GTK3 probe points at the font cache, not at a dead socket alone`() {
        val hung = FakeRunner { RunOutcome(null, "", "", "Timed out after ${GTK_PROBE_MILLIS}ms") }
        val report = test(hung, trace = null).run()
        val gtk = report.steps.first { it.id == GTK_PROBE_ID }
        assertEquals(StepVerdict.FAIL, gtk.verdict)
        assertTrue(gtk.reason!!.contains("hung"))
        assertTrue(gtk.reason!!.contains("fontconfig"))
    }

    @Test
    fun `the GTK3 probe is skipped, not failed, when libgtk-3-bin is not installed`() {
        val rootfs = rootfs()
        File(rootfs, "usr/bin/gtk-query-settings").delete()
        val report = test(healthyRunner(), rootfsDir = rootfs).run()
        val gtk = report.steps.first { it.id == GTK_PROBE_ID }
        assertEquals(StepVerdict.SKIPPED, gtk.verdict)
        assertTrue(gtk.reason!!.contains("libgtk-3-bin"))
        assertTrue("the skip must say how to fix it", gtk.reason!!.contains("Provision"))
        // The other four steps are unaffected: a missing diagnostic is not a red run.
        assertEquals(StepVerdict.PASS, report.steps.first { it.id == "xdpyinfo" }.verdict)
        assertEquals(StepVerdict.PASS, report.steps.first { it.id == "xclock" }.verdict)
    }

    @Test
    fun `the excerpt keeps GTK's Details line even when it falls outside the first six`() {
        // GDK's fatal handler prints a preamble; the request_code line that names
        // the missing X request comes later and is the whole point of the probe.
        val preamble = (1..10).joinToString("\n") { "Gtk-Message: $it: preamble" }
        val runner = FakeRunner { command ->
            when {
                command.any { it.contains("gtk-query-settings") } -> RunOutcome(
                    1, "",
                    preamble + "\nX Error of failed request:  BadRequest\n" +
                        "  (Details: serial 512 error_code 1 request_code 135 (XKEYBOARD) minor_code 8)\n",
                )
                command.any { it.contains("xdpyinfo") } -> xdpyinfoOk
                command.any { it.contains("xwininfo") } -> xwininfoOk
                else -> timedOut
            }
        }
        val report = test(runner, trace = null).run()
        val gtk = report.steps.first { it.id == GTK_PROBE_ID }
        assertEquals(StepVerdict.FAIL, gtk.verdict)
        assertTrue("the request_code line must survive truncation: ${gtk.output}",
            gtk.output.contains("request_code 135 (XKEYBOARD) minor_code 8"))
        assertTrue(gtk.output.contains("serial 512"))
    }

    @Test
    fun `provision installs the GTK3 probe's package alongside the browser stack`() {
        assertTrue(
            "the self-test's GTK3 probe comes from libgtk-3-bin, so Provision must install it",
            RootfsProvisioner.BROWSER_PACKAGES.contains("libgtk-3-bin"),
        )
        // noble's 64-bit time_t transition renamed this package: the jammy spelling
        // libgtk-3-0 does not exist in the 24.04 arm64 index, and one wrong name fails
        // the whole apt-get install with exit 100. Membership is exact, so this pin
        // catches a silent revert to the jammy name (a substring check would not:
        // "libgtk-3-0t64".contains("libgtk-3-0") is true).
        assertTrue(RootfsProvisioner.BROWSER_PACKAGES.contains("libgtk-3-0t64"))
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
