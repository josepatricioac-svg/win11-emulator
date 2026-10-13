package dev.win11we.shell.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.win11we.shell.R

/**
 * The shell's design tokens: one object, two palettes, everything a surface, a
 * label or an animation is allowed to use. Values are the web prototype's
 * (Win11-style Fluent): dark is the default, light follows Settings ▸
 * Personalisation ▸ Theme.
 *
 * Visuals only - nothing here reads state, touches disk or changes behaviour.
 * The motion half lives in [FluentMotion] so it stays unit-testable off-device.
 */
object Fluent {

    /** Corner radii: windows and flyouts 8, everything you click or type in 6. */
    object Radius {
        val window: Dp = 8.dp
        val flyout: Dp = 8.dp
        val control: Dp = 6.dp
    }

    /** Heights and text sizes. */
    object Size {
        val taskbarHeight: Dp = 48.dp
        val textBase = 14.sp
        val windowTitle = 12.5.sp
        val textSmall = 12.sp
    }

    /**
     * One shadow layer: CSS box-shadow semantics (offset-y in dp, blur radius in
     * dp, colour). [FluentShadow] draws these with a blur mask filter, because
     * Compose's `Modifier.shadow` only does a single elevation-based layer.
     */
    data class ShadowLayer(val offsetY: Dp, val blur: Dp, val color: Color)

    class FluentShadow(val layers: List<ShadowLayer>)

    object Elevation {
        private val black50 = Color(0x80000000)
        private val black35 = Color(0x59000000)
        private val black30 = Color(0x4D000000)
        private val black42 = Color(0x6B000000)

        /** Focused window: deep ambient + tight contact shadow. */
        val window = FluentShadow(
            listOf(ShadowLayer(24.dp, 60.dp, black50), ShadowLayer(4.dp, 14.dp, black35)),
        )

        /** Unfocused window: one softer layer, so focus reads at a glance. */
        val windowUnfocused = FluentShadow(listOf(ShadowLayer(10.dp, 30.dp, black30)))

        /** Start menu, flyouts, context and power menus. */
        val flyout = FluentShadow(
            listOf(ShadowLayer(16.dp, 40.dp, black42), ShadowLayer(2.dp, 10.dp, black30)),
        )
    }

    /** Compose easings matching [FluentMotion], for tween/animate-based motion. */
    val decelerateEasing = androidx.compose.animation.core.CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    val standardEasing = androidx.compose.animation.core.CubicBezierEasing(0.33f, 0f, 0.67f, 1f)

    /** Inter (SIL OFL 1.1): the open stand-in for Segoe UI Variable. */
    val family: FontFamily = FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal),
        Font(R.font.inter_semibold, FontWeight.SemiBold),
        Font(R.font.inter_bold, FontWeight.Bold),
    )
}

/**
 * Every colour a surface or label may use, for one theme. Alpha values are the
 * prototype's, expressed over whatever is behind them (mica/acrylic backdrops,
 * see step 2), never baked onto an opaque panel.
 */
