package dev.win11we.shell.ui.taskbar

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.win11we.shell.settings.FakePreferences
import dev.win11we.shell.settings.SettingsStore
import dev.win11we.shell.ui.theme.Win11Theme
import dev.win11we.shell.window.ShellApps
import dev.win11we.shell.window.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Composes the real taskbar on the JVM (Robolectric) so popup and gesture
 * regressions are caught here instead of on a device.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric's default screen is 320dp wide, which clips the 8th pin and shifts hit targets;
// size the display like the desktop the test pretends to be.
@Config(sdk = [34], qualifiers = "w900dp-h500dp")
class TaskbarComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private val desktop = DpSize(900.dp, 500.dp)
    private val manager = WindowManager()
    private val store = SettingsStore(FakePreferences())

    private var startClicks = 0
    private var openedViaMenu = 0

    private fun setTaskbar() {
        compose.setContent {
            Win11Theme {
                Taskbar(
                    manager = manager,
                    desktopSize = desktop,
                    store = store,
                    startOpen = false,
                    onStartClick = { startClicks++ },
                    onOpenApp = { openedViaMenu++ },
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `every pinned app is rendered exactly once`() {
        setTaskbar()
        listOf("Start", "File Explorer", "Browser", "Notepad", "Settings", "Terminal", "Task Manager")
            .forEach { label -> compose.onNodeWithContentDescription(label).assertIsDisplayed() }
        assertEquals(7, ShellApps.count { it.pinned })
        compose.onNodeWithContentDescription("Container Manager").assertIsDisplayed()
    }

    @Test
    fun `no jump list is composed before the user asks for one`() {
        setTaskbar()
        // Regression guard: an ungated Popup used to draw every pin's menu at once.
        compose.onNodeWithText("New window").assertDoesNotExist()
        compose.onNodeWithText("Restore").assertDoesNotExist()
        // Pins expose their label as a content description (icon-only buttons), so the
        // Task Manager pin is one description node and no text node until a menu opens.
        compose.onAllNodes(hasContentDescription("Task Manager")).assertCountEquals(1)
        compose.onAllNodes(hasText("Task Manager")).assertCountEquals(0)
    }

    @Test
    fun `clicking a pin opens its window and the start pin reports clicks`() {
        setTaskbar()
        compose.onNodeWithContentDescription("Notepad").performClick()
        compose.waitForIdle()
        assertEquals(1, manager.windows.size)
        assertEquals("notepad", manager.windows.first().app.id)

        compose.onNodeWithContentDescription("Start").performClick()
        compose.waitForIdle()
        assertEquals(1, startClicks)
    }

    @Test
    fun `long press opens exactly one jump list for that pin`() {
        setTaskbar()
        compose.onNodeWithContentDescription("Notepad").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onAllNodes(hasText("New window")).assertCountEquals(1)
        // The menu header names the pin it belongs to, and only that pin.
        compose.onAllNodes(hasText("Notepad")).assertCountEquals(1)
        compose.onAllNodes(hasText("File Explorer")).assertCountEquals(0)
    }

    @Test
    fun `a jump list entry opens the app and dismisses the menu`() {
        setTaskbar()
        compose.onNodeWithContentDescription("Notepad").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText("Task Manager").performClick()
        compose.waitForIdle()
        assertEquals(1, openedViaMenu)
        compose.onNodeWithText("New window").assertDoesNotExist()
    }

    @Test
    fun `hiding a pin in settings removes it from the taskbar`() {
        store.showBrowserPin = false
        setTaskbar()
        compose.onNodeWithContentDescription("Browser").assertDoesNotExist()
        assertTrue(ShellApps.any { it.id == "browser" })
    }
}
