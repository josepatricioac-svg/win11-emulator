package dev.win11we.shell.ui.start

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.win11we.shell.R
import dev.win11we.shell.ui.theme.AccentBlue
import dev.win11we.shell.ui.theme.CardSurface
import dev.win11we.shell.ui.theme.HoverLight
import dev.win11we.shell.ui.theme.PressedLight
import dev.win11we.shell.ui.theme.StartSurface
import dev.win11we.shell.ui.theme.StrokeSubtle
import dev.win11we.shell.ui.theme.TextPrimary
import dev.win11we.shell.ui.theme.TextSecondary
import dev.win11we.shell.ui.theme.TextTertiary
import dev.win11we.shell.window.AppSpec
import dev.win11we.shell.window.ShellApps

private val PanelShape = RoundedCornerShape(14.dp)
private val TileShape = RoundedCornerShape(8.dp)

/** Scrim + animated Start panel anchored above the taskbar. */
@Composable
fun StartMenuPanel(
    open: Boolean,
    onDismiss: () -> Unit,
    onLaunch: (AppSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = open,
        enter = fadeIn(tween(140)) + slideInVertically(tween(200)) { it / 8 },
        exit = fadeOut(tween(120)) + slideOutVertically(tween(160)) { it / 8 },
        modifier = modifier.fillMaxSize(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
            StartMenu(
                onLaunch = onLaunch,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(bottom = 66.dp)
                    .widthIn(max = 640.dp),
            )
        }
    }
}

@Composable
private fun StartMenu(onLaunch: (AppSpec) -> Unit, modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    var showAllApps by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(StartSurface)
            .border(1.dp, StrokeSubtle, PanelShape)
            .padding(horizontal = 22.dp, vertical = 20.dp),
    ) {
        StartSearchBar(query = query, onQueryChange = { query = it })
        Spacer(Modifier.height(18.dp))

        val apps = ShellApps.filter { it.label.contains(query.trim(), ignoreCase = true) }
        if (showAllApps) {
            AllAppsHeader(onBack = { showAllApps = false })
            Spacer(Modifier.height(10.dp))
            AllAppsList(apps = apps, onLaunch = onLaunch)
        } else {
            SectionHeader(title = "Pinned", actionLabel = "All apps") { showAllApps = true }
            Spacer(Modifier.height(10.dp))
            PinnedGrid(apps = apps, onLaunch = onLaunch)
            Spacer(Modifier.height(20.dp))
            SectionHeader(title = "Recommended", actionLabel = null) {}
            Spacer(Modifier.height(10.dp))
            RecommendedGrid(onLaunch = onLaunch)
        }

        Spacer(Modifier.height(18.dp))
        StartBottomBar()
    }
}

@Composable
private fun StartSearchBar(query: String, onQueryChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(50))
            .background(CardSurface)
            .border(1.dp, StrokeSubtle, RoundedCornerShape(50))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_search),
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text("Search apps and files", color = TextTertiary, fontSize = 13.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp),
                cursorBrush = SolidColor(AccentBlue),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, actionLabel: String?, onAction: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        if (actionLabel != null) {
            Text(
                text = actionLabel,
                color = TextPrimary,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(CardSurface)
                    .clickable(onClickLabel = actionLabel, onClick = onAction)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun PinnedGrid(apps: List<AppSpec>, onLaunch: (AppSpec) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 88.dp),
        modifier = Modifier.fillMaxWidth().height(196.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        userScrollEnabled = false,
    ) {
        items(apps) { app -> PinnedTile(app = app, onLaunch = onLaunch) }
    }
}

@Composable
private fun PinnedTile(app: AppSpec, onLaunch: (AppSpec) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        modifier = Modifier
            .clip(TileShape)
            .background(if (pressed) PressedLight else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true),
                enabled = app.enabled,
                onClickLabel = app.label,
                onClick = { onLaunch(app) },
            )
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(app.iconRes), null, tint = Color.Unspecified, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            text = app.label,
            color = if (app.enabled) TextPrimary else TextTertiary,
            fontSize = 11.5.sp,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
        if (!app.enabled) {
            Text("Soon", color = TextTertiary, fontSize = 10.sp)
        }
    }
}

@Composable
private fun AllAppsHeader(onBack: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("All apps", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(CardSurface)
                .clickable(onClickLabel = "Back", onClick = onBack)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_all_apps), null, tint = TextPrimary, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(7.dp))
            Text("Back", color = TextPrimary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun AllAppsList(apps: List<AppSpec>, onLaunch: (AppSpec) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxWidth().height(320.dp)) {
        items(apps) { app ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(TileShape)
                    .clickable(enabled = app.enabled, onClickLabel = app.label, onClick = { onLaunch(app) })
                    .padding(horizontal = 8.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(app.iconRes), null, tint = Color.Unspecified, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Text(app.label, color = if (app.enabled) TextPrimary else TextTertiary, fontSize = 13.sp,
                    modifier = Modifier.weight(1f))
                if (!app.enabled) Text("Soon", color = TextTertiary, fontSize = 11.sp)
            }
        }
    }
}

/** N1: no recent documents yet; shows the shell's own next steps honestly. */
@Composable
private fun RecommendedGrid(onLaunch: (AppSpec) -> Unit) {
    val notepad = ShellApps.first { it.id == "notepad" }
    val explorer = ShellApps.first { it.id == "explorer" }
    val recent = listOf(
        RecommendedEntry("Untitled.txt", "Notepad - empty buffer", notepad),
        RecommendedEntry("This PC", "No container mounted yet", explorer),
    )
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxWidth().height(116.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        userScrollEnabled = false,
    ) {
        items(recent) { entry ->
            RecommendedTile(title = entry.title, subtitle = entry.subtitle, onOpen = { onLaunch(entry.app) })
        }
    }
}

private data class RecommendedEntry(val title: String, val subtitle: String, val app: AppSpec)

@Composable
private fun RecommendedTile(title: String, subtitle: String, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(TileShape)
            .background(CardSurface)
            .clickable(onClickLabel = title, onClick = onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_app_notepad), null, tint = Color.Unspecified, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = TextTertiary, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StartBottomBar() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClickLabel = "Account", onClick = {})
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(AccentBlue.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_person), "Account", tint = AccentBlue, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text("User", color = TextPrimary, fontSize = 12.5.sp)
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(HoverLight)
                .clickable(onClickLabel = "Power", onClick = {}),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_power), "Power", tint = TextPrimary, modifier = Modifier.size(16.dp))
        }
    }
}
