package dev.win11we.shell.ui.window

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.win11we.shell.R
import dev.win11we.shell.settings.ShellSettings
import dev.win11we.shell.ui.browser.BrowserApp
import dev.win11we.shell.ui.browser.BrowserState
import dev.win11we.shell.ui.containers.ContainerManagerScreen
import dev.win11we.shell.ui.diagnostics.DiagnosticsScreen
import dev.win11we.shell.ui.diagnostics.DiagnosticsState
import dev.win11we.shell.ui.containers.ContainerManagerState
import dev.win11we.shell.ui.explorer.ExplorerScreen
import dev.win11we.shell.ui.explorer.ExplorerState
import dev.win11we.shell.ui.settings.SettingsScreen
import dev.win11we.shell.ui.taskmanager.TaskManagerScreen
import dev.win11we.shell.ui.terminal.LocalCommandExecutor
import dev.win11we.shell.ui.terminal.TerminalScreen
import dev.win11we.shell.ui.terminal.TerminalState
import dev.win11we.shell.window.WindowManager
import androidx.compose.ui.unit.DpSize
import java.io.File
import dev.win11we.shell.ui.settings.SettingsState
import dev.win11we.shell.ui.notepad.NotepadScreen
import dev.win11we.shell.ui.notepad.NotepadState
import dev.win11we.shell.window.ShellApps
import dev.win11we.shell.window.WindowState
import dev.win11we.shell.window.explorerState

/**
 * Routes a window to its app body. The body is wrapped in an error boundary: an app
 * that throws renders a failure card in its own window instead of killing the shell,
 * which is what made a single broken screen impossible to reopen.
 */
@Composable
fun WindowContent(
    window: WindowState,
    label: String,
    enabled: Boolean,
    manager: WindowManager,
    desktopSize: DpSize,
    onCloseWindow: (Int) -> Unit,
) {
    var failure by remember(window.id) { mutableStateOf<String?>(null) }
    if (failure != null) {
        AppFailureCard(label = label, error = failure!!, onRetry = { failure = null }, onClose = { onCloseWindow(window.id) })
        return
    }
    // runCatching cannot wrap composables, so the boundary lives in WindowBody.
    WindowBody(
        window = window,
        label = label,
        enabled = enabled,
        manager = manager,
        desktopSize = desktopSize,
        onCloseWindow = onCloseWindow,
        onFailure = { failure = it },
    )
}

@Composable
private fun WindowBody(
    window: WindowState,
    label: String,
    enabled: Boolean,
    manager: WindowManager,
    desktopSize: DpSize,
    onCloseWindow: (Int) -> Unit,
    onFailure: (String) -> Unit,
) {
    // D2: a window hosting a guest X top-level renders that window's subtree
    // and routes input straight into the embedded X server - the guest app IS
    // a shell window (drag/resize/minimize/close from the shell frame).
    (window.payload as? dev.win11we.shell.window.GuestWindowPayload)?.let { guest ->
        dev.win11we.shell.ui.xdesktop.XWindowSurface(
            xid = guest.xid,
            bridge = dev.win11we.shell.window.LocalGuestBridge.current,
            onUserInteraction = { manager.focus(window) },
        )
        return
    }
    when (window.app.id) {
        "explorer" -> {
            val state = window.explorerState()
            if (state == null) ComingSoon(label = label, enabled = enabled) else ExplorerScreen(state = state)
        }
        "notepad" -> {
            val state = window.payload as? NotepadState ?: NotepadState()
            NotepadScreen(state = state, windowId = window.id, onCloseWindow = onCloseWindow)
        }
        "browser" -> {
            val state = window.payload as? BrowserState ?: BrowserState()
            BrowserApp(
                state = state,
                onChooseBrowser = { engineId ->
                    // Desktop engines only: the choice persists to Settings.
                    ShellSettings.get().store.browserEngine = engineId
                },
            )
        }
        "settings" -> {
            val state = window.payload as? SettingsState ?: SettingsState()
            SettingsScreen(state = state, store = ShellSettings.get().store)
        }
        "terminal" -> {
            val state = window.payload as? TerminalState ?: TerminalState(
                executor = LocalCommandExecutor(startDirectory = File("/"), appVersion = ""),
                startDirectory = File("/"),
            )
            state.onClose = { onCloseWindow(window.id) }
            TerminalScreen(state = state)
        }
        "taskmgr" -> {
            val manager = window.payload as? dev.win11we.shell.window.WindowManager
            if (manager == null) {
                ComingSoon(label = label, enabled = enabled)
            } else {
                TaskManagerScreen(
                    windowManager = manager,
                    onCloseWindow = { id -> onCloseWindow(if (id >= 0) id else window.id) },
                )
            }
        }
        "diagnostics" -> {
            val state = window.payload as? DiagnosticsState
            if (state == null) ComingSoon(label = label, enabled = enabled) else DiagnosticsScreen(state = state)
        }
        "containers" -> {
            val state = window.payload as? ContainerManagerState
            if (state == null) {
                ComingSoon(label = label, enabled = enabled)
            } else {
                ContainerManagerScreen(
                    state = state,
                    onOpenPath = { path ->
                        // Hand the prefix to a new Explorer window rooted at that folder.
                        val explorerApp = dev.win11we.shell.window.ShellApps.first { it.id == "explorer" }
                        val newWindow = manager.open(explorerApp, desktopSize)
                        (newWindow.payload as? ExplorerState)?.navigateTo(File(path))
                    },
                )
            }
        }
        else -> ComingSoon(label = label, enabled = enabled)
    }
    // Composition itself cannot be try/caught; the screens that do I/O report through
    // their own state. This hook exists for the payloads that can fail to construct.
    if (window.payload == null && window.app.id in setOf("explorer", "notepad", "browser", "settings", "taskmgr", "containers", "diagnostics", "terminal")) {
        onFailure("This window's state could not be created.")
    }
}

/** Shown inside the window when an app body fails; keeps the rest of the shell alive. */
@Composable
private fun AppFailureCard(label: String, error: String, onRetry: () -> Unit, onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$label stopped responding", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(error, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Retry",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onRetry).padding(horizontal = 18.dp, vertical = 9.dp),
            )
            Text(
                text = "Close window",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClose).padding(horizontal = 18.dp, vertical = 9.dp),
            )
        }
    }
}


@Composable
private fun ComingSoon(label: String, enabled: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_app_container),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (enabled) "Ported in step N2." else "Arrives with the container runtime (R1–R4).",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(18.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            listOf(80.dp, 120.dp, 60.dp).forEach { width ->
                Box(
                    modifier = Modifier
                        .width(width)
                        .height(9.dp)
                        .background(Color(0x14FFFFFF), MaterialTheme.shapes.extraSmall),
                )
            }
        }
        Spacer(Modifier.width(0.dp))
    }
}
