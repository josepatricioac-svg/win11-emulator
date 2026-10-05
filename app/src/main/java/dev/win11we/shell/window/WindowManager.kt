package dev.win11we.shell.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import android.os.Environment
import dev.win11we.shell.R
import dev.win11we.shell.ui.explorer.ExplorerState
import java.io.File

/** Every app the shell knows about. Order == taskbar / Start menu order. */
val ShellApps: List<AppSpec> = listOf(
    AppSpec(
        id = "explorer",
        label = "File Explorer",
        iconRes = R.drawable.ic_app_explorer,
        title = { window ->
            val current = window.explorerState()?.current
            when {
                current == null -> "File Explorer"
                current.name.isBlank() -> "Phone (C:)"
                else -> current.name
            }
        },
    ),
    AppSpec("notepad", "Notepad", R.drawable.ic_app_notepad),
    AppSpec("settings", "Settings", R.drawable.ic_app_settings),
    AppSpec("terminal", "Terminal", R.drawable.ic_app_terminal),
    AppSpec("taskmgr", "Task Manager", R.drawable.ic_app_taskmgr),
    AppSpec("containers", "Container Manager", R.drawable.ic_app_container, enabled = false),
)

/** Owns the window list, the z-order and which window is on top. */
@Stable
class WindowManager(
    /**
     * Creates the per-window app state. Injected so the manager stays testable on
     * the JVM (no android.os.Environment); the shell wires Explorer's root here.
     */
    private val payloadFactory: (AppSpec) -> Any? = { null },
) {
    val windows: SnapshotStateList<WindowState> = mutableStateListOf()
    var focusedId by mutableStateOf<Int?>(null)
        private set
    private var nextId = 1
    private var nextZ = 1

    /**
     * Apps that are not ported yet still open a window that says so, instead of
     * silently ignoring the click.
     */
    fun open(app: AppSpec, desktop: DpSize): WindowState {
        val size = DpSize(
            width = (desktop.width * 0.62f).coerceIn(420.dp, 980.dp),
            height = (desktop.height * 0.66f).coerceIn(320.dp, 760.dp),
        )
        val cascade = 28.dp * (windows.size % 6)
        val window = WindowState(
            id = nextId++,
            app = app,
            initialOffset = DpOffset(
                x = ((desktop.width - size.width) / 2f + cascade).coerceAtLeast(0.dp),
                y = ((desktop.height - size.height) / 2f + cascade - 12.dp).coerceAtLeast(0.dp),
            ),
            initialSize = size,
        )
        window.payload = payloadFactory(app)
        window.zIndex = nextZ++
        windows.add(window)
        focusedId = window.id
        return window
    }

    fun close(window: WindowState) {
        windows.remove(window)
        if (focusedId == window.id) focusedId = windows.maxByOrNull { it.zIndex }?.id
    }

    fun focus(window: WindowState) {
        if (focusedId == window.id && !window.minimized) return
        window.minimized = false
        window.zIndex = nextZ++
        focusedId = window.id
    }

    /** Taskbar click: closed -> open, minimized -> restore, focused -> minimize, else -> focus. */
    fun toggle(app: AppSpec, desktop: DpSize) {
        val running = windows.filter { it.app.id == app.id }
        when {
            running.isEmpty() -> open(app, desktop)
            running.any { focusedId == it.id && !it.minimized } -> running.forEach { it.minimized = true }
            else -> focus(running.maxByOrNull { it.zIndex } ?: running.first())
        }
    }

    fun minimize(window: WindowState) {
        window.minimized = true
        if (focusedId == window.id) {
            focusedId = windows.filter { !it.minimized }.maxByOrNull { it.zIndex }?.id
        }
    }
}

@Composable
fun rememberWindowManager(): WindowManager = remember {
    val root = Environment.getExternalStorageDirectory()
    WindowManager { app ->
        if (app.id == "explorer") {
            ExplorerState(root = if (root.isDirectory) root else File(root.parent ?: "/"))
        } else {
            null
        }
    }
}

/**
 * Explorer's navigation state, rooted at shared storage ("Phone (C:)").
 * The Wine prefix (Z:) is mounted here in R1/R3, SAF drives in P1.
 */
fun WindowState.explorerState(): ExplorerState? = payload as? ExplorerState
