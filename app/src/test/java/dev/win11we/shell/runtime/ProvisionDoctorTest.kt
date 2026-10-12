package dev.win11we.shell.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The Provision doctor: every host-side prerequisite for apt-inside-proot, each
 * named in plain language, the first blocking failure promoted to the headline.
 * Pure file checks plus the [ProvisionDoctor.Reachability] seam - no proot, no
 * network, nothing spawned.
 */
class ProvisionDoctorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Fixture names must be unique: one test builds two reports from fresh dirs. */
    private var fixtureSeq = 0

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

    /** Writes a minimal, healthy-looking extracted ubuntu-base arm64 rootfs. */
    private fun healthyRootfs(eMachine: Int = 183): File {
        val rootfs = tmp.newFolder("rootfs${fixtureSeq++}")
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "usr/bin/apt-get").writeText("#!/bin/sh\n")
        // A real ELF header prefix: e_machine is the 2 bytes at offset 18, little-endian.
        val elf = ByteArray(20)
        elf[0] = 0x7F; elf[1] = 'E'.code.toByte(); elf[2] = 'L'.code.toByte(); elf[3] = 'F'.code.toByte()
        elf[4] = 2 // ELFCLASS64
        elf[5] = 1 // ELFDATA2LSB
        elf[18] = (eMachine and 0xFF).toByte()
        elf[19] = ((eMachine shr 8) and 0xFF).toByte()
        File(rootfs, "usr/bin/bash").writeBytes(elf)
        File(rootfs, "etc/apt").mkdirs()
        File(rootfs, "etc/apt/sources.list").writeText(
            "deb http://ports.ubuntu.com/ubuntu-ports/ jammy main restricted\n" +
                "deb http://ports.ubuntu.com/ubuntu-ports/ jammy-updates main restricted\n",
        )
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/resolv.conf").writeText("nameserver 1.1.1.1\n")
        return rootfs
    }

    private val ok200 = ProvisionDoctor.Reachability { "HTTP 200" }

    /** The doctor now really execs proot; tests inject this instead of spawning. */
    private fun doctor(
        binaries: NativeBinaries,
        rootfs: File,
        filesDir: File,
        includeNetwork: Boolean = true,
        reachability: ProvisionDoctor.Reachability = ok200,
        includeProotSmoke: Boolean = true,
        runner: ProcessRunner = FakeProot(),
    ) = ProvisionDoctor.run(
        binaries, rootfs, filesDir,
        includeNetwork = includeNetwork,
        reachability = reachability,
        includeProotSmoke = includeProotSmoke,
        runner = runner,
    )

    @Test
    fun `a healthy arm64 rootfs passes every blocking check`() {
        val report = doctor(shippedBinaries(), healthyRootfs(), tmp.root)
        assertTrue(report.checks.joinToString { it.line() }, report.ok)
        assertEquals(null, report.firstFailure)
        // 8 host-side file checks + the proot smoke test + the reachability probe.
        assertEquals(10, report.passes)
        assertTrue(report.headline().contains("All prerequisites pass"))
    }

    @Test
    fun `includeNetwork=false skips the reachability probe`() {
        val report = doctor(
            shippedBinaries(), healthyRootfs(), tmp.root,
            includeNetwork = false,
            reachability = ProvisionDoctor.Reachability { throw AssertionError("must not probe") },
        )
        assertTrue(report.ok)
        assertTrue(report.checks.none { it.id == "network" })
    }

    @Test
    fun `a missing rootfs is the first blocking failure, in plain language`() {
        val runner = FakeProot()
        val report = doctor(
            shippedBinaries(), File(tmp.root, "nope"), tmp.root,
            includeNetwork = false,
            runner = runner,
        )
        assertTrue("no point exec'ing proot without a rootfs", runner.commands.isEmpty())
        assertFalse(report.ok)
        val first = report.firstBlockingFailure
        assertNotNull(first)
        assertEquals("rootfs-extracted", first!!.id)
        assertTrue(report.headline().contains("not extracted"))
        assertTrue(report.headline().contains("Runtime download"))
    }

    @Test
    fun `an x86-64 rootfs is refused before apt ever runs`() {
        val report = doctor(
            shippedBinaries(), healthyRootfs(eMachine = 62), tmp.root,
            includeNetwork = false,
        )
        assertFalse(report.ok)
        assertEquals("rootfs-arch", report.firstBlockingFailure!!.id)
        assertTrue(report.checks.first { it.id == "rootfs-arch" }.detail.contains("x86-64"))
    }

    @Test
    fun `x86 archive sources are named as the wrong flavour`() {
        val rootfs = healthyRootfs()
        File(rootfs, "etc/apt/sources.list").writeText(
            "deb http://archive.ubuntu.com/ubuntu jammy main\n" +
                "deb http://security.ubuntu.com/ubuntu jammy-security main\n",
        )
        val report = doctor(shippedBinaries(), rootfs, tmp.root, includeNetwork = false)
        assertFalse(report.ok)
        assertEquals("apt-sources", report.firstBlockingFailure!!.id)
        assertTrue(report.checks.first { it.id == "apt-sources" }.detail.contains("ports.ubuntu.com"))
    }

    @Test
    fun `an empty sources list fails with the reason`() {
        val rootfs = healthyRootfs()
        File(rootfs, "etc/apt/sources.list").writeText("# nothing here\n")
        val report = doctor(shippedBinaries(), rootfs, tmp.root, includeNetwork = false)
        assertTrue(report.checks.first { it.id == "apt-sources" }.detail.contains("No active 'deb' lines"))
    }

    @Test
    fun `a symlinked resolv conf warns and stays non-blocking`() {
        val rootfs = healthyRootfs()
        File(rootfs, "etc/resolv.conf").delete()
        java.nio.file.Files.createSymbolicLink(
            File(rootfs, "etc/resolv.conf").toPath(),
            File("../run/systemd/resolve/stub-resolv.conf").toPath(),
        )
        val report = doctor(shippedBinaries(), rootfs, tmp.root, includeNetwork = false)
        val dns = report.checks.first { it.id == "dns" }
        assertEquals(CheckState.WARN, dns.state)
        assertTrue(dns.detail.contains("symlink"))
        assertTrue("a symlinked resolv.conf must not block provisioning", report.ok)
    }

    @Test
    fun `an unreachable archive warns without blocking, a 200 passes`() {
        val down = doctor(
            shippedBinaries(), healthyRootfs(), tmp.root,
            reachability = ProvisionDoctor.Reachability { "unreachable: SocketTimeoutException: connect timed out" },
        )
        val net = down.checks.first { it.id == "network" }
        assertEquals(CheckState.WARN, net.state)
        assertFalse(net.blocking)
        assertTrue("host TLS failing must not block the guest's own http attempt", down.ok)

        val up = doctor(shippedBinaries(), healthyRootfs(), tmp.root, reachability = ok200)
        assertEquals(CheckState.PASS, up.checks.first { it.id == "network" }.state)
    }

    @Test
    fun `an interrupted dpkg state warns without blocking - provision repairs it`() {
        val rootfs = healthyRootfs()
        File(rootfs, "var/lib/dpkg/updates").mkdirs()
        File(rootfs, "var/lib/dpkg/updates/0001").writeText("half-configured")
        val report = doctor(shippedBinaries(), rootfs, tmp.root, includeNetwork = false)
        val dpkg = report.checks.first { it.id == "dpkg-state" }
        assertEquals(CheckState.WARN, dpkg.state)
        assertFalse(dpkg.blocking)
        assertTrue(dpkg.detail.contains("dpkg --configure -a"))
        assertTrue(report.ok)
    }

    @Test
    fun `provision refuses through the doctor without invoking apt`() {
        val runner = FakeProot()
        val rootfs = healthyRootfs()
        File(rootfs, "etc/apt/sources.list").writeText("deb http://archive.ubuntu.com/ubuntu jammy main\n")
        val log = dev.win11we.shell.ui.terminal.LogBuffer()
        val result = RootfsProvisioner.provision(
            binaries = shippedBinaries(),
            rootfsDir = rootfs,
            filesDir = tmp.root,
            log = log,
            runner = runner,
        )
        assertFalse(result.success)
        assertTrue("apt must not even be attempted", runner.aptCommands.isEmpty())
        assertTrue(result.message.contains("ports.ubuntu.com"))
        // The card payload carries every doctor row, and the log too.
        assertTrue(result.tailLines.any { it.startsWith("FAIL apt-sources") })
        assertTrue(
            log.snapshot().any { it.kind == dev.win11we.shell.ui.terminal.LineKind.ERROR && it.text.contains("apt-sources") },
        )
    }

    // --- the real proot smoke test (hard-won fact: a green file-check report
    // --- proves nothing about proot itself) ---------------------------------

    @Test
    fun `the doctor really execs proot - three smoke steps in the jail's own argument form`() {
        val binaries = shippedBinaries()
        val rootfs = healthyRootfs()
        val runner = FakeProot()
        val report = doctor(binaries, rootfs, tmp.root, includeNetwork = false, runner = runner)

        assertEquals(RootfsProvisioner.SMOKE_STEPS.size, runner.commands.size)
        val exec = runner.smokeCommand("exec")
        assertNotNull("the smoke test must really run /bin/true inside proot", exec)
        assertEquals(File(binaries.nativeLibraryDir, BinaryRole.PROOT.fileName).absolutePath, exec!!.first())
        // Short options only: the vendored termux/proot rejects a detached long
        // option value (RuntimeTest pins the same rule for ProotCommand).
        listOf("--rootfs", "--bind", "--cwd", "--rootfs=").forEach { long ->
            assertFalse("never emit '$long' - termux/proot refuses detached long option values", exec.contains(long))
        }
        // And no end-of-options marker: this fork's option table has no "--" entry,
        // so it aborts with `proot error: unknown option "--"` (seen on a device).
        assertFalse("termux/proot has no '--' separator: $exec", exec.contains("--"))
        assertTrue(exec.containsAll(listOf("-r", "-0", "--link2symlink", "--kill-on-exit", "-b", "-w")))
        assertEquals(rootfs.absolutePath, exec[exec.indexOf("-r") + 1])
        assertEquals("/root", exec[exec.indexOf("-w") + 1])
        assertTrue("the jail must see /proc, /sys and /dev", exec.containsAll(listOf("/proc:/proc", "/sys:/sys", "/dev:/dev")))
        assertTrue(exec.any { it.endsWith(":/etc/resolv.conf") })
        // The guest command follows the last option directly.
        assertEquals(listOf("/bin/true"), exec.subList(exec.indexOf("-w") + 2, exec.size))

        val smoke = report.checks.first { it.id == "proot-smoke" }
        assertEquals(report.checks.joinToString { it.line() }, CheckState.PASS, smoke.state)
        assertTrue(smoke.detail.contains("uid=0"))
        assertTrue(smoke.detail.contains("apt 2.4.13"))
    }

    @Test
    fun `the smoke test runs with provision's own environment`() {
        val binaries = shippedBinaries()
        val runner = FakeProot()
        doctor(binaries, healthyRootfs(), tmp.root, includeNetwork = false, runner = runner)
        val env = runner.environments.first()
        assertEquals("1", env["PROOT_NO_SECCOMP"])
        assertTrue("proot needs its loader path to exec anything", env.containsKey("PROOT_LOADER"))
        assertEquals(binaries.nativeLibraryDir.absolutePath, env["LD_LIBRARY_PATH"])
        assertEquals(File(tmp.root, "tmp").absolutePath, env["TMPDIR"])
        assertEquals(env.keys.toList(), env.keys.sorted())
    }

    @Test
    fun `a proot that cannot be started names the cause and prints the command`() {
        val runner = FakeProot(failStep = "exec", failWith = RunOutcome(null, "", "", "IOException: Permission denied"))
        val report = doctor(shippedBinaries(), healthyRootfs(), tmp.root, includeNetwork = false, runner = runner)
        assertFalse(report.ok)
        val smoke = report.checks.first { it.id == "proot-smoke" }
        assertEquals(CheckState.FAIL, smoke.state)
        assertTrue(smoke.detail.contains("could not be started"))
        assertTrue(smoke.detail.contains("Permission denied"))
        assertTrue("the failing command must be reproducible", smoke.detail.contains("/bin/true"))
        assertTrue(report.headline().contains("proot smoke test"))
        assertEquals("later steps are pointless once proot itself fails", 1, runner.commands.size)
    }

    @Test
    fun `a missing guest binary is named instead of a bare apt failure`() {
        val runner = FakeProot(failStep = "fake-root", failWith = RunOutcome(127, "", "/usr/bin/id: not found"))
        val report = doctor(shippedBinaries(), healthyRootfs(), tmp.root, includeNetwork = false, runner = runner)
        val smoke = report.checks.first { it.id == "proot-smoke" }
        assertEquals(CheckState.FAIL, smoke.state)
        assertTrue(smoke.detail.contains("missing from the rootfs"))
        assertTrue(smoke.detail.contains("/usr/bin/id"))
        assertFalse(report.ok)
    }

    @Test
    fun `exec format error names a wrong-architecture rootfs`() {
        val runner = FakeProot(failStep = "exec", failWith = RunOutcome(126, "", "proot error: execve(\"/bin/true\"): Exec format error"))
        val report = doctor(shippedBinaries(), healthyRootfs(), tmp.root, includeNetwork = false, runner = runner)
        val smoke = report.checks.first { it.id == "proot-smoke" }
        assertEquals(CheckState.FAIL, smoke.state)
        assertTrue(smoke.detail.contains("Exec format error"))
        assertTrue(smoke.detail.contains("arm64"))
    }

    @Test
    fun `a hung proot is reported as a hang with the ptrace hint, never as a pass`() {
        val runner = FakeProot(failStep = "exec", failWith = RunOutcome(null, "", "", "Timed out after 20000ms"))
        val report = doctor(shippedBinaries(), healthyRootfs(), tmp.root, includeNetwork = false, runner = runner)
        val smoke = report.checks.first { it.id == "proot-smoke" }
        assertEquals(CheckState.FAIL, smoke.state)
        assertTrue(smoke.detail.contains("hung"))
        assertTrue(smoke.detail.contains("PROOT_NO_SECCOMP=1"))
        assertFalse(report.ok)
    }

    @Test
    fun `fake root that does not report uid 0 blocks provisioning, because dpkg would refuse`() {
        val runner = FakeProot(failStep = "fake-root", failWith = RunOutcome(0, "uid=10123(u0_a123) gid=10123(u0_a123) groups=10123", ""))
        val report = doctor(shippedBinaries(), healthyRootfs(), tmp.root, includeNetwork = false, runner = runner)
        val smoke = report.checks.first { it.id == "proot-smoke" }
        assertEquals(CheckState.FAIL, smoke.state)
        assertTrue(smoke.detail.contains("expected 'uid=0'"))
        assertTrue(smoke.detail.contains("uid=10123"))
        assertTrue(smoke.detail.contains("superuser privilege"))
        assertFalse("apt would die on the dpkg lock, so this is blocking", report.ok)
        assertEquals("the apt-get step is not attempted once fake root is broken", 2, runner.commands.size)
    }

    @Test
    fun `apt-get that answers nothing is a failure, not a pass`() {
        val runner = FakeProot(failStep = "apt-get", failWith = RunOutcome(0, "", ""))
        val report = doctor(shippedBinaries(), healthyRootfs(), tmp.root, includeNetwork = false, runner = runner)
        val smoke = report.checks.first { it.id == "proot-smoke" }
        assertEquals(CheckState.FAIL, smoke.state)
        assertTrue(smoke.detail.contains("expected 'apt'"))
        assertTrue(smoke.detail.contains("Provision has nothing to call"))
        assertFalse(report.ok)
    }

    @Test
    fun `the smoke test is skipped, not faked, when an earlier prerequisite already failed`() {
        val runner = FakeProot()
        val rootfs = healthyRootfs()
        File(rootfs, "etc/apt/sources.list").writeText("deb http://archive.ubuntu.com/ubuntu jammy main\n")
        val report = doctor(shippedBinaries(), rootfs, tmp.root, includeNetwork = false, runner = runner)
        val smoke = report.checks.first { it.id == "proot-smoke" }
        assertEquals(CheckState.WARN, smoke.state)
        assertFalse(smoke.blocking)
        assertTrue(smoke.detail.contains("not run"))
        assertTrue(smoke.detail.contains("apt-sources"))
        assertTrue("nothing may be spawned when a file check already failed", runner.commands.isEmpty())
        assertEquals("apt-sources", report.firstBlockingFailure!!.id)
    }

    @Test
    fun `includeProotSmoke=false spawns nothing - the row is simply absent`() {
        val runner = FakeProot()
        val report = doctor(
            shippedBinaries(), healthyRootfs(), tmp.root,
            includeNetwork = false, includeProotSmoke = false, runner = runner,
        )
        assertTrue(runner.commands.isEmpty())
        assertTrue(report.checks.none { it.id == "proot-smoke" })
        assertTrue(report.ok)
    }

    @Test
    fun `provision refuses when the smoke test fails, without ever running apt`() {
        val runner = FakeProot(failStep = "exec", failWith = RunOutcome(1, "", "proot error: ptrace(TRACEME): Operation not permitted"))
        val log = dev.win11we.shell.ui.terminal.LogBuffer()
        val result = RootfsProvisioner.provision(
            binaries = shippedBinaries(),
            rootfsDir = healthyRootfs(),
            filesDir = tmp.root,
            log = log,
            runner = runner,
        )
        assertFalse(result.success)
        assertTrue("apt must not be attempted when proot cannot exec", runner.aptCommands.isEmpty())
        assertTrue(result.message.contains("proot smoke test"))
        assertTrue(result.message.contains("ptrace"))
        assertTrue(result.tailLines.any { it.startsWith("FAIL proot-smoke") })
        assertTrue(
            log.snapshot().any {
                it.kind == dev.win11we.shell.ui.terminal.LineKind.ERROR && it.text.contains("proot-smoke")
            },
        )
    }

    @Test
    fun `dpkg's required tools missing from the guest PATH is named before Provision wastes minutes`() {
        // The real device failure: apt-get installs nothing because dpkg aborts with
        // "error: 2 expected programs not found in PATH" - an environment bug that
        // only surfaced as exit 100 minutes into a provision.
        val runner = FakeProot(
            failStep = "dpkg-tools",
            failWith = RunOutcome(1, "", "command -v: ldconfig: not found"),
        )
        val report = doctor(shippedBinaries(), healthyRootfs(), tmp.root, includeNetwork = false, runner = runner)
        val smoke = report.checks.first { it.id == "proot-smoke" }
        assertEquals(CheckState.FAIL, smoke.state)
        assertTrue(smoke.detail.contains("dpkg requires ldconfig and start-stop-daemon"))
        assertTrue(smoke.detail.contains("/sbin"))
        assertFalse(report.ok)
        assertEquals("the four steps run in order and stop at the first failure",
            RootfsProvisioner.SMOKE_STEPS.size, runner.commands.size)
    }

    @Test
    fun `the smoke command shares provision's proot prefix - same binds, same fake root`() {
        val binaries = shippedBinaries()
        val rootfs = healthyRootfs()
        val resolv = File(tmp.root, "tmp/resolv.conf")
        RootfsProvisioner.ensureResolvConf(resolv)
        val proot = File(binaries.nativeLibraryDir, BinaryRole.PROOT.fileName)
        val apt = RootfsProvisioner.command(proot, rootfs, resolv)
        val prefixEnd = apt.indexOf("-w") + 2 // everything up to and including "-w /root"
        RootfsProvisioner.SMOKE_STEPS.forEach { step ->
            val smoke = RootfsProvisioner.smokeCommand(proot, rootfs, resolv, step.guestCommand)
            assertEquals("the smoke test must exercise the same jail",
                apt.subList(0, prefixEnd), smoke.subList(0, prefixEnd))
            assertEquals(step.guestCommand, smoke.subList(prefixEnd, smoke.size))
        }
    }

    /**
     * A proot that behaves: the doctor's three smoke steps answer like a healthy
     * arm64 jail (uid 0, apt-get present) and the apt pass answers [aptOutcome].
     * [failStep] makes one named smoke step fail instead, which is how the
     * "the doctor catches what apt would have died on" cases are built.
     */
    private class FakeProot(
        private val aptOutcome: RunOutcome = RunOutcome(0, "Reading package lists... Done", ""),
        private val failStep: String? = null,
        private val failWith: RunOutcome = RunOutcome(1, "", "boom"),
    ) : ProcessRunner {
        val commands = mutableListOf<List<String>>()
        val environments = mutableListOf<Map<String, String>>()

        /** Only the real apt pass; the smoke commands are recorded in [commands]. */
        val aptCommands: List<List<String>> get() = commands.filter { it.last().contains("dpkg --configure -a") }

        /** The smoke command for one [RootfsProvisioner.SMOKE_STEPS] id. */
        fun smokeCommand(stepId: String): List<String>? {
            val step = RootfsProvisioner.SMOKE_STEPS.first { it.id == stepId }
            return commands.firstOrNull { it.takeLast(step.guestCommand.size) == step.guestCommand }
        }

        override fun run(command: List<String>, environment: Map<String, String>, workingDir: File?, timeoutMillis: Long): RunOutcome {
            commands.add(command)
            environments.add(environment)
            val step = RootfsProvisioner.SMOKE_STEPS.firstOrNull { command.takeLast(it.guestCommand.size) == it.guestCommand }
            if (step != null) {
                if (step.id == failStep) return failWith
                return when (step.id) {
                    "exec" -> RunOutcome(0, "", "")
                    "fake-root" -> RunOutcome(0, "uid=0(root) gid=0(root) groups=0(root)", "")
                    "dpkg-tools" -> RunOutcome(0, "/sbin/ldconfig\n/sbin/start-stop-daemon", "")
                    else -> RunOutcome(0, "apt 2.4.13 (arm64)", "")
                }
            }
            return aptOutcome
        }
    }

    private val healthyProot get() = FakeProot()
}