data class FluentPalette(
    val accent: Color,
    val accentSolid: Color,
    val mica: Color,
    val acrylic: Color,
    val card: Color,
    val cardHover: Color,
    val cardPress: Color,
    val stroke: Color,
    val strokeStrong: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    /** The 1dp inset highlight along a surface's top inner edge. */
    val insetHighlight: Color,
    /** Text on accentSolid. */
    val onAccent: Color,
    val destructive: Color,
) {
    companion object {
        val Dark = FluentPalette(
            accent = Color(0xFF4CC2FF),
            accentSolid = Color(0xFF0078D4),
            mica = Color(0xD1242426), // rgba(36,36,38,.82)
            acrylic = Color(0xDB28282C), // rgba(40,40,44,.86)
            card = Color(0x0DFFFFFF), // white 5.2%
            cardHover = Color(0x16FFFFFF), // white 8.5%
            cardPress = Color(0x09FFFFFF), // white 3.5%
            stroke = Color(0x15FFFFFF), // white 8.3%
            strokeStrong = Color(0x24FFFFFF), // white 14%
            text = Color(0xFFFFFFFF),
            text2 = Color(0xB8FFFFFF), // white 72%
            text3 = Color(0x85FFFFFF), // white 52%
            insetHighlight = Color(0x0FFFFFFF), // white 6%
            onAccent = Color(0xFFFFFFFF),
            destructive = Color(0xFFC42B1C),
        )

        val Light = FluentPalette(
            accent = Color(0xFF005FB8),
            accentSolid = Color(0xFF0067C0),
            mica = Color(0xD9F3F3F3), // rgba(243,243,243,.85)
            acrylic = Color(0xE6FCFCFC), // rgba(252,252,252,.9)
            card = Color(0x9EFFFFFF), // white 62%
            cardHover = Color(0xD9FFFFFF), // white 85%
            cardPress = Color(0x08000000), // black 3%
            stroke = Color(0x14000000), // black 7.8%
            strokeStrong = Color(0x21000000), // black 13%
            text = Color(0xFF1B1B1B),
            text2 = Color(0xAD000000), // black 68%
            text3 = Color(0x7A000000), // black 48%
            insetHighlight = Color(0x99FFFFFF), // white 60%
            onAccent = Color(0xFFFFFFFF),
            destructive = Color(0xFFC42B1C),
        )
    }
}

/** The palette of the active theme; [FluentTheme] provides it. */
val LocalFluentPalette: ProvidableCompositionLocal<FluentPalette> =
    staticCompositionLocalOf { FluentPalette.Dark }

@Composable
fun fluentPalette(): FluentPalette = LocalFluentPalette.current

/**
 * Provides the palette and makes Inter the default face for every Text that
 * does not pick its own family. Wraps the existing [Win11Theme] so Material3
 * widgets keep working unchanged.
 */
@Composable
fun FluentTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    val palette = if (dark) FluentPalette.Dark else FluentPalette.Light
    CompositionLocalProvider(LocalFluentPalette provides palette) {
        Win11Theme(darkTheme = dark) {
            MaterialTheme(typography = fluentTypography()) {
                CompositionLocalProvider(
                    androidx.compose.material3.LocalTextStyle provides
                        MaterialTheme.typography.bodyMedium.copy(fontFamily = Fluent.family),
                    content = content,
                )
            }
        }
    }
}

/** The default Material typography, re-faced with [Fluent.family]. */
@Composable
private fun fluentTypography(): Typography {
    val base = Typography()
    fun TextStyle.refaced(size: androidx.compose.ui.unit.TextUnit? = null) =
        copy(fontFamily = Fluent.family).let { if (size == null) it else it.copy(fontSize = size) }
    return base.copy(
        displayLarge = base.displayLarge.refaced(),
        displayMedium = base.displayMedium.refaced(),
        displaySmall = base.displaySmall.refaced(),
        headlineLarge = base.headlineLarge.refaced(),
        headlineMedium = base.headlineMedium.refaced(),
        headlineSmall = base.headlineSmall.refaced(),
        titleLarge = base.titleLarge.refaced(),
        titleMedium = base.titleMedium.refaced(Fluent.Size.windowTitle),
        titleSmall = base.titleSmall.refaced(Fluent.Size.windowTitle),
        bodyLarge = base.bodyLarge.refaced(Fluent.Size.textBase),
        bodyMedium = base.bodyMedium.refaced(Fluent.Size.textBase),
        bodySmall = base.bodySmall.refaced(Fluent.Size.textSmall),
        labelLarge = base.labelLarge.refaced(Fluent.Size.textBase),
        labelMedium = base.labelMedium.refaced(Fluent.Size.textSmall),
        labelSmall = base.labelSmall.refaced(Fluent.Size.textSmall),
    )
}

