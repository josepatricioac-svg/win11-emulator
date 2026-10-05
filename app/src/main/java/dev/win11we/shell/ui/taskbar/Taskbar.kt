package dev.win11we.shell.ui.taskbar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.ripple
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.win11we.shell.R
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

private val BarShape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
private val IconShape = RoundedCornerShape(8.dp)

/** Windows 11-style taskbar: centred pins, tray + clock on the right. */
@Composable
fun Taskbar(items: List<TaskbarItem>, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, BarShape)
            .navigationBarsPadding()
            .height(56.dp),
    ) {
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items.forEach { TaskbarButton(it) }
        }
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrayIcons()
            Spacer(Modifier.width(10.dp))
            Clock()
        }
    }
}

@Composable
private fun TaskbarButton(item: TaskbarItem) {
    val label = stringResource(item.labelRes)
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(IconShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true),
                enabled = item.onClick != null,
                onClick = { item.onClick?.invoke() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(item.iconRes),
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** N0 tray: static status glyphs only, no system wiring yet. */
@Composable
private fun TrayIcons() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(R.drawable.ic_terminal, R.drawable.ic_settings, R.drawable.ic_search).forEach { icon ->
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun Clock() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(20.seconds)
        }
    }
    val locale = Locale.getDefault()
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = SimpleDateFormat("HH:mm", locale).format(now),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = SimpleDateFormat("dd/MM/yyyy", locale).format(now),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
    }
}
