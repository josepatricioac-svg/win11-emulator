package dev.win11we.shell.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NativeBinariesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A stub with the ELF magic; enough for presence/size/permission/hash tests. */
    private fun writeBinary(dir: File, role: BinaryRole, bytes: Int = 4096): File {
        val file = File(dir, role.fileName)
        file.writeBytes(
            byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) + ByteArray(bytes - 4),
        )
        return file
    }

    private fun pinsFor(vararg roles: BinaryRole): Map<String, String> =
        roles.associate { it.fileName to NativeBinaries.sha256(File(tmp.root, it.fileName))!! }

    private fun shipAll() {
        writeBinary(tmp.root, BinaryRole.BOX64)
        writeBinary(tmp.root, BinaryRole.PROOT)
        writeBinary(tmp.root, BinaryRole.PROOT_LOADER)
        writeBinary(tmp.root, BinaryRole.TALLOC)
    }

    @Test
    fun `an empty directory reports every required binary as missing`() {
        val binaries = NativeBinaries(tmp.root)
        assertFalse(binaries.isReady)
        assertEquals(
            listOf(BinaryRole.BOX64, BinaryRole.PROOT, BinaryRole.PROOT_LOADER, BinaryRole.TALLOC),
            binaries.missingRequired(),
        )
        val result = binaries.prepare()
        assertEquals(PrepareStatus.MISSING_REQUIRED, result.status)
        assertTrue(result.message.contains("docs/NATIVE_RUNTIME.md"))
    }

    @Test
    fun `a missing directory is reported distinctly from an empty one`() {
        val binaries = NativeBinaries(File(tmp.root, "does-not-exist"))
        assertEquals(PrepareStatus.NO_DIRECTORY, binaries.prepare().status)
        assertFalse(binaries.exists)
    }

    @Test
    fun `all required binaries present and pinned means ready`() {
        shipAll()
        val binaries = NativeBinaries(tmp.root, pinsFor(BinaryRole.BOX64, BinaryRole.PROOT, BinaryRole.PROOT_LOADER, BinaryRole.TALLOC))
        val result = binaries.prepare()
        assertEquals(PrepareStatus.READY, result.status)
        assertTrue(result.message.contains("libbox64.so"))
        assertTrue(binaries.isReady)
        assertTrue(binaries.missingRequired().isEmpty())
    }

    @Test
    fun `the proot-only requirement ignores a missing box64 - the browser case`() {
        // Ship everything except box64: Firefox ESR (native arm64) must still be READY.
        writeBinary(tmp.root, BinaryRole.PROOT)
        writeBinary(tmp.root, BinaryRole.PROOT_LOADER)
        writeBinary(tmp.root, BinaryRole.TALLOC)
        val binaries = NativeBinaries(
            tmp.root,
            pinsFor(BinaryRole.PROOT, BinaryRole.PROOT_LOADER, BinaryRole.TALLOC),
        )
        assertEquals(PrepareStatus.PARTIAL, binaries.prepare().status) // wine semantics: box64 missing
        assertEquals(PrepareStatus.READY, binaries.prepare(NativeBinaries.REQUIRED_PROOT_ONLY).status)
        assertTrue(BinaryRole.BOX64 !in NativeBinaries.REQUIRED_PROOT_ONLY)
    }

    @Test
    fun `the optional 32-bit loader does not block readiness`() {
        shipAll()
        val binaries = NativeBinaries(tmp.root, pinsFor(BinaryRole.BOX64, BinaryRole.PROOT, BinaryRole.PROOT_LOADER, BinaryRole.TALLOC))
        assertEquals(PrepareStatus.READY, binaries.prepare().status)
        assertFalse(binaries.record(BinaryRole.PROOT_LOADER_32).present)
    }

    @Test
    fun `a partial set names what is still missing before complaining about hashes`() {
        writeBinary(tmp.root, BinaryRole.BOX64)
        val result = NativeBinaries(tmp.root, pinsFor(BinaryRole.BOX64)).prepare()
        assertEquals(PrepareStatus.PARTIAL, result.status)
        assertTrue(result.message.contains("libproot.so"))
    }

    @Test
    fun `prepare marks the binaries executable`() {
        val box64 = writeBinary(tmp.root, BinaryRole.BOX64)
        box64.setExecutable(false)
        assertFalse(box64.canExecute())
        NativeBinaries(tmp.root).prepare()
        assertTrue(box64.canExecute())
        assertTrue(NativeBinaries(tmp.root).record(BinaryRole.BOX64).executable)
    }

    @Test
    fun `the inventory covers every role with its size`() {
        writeBinary(tmp.root, BinaryRole.BOX64, bytes = 8192)
        val inventory = NativeBinaries(tmp.root).inventory()
        assertEquals(BinaryRole.entries.size, inventory.size)
        assertEquals(8192L, inventory.first { it.role == BinaryRole.BOX64 }.sizeBytes)
        assertEquals(0L, inventory.first { it.role == BinaryRole.PROOT }.sizeBytes)
        assertEquals("8 KB", NativeBinaries.formatSize(8192L))
        assertEquals("1.5 MB", NativeBinaries.formatSize(1536L * 1024L))
    }

    @Test
    fun `loader paths only list loaders that are actually shipped`() {
        writeBinary(tmp.root, BinaryRole.PROOT_LOADER)
        val binaries = NativeBinaries(tmp.root)
        assertEquals(setOf("PROOT_LOADER"), binaries.loaderPaths().keys)
        assertEquals(File(tmp.root, "libproot-loader.so").absolutePath, binaries.loaderPaths()["PROOT_LOADER"])
        writeBinary(tmp.root, BinaryRole.PROOT_LOADER_32)
        assertEquals(setOf("PROOT_LOADER", "PROOT_LOADER_32"), binaries.loaderPaths().keys)
    }

    // --- fail-closed hash pinning -------------------------------------------

    @Test
    fun `an unpinned required binary is refused`() {
        shipAll()
        val result = NativeBinaries(tmp.root).prepare() // no pins at all
        assertEquals(PrepareStatus.CHECKSUM_NOT_PINNED, result.status)
        assertTrue(result.message.contains("refusing to exec"))
        assertTrue(result.message.contains("checksums.txt"))
    }

    @Test
    fun `a hash mismatch is refused and names the file`() {
        shipAll()
        val pins = pinsFor(BinaryRole.BOX64, BinaryRole.PROOT, BinaryRole.PROOT_LOADER, BinaryRole.TALLOC).toMutableMap()
        pins["libbox64.so"] = "0".repeat(64)
        val result = NativeBinaries(tmp.root, pins).prepare()
        assertEquals(PrepareStatus.CHECKSUM_MISMATCH, result.status)
        assertTrue(result.message.contains("libbox64.so"))
    }

    @Test
    fun `an unpinned optional binary does not block readiness`() {
        shipAll()
        writeBinary(tmp.root, BinaryRole.PROOT_LOADER_32) // present but deliberately unpinned
        val binaries = NativeBinaries(tmp.root, pinsFor(BinaryRole.BOX64, BinaryRole.PROOT, BinaryRole.PROOT_LOADER, BinaryRole.TALLOC))
        assertEquals(PrepareStatus.READY, binaries.prepare().status)
        assertEquals(
            ChecksumStatus.NOT_PINNED,
            binaries.verifyChecksums().first { it.role == BinaryRole.PROOT_LOADER_32 }.status,
        )
    }

    @Test
    fun `checksum verification reports every role`() {
        writeBinary(tmp.root, BinaryRole.BOX64)
        val results = NativeBinaries(tmp.root).verifyChecksums()
        assertEquals(BinaryRole.entries.size, results.size)
        assertEquals(ChecksumStatus.NOT_PINNED, results.first { it.role == BinaryRole.BOX64 }.status)
        assertEquals(ChecksumStatus.MISSING, results.first { it.role == BinaryRole.PROOT }.status)
        assertEquals(64, results.first { it.role == BinaryRole.BOX64 }.actualSha256?.length)
    }

    @Test
    fun `the pins file tolerates comments blanks and junk lines`() {
        val pins = File(tmp.root, "checksums.txt")
        pins.writeText(
            "# a comment\n" +
                "libbox64.so=ABC123\n" +
                "\n" +
                "not-a-pin-line\n" +
                "libproot.so = def456\n",
        )
        val loaded = NativeBinaries.loadPins(pins)
        assertEquals(setOf("libbox64.so", "libproot.so"), loaded.keys)
        assertEquals("abc123", loaded["libbox64.so"]) // normalised to lower case
        assertEquals("def456", loaded["libproot.so"])
        assertEquals(emptyMap<String, String>(), NativeBinaries.loadPins(File(tmp.root, "absent.txt")))
    }

    @Test
    fun `sha256 matches a known vector and absent files are null`() {
        val file = File(tmp.root, "hello.bin")
        file.writeText("hello")
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", NativeBinaries.sha256(file))
        assertNull(NativeBinaries.sha256(File(tmp.root, "absent.bin")))
    }
}

class RuntimeProbeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Records what it was asked to run and answers with a canned result. */
    private class FakeRunner(
        private val exitCode: Int? = 0,
        private val stdout: String = "",
        private val stderr: String = "",
        private val error: String? = null,
    ) : ProcessRunner {
        val calls = mutableListOf<List<String>>()
        val environments = mutableListOf<Map<String, String>>()

        override fun run(
            command: List<String>,
            environment: Map<String, String>,
            workingDir: File?,
            timeoutMillis: Long,
        ): RunOutcome {
            calls.add(command)
            environments.add(environment)
            return RunOutcome(exitCode, stdout, stderr, error)
        }
    }

    private fun probe(runner: ProcessRunner) = RuntimeProbe(NativeBinaries(tmp.root), runner)

    @Test
    fun `the probes exec the binaries from nativeLibraryDir with --version`() {
        val runner = FakeRunner(stdout = "box64 v0.4.5\n")
        val results = probe(runner).runAll()
        assertEquals(2, results.size)
        assertEquals(File(tmp.root, "libbox64.so").absolutePath, runner.calls[0].first())
        assertEquals(listOf("--version"), runner.calls[0].drop(1))
        assertEquals(File(tmp.root, "libproot.so").absolutePath, runner.calls[1].first())
    }

    @Test
    fun `the environment is explicit - nothing is inherited`() {
        val runner = FakeRunner()
        probe(runner).runBox64()
        val env = runner.environments.single()
        assertTrue(env.containsKey("PATH"))
        assertTrue(env.containsKey("HOME"))
        assertTrue(env.containsKey("TMPDIR"))
        assertTrue(env.containsKey("LANG"))
        assertEquals("1", env["BOX64_NOBANNER"])
        assertFalse(env.containsKey("ANDROID_ROOT")) // a leaked system variable would show up here
    }

    @Test
    fun `a zero exit code with no stderr passes`() {
        val result = probe(FakeRunner(exitCode = 0, stdout = "proot 5.5.0\n")).runProot()
        assertTrue(result.passed)
        assertEquals("exit 0", result.summary)
        assertEquals("proot 5.5.0", result.output)
        assertNull(result.error)
    }

    @Test
    fun `a non-zero exit code fails and keeps stderr as the excerpt`() {
        val result = probe(FakeRunner(exitCode = 126, stderr = "Permission denied")).runBox64()
        assertFalse(result.passed)
        assertEquals("exit 126", result.summary)
        assertTrue(result.output.contains("Permission denied"))
    }

    @Test
    fun `exec failures are reported instead of throwing`() {
        val result = probe(FakeRunner(error = "IOException: No such file or directory")).runBox64()
        assertFalse(result.passed)
        assertEquals("IOException: No such file or directory", result.summary)
    }

    @Test
    fun `a timeout has no exit code and says so`() {
        val result = probe(FakeRunner(exitCode = null, error = "Timed out after 8000ms")).runProot()
        assertFalse(result.passed)
        assertTrue(result.summary.contains("Timed out"))
    }

    @Test
    fun `the log excerpt is trimmed to the last lines`() {
        val result = probe(FakeRunner(stdout = (1..40).joinToString("\n") { "line $it" })).runBox64()
        assertTrue(result.output.lines().size <= 6)
        assertTrue(result.output.contains("line 1"))
    }
}

class ProotCommandTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val proot = File("/data/app/lib/arm64/libproot.so")
    private val box64 = File("/data/app/lib/arm64/libbox64.so")
    private val rootfs = File("/data/user/0/app/files/rootfs")
    private val prefix = File("/data/user/0/app/files/containers/office/prefix")

    @Test
    fun `the prefix bind mount is fixed so wine paths stay valid`() {
        val command = ProotCommand.build(proot, rootfs, prefix, guestCommand = listOf("wine64", "--version"))
        assertEquals(proot.absolutePath, command.first())
        val index = command.indexOf("-b")
        assertEquals(prefix.absolutePath + ":/root/.wine", command[index + 1])
        assertEquals("/root/.wine", ProotCommand.GUEST_PREFIX)
    }

    @Test
    fun `rootfs, flags and the guest command are all present in order`() {
        val command = ProotCommand.build(
            proot, rootfs, prefix,
            guestCommand = listOf("wine64", "cmd", "/c", "echo", "hello"),
        )
        assertEquals(
            listOf(
                proot.absolutePath,
                "-r", rootfs.absolutePath,
                "--link2symlink",
                "--kill-on-exit",
                "-b", prefix.absolutePath + ":/root/.wine",
                // Bare --rootfs binds nothing else (termux/proot cli/proot.c): the
                // kernel interfaces the guest needs are bound explicitly.
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-w", "/root",
                "wine64", "cmd", "/c", "echo", "hello",
            ),
            command,
        )
    }

    @Test
    fun `termux proot gets short options so values are never detached from long ones`() {
        // termux/proot rejects "--rootfs /path" (value must be joined by '='); the short
        // forms take a separate argument. Seen on a real device as
        // "option '--rootfs' and its value must be separated by '='".
        val command = ProotCommand.build(proot, rootfs, prefix, guestCommand = listOf("true"))
        val guestStart = command.indexOf("true")
        val prootArgs = command.subList(1, guestStart)
        listOf("--rootfs", "--bind", "--cwd", "--pwd", "--mount", "--qemu").forEach { long ->
            assertFalse("$long must not be emitted as a detached pair", prootArgs.contains(long))
        }
        assertEquals(rootfs.absolutePath, prootArgs[prootArgs.indexOf("-r") + 1])
        assertEquals("/root", prootArgs[prootArgs.indexOf("-w") + 1])
        assertTrue(prootArgs.contains("-b"))
    }

    @Test
    fun `no bare double-dash separator anywhere - this proot has no end-of-options marker`() {
        // termux/proot v5.1.107.96's generated option table has no "--" entry, so it
        // reaches note(ERROR, USER, "unknown option '%s'.") and proot aborts. Seen on a
        // real device during Firefox setup as: proot error: unknown option "--".
        // Its parse_config() simply stops at the first argument not starting with '-'.
        val launch = ProotCommand.build(proot, rootfs, prefix, guestCommand = listOf("wine64", "cmd"))
        val provision = RootfsProvisioner.command(proot, rootfs, File(tmp.root, "resolv.conf"))
        RootfsProvisioner.SMOKE_STEPS.forEach { step ->
            val smoke = RootfsProvisioner.smokeCommand(
                proot, rootfs, File(tmp.root, "resolv.conf"), step.guestCommand,
            )
            assertFalse("smoke (${step.id}) must not carry '--': $smoke", smoke.contains("--"))
            assertEquals(step.guestCommand, smoke.takeLast(step.guestCommand.size))
        }
        listOf(launch, provision).forEach { command ->
            assertFalse("no '--' in $command", command.contains("--"))
        }
        // The guest command follows the last option directly.
        assertEquals("/bin/bash", provision[provision.indexOf("-w") + 2])
        assertEquals("-c", provision[provision.indexOf("-w") + 3])
    }

    @Test
    fun `the rootfs every component judges is the configured one, from one function`() {
        // Diagnostics once hardcoded <files>/rootfs while the launcher honoured the
        // container.rootfsLocation setting: with the setting set, the X self-test
        // skipped all five rows while judging a directory with no rootfs in it.
        val files = File(tmp.root, "files")
        assertEquals(
            RuntimePaths.defaultRootfs(files),
            RuntimePaths.configuredRootfs(files, null),
        )
        assertEquals(
            RuntimePaths.defaultRootfs(files),
            RuntimePaths.configuredRootfs(files, ""),
        )
        assertEquals(
            RuntimePaths.defaultRootfs(files),
            RuntimePaths.configuredRootfs(files, "   "),
        )
        val custom = File(tmp.root, "elsewhere/rootfs")
        assertEquals(custom, RuntimePaths.configuredRootfs(files, custom.absolutePath))
    }

    @Test
    fun `the pinned package list is exactly the names verified against jammy arm64`() {
        // One wrong name fails the whole apt-get install (exit 100, "Unable to locate
        // package"), which is what libdbus-glib-1-2-2 did on a real device. Every name
        // here was checked against the jammy main+universe binary-arm64 Packages index
        // on 2026-10-12; changing the list means re-checking and editing this pin.
        assertEquals(
            listOf(
                "libgtk-3-0",
                "libdbus-glib-1-2",
                "libdbus-1-3",
                "libasound2",
                "libxt6",
                "libx11-xcb1",
                "libegl1",
                "libgl1",
                "x11-apps",
                "x11-utils",
                "libgtk-3-bin",
                "fontconfig",
                "fonts-dejavu-core",
                "fonts-liberation",
                "ca-certificates",
                "dbus",
                "dbus-x11",
            ),
            RootfsProvisioner.BROWSER_PACKAGES,
        )
    }

    @Test
    fun `a guest command starting with a dash is refused, not sent to proot`() {
        // With no '--' to stop option parsing, "-v" would be read as proot's own
        // verbose option - silently changing what runs. Refuse loudly instead.
        try {
            ProotCommand.build(proot, rootfs, prefix, guestCommand = listOf("-v", "1"))
            fail("a guest command beginning with '-' cannot be expressed")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("no '--' separator"))
        }
    }

    @Test
    fun `the system binds can be overridden`() {
        val command = ProotCommand.build(
            proot, rootfs, prefix,
            guestCommand = listOf("true"),
            systemBinds = emptyList(),
        )
        assertFalse(command.contains("/dev"))
        assertFalse(command.contains("/proc"))
    }

    @Test
    fun `extra drives are bound after the prefix`() {
        val command = ProotCommand.build(
            proot, rootfs, prefix,
            binds = listOf(Bind("/storage/emulated/0/Documents", "/mnt/documents")),
            guestCommand = listOf("wine64", "--version"),
        )
        val prefixBind = command.indexOf(prefix.absolutePath + ":/root/.wine")
        val extraBind = command.indexOf("/storage/emulated/0/Documents:/mnt/documents")
        assertTrue(extraBind > prefixBind)
        assertEquals("-b", command[extraBind - 1])
    }

    @Test
    fun `the optional flags can be turned off`() {
        val command = ProotCommand.build(
            proot, rootfs, prefix,
            guestCommand = listOf("true"),
            link2symlink = false,
            killOnExit = false,
        )
        assertFalse(command.contains("--link2symlink"))
        assertFalse(command.contains("--kill-on-exit"))
    }

    @Test
    fun `an empty guest command is refused`() {
        var threw = false
        try {
            ProotCommand.build(proot, rootfs, prefix, guestCommand = emptyList())
        } catch (error: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `the R4 self-test plan is box64 then wine64 then a real command`() {
        val plan = ProotCommand.selfTestPlan(proot, box64, rootfs, prefix)
        assertEquals(3, plan.size)
        assertEquals(listOf(box64.absolutePath, "--version"), plan[0])
        assertTrue(plan[1].containsAll(listOf("wine64", "--version")))
        assertTrue(plan[2].containsAll(listOf("wine64", "cmd", "/c", "echo", "hello")))
        assertTrue(plan[1].contains(proot.absolutePath))
    }
}
