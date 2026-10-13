package dev.win11we.shell.ui.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dev.win11we.shell.ui.theme.fluentPalette
import kotlin.math.cos
import kotlin.math.sin

/**
 * Original procedurally drawn "bloom" wallpaper: a deep field with soft petals
 * around the lower-right third. No bitmap, no Microsoft artwork.
 *
 * The geometry and colours live in [BloomSpec] so the mica/acrylic backdrops
 * (which pre-render this same artwork, blurred and saturated) can never drift
 * from what the desktop shows.
 */
@Composable
fun BloomWallpaper(modifier: Modifier = Modifier) {
    val spec = BloomSpec.forDark(fluentPalette().isDark)
    // Brushes and petal geometry are rebuilt only when the surface size changes, not
    // on every frame: the wallpaper is redrawn whenever a window above it animates.
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(spec.background.last()))
            .drawWithCache {
                val w = size.width
                val h = size.height
                val cx = w * BloomSpec.CENTER_X
                val cy = h * BloomSpec.CENTER_Y
                val r = minOf(w, h) * BloomSpec.RADIUS
                val center = Offset(cx, cy)

                val background = Brush.verticalGradient(spec.background.map { Color(it) })
                val glowRadius = r * 1.25f
                val glow = Brush.radialGradient(
                    colors = spec.glow.map { Color(it) },
                    center = center,
                    radius = glowRadius,
                )
                val coreRadius = r * BloomSpec.CORE_FRACTION
                val core = Brush.radialGradient(
                    colors = spec.core.map { Color(it) },
                    center = center,
                    radius = coreRadius,
                )
                val petals = spec.petals.map { petal ->
                    val rad = Math.toRadians(petal.degrees.toDouble())
                    val petalCenter = center + Offset(
                        x = (r * petal.spread * cos(rad)).toFloat(),
                        y = (r * petal.spread * sin(rad)).toFloat(),
                    )
                    val petalRadius = r * BloomSpec.PETAL_RADIUS
                    Triple(
                        petalCenter,
                        petalRadius,
                        Brush.radialGradient(
                            listOf(Color(petal.tint), Color.Transparent),
                            petalCenter,
                            petalRadius,
                        ),
                    )
                }
                onDrawBehind {
                    drawRect(brush = background)
                    drawCircle(brush = glow, radius = glowRadius, center = center)
                    petals.forEach { (petalCenter, petalRadius, brush) ->
                        drawCircle(brush = brush, radius = petalRadius, center = petalCenter)
                    }
                    drawCircle(brush = core, radius = coreRadius, center = center)
                }
            },
    )
}
