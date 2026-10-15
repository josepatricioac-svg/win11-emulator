package dev.win11we.shell.runtime

import dev.win11we.shell.container.Container
import dev.win11we.shell.settings.Box64Preset
import dev.win11we.shell.ui.terminal.LogBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A [ProcessRunner] the tests drive by hand; records commands and environments. */
private class PrefixFakeRunner(
    private val exitCode: Int? = 0,
    private val stdout: String = "",
    private val stderr: String = "",
    private val error: String? = null,
    /** When set, the runner materialises a healthy prefix (what wineboot does). */
    private val simulateWinebootInto: File? = null,
) : ProcessRunner {
    val commands = mutableListOf<List<String>>()
    val environments = mutableListOf<Map<String, String>>()
    override fun run(
        command: List<String>,
        environment: Map<String, String>,
        workingDir: File?,
        timeoutMillis: Long,
    ): RunOutcome {
        commands += command
        environments += environment
        simulateWinebootInto?.let { dir ->
            dir.mkdirs()
            File(dir, "system.reg").writeText("# WINE REGISTRY Version 2\n# All keys relative to z:\n\n#arch=win64\n")
            File(dir, "user.reg").writeText("# WINE REGISTRY Version 2\n# All keys relative to z:\n\n#arch=win64\n")
            File(dir, "userdef.reg").writeText("# WINE REGISTRY Version 2\n")
            File(dir, "drive_c").mkdirs()
        }
        return RunOutcome(exitCode, stdout, stderr, error)
    }
}

class WinePrefixManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var rootfs: File
    private lateinit var prefix: File
    private lateinit var container: Container

    /**
     * A FAITHFUL noble arm64 Wine payload, matching the real .deb contents rather than
     * a single made-up file: the loader from `wine64` at /usr/lib/wine/wine64, and the
     * `libwine` half that actually carries wineboot + the builtins
     * (/usr/lib/wine/wineserver and /usr/lib/aarch64-linux-gnu/wine). A fixture with the
     * loader alone would pass a file check yet could never run wineboot - which is the
     * exact gap [WinePayload.payloadGap] exists to name.
     */
    private fun writeWinePayload() {
        File(rootfs, "usr/lib/wine").mkdirs()
        File(rootfs, "usr/lib/wine/wine64").writeText("elf")
        File(rootfs, "usr/lib/wine/wineserver").writeText("#!/bin/sh\n")
        File(rootfs, "usr/lib/aarch64-linux-gnu/wine/aarch64-unix").mkdirs()
        File(rootfs, "usr/lib/aarch64-linux-gnu/wine/aarch64-unix/wineboot.exe.so").writeText("elf")
    }

    private fun writeHealthyPrefix(dir: File = prefix, arch: String = "win64") {
        dir.mkdirs()
        File(dir, "system.reg").writeText("# WINE REGISTRY Version 2\n# All keys relative to z:\n\n#arch=$arch\n")
        File(dir, "user.reg").writeText("# WINE REGISTRY Version 2\n\n#arch=$arch\n")
        File(dir, "drive_c").mkdirs()
    }

    @Before
    fun setUp() {
        rootfs = tmp.newFolder("rootfs")
        prefix = File(tmp.root, "containers/office/prefix")
        container = Container(id = "office", name = "Office", prefixPath = prefix.absolutePath, preset = Box64Preset.GENERAL)
    }

    // --- inspect: the four honest states ---------------------------------------

    @Test
    fun `a missing directory is MISSING with the create hint`() {
        val report = WinePrefixManager.inspect(container.id, prefix)
        assertEquals(PrefixState.MISSING, report.state)
        assertFalse(report.usable)
        assertTrue(report.detail.contains("wineboot"))
    }

    @Test
    fun `an empty directory is UNINITIALISED, not corrupt and not ready`() {
        prefix.mkdirs()
        val report = WinePrefixManager.inspect(container.id, prefix)
        assertEquals(PrefixState.UNINITIALISED, report.state)
        assertTrue(report.detail.contains("wineboot never ran"))
    }

    @Test
    fun `a healthy prefix is READY`() {
        writeHealthyPrefix()
        val report = WinePrefixManager.inspect(container.id, prefix)
        assertEquals(PrefixState.READY, report.state)
        assertTrue(report.usable)
    }

    @Test
    fun `a registry without a Wine header is CORRUPT - the half-written case`() {
        writeHealthyPrefix()
        File(prefix, "system.reg").writeText("\u0000\u0000garbage tail of an interrupted write")
        val report = WinePrefixManager.inspect(container.id, prefix)
        assertEquals(PrefixState.CORRUPT, report.state)
        assertTrue(report.detail.contains("system.reg"))
        assertTrue(report.detail.contains("header"))
    }

    @Test
    fun `a partial marker set is CORRUPT and names what is missing`() {
        writeHealthyPrefix()
        File(prefix, "user.reg").delete()
        val report = WinePrefixManager.inspect(container.id, prefix)
        assertEquals(PrefixState.CORRUPT, report.state)
        assertTrue(report.detail.contains("user.reg is missing"))
    }

    @Test
    fun `an arch mismatch between registry and container is CORRUPT`() {
        writeHealthyPrefix(arch = "win32")
        val report = WinePrefixManager.inspect(container.id, prefix, expectedArch = "win64")
        assertEquals(PrefixState.CORRUPT, report.state)
        assertTrue(report.detail.contains("win32"))
        assertTrue(report.detail.contains("win64"))
    }

    @Test
    fun `a legacy REGEDIT4 header is accepted`() {
        prefix.mkdirs()
        File(prefix, "system.reg").writeText("REGEDIT4\n")
        File(prefix, "user.reg").writeText("REGEDIT4\n")
        File(prefix, "drive_c").mkdirs()
        assertEquals(PrefixState.READY, WinePrefixManager.inspect(container.id, prefix).state)
    }

    @Test
    fun `a stale update lock is reported but does not demote a READY prefix`() {
        writeHealthyPrefix()
        File(prefix, WinePrefixManager.UPDATE_LOCK).writeText("1\n")
        val report = WinePrefixManager.inspect(container.id, prefix)
        assertEquals(PrefixState.READY, report.state)
        assertTrue(report.detail.contains(WinePrefixManager.UPDATE_LOCK))
        assertTrue(report.detail.contains("Repair"))
    }

    @Test
    fun `list maps every container to its own report`() {
        val second = Container(id = "games", name = "Games", prefixPath = File(tmp.root, "containers/games/prefix").absolutePath)
        writeHealthyPrefix()
        val reports = WinePrefixManager.list(listOf(container, second))
        assertEquals(2, reports.size)
        assertEquals(PrefixState.READY, reports.first { it.containerId == "office" }.state)
        assertEquals(PrefixState.MISSING, reports.first { it.containerId == "games" }.state)
    }

    // --- repair ----------------------------------------------------------------

    @Test
    fun `repairFiles removes the stale lock and reports it`() {
        writeHealthyPrefix()
        val lock = File(prefix, WinePrefixManager.UPDATE_LOCK)
        lock.writeText("1\n")
        val done = WinePrefixManager.repairFiles(prefix)
        assertFalse(lock.exists())
        assertTrue(done.any { it.contains("update-lock") })
    }

    @Test
    fun `repairFiles on a missing prefix does nothing and says nothing`() {
        assertTrue(WinePrefixManager.repairFiles(prefix).isEmpty())
    }

    // --- commands ---------------------------------------------------------------

    @Test
    fun `the init command is wineboot --init then wineserver -w inside the usual jail`() {
        val proot = File("/lib/libproot.so")
        val command = WinePrefixManager.initCommand(proot, rootfs, prefix, binds = listOf(Bind("/h/resolv.conf", "/etc/resolv.conf")))
        assertEquals(proot.absolutePath, command.first())
        // The fixed prefix bind (isolation: this container's prefix, guest /root/.wine).
        assertTrue(command.contains("${prefix.absolutePath}:/root/.wine"))
        assertTrue(command.contains("/h/resolv.conf:/etc/resolv.conf"))
        // No '--' anywhere (termux/proot has no end-of-options marker) and the
        // guest command never starts with '-'.
        assertFalse(command.contains("--"))
        assertEquals(listOf("/bin/bash", "-c"), command.takeLast(3).dropLast(1).take(2))
        val script = command.last()
        assertTrue(script, script.contains("wineboot --init"))
        assertTrue(script, script.contains("wineserver -w"))
        assertTrue("wineboot must run BEFORE the wait", script.indexOf("wineboot") < script.indexOf("wineserver"))
    }

    @Test
    fun `the kill command is wineserver -k and always exits 0`() {
        val command = WinePrefixManager.killCommand(File("/lib/libproot.so"), rootfs, prefix)
        val script = command.last()
        assertTrue(script, script.contains("wineserver -k"))
        assertTrue("best effort: no server running must not fail the teardown", script.contains("|| true"))
    }

    @Test
    fun `operation binds carry DNS and dev-shm when provided`() {
        val binds = WinePrefixManager.operationBinds(File("/h/resolv.conf"), File("/h/shm"))
        assertEquals(listOf("/h/resolv.conf:/etc/resolv.conf", "/h/shm:/dev/shm"), binds.map { it.argument })
        assertTrue(WinePrefixManager.operationBinds(null, null).isEmpty())
    }

    @Test
    fun `the operation environment is the launch environment - explicit prefix, arch, display, overrides`() {
        val env = WinePrefixManager.operationEnvironment(container, ":100", rootfs, File("/lib"))
        assertEquals(":100", env["DISPLAY"])
        assertEquals("/root/.wine", env["WINEPREFIX"])
        assertEquals("win64", env["WINEARCH"])
        assertEquals("mscoree,mshtml=", env["WINEDLLOVERRIDES"])
        assertEquals("1", env["PROOT_NO_SECCOMP"])
    }

    // --- create -----------------------------------------------------------------

    @Test
    fun `create refuses without a Wine payload and names the installer`() {
        val result = WinePrefixManager.create(NativeBinaries(tmp.newFolder("lib")), rootfs, container, ":100")
        assertFalse(result.success)
        assertNull(result.state)
        assertTrue(result.message, result.message.contains("No usable Wine payload"))
        assertTrue(result.message.contains("Install Wine payload"))
    }

    @Test
    fun `create runs wineboot and success means the verdict is READY, not merely exit 0`() {
        writeWinePayload()
        val lib = tmp.newFolder("lib")
        listOf(BinaryRole.PROOT, BinaryRole.PROOT_LOADER, BinaryRole.TALLOC).forEach { role ->
            File(lib, role.fileName).writeBytes(ByteArray(64) { 0x41 })
        }
        val binaries = NativeBinaries(
            lib,
            BinaryRole.entries.filter { File(lib, it.fileName).isFile }
                .associate { it.fileName to NativeBinaries.sha256(File(lib, it.fileName))!! },
        )
        val runner = PrefixFakeRunner(simulateWinebootInto = prefix)
        val log = LogBuffer()
        val result = WinePrefixManager.create(binaries, rootfs, container, ":100", log = log, runner = runner)
        assertTrue(result.message, result.success)
        assertEquals(PrefixState.READY, result.state)
        assertEquals(1, runner.commands.size)
        assertTrue(runner.commands.single().last().contains("wineboot --init"))
        // box64 is NOT required for the native arm64 wine tools.
        assertEquals(":100", runner.environments.single()["DISPLAY"])
    }

    @Test
    fun `create reports failure with the guest tail when wineboot exits non-zero`() {
        writeWinePayload()
        val lib = tmp.newFolder("lib")
        listOf(BinaryRole.PROOT, BinaryRole.PROOT_LOADER, BinaryRole.TALLOC).forEach { role ->
            File(lib, role.fileName).writeBytes(ByteArray(64) { 0x41 })
        }
        val binaries = NativeBinaries(
            lib,
            BinaryRole.entries.filter { File(lib, it.fileName).isFile }
                .associate { it.fileName to NativeBinaries.sha256(File(lib, it.fileName))!! },
        )
        val runner = PrefixFakeRunner(exitCode = 127, stderr = "wineboot: command not found\n")
        val result = WinePrefixManager.create(binaries, rootfs, container, ":100", runner = runner)
        assertFalse(result.success)
        assertTrue(result.message.contains("exit 127"))
        assertTrue(result.tailLines.any { it.contains("command not found") })
    }

    @Test
    fun `create with exit 0 but no markers on disk is a failure - the disk is the verdict`() {
        writeWinePayload()
        val lib = tmp.newFolder("lib")
        listOf(BinaryRole.PROOT, BinaryRole.PROOT_LOADER, BinaryRole.TALLOC).forEach { role ->
            File(lib, role.fileName).writeBytes(ByteArray(64) { 0x41 })
        }
        val binaries = NativeBinaries(
            lib,
            BinaryRole.entries.filter { File(lib, it.fileName).isFile }
                .associate { it.fileName to NativeBinaries.sha256(File(lib, it.fileName))!! },
        )
        val result = WinePrefixManager.create(binaries, rootfs, container, ":100", runner = PrefixFakeRunner(exitCode = 0))
        assertFalse(result.success)
        assertTrue(result.message.contains("still"))
    }

    // --- killServers / destroy ----------------------------------------------------

    @Test
    fun `killServers skips prefixes that were never initialised`() {
        val runner = PrefixFakeRunner()
        val (ok, detail) = WinePrefixManager.killServers(
            NativeBinaries(tmp.newFolder("lib")), rootfs, container, runner = runner,
        )
        assertFalse(ok)
        assertTrue(detail.contains("not initialised"))
        assertTrue(runner.commands.isEmpty())
    }

    @Test
    fun `destroy removes the whole tree and runs the kill hook first`() {
        writeHealthyPrefix()
        File(prefix, "drive_c/windows").mkdirs()
        File(prefix, "drive_c/windows/system.reg").writeText("x")
        var killed = false
        val gone = WinePrefixManager.destroy(prefix, killFirst = { killed = true })
        assertTrue(killed)
        assertTrue(gone)
        assertFalse(prefix.exists())
    }

    @Test
    fun `destroy of a missing prefix is a no-op success`() {
        assertTrue(WinePrefixManager.destroy(prefix))
    }
}
