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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.win11we.shell.R
import dev.win11we.shell.ui.explorer.ExplorerScreen
import dev.win11we.shell.ui.notepad.NotepadScreen
import dev.win11we.shell.ui.notepad.NotepadState
import dev.win11we.shell.window.WindowState
import dev.win11we.shell.window.explorerState

/** Routes a window to its app body; unported apps get an honest placeholder. */
@Composable
fun WindowContent(
    window: WindowState,
    label: String,
    enabled: Boolean,
    onCloseWindow: (Int) -> Unit,
) {
    when (window.app.id) {
        "explorer" -> {
            val state = window.explorerState()
            if (state == null) ComingSoon(label = label, enabled = enabled) else ExplorerScreen(state = state)
        }
        "notepad" -> {
            val state = window.payload as? NotepadState ?: NotepadState()
            NotepadScreen(state = state, windowId = window.id, onCloseWindow = onCloseWindow)
        }
        else -> ComingSoon(label = label, enabled = enabled)
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
