package dev.win11we.shell.runtime.download

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
import java.io.IOException

/** A [Transfer] the tests drive: correct bytes, corrupt bytes, partial writes, errors. */
private class FakeTransfer(
    private val payload: ByteArray = "correct-payload".toByteArray(),
    private val writeOnly: Long = -1L,
    private val throwAfter: Long = -1L,
    private val refuseRange: Boolean = false,
) : Transfer {
    val ranges = mutableListOf<Long>()
    val progressCalls = mutableListOf<Pair<Long, Long>>()

    override fun fetch(
        artifact: Artifact,
        target: File,
        fromByte: Long,
        onProgress: (Long, Long) -> Unit,
        cancelled: () -> Boolean,
    ): Long {
        ranges.add(fromByte)
        if (refuseRange && fromByte > 0) throw ResumeNotSupportedException("server ignored Range")
        val resuming = fromByte > 0 && target.isFile
        val startBytes = if (resuming) target.length() else 0L
        // A real server answers a Range request with the remaining bytes only.
        val available = payload.size - fromByte.toInt()
        val limit = if (writeOnly >= 0) writeOnly else available.coerceAtLeast(0).toLong()
        var written = 0L
        java.io.FileOutputStream(target, resuming).use { output ->
            val chunk = 4
            while (written < limit) {
                if (cancelled() || (throwAfter in 0..written)) break
                val size = minOf(chunk.toLong(), limit - written).toInt()
                output.write(payload, (fromByte + written).toInt(), size)
                written += size
                onProgress(startBytes + written, startBytes + limit)
                progressCalls.add((startBytes + written) to (startBytes + limit))
            }
            if (throwAfter in 0..written) throw IOException("connection reset")
        }
        return written
    }
}

class DownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private val payload = "the-real-archive-bytes".toByteArray()
    private lateinit var artifact: Artifact

    @Before
    fun setUp() {
        dir = tmp.newFolder("downloads")
        val real = DownloadSession.sha256(writeTemp(payload))!!
        artifact = Artifact(
            id = "thing",
            label = "Thing",
            url = "https://example.org/thing.tar.gz",
            sha256 = real,
            sizeBytes = payload.size.toLong(),
            requiredFreeBytes = 1024,
            description = "test artifact",
        )
        assertEquals(64, artifact.sha256.length)
    }

    private fun writeTemp(bytes: ByteArray): File =
        File(tmp.root, "src-${bytes.size}-${bytes.hashCode()}").also { it.writeBytes(bytes) }

    private fun session(transfer: Transfer, freeBytes: Long = Long.MAX_VALUE) =
        DownloadSession(transfer, FreeSpaceProvider { freeBytes })

    @Test
    fun `a good download is verified and promoted to its real name`() {
        val result = session(FakeTransfer(payload)).download(artifact, dir)
        assertEquals(DownloadOutcome.VERIFIED, result.outcome)
        assertTrue(result.success)
        assertEquals(artifact.sha256, result.sha256)
        assertTrue(artifact.targetFile(dir).isFile)
        assertFalse("the .part file must not survive", artifact.partialFile(dir).exists())
    }

    @Test
    fun `a corrupt download is deleted, never kept - fail closed`() {
        val result = session(FakeTransfer("tampered-bytes!!".toByteArray())).download(artifact, dir)
        assertEquals(DownloadOutcome.HASH_MISMATCH, result.outcome)
        assertFalse(result.success)
        assertFalse(artifact.targetFile(dir).exists())
        assertFalse(artifact.partialFile(dir).exists())
        assertTrue(result.message.contains("expected"))
        assertTrue(result.message.contains("got"))
    }

    @Test
    fun `not enough space is refused before any byte moves`() {
        val transfer = FakeTransfer(payload)
        val result = session(transfer, freeBytes = 10).download(artifact, dir)
        assertEquals(DownloadOutcome.NOT_ENOUGH_SPACE, result.outcome)
        assertTrue(transfer.ranges.isEmpty())
        assertTrue(result.message.contains("Not enough space"))
    }

    @Test
    fun `an existing partial file resumes from its length`() {
        val partial = artifact.partialFile(dir)
        partial.writeBytes(payload.copyOf(8))
        val transfer = FakeTransfer(payload)
        val result = session(transfer).download(artifact, dir)
        assertEquals(DownloadOutcome.VERIFIED, result.outcome)
        assertEquals(listOf(8L), transfer.ranges)
        assertFalse(partial.exists())
    }

    @Test
    fun `cancelling keeps the partial file for the next attempt`() {
        var calls = 0
        val transfer = object : Transfer {
            override fun fetch(
                artifact: Artifact,
                target: File,
                fromByte: Long,
                onProgress: (Long, Long) -> Unit,
                cancelled: () -> Boolean,
            ): Long {
                target.writeBytes(payload.copyOf(6))
                onProgress(6, payload.size.toLong())
                calls++
                return 6L
            }
        }
        val result = session(transfer).download(artifact, dir, cancelled = { calls > 0 })
        assertEquals(DownloadOutcome.CANCELLED, result.outcome)
        assertTrue(artifact.partialFile(dir).isFile)
        assertFalse(artifact.targetFile(dir).exists())
        assertTrue(result.message.contains("kept for resume"))
    }

    @Test
    fun `a network error is reported and the partial is kept`() {
        val transfer = FakeTransfer(payload, throwAfter = 4)
        val result = session(transfer).download(artifact, dir)
        assertEquals(DownloadOutcome.NETWORK_ERROR, result.outcome)
        assertTrue(result.message.contains("connection reset"))
        assertTrue("resume material must survive", artifact.partialFile(dir).isFile)
    }

    @Test
    fun `a server that ignores Range does not produce a corrupt tail`() {
        artifact.partialFile(dir).writeBytes(payload.copyOf(8))
        val result = session(FakeTransfer(payload, refuseRange = true)).download(artifact, dir)
        assertEquals(DownloadOutcome.RESUME_NOT_SUPPORTED, result.outcome)
        assertFalse(artifact.partialFile(dir).exists())
        assertFalse(artifact.targetFile(dir).exists())
    }

    @Test
    fun `an already verified file is not downloaded again`() {
        artifact.targetFile(dir).writeBytes(payload)
        val transfer = FakeTransfer(payload)
        val result = session(transfer).download(artifact, dir)
        assertEquals(DownloadOutcome.ALREADY_VERIFIED, result.outcome)
        assertTrue(transfer.ranges.isEmpty())
        assertEquals(0L, result.bytesTransferred)
    }

    @Test
    fun `an existing file with the wrong hash is deleted and reported`() {
        artifact.targetFile(dir).writeBytes("rot".toByteArray())
        val result = session(FakeTransfer(payload)).download(artifact, dir)
        assertEquals(DownloadOutcome.HASH_MISMATCH, result.outcome)
        assertFalse(artifact.targetFile(dir).exists())
    }

    @Test
    fun `progress is reported while bytes arrive`() {
        val transfer = FakeTransfer(payload)
        session(transfer).download(artifact, dir)
        assertTrue(transfer.progressCalls.isNotEmpty())
        assertEquals(payload.size.toLong(), transfer.progressCalls.last().first)
    }

    @Test
    fun `artifact rejects a bad pin or a non-https url`() {
        var threw = 0
        runCatching { Artifact("x", "X", "https://e/x", "abc", 1, 1, "") }.onFailure { threw++ }
        runCatching { Artifact("x", "X", "ftp://e/x", "0".repeat(64), 1, 1, "") }.onFailure { threw++ }
        // Cleartext is blocked by Android 9+ anyway; refusing it here keeps the manifest honest.
        runCatching { Artifact("x", "X", "http://e/x", "0".repeat(64), 1, 1, "") }.onFailure { threw++ }
        assertEquals(3, threw)
    }

    @Test
    fun `the pinned manifest is self-consistent`() {
        RuntimeManifest.ALL.forEach { artifact ->
            assertEquals(64, artifact.sha256.length)
            assertTrue(artifact.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(artifact.fileName.isNotBlank())
            assertTrue(artifact.requiredFreeBytes > artifact.sizeBytes / 2)
            assertEquals(artifact, RuntimeManifest.byId(artifact.id))
        }
        assertEquals("ubuntu-base-22.04.5-base-arm64.tar.gz", RuntimeManifest.ROOTFS.fileName)
        assertTrue(RuntimeManifest.ROOTFS.url.startsWith("https://cdimage.ubuntu.com/"))
        RuntimeManifest.ALL.forEach { artifact ->
            assertTrue("${artifact.id} must be https", artifact.url.startsWith("https://"))
        }
        assertTrue(RuntimeManifest.WINE64_DEB.incompleteAlone)
        assertTrue(RuntimeManifest.requiredFreeBytes > 1_000_000_000L)
    }

    @Test
    fun `size formatting is readable`() {
        assertEquals("512 B", DownloadSession.formatBytes(512))
        assertEquals("27 KB", DownloadSession.formatBytes(27 * 1024))
        assertEquals("26.4 MB", DownloadSession.formatBytes(27_671_306))
        assertEquals("1.50 GB", DownloadSession.formatBytes(1536L * 1024 * 1024))
    }
}

class RootfsManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var downloads: File
    private lateinit var rootfsDir: File
    private val payload = "rootfs-archive".toByteArray()
    private lateinit var artifact: Artifact

    @Before
    fun setUp() {
        downloads = tmp.newFolder("downloads")
        rootfsDir = File(tmp.root, "rootfs")
        val hashFile = File(tmp.root, "hash-src").also { it.writeBytes(payload) }
        artifact = Artifact(
            id = "rootfs",
            label = "Rootfs",
            url = "https://example.org/rootfs.tar.gz",
            sha256 = DownloadSession.sha256(hashFile)!!,
            sizeBytes = payload.size.toLong(),
            requiredFreeBytes = 1024,
            description = "test rootfs",
        )
    }

    private fun manager(
        transfer: Transfer = FakeTransfer(payload),
        extractor: (File, File) -> Boolean = { _, _ -> false },
        free: Long = Long.MAX_VALUE,
    ) = RootfsManager(
        downloadDir = downloads,
        rootfsDir = rootfsDir,
        session = DownloadSession(transfer, FreeSpaceProvider { free }),
        log = LogBuffer(),
        artifacts = listOf(artifact),
        extractor = extractor,
    )

    @Test
    fun `a fresh install scans as not downloaded`() {
        val manager = manager()
        assertEquals(ArtifactState.NOT_DOWNLOADED, manager.stateOf(artifact.id))
        assertFalse(manager.rootfsReady)
        assertFalse(manager.busy)
    }

    @Test
    fun `download then extract reaches ready when the extractor works`() {
        val manager = manager(extractor = { archive, target ->
            File(target, "bin").mkdirs()
            File(target, "usr").mkdirs()
            archive.isFile
        })
        assertTrue(manager.download(artifact).success)
        assertEquals(ArtifactState.VERIFIED, manager.stateOf(artifact.id))
        assertTrue(manager.extract(artifact))
        assertEquals(ArtifactState.READY, manager.stateOf(artifact.id))
        assertTrue(manager.rootfsReady)
        assertTrue(manager.messages[artifact.id]!!.contains("Extracted to"))
    }

    @Test
    fun `extract is refused while no extractor is available - no silent half install`() {
        val manager = manager()
        manager.download(artifact)
        assertFalse(manager.extract(artifact))
        assertEquals(ArtifactState.FAILED, manager.stateOf(artifact.id))
        assertTrue(manager.messages[artifact.id]!!.contains("proot"))
    }

    @Test
    fun `extract refuses an archive whose hash does not match`() {
        val manager = manager(extractor = { _, _ -> true })
        artifact.targetFile(downloads).writeBytes("tampered".toByteArray())
        assertFalse(manager.extract(artifact))
        assertEquals(ArtifactState.FAILED, manager.stateOf(artifact.id))
        assertTrue(manager.messages[artifact.id]!!.contains("pinned SHA-256"))
    }

    @Test
    fun `extract without a download says so`() {
        val manager = manager()
        assertFalse(manager.extract(artifact))
        assertEquals(ArtifactState.NOT_DOWNLOADED, manager.stateOf(artifact.id))
    }

    @Test
    fun `a partial file scans as partial and the state machine reports progress`() {
        artifact.partialFile(downloads).writeBytes(payload.copyOf(5))
        val manager = manager()
        manager.rescan() // construction never touches the disk; the UI calls refreshAsync
        assertEquals(ArtifactState.PARTIAL, manager.stateOf(artifact.id))
        manager.download(artifact)
        assertEquals(1f, manager.progress[artifact.id]!!, 0.001f)
    }

    @Test
    fun `a verified file on disk scans as verified without downloading`() {
        artifact.targetFile(downloads).writeBytes(payload)
        val transfer = FakeTransfer(payload)
        val manager = manager(transfer)
        manager.rescan()
        assertEquals(ArtifactState.VERIFIED, manager.stateOf(artifact.id))
        assertTrue(transfer.ranges.isEmpty())
    }

    @Test
    fun `a corrupt file on disk is dropped during the scan`() {
        artifact.targetFile(downloads).writeBytes("rot".toByteArray())
        val manager = manager()
        manager.rescan()
        assertEquals(ArtifactState.FAILED, manager.stateOf(artifact.id))
        assertFalse(artifact.targetFile(downloads).exists())
    }

    @Test
    fun `an import is held to the same pin as a download`() {
        val manager = manager()
        val good = manager.import(artifact, ByteArrayInputStream(payload), "picked.tar.gz")
        assertEquals(DownloadOutcome.VERIFIED, good.outcome)
        assertEquals(ArtifactState.VERIFIED, manager.stateOf(artifact.id))
        assertTrue(artifact.targetFile(downloads).isFile)

        val bad = manager.import(artifact, ByteArrayInputStream("wrong".toByteArray()), "wrong.tar.gz")
        assertEquals(DownloadOutcome.HASH_MISMATCH, bad.outcome)
        assertEquals(ArtifactState.FAILED, manager.stateOf(artifact.id))
        assertTrue(manager.messages[artifact.id]!!.contains("Nothing was kept"))
    }

    @Test
    fun `a failing import reports the error instead of throwing`() {
        val manager = manager()
        val result = manager.import(
            artifact,
            object : java.io.InputStream() {
                override fun read(): Int = throw IOException("stream closed")
            },
            "broken.tar.gz",
        )
        assertEquals(DownloadOutcome.NETWORK_ERROR, result.outcome)
        assertFalse(manager.busy)
        assertEquals(ArtifactState.FAILED, manager.stateOf(artifact.id))
    }

    @Test
    fun `remove deletes the archive the partial and the unpacked rootfs`() {
        val manager = manager(extractor = { _, target ->
            File(target, "bin").mkdirs(); File(target, "usr").mkdirs(); true
        })
        manager.download(artifact)
        manager.extract(artifact)
        assertTrue(manager.rootfsReady)
        assertTrue(manager.remove(artifact))
        assertFalse(artifact.targetFile(downloads).exists())
        assertFalse(rootfsDir.exists())
        assertNull(manager.messages[artifact.id]?.takeIf { it.contains("Extracted") })
    }

    @Test
    fun `every step is written to the shared log`() {
        val log = LogBuffer()
        val manager = RootfsManager(
            downloadDir = downloads,
            rootfsDir = rootfsDir,
            session = DownloadSession(FakeTransfer(payload), FreeSpaceProvider { Long.MAX_VALUE }),
            log = log,
            artifacts = listOf(artifact),
        )
        manager.download(artifact)
        manager.extract(artifact)
        val text = log.tail(10)
        assertTrue(text.contains("downloading"))
        assertTrue(text.contains("VERIFIED"))
        assertTrue(text.contains("extract"))
    }
}

