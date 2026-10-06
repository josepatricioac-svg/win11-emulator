package dev.win11we.shell.ui.notepad

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val MenuBarColor = Color(0xFFEFF4FA)
private val MenuTextColor = Color(0xFF1B1F27)
private val ItemShape = RoundedCornerShape(5.dp)

/** One top-level menu of the Notepad menu bar. */
data class NotepadMenu(val label: String, val items: List<NotepadMenuItem>)

data class NotepadMenuItem(
    val label: String,
    val enabled: Boolean = true,
    val checked: Boolean = false,
    val shortcut: String? = null,
    val onClick: () -> Unit,
)

/** Windows 11 Notepad menu bar: thin, light, dropdowns on tap. */
@Composable
fun NotepadMenuBar(menus: List<NotepadMenu>, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().background(MenuBarColor).padding(horizontal = 6.dp, vertical = 3.dp)) {
        menus.forEach { menu ->
            var expanded by remember { mutableStateOf(false) }
            Box {
                Text(
                    text = menu.label,
                    color = MenuTextColor,
                    fontSize = 12.5.sp,
                    modifier = Modifier
                        .clip(ItemShape)
                        .clickable(onClickLabel = menu.label) { expanded = true }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    menu.items.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item.label + if (item.checked) "   \u2713" else "", fontSize = 13.sp) },
                            enabled = item.enabled,
                            trailingIcon = {
                                if (item.shortcut != null) {
                                    Text(item.shortcut, color = Color(0xFF7A8494), fontSize = 12.sp)
                                }
                            },
                            onClick = {
                                expanded = false
                                item.onClick()
                            },
                        )
                    }
                }
            }
        }
    }
}
