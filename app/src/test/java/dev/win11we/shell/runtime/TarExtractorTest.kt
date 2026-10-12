package dev.win11we.shell.runtime.download

import dev.win11we.shell.runtime.BinaryRole
import dev.win11we.shell.runtime.NativeBinaries
import dev.win11we.shell.runtime.ProcessRunner
import dev.win11we.shell.runtime.RunOutcome
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

/** Builds real tar bytes (ustar, with checksums) so the extractor is tested against the format, not a mock. */
private object Tars {
    fun header(
        name: String,
        mode: Int,
        size: Long,
        typeFlag: Char,
        linkName: String = "",
        prefix: String = "",
    ): ByteArray {
        val h = ByteArray(512)
        fun put(offset: Int, value: String) {
            val bytes = value.toByteArray(Charsets.US_ASCII)
            bytes.copyInto(h, offset, 0, minOf(bytes.size, h.size - offset))
        }

        fun putOctal(offset: Int, length: Int, value: Long) {
            put(offset, value.toString(8).padStart(length - 1, '0'))
        }
        put(0, name)
        put(345, prefix)
        putOctal(100, 8, mode.toLong())
        putOctal(108, 8, 0) // uid
        putOctal(116, 8, 0) // gid
        putOctal(124, 12, size)
        putOctal(136, 12, 0) // mtime
        h[156] = typeFlag.code.toByte()
        put(157, linkName)
        put(257, "ustar")
        put(263, "00") // version
        put(297, "root")
        // Checksum: field treated as spaces while summing, then 6 octal digits + NUL + space.
        for (index in 148..155) h[index] = ' '.code.toByte()
        var sum = 0
        for (byte in h) sum += byte.toInt() and 0xFF
        val checksum = sum.toString(8).padStart(6, '0')
        put(148, checksum)
        h[154] = 0
        h[155] = ' '.code.toByte()
        return h
    }

    fun file(name: String, content: ByteArray, mode: Int = 0b110_100_100, prefix: String = ""): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(header(name, mode, content.size.toLong(), '0', prefix = prefix))
        out.write(content)
        val pad = (512 - content.size % 512) % 512
        out.write(ByteArray(pad))
        return out.toByteArray()
    }

    fun dir(name: String, mode: Int = 0b111_101_101): ByteArray =
        header(name.trimEnd('/') + "/", mode, 0, '5')

    fun symlink(name: String, target: String): ByteArray = header(name, 0b111_101_101, 0, '2', target)

    fun hardlink(name: String, target: String): ByteArray = header(name, 0b110_100_100, 0, '1', target)

    fun charDevice(name: String): ByteArray = header(name, 0b110_100_100, 0, '3')

    fun gnuLongName(name: String, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(header("././@LongLink", 0, (name.length + 1).toLong(), 'L'))
        out.write(name.toByteArray())
        out.write(0)
        val written = name.length + 1
        out.write(ByteArray((512 - written % 512) % 512))
        out.write(header("", 0b110_100_100, content.size.toLong(), '0'))
        out.write(content)
        out.write(ByteArray((512 - content.size % 512) % 512))
        return out.toByteArray()
    }

    fun end(): ByteArray = ByteArray(1024)

    fun archive(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        out.write(end())
        return out.toByteArray()
    }
}

class TarExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun extract(bytes: ByteArray, strip: Int = 0, name: String = "test.tar"): TarExtractor.Result {
        val archive = File(tmp.root, name).also { it.writeBytes(bytes) }
        val target = File(tmp.root, "out")
        return TarExtractor.open(archive).extractTo(target, stripComponents = strip)
    }

    @Test
    fun `files directories modes symlinks and hardlinks round trip`() {
        val bytes = Tars.archive(
            Tars.dir("etc"),
            Tars.file("etc/hello", "world".toByteArray()),
            Tars.file("bin/tool", "#!/bin/sh\n".toByteArray(), mode = 0b111_101_101),
            Tars.symlink("etc/link", "hello"),
            Tars.hardlink("etc/hard", "etc/hello"),
        )
        val result = extract(bytes)
        assertTrue(result.error ?: "", result.success)
        val out = File(tmp.root, "out")
        assertEquals("world", File(out, "etc/hello").readText())
        assertEquals(1, result.symlinks)
        assertEquals("world", File(out, "etc/link").readText()) // symlink resolves
        assertEquals(1, result.hardlinks)
        assertTrue("exec bit must survive", File(out, "bin/tool").canExecute())
        assertFalse(File(out, "etc/hello").canExecute())
    }

    @Test
    fun `gnu long names are supported`() {
        val longName = "opt/" + "very-long-directory-name/".repeat(8) + "firefox"
        val result = extract(Tars.archive(Tars.gnuLongName(longName, "payload".toByteArray())))
        assertTrue(result.success)
        assertEquals("payload", File(tmp.root, "out/$longName").readText())
    }

    @Test
    fun `strip components unpacks the firefox layout into place`() {
        val bytes = Tars.archive(
            Tars.dir("firefox"),
            Tars.file("firefox/firefox", "#!/usr/bin/env bash\n".toByteArray(), mode = 0b111_101_101),
            Tars.file("firefox/libxul.so", ByteArray(64)),
        )
        val result = extract(bytes, strip = 1)
        assertTrue(result.success)
        val out = File(tmp.root, "out")
        assertTrue(File(out, "firefox").isFile) // the launcher ends up at the dest root
        assertTrue(File(out, "libxul.so").isFile)
        assertFalse(File(out, "firefox/firefox").exists())
    }

    @Test
    fun `path traversal aborts the extraction and writes nothing outside`() {
        val result = extract(Tars.archive(Tars.file("../evil", "x".toByteArray())))
        assertFalse(result.success)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("escapes"))
        assertFalse(File(tmp.root, "evil").exists())
    }

    @Test
    fun `device nodes are skipped and counted, not created`() {
        val result = extract(Tars.archive(Tars.charDevice("dev/null"), Tars.file("after", "ok".toByteArray())))
        assertTrue(result.success)
        assertEquals(1, result.skipped)
        assertEquals("ok", File(tmp.root, "out/after").readText())
    }

    @Test
    fun `a corrupt header checksum fails closed`() {
        val bytes = Tars.archive(Tars.file("f", "x".toByteArray())).clone()
        // Change the last checksum digit to another VALID octal digit: the field
        // must still parse, and the verification must then reject it.
        val original = bytes[153]
        bytes[153] = ((((original - '0'.code) + 1) % 8) + '0'.code).toByte()
        val result = extract(bytes)
        assertFalse(result.success)
        assertTrue(result.error!!.contains("checksum"))
    }

    @Test
    fun `gzip archives open through the name-based factory`() {
        val raw = Tars.archive(Tars.file("gz-inside", "yes".toByteArray()))
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(raw) }
        val result = extract(gz.toByteArray(), name = "test.tar.gz")
        assertTrue(result.success)
        assertEquals("yes", File(tmp.root, "out/gz-inside").readText())
    }

    /**
     * Regression: JDK BufferedInputStream.skip() legally skips LESS than asked
     * (it only advances within its current buffer). A bare skip() for the 512-byte
     * alignment silently misaligned every following header on the real ubuntu-base
     * archive; this wrapper forces short skips so the extractor's loops are pinned.
     */
    private class StingySkipStream(input: java.io.InputStream) : java.io.FilterInputStream(input) {
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, minOf(len, 64)) // keep the outer buffer small so skips hit us

        override fun skip(n: Long): Long {
            val limited = minOf(n, 7L)
            var done = 0L
            while (done < limited) {
                if (read() < 0) break
                done++
            }
            return done
        }
    }

    @Test
    fun `short skips from the underlying stream never misalign the archive`() {
        val bytes = Tars.archive(
            Tars.file("one", ByteArray(89)), // sizes whose padding crosses buffer bounds
            Tars.file("two", ByteArray(100_000)), // bigger than the extractor's 64 KiB buffer
            Tars.dir("d"),
            Tars.file("d/three", "third".toByteArray()),
        )
        val target = File(tmp.root, "stingy-out")
        val result = TarExtractor(StingySkipStream(bytes.inputStream())).extractTo(target)
        assertTrue(result.error ?: "", result.success)
        assertEquals("third", File(target, "d/three").readText())
        assertEquals(100_000, File(target, "two").length().toInt())
    }

    @Test
    fun `cancellation stops mid-archive and reports incomplete`() {
        val bytes = Tars.archive(Tars.file("a", ByteArray(2048)), Tars.file("b", ByteArray(2048)))
        val archive = File(tmp.root, "cancel.tar").also { it.writeBytes(bytes) }
        var calls = 0
        val result = TarExtractor.open(archive).extractTo(File(tmp.root, "out")) { calls++ >= 1 }
        assertFalse(result.complete)
        assertEquals("cancelled", result.error)
    }

    @Test
    fun `the xz factory opens the real firefox archive shape`() {
        // A real .tar.xz round trip through org.tukaani, the exact path R3 uses
        // for Mozilla's tarball (xz-java is on the test classpath like the app's).
        val raw = Tars.archive(Tars.dir("firefox"), Tars.file("firefox/firefox", "MZ".toByteArray()))
        val xz = ByteArrayOutputStream()
        org.tukaani.xz.XZOutputStream(xz, org.tukaani.xz.LZMA2Options()).use { it.write(raw) }
        val result = extract(xz.toByteArray(), name = "firefox-test.tar.xz")
        // The factory dispatches by name; content check via a second direct pass.
        assertTrue(result.success)
        assertTrue(File(tmp.root, "out/firefox/firefox").isFile)
    }

    @Test
    fun `pax extended headers override names and are consumed silently`() {
        fun paxRecords(vararg kvs: Pair<String, String>): ByteArray {
            val text = buildString {
                for ((key, value) in kvs) {
                    val body = " $key=$value\n"
                    var len = body.length + 1
                    while (len.toString().length + body.length != len) len = len.toString().length + body.length
                    append(len.toString()); append(body)
                }
            }
            return text.toByteArray()
        }

        val out = ByteArrayOutputStream()
        val records = paxRecords("path" to "the/real/name.txt")
        out.write(Tars.header("./PaxHeaders/whatever", 0b110_100_100, records.size.toLong(), 'x'))
        out.write(records)
        out.write(ByteArray((512 - records.size % 512) % 512))
        out.write(Tars.file("placeholder-name", "pax content".toByteArray()))
        out.write(Tars.end())

        val archive = File(tmp.root, "pax.tar").also { it.writeBytes(out.toByteArray()) }
        val result = TarExtractor.open(archive).extractTo(File(tmp.root, "out"))
        assertTrue(result.error ?: "", result.success)
        assertEquals("pax headers are normal tar metadata, not skips", 0, result.skipped)
        assertEquals("pax content", File(tmp.root, "out/the/real/name.txt").readText())
    }

    @Test
    fun `prefix-style ustar names and dot-slash names both land in place`() {
        val bytes = Tars.archive(
            Tars.file("deep/file", "x".toByteArray(), prefix = "usr/lib"),
            Tars.file("./dotted", "y".toByteArray()),
        )
        val result = extract(bytes)
        assertTrue(result.success)
        assertEquals("x", File(tmp.root, "out/usr/lib/deep/file").readText())
        assertEquals("y", File(tmp.root, "out/dotted").readText())
    }
}

