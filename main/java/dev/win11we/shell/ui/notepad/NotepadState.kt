package dev.win11we.shell.ui.notepad

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Everything one Notepad window owns: buffer, undo stack, find state, view
 * settings and the SAF uri it was loaded from / saves back to.
 */
@Stable
class NotepadState(val title: String = "Untitled") {
    val textState = TextFieldState()

    var fileUri by mutableStateOf<Uri?>(null)
    var fileName by mutableStateOf(title)
        private set
    var encoding by mutableStateOf(DocEncoding.UTF8)
    var lineEndings by mutableStateOf(LineEndings.WINDOWS)
    var dirty by mutableStateOf(false)
        internal set

    var wordWrap by mutableStateOf(true)
    var zoom by mutableStateOf(100f)
        private set
    var statusBarVisible by mutableStateOf(true)
    var findBarVisible by mutableStateOf(false)
    var findQuery by mutableStateOf("")
    var replaceQuery by mutableStateOf("")
    var findMatchCase by mutableStateOf(false)

    /** Undo/redo is the text field's own history (foundation 1.8+). */
    @OptIn(ExperimentalFoundationApi::class)
    private val undoState get() = textState.undoState
    val canUndo: Boolean get() = undoState.canUndo
    val canRedo: Boolean get() = undoState.canRedo
    fun undo() = undoState.undo()
    fun redo() = undoState.redo()
    private fun clearHistory() = undoState.clearHistory()

    val text: String get() = textState.text.toString()
    val selection: TextRange get() = textState.selection
    val caret: Int get() = selection.min

    /** Set by the screen so the window's close button can ask about unsaved changes. */
    var closeGuard: (() -> Unit)? = null

    fun position(): TextPosition = TextPosition.of(text, caret)
    fun selectedText(): String = text.substring(selection.min, selection.max)
    fun hasSelection(): Boolean = selection.max > selection.min

    fun selectAll() {
        val end = text.length
        textState.edit { selection = TextRange(0, end) }
    }

    /** Notepad's F5 inserts the locale time stamp at the caret. */
    fun insertTimeStamp(
        stamp: String = java.text.SimpleDateFormat("HH:mm dd/MM/yyyy", java.util.Locale.getDefault())
            .format(java.util.Date()),
    ) {
        replaceSelection(stamp)
        dirty = true
    }
    fun lineCount(): Int = if (text.isEmpty()) 1 else text.count { it == '\n' } + 1
    fun zoomLabel(): String = "${zoom.toInt()}%"

    /** Window caption: "readme.txt - Notepad", with the dirty marker Notepad uses. */
    fun caption(): String = buildString {
        if (dirty) append("*")
        append(fileName)
        append(" - Notepad")
    }

    // --- loading / saving -------------------------------------------------

    fun load(text: String, uri: Uri?, name: String, detectedEncoding: DocEncoding, detectedEndings: LineEndings) {
        textState.setTextAndPlaceCursorAtEnd(DocumentIO.normalize(text))
        fileUri = uri
        fileName = name
        encoding = detectedEncoding
        lineEndings = detectedEndings
        clearHistory()
        dirty = false
    }

    fun newDocument() {
        textState.setTextAndPlaceCursorAtEnd("")
        fileUri = null
        fileName = "Untitled"
        encoding = DocEncoding.UTF8
        lineEndings = LineEndings.WINDOWS
        clearHistory()
        dirty = false
    }

    /** Bytes for the current buffer, with the document's ending and BOM restored. */
    fun bytesToSave(): ByteArray = DocumentIO.encode(DocumentIO.denormalize(text, lineEndings), encoding)

    fun markSaved(uri: Uri?, name: String) {
        fileUri = uri ?: fileUri
        fileName = name
        dirty = false
    }

    // --- find / replace ---------------------------------------------------

    /** Selects the next match after the caret, wrapping once. True when found. */
    fun findNext(query: String = findQuery): Boolean {
        if (query.isEmpty()) return false
        val haystack = if (findMatchCase) text else text.lowercase()
        val needle = if (findMatchCase) query else query.lowercase()
        val from = selection.max
        val index = haystack.indexOf(needle, from).takeIf { it >= 0 } ?: haystack.indexOf(needle)
        if (index < 0) return false
        select(index, index + query.length)
        return true
    }

    fun findPrevious(query: String = findQuery): Boolean {
        if (query.isEmpty()) return false
        val haystack = if (findMatchCase) text else text.lowercase()
        val needle = if (findMatchCase) query else query.lowercase()
        val from = (selection.min - 1).coerceAtLeast(0)
        val index = haystack.lastIndexOf(needle, from).takeIf { it >= 0 } ?: haystack.lastIndexOf(needle)
        if (index < 0) return false
        select(index, index + query.length)
        return true
    }

    /** Replaces the selection when it is a match, then moves to the next one. */
    fun replaceNext(replacement: String = replaceQuery): Boolean {
        val query = findQuery
        if (query.isEmpty()) return false
        val selected = text.substring(selection.min, selection.max)
        val matches = if (findMatchCase) selected == query else selected.equals(query, ignoreCase = true)
        if (matches) {
            replaceSelection(replacement)
            dirty = true
        }
        return findNext(query)
    }

    /** Returns how many occurrences were replaced. */
    fun replaceAll(replacement: String = replaceQuery): Int {
        val query = findQuery
        if (query.isEmpty()) return 0
        var count = 0
        val result = StringBuilder()
        var index = 0
        while (index < text.length) {
            val found = text.indexOf(query, index, ignoreCase = !findMatchCase)
            if (found < 0) {
                result.append(text, index, text.length)
                break
            }
            result.append(text, index, found)
            result.append(replacement)
            count++
            index = found + query.length
        }
        if (count > 0) {
            val replaced = result.toString()
            textState.edit {
                replace(0, length, replaced)
                selection = TextRange(replaced.length)
            }
            dirty = true
        }
        return count
    }

    fun select(start: Int, end: Int) {
        val safeStart = start.coerceIn(0, text.length)
        val safeEnd = end.coerceIn(safeStart, text.length)
        textState.edit { selection = TextRange(safeStart, safeEnd) }
    }

    /** Replaces the current selection. The range must be captured *before* [edit]. */
    fun replaceSelection(with: String) {
        val range = selection
        textState.edit { replace(range.min, range.max, with) }
    }

    fun deleteSelection() = replaceSelection("")

    /** Flow the screen observes to keep [dirty] in sync while typing. */
    fun textChanges() = snapshotFlow { text }.distinctUntilChanged()

    fun zoomTo(percent: Float) {
        zoom = percent.coerceIn(50f, 300f)
    }

    fun zoomBy(step: Float) = zoomTo(zoom + step)
}
