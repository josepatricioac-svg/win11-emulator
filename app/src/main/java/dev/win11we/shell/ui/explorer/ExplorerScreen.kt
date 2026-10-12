package dev.win11we.shell.ui.explorer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.core.content.ContextCompat
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
import java.io.File
import kotlinx.coroutines.delay

private val IconBtn = RoundedCornerShape(6.dp)

/** Whole Explorer window body: toolbar, navigation pane, file view, status bar. */
@Composable
fun ExplorerScreen(state: ExplorerState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val wide = LocalConfiguration.current.screenWidthDp >= 720
    // API < 30: the classic runtime permission still shows every file.
    var needsPermission by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> needsPermission = !granted }
    LaunchedEffect(Unit) {
        if (needsPermission) permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    // API 30+: only "All files access" reveals non-media files such as .exe/.msi. It is a
    // Settings toggle, not a dialog, so poll until the user comes back with it granted.
    var needsAllFiles by remember { mutableStateOf(!allFilesAccessGranted()) }
    LaunchedEffect(needsAllFiles) {
        while (needsAllFiles) {
            delay(1000)
            if (allFilesAccessGranted()) needsAllFiles = false
        }
    }

    var pendingNewFolder by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }
    var contextEntry by remember { mutableStateOf<FileEntry?>(null) }

    val entries = state.entries()
    val selected = remember(state.selection.toList()) { state.selection.toSet() }
    val gestures = ExplorerGestures(
        onSelect = { file, additive -> state.select(file, additive) },
        onOpen = { entry ->
            if (entry.isDirectory) {
                state.navigateTo(entry.file)
            } else {
                val windowsProgram = entry.extension in setOf("exe", "msi", "bat")
                val message = if (windowsProgram) {
                    "${entry.name}: running Windows programs needs libbox64.so + Wine (roadmap R1/R4) - not available yet."
                } else {
                    "Opening ${entry.name} lands with the N2 apps / R1 runtime."
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        },
        onContext = { entry, select ->
            if (select) state.select(entry.file, false)
            contextEntry = entry
        },
    )

    Row(modifier = modifier.fillMaxSize()) {
        if (wide) {
            NavigationPane(state = state, modifier = Modifier.width(216.dp).fillMaxHeight())
        }
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            ExplorerToolbar(
                state = state,
                showLocationsButton = !wide,
                onNewFolder = { pendingNewFolder = true },
                modifier = Modifier.fillMaxWidth(),
            )
            when {
                needsAllFiles -> AllFilesAccessView(
                    onGrant = { openAllFilesAccessSettings(context) },
                    modifier = Modifier.fillMaxSize(),
                )
                needsPermission -> EmptyView("Storage permission is needed to browse this device.")
                !state.current.isDirectory -> EmptyView("This folder is not readable.")
                entries.isEmpty() -> EmptyView(if (state.query.isBlank()) "This folder is empty." else "No matches.")
                state.viewMode == ExplorerViewMode.DETAILS -> DetailsView(
                    entries = entries,
                    selected = selected,
                    gestures = gestures,
                    sortKey = state.sortKey,
                    ascending = state.ascending,
                    onSort = state::toggleSort,
                    modifier = Modifier.weight(1f),
                )
                else -> TilesView(
                    entries = entries,
                    selected = selected,
                    gestures = gestures,
                    modifier = Modifier.weight(1f),
                )
            }
            StatusBar(count = entries.size, selectedCount = state.selection.size, state = state)
        }
    }

    contextEntry?.let { entry ->
        EntryMenu(
            entry = entry,
            onDismiss = { contextEntry = null },
            onOpen = { contextEntry = null; gestures.onOpen(entry) },
            onRename = { contextEntry = null; renameTarget = entry.file },
            onDelete = { contextEntry = null; deleteTarget = entry.file },
        )
    }
    if (pendingNewFolder) {
        NameDialog(
            title = "New folder",
            initial = "New folder",
            onDismiss = { pendingNewFolder = false },
            onConfirm = { name ->
                pendingNewFolder = false
                if (state.createFolder(name) == null) toast(context, "Could not create \"$name\".")
            },
        )
    }
    renameTarget?.let { file ->
        NameDialog(
            title = "Rename",
            initial = file.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                if (state.rename(file, name) == null) toast(context, "Could not rename to \"$name\".")
            },
        )
    }
    deleteTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = StartSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("Delete ${if (file.isDirectory) "folder" else "file"}?") },
            text = { Text("\"${file.name}\" will be removed permanently.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    if (!state.delete(file)) toast(context, "Delete failed.")
                }) { Text("Delete", color = Color(0xFFFF6B5A)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel", color = TextSecondary) } },
        )
    }
}

private fun toast(context: android.content.Context, message: String) =
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

