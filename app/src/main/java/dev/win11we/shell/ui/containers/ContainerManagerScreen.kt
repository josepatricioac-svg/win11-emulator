package dev.win11we.shell.ui.containers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.win11we.shell.R
import dev.win11we.shell.container.Container
import dev.win11we.shell.container.ContainerStatus
import dev.win11we.shell.container.ContainerStore
import dev.win11we.shell.container.DriveMapping
import dev.win11we.shell.container.GuestPaths
import dev.win11we.shell.container.WineArch
import dev.win11we.shell.container.WineEnvironment
import dev.win11we.shell.settings.Box64Preset
import dev.win11we.shell.ui.theme.AccentBlue
import dev.win11we.shell.ui.theme.CardSurface
import dev.win11we.shell.ui.theme.HoverLight
import dev.win11we.shell.ui.theme.ShellSurfaces
import dev.win11we.shell.ui.theme.TextPrimary
import dev.win11we.shell.ui.theme.TextSecondary
import dev.win11we.shell.ui.theme.TextTertiary

private val CardShape = RoundedCornerShape(10.dp)
private val ChipShape = RoundedCornerShape(50)
private val ErrorRed = Color(0xFFFF8A7A)
private val OkGreen = Color(0xFF7BE0A8)

/**
 * Container Manager: the list of Wine prefixes, their Box64 preset, drive
 * mappings and environment, plus the Start/Stop/Restart controls that R2's
 * ContainerService will answer.
 */
