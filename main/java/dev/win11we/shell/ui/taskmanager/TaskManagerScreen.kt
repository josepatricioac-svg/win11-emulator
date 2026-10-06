package dev.win11we.shell.ui.taskmanager

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import dev.win11we.shell.ui.theme.ShellSurfaces
import dev.win11we.shell.ui.theme.StrokeSubtle
import dev.win11we.shell.ui.theme.TextPrimary
import dev.win11we.shell.ui.theme.TextSecondary
import dev.win11we.shell.ui.theme.TextTertiary
import dev.win11we.shell.window.WindowManager
import kotlinx.coroutines.delay
import java.util.Locale

private enum class TaskTab(val label: String) { PROCESSES("Processes"), PERFORMANCE("Performance") }

private enum class ProcessSort(val label: String) { NAME("Name"), MEMORY("Memory"), CPU("CPU"), STATUS("Status") }

private val CardShape = RoundedCornerShape(9.dp)
private val RowShape = RoundedCornerShape(6.dp)
private const val REFRESH_MILLIS = 2_000L

/** Task Manager: shell processes, window list and live CPU/memory graphs. */
@Composable
fun TaskManagerScreen(
    windowManager: WindowManager,
    onCloseWindow: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sampler = remember(context, windowManager) { TaskSampler(context, windowManager) }
    var tab by remember { mutableStateOf(TaskTab.PROCESSES) }
    var sort by remember { mutableStateOf(ProcessSort.MEMORY) }
    var ascending by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var snapshot by remember { mutableStateOf(sampler.sample()) }
    val cpuHistory = remember { History() }
    val memoryHistory = remember { History() }

    LaunchedEffect(sampler) {
        while (true) {
            val fresh = sampler.sample()
            snapshot = fresh
            cpuHistory.add(fresh.cpuPercent)
            memoryHistory.add(
                if (fresh.memoryTotalMb > 0) fresh.memoryUsedMb * 100f / fresh.memoryTotalMb else 0f,
            )
            delay(REFRESH_MILLIS)
        }
    }

    Column(modifier = modifier.fillMaxSize().background(ShellSurfaces.windowContent)) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0x0AFFFFFF)).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TaskTab.entries.forEach { entry ->
                val active = tab == entry
                Text(
                    text = entry.label,
                    color = if (active) AccentBlue else TextSecondary,
                    fontSize = 12.5.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (active) Color(0x224CC2FF) else Color.Transparent)
                        .clickable(onClickLabel = entry.label) { tab = entry }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "Refreshes every ${(REFRESH_MILLIS / 1000)}s",
                color = TextTertiary,
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }

        when (tab) {
            TaskTab.PROCESSES -> ProcessesTab(
                snapshot = snapshot,
                sort = sort,
                ascending = ascending,
                selected = selected,
                onSort = { key ->
                    if (sort == key) ascending = !ascending else {
                        sort = key
                        ascending = key == ProcessSort.NAME
                    }
                },
                onSelect = { selected = it },
                onEndTask = { process ->
                    when (process.kind) {
                        ProcessKind.WINDOW -> {
                            val window = process.windowId?.let { id -> windowManager.windows.firstOrNull { it.id == id } }
                            if (window != null) windowManager.requestClose(window)
                            selected = null
                        }
                        ProcessKind.SHELL -> {
                            windowManager.windows.toList().forEach { windowManager.close(it) }
                            selected = null
                        }
                        else -> Unit // container processes are ended from Container Manager (R2)
                    }
                },
                modifier = Modifier.weight(1f),
            )
            TaskTab.PERFORMANCE -> PerformanceTab(
                snapshot = snapshot,
                cpuHistory = cpuHistory.snapshot(),
                memoryHistory = memoryHistory.snapshot(),
                modifier = Modifier.weight(1f),
            )
        }
        val selectedProcess = snapshot.processes.firstOrNull { it.id == selected }
        TaskStatusBar(
            snapshot = snapshot,
            selected = selectedProcess,
            onEndTask = { process ->
                when (process.kind) {
                    ProcessKind.WINDOW -> process.windowId
                        ?.let { id -> windowManager.windows.firstOrNull { it.id == id } }
                        ?.let(windowManager::requestClose)
                    ProcessKind.SHELL -> windowManager.windows.toList().forEach(windowManager::close)
                    else -> Unit
                }
                selected = null
            },
        )
    }
}