// --- surface modifiers -------------------------------------------------------

/**
 * The two lines every Fluent surface carries: a 1dp border in the stroke token,
 * and a 1dp inset highlight along the top inner edge (white 6% dark / 60% light)
 * that follows the top corners - the detail that makes a panel read as glass
 * rather than as a flat rectangle.
 */
fun Modifier.fluentStroke(palette: FluentPalette, radius: Dp = Fluent.Radius.control): Modifier =
    drawWithContent {
        drawContent()
        drawFluentEdge(palette, radius)
    }

/** Draws border + inset top highlight for the current size; shared by surfaces. */
private fun DrawScope.drawFluentEdge(palette: FluentPalette, radius: Dp) {
    val strokePx = 1.dp.toPx()
    val cornerPx = radius.toPx()
    val inset = strokePx / 2f
    val corner = CornerRadius(maxOf(cornerPx - inset, 0f))
    val rectSize = Size(size.width - strokePx, size.height - strokePx)
    drawRoundRect(
        color = palette.stroke,
        topLeft = Offset(inset, inset),
        size = rectSize,
        cornerRadius = corner,
        style = Stroke(strokePx),
    )
    // Inset top highlight: the same rounded rect, clipped to the top band so only
    // the top edge and the two top corners light up.
    val bandHeight = maxOf(cornerPx + strokePx * 2f, strokePx * 3f)
    clipRect(0f, 0f, size.width, bandHeight) {
        drawRoundRect(
            color = palette.insetHighlight,
            topLeft = Offset(inset, inset),
            size = rectSize,
            cornerRadius = corner,
            style = Stroke(strokePx),
        )
    }
}

/**
 * A filled Fluent surface: optional shadow, the fill, then the standard edge.
 * Order matters - the shadow must sit behind the fill, the edge above it.
 */
fun Modifier.fluentSurface(
    palette: FluentPalette,
    fill: Color,
    radius: Dp = Fluent.Radius.control,
    shadow: Fluent.FluentShadow? = null,
): Modifier = this
    .then(if (shadow != null) fluentShadow(shadow, radius) else Modifier)
    .background(fill, RoundedCornerShape(radius))
    .fluentStroke(palette, radius)

/**
 * CSS box-shadow-equivalent: each layer is a blurred rounded rect offset below
 * the surface. BlurMaskFilter's radius is a sigma, and CSS blur is ~2 sigma, so
 * the dp blur is halved.
 */
fun Modifier.fluentShadow(shadow: Fluent.FluentShadow, radius: Dp = Fluent.Radius.window): Modifier =
    drawWithContent {
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint()
            shadow.layers.forEach { layer ->
                paint.color = android.graphics.Color.argb(
                    (layer.color.alpha * 255).toInt(),
                    (layer.color.red * 255).toInt(),
                    (layer.color.green * 255).toInt(),
                    (layer.color.blue * 255).toInt(),
                )
                // BlurMaskFilter's radius is a sigma and CSS blur is ~2 sigma.
                paint.maskFilter = android.graphics.BlurMaskFilter(
                    layer.blur.toPx() / 2f,
                    android.graphics.BlurMaskFilter.Blur.NORMAL,
                )
                // Inset by the blur so the soft edge grows from the surface edge
                // instead of being clipped by the layer bounds.
                val inset = layer.blur.toPx() / 2f
                val dy = layer.offsetY.toPx()
                canvas.nativeCanvas.drawRoundRect(
                    inset,
                    inset + dy,
                    size.width - inset,
                    size.height - inset + dy,
                    radius.toPx(),
                    radius.toPx(),
                    paint,
                )
            }
        }
        drawContent()
    }

/** Clips to a Fluent surface shape (used before drawing backdrops inside it). */
fun Modifier.fluentClip(radius: Dp = Fluent.Radius.window): Modifier =
    clip(RoundedCornerShape(radius))
