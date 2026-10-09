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

    private fun request(url: String? = null, profile: File, xDir: File) = RuntimeRequest(
        container = Container.defaults(FirefoxSession.SESSION_ID, FirefoxSession.SESSION_NAME, profile.absolutePath),
        rootfsDir = File(tmp.root, "rootfs"),
        xDisplay = RuntimePaths.X_DISPLAY,
        guestCommand = FirefoxSession.guestCommand(url),
        guestPrefix = FirefoxSession.GUEST_PROFILE_DIR,
        binds = FirefoxSession.binds(profile, xDir),
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
        assertEquals(binaries.nativeLibraryDir.absolutePath + "/libproot.so", command.first())
        assertTrue(command.containsAll(listOf("--rootfs", "--link2symlink", "--kill-on-exit")))
        // The session's state dir replaces the Wine prefix bind...
        assertTrue(command.contains("${profile.absolutePath}:/root/.mozilla"))
        // ...and nothing is bound at /root/.wine.
        assertFalse(command.any { it.endsWith(":/root/.wine") })
        // The X socket directory lands where libX11 looks.
        assertTrue(command.contains("${xDir.absolutePath}:/tmp/.X11-unix"))
        assertEquals(
            listOf("/opt/firefox-esr/firefox", "-P", "shell", "https://example.org"),
            command.takeLast(4),
        )
        assertFalse("native arm64 - no box64 in the chain", command.any { it.contains("box64") })
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
        assertTrue(env.getValue("PATH").endsWith("/opt/firefox-esr"))
        assertNull("no Wine environment for this session", env["WINEPREFIX"])
        assertNull(env["WINEDEBUG"])
        // proot's loader paths are still injected and everything is sorted for diffing.
        assertTrue(env.containsKey("PROOT_LOADER"))
        assertTrue(env.containsKey("PROOT_LOADER_32"))
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
        // The socket path must stay under the unix-socket length limit (~108 bytes);
        // app data dirs are deep, so this is a real constraint worth pinning.
        assertTrue(RuntimePaths.xSocket(files).absolutePath.length < 100)
        assertFalse(RuntimePaths.rootfsExtracted(File(files, "rootfs")))
    }
}
