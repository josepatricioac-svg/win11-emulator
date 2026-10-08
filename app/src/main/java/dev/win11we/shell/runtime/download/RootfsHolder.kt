package dev.win11we.shell.runtime.download

import android.content.Context
import dev.win11we.shell.runtime.RuntimePaths
import dev.win11we.shell.ui.terminal.SharedLog
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
            // Real extraction needs `proot --rootfs / -0 tar` (device nodes, fake root),
            // which only exists once R1's binaries are vendored - see RootfsManager.extract.
            extractor = { _, _ -> false },
        )
    }

    /** For tests and for a settings-driven relocation. */
    fun reset() {
        instance = null
    }
}
