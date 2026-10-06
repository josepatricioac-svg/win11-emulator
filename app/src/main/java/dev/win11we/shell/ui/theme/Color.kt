package dev.win11we.shell.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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

/** Surfaces (dark) */
val TaskbarSurface = Color(0xF01A1E27)
val StartSurface = Color(0xF71C2029)
val CardSurface = Color(0x0DFFFFFF)
val WindowDark = Color(0xFF1F242E)
val WindowContentDark = Color(0xFF1B1F27)
val Destructive = Color(0xFFC42B1C)

/** Light "day" scheme, for the Settings ▸ Personalisation toggle. */
val DesktopLight = Color(0xFFE8EEF7)
val TaskbarLight = Color(0xF2F1F4F9)
val StartLight = Color(0xF7F4F7FC)
val CardLight = Color(0x0A000000)
val WindowLight = Color(0xFFF6F8FC)
val StrokeLight = Color(0x14000000)
val TextDarkPrimary = Color(0xFF14181F)
val TextDarkSecondary = Color(0xB31B2129)

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

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F6CBD),
    onPrimary = Color.White,
    secondary = AccentViolet,
    background = DesktopLight,
    onBackground = TextDarkPrimary,
    surface = TaskbarLight,
    onSurface = TextDarkPrimary,
    surfaceVariant = CardLight,
    onSurfaceVariant = TextDarkSecondary,
    outline = StrokeLight,
    error = Destructive,
)

@Composable
fun Win11Theme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}

/**
 * Surface colours that follow the active scheme, so the light theme is coherent
 * instead of dark panels on a light wallpaper.
 */
object ShellSurfaces {
    val taskbar: Color
        @Composable get() = if (isSystemInDarkTheme() || !isLightScheme()) TaskbarSurface else TaskbarLight

    val start: Color
        @Composable get() = if (isLightScheme()) StartLight else StartSurface

    val card: Color
        @Composable get() = if (isLightScheme()) CardLight else CardSurface

    val window: Color
        @Composable get() = if (isLightScheme()) WindowLight else WindowDark

    val windowContent: Color
        @Composable get() = if (isLightScheme()) Color(0xFFFFFFFF) else WindowContentDark

    val stroke: Color
        @Composable get() = if (isLightScheme()) StrokeLight else StrokeSubtle

    @Composable
    private fun isLightScheme() = MaterialTheme.colorScheme.background == DesktopLight
}

@Composable
private fun isSystemInDarkTheme() = MaterialTheme.colorScheme.background == DesktopDeep