@Composable
private fun ToolbarButton(
    iconRes: Int,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(IconBtn)
            .background(if (pressed) PressedLight else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClickLabel = description,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = description,
            tint = if (enabled) TextPrimary else TextTertiary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** Command bar + address bar (breadcrumb) + search box. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExplorerToolbar(
    state: ExplorerState,
    showLocationsButton: Boolean,
    onNewFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewMenu by remember { mutableStateOf(false) }
    var locationsMenu by remember { mutableStateOf(false) }
    var newMenu by remember { mutableStateOf(false) }
    var editingPath by remember { mutableStateOf(false) }
    var pathDraft by remember { mutableStateOf("") }

    Column(modifier = modifier.background(Color(0x0AFFFFFF))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ToolbarButton(R.drawable.ic_arrow_left, "Back", state.canGoBack) { state.back() }
            ToolbarButton(R.drawable.ic_arrow_right, "Forward", state.canGoForward) { state.forward() }
            ToolbarButton(R.drawable.ic_arrow_up, "Up", state.canGoUp) { state.up() }
            ToolbarButton(R.drawable.ic_refresh, "Refresh") { state.refresh() }
            if (showLocationsButton) {
                Spacer(Modifier.width(4.dp))
                Box {
                    ToolbarButton(R.drawable.ic_home, "Locations") { locationsMenu = true }
                    DropdownMenu(expanded = locationsMenu, onDismissRequest = { locationsMenu = false }) {
                        NavigationItems(state = state, onPicked = { locationsMenu = false })
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Box {
                Row(
                    modifier = Modifier
                        .clip(IconBtn)
                        .background(CardSurface)
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { newMenu = true },
                            onLongClick = {},
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_new_folder), null, tint = TextPrimary, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("New", color = TextPrimary, fontSize = 12.sp)
                    Spacer(Modifier.width(4.dp))
                    Icon(painterResource(R.drawable.ic_arrow_down), null, tint = TextSecondary, modifier = Modifier.size(12.dp))
                }
                DropdownMenu(expanded = newMenu, onDismissRequest = { newMenu = false }) {
                    DropdownMenuItem(text = { Text("Folder") }, onClick = { newMenu = false; onNewFolder() })
                    DropdownMenuItem(
                        text = { Text("Text document (N2)") },
                        enabled = false,
                        onClick = { newMenu = false },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Box {
                ToolbarButton(
                    iconRes = if (state.viewMode == ExplorerViewMode.DETAILS) R.drawable.ic_view_list else R.drawable.ic_view_grid,
                    description = "View and sort",
                ) { viewMenu = true }
                DropdownMenu(expanded = viewMenu, onDismissRequest = { viewMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Details") },
                        trailingIcon = { if (state.viewMode == ExplorerViewMode.DETAILS) Check() },
                        onClick = { state.viewMode = ExplorerViewMode.DETAILS },
                    )
                    DropdownMenuItem(
                        text = { Text("Tiles") },
                        trailingIcon = { if (state.viewMode == ExplorerViewMode.GRID) Check() },
                        onClick = { state.viewMode = ExplorerViewMode.GRID },
                    )
                    listOf(SortKey.NAME to "Name", SortKey.DATE to "Date modified", SortKey.TYPE to "Type", SortKey.SIZE to "Size")
                        .forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text("Sort by $label") },
                                trailingIcon = { if (state.sortKey == key) Check() },
                                onClick = { state.toggleSort(key); viewMenu = false },
                            )
                        }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(30.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(CardSurface)
                    .border(1.dp, StrokeSubtle, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            if (!editingPath) {
                                pathDraft = state.current.absolutePath
                                editingPath = true
                            }
                        },
                        onLongClick = {},
                    ),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (editingPath) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(
                            value = pathDraft,
                            onValueChange = { pathDraft = it },
                            singleLine = true,
                            textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp),
                            cursorBrush = SolidColor(AccentBlue),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "Go",
                            color = AccentBlue,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clip(IconBtn)
                                .clickable {
                                    editingPath = false
                                    state.navigateTo(File(pathDraft))
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                } else {
                    Breadcrumb(state = state, onEdit = { editingPath = true })
                }
            }
            Row(
                modifier = Modifier
                    .width(180.dp)
                    .height(30.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(CardSurface)
                    .border(1.dp, StrokeSubtle, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_search), null, tint = TextTertiary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (state.query.isEmpty()) Text("Search", color = TextTertiary, fontSize = 12.sp)
                    BasicTextField(
                        value = state.query,
                        onValueChange = { state.query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp),
                        cursorBrush = SolidColor(AccentBlue),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun Check() {
    Icon(painterResource(R.drawable.ic_arrow_right), contentDescription = null, tint = AccentBlue, modifier = Modifier.size(13.dp))
}

/** "This PC > Phone (C:) > Download"; tapping a crumb navigates there. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Breadcrumb(state: ExplorerState, onEdit: () -> Unit) {
    val trail = remember(state.current) { state.trail() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "This PC",
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.clip(IconBtn).clickable(onClick = onEdit).padding(horizontal = 4.dp),
        )
        trail.forEach { dir ->
            Icon(painterResource(R.drawable.ic_arrow_right), null, tint = TextTertiary, modifier = Modifier.size(11.dp))
            val isCurrent = dir == trail.last()
            Text(
                text = if (dir == trail.first() && dir.name.isBlank()) "Phone (C:)" else dir.name,
                color = if (isCurrent) TextPrimary else TextSecondary,
                fontSize = 12.sp,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 160.dp)
                    .clip(IconBtn)
                    .clickable { if (!isCurrent) state.navigateTo(dir) }
                    .padding(horizontal = 4.dp),
            )
        }
    }
}

/** Quick access + This PC, like the Windows 11 navigation pane (wide windows only). */
@Composable
private fun NavigationPane(state: ExplorerState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color(0x07FFFFFF))
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp, horizontal = 6.dp),
    ) {
        NavigationItems(state = state)
    }
}

/** Grouped location list, shared by the pane and the narrow-window dropdown. */
@Composable
private fun NavigationItems(state: ExplorerState, onPicked: () -> Unit = {}) {
    val storageRoot = remember { Environment.getExternalStorageDirectory().absolutePath }
    val locations = remember(storageRoot) { explorerLocations(storageRoot, prefixPath = null) }
    Column {
        locations.map { it.group }.distinct().forEach { group ->
            Text(
                text = group,
                color = TextTertiary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
            )
            locations.filter { it.group == group }.forEach { location ->
                val active = location.path != null && location.path == state.current.absolutePath
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(IconBtn)
                        .background(if (active) HoverLight else Color.Transparent)
                        .clickable(
                            enabled = location.enabled && location.path != null,
                            onClick = {
                                location.path?.let { state.navigateTo(File(it)) }
                                onPicked()
                            },
                        )
                        .padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(location.iconRes),
                        contentDescription = null,
                        tint = if (location.enabled) Color.Unspecified else TextTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = location.label,
                        color = if (location.enabled) (if (active) AccentBlue else TextPrimary) else TextTertiary,
                        fontSize = 12.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBar(count: Int, selectedCount: Int, state: ExplorerState, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(Color(0x0AFFFFFF))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$count item${if (count == 1) "" else "s"}", color = TextSecondary, fontSize = 11.sp)
        if (selectedCount > 0) {
            Spacer(Modifier.width(14.dp))
            Text("$selectedCount selected", color = TextSecondary, fontSize = 11.sp)
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = if (state.viewMode == ExplorerViewMode.DETAILS) "Details" else "Tiles",
            color = TextTertiary,
            fontSize = 11.sp,
        )
    }
}

/** Long-press menu for one entry. */
@Composable
private fun EntryMenu(
    entry: FileEntry,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StartSurface,
        titleContentColor = TextPrimary,
        title = { Text(entry.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                MenuRow(label = "Open", onClick = onOpen)
                MenuRow(label = "Rename", onClick = onRename)
                MenuRow(
                    label = "Copy path",
                    onClick = {
                        val clipboard = ContextCompat.getSystemService(context, android.content.ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("path", entry.file.absolutePath))
                        toast(context, "Path copied (two-way clipboard sync lands in P1).")
                        onDismiss()
                    },
                )
                MenuRow(label = "Delete", destructive = true, onClick = onDelete)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = TextSecondary) } },
    )
}

@Composable
private fun MenuRow(label: String, destructive: Boolean = false, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (destructive) Color(0xFFFF6B5A) else TextPrimary,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(IconBtn)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 6.dp),
    )
}

/** One-line name prompt used by New folder and Rename. */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StartSurface,
        titleContentColor = TextPrimary,
        title = { Text(title, fontSize = 15.sp) },
        text = {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp),
                cursorBrush = SolidColor(AccentBlue),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(CardSurface)
                    .border(1.dp, StrokeSubtle, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 9.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text("OK", color = AccentBlue) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } },
    )
}

/** True when shared storage is fully browsable (always true below API 30, where the classic permission rules). */
private fun allFilesAccessGranted(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

/** Opens this app's "All files access" toggle, falling back to the generic list if the OEM lacks the per-app page. */
private fun openAllFilesAccessSettings(context: android.content.Context) {
    val perApp = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val generic = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(perApp)
    } catch (e: Throwable) {
        try {
            context.startActivity(generic)
        } catch (e: Throwable) {
            Toast.makeText(context, "Open Settings > Apps > Special access > All files access and allow this app.", Toast.LENGTH_LONG).show()
        }
    }
}

/** Shown until "All files access" is granted: explains why the folders look empty and how to fix it. */
@Composable
private fun AllFilesAccessView(onGrant: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text(
                "Allow \"All files access\" to see every file (including .exe installers) on this device.",
                color = TextSecondary,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onGrant) { Text("Open settings", color = AccentBlue) }
        }
    }
}
