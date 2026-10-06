package dev.win11we.shell.ui.notepad

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.sp
import dev.win11we.shell.ui.theme.StartSurface
import dev.win11we.shell.ui.theme.TextPrimary
import dev.win11we.shell.ui.theme.TextSecondary
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Notepad window body: menu bar, find bar, editor, status bar, plus the SAF
 * open/save plumbing and the unsaved-changes guard used when closing.
 */
@Composable
fun NotepadScreen(state: NotepadState, windowId: Int, onCloseWindow: (Int) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // P1 replaces this with LocalClipboard (suspend) for two-way Windows clipboard sync.
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var pendingSaveAs by remember { mutableStateOf(false) }
    var showCloseConfirm by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun report(text: String?) {
        message = text
    }

    fun saveBytes(uri: Uri, bytes: ByteArray): Boolean = try {
        writeUri(context, uri, bytes)
        state.markSaved(uri, displayName(context, uri))
        true
    } catch (error: IOException) {
        report("Save failed: ${error.message}")
        false
    }

    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            saveBytes(uri, state.bytesToSave())
            if (!pendingSaveAs) report("Saved ${state.fileName}")
        }
        pendingSaveAs = false
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val bytes = readUri(context, uri)
                val encoding = DocumentIO.detectEncoding(bytes)
                val raw = DocumentIO.decode(bytes, encoding)
                state.load(
                    text = raw,
                    uri = uri,
                    name = displayName(context, uri),
                    detectedEncoding = encoding,
                    detectedEndings = DocumentIO.detectLineEndings(raw),
                )
                report(null)
            } catch (error: IOException) {
                report("Open failed: ${error.message}")
            }
        }
    }

    fun save() {
        val uri = state.fileUri
        if (uri == null) {
            pendingSaveAs = false
            createLauncher.launch("${state.fileName}.txt")
        } else {
            if (saveBytes(uri, state.bytesToSave())) report("Saved ${state.fileName}")
        }
    }

    fun saveAs() {
        pendingSaveAs = true
        createLauncher.launch(if (state.fileName.endsWith(".txt")) state.fileName else "${state.fileName}.txt")
    }

    // The window's close button asks first while the buffer is dirty.
    LaunchedEffect(state, windowId) {
        state.closeGuard = {
            if (state.dirty) showCloseConfirm = true else onCloseWindow(windowId)
        }
    }
    LaunchedEffect(state) {
        state.textChanges().collect { if (!state.dirty) state.dirty = true }
    }

    val menus = remember(state, clipboard) {
        listOf(
            NotepadMenu(
                "File",
                listOf(
                    NotepadMenuItem("New", shortcut = "Ctrl+N", onClick = { state.newDocument(); report(null) }),
                    NotepadMenuItem("Open...", shortcut = "Ctrl+O", onClick = { openLauncher.launch(arrayOf("text/*")) }),
                    NotepadMenuItem("Save", shortcut = "Ctrl+S", onClick = { save() }),
                    NotepadMenuItem("Save as...", onClick = { saveAs() }),
                ),
            ),
            NotepadMenu(
                "Edit",
                listOf(
                    NotepadMenuItem("Undo", shortcut = "Ctrl+Z", onClick = { state.undo() }),
                    NotepadMenuItem("Redo", shortcut = "Ctrl+Y", onClick = { state.redo() }),
                    NotepadMenuItem(
                        "Cut",
                        enabled = state.hasSelection(),
                        shortcut = "Ctrl+X",
                        onClick = {
                            val selected = state.selectedText()
                            if (selected.isNotEmpty()) {
                                clipboard.setText(AnnotatedString(selected))
                                state.deleteSelection()
                            }
                        },
                    ),
                    NotepadMenuItem(
                        "Copy",
                        enabled = state.hasSelection(),
                        shortcut = "Ctrl+C",
                        onClick = { clipboard.setText(AnnotatedString(state.selectedText())) },
                    ),
                    NotepadMenuItem(
                        "Paste",
                        shortcut = "Ctrl+V",
                        onClick = {
                            val pasted = clipboard.getText()?.text ?: return@NotepadMenuItem
                            state.replaceSelection(pasted)
                        },
                    ),
                    NotepadMenuItem(
                        "Delete",
                        enabled = state.hasSelection(),
                        shortcut = "Del",
                        onClick = { state.deleteSelection() },
                    ),
                    NotepadMenuItem("Select all", shortcut = "Ctrl+A", onClick = { state.selectAll() }),
                    NotepadMenuItem(
                        "Find...",
                        shortcut = "Ctrl+F",
                        onClick = { state.findBarVisible = true; report(null) },
                    ),
                    NotepadMenuItem("Replace...", shortcut = "Ctrl+H", onClick = { state.findBarVisible = true }),
                    NotepadMenuItem("Time/Date", shortcut = "F5", onClick = { state.insertTimeStamp() }),
                ),
            ),
            NotepadMenu(
                "Format",
                listOf(
                    NotepadMenuItem("Word wrap", checked = state.wordWrap, onClick = { state.wordWrap = !state.wordWrap }),
                ) + DocEncoding.entries.map { encoding ->
                    NotepadMenuItem(
                        label = encoding.label,
                        checked = state.encoding == encoding,
                        onClick = {
                            state.encoding = encoding
                            state.dirty = true
                        },
                    )
                },
            ),
            NotepadMenu(
                "View",
                listOf(
                    NotepadMenuItem("Zoom in", shortcut = "Ctrl++", onClick = { state.zoomBy(10f) }),
                    NotepadMenuItem("Zoom out", shortcut = "Ctrl+-", onClick = { state.zoomBy(-10f) }),
                    NotepadMenuItem("Reset zoom", shortcut = "Ctrl+0", onClick = { state.zoomTo(100f) }),
                    NotepadMenuItem(
                        "Status bar",
                        checked = state.statusBarVisible,
                        onClick = { state.statusBarVisible = !state.statusBarVisible },
                    ),
                ),
            ),
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.N -> { state.newDocument(); report(null) }
                    Key.O -> openLauncher.launch(arrayOf("text/*"))
                    Key.S -> if (pendingSaveAs) saveAs() else save()
                    Key.F, Key.H -> state.findBarVisible = true
                    Key.Z -> state.undo()
                    Key.Y -> state.redo()
                    Key.A -> state.selectAll()
                    Key.X -> {
                        val selected = state.selectedText()
                        if (selected.isNotEmpty()) {
                            clipboard.setText(AnnotatedString(selected))
                            state.deleteSelection()
                        }
                    }
                    Key.C -> clipboard.setText(AnnotatedString(state.selectedText()))
                    Key.V -> {
                        val pasted = clipboard.getText()?.text
                        if (pasted != null) state.replaceSelection(pasted)
                    }
                    Key.Equals, Key.Plus -> state.zoomBy(10f)
                    Key.Minus -> state.zoomBy(-10f)
                    Key.Zero -> state.zoomTo(100f)
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        NotepadMenuBar(menus = menus, modifier = Modifier.fillMaxWidth())
        if (state.findBarVisible) {
            NotepadFindBar(state = state, onClose = { state.findBarVisible = false }, onResult = { report(it) })
        }
        NotepadEditor(state = state, modifier = Modifier.fillMaxWidth().weight(1f))
        if (state.statusBarVisible) {
            NotepadStatusBar(state = state, modifier = Modifier.fillMaxWidth())
        }
    }

    message?.let { text ->
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        message = null
    }
    if (showCloseConfirm) {
        AlertDialog(
            onDismissRequest = { showCloseConfirm = false },
            containerColor = StartSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("Save changes to ${state.fileName}?", fontSize = 15.sp) },
            text = { Text("Unsaved changes will be lost.", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { showCloseConfirm = false; save(); onCloseWindow(windowId) }) {
                    Text("Save", color = Color(0xFF4CC2FF))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showCloseConfirm = false; onCloseWindow(windowId) }) {
                        Text("Don't save", color = TextSecondary)
                    }
                    TextButton(onClick = { showCloseConfirm = false }) { Text("Cancel", color = TextSecondary) }
                }
            },
        )
    }
}

/** Reads a SAF uri fully into memory (documents are small; guarded by R3 limits later). */
internal fun readUri(context: Context, uri: Uri): ByteArray {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArrayOutputStream()
        input.copyTo(buffer)
        return buffer.toByteArray()
    } ?: throw IOException("Cannot open this file")
}

internal fun writeUri(context: Context, uri: Uri, bytes: ByteArray) {
    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
        ?: throw IOException("Cannot write to this file")
}

/** Display name from the provider, falling back to the last path segment. */
internal fun displayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index) ?: "Untitled"
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "Untitled"
}