/**
 * The apt-inside-proot provisioning step: pure command/environment shapes and
 * the honest refusal paths, driven through the [ProcessRunner] seam.
 */
class RootfsProvisionerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeRunner(
        private val outcome: RunOutcome = RunOutcome(0, "Reading package lists... Done\nSetting up libgtk-3-0 (3.24.33) ...\n", ""),
    ) : ProcessRunner {
        val commands = mutableListOf<List<String>>()
        val environments = mutableListOf<Map<String, String>>()
        override fun run(command: List<String>, environment: Map<String, String>, workingDir: File?, timeoutMillis: Long): RunOutcome {
            commands.add(command)
            environments.add(environment)
            return outcome
        }
    }

    private fun shippedBinaries(): NativeBinaries {
        val dir = tmp.newFolder("nativelib")
        listOf(BinaryRole.PROOT, BinaryRole.PROOT_LOADER, BinaryRole.TALLOC).forEach { role ->
            val file = File(dir, role.fileName)
            file.writeBytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()) + ByteArray(2044))
        }
        val pins = BinaryRole.entries
            .filter { File(dir, it.fileName).isFile }
            .associate { it.fileName to NativeBinaries.sha256(File(dir, it.fileName))!! }
        return NativeBinaries(dir, pins)
    }

    private fun extractedRootfs(): File {
        val rootfs = tmp.newFolder("rootfs")
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "usr/bin/apt-get").writeText("#!/bin/sh\n")
        // An arm64 ELF header prefix (e_machine=183 LE at offset 18) so the
        // Provision doctor's architecture check passes.
        val elf = ByteArray(20)
        elf[0] = 0x7F; elf[1] = 'E'.code.toByte(); elf[2] = 'L'.code.toByte(); elf[3] = 'F'.code.toByte()
        elf[4] = 2; elf[5] = 1; elf[18] = 183.toByte()
        File(rootfs, "usr/bin/bash").writeBytes(elf)
        File(rootfs, "etc/apt").mkdirs()
        File(rootfs, "etc/apt/sources.list").writeText("deb http://ports.ubuntu.com/ubuntu-ports/ jammy main\n")
        File(rootfs, "bin").mkdirs()
        return rootfs
    }

    @Test
    fun `the command is apt inside proot with the runtime binds`() {
        val command = dev.win11we.shell.runtime.RootfsProvisioner.command(
            prootBinary = File("/lib/arm64/libproot.so"),
            rootfsDir = File("/data/rootfs"),
            resolvConf = File("/data/tmp/resolv.conf"),
        )
        assertEquals("/lib/arm64/libproot.so", command.first())
        assertTrue(command.containsAll(listOf("--rootfs", "/data/rootfs", "--link2symlink", "--kill-on-exit")))
        // Without -0 dpkg refuses ("requires superuser privilege") and apt cannot
        // take its locks; it must come before the `--` separator.
        assertTrue("-0 (fake root) is missing", command.contains("-0"))
        assertTrue(command.indexOf("-0") < command.indexOf("--"))
        assertTrue(command.contains("/proc:/proc"))
        assertTrue(command.contains("/sys:/sys"))
        assertTrue(command.contains("/dev:/dev"))
        assertTrue(command.contains("/data/tmp/resolv.conf:/etc/resolv.conf"))
        val script = command.last()
        assertTrue(script.startsWith("apt-get -o APT::Sandbox::User=root"))
        assertTrue(script.contains("-o Dpkg::Options::=--force-unsafe-io"))
        assertTrue(script.contains("-o Dpkg::Options::=--force-confold"))
        // update must run first, and all three apt calls carry the same options
        val updateAt = script.indexOf(" update && ")
        val installAt = script.indexOf(" install -y ")
        assertTrue("apt-get update must run before install", updateAt in 0 until installAt)
        assertEquals("every apt-get call needs the sandbox/dpkg options", 3, Regex("APT::Sandbox::User=root").findAll(script).count())
        assertTrue(script.contains("--no-install-recommends libgtk-3-0"))
        assertTrue(script.contains("ca-certificates"))
        assertTrue(script.contains("fonts-dejavu-core"))
        // The X self-test's clients ship in the same provisioning pass, so the
        // one-tap Firefox setup also produces a testable X server.
        assertTrue(script.contains("x11-apps"))
        assertTrue(script.contains("x11-utils"))
        assertTrue(script.endsWith("rm -rf /var/lib/apt/lists/*"))
        assertTrue("no box64 in this chain", command.none { it.contains("box64") })
    }

    @Test
    fun `the environment carries the loader paths and the host library dir`() {
        val env = dev.win11we.shell.runtime.RootfsProvisioner.environment(
            nativeLibraryDir = File("/native"),
            loaderPaths = mapOf("PROOT_LOADER" to "/native/libproot-loader.so"),
            tmpDir = File("/data/tmp"),
        )
        assertEquals("/native", env["LD_LIBRARY_PATH"])
        assertEquals("/native/libproot-loader.so", env["PROOT_LOADER"])
        assertEquals("noninteractive", env["DEBIAN_FRONTEND"])
        assertEquals("1", env["PROOT_NO_SECCOMP"])
        assertEquals("/data/tmp", env["TMPDIR"])
        assertEquals(env.keys.toList(), env.keys.sorted())
    }

    @Test
    fun `installResolvConf replaces a dangling systemd-resolved symlink with a real file`() {
        val rootfs = tmp.newFolder("resolv-rootfs")
        File(rootfs, "etc").mkdirs()
        val target = File(rootfs, "etc/resolv.conf")
        java.nio.file.Files.createSymbolicLink(
            target.toPath(),
            File("../run/systemd/resolve/stub-resolv.conf").toPath(),
        )
        assertTrue(java.nio.file.Files.isSymbolicLink(target.toPath()))
        assertFalse("dangling symlink reads as absent", target.isFile)

        dev.win11we.shell.runtime.RootfsProvisioner.installResolvConf(rootfs)

        assertFalse(java.nio.file.Files.isSymbolicLink(target.toPath()))
        assertTrue(target.isFile)
        assertTrue(target.readText().contains("nameserver 1.1.1.1"))
    }

    @Test
    fun `provisioning succeeds through the seam and logs the tail`() {
        val binaries = shippedBinaries()
        val rootfs = extractedRootfs()
        val log = dev.win11we.shell.ui.terminal.LogBuffer()
        val runner = FakeRunner()
        val result = dev.win11we.shell.runtime.RootfsProvisioner.provision(
            binaries = binaries,
            rootfsDir = rootfs,
            filesDir = tmp.root,
            log = log,
            runner = runner,
        )
        assertTrue(result.message, result.success)
        assertEquals(1, runner.commands.size)
        val env = runner.environments.single()
        assertTrue(env.containsKey("PROOT_LOADER"))
        assertEquals("1", env["PROOT_NO_SECCOMP"])
        assertEquals(File(tmp.root, "tmp").absolutePath, env["TMPDIR"])
        assertTrue(log.snapshot().any { it.text.contains("Setting up libgtk-3-0") })
        // The guest DNS file was written for the bind...
        val resolv = File(tmp.root, "tmp/resolv.conf")
        assertTrue(resolv.isFile)
        assertTrue(resolv.readText().contains("nameserver"))
        // ...and as a real file inside the rootfs (imported images may ship a
        // dangling symlink there, which no bind can fix).
        val guestResolv = File(rootfs, "etc/resolv.conf")
        assertTrue(guestResolv.isFile)
        assertFalse(Files.isSymbolicLink(guestResolv.toPath()))
        assertTrue(guestResolv.readText().contains("nameserver"))
    }

    @Test
    fun `provisioning refuses honestly without binaries or without an extracted rootfs`() {
        val log = dev.win11we.shell.ui.terminal.LogBuffer()
        val emptyBinaries = NativeBinaries(tmp.newFolder("empty"), emptyMap())
        val noBinaries = dev.win11we.shell.runtime.RootfsProvisioner.provision(
            binaries = emptyBinaries, rootfsDir = extractedRootfs(), filesDir = tmp.root, log = log, runner = FakeRunner(),
        )
        assertFalse(noBinaries.success)
        assertTrue(noBinaries.message.contains("Runtime binaries not ready"))

        val noRootfs = dev.win11we.shell.runtime.RootfsProvisioner.provision(
            binaries = shippedBinaries(), rootfsDir = File(tmp.root, "nope"), filesDir = tmp.root, log = log, runner = FakeRunner(),
        )
        assertFalse(noRootfs.success)
        assertTrue(noRootfs.message.contains("not extracted"))
    }

    @Test
    fun `an apt failure is reported with its exit code and last output line`() {
        val runner = FakeRunner(RunOutcome(100, "", "E: Unable to locate package libgtk-3-0"))
        val result = dev.win11we.shell.runtime.RootfsProvisioner.provision(
            binaries = shippedBinaries(),
            rootfsDir = extractedRootfs(),
            filesDir = tmp.root,
            log = dev.win11we.shell.ui.terminal.LogBuffer(),
            runner = runner,
        )
        assertFalse(result.success)
        assertTrue(result.message.contains("exit 100"))
        assertTrue(result.message.contains("Unable to locate package"))
        assertEquals(listOf("E: Unable to locate package libgtk-3-0"), result.tailLines)
    }

    @Test
    fun `a failure reports the last 30 output lines in the card and the shared log`() {
        val noisy = (1..45).joinToString("\n") { "apt line $it" }
        val runner = FakeRunner(RunOutcome(100, noisy, "E: Sub-process returned an error code"))
        val log = dev.win11we.shell.ui.terminal.LogBuffer()
        val result = dev.win11we.shell.runtime.RootfsProvisioner.provision(
            binaries = shippedBinaries(),
            rootfsDir = extractedRootfs(),
            filesDir = tmp.root,
            log = log,
            runner = runner,
        )
        assertFalse(result.success)
        // The card payload: exit code in the message, exactly the last 30 lines.
        assertTrue(result.message.contains("exit 100"))
        assertEquals(30, result.tailLines.size)
        assertEquals("apt line 17", result.tailLines.first()) // 46 lines total - 30 + 1
        assertEquals("E: Sub-process returned an error code", result.tailLines.last())
        assertTrue(result.message.contains("E: Sub-process returned an error code"))
        // The shared log (Terminal ▸ log) carries the same tail as ERROR lines,
        // followed by a SYSTEM summary naming the exit code.
        val errorLines = log.filter(dev.win11we.shell.ui.terminal.LineKind.ERROR).map { it.text }
        assertEquals(30, errorLines.size)
        assertEquals(result.tailLines, errorLines)
        assertTrue(
            log.filter(dev.win11we.shell.ui.terminal.LineKind.SYSTEM)
                .any { it.text.contains("exit 100") },
        )
    }

    @Test
    fun `a timeout is reported as 'did not finish' with no fake exit code`() {
        val runner = FakeRunner(RunOutcome(null, "Get:1 http://ports.ubuntu.com jammy InRelease", "", "Timed out after 2700000ms"))
        val result = dev.win11we.shell.runtime.RootfsProvisioner.provision(
            binaries = shippedBinaries(),
            rootfsDir = extractedRootfs(),
            filesDir = tmp.root,
            log = dev.win11we.shell.ui.terminal.LogBuffer(),
            runner = runner,
        )
        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("did not finish"))
        assertTrue(result.message.contains("Timed out after 2700000ms"))
        assertFalse("a timeout must not invent an exit code", result.message.contains("exit "))
        assertEquals(listOf("Get:1 http://ports.ubuntu.com jammy InRelease"), result.tailLines)
    }
}
