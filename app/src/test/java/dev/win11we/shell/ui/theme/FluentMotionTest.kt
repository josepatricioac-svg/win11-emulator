package dev.win11we.shell.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Bezier solver behind every shell animation, checked against points that
 * can be computed by hand from the curve definition: for cubic-bezier(x1,y1,x2,y2)
 * at parameter u, x(u) and y(u) are plain cubic polynomials, so (x(u), y(u)) is
 * an exact point of the easing the solver must reproduce.
 */
class FluentMotionTest {

    @Test
    fun `endpoints are exact and input is clamped`() {
        listOf(FluentMotion.DECELERATE, FluentMotion.STANDARD).forEach { curve ->
            assertEquals(0f, curve(0f), 0.0001f)
            assertEquals(1f, curve(1f), 0.0001f)
            assertEquals(0f, curve(-2f), 0.0001f)
            assertEquals(1f, curve(3f), 0.0001f)
        }
    }

    @Test
    fun `the decelerate curve passes through its own control-point polynomials`() {
        // u = 0.5: x = 3*.25*.5*.16 + 3*.5*.25*.3 + .125 = 0.2975, y = 0.875
        assertEquals(0.875f, FluentMotion.DECELERATE(0.2975f), 0.001f)
        // u = 0.25: x = 0.1253125, y = 0.578125
        assertEquals(0.578125f, FluentMotion.DECELERATE(0.1253125f), 0.001f)
        // u = 0.75: x = 3*.0625*.75*.16 + 3*.25*.5625*.3 + .421875 = 0.5709375,
        //           y = 3*.0625*.75 + 3*.25*.5625 + .421875 = 0.984375
        assertEquals(0.984375f, FluentMotion.DECELERATE(0.5709375f), 0.001f)
    }

    @Test
    fun `the standard curve is symmetric about the midpoint`() {
        // cubic-bezier(.33,0,.67,1) is point-symmetric: y(x) + y(1-x) == 1.
        for (i in 1..9) {
            val x = i / 10f
            assertEquals(1f, FluentMotion.STANDARD(x) + FluentMotion.STANDARD(1f - x), 0.002f)
        }
        assertEquals(0.5f, FluentMotion.STANDARD(0.5f), 0.001f)
    }

    @Test
    fun `both curves are monotonic and the decelerate one front-loads progress`() {
        listOf(FluentMotion.DECELERATE, FluentMotion.STANDARD).forEach { curve ->
            val samples = (0..20).map { curve(it / 20f) }
            samples.zipWithNext().forEach { (a, b) -> assertTrue("not monotonic", b >= a - 1e-6f) }
        }
        // Decelerate: most of the travel happens early (that is the whole point).
        assertTrue(FluentMotion.DECELERATE(0.3f) > 0.8f)
        assertTrue(FluentMotion.STANDARD(0.3f) < 0.5f)
    }

    @Test
    fun `the durations are the two tokens the spec defines`() {
        assertEquals(160L, FluentMotion.FAST_MILLIS)
        assertEquals(280L, FluentMotion.MEDIUM_MILLIS)
    }
}
