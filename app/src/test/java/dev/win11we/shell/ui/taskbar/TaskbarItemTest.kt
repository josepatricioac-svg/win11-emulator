package dev.win11we.shell.ui.taskbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * N0 smoke test: the pin model is plain data, so the taskbar wiring can be
 * unit-tested without Robolectric. The real pin list (resource ids) is covered
 * by instrumented tests later; R2 will add the env-builder tests here.
 */
class TaskbarItemTest {

    @Test
    fun `unwired pin has no click handler`() {
        val pin = TaskbarItem(id = "explorer", labelRes = 0, iconRes = 0)
        assertEquals("explorer", pin.id)
        assertNull(pin.onClick)
    }

    @Test
    fun `wired pin invokes its handler once`() {
        var clicks = 0
        val start = TaskbarItem(id = "start", labelRes = 0, iconRes = 0, onClick = { clicks++ })
        start.onClick?.invoke()
        assertEquals(1, clicks)
    }
}