@Composable
fun ContainerManagerScreen(
    state: ContainerManagerState,
    onOpenPath: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ShellSurfaces.windowContent)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Header(state = state)
        Spacer(Modifier.height(12.dp))

        if (state.containers.isEmpty()) {
            EmptyCard(onCreate = { state.creating = true })
        } else {
            state.containers.forEach { container ->
                ContainerCard(
                    container = container,
                    expanded = state.expandedId == container.id,
                    isDefault = state.store.isDefault(container.id),
                    store = state.store,
                    onToggle = { state.toggle(container) },
                    onAction = { state.start(container) },
                    onStop = { state.stop(container) },
                    onMakeDefault = { state.makeDefault(container) },
                    onRename = { state.renaming = container },
                    onDelete = { state.deleting = container },
                    onAddDrive = { state.editingDrive = container to null },
                    onEditDrive = { drive -> state.editingDrive = container to drive },
                    onRemoveDrive = { drive -> state.removeDrive(container, drive.letter) },
                    onOpenPrefix = { onOpenPath(container.prefixPath) },
                    onSave = { updated, what -> state.save(container, updated, what) },
                    logExcerpt = { lines -> state.logExcerpt(container, lines) },
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        state.message?.let { text ->
            Spacer(Modifier.height(6.dp))
            Text(text, color = TextSecondary, fontSize = 12.sp)
        }
    }

    if (state.creating) {
        TextDialog(
            title = "New container",
            label = "Name",
            initial = "Container ${state.containers.size + 1}",
            onDismiss = { state.creating = false },
            onConfirm = { state.creating = false; state.create(it) },
        )
    }
    state.renaming?.let { container ->
        TextDialog(
            title = "Rename container",
            label = "Name",
            initial = container.name,
            onDismiss = { state.renaming = null },
            onConfirm = { state.renaming = null; state.rename(container, it) },
        )
    }
    state.deleting?.let { container ->
        AlertDialog(
            onDismissRequest = { state.deleting = null },
            containerColor = ShellSurfaces.start,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("Delete ${container.name}?", fontSize = 15.sp) },
            text = { Text("The Wine prefix and everything installed in it are removed.", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { state.deleting = null; state.delete(container) }) {
                    Text("Delete", color = ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { state.deleting = null }) { Text("Cancel", color = TextSecondary) }
            },
        )
    }
    state.editingDrive?.let { (container, drive) ->
        DriveDialog(
            initial = drive,
            takenLetters = container.allDrives.map { it.letter.uppercaseChar() },
            onDismiss = { state.editingDrive = null },
            onConfirm = { letter, path ->
                state.editingDrive = null
                state.addDrive(container, letter, path)
            },
        )
    }
}

@Composable
private fun Header(state: ContainerManagerState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Containers", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = if (state.runtime.ready) {
                    "Runtime binaries ready - Start still validates each container: a Wine prefix " +
                        "needs wineboot + a Wine payload (Goal 2 step 2), which nothing builds yet."
                } else {
                    "Runtime not ready: " + state.runtimeStatus.ifBlank {
                        "no libbox64.so / libproot.so in this APK (R1), rootfs in R3. See Diagnostics."
                    }
                },
                color = if (state.runtime.ready) OkGreen else TextTertiary,
                fontSize = 11.5.sp,
            )
        }
        Text(
            text = "New",
            color = Color(0xFF0B1220),
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(ChipShape)
                .background(AccentBlue)
                .clickable(onClickLabel = "New container") { state.creating = true }
                .padding(horizontal = 18.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun EmptyCard(onCreate: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(CardSurface)
            .border(1.dp, ShellSurfaces.stroke, CardShape)
            .clickable(onClick = onCreate)
            .padding(18.dp),
    ) {
        Icon(painterResource(R.drawable.ic_app_container), null, tint = Color.Unspecified, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(10.dp))
        Text("No containers yet", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            text = "A container is a Wine prefix plus its Box64 preset, drive mappings and environment. " +
                "Create one now; it becomes usable when R2/R3 land.",
            color = TextTertiary,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun ContainerCard(
    container: Container,
    expanded: Boolean,
    isDefault: Boolean,
    store: ContainerStore,
    onToggle: () -> Unit,
    onAction: () -> Unit,
    onStop: () -> Unit,
    onMakeDefault: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onAddDrive: () -> Unit,
    onEditDrive: (DriveMapping) -> Unit,
    onRemoveDrive: (DriveMapping) -> Unit,
    onOpenPrefix: () -> Unit,
    onSave: (Container, String) -> Unit,
    logExcerpt: (Int) -> String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(CardSurface)
            .border(1.dp, if (isDefault) Color(0x594CC2FF) else ShellSurfaces.stroke, CardShape),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(9.dp).clip(CircleShape).background(
                    when (container.status) {
                        ContainerStatus.RUNNING -> OkGreen
                        ContainerStatus.STARTING -> Color(0xFFFFC44D)
                        ContainerStatus.ERROR -> ErrorRed
                        ContainerStatus.STOPPED -> TextTertiary
                    },
                ),
            )
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(container.name, color = TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                    if (isDefault) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Default",
                            color = AccentBlue,
                            fontSize = 10.sp,
                            modifier = Modifier.clip(ChipShape).background(Color(0x224CC2FF))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    text = "${container.status.label}  ·  ${container.preset.label}  ·  ${container.arch.label}",
                    color = TextTertiary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                painterResource(if (expanded) R.drawable.ic_chevron_up else R.drawable.ic_arrow_down),
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = TextSecondary,
                modifier = Modifier.size(14.dp),
            )
        }

        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                container.lastError?.let { error ->
                    Text(error, color = ErrorRed, fontSize = 11.5.sp)
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionButton("Start", onAction)
                    ActionButton("Stop", onStop)
                    ActionButton(if (isDefault) "Default" else "Set default", onMakeDefault, enabled = !isDefault)
                    ActionButton("Rename", onRename)
                    ActionButton("Delete", onDelete, destructive = true)
                }
                Spacer(Modifier.height(14.dp))

                Label("Box64 preset")
                ChipRow(
                    options = Box64Preset.entries.map { it.label },
                    selected = Box64Preset.entries.indexOf(container.preset),
                    onSelect = { index ->
                        val preset = Box64Preset.entries[index]
                        onSave(container.copy(preset = preset), "Preset: ${preset.label}")
                    },
                )
                Text(container.preset.description, color = TextTertiary, fontSize = 11.sp)
                Spacer(Modifier.height(12.dp))

                Label("Architecture")
                ChipRow(
                    options = WineArch.entries.map { it.label },
                    selected = WineArch.entries.indexOf(container.arch),
                    onSelect = { index ->
                        onSave(container.copy(arch = WineArch.entries[index]), "Architecture updated")
                    },
                )
                Spacer(Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("Scaling (DPI)", modifier = Modifier.weight(1f))
                    NumberField(
                        value = container.dpi.toString(),
                        onCommit = { text ->
                            val dpi = text.toIntOrNull()?.coerceIn(48, 480) ?: container.dpi
                            onSave(container.copy(dpi = dpi), "Scaling set to $dpi dpi")
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))

                Label("Drives")
                container.allDrives.forEach { drive ->
                    DriveRow(
                        drive = drive,
                        removable = drive.letter.uppercaseChar() != 'C',
                        onEdit = { onEditDrive(drive) },
                        onRemove = { onRemoveDrive(drive) },
                    )
                }
                Text(
                    text = "Add drive",
                    color = AccentBlue,
                    fontSize = 12.sp,
                    modifier = Modifier.clip(ChipShape).clickable(onClick = onAddDrive)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                Spacer(Modifier.height(12.dp))

                Label("Container log")
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Color(0xFF0B0F16)).padding(10.dp),
                ) {
                    Text(
                        text = logExcerpt(8).ifBlank { "(no output yet)" },
                        color = Color(0xFF9FE8B8),
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(12.dp))

                Label("Prefix")
                Text(container.prefixPath, color = TextSecondary, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ActionButton("Open in Explorer", onOpenPrefix)
                    ActionButton("Size: ${formatKb(store.prefixSizeKb(container.id))}", onClick = {}, enabled = false)
                }
                Spacer(Modifier.height(12.dp))

                Label("Environment preview (what R2 will exec with)")
                val env = remember(container) {
                    WineEnvironment.build(
                        container = container,
                        xDisplay = ":100",
                        prootRootfs = "/data/user/0/dev.win11we.shell/files/rootfs",
                        nativeLibraryDir = "/data/app/lib/arm64",
                    )
                }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Color(0xFF0B0F16)).padding(10.dp),
                ) {
                    env.forEach { (key, value) ->
                        Text(
                            text = "$key=$value",
                            color = Color(0xFF9FE8B8),
                            fontSize = 10.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = "guest path of the prefix: ${GuestPaths.toGuest(container.prefixPath, container)}",
                        color = Color(0xFF7FC9FF),
                        fontSize = 10.5.sp,
                    )
                }
            }
        }
    }
}

