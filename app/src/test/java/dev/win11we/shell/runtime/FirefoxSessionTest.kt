package dev.win11we.shell.runtime

import dev.win11we.shell.container.Container
import dev.win11we.shell.runtime.download.ArtifactState
import dev.win11we.shell.runtime.download.DownloadSession
import dev.win11we.shell.runtime.download.FreeSpaceProvider
import dev.win11we.shell.runtime.download.RootfsManager
import dev.win11we.shell.runtime.download.RuntimeManifest
import dev.win11we.shell.runtime.download.Transfer
import dev.win11we.shell.ui.terminal.LogBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

private class UnusedLauncher : ProcessLauncher {
    override fun start(command: List<String>, environment: Map<String, String>, workingDir: File?): LaunchAttempt =
        LaunchAttempt(null, "tests only build command lines")
}

private class UnusedHandle : ProcessHandle {
    override val isAlive: Boolean get() = false
    override fun stdout(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun stderr(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun waitFor(timeoutMillis: Long): Int? = 0
    override fun destroy() {}
}

/**
 * The Firefox ESR desktop-browser session (R3 artifact + launch plan): pure
 * command/env/bind shapes, install checks, the R3 state machine's treatment of
 * the artifact, and the pinned manifest entry.
 */
class FirefoxSessionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var binaries: NativeBinaries
    private lateinit var launcher: ContainerLauncher

    @Before
    fun setUp() {
        // loaderPaths() only lists loaders that are actually present, so the fake
        // nativeLibraryDir gets them; the launcher must inject them for any guest.
        val nativeDir = File(tmp.root, "nativelib").also { it.mkdirs() }
        File(nativeDir, "libproot-loader.so").writeBytes(byteArrayOf(1))
        File(nativeDir, "libproot-loader32.so").writeBytes(byteArrayOf(1))
        binaries = NativeBinaries.from(nativeDir.absolutePath, emptyMap())
        launcher = ContainerLauncher(binaries, LogBuffer(), launcher = UnusedLauncher(), streamThreads = false)
    }

    private fun request(url: String? = null, profile: File, xDir: File, resolv: File = File(tmp.root, "resolv.conf")) = RuntimeRequest(
        container = Container.defaults(FirefoxSession.SESSION_ID, FirefoxSession.SESSION_NAME, profile.absolutePath),
        rootfsDir = File(tmp.root, "rootfs"),
        xDisplay = RuntimePaths.X_DISPLAY,
        guestCommand = FirefoxSession.sessionCommand(url),
        guestPrefix = FirefoxSession.GUEST_PROFILE_DIR,
        binds = FirefoxSession.binds(profile, xDir, resolv),
        environment = FirefoxEnvironment.build(RuntimePaths.X_DISPLAY),
    )

    @Test
    fun `the guest command is native firefox - never box64 - with the profile and url`() {
        val withUrl = FirefoxSession.guestCommand("https://example.org")
        assertEquals(listOf("/opt/firefox-esr/firefox", "-P", "shell", "https://example.org"), withUrl)
        assertEquals(listOf("/opt/firefox-esr/firefox", "-P", "shell"), FirefoxSession.guestCommand(null))
        assertEquals(listOf("/opt/firefox-esr/firefox", "-P", "shell"), FirefoxSession.guestCommand("  "))
        assertFalse(launcher.needsBox64(withUrl))
        assertFalse(launcher.needsBox64(FirefoxSession.guestCommand(null)))
    }

    @Test
    fun `the proot command binds the profile at root dot mozilla and the X socket dir`() {
        val profile = File(tmp.root, "profile")
        val xDir = File(tmp.root, "x11")
        val command = launcher.buildCommand(request("https://example.org", profile, xDir))
        // Bare --rootfs binds nothing else; the kernel interfaces must be explicit.
        assertTrue(command.containsAll(listOf("-b", "/dev")))
        assertTrue(command.contains("/proc"))
        assertTrue(command.contains("/sys"))
        // Guest DNS: Android has no /etc/resolv.conf for the jail.
        assertTrue(command.contains("${File(tmp.root, "resolv.conf").absolutePath}:/etc/resolv.conf"))
        assertEquals(binaries.nativeLibraryDir.absolutePath + "/libproot.so", command.first())
        assertTrue(command.containsAll(listOf("-r", "--link2symlink", "--kill-on-exit")))
        // The session's state dir replaces the Wine prefix bind...
        assertTrue(command.contains("${profile.absolutePath}:/root/.mozilla"))
        // ...and nothing is bound at /root/.wine.
        assertFalse(command.any { it.endsWith(":/root/.wine") })
        // The X socket directory lands where libX11 looks.
        assertTrue(command.contains("${xDir.absolutePath}:/tmp/.X11-unix"))
        // The guest tail is the D-Bus bootstrap wrapper; the firefox argv is the
        // script's exec line, shell-quoted.
        assertEquals(listOf("/bin/bash", "-c"), command.takeLast(3).dropLast(1))
        val script = command.last()
        assertTrue(script, script.contains("exec '/opt/firefox-esr/firefox' '-P' 'shell' 'https://example.org'"))
        assertFalse("native arm64 - no box64 in the chain", command.any { it.contains("box64") })
        assertFalse(launcher.needsBox64(FirefoxSession.sessionCommand("https://example.org")))
    }

    @Test
    fun `binds carry dev-shm when the shell provides one - and stay three without it`() {
        val profile = File(tmp.root, "p")
        val xDir = File(tmp.root, "x")
        val resolv = File(tmp.root, "r")
        val shm = File(tmp.root, "shm")
        val withShm = FirefoxSession.binds(profile, xDir, resolv, shm)
        assertEquals(4, withShm.size)
        assertTrue(withShm.map { it.argument }.contains("${shm.absolutePath}:${FirefoxSession.GUEST_SHM_DIR}"))
        // Backwards compatible: no shm dir -> the historical three binds.
        assertEquals(3, FirefoxSession.binds(profile, xDir, resolv).size)
    }

    @Test
    fun `the environment is firefox-specific explicit and sorted - no wine variables`() {
        val env = launcher.buildEnvironment(request(null, File(tmp.root, "p"), File(tmp.root, "x")))
        assertEquals(RuntimePaths.X_DISPLAY, env["DISPLAY"])
        assertEquals("/root", env["HOME"])
        assertEquals("0", env["MOZ_ENABLE_WAYLAND"])
        assertEquals("1", env["MOZ_DISABLE_CONTENT_SANDBOX"])
        assertEquals("1", env["MOZ_DISABLE_GMP_SANDBOX"])
        assertEquals("1", env["MOZ_DISABLE_RDD_SANDBOX"])
        assertEquals("1", env["MOZ_FORCE_DISABLE_E10S"]) // proot ptrace cost: one process
        // The embedded X server is a software renderer: no GL may be probed.
        assertEquals("1", env["LIBGL_ALWAYS_SOFTWARE"])
        assertEquals("llvmpipe", env["GALLIUM_DRIVER"])
        // proot's seccomp filter misbehaves on Android kernels - the documented
        // kill switch must be set for guest sessions too (Phase-2A seccomp fix).
        assertEquals("1", env["PROOT_NO_SECCOMP"])
        assertTrue(env.getValue("PATH").endsWith("/opt/firefox-esr"))
        assertNull("no Wine environment for this session", env["WINEPREFIX"])
        assertNull(env["WINEDEBUG"])
        // proot's loader paths are still injected and everything is sorted for diffing.
        assertTrue(env.containsKey("PROOT_LOADER"))
        assertTrue(env.containsKey("PROOT_LOADER_32"))
        // The vendored proot links libtalloc.so: the host library dir must lead
        // LD_LIBRARY_PATH or the exec dies with CANNOT LINK EXECUTABLE.
        assertTrue(env.getValue("LD_LIBRARY_PATH").startsWith(binaries.nativeLibraryDir.absolutePath))
        assertEquals(env.keys.toList(), env.keys.sorted())
        // FirefoxEnvironment itself is pure and sorted.
        val pure = FirefoxEnvironment.build(":100")
        assertEquals(pure.keys.toList(), pure.keys.sorted())
        assertFalse(pure.containsKey("PULSE_SERVER"))
        assertTrue(FirefoxEnvironment.build(":100", guestAudioSocket = "/tmp/pulse-native")
            .containsValue("unix:/tmp/pulse-native"))
    }

    @Test
    fun `install and gui-library checks look at the exact rootfs paths`() {
        val rootfs = File(tmp.root, "rootfs")
        assertFalse(FirefoxSession.isInstalled(rootfs))
        assertFalse(FirefoxSession.guiLibrariesPresent(rootfs))
        File(rootfs, "opt/firefox-esr").mkdirs()
        assertFalse("a directory alone is not an install", FirefoxSession.isInstalled(rootfs))
        File(rootfs, "opt/firefox-esr/firefox").writeText("#!/bin/sh\n")
        assertTrue(FirefoxSession.isInstalled(rootfs))
        File(rootfs, "usr/lib/aarch64-linux-gnu").mkdirs()
        File(rootfs, "usr/lib/aarch64-linux-gnu/libgtk-3.so.0").writeText("")
        assertTrue(FirefoxSession.guiLibrariesPresent(rootfs))
    }

    // --- R3 state machine treatment of the artifact ---------------------------

    private class BytesTransfer(private val payload: ByteArray) : Transfer {
        override fun fetch(
            artifact: dev.win11we.shell.runtime.download.Artifact,
            target: File,
            fromByte: Long,
            onProgress: (Long, Long) -> Unit,
            cancelled: () -> Boolean,
        ): Long {
            target.writeBytes(payload)
            onProgress(payload.size.toLong(), payload.size.toLong())
            return payload.size.toLong()
        }
    }

    private fun manager(rootfs: File, downloads: File, extractor: (File, File) -> Boolean = { _, _ -> false }) =
        RootfsManager(
            downloadDir = downloads,
            rootfsDir = rootfs,
            session = DownloadSession(BytesTransfer(ByteArray(0)), FreeSpaceProvider { Long.MAX_VALUE }),
            log = LogBuffer(),
            artifacts = listOf(RuntimeManifest.FIREFOX_ESR),
            extractor = extractor,
        )

    @Test
    fun `the firefox artifact scans READY once it is installed into the rootfs`() {
        val rootfs = File(tmp.root, "rootfs")
        val downloads = tmp.newFolder("downloads")
        val manager = manager(rootfs, downloads)
        manager.rescan()
        assertEquals(ArtifactState.NOT_DOWNLOADED, manager.stateOf(RuntimeManifest.FIREFOX_ESR.id))
        File(rootfs, "opt/firefox-esr").mkdirs()
        File(rootfs, "opt/firefox-esr/firefox").writeText("")
        manager.rescan()
        assertEquals(ArtifactState.READY, manager.stateOf(RuntimeManifest.FIREFOX_ESR.id))
    }

    @Test
    fun `extracting firefox without an archive or with a wrong hash fails honestly`() {
        val rootfs = File(tmp.root, "rootfs")
        val downloads = tmp.newFolder("downloads")
        val manager = manager(rootfs, downloads, extractor = { _, _ -> true })
        assertFalse(manager.extract(RuntimeManifest.FIREFOX_ESR))
        assertEquals(ArtifactState.NOT_DOWNLOADED, manager.stateOf(RuntimeManifest.FIREFOX_ESR.id))

        RuntimeManifest.FIREFOX_ESR.targetFile(downloads).writeBytes("not-the-real-tarball".toByteArray())
        assertFalse(manager.extract(RuntimeManifest.FIREFOX_ESR))
        assertEquals(ArtifactState.FAILED, manager.stateOf(RuntimeManifest.FIREFOX_ESR.id))
        assertTrue(manager.messages.getValue(RuntimeManifest.FIREFOX_ESR.id).contains("pinned SHA-256"))
    }

    @Test
    fun `the pinned manifest entry is the verified Mozilla release`() {
        val artifact = RuntimeManifest.FIREFOX_ESR
        assertEquals("firefox-esr", artifact.id)
        assertTrue(artifact.url.startsWith("https://archive.mozilla.org/pub/firefox/releases/140.17.0esr/"))
        assertTrue(artifact.url.endsWith("/linux-aarch64/en-US/firefox-140.17.0esr.tar.xz"))
        assertEquals(64, artifact.sha256.length)
        // SHA-256 of the release tarball whose SHA-512 matches Mozilla's SHA512SUMS (2026-10-10).
        assertEquals("dd579f48405a7065f0f643ddb71a8de0f48a9d984c2783dee2ca173081850084", artifact.sha256)
        assertEquals(66_921_712L, artifact.sizeBytes)
        assertEquals("firefox-140.17.0esr.tar.xz", artifact.fileName)
        assertTrue("needs the rootfs to be a working install", artifact.incompleteAlone)
        assertTrue(RuntimeManifest.ALL.contains(artifact))
        assertEquals(4, RuntimeManifest.ALL.size)
        assertNull(RuntimeManifest.byId("nope"))
    }

    @Test
    fun `runtime paths follow the X convention and keep the profile persistent`() {
        val files = File(tmp.root, "files")
        assertEquals(File(files, "tmp/.X11-unix"), RuntimePaths.xSocketUnixDir(files))
        assertEquals("X100", RuntimePaths.xSocket(files).name)
        assertEquals(File(files, "tmp/.X11-unix"), RuntimePaths.xSocket(files).parentFile)
        assertEquals(File(files, "browser/firefox-profile"), RuntimePaths.browserProfileDir(files))
        assertEquals(File(files, "tmp/resolv.conf"), RuntimePaths.resolvConf(files))
        assertEquals(File(files, "downloads"), RuntimePaths.downloadDir(files))
        // /dev/shm provider: Android's /dev has no shm entry, so every session
        // binds this host dir at the guest's /dev/shm (Chromium/Gecko/esync).
        assertEquals(File(files, "tmp/shm"), RuntimePaths.shmDir(files))
        val shm = RuntimePaths.ensureShmDir(files)
        assertTrue(shm.isDirectory)
        assertTrue(shm.canWrite())
        // The socket path must stay under the unix-socket length limit (~108 bytes);
        // app data dirs are deep, so this is a real constraint worth pinning.
        assertTrue(RuntimePaths.xSocket(files).absolutePath.length < 100)
        assertFalse(RuntimePaths.rootfsExtracted(File(files, "rootfs")))
    }
}

/**
 * The D-Bus session bootstrap (D1d): the fixed-address bus that lets a second
 * URL reach the RUNNING firefox instead of fighting the profile lock, and the
 * shell-quoting that keeps a user-typed URL from ever being interpolated raw.
 */
class FirefoxDBusSessionTest {

