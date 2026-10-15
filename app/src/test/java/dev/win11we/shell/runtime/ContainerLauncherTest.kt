package dev.win11we.shell.runtime

import dev.win11we.shell.container.Container
import dev.win11we.shell.container.ContainerStatus
import dev.win11we.shell.container.DriveMapping
import dev.win11we.shell.settings.Box64Preset
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
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/** A [ProcessHandle] the tests drive by hand. */
private class FakeHandle(
    private val out: String = "",
    private val err: String = "",
    var exitCode: Int? = 0,
) : ProcessHandle {
    var alive = true
    var destroyed = 0
    override val isAlive: Boolean get() = alive
    override fun stdout(): InputStream = ByteArrayInputStream(out.toByteArray())
    override fun stderr(): InputStream = ByteArrayInputStream(err.toByteArray())
    override fun waitFor(timeoutMillis: Long): Int? {
        alive = false
        return exitCode
    }

    override fun destroy() {
        destroyed++
        alive = false
    }
}

private class FakeLauncher(
    private val handle: ProcessHandle? = FakeHandle(),
    private val failure: String? = null,
) : ProcessLauncher {
    val commands = mutableListOf<List<String>>()
    val environments = mutableListOf<Map<String, String>>()
    override fun start(command: List<String>, environment: Map<String, String>, workingDir: File?): LaunchAttempt {
        commands.add(command)
        environments.add(environment)
        return LaunchAttempt(handle, failure)
    }
}

class ContainerLauncherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var nativeDir: File
    private lateinit var rootfs: File
    private lateinit var prefix: File
    private lateinit var container: Container
    private val log = LogBuffer()

    @Before
    fun setUp() {
        nativeDir = tmp.newFolder("lib")
        rootfs = tmp.newFolder("rootfs")
        prefix = tmp.newFolder("prefix")
        // An initialised prefix: wineboot's three markers. Without them the
        // launcher refuses with PREFIX_MISSING before any of these tests' subject
        // (command shape, status mapping, stop/restart) is even reached.
        File(prefix, "system.reg").writeText("REGEDIT4\n")
        File(prefix, "user.reg").writeText("REGEDIT4\n")
        File(prefix, "drive_c").mkdirs()
        listOf(BinaryRole.BOX64, BinaryRole.PROOT, BinaryRole.PROOT_LOADER, BinaryRole.TALLOC).forEach { role ->
            File(nativeDir, role.fileName).writeBytes(ByteArray(2048) { 0x41 })
        }
        container = Container(
            id = "office",
            name = "Office",
            prefixPath = prefix.absolutePath,
            preset = Box64Preset.OFFICE,
            drives = listOf(DriveMapping('D', "/sdcard/Documents")),
        )
    }

    private fun pins() = BinaryRole.entries
        .filter { File(nativeDir, it.fileName).isFile }
        .associate { it.fileName to NativeBinaries.sha256(File(nativeDir, it.fileName))!! }

    private fun launcher(fake: ProcessLauncher = FakeLauncher()) =
        ContainerLauncher(NativeBinaries(nativeDir, pins()), log, fake, streamThreads = false)

    private fun request(command: List<String> = listOf("wine64", "cmd")) = RuntimeRequest(
        container = container,
        rootfsDir = rootfs,
        xDisplay = ":100",
        audioSocket = "/tmp/pulse",
        guestCommand = command,
    )

    /**
     * A faithful payload fixture: Ubuntu's real loader path (`/usr/lib/wine/wine64`
     * - the pinned debs install nothing into /usr/bin) carrying a real ELF header
     * with the given e_machine, because the box64 rule reads the header, never
     * the name.
     */
    private fun writeWinePayload(machine: Int) {
        File(rootfs, "usr/lib/wine").mkdirs()
        File(rootfs, "usr/lib/wine/wine64").writeBytes(TestElf.header(machine))
    }

    // --- the gate: nothing is exec'd unless everything is in place -------------

    @Test
    fun `refuses to start when the binaries are missing`() {
        val empty = tmp.newFolder("empty-lib")
        val launcher = ContainerLauncher(NativeBinaries(empty), log, FakeLauncher(), streamThreads = false)
        val result = launcher.start(request())
        assertFalse(result.success)
        assertEquals(LaunchFailure.BINARIES_NOT_READY, result.failure)
        assertEquals(ContainerStatus.ERROR, launcher.statusOf(container.id))
        assertTrue(log.filter(LineKind.ERROR).any { it.text.contains("start refused") })
    }

    @Test
    fun `refuses to start an unpinned binary - fail closed`() {
        val unpinned = ContainerLauncher(NativeBinaries(nativeDir), log, FakeLauncher(), streamThreads = false)
        val result = unpinned.start(request())
        assertEquals(LaunchFailure.BINARIES_NOT_READY, result.failure)
        assertTrue(result.message.contains("refusing to exec"))
    }

    @Test
    fun `refuses to start without a rootfs and says R3`() {
        val result = launcher().start(request().copy(rootfsDir = File(tmp.root, "no-rootfs")))
        assertEquals(LaunchFailure.ROOTFS_MISSING, result.failure)
        assertTrue(result.message.contains("R3"))
    }

    @Test
    fun `refuses to start without a prefix`() {
        val broken = container.copy(prefixPath = File(tmp.root, "no-prefix").absolutePath)
        val result = launcher().start(request().copy(container = broken))
        assertEquals(LaunchFailure.PREFIX_MISSING, result.failure)
    }

    @Test
    fun `a missing prefix names the whole chain instead of promising a first-launch fix`() {
        // The old message said the prefix "is created on first launch" - nothing
        // created it, so the user retried forever. The refusal must name every link:
        // no Wine payload -> no wineboot -> no prefix. Since the Prefix Manager
        // landed, it must ALSO name the action that builds one (Initialize prefix
        // runs wineboot; Install Wine payload provisions the rootfs) instead of
        // pointing at a roadmap step.
        val broken = container.copy(prefixPath = File(tmp.root, "no-prefix").absolutePath)
        val result = launcher().start(request().copy(container = broken))
        assertTrue(result.message, result.message.contains("wineboot"))
        assertTrue("the refusal must name the action that builds a prefix",
            result.message.contains("Initialize prefix"))
        assertTrue(result.message, result.message.contains("no Wine payload"))
        assertTrue("must negate the old lie, not repeat it",
            result.message.contains("NOT created on first launch"))
        assertTrue(result.message, result.message.contains(WinePayload.CANDIDATES.first()))
        assertTrue("and point at the self-test that shows where the chain stops",
            result.message.contains("Run self-test"))
    }

    @Test
    fun `with Wine present but no wineboot run, the refusal says exactly that`() {
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "usr/bin/wine64").writeText("#!/bin/sh\n")
        val broken = container.copy(prefixPath = File(tmp.root, "no-prefix-2").absolutePath)
        val result = launcher().start(request().copy(container = broken))
        assertEquals(LaunchFailure.PREFIX_MISSING, result.failure)
        assertTrue(result.message, result.message.contains("wineboot has never been run"))
        assertTrue(result.message, result.message.contains("usr/bin/wine64"))
    }

    @Test
    fun `an empty prefix directory is not a prefix - wineboot never initialised it`() {
        val empty = tmp.newFolder("empty-prefix")
        val result = launcher().start(request().copy(container = container.copy(prefixPath = empty.absolutePath)))
        assertEquals(LaunchFailure.PREFIX_MISSING, result.failure)
        assertTrue(result.message, result.message.contains("never initialised"))
        assertTrue(result.message, result.message.contains("system.reg"))
    }

    @Test
    fun `an initialised prefix passes validation and reaches the spawn stage`() {
        val prefixDir = tmp.newFolder("real-prefix")
        File(prefixDir, "system.reg").writeText("REGEDIT4\n")
        File(prefixDir, "user.reg").writeText("REGEDIT4\n")
        File(prefixDir, "drive_c").mkdirs()
        val fake = FakeLauncher()
        val result = launcher(fake).start(request().copy(container = container.copy(prefixPath = prefixDir.absolutePath)))
        assertFalse("validation must not be what refuses now", result.failure == LaunchFailure.PREFIX_MISSING)
        assertTrue("and the guest command really reaches the spawn stage", fake.commands.isNotEmpty())
    }

    @Test
    fun `WinePayload reports provisioning from the rootfs alone`() {
        val bare = tmp.newFolder("bare-rootfs")
        assertFalse(WinePayload.isProvisioned(bare))
        assertNull(WinePayload.launcherIn(bare))
        File(bare, "usr/lib/wine").mkdirs()
        File(bare, "usr/lib/wine/wine64").writeText("elf")
        assertTrue(WinePayload.isProvisioned(bare))
        assertEquals(File(bare, "usr/lib/wine/wine64"), WinePayload.launcherIn(bare))
        // A directory alone is not a payload, and not a prefix either.
        val prefixDir = tmp.newFolder("dir-only-prefix")
        assertFalse(WinePayload.prefixLooksInitialised(prefixDir))
        File(prefixDir, "system.reg").writeText("x")
        assertFalse(WinePayload.prefixLooksInitialised(prefixDir))
        File(prefixDir, "user.reg").writeText("x")
        File(prefixDir, "drive_c").mkdirs()
        assertTrue(WinePayload.prefixLooksInitialised(prefixDir))
    }

    @Test
    fun `WinePayload payloadGap separates a loader from a payload that can build a prefix`() {
        // `isProvisioned` answers "is there a loader"; `payloadGap` answers the question
        // Initialize prefix actually depends on: can wineboot run? wineboot is a Windows
        // PE shipped by libwine, so a rootfs with only the loader is NOT enough - and
        // saying so up front beats a wineboot failure with no explanation.
        val bare = tmp.newFolder("gap-rootfs")
        assertNotNull("nothing installed at all", WinePayload.payloadGap(bare))
        assertTrue(WinePayload.payloadGap(bare)!!.contains("no Wine loader"))

        File(bare, "usr/lib/wine").mkdirs()
        File(bare, "usr/lib/wine/wine64").writeText("elf")
        assertTrue("the loader alone satisfies isProvisioned...", WinePayload.isProvisioned(bare))
        val gap = WinePayload.payloadGap(bare)
        assertNotNull("...but NOT payloadGap: libwine is missing", gap)
        assertTrue(gap!!, gap.contains("libwine"))

        // Either libwine marker is enough: the wineserver wrapper...
        File(bare, "usr/lib/wine/wineserver").writeText("#!/bin/sh\n")
        assertNull(WinePayload.payloadGap(bare))
        // ...or the builtin tree that carries wineboot.exe.
        File(bare, "usr/lib/wine/wineserver").delete()
        assertNotNull(WinePayload.payloadGap(bare))
        File(bare, "usr/lib/aarch64-linux-gnu/wine").mkdirs()
        assertNull(WinePayload.payloadGap(bare))
    }

    @Test
    fun `WinePayload guestLauncherPath answers the real Ubuntu loader path, null without a payload`() {
        assertNull(WinePayload.guestLauncherPath(rootfs))
        assertNull("no rootfs context, no invented path", WinePayload.guestLauncherPath(null))
        writeWinePayload(ElfHeader.EM_AARCH64)
        assertEquals("/usr/lib/wine/wine64", WinePayload.guestLauncherPath(rootfs))
        // A hand-made or upstream-tarball payload in /usr/bin wins as the first candidate.
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "usr/bin/wine64").writeBytes(TestElf.header(ElfHeader.EM_AARCH64))
        assertEquals("/usr/bin/wine64", WinePayload.guestLauncherPath(rootfs))
    }

    @Test
    fun `WinePayload launcherArch reads the ELF header, not the name`() {
        assertEquals(PayloadArch.UNKNOWN, WinePayload.launcherArch(rootfs))
        assertEquals(PayloadArch.UNKNOWN, WinePayload.launcherArch(null))
        writeWinePayload(ElfHeader.EM_AARCH64)
        assertEquals(PayloadArch.ARM64, WinePayload.launcherArch(rootfs))
        writeWinePayload(ElfHeader.EM_X86_64)
        assertEquals(PayloadArch.X86_64, WinePayload.launcherArch(rootfs))
        // libwine's wineserver is a `sh` wrapper - a loader that is not an ELF
        // answers UNKNOWN instead of pretending to an architecture.
        File(rootfs, "usr/lib/wine/wine64").writeText("#!/bin/sh\n")
        assertEquals(PayloadArch.UNKNOWN, WinePayload.launcherArch(rootfs))
    }

    @Test
    fun `WinePayload resolveGuestCommand replaces only a bare wine64, only with a payload on disk`() {
        val cmd = listOf("wine64", "cmd")
        assertEquals("no payload: unchanged - the refusal path names it", cmd, WinePayload.resolveGuestCommand(cmd, rootfs))
        assertEquals(cmd, WinePayload.resolveGuestCommand(cmd, null))
        assertEquals(emptyList<String>(), WinePayload.resolveGuestCommand(emptyList(), rootfs))
        writeWinePayload(ElfHeader.EM_AARCH64)
        assertEquals(listOf("/usr/lib/wine/wine64", "cmd"), WinePayload.resolveGuestCommand(cmd, rootfs))
        // Real PATH entries (update-alternatives) and caller-chosen paths stay untouched.
        assertEquals(listOf("wineboot", "--init"), WinePayload.resolveGuestCommand(listOf("wineboot", "--init"), rootfs))
        assertEquals(listOf("wineserver", "-k"), WinePayload.resolveGuestCommand(listOf("wineserver", "-k"), rootfs))
        assertEquals(listOf("wine", "notepad.exe"), WinePayload.resolveGuestCommand(listOf("wine", "notepad.exe"), rootfs))
        val byPath = listOf("/opt/wine/bin/wine64", "cmd")
        assertEquals(byPath, WinePayload.resolveGuestCommand(byPath, rootfs))
    }

    @Test
    fun `refuses an empty guest command`() {
        val result = launcher().start(request(command = emptyList()))
        assertEquals(LaunchFailure.NO_COMMAND, result.failure)
    }

    @Test
    fun `a spawn failure is reported, not thrown`() {
        val launcher = launcher(FakeLauncher(handle = null, failure = "IOException: Permission denied"))
        val result = launcher.start(request())
        assertFalse(result.success)
        assertEquals(LaunchFailure.SPAWN_FAILED, result.failure)
        assertEquals(ContainerStatus.ERROR, launcher.statusOf(container.id))
        assertTrue(log.filter(LineKind.ERROR).any { it.text.contains("Permission denied") })
    }

    // --- the happy path ------------------------------------------------------

    @Test
    fun `an x86_64 wine payload runs behind box64, resolved to its real loader path`() {
        writeWinePayload(ElfHeader.EM_X86_64)
        val fake = FakeLauncher()
        val launcher = launcher(fake)
        val result = launcher.start(request())
        assertTrue(result.message, result.success)
        assertEquals(ContainerStatus.RUNNING, launcher.statusOf(container.id))

        val command = fake.commands.single()
        assertEquals(File(nativeDir, "libproot.so").absolutePath, command.first())
        assertTrue(command.containsAll(listOf("-r", rootfs.absolutePath)))
        assertTrue(command.contains(prefix.absolutePath + ":/root/.wine"))
        assertTrue("box64 must sit between proot and wine", command.contains(File(nativeDir, "libbox64.so").absolutePath))
        // Bare `wine64` is not on Ubuntu's PATH: the payload's real loader path is exec'd.
        assertEquals(listOf("/usr/lib/wine/wine64", "cmd"), command.subList(command.size - 2, command.size))
        // container drives are bound under /mnt/<letter>
        assertTrue(command.contains("/sdcard/Documents:/mnt/d"))
        assertEquals(command, launcher.commandOf(container.id))
    }

    @Test
    fun `the native arm64 wine payload runs WITHOUT box64 - box64 cannot carry an aarch64 ELF`() {
        writeWinePayload(ElfHeader.EM_AARCH64)
        val fake = FakeLauncher()
        val launcher = launcher(fake)
        val result = launcher.start(request())
        assertTrue(result.message, result.success)

        val command = fake.commands.single()
        assertFalse(
            "no translator in front of a native arm64 loader",
            command.contains(File(nativeDir, "libbox64.so").absolutePath),
        )
        assertEquals(listOf("/usr/lib/wine/wine64", "cmd"), command.subList(command.size - 2, command.size))
    }

    @Test
    fun `without a provisioned payload nothing is resolved or translated - validation names the gap first`() {
        val fake = FakeLauncher()
        launcher(fake).start(request().copy(container = container.copy(id = "raw", name = "Raw")))
        val command = fake.commands.single()
        assertFalse(command.contains(File(nativeDir, "libbox64.so").absolutePath))
        assertEquals(listOf("wine64", "cmd"), command.subList(command.size - 2, command.size))
    }

    @Test
    fun `native linux commands are not wrapped in box64`() {
        val fake = FakeLauncher()
        launcher(fake).start(request(command = listOf("ls", "-l")))
        val command = fake.commands.single()
        assertFalse(command.contains(File(nativeDir, "libbox64.so").absolutePath))
        assertEquals(listOf("ls", "-l"), command.subList(command.size - 2, command.size))
    }

    @Test
    fun `needsBox64 is payload-driven for wine - the ELF header decides, never the name`() {
        val launcher = launcher()
        val wine = listOf("wine64", "cmd")
        // No payload provisioned (or no rootfs context): run natively - a translator
        // is never prepended on a guess, and validate() names a missing payload
        // before spawn, so the guess is never load-bearing.
        assertFalse(launcher.needsBox64(wine))
        assertFalse(launcher.needsBox64(wine, rootfs))
        assertFalse(launcher.needsBox64(wine, File(tmp.root, "no-such-rootfs")))

        writeWinePayload(ElfHeader.EM_AARCH64)
        assertFalse("the pinned Ubuntu payload is a native arm64 ELF", launcher.needsBox64(wine, rootfs))
        assertFalse(launcher.needsBox64(listOf("/usr/lib/wine/wine64", "cmd"), rootfs))
        assertFalse("wineboot/wineserver are real PATH entries and arm64 too", launcher.needsBox64(listOf("wineboot", "--init"), rootfs))

        writeWinePayload(ElfHeader.EM_X86_64)
        assertTrue("an x86_64 payload is what box64 exists for", launcher.needsBox64(wine, rootfs))
        assertTrue(launcher.needsBox64(listOf("/opt/wine/bin/wine", "notepad.exe"), rootfs))

        // The name-independent rules.
        assertTrue(launcher.needsBox64(listOf("C:\\office\\winword.exe")))
        assertFalse(launcher.needsBox64(listOf("ls")))
        assertFalse(launcher.needsBox64(listOf("box64", "wine64"), rootfs))
        assertFalse(launcher.needsBox64(emptyList()))
    }

    @Test
    fun `requiredFor demands box64 only for an x86_64 payload`() {
        val launcher = launcher()
        val wine = listOf("wine64", "cmd")
        assertFalse("native/no payload: box64 must not gate the launch", launcher.requiredFor(wine, rootfs).contains(BinaryRole.BOX64))
        writeWinePayload(ElfHeader.EM_AARCH64)
        assertFalse(launcher.requiredFor(wine, rootfs).contains(BinaryRole.BOX64))
        writeWinePayload(ElfHeader.EM_X86_64)
        assertTrue(launcher.requiredFor(wine, rootfs).contains(BinaryRole.BOX64))
        assertTrue("proot is always required", launcher.requiredFor(wine, rootfs).contains(BinaryRole.PROOT))
    }

    @Test
    fun `runningWineRequests finds native wine sessions structurally - not by the box64 rule`() {
        // The shutdown contract (graceful wineserver -k before teardown) must see
        // a NATIVE arm64 wine session too: needsBox64 is false for it, so the old
        // filter would have skipped the registry flush and corrupted prefixes.
        writeWinePayload(ElfHeader.EM_AARCH64)
        val launcher = launcher(FakeLauncher())
        launcher.start(request())
        assertFalse("the payload is native: no box64", launcher.needsBox64(listOf("wine64", "cmd"), rootfs))
        assertEquals(1, launcher.runningWineRequests().size)
        assertEquals(prefix.absolutePath, launcher.runningWineRequests().single().container.prefixPath)

        // A Firefox-shaped session (its own guestPrefix bind, native bash exec)
        // has no wineserver and must NOT be swept.
        val browser = request(command = listOf("/bin/bash", "-c", "exec firefox")).copy(
            container = container.copy(id = "browser", name = "Browser"),
            guestPrefix = "/root/.mozilla",
        )
        launcher.start(browser)
        assertEquals(
            "only the /root/.wine-bound session is a Wine session",
            listOf("office"),
            launcher.runningWineRequests().map { it.container.id },
        )
    }

    @Test
    fun `the environment is explicit and carries the container's tuning`() {
        val fake = FakeLauncher()
        launcher(fake).start(request())
        val env = fake.environments.single()
        assertEquals(":100", env["DISPLAY"])
        assertEquals("/root/.wine", env["WINEPREFIX"])
        assertEquals(rootfs.absolutePath, env["PROOT_ROOTFS"])
        assertEquals(File(nativeDir, "libproot-loader.so").absolutePath, env["PROOT_LOADER"])
        assertEquals("/tmp/pulse", env["PULSE_SERVER"])
        assertEquals("2", env["BOX64_DYNAREC_BIGBLOCK"]) // the Office preset
        assertEquals(env.keys.toList(), env.keys.sorted()) // deterministic for diagnostics
    }

    // --- lifecycle -----------------------------------------------------------

    @Test
    fun `starting twice is refused while one is running`() {
        val launcher = launcher()
        assertTrue(launcher.start(request()).success)
        val second = launcher.start(request())
        assertEquals(LaunchFailure.ALREADY_RUNNING, second.failure)
        assertEquals(1, launcher.runningIds().size)
    }

    @Test
    fun `stop destroys the process and clears the state`() {
        val handle = FakeHandle()
        val launcher = launcher(FakeLauncher(handle))
        launcher.start(request())
        val result = launcher.stop(container.id)
        assertTrue(result.success)
        assertEquals(1, handle.destroyed)
        assertEquals(ContainerStatus.STOPPED, launcher.statusOf(container.id))
        assertTrue(launcher.runningIds().isEmpty())
        assertNull(launcher.commandOf(container.id))
        assertTrue(log.snapshot().any { it.text.contains("stopped by user") })
    }

    @Test
    fun `stopping something that is not running is a no-op success`() {
        val result = launcher().stop("nothing")
        assertTrue(result.success)
        assertEquals("Not running.", result.message)
    }

    @Test
    fun `restart stops then starts again`() {
        val handle = FakeHandle()
        val launcher = launcher(FakeLauncher(handle))
        launcher.start(request())
        val result = launcher.restart(request())
        assertTrue(result.success)
        assertEquals(1, handle.destroyed)
        assertEquals(ContainerStatus.RUNNING, launcher.statusOf(container.id))
    }

    @Test
    fun `a zero exit code leaves the container stopped, a failure marks it errored`() {
        val launcher = launcher()
        launcher.start(request())
        launcher.onProcessExited(container.id, 0)
        assertEquals(ContainerStatus.STOPPED, launcher.statusOf(container.id))
        assertTrue(log.snapshot().any { it.text.contains("exited with code 0") })

        launcher.start(request())
        launcher.onProcessExited(container.id, 137)
        assertEquals(ContainerStatus.ERROR, launcher.statusOf(container.id))
        assertTrue(log.filter(LineKind.ERROR).any { it.text.contains("code 137") })
    }

    @Test
    fun `a timeout is reported as no exit code`() {
        val launcher = launcher()
        launcher.start(request())
        launcher.onProcessExited(container.id, null)
        assertEquals(ContainerStatus.ERROR, launcher.statusOf(container.id))
        assertTrue(log.snapshot().any { it.text.contains("no code (timed out)") })
    }

    @Test
    fun `stopAll empties the running set - what the service calls on destroy`() {
        val launcher = launcher()
        launcher.start(request())
        launcher.start(request().copy(container = container.copy(id = "games", name = "Games")))
        assertEquals(2, launcher.runningIds().size)
        launcher.stopAll()
        assertTrue(launcher.runningIds().isEmpty())
        assertEquals(ContainerStatus.STOPPED, launcher.statusOf("games"))
    }

    @Test
    fun `the launch is logged with its full command line for diagnostics`() {
        val launcher = launcher()
        launcher.start(request())
        val line = log.snapshot().first { it.text.startsWith("starting Office:") }
        assertEquals(LineKind.SYSTEM, line.kind)
        assertTrue(line.text.contains("libproot.so"))
        assertTrue(line.text.contains("wine64"))
        assertNotNull(launcher.binaries.loaderPaths()["PROOT_LOADER"])
    }
}
