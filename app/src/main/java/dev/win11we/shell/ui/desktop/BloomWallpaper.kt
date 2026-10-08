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
import dev.win11we.shell.ui.theme.DesktopDeep
import kotlin.math.cos
import kotlin.math.sin

private data class Petal(val tint: Color, val degrees: Float, val spread: Float)

/**
 * Original procedurally drawn "bloom" wallpaper: deep blue field with soft
 * petals around the lower-right third. No bitmap, no Microsoft artwork.
 */
@Composable
fun BloomWallpaper(modifier: Modifier = Modifier) {
    // Brushes and petal geometry are rebuilt only when the surface size changes, not
    // on every frame: the wallpaper is redrawn whenever a window above it animates.
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DesktopDeep)
            .drawWithCache {
                val w = size.width
                val h = size.height
                val cx = w * 0.62f
                val cy = h * 0.46f
                val r = minOf(w, h) * 0.62f
                val center = Offset(cx, cy)

                val background = Brush.verticalGradient(
                    colors = listOf(Color(0xFF0A1630), Color(0xFF050B1C), Color(0xFF02040C)),
                )
                val glowRadius = r * 1.25f
                val glow = Brush.radialGradient(
                    colors = listOf(Color(0x551E4C8A), Color.Transparent),
                    center = center,
                    radius = glowRadius,
                )
                val coreRadius = r * 0.16f
                val core = Brush.radialGradient(
                    colors = listOf(Color(0xB3CFEBFF), Color.Transparent),
                    center = center,
                    radius = coreRadius,
                )
                val petals = PETALS.map { petal ->
                    val rad = Math.toRadians(petal.degrees.toDouble())
                    val petalCenter = center + Offset(
                        x = (r * petal.spread * cos(rad)).toFloat(),
                        y = (r * petal.spread * sin(rad)).toFloat(),
                    )
                    val petalRadius = r * 0.46f
                    Triple(
                        petalCenter,
                        petalRadius,
                        Brush.radialGradient(listOf(petal.tint, Color.Transparent), petalCenter, petalRadius),
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

private val PETALS = listOf(
    Petal(Color(0x8C4CC2FF), -35f, 0.52f),
    Petal(Color(0x737A5CFF), 55f, 0.58f),
    Petal(Color(0x592ED0C8), 150f, 0.50f),
    Petal(Color(0x594A78E8), 235f, 0.55f),
)