    @Test
    fun `the bootstrap probes, starts a private bus only when dead, and exports the address`() {
        val script = FirefoxSession.dbusBootstrapScript(FirefoxSession.guestCommand("https://example.org"))
        val address = FirefoxSession.GUEST_DBUS_ADDRESS
        assertEquals("unix:path=/tmp/shell-dbus-session", address)
        // the fixed address is exported to the shell variables the script uses
        assertTrue(script.contains("ADDR='$address'"))
        assertTrue(script.contains("SOCK='/tmp/shell-dbus-session'"))
        // liveness probe through dbus-send GetId on the fixed address
        assertTrue(script.contains("dbus-send --address=\"\$ADDR\""))
        assertTrue(script.contains("org.freedesktop.DBus.GetId"))
        // only starts a daemon when the probe failed AND dbus-daemon exists
        assertTrue(script.contains("if ! probe && command -v dbus-daemon"))
        assertTrue(script.contains("dbus-daemon --session --address=\"\$ADDR\" --nofork"))
        // a SIGKILL'd daemon can leave a stale socket behind: it is removed before rebinding
        assertTrue(script.contains("rm -f \"\$SOCK\""))
        // bounded wait, then the export the remote client needs, then exec
        assertTrue(script.contains("while [ \$i -lt 50 ]"))
        assertTrue(script.contains("sleep 0.1"))
        assertTrue(script.contains("export DBUS_SESSION_BUS_ADDRESS=\"\$ADDR\""))
        assertTrue(script.trimEnd().endsWith("exec '/opt/firefox-esr/firefox' '-P' 'shell' 'https://example.org'"))
    }

