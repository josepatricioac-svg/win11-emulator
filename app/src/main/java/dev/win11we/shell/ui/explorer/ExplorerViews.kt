package dev.win11we.shell.ui.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.win11we.shell.R
import dev.win11we.shell.ui.theme.AccentBlue
import dev.win11we.shell.ui.theme.HoverLight
import dev.win11we.shell.ui.theme.TextPrimary
import dev.win11we.shell.ui.theme.TextSecondary
import dev.win11we.shell.ui.theme.TextTertiary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CellShape = RoundedCornerShape(6.dp)
private val SelectedBrush = Color(0x2E4CC2FF)
private val dateFmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

/** Explorer interactions shared by the details rows and the grid tiles. */
class ExplorerGestures(
    val onSelect: (File, Boolean) -> Unit,
    val onOpen: (FileEntry) -> Unit,
    val onContext: (FileEntry, Boolean) -> Unit,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.entryGestures(entry: FileEntry, gestures: ExplorerGestures): Modifier = this
    .combinedClickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = { gestures.onSelect(entry.file, false) },
        onLongClick = { gestures.onContext(entry, true) },
    )
    .pointerInput(entry.file) {
        detectTapGestures(onDoubleTap = { gestures.onOpen(entry) })
    }

/** Details view: header + rows with Name / Date modified / Type / Size. */
@Composable
fun DetailsView(
    entries: List<FileEntry>,
    selected: Set<File>,
    gestures: ExplorerGestures,
    sortKey: SortKey,
    ascending: Boolean,
    onSort: (SortKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .background(Color(0x0AFFFFFF))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderCell("Name", 0.40f, sortKey == SortKey.NAME, ascending, SortKey.NAME, onSort)
                HeaderCell("Date modified", 0.24f, sortKey == SortKey.DATE, ascending, SortKey.DATE, onSort)
                HeaderCell("Type", 0.20f, sortKey == SortKey.TYPE, ascending, SortKey.TYPE, onSort)
                HeaderCell("Size", 0.16f, sortKey == SortKey.SIZE, ascending, SortKey.SIZE, onSort, end = true)
            }
        }
        items(entries, key = { it.file.absolutePath }) { entry ->
            DetailsRow(entry = entry, selected = selected.contains(entry.file), gestures = gestures)
        }
    }
}

@Composable
private fun RowScope.HeaderCell(
    title: String,
    weight: Float,
    active: Boolean,
    ascending: Boolean,
    key: SortKey,
    onSort: (SortKey) -> Unit,
    end: Boolean = false,
) {
    Row(
        modifier = Modifier
            .weight(weight)
            .fillMaxWidth()
            .clip(CellShape)
            .clickable(onClickLabel = "Sort by $title") { onSort(key) }
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (end) Arrangement.End else Arrangement.Start,
    ) {
        Text(
            text = title,
            color = if (active) AccentBlue else TextSecondary,
            fontSize = 11.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
private fun DetailsRow(entry: FileEntry, selected: Boolean, gestures: ExplorerGestures) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .padding(horizontal = 6.dp)
            .clip(CellShape)
            .background(if (selected) SelectedBrush else Color.Transparent)
            .entryGestures(entry, gestures)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconFor(entry)),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = entry.name,
            color = TextPrimary,
            fontSize = 12.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.40f),
        )
        Text(
            text = if (entry.isDirectory) "" else dateFmt.format(Date(entry.modifiedMillis)),
            color = TextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.weight(0.24f),
        )
        Text(
            text = FileEntries.typeLabel(entry),
            color = TextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.20f),
        )
        Text(
            text = if (entry.isDirectory) "" else FileEntries.formatSize(entry.sizeBytes),
            color = TextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.16f),
        )
    }
}

/** Tiles view: 32dp icon over a wrapped name. */
@Composable
fun TilesView(
    entries: List<FileEntry>,
    selected: Set<File>,
    gestures: ExplorerGestures,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
    ) {
        items(entries, key = { it.file.absolutePath }) { entry ->
            val isSelected = selected.contains(entry.file)
            Column(
                modifier = Modifier
                    .clip(CellShape)
                    .background(if (isSelected) SelectedBrush else Color.Transparent)
                    .entryGestures(entry, gestures)
                    .padding(vertical = 10.dp, horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    painter = painterResource(iconFor(entry)),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(34.dp),
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    text = entry.name,
                    color = if (isSelected) AccentBlue else TextPrimary,
                    fontSize = 11.5.sp,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Empty-folder / no-permission message. */
@Composable
fun EmptyView(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = TextTertiary, fontSize = 13.sp)
    }
}
