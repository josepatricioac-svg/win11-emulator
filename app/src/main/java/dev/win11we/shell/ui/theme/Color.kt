package dev.win11we.shell.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Windows 11-style dark palette, original values (no Microsoft assets).
 * Mica/acrylic surfaces are approximated with translucent scrims + blur.
 */
val DesktopDeep = Color(0xFF050B1C)
val MicaDark = Color(0xE6161A22)
val AcrylicDark = Color(0xCC1B1F27)
val AccentBlue = Color(0xFF4CC2FF)
val AccentViolet = Color(0xFF7A5CFF)
val TextPrimary = Color(0xFFF3F6FB)
val TextSecondary = Color(0xB3E6EDF7)
val StrokeSubtle = Color(0x22FFFFFF)

private val DarkColors = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color(0xFF00161F),
    secondary = AccentViolet,
    background = DesktopDeep,
    onBackground = TextPrimary,
    surface = MicaDark,
    onSurface = TextPrimary,
    surfaceVariant = AcrylicDark,
    onSurfaceVariant = TextSecondary,
    outline = StrokeSubtle,
)

@Composable
fun Win11Theme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
