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

    @Test
    fun `a healthy arm64 rootfs passes every blocking check`() {
        val report = ProvisionDoctor.run(shippedBinaries(), healthyRootfs(), tmp.root, reachability = ok200)
        assertTrue(report.checks.joinToString { it.line() }, report.ok)
        assertEquals(null, report.firstFailure)
        assertEquals(8, report.passes)
        assertTrue(report.headline().contains("All prerequisites pass"))
    }

    @Test
    fun `includeNetwork=false skips the reachability probe`() {
        val report = ProvisionDoctor.run(
            shippedBinaries(), healthyRootfs(), tmp.root,
            includeNetwork = false,
            reachability = ProvisionDoctor.Reachability { throw AssertionError("must not probe") },
        )
        assertTrue(report.ok)
        assertTrue(report.checks.none { it.id == "network" })
    }

    @Test
    fun `a missing rootfs is the first blocking failure, in plain language`() {
        val report = ProvisionDoctor.run(
            shippedBinaries(), File(tmp.root, "nope"), tmp.root,
            includeNetwork = false,
        )
        assertFalse(report.ok)
        val first = report.firstBlockingFailure
        assertNotNull(first)
        assertEquals("rootfs-extracted", first!!.id)
        assertTrue(report.headline().contains("not extracted"))
        assertTrue(report.headline().contains("Runtime download"))
    }

    @Test
    fun `an x86-64 rootfs is refused before apt ever runs`() {
        val report = ProvisionDoctor.run(
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
        val report = ProvisionDoctor.run(shippedBinaries(), rootfs, tmp.root, includeNetwork = false)
        assertFalse(report.ok)
        assertEquals("apt-sources", report.firstBlockingFailure!!.id)
        assertTrue(report.checks.first { it.id == "apt-sources" }.detail.contains("ports.ubuntu.com"))
    }

    @Test
    fun `an empty sources list fails with the reason`() {
        val rootfs = healthyRootfs()
        File(rootfs, "etc/apt/sources.list").writeText("# nothing here\n")
        val report = ProvisionDoctor.run(shippedBinaries(), rootfs, tmp.root, includeNetwork = false)
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
        val report = ProvisionDoctor.run(shippedBinaries(), rootfs, tmp.root, includeNetwork = false)
        val dns = report.checks.first { it.id == "dns" }
        assertEquals(CheckState.WARN, dns.state)
        assertTrue(dns.detail.contains("symlink"))
        assertTrue("a symlinked resolv.conf must not block provisioning", report.ok)
    }

    @Test
    fun `an unreachable archive warns without blocking, a 200 passes`() {
        val down = ProvisionDoctor.run(
            shippedBinaries(), healthyRootfs(), tmp.root,
            reachability = ProvisionDoctor.Reachability { "unreachable: SocketTimeoutException: connect timed out" },
        )
        val net = down.checks.first { it.id == "network" }
        assertEquals(CheckState.WARN, net.state)
        assertFalse(net.blocking)
        assertTrue("host TLS failing must not block the guest's own http attempt", down.ok)

        val up = ProvisionDoctor.run(shippedBinaries(), healthyRootfs(), tmp.root, reachability = ok200)
        assertEquals(CheckState.PASS, up.checks.first { it.id == "network" }.state)
    }

    @Test
    fun `provision refuses through the doctor without invoking apt`() {
        val runner = RecordingRunner()
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
        assertTrue("apt must not even be attempted", runner.commands.isEmpty())
        assertTrue(result.message.contains("ports.ubuntu.com"))
        // The card payload carries every doctor row, and the log too.
        assertTrue(result.tailLines.any { it.startsWith("FAIL apt-sources") })
        assertTrue(
            log.snapshot().any { it.kind == dev.win11we.shell.ui.terminal.LineKind.ERROR && it.text.contains("apt-sources") },
        )
    }

    private class RecordingRunner : ProcessRunner {
        val commands = mutableListOf<List<String>>()
        override fun run(command: List<String>, environment: Map<String, String>, workingDir: File?, timeoutMillis: Long): RunOutcome {
            commands.add(command)
            return RunOutcome(0, "", "")
        }
    }
}