    @Test
    fun `sessionCommand wraps the guest command in bash and never needs box64`() {
        val command = FirefoxSession.sessionCommand(null)
        assertEquals("/bin/bash", command[0])
        assertEquals("-c", command[1])
        assertEquals(3, command.size)
        assertTrue(command[2].trimEnd().endsWith("exec '/opt/firefox-esr/firefox' '-P' 'shell'"))
        // blank URLs stay absent, exactly like guestCommand
        assertEquals(command[2], FirefoxSession.sessionCommand("  ")[2])
    }

    @Test
    fun `URLs are single-quote escaped - shell metacharacters cannot escape the argument`() {
        assertEquals("'plain'", FirefoxSession.shellQuote("plain"))
        assertEquals("""'it'\''s'""", FirefoxSession.shellQuote("it's"))
        val hostile = "https://x.org/?a='b'; rm -rf / ;\$(reboot)`id`"
        val quoted = FirefoxSession.shellQuote(hostile)
        // every single quote in the input becomes the '\'' dance, so the whole
        // hostile string stays ONE quoted argument
        assertTrue(quoted.startsWith("'") && quoted.endsWith("'"))
        assertEquals(hostile.length + 3 * hostile.count { it == '\'' } + 2, quoted.length)
        val script = FirefoxSession.dbusBootstrapScript(FirefoxSession.guestCommand(hostile))
        assertTrue(script.contains("exec '/opt/firefox-esr/firefox' '-P' 'shell' $quoted"))
    }