/** Walking a prefix tree is disk I/O: do it off the UI thread and show a placeholder. */
@Composable
private fun PrefixSize(store: ContainerStore, containerId: String) {
    var sizeKb by remember(containerId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(containerId) {
        withContext(Dispatchers.IO) { sizeKb = runCatching { store.prefixSizeKb(containerId) }.getOrDefault(0L) }
    }
    ActionButton(sizeKb?.let { "Size: ${formatKb(it)}" } ?: "Size: ...", onClick = {}, enabled = false)
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text, color = TextSecondary, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit, destructive: Boolean = false, enabled: Boolean = true) {
    Text(
        text = label,
        color = when {
            !enabled -> TextTertiary
            destructive -> ErrorRed
            else -> TextPrimary
        },
        fontSize = 11.5.sp,
        modifier = Modifier
            .clip(ChipShape)
            .background(if (destructive) Color(0x1FC42B1C) else HoverLight)
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun ChipRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selected
            Text(
                text = label,
                color = if (active) Color(0xFF0B1220) else TextSecondary,
                fontSize = 11.5.sp,
                modifier = Modifier
                    .clip(ChipShape)
                    .background(if (active) AccentBlue else Color(0x14FFFFFF))
                    .clickable(onClickLabel = label) { onSelect(index) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun NumberField(value: String, onCommit: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp),
        cursorBrush = SolidColor(AccentBlue),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .width(74.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x14FFFFFF))
            .border(1.dp, ShellSurfaces.stroke, RoundedCornerShape(6.dp))
            .padding(horizontal = 9.dp, vertical = 6.dp),
    )
    Spacer(Modifier.width(6.dp))
    Text("Set", color = AccentBlue, fontSize = 12.sp,
        modifier = Modifier.clip(ChipShape).clickable(onClickLabel = "Set DPI") { onCommit(text) }
            .padding(horizontal = 10.dp, vertical = 6.dp))
}

@Composable
private fun DriveRow(drive: DriveMapping, removable: Boolean, onEdit: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${drive.guestRoot}", color = AccentBlue, fontSize = 12.sp, modifier = Modifier.width(34.dp))
        Text(
            text = drive.hostPath,
            color = TextSecondary,
            fontSize = 11.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (removable) {
            Text("Edit", color = TextSecondary, fontSize = 11.5.sp,
                modifier = Modifier.clip(ChipShape).clickable(onClick = onEdit).padding(horizontal = 9.dp, vertical = 4.dp))
            Text("Remove", color = ErrorRed, fontSize = 11.5.sp,
                modifier = Modifier.clip(ChipShape).clickable(onClickLabel = "Remove drive", onClick = onRemove)
                    .padding(horizontal = 9.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun TextDialog(
    title: String,
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ShellSurfaces.start,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text(title, fontSize = 15.sp) },
        text = {
            Column {
                Text(label, color = TextTertiary, fontSize = 11.5.sp)
                Spacer(Modifier.height(6.dp))
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp),
                    cursorBrush = SolidColor(AccentBlue),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(CardSurface).border(1.dp, ShellSurfaces.stroke, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text("OK", color = AccentBlue) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } },
    )
}

@Composable
private fun DriveDialog(
    initial: DriveMapping?,
    takenLetters: List<Char>,
    onDismiss: () -> Unit,
    onConfirm: (Char, String) -> Unit,
) {
    var letter by remember { mutableStateOf(initial?.letter?.toString() ?: "D") }
    var path by remember { mutableStateOf(initial?.hostPath ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ShellSurfaces.start,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text(if (initial == null) "Add drive" else "Edit drive ${initial.letter}:", fontSize = 15.sp) },
        text = {
            Column {
                Text("Letter", color = TextTertiary, fontSize = 11.5.sp)
                BasicTextField(
                    value = letter,
                    onValueChange = { letter = it.take(1) },
                    singleLine = true,
                    textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp),
                    cursorBrush = SolidColor(AccentBlue),
                    modifier = Modifier.width(60.dp).clip(RoundedCornerShape(6.dp)).background(CardSurface)
                        .border(1.dp, ShellSurfaces.stroke, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text("Host path (Android side)", color = TextTertiary, fontSize = 11.5.sp)
                BasicTextField(
                    value = path,
                    onValueChange = { path = it },
                    singleLine = true,
                    textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp),
                    cursorBrush = SolidColor(AccentBlue),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(CardSurface)
                        .border(1.dp, ShellSurfaces.stroke, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Taken: ${takenLetters.joinToString(" ") { "$it:" }}. SAF-picked folders become mappings in P1.",
                    color = TextTertiary,
                    fontSize = 11.sp,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { letter.firstOrNull()?.let { onConfirm(it, path) } },
                enabled = path.isNotBlank() && (letter.firstOrNull()?.isLetter() == true),
            ) { Text("Save", color = AccentBlue) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } },
    )
}

private fun formatKb(kb: Long): String = when {
    kb <= 0 -> "0 KB"
    kb < 1024 -> "$kb KB"
    kb < 1024 * 1024 -> "%.1f MB".format(kb / 1024.0)
    else -> "%.2f GB".format(kb / (1024.0 * 1024.0))
}
