package dev.win11we.shell.runtime.download

import android.content.Context
import dev.win11we.shell.runtime.RuntimePaths
import dev.win11we.shell.ui.terminal.LineKind
import dev.win11we.shell.ui.terminal.SharedLog
import dev.win11we.shell.ui.terminal.TerminalLine
import java.io.File

/**
 * Process-wide [RootfsManager]. Downloads run on a worker thread; the manager's
 * state is snapshot-backed so the UI recomposes as bytes arrive.
 */
object RootfsHolder {

    @Volatile
    private var instance: RootfsManager? = null

    fun get(context: Context): RootfsManager = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }

    private fun create(appContext: Context): RootfsManager {
        val filesDir = appContext.filesDir
        return RootfsManager(
            downloadDir = File(filesDir, "downloads"),
            rootfsDir = RuntimePaths.defaultRootfs(filesDir),
            session = DownloadSession(
                transfer = HttpTransfer(),
                freeSpace = FreeSpaceProvider { dir -> dir.usableSpace },
            ),
            log = SharedLog.buffer,
            // Real extraction, no proot needed: the archives are unpacked by the
            // app's own TAR reader (gzip via the JDK, xz via org.tukaani). Device
            // nodes are skipped - proot emulates the standard /dev entries at
            // runtime, which is how every proot-based distro extracts a rootfs.
            extractor = { archive, rootfsDir, onProgress -> extract(archive, rootfsDir, onProgress) },
        )
    }

    private fun extract(archive: File, rootfsDir: File, onProgress: (Float) -> Unit): Boolean = runCatching {
        val log: (String) -> Unit = { line -> SharedLog.buffer.add(TerminalLine(LineKind.SYSTEM, line)) }
        // Real progress: the TAR reader counts the COMPRESSED bytes it consumes from
        // the archive file, so the fraction is honest even for the multi-GB bundle.
        val progress: (Long, Long) -> Unit = { read, total ->
            if (total > 0 && read >= 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
        }
        val result = when {
            // Mozilla's Firefox tarball unpacks into the rootfs, top dir stripped.
            archive.name.startsWith("firefox-") && archive.name.endsWith(".tar.xz") -> {
                val dest = File(rootfsDir, "opt/firefox-esr")
                dest.mkdirs()
                TarExtractor.open(archive, log).extractTo(dest, stripComponents = 1, progress = progress)
            }
            else -> TarExtractor.open(archive, log).extractTo(rootfsDir, progress = progress)
        }
        log(
            "${archive.name}: ${result.files} files, ${result.directories} dirs, " +
                "${result.symlinks} symlinks, ${result.hardlinks} hardlinks, " +
                "${result.skipped} device/fifo entries skipped" +
                (result.error?.let { ", error: $it" } ?: ""),
        )
        result.success
    }.getOrElse { false }

    /** For tests and for a settings-driven relocation. */
    fun reset() {
        instance = null
    }
}
