package dev.win11we.shell.runtime.download

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    init {
        artifacts.forEach { states[it.id] = scan(it) }
    }

    fun artifact(id: String): Artifact? = artifacts.firstOrNull { it.id == id }
    fun all(): List<Artifact> = artifacts
    fun stateOf(id: String): ArtifactState = states[id] ?: ArtifactState.NOT_DOWNLOADED

    /** Rootfs is usable only when it has been unpacked. */
    val rootfsReady: Boolean
        get() = rootfsDir.isDirectory && File(rootfsDir, "bin").isDirectory && File(rootfsDir, "usr").isDirectory

    /** Reads what is already on disk without touching the network. */
    fun scan(artifact: Artifact): ArtifactState {
        val target = artifact.targetFile(downloadDir)
        val partial = artifact.partialFile(downloadDir)
        return when {
            artifact.id == RuntimeManifest.ROOTFS.id && rootfsReady -> ArtifactState.READY
            target.isFile -> if (DownloadSession.sha256(target) == artifact.sha256) {
                ArtifactState.VERIFIED
            } else {
                target.delete()
                ArtifactState.FAILED
            }
            partial.isFile -> ArtifactState.PARTIAL
            else -> ArtifactState.NOT_DOWNLOADED
        }
    }

    fun rescan() {
        artifacts.forEach { states[it.id] = scan(it) }
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
        busy = true
        cancelRequested = false
        states[artifact.id] = ArtifactState.DOWNLOADING
        progress[artifact.id] = 0f
        log.add(TerminalLine(LineKind.SYSTEM, "downloading ${artifact.fileName} from ${artifact.url}"))
        val result = session.download(
            artifact = artifact,
            dir = downloadDir,
            onProgress = { done, total ->
                progress[artifact.id] = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
            },
            cancelled = { cancelRequested },
        )
        states[artifact.id] = when (result.outcome) {
            DownloadOutcome.VERIFIED, DownloadOutcome.ALREADY_VERIFIED -> ArtifactState.VERIFIED
            DownloadOutcome.CANCELLED -> ArtifactState.PARTIAL
            else -> ArtifactState.FAILED
        }
        progress[artifact.id] = if (result.success) 1f else progress[artifact.id] ?: 0f
        messages[artifact.id] = result.message
        log.add(
            TerminalLine(
                if (result.success) LineKind.INFO else LineKind.ERROR,
                "${artifact.id}: ${result.outcome} - ${result.message}",
            ),
        )
        busy = false
        return result
    }

    /**
     * Installs a file the user picked with SAF: it is streamed into the download
     * directory and held to the same pinned hash as a network download.
     */
    fun import(artifact: Artifact, input: InputStream, sourceName: String): DownloadResult {
        if (busy) return DownloadResult(DownloadOutcome.CANCELLED, "Another download is already running.")
        busy = true
        downloadDir.mkdirs()
        states[artifact.id] = ArtifactState.VERIFYING
        val target = artifact.targetFile(downloadDir)
        val written = try {
            target.outputStream().use { output -> input.copyTo(output) }
            target.length()
        } catch (error: Exception) {
            busy = false
            states[artifact.id] = ArtifactState.FAILED
            val message = "Import failed: ${error.javaClass.simpleName}: ${error.message}"
            messages[artifact.id] = message
            log.add(TerminalLine(LineKind.ERROR, message))
            return DownloadResult(DownloadOutcome.NETWORK_ERROR, message)
        }
        val hash = DownloadSession.sha256(target)
        busy = false
        return if (hash == artifact.sha256) {
            states[artifact.id] = ArtifactState.VERIFIED
            val message = "Imported $sourceName (${DownloadSession.formatBytes(written)}), SHA-256 matches the pin."
            messages[artifact.id] = message
            log.add(TerminalLine(LineKind.INFO, message))
            DownloadResult(DownloadOutcome.VERIFIED, message, written, hash)
        } else {
            target.delete()
            states[artifact.id] = ArtifactState.FAILED
            val message = "Imported file does not match the pinned SHA-256 for ${artifact.fileName} " +
                "(expected ${artifact.sha256.take(16)}…, got ${hash?.take(16) ?: "unreadable"}). Nothing was kept."
            messages[artifact.id] = message
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
            messages[artifact.id] = "Nothing to extract: download it first."
            states[artifact.id] = ArtifactState.NOT_DOWNLOADED
            return false
        }
        if (DownloadSession.sha256(archive) != artifact.sha256) {
            messages[artifact.id] = "Refusing to extract: the archive does not match its pinned SHA-256."
            states[artifact.id] = ArtifactState.FAILED
            log.add(TerminalLine(LineKind.ERROR, "extract refused for ${artifact.fileName}: hash mismatch"))
            return false
        }
        states[artifact.id] = ArtifactState.EXTRACTING
        val ok = extractor(archive, rootfsDir)
        states[artifact.id] = if (ok && rootfsReady) ArtifactState.READY else ArtifactState.FAILED
        messages[artifact.id] = if (ok && rootfsReady) {
            "Extracted to ${rootfsDir.absolutePath}."
        } else {
            "Extraction did not produce a usable rootfs. proot-based untar arrives with the R1 binaries " +
                "(device nodes and root ownership need `proot --rootfs / -0 tar`)."
        }
        log.add(
            TerminalLine(
                if (states[artifact.id] == ArtifactState.READY) LineKind.INFO else LineKind.ERROR,
                "extract ${artifact.fileName}: ${messages[artifact.id]}",
            ),
        )
        return states[artifact.id] == ArtifactState.READY
    }

    /** Removes the archive, its partial download and (for the rootfs) the unpacked tree. */
    fun remove(artifact: Artifact): Boolean {
        val removed = session.remove(artifact, downloadDir)
        if (artifact.id == RuntimeManifest.ROOTFS.id && rootfsDir.isDirectory) {
            rootfsDir.deleteRecursively()
        }
        states[artifact.id] = scan(artifact)
        messages[artifact.id] = if (removed) "Removed ${artifact.fileName}." else "Nothing to remove."
        return removed
    }

    /** Free space on the partition that holds the downloads, for the UI's warning. */
    fun freeBytes(): Long = downloadDir.usableSpace.let { if (it > 0) it else downloadDir.freeSpace }
}
