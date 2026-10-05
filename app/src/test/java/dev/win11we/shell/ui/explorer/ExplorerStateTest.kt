package dev.win11we.shell.ui.explorer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Navigation, selection and the mutating commands of one Explorer window. */
class ExplorerStateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun state(): ExplorerState {
        File(tmp.root, "Docs").mkdir()
        File(tmp.root, "Docs/inner").mkdir()
        File(tmp.root, "Docs/readme.txt").writeText("hello")
        return ExplorerState(root = tmp.root)
    }

    @Test
    fun `navigateTo pushes history and clears the selection`() {
        val state = state()
        val docs = File(tmp.root, "Docs")
        state.select(docs, additive = false)
        state.navigateTo(docs)
        assertEquals(docs, state.current)
        assertTrue(state.selection.isEmpty())
        assertTrue(state.canGoBack)
        assertFalse(state.canGoForward)
    }

    @Test
    fun `back and forward walk the history`() {
        val state = state()
        val docs = File(tmp.root, "Docs")
        val inner = File(docs, "inner")
        state.navigateTo(docs)
        state.navigateTo(inner)
        assertTrue(state.back())
        assertEquals(docs, state.current)
        assertTrue(state.forward())
        assertEquals(inner, state.current)
    }

    @Test
    fun `up stops at the mapped root`() {
        val state = state()
        state.navigateTo(File(tmp.root, "Docs/inner"))
        assertTrue(state.up())
        assertEquals(File(tmp.root, "Docs"), state.current)
        assertTrue(state.up())
        assertEquals(tmp.root, state.current)
        assertFalse(state.up())
        assertFalse(state.canGoUp)
    }

    @Test
    fun `trail goes from the root down to the current folder`() {
        val state = state()
        state.navigateTo(File(tmp.root, "Docs/inner"))
        assertEquals(listOf(tmp.root, File(tmp.root, "Docs"), File(tmp.root, "Docs/inner")), state.trail())
    }

    @Test
    fun `toggleSort flips direction for the same key and resets for a new one`() {
        val state = state()
        assertEquals(SortKey.NAME, state.sortKey)
        state.toggleSort(SortKey.NAME)
        assertFalse(state.ascending)
        state.toggleSort(SortKey.SIZE)
        assertEquals(SortKey.SIZE, state.sortKey)
        assertTrue(state.ascending)
    }

    @Test
    fun `select toggles and additive keeps the rest`() {
        val state = state()
        val readme = File(tmp.root, "Docs/readme.txt")
        val docs = File(tmp.root, "Docs")
        state.select(readme, additive = false)
        state.select(docs, additive = true)
        assertEquals(2, state.selection.size)
        state.select(docs, additive = true) // second click removes it again
        assertEquals(listOf(readme), state.selection.toList())
        state.navigateTo(File(tmp.root, "Docs"))
        state.selectAll()
        assertEquals(2, state.selection.size)
        state.clearSelection()
        assertTrue(state.selection.isEmpty())
    }

    @Test
    fun `createFolder rejects empty, duplicate and traversal names`() {
        val state = state()
        assertNull(state.createFolder("   "))
        assertNull(state.createFolder("a/b"))
        assertNull(state.createFolder(".dot"))
        assertNull(state.createFolder("Docs")) // already there
        val created = state.createFolder("Work")
        assertTrue(created != null && created.isDirectory)
        assertTrue(state.entries().any { it.name == "Work" })
    }

    @Test
    fun `rename keeps the file in the same folder`() {
        val state = state()
        state.navigateTo(File(tmp.root, "Docs"))
        val readme = File(tmp.root, "Docs/readme.txt")
        val renamed = state.rename(readme, "notes.txt")
        assertTrue(renamed != null && renamed.isFile)
        assertEquals(File(tmp.root, "Docs/notes.txt"), renamed)
        assertFalse(readme.exists())
        assertNull(state.rename(renamed!!, "inner")) // taken by the folder
    }

    @Test
    fun `delete removes files and folders recursively`() {
        val state = state()
        assertTrue(state.delete(File(tmp.root, "Docs/readme.txt")))
        assertTrue(state.delete(File(tmp.root, "Docs")))
        assertFalse(File(tmp.root, "Docs").exists())
        assertFalse(state.entries().any { it.name == "Docs" })
    }

    @Test
    fun `every mutation bumps the revision so the list recomposes`() {
        val state = state()
        val before = state.revision
        state.createFolder("Work")
        assertTrue(state.revision > before)
        val afterCreate = state.revision
        state.rename(File(tmp.root, "Work"), "Play")
        assertTrue(state.revision > afterCreate)
        val afterRename = state.revision
        state.refresh()
        assertTrue(state.revision > afterRename)
    }
}