/** Regressions for the crash reported when downloading the rootfs from Settings. */
class RootfsCrashSafetyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val payload = "rootfs".toByteArray()
    private lateinit var artifact: Artifact

    @Before
    fun setUp() {
        val hashSource = File(tmp.root, "hash-src").also { it.writeBytes(payload) }
        artifact = Artifact(
            id = "rootfs",
            label = "Rootfs",
            url = "https://example.org/rootfs.tar.gz",
            sha256 = DownloadSession.sha256(hashSource)!!,
            sizeBytes = payload.size.toLong(),
            requiredFreeBytes = 1024,
            description = "test",
        )
    }

    private fun manager(transfer: Transfer) = RootfsManager(
        downloadDir = File(tmp.root, "downloads"),
        rootfsDir = File(tmp.root, "rootfs"),
        session = DownloadSession(transfer, FreeSpaceProvider { Long.MAX_VALUE }),
        log = LogBuffer(),
        artifacts = listOf(artifact),
    )

    @Test
    fun `a transport that throws a RuntimeException becomes a failed download, not a crash`() {
        val exploding = object : Transfer {
            override fun fetch(
                artifact: Artifact,
                target: File,
                fromByte: Long,
                onProgress: (Long, Long) -> Unit,
                cancelled: () -> Boolean,
            ): Long = throw SecurityException("Permission denied (missing INTERNET?)")
        }
        val manager = manager(exploding)
        val result = manager.download(artifact)
        assertFalse(result.success)
        assertEquals(DownloadOutcome.NETWORK_ERROR, result.outcome)
        assertTrue(result.message.contains("SecurityException"))
        assertEquals(ArtifactState.FAILED, manager.stateOf(artifact.id))
        assertFalse("busy must be cleared or every later download is refused", manager.busy)
    }

    @Test
    fun `reportFailure clears busy and records the reason`() {
        val log = LogBuffer()
        val manager = RootfsManager(
            downloadDir = File(tmp.root, "downloads"),
            rootfsDir = File(tmp.root, "rootfs"),
            session = DownloadSession(FakeTransfer(payload), FreeSpaceProvider { Long.MAX_VALUE }),
            log = log,
            artifacts = listOf(artifact),
        )
        manager.reportFailure("download", IllegalStateException("boom"))
        assertFalse(manager.busy)
        assertTrue(log.tail(5).contains("boom"))
    }

    @Test
    fun `constructing the manager does no disk hashing`() {
        // A 27 MB archive must not be hashed on the UI thread while composing Settings.
        val downloads = File(tmp.root, "downloads").apply { mkdirs() }
        artifact.targetFile(downloads).writeBytes(payload)
        val log = LogBuffer()
        val manager = RootfsManager(
            downloadDir = downloads,
            rootfsDir = File(tmp.root, "rootfs"),
            session = DownloadSession(FakeTransfer(payload), FreeSpaceProvider { Long.MAX_VALUE }),
            log = log,
            artifacts = listOf(artifact),
        )
        // No scan happened during construction, so the state is unknown-but-safe...
        assertEquals(ArtifactState.NOT_DOWNLOADED, manager.stateOf(artifact.id))
        // ...and an explicit scan finds the verified archive.
        manager.rescan()
        assertEquals(ArtifactState.VERIFIED, manager.stateOf(artifact.id))
        // Scanning again reuses the cached hash (same result, no exception, no re-read cost).
        manager.rescan()
        assertEquals(ArtifactState.VERIFIED, manager.stateOf(artifact.id))
    }

    @Test
    fun `free space is cached and can be invalidated`() {
        val manager = manager(FakeTransfer(payload))
        val first = manager.freeBytes()
        assertEquals(first, manager.freeBytes())
        manager.invalidateFreeSpace()
        assertTrue(manager.freeBytes() >= 0L)
    }
}
