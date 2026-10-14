package dev.win11we.shell.runtime

import dev.win11we.shell.runtime.download.RuntimeManifest
import dev.win11we.shell.ui.terminal.LogBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WineProvisionerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var rootfs: File
    private lateinit var filesDir: File
    private lateinit var downloadDir: File
    private lateinit var lib: File
    private var fixtureSeq = 0

    private fun binaries(): NativeBinaries {
        val dir = tmp.newFolder("lib${fixtureSeq++}")
        listOf(BinaryRole.PROOT, BinaryRole.PROOT_LOADER, BinaryRole.TALLOC).forEach { role ->
            val file = File(dir, role.fileName)
            file.writeBytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) + ByteArray(2044))
        }
        lib = dir
        return NativeBinaries(
            dir,
            BinaryRole.entries.filter { File(dir, it.fileName).isFile }
                .associate { it.fileName to NativeBinaries.sha256(File(dir, it.fileName))!! },
        )
    }

    /** A minimal healthy-looking extracted ubuntu-base arm64 rootfs (doctor-passing). */
    private fun healthyRootfs(): File {
        val dir = tmp.newFolder("rootfs${fixtureSeq++}")
        File(dir, "bin").mkdirs()
        File(dir, "usr/bin").mkdirs()
        File(dir, "usr/bin/apt-get").writeText("#!/bin/sh\n")
        val elf = ByteArray(20)
        elf[0] = 0x7F; elf[1] = 'E'.code.toByte(); elf[2] = 'L'.code.toByte(); elf[3] = 'F'.code.toByte()
        elf[4] = 2; elf[5] = 1; elf[18] = 183.toByte() // EM_AARCH64
        File(dir, "usr/bin/bash").writeBytes(elf)
        File(dir, "etc/apt").mkdirs()
        File(dir, "etc/apt/sources.list").writeText(
            "deb http://ports.ubuntu.com/ubuntu-ports/ jammy main restricted\n",
        )
        File(dir, "etc/resolv.conf").writeText("nameserver 1.1.1.1\n")
        return dir
    }

    private fun placePinnedDebs() {
        downloadDir.mkdirs()
        WineProvisioner.pinnedDebs(downloadDir).forEach { it.writeBytes(ByteArray(64)) }
    }

    @Before
    fun setUp() {
        rootfs = healthyRootfs()
        filesDir = tmp.newFolder("files")
        downloadDir = File(filesDir, "downloads")
    }

    /**
     * Answers the doctor's proot smoke steps exactly like ProvisionDoctorTest's
     * fake, and hands the "real" pass (the apt/dpkg command) the configured
     * outcome - optionally materialising the payload first ([onRealRun]).
     */
    private class FakeProot(
        private val realOutcome: RunOutcome = RunOutcome(0, "Setting up wine64 (9.0~repack-4build3) ...", ""),
        private val onRealRun: (() -> Unit)? = null,
    ) : ProcessRunner {
        val commands = mutableListOf<List<String>>()
        override fun run(command: List<String>, environment: Map<String, String>, workingDir: File?, timeoutMillis: Long): RunOutcome {
            commands += command
            val step = RootfsProvisioner.SMOKE_STEPS.firstOrNull { command.takeLast(it.guestCommand.size) == it.guestCommand }
            if (step != null) {
                return when (step.id) {
                    "exec" -> RunOutcome(0, "", "")
                    "fake-root" -> RunOutcome(0, "uid=0(root) gid=0(root) groups=0(root)", "")
                    "dpkg-tools" -> RunOutcome(0, "/sbin/ldconfig\n/sbin/start-stop-daemon", "")
                    else -> RunOutcome(0, "apt 2.4.13 (arm64)", "")
                }
            }
            onRealRun?.invoke()
            return realOutcome
        }
    }

    // --- the pinned-deb path -----------------------------------------------------

    @Test
    fun `pinnedDebs are exactly the two R3 manifest files`() {
        val names = WineProvisioner.pinnedDebs(downloadDir).map { it.name }
        assertEquals(
            listOf(RuntimeManifest.WINE64_DEB.fileName, RuntimeManifest.WINE_ALL_DEB.fileName),
            names,
        )
        assertFalse(WineProvisioner.hasPinnedDebs(downloadDir))
        placePinnedDebs()
        assertTrue(WineProvisioner.hasPinnedDebs(downloadDir))
    }

    @Test
    fun `with both debs present the command binds them and installs via dpkg plus apt -f`() {
        binaries()
        placePinnedDebs()
        val command = WineProvisioner.command(File(lib, "libproot.so"), rootfs, File(filesDir, "resolv.conf"), downloadDir)
        assertTrue(command.contains("${downloadDir.absolutePath}:${WineProvisioner.GUEST_DEB_DIR}"))
        val script = command.last()
        assertTrue(script.contains("dpkg --configure -a"))
        assertTrue(script.contains("dpkg -i ${WineProvisioner.GUEST_DEB_DIR}/*.deb"))
        assertTrue("dpkg alone cannot fetch dependencies - apt -f must follow", script.contains("-f install -y"))
        assertFalse("no '--' - termux/proot has no end-of-options marker", command.contains("--"))
        assertEquals("/bin/bash", command[command.size - 3])
    }

    @Test
    fun `without the debs the command falls back to plain apt for the same packages`() {
        binaries()
        val command = WineProvisioner.command(File(lib, "libproot.so"), rootfs, File(filesDir, "resolv.conf"), downloadDir)
        // The script always MENTIONS the deb dir (it is the branch condition),
        // but no bind may carry it: nothing is mounted, the apt path runs.
        assertFalse(command.any { it.endsWith(":${WineProvisioner.GUEST_DEB_DIR}") })
        assertTrue(command.last().contains("install -y --no-install-recommends wine64 wine"))
    }

    @Test
    fun `a null debDir behaves like no debs`() {
        binaries()
        val command = WineProvisioner.command(File(lib, "libproot.so"), rootfs, File(filesDir, "resolv.conf"), null)
        assertFalse(command.any { it.endsWith(":${WineProvisioner.GUEST_DEB_DIR}") })
    }

    // --- provision: the disk is the verdict --------------------------------------

    @Test
    fun `provision fails honestly when the payload is absent after exit 0`() {
        val runner = FakeProot()
        val result = WineProvisioner.provision(binaries(), rootfs, filesDir, downloadDir, LogBuffer(), runner)
        assertFalse(result.success)
        assertTrue(result.message.contains("no Wine launcher"))
    }

    @Test
    fun `provision succeeds when the payload lands on disk`() {
        val runner = FakeProot(onRealRun = {
            File(rootfs, "usr/bin/wine64").writeText("#!/bin/sh\n")
        })
        val log = LogBuffer()
        val result = WineProvisioner.provision(binaries(), rootfs, filesDir, downloadDir, log, runner)
        assertTrue(result.message, result.success)
        assertTrue(result.message.contains("wine64"))
        assertTrue(log.tail(50).contains("Wine payload ready"))
        // The real pass really ran through the shared fake-root prefix (one apt command).
        assertTrue(runner.commands.any { it.last().contains("dpkg --configure -a") })
    }

    @Test
    fun `provision reports the apt failure with its tail lines`() {
        val runner = FakeProot(realOutcome = RunOutcome(100, "", "E: Unable to locate package wine64\n"))
        val result = WineProvisioner.provision(binaries(), rootfs, filesDir, downloadDir, LogBuffer(), runner)
        assertFalse(result.success)
        assertTrue(result.message.contains("exit 100"))
        assertTrue(result.tailLines.any { it.contains("Unable to locate") })
    }

    @Test
    fun `provision names a timeout instead of pretending`() {
        val runner = FakeProot(realOutcome = RunOutcome(null, "", "", "Timed out after 2700000ms"))
        val result = WineProvisioner.provision(binaries(), rootfs, filesDir, downloadDir, LogBuffer(), runner)
        assertFalse(result.success)
        assertTrue(result.message.contains("did not finish"))
    }
}
