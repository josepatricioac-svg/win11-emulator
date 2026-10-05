package dev.win11we.shell.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Windows 11-style dark palette. Original values, no Microsoft assets.
 * "Acrylic"/"mica" surfaces are approximated with translucent scrims.
 */
val DesktopDeep = Color(0xFF050B1C)
val TextPrimary = Color(0xFFF3F6FB)
val TextSecondary = Color(0xB3E6EDF7)
val TextTertiary = Color(0x80E6EDF7)
val StrokeSubtle = Color(0x1FFFFFFF)
val HoverLight = Color(0x0FFFFFFF)
val PressedLight = Color(0x1AFFFFFF)
val AccentBlue = Color(0xFF4CC2FF)
val AccentViolet = Color(0xFF7A5CFF)

/** Surfaces */
val TaskbarSurface = Color(0xF01A1E27)
val StartSurface = Color(0xF71C2029)
val CardSurface = Color(0x0DFFFFFF)
val WindowDark = Color(0xFF1F242E)
val WindowContentDark = Color(0xFF1B1F27)
val Destructive = Color(0xFFC42B1C)

private val DarkColors = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color(0xFF00161F),
    secondary = AccentViolet,
    background = DesktopDeep,
    onBackground = TextPrimary,
    surface = TaskbarSurface,
    onSurface = TextPrimary,
    surfaceVariant = CardSurface,
    onSurfaceVariant = TextSecondary,
    outline = StrokeSubtle,
    error = Destructive,
)

@Composable
fun Win11Theme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