@Composable
private fun ProcessesTab(
    snapshot: TaskSnapshot,
    sort: ProcessSort,
    ascending: Boolean,
    selected: String?,
    onSort: (ProcessSort) -> Unit,
    onSelect: (String?) -> Unit,
    onEndTask: (ShellProcess) -> Unit,
    modifier: Modifier = Modifier,
) {
    val processes = remember(snapshot.processes, sort, ascending) {
        val comparator: Comparator<ShellProcess> = when (sort) {
            ProcessSort.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            ProcessSort.MEMORY -> compareBy({ it.memoryKb }, { it.name })
            ProcessSort.CPU -> compareBy({ it.cpuPercent }, { it.name })
            ProcessSort.STATUS -> compareBy({ it.status }, { it.name })
        }
        snapshot.processes.sortedWith(if (ascending) comparator else comparator.reversed())
    }
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(30.dp).background(Color(0x0AFFFFFF)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderCell("Name", 0.42f, sort == ProcessSort.NAME, ascending, ProcessSort.NAME, onSort)
            HeaderCell("Status", 0.18f, sort == ProcessSort.STATUS, ascending, ProcessSort.STATUS, onSort)
            HeaderCell("CPU", 0.18f, sort == ProcessSort.CPU, ascending, ProcessSort.CPU, onSort)
            HeaderCell("Memory", 0.22f, sort == ProcessSort.MEMORY, ascending, ProcessSort.MEMORY, onSort, end = true)
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(processes, key = { it.id }) { process ->
                ProcessRow(
                    process = process,
                    selected = process.id == selected,
                    onClick = { onSelect(if (process.id == selected) null else process.id) },
                    onEndTask = { onEndTask(process) },
                )
            }
        }
    }
}

