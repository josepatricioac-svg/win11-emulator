package dev.win11we.shell.runtime.download

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import dev.win11we.shell.runtime.FirefoxSession
import dev.win11we.shell.runtime.RuntimePaths
import dev.win11we.shell.ui.terminal.LineKind
import dev.win11we.shell.ui.terminal.LogBuffer
import dev.win11we.shell.ui.terminal.TerminalLine
import java.io.File
import java.io.InputStream

/** Where an artifact is in its lifecycle. */
enum class ArtifactState(val label: String) {
    NOT_DOWNLOADED("Not downloaded"),
    PARTIAL("Partially downloaded"),
    DOWNLOADING("Downloading"),
    VERIFYING("Verifying"),
    VERIFIED("Downloaded, hash OK"),
    EXTRACTING("Extracting"),
    READY("Installed"),
    FAILED("Failed"),
}

/**
 * Downloads, verifies and installs the runtime payload.
 *
 * Android-free on purpose: the app passes in an [InputStream] for SAF imports and a
 * [Transfer] for network fetches, so the whole state machine is unit-tested on the JVM.
 */
@Stable
class RootfsManager(
    /** `<filesDir>/downloads` */
    val downloadDir: File,
    /** `<filesDir>/rootfs` - where the rootfs is unpacked */
    val rootfsDir: File,
    private val session: DownloadSession,
    private val log: LogBuffer,
    private val artifacts: List<Artifact> = RuntimeManifest.ALL,
    private val extractor: (File, File) -> Boolean = { _, _ -> false },
) {
    val states = mutableStateMapOf<String, ArtifactState>()
    val progress = mutableStateMapOf<String, Float>()
    val messages = mutableStateMapOf<String, String>()

    /** Set while a download is in flight so the UI can offer Cancel. */
    var busy by mutableStateOf(false)
        private set
    private var cancelRequested = false

    /** Hashes are cached per file stamp: re-scanning never re-reads 27 MB. */
    private val hashCache = HashMap<String, Pair<Long, String?>>()

    /**
     * Construction must stay free of disk I/O: this object is created during
     * composition, and hashing a 27 MB archive on the UI thread stalls the shell.
     * The initial scan happens in [refreshAsync].
     */
    private var scanned = false

    private fun cachedSha256(file: File): String? {
        val stamp = file.length()
        hashCache[file.absolutePath]?.takeIf { it.first == stamp }?.let { return it.second }
        val hash = DownloadSession.sha256(file)
        hashCache[file.absolutePath] = stamp to hash
        return hash
    }

    fun artifact(id: String): Artifact? = artifacts.firstOrNull { it.id == id }
    fun all(): List<Artifact> = artifacts
    fun stateOf(id: String): ArtifactState = states[id] ?: ArtifactState.NOT_DOWNLOADED

    /** Rootfs is usable only when it has been unpacked. */
    val rootfsReady: Boolean
        get() = RuntimePaths.rootfsExtracted(rootfsDir)

    /**
     * Whether an artifact's install result is already on disk: the unpacked
     * rootfs tree for ROOTFS, the unpacked Mozilla build for FIREFOX_ESR.
     */
    private fun installedCheck(artifact: Artifact): Boolean = when (artifact.id) {
        RuntimeManifest.ROOTFS.id -> rootfsReady
        RuntimeManifest.FIREFOX_ESR.id -> FirefoxSession.isInstalled(rootfsDir)
        else -> false
    }

    /** Reads what is already on disk without touching the network. May hash a file. */
    fun scan(artifact: Artifact): ArtifactState {
        val target = artifact.targetFile(downloadDir)
        val partial = artifact.partialFile(downloadDir)
        return when {
            installedCheck(artifact) -> ArtifactState.READY
            target.isFile -> if (cachedSha256(target) == artifact.sha256) {
                ArtifactState.VERIFIED
            } else {
                target.delete()
                hashCache.remove(target.absolutePath)
                ArtifactState.FAILED
            }
            partial.isFile -> ArtifactState.PARTIAL
            else -> ArtifactState.NOT_DOWNLOADED
        }
    }

    fun rescan() {
        artifacts.forEach { states[it.id] = scan(it) }
        scanned = true
    }

    /**
     * Scans on a low-priority background thread and publishes through a snapshot, so
     * the UI never blocks and background writes stay visible to Compose.
     */
    fun refreshAsync(onDone: () -> Unit = {}) {
        if (scanned && !forceRescan) {
            onDone()
            return
        }
        forceRescan = false
        Thread {
            runCatching {
                val result = artifacts.associate { it.id to scan(it) }
                Snapshot.withMutableSnapshot {
                    result.forEach { (id, state) -> states[id] = state }
                    scanned = true
                }
            }
            runCatching { onDone() }
        }.apply {
            name = "rootfs-scan"
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private var forceRescan = false

    /** Next [refreshAsync] re-reads the disk even if a scan already happened. */
    fun invalidateScan() {
        forceRescan = true
        hashCache.clear()
    }

    fun cancel() {
        cancelRequested = true
    }

    /**
     * Downloads and verifies one artifact. Returns the outcome; the caller (a background
     * thread in the app) is responsible for not blocking the UI.
     */
    fun download(artifact: Artifact): DownloadResult {
        if (busy) return DownloadResult(DownloadOutcome.CANCELLED, "Another download is already running.")
        setState(artifact.id, ArtifactState.DOWNLOADING)
        Snapshot.withMutableSnapshot {
            busy = true
            cancelRequested = false
            progress[artifact.id] = 0f
        }
        log.add(TerminalLine(LineKind.SYSTEM, "downloading ${artifact.fileName} from ${artifact.url}"))
        val result = try {
            session.download(
                artifact = artifact,
                dir = downloadDir,
                onProgress = { done, total ->
                    Snapshot.withMutableSnapshot {
                        progress[artifact.id] = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                    }
                },
                cancelled = { cancelRequested },
            )
        } catch (error: Throwable) {
            // A crash here used to take the whole process down: no permission, no network,
            // no storage. It is a failed download now.
            DownloadResult(
                DownloadOutcome.NETWORK_ERROR,
                "${error.javaClass.simpleName}: ${error.message}",
            )
        }
        val newState = when (result.outcome) {
            DownloadOutcome.VERIFIED, DownloadOutcome.ALREADY_VERIFIED -> ArtifactState.VERIFIED
            DownloadOutcome.CANCELLED -> ArtifactState.PARTIAL
            else -> ArtifactState.FAILED
        }
        Snapshot.withMutableSnapshot {
            states[artifact.id] = newState
            progress[artifact.id] = if (result.success) 1f else progress[artifact.id] ?: 0f
            messages[artifact.id] = result.message
            busy = false
        }
        log.add(
            TerminalLine(
                if (result.success) LineKind.INFO else LineKind.ERROR,
                "${artifact.id}: ${result.outcome} - ${result.message}",
            ),
        )
        invalidateScan()
        return result
    }

    /** UI-side message setter (snapshot-safe, callable from any thread). */
    fun setMessage(id: String, message: String) {
        Snapshot.withMutableSnapshot { messages[id] = message }
    }

    /** Last resort for worker threads: never let an exception escape into the process. */
    fun reportFailure(what: String, error: Throwable) {
        val message = "$what failed: ${error.javaClass.simpleName}: ${error.message}"
        Snapshot.withMutableSnapshot { busy = false }
        artifacts.firstOrNull { it.id == what || message.contains(it.fileName) }?.let { artifact ->
            setState(artifact.id, ArtifactState.FAILED)
            setMessage(artifact.id, message)
        }
        log.add(TerminalLine(LineKind.ERROR, message))
    }

    /** All state writes from worker threads go through a snapshot so Compose sees them. */
    private fun setState(id: String, state: ArtifactState) {
        Snapshot.withMutableSnapshot { states[id] = state }
    }

    /**
     * Installs a file the user picked with SAF: it is streamed into the download
     * directory and held to the same pinned hash as a network download.
     */
    fun import(artifact: Artifact, input: InputStream, sourceName: String): DownloadResult {
        if (busy) return DownloadResult(DownloadOutcome.CANCELLED, "Another download is already running.")
        Snapshot.withMutableSnapshot { busy = true }
        runCatching { downloadDir.mkdirs() }
        setState(artifact.id, ArtifactState.VERIFYING)
        val target = artifact.targetFile(downloadDir)
        val written = try {
            target.outputStream().use { output -> input.copyTo(output) }
            target.length()
        } catch (error: Throwable) {
            Snapshot.withMutableSnapshot { busy = false }
            setState(artifact.id, ArtifactState.FAILED)
            val message = "Import failed: ${error.javaClass.simpleName}: ${error.message}"
            messages[artifact.id] = message
            log.add(TerminalLine(LineKind.ERROR, message))
            return DownloadResult(DownloadOutcome.NETWORK_ERROR, message)
        }
        val hash = cachedSha256(target)
        Snapshot.withMutableSnapshot { busy = false }
        return if (hash == artifact.sha256) {
            setState(artifact.id, ArtifactState.VERIFIED)
            val message = "Imported $sourceName (${DownloadSession.formatBytes(written)}), SHA-256 matches the pin."
            Snapshot.withMutableSnapshot { messages[artifact.id] = message }
            invalidateScan()
            log.add(TerminalLine(LineKind.INFO, message))
            DownloadResult(DownloadOutcome.VERIFIED, message, written, hash)
        } else {
            runCatching { target.delete() }
            hashCache.remove(target.absolutePath)
            setState(artifact.id, ArtifactState.FAILED)
            val message = "Imported file does not match the pinned SHA-256 for ${artifact.fileName} " +
                "(expected ${artifact.sha256.take(16)}…, got ${hash?.take(16) ?: "unreadable"}). Nothing was kept."
            Snapshot.withMutableSnapshot { messages[artifact.id] = message }
            log.add(TerminalLine(LineKind.ERROR, message))
            DownloadResult(DownloadOutcome.HASH_MISMATCH, message, written, hash)
        }
    }

    /**
     * Unpacks the verified rootfs archive. The real implementation runs
     * `proot --rootfs / -0 tar -xf …` (device nodes and fake root need proot); that is
     * injected as [extractor] so this class stays testable and honest about support.
     */
    fun extract(artifact: Artifact = RuntimeManifest.ROOTFS): Boolean {
        val archive = artifact.targetFile(downloadDir)
        if (!archive.isFile) {
            Snapshot.withMutableSnapshot {
                messages[artifact.id] = "Nothing to extract: download it first."
                states[artifact.id] = ArtifactState.NOT_DOWNLOADED
            }
            return false
        }
        if (cachedSha256(archive) != artifact.sha256) {
            hashCache.remove(archive.absolutePath)
            Snapshot.withMutableSnapshot {
                messages[artifact.id] = "Refusing to extract: the archive does not match its pinned SHA-256."
                states[artifact.id] = ArtifactState.FAILED
            }
            log.add(TerminalLine(LineKind.ERROR, "extract refused for ${artifact.fileName}: hash mismatch"))
            return false
        }
        setState(artifact.id, ArtifactState.EXTRACTING)
        val ok = runCatching { extractor(archive, rootfsDir) }.getOrDefault(false)
        val ready = ok && installedCheck(artifact)
        setState(artifact.id, if (ready) ArtifactState.READY else ArtifactState.FAILED)
        val message = when {
            ready && artifact.id == RuntimeManifest.FIREFOX_ESR.id ->
                "Installed to ${File(rootfsDir, "opt/firefox-esr").absolutePath}."
            ready -> "Extracted to ${rootfsDir.absolutePath}."
            else ->
                "Extraction did not produce a usable ${if (artifact.id == RuntimeManifest.ROOTFS.id) "rootfs" else artifact.label} install. " +
                    "The app's own TAR reader ran in-process (no proot needed); check the log for the failing entry. " +
                    "Device nodes are skipped on purpose - proot emulates the standard /dev entries at runtime."
        }
        Snapshot.withMutableSnapshot { messages[artifact.id] = message }
        log.add(
            TerminalLine(if (ready) LineKind.INFO else LineKind.ERROR, "extract ${artifact.fileName}: $message"),
        )
        return ready
    }

    /** Removes the archive, its partial download and the artifact's unpacked tree. */
    fun remove(artifact: Artifact): Boolean {
        val removed = runCatching { session.remove(artifact, downloadDir) }.getOrDefault(false)
        if (artifact.id == RuntimeManifest.ROOTFS.id && rootfsDir.isDirectory) {
            runCatching { rootfsDir.deleteRecursively() }
        }
        if (artifact.id == RuntimeManifest.FIREFOX_ESR.id) {
            runCatching { File(rootfsDir, "opt/firefox-esr").deleteRecursively() }
        }
        hashCache.clear()
        val state = scan(artifact)
        Snapshot.withMutableSnapshot {
            states[artifact.id] = state
            messages[artifact.id] = if (removed) "Removed ${artifact.fileName}." else "Nothing to remove."
        }
        return removed
    }

    /** Free space on the partition that holds the downloads, for the UI's warning. */
    /** Cached: `usableSpace` is a statfs syscall per call and this is read during layout. */
    private var freeBytesCache = -1L
    fun freeBytes(): Long {
        if (freeBytesCache < 0) {
            freeBytesCache = runCatching { downloadDir.usableSpace }.getOrDefault(0L)
                .let { if (it > 0) it else runCatching { downloadDir.freeSpace }.getOrDefault(0L) }
        }
        return freeBytesCache
    }

    fun invalidateFreeSpace() {
        freeBytesCache = -1L
    }
}
