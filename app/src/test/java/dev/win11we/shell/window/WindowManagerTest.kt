package dev.win11we.shell.window

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.win11we.shell.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Z-order / focus / taskbar-toggle behaviour of the window manager. */
class WindowManagerTest {

    private val desktop = DpSize(1200.dp, 700.dp)
    private val notepad = AppSpec("notepad", "Notepad", R.drawable.ic_app_notepad)
    private val explorer = AppSpec("explorer", "File Explorer", R.drawable.ic_app_explorer)

    @Test
    fun `open focuses the new window and raises it`() {
        val manager = WindowManager()
        val first = manager.open(notepad, desktop)
        assertEquals(first.id, manager.focusedId)
        val second = manager.open(explorer, desktop)
        assertEquals(second.id, manager.focusedId)
        assertTrue(second.zIndex > first.zIndex)
    }

    @Test
    fun `closing the focused window promotes the next topmost`() {
        val manager = WindowManager()
        val first = manager.open(notepad, desktop)
        val second = manager.open(explorer, desktop)
        manager.close(second)
        assertEquals(first.id, manager.focusedId)
        manager.close(first)
        assertNull(manager.focusedId)
        assertTrue(manager.windows.isEmpty())
    }

    @Test
    fun `toggle opens then minimizes then restores`() {
        val manager = WindowManager()
        manager.toggle(notepad, desktop)
        val window = manager.windows.single()
        assertFalse(window.minimized)

        manager.toggle(notepad, desktop) // focused -> minimize
        assertTrue(window.minimized)

        manager.toggle(notepad, desktop) // minimized -> restore + focus
        assertFalse(window.minimized)
        assertEquals(window.id, manager.focusedId)
    }

    @Test
    fun `minimizing the focused window focuses the next visible one`() {
        val manager = WindowManager()
        val first = manager.open(notepad, desktop)
        val second = manager.open(explorer, desktop)
        manager.minimize(second)
        assertEquals(first.id, manager.focusedId)
    }

    @Test
    fun `new windows cascade instead of stacking exactly on top of each other`() {
        val manager = WindowManager()
        val first = manager.open(notepad, desktop)
        val second = manager.open(notepad, desktop)
        assertTrue(second.offset.x > first.offset.x)
        assertTrue(second.offset.y > first.offset.y)
    }
}

/** The close button must respect an app's unsaved-changes guard (Notepad). */
class WindowCloseGuardTest {

    private val desktop = DpSize(1200.dp, 700.dp)
    private val notepad = AppSpec("notepad", "Notepad", R.drawable.ic_app_notepad)

    @Test
    fun `requestClose without a guard closes immediately`() {
        val manager = WindowManager()
        val window = manager.open(notepad, desktop)
        manager.requestClose(window)
        assertTrue(manager.windows.isEmpty())
    }

    @Test
    fun `requestClose with a guard asks the app instead of closing`() {
        val manager = WindowManager()
        val window = manager.open(notepad, desktop)
        var asked = 0
        window.closeGuard = { asked++ }
        manager.requestClose(window)
        assertEquals(1, asked)
        assertEquals(1, manager.windows.size)
        window.closeGuard = null // app decided to close anyway
        manager.requestClose(window)
        assertTrue(manager.windows.isEmpty())
    }
}
