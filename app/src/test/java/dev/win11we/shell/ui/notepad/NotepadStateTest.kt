package dev.win11we.shell.ui.notepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Buffer, find/replace and view state of one Notepad window. Undo/redo is
 * delegated to the text field's own history and is exercised on device.
 */
class NotepadStateTest {

    private fun loaded(text: String = "hello world\nsecond line", name: String = "notes.txt"): NotepadState {
        val state = NotepadState()
        state.load(
            text = text,
            uri = null,
            name = name,
            detectedEncoding = DocEncoding.UTF8,
            detectedEndings = LineEndings.WINDOWS,
        )
        return state
    }

    @Test
    fun `a fresh document is clean and untitled`() {
        val state = NotepadState()
        assertEquals("Untitled - Notepad", state.caption())
        assertFalse(state.dirty)
        assertNull(state.fileUri)
        assertEquals(DocEncoding.UTF8, state.encoding)
        assertEquals(LineEndings.WINDOWS, state.lineEndings)
    }

    @Test
    fun `loading normalizes line endings and clears the dirty flag`() {
        val state = loaded(text = "a\r\nb\rc")
        assertEquals("a\nb\nc", state.text)
        assertEquals("notes.txt - Notepad", state.caption())
        assertFalse(state.dirty)
    }

    @Test
    fun `saving restores the document's endings and bom`() {
        val state = loaded(text = "a\r\nb")
        state.encoding = DocEncoding.UTF8_BOM
        val bytes = state.bytesToSave()
        assertEquals(DocEncoding.UTF8_BOM, DocumentIO.detectEncoding(bytes))
        assertEquals("a\r\nb", DocumentIO.decode(bytes, DocEncoding.UTF8_BOM))
    }

    @Test
    fun `editing marks the document dirty and the caption shows it`() {
        val state = loaded()
        state.textState.edit { replace(length, length, "!") }
        state.dirty = true
        assertEquals("*notes.txt - Notepad", state.caption())
    }

    @Test
    fun `findNext selects the next match and wraps around`() {
        val state = loaded(text = "cat dog cat")
        state.findQuery = "cat"
        assertTrue(state.findNext())
        assertEquals(0, state.selection.min)
        assertEquals(3, state.selection.max)
        assertTrue(state.findNext()) // second occurrence
        assertEquals(8, state.selection.min)
        assertTrue(state.findNext()) // wraps to the first one again
        assertEquals(0, state.selection.min)
    }

    @Test
    fun `find is case-insensitive unless match case is on`() {
        val state = loaded(text = "Cat cat CAT")
        state.findQuery = "cat"
        assertTrue(state.findNext())
        assertEquals(0, state.selection.min)

        state.findMatchCase = true
        state.select(0, 0)
        assertTrue(state.findNext())
        assertEquals(4, state.selection.min) // only the lowercase one matches
    }

    @Test
    fun `findPrevious walks backwards and reports misses`() {
        val state = loaded(text = "aa bb aa")
        state.findQuery = "aa"
        state.select(8, 8)
        assertTrue(state.findPrevious())
        assertEquals(6, state.selection.min)
        assertTrue(state.findPrevious())
        assertEquals(0, state.selection.min)

        state.findQuery = "zz"
        assertFalse(state.findNext())
        assertFalse(state.findPrevious())
        assertFalse(NotepadState().findNext("")) // empty query never matches
    }

    @Test
    fun `replaceNext replaces the matching selection then selects the next one`() {
        val state = loaded(text = "one two one")
        state.findQuery = "one"
        state.replaceQuery = "1"
        state.select(0, 3) // "one" matches
        state.replaceNext()
        assertEquals("1 two one", state.text)
        assertEquals(6, state.selection.min) // jumped to the remaining "one"
        assertEquals(9, state.selection.max)
        assertTrue(state.dirty)
    }

    @Test
    fun `replaceNext leaves a non-matching selection alone and just advances`() {
        val state = loaded(text = "one two one")
        state.findQuery = "one"
        state.replaceQuery = "1"
        state.select(3, 6) // "two" is not a match
        state.replaceNext()
        assertEquals("one two one", state.text)
        assertEquals(8, state.selection.min)
    }

    @Test
    fun `replaceAll counts every replacement and keeps unrelated text`() {
        val state = loaded(text = "aXbXcXd")
        state.findQuery = "X"
        state.replaceQuery = "-"
        assertEquals(3, state.replaceAll())
        assertEquals("a-b-c-d", state.text)
        assertEquals(0, state.replaceAll()) // nothing left to do
    }

    @Test
    fun `selectAll selection and helpers agree`() {
        val state = loaded(text = "four")
        assertFalse(state.hasSelection())
        state.selectAll()
        assertTrue(state.hasSelection())
        assertEquals("four", state.selectedText())
        assertEquals(0, state.selection.min)
        assertEquals(4, state.selection.max)
    }

    @Test
    fun `status bar numbers follow the caret`() {
        val state = loaded(text = "line1\nline22\nline333")
        state.select(8, 8) // second character of the second line
        assertEquals(TextPosition(2, 3), state.position())
        assertEquals(3, state.lineCount())
        assertEquals(20, state.text.length)
    }

    @Test
    fun `insertTimeStamp writes at the caret`() {
        val state = loaded(text = "ab")
        state.select(1, 1)
        state.insertTimeStamp(stamp = "12:00")
        assertEquals("a12:00b", state.text)
        assertTrue(state.dirty)
    }

    @Test
    fun `zoom stays inside the supported range`() {
        val state = NotepadState()
        state.zoomBy(10f)
        assertEquals(110f, state.zoom)
        state.zoomTo(1000f)
        assertEquals(300f, state.zoom)
        state.zoomTo(1f)
        assertEquals(50f, state.zoom)
        assertEquals("50%", state.zoomLabel())
    }

    @Test
    fun `new document resets buffer file and view state`() {
        val state = loaded()
        state.dirty = true
        state.encoding = DocEncoding.ANSI
        state.lineEndings = LineEndings.UNIX
        state.newDocument()
        assertEquals("", state.text)
        assertEquals("Untitled - Notepad", state.caption())
        assertNull(state.fileUri)
        assertEquals(DocEncoding.UTF8, state.encoding)
        assertEquals(LineEndings.WINDOWS, state.lineEndings)
        assertFalse(state.dirty)
    }

    @Test
    fun `markSaved clears the dirty flag and adopts the new name`() {
        val state = loaded()
        state.dirty = true
        state.markSaved(uri = null, name = "renamed.txt")
        assertEquals("renamed.txt - Notepad", state.caption())
        assertFalse(state.dirty)
    }
}