@Composable
private fun RowScope.HeaderCell(
    title: String,
    weight: Float,
    active: Boolean,
    ascending: Boolean,
    key: ProcessSort,
    onSort: (ProcessSort) -> Unit,
    end: Boolean = false,
) {
    Row(
        modifier = Modifier
            .weight(weight)
            .clip(RowShape)
            .clickable(onClickLabel = "Sort by $title") { onSort(key) }
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (end) Arrangement.End else Arrangement.Start,
    ) {
        Text(title, color = if (active) AccentBlue else TextSecondary, fontSize = 11.5.sp)
        if (active) {
            Spacer(Modifier.width(4.dp))
            Icon(
                painter = painterResource(if (ascending) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down),
                contentDescription = null,
                tint = AccentBlue,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}

@Composable
private fun ProcessRow(process: ShellProcess, selected: Boolean, onClick: () -> Unit, onEndTask: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .padding(horizontal = 8.dp)
            .clip(RowShape)
            .background(if (selected) Color(0x2E4CC2FF) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(
                when (process.kind) {
                    ProcessKind.SHELL -> R.drawable.ic_app_container
                    ProcessKind.WINDOW -> R.drawable.ic_restore
                    ProcessKind.CONTAINER -> R.drawable.ic_app_terminal
                    ProcessKind.WINDOWS_APP -> R.drawable.ic_file_app
                },
            ),
            contentDescription = null,
            tint = if (process.kind == ProcessKind.SHELL) Color.Unspecified else TextSecondary,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(0.42f)) {
            Text(process.name, color = TextPrimary, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(process.kind.label, color = TextTertiary, fontSize = 10.5.sp)
        }
        Text(process.status, color = TextSecondary, fontSize = 11.5.sp, modifier = Modifier.weight(0.18f))
        Text(
            text = if (process.cpuPercent > 0.05f) String.format(Locale.US, "%.1f%%", process.cpuPercent) else "-",
            color = TextSecondary,
            fontSize = 11.5.sp,
            modifier = Modifier.weight(0.18f),
        )
        Text(
            text = if (process.memoryKb > 0) String.format(Locale.US, "%.1f MB", process.memoryKb / 1024f) else "-",
            color = TextSecondary,
            fontSize = 11.5.sp,
            modifier = Modifier.weight(0.22f),
            textAlign = TextAlign.End,
        )
        if (selected && process.endable) {
            Text(
                text = "End task",
                color = Color(0xFFFF8A7A),
                fontSize = 11.5.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x1FC42B1C))
                    .clickable(onClickLabel = "End task", onClick = onEndTask)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun PerformanceTab(
    snapshot: TaskSnapshot,
    cpuHistory: List<Float>,
    memoryHistory: List<Float>,
    modifier: Modifier = Modifier,
) {
    val cores = remember { Runtime.getRuntime().availableProcessors() }
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PerformanceCard(
            title = "CPU",
            value = String.format(Locale.US, "%.0f%%", snapshot.cpuPercent),
            subtitle = "$cores logical processors",
            history = cpuHistory,
            color = AccentBlue,
        )
        PerformanceCard(
            title = "Memory",
            value = "${snapshot.memoryUsedMb}/${snapshot.memoryTotalMb} MB",
            subtitle = if (snapshot.memoryTotalMb > 0) {
                String.format(Locale.US, "%.0f%% in use", snapshot.memoryUsedMb * 100f / snapshot.memoryTotalMb)
            } else {
                "Device total unavailable"
            },
            history = memoryHistory,
            color = Color(0xFF7A5CFF),
        )
        Text(
            text = "Container graphs (proot, box64, wine, X server) appear once ContainerService reports them in R2.",
            color = TextTertiary,
            fontSize = 11.5.sp,
        )
    }
}

@Composable
private fun PerformanceCard(title: String, value: String, subtitle: String, history: List<Float>, color: Color) {
    val density = LocalDensity.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(CardSurface)
            .border(1.dp, ShellSurfaces.stroke, CardShape)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = TextTertiary, fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))
        Canvas(modifier = Modifier.fillMaxWidth().height(64.dp)) {
            val max = 100f
            drawLine(
                color = Color(0x14FFFFFF),
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = with(density) { 1.dp.toPx() },
            )
            if (history.size >= 2) {
                val step = size.width / (history.size - 1).coerceAtLeast(1)
                val path = Path()
                history.forEachIndexed { index, value ->
                    val x = index * step
                    val y = size.height - (value / max).coerceIn(0f, 1f) * size.height
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path = path, color = color, style = Stroke(width = with(density) { 1.6.dp.toPx() }))
            }
        }
    }
}

@Composable
private fun TaskStatusBar(snapshot: TaskSnapshot, selected: ShellProcess?, onEndTask: (ShellProcess) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(34.dp).background(Color(0x0AFFFFFF)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${snapshot.processes.size} processes", color = TextSecondary, fontSize = 11.sp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = String.format(Locale.US, "CPU %.0f%%", snapshot.cpuPercent),
            color = TextSecondary,
            fontSize = 11.sp,
        )
        Spacer(Modifier.width(12.dp))
        Text("${snapshot.memoryUsedMb}/${snapshot.memoryTotalMb} MB", color = TextSecondary, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        if (selected != null && selected.endable) {
            Text(
                text = "End task",
                color = Color(0xFFFF8A7A),
                fontSize = 11.5.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(HoverLight)
                    .clickable(onClickLabel = "End task") { onEndTask(selected) }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}
