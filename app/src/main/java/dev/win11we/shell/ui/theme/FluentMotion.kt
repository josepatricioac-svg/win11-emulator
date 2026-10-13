package dev.win11we.shell.ui.theme

import kotlin.math.abs

/**
 * The motion half of the Fluent design tokens: the two curves the whole shell
 * animates with, and the two durations.
 *
 * Pure Kotlin on purpose (no Compose imports) so the curve solver is unit-tested
 * off-device: [cubicBezier] is the same maths `cubic-bezier()` is in the web
 * prototype this shell is being restyled against - default "decelerate"
 * `(.16, 1, .3, 1)` for anything that enters or settles, "standard"
 * `(.33, 0, .67, 1)` for state changes that move both ways.
 *
 * Durations: **fast = 160 ms** (minimize/restore, flyout fades, snap preview),
 * **medium = 280 ms** (window open/close). The Settings ▸ Personalisation
 * "Window animations" switch still collapses both to 1 ms, unchanged.
 */
object FluentMotion {
    const val FAST_MILLIS = 160L
    const val MEDIUM_MILLIS = 280L

    /** Entering/settling: fast start, long asymptote. The shell's default. */
    val DECELERATE: (Float) -> Float = cubicBezier(0.16f, 1f, 0.3f, 1f)

    /** Symmetric curve for reversible state changes. */
    val STANDARD: (Float) -> Float = cubicBezier(0.33f, 0f, 0.67f, 1f)

    /**
     * Evaluates the cubic Bezier y(x) with control points (x1,y1),(x2,y2) and
     * endpoints (0,0),(1,1): solve x(u)=x for u (Newton with a bisection
     * fallback, the same approach browsers use), then return y(u).
     */
    fun cubicBezier(x1: Float, y1: Float, x2: Float, y2: Float): (Float) -> Float = { x ->
        val t = x.coerceIn(0f, 1f)
        if (t == 0f || t == 1f) t else bezierY(solveU(t, x1, x2), y1, y2)
    }

    /** x(u) = 3(1-u)^2*u*x1 + 3(1-u)*u^2*x2 + u^3 */
    private fun bezierX(u: Float, x1: Float, x2: Float): Float {
        val inv = 1f - u
        return 3f * inv * inv * u * x1 + 3f * inv * u * u * x2 + u * u * u
    }

    private fun bezierY(u: Float, y1: Float, y2: Float): Float {
        val inv = 1f - u
        return 3f * inv * inv * u * y1 + 3f * inv * u * u * y2 + u * u * u
    }

    private fun solveU(x: Float, x1: Float, x2: Float): Float {
        // Newton-Raphson on x(u) - x.
        var u = x
        var iterations = 0
        while (iterations < 8) {
            val dx = bezierX(u, x1, x2) - x
            if (abs(dx) < 1e-5f) return u
            // dx/du of the same cubic, for the Newton step.
            val inv = 1f - u
            val derivative = 3f * inv * inv * x1 + 6f * inv * u * (x2 - x1) + 3f * u * u * (1f - x2)
            if (abs(derivative) < 1e-6f) break
            u = (u - dx / derivative).coerceIn(0f, 1f)
            iterations++
        }
        // Bisection fallback for the flat parts of very eased curves.
        var low = 0f
        var high = 1f
        var bisected = x
        iterations = 0
        while (iterations < 24) {
            bisected = (low + high) / 2f
            val dx = bezierX(bisected, x1, x2) - x
            if (abs(dx) < 1e-6f) return bisected
            if (dx < 0f) low = bisected else high = bisected
            iterations++
        }
        return bisected
    }
}
