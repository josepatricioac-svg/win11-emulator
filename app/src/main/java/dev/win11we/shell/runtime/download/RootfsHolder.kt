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
            extractor = { archive, rootfsDir -> extract(archive, rootfsDir) },
        )
    }

    private fun extract(archive: File, rootfsDir: File): Boolean = runCatching {
        val log: (String) -> Unit = { line -> SharedLog.buffer.add(TerminalLine(LineKind.SYSTEM, line)) }
        val result = when {
            // Mozilla's Firefox tarball unpacks into the rootfs, top dir stripped.
            archive.name.startsWith("firefox-") && archive.name.endsWith(".tar.xz") -> {
                val dest = File(rootfsDir, "opt/firefox-esr")
                dest.mkdirs()
                TarExtractor.open(archive, log).extractTo(dest, stripComponents = 1)
            }
            else -> TarExtractor.open(archive, log).extractTo(rootfsDir)
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
