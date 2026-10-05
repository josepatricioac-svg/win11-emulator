package dev.win11we.shell.ui.window

import dev.win11we.shell.window.SnapZone
import org.junit.Assert.assertEquals
import org.junit.Test

/** Snap zones are pure geometry, so they are unit-tested without Robolectric. */
class SnapZoneTest {

    @Test
    fun `top edge maximizes`() {
        assertEquals(SnapZone.MAXIMIZE, snapZoneFor(0.5f, 0.01f))
    }

    @Test
    fun `side edges snap to halves`() {
        assertEquals(SnapZone.LEFT, snapZoneFor(0.02f, 0.5f))
        assertEquals(SnapZone.RIGHT, snapZoneFor(0.98f, 0.5f))
    }

    @Test
    fun `middle of the desktop does not snap`() {
        assertEquals(SnapZone.NONE, snapZoneFor(0.5f, 0.5f))
        assertEquals(SnapZone.NONE, snapZoneFor(0.2f, 0.9f))
    }

    @Test
    fun `top edge wins over side edges`() {
        assertEquals(SnapZone.MAXIMIZE, snapZoneFor(0.01f, 0.01f))
    }
}
