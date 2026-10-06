package dev.win11we.shell.ui.taskbar

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.win11we.shell.R
import dev.win11we.shell.ui.theme.AccentBlue
import dev.win11we.shell.ui.theme.HoverLight
import dev.win11we.shell.ui.theme.PressedLight
import dev.win11we.shell.ui.theme.TextPrimary
import dev.win11we.shell.ui.theme.TextSecondary
import dev.win11we.shell.ui.theme.TextTertiary
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

private val ButtonShape = RoundedCornerShape(8.dp)

/** One entry of a pin's jump list (long-press / right-click on a taskbar pin). */
data class PinMenuEntry(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * A pinned taskbar button: 44x40, Windows 11 running/focus indicator, and a jump
 * list that is only composed while [menuOpen] - a permanently composed popup
 * would draw every pin's menu at once and steal input from the shell.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskbarButton(
    @DrawableRes iconRes: Int,
    label: String,
    running: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
    menuEntries: List<PinMenuEntry> = emptyList(),
) {
    var menuOpen by remember(label, menuEntries.size) { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Box {
        Box(
            modifier = Modifier
                .size(width = 44.dp, height = 40.dp)
                .clip(ButtonShape)
                .background(
                    when {
                        pressed -> PressedLight
                        menuOpen || focused -> HoverLight
                        else -> Color.Transparent
                    },
                )
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClickLabel = label,
                    onLongClickLabel = "$label options",
                    onLongClick = { if (menuEntries.isNotEmpty()) menuOpen = true },
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = label,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.height(3.dp))
                Box(
                    Modifier
                        .width(if (focused) 16.dp else if (running) 6.dp else 0.dp)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(if (focused) AccentBlue else TextSecondary),
                )
            }
        }

        // expanded controls the popup: nothing is composed until the user asks for it.
        DropdownMenu(
            expanded = menuOpen && menuEntries.isNotEmpty(),
            onDismissRequest = { menuOpen = false },
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
        ) {
            DropdownMenuItem(
                text = { Text(label, color = TextTertiary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                enabled = false,
                onClick = {},
            )
            menuEntries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label, fontSize = 12.5.sp) },
                    enabled = entry.enabled,
                    onClick = {
                        menuOpen = false
                        entry.onClick()
                    },
                )
            }
        }
    }
}

/** Left cluster: widgets chip. The label hides on narrow screens. */
@Composable
fun WidgetsChip() {
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(HoverLight)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_app_container),
                contentDescription = "Widgets",
                tint = Color.Unspecified,
                modifier = Modifier.size(16.dp),
            )
            if (wide) {
                Spacer(Modifier.width(8.dp))
                Text("Widgets", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** Tray: overflow chevron + network/volume/battery glyphs (real wiring in P1). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TrayIcons() {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text("Hidden icons", fontSize = 11.5.sp) } },
        state = rememberTooltipState(),
    ) {
        Row(
            modifier = Modifier
                .clip(ButtonShape)
                .background(if (pressed) PressedLight else Color.Transparent)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClickLabel = "Hidden icons",
                    onLongClickLabel = "Hidden icons",
                    onLongClick = {},
                    onClick = {},
                )
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(painterResource(R.drawable.ic_chevron_up), null, tint = TextSecondary, modifier = Modifier.size(12.dp))
            Icon(painterResource(R.drawable.ic_wifi), "Network", tint = TextPrimary, modifier = Modifier.size(15.dp))
            Icon(painterResource(R.drawable.ic_volume), "Volume", tint = TextPrimary, modifier = Modifier.size(15.dp))
            Icon(painterResource(R.drawable.ic_battery), "Battery", tint = TextPrimary, modifier = Modifier.size(15.dp))
        }
    }
}

/** Clock + date, ticks every 15s. The notification centre opens here later. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ClockButton() {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(15.seconds)
        }
    }
    val locale = Locale.getDefault()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(SimpleDateFormat("EEEE, d MMMM", locale).format(now), fontSize = 11.5.sp) } },
        state = rememberTooltipState(),
    ) {
        Column(
            modifier = Modifier
                .clip(ButtonShape)
                .background(if (pressed) PressedLight else Color.Transparent)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClickLabel = "Date and time",
                    onLongClickLabel = "Date and time",
                    onLongClick = {},
                    onClick = {},
                )
                .padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = SimpleDateFormat("HH:mm", locale).format(now),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = SimpleDateFormat("dd/MM/yyyy", locale).format(now),
                color = TextSecondary,
                fontSize = 11.sp,
            )
        }
    }
}
