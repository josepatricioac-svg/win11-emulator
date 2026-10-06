package dev.win11we.shell.ui.window

import dev.win11we.shell.window.SnapZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The open/close ramp: monotonic, clamped, and exact at both ends. */
class AnimationCurveTest {

    @Test
    fun `endpoints are exact`() {
        assertEquals(0f, easeOut(0f), 0.0001f)
        assertEquals(1f, easeOut(1f), 0.0001f)
    }

    @Test
    fun `out-of-range input is clamped`() {
        assertEquals(0f, easeOut(-3f), 0.0001f)
        assertEquals(1f, easeOut(7f), 0.0001f)
    }

    @Test
    fun `the curve is monotonic and decelerating`() {
        val samples = (0..10).map { easeOut(it / 10f) }
        samples.zipWithNext().forEach { (a, b) -> assertTrue("not monotonic: $a -> $b", b >= a) }
        val firstStep = samples[1] - samples[0]
        val lastStep = samples[10] - samples[9]
        assertTrue("expected deceleration", firstStep > lastStep)
    }

    @Test
    fun `a window visual goes from invisible to settled and back`() {
        val visual = WindowVisual(progress = 0f, alpha = 0f, scale = 0.94f, shrink = 1f)
        visual.apply(enter = 0f, leave = 1f)
        assertEquals(0f, visual.alpha, 0.0001f)
        assertEquals(0.94f, visual.scale, 0.0001f)

        visual.apply(enter = 1f, leave = 1f)
        assertEquals(1f, visual.alpha, 0.0001f)
        assertEquals(1f, visual.scale, 0.0001f)
        assertEquals(1f, visual.progress, 0.0001f)

        visual.apply(enter = 1f, leave = 0f) // close animation finished
        assertEquals(0f, visual.alpha, 0.0001f)
        assertEquals(0f, visual.shrink, 0.0001f)
    }

    @Test
    fun `the snap thresholds stay where the drag code expects them`() {
        assertEquals(SnapZone.MAXIMIZE, snapZoneFor(0.5f, SNAP_TOP_FRACTION / 2f))
        assertEquals(SnapZone.LEFT, snapZoneFor(SNAP_SIDE_FRACTION / 2f, 0.5f))
        assertEquals(SnapZone.RIGHT, snapZoneFor(1f - SNAP_SIDE_FRACTION / 2f, 0.5f))
        assertEquals(SnapZone.NONE, snapZoneFor(0.5f, 0.5f))
    }
}