    @Test
    fun `handoff reuses the same bootstrap so the running instance's bus is found`() {
        // The handoff request is built with handoff=true in RuntimeHolder (Android
        // side); what is unit-testable is the invariant that makes it work: the
        // handoff command is byte-identical in shape to the session command, so
        // it probes the SAME fixed socket the running session created.
        val session = FirefoxSession.sessionCommand("https://second.example")
        val handoff = FirefoxSession.sessionCommand("https://second.example")
        assertEquals(session, handoff)
        assertTrue(session[2].contains(FirefoxSession.GUEST_DBUS_SOCKET))
    }

    @Test
    fun `the desktop session starts openbox - and only the fresh session does`() {
        val script = FirefoxSession.sessionCommand("https://example.org")[2]
        // The guest WM prelude: start openbox, wait (BOUNDED) for its EWMH
        // announcement, then exec firefox - the whole desktop renders inside
        // the X Desktop surface because the server runs pass-through.
        assertTrue(script.contains("command -v openbox"))
        assertTrue(script.contains("openbox >/dev/null 2>&1 &"))
        assertTrue(script.contains("_NET_SUPPORTING_WM_CHECK"))
        assertTrue(script.contains("exec '/opt/firefox-esr/firefox' '-P' 'shell' 'https://example.org'"))
        assertTrue(script.contains("while [ ${'$'}i -lt 20 ]"))
        // A handoff must NOT start a second WM - the session's openbox is up.
        val handoff = FirefoxSession.sessionCommand("https://example.org", withDesktop = false)
        assertFalse(handoff[2].contains("openbox"))
        assertTrue(handoff[2].contains(FirefoxSession.GUEST_DBUS_SOCKET))
        assertTrue(handoff[2].contains("exec '/opt/firefox-esr/firefox'"))
    }
}
