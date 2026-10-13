package dev.win11we.shell.ui.desktop

import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import dev.win11we.shell.ui.theme.Fluent
import dev.win11we.shell.ui.theme.fluentStroke
import dev.win11we.shell.ui.theme.FluentPalette
import dev.win11we.shell.ui.theme.fluentStroke
import kotlin.math.max
import kotlin.math.min

/**
 * Mica and acrylic for Compose.
 *
 * Compose cannot blur what is behind a composable, so the backdrop is
 * **pre-rendered**: the procedural bloom wallpaper is drawn once into two tiny
 * bitmaps (the desktop at 1/16 for mica, 1/32 for acrylic), saturated while it
 * is drawn (1.55 / 1.6, the prototype's values) and then upscaled with linear
 * filtering when a surface samples it - the upscale of a 1/16 render *is* the
 * 50-60dp blur, for a fraction of a millisecond, once per size and theme.
 *
 * Sampling is position-aligned like the real thing: a window at desktop (x, y)
 * reads the backdrop at (x, y), so mica stays put while the window moves over
 * it. Acrylic adds the grain: a seeded 128px noise bitmap tiled at ~3.5% alpha.
 *
 * Nothing here touches disk, and generation happens in `remember` (a few
 * hundred pixels of Canvas work), never per frame.
 */
class BackdropSet internal constructor(
    val mica: ImageBitmap,
    val acrylic: ImageBitmap,
    val noise: ImageBitmap,
    /** The desktop size in dp these bitmaps represent. */
    val desktopWidth: Float,
    val desktopHeight: Float,
)

/** Where a surface sits on the desktop, in dp; evaluated in the draw phase. */
class BackdropSample(
    val position: () -> Offset,
    val size: () -> Size,
)

val LocalFluentBackdrop: ProvidableCompositionLocal<BackdropSet?> =
    staticCompositionLocalOf { null }

object FluentBackdrop {
    /** 1/16 of the desktop reads as ~50dp of blur once upscaled; 1/32 as ~60dp. */
    private const val MICA_DIVISOR = 16
    private const val ACRYLIC_DIVISOR = 32
    private const val MICA_SATURATION = 1.55f
    private const val ACRYLIC_SATURATION = 1.6f
    private const val NOISE_ALPHA = 0.035f

    fun create(desktopWidth: Float, desktopHeight: Float, dark: Boolean): BackdropSet = BackdropSet(
        mica = renderBloom(
            max(8, (desktopWidth / MICA_DIVISOR).toInt()),
            max(8, (desktopHeight / MICA_DIVISOR).toInt()),
            dark,
            MICA_SATURATION,
        ),
        acrylic = renderBloom(
            max(6, (desktopWidth / ACRYLIC_DIVISOR).toInt()),
            max(6, (desktopHeight / ACRYLIC_DIVISOR).toInt()),
            dark,
            ACRYLIC_SATURATION,
        ),
        noise = renderNoise(),
        desktopWidth = desktopWidth,
        desktopHeight = desktopHeight,
    )

    /** The bloom wallpaper at bitmap scale, saturated: the mica/acrylic source. */
    private fun renderBloom(width: Int, height: Int, dark: Boolean, saturation: Float): ImageBitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(
            android.graphics.ColorMatrix().apply { setSaturation(saturation) },
        )
        val w = width.toFloat()
        val h = height.toFloat()
        val spec = BloomSpec.forDark(dark)
        paint.shader = LinearGradient(
            w / 2f, 0f, w / 2f, h,
            spec.background, null, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        val cx = w * BloomSpec.CENTER_X
        val cy = h * BloomSpec.CENTER_Y
        val r = min(w, h) * BloomSpec.RADIUS
        paint.shader = RadialGradient(
            cx, cy, r * 1.25f, spec.glow, null, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r * 1.25f, paint)
        spec.petals.forEach { petal ->
            val rad = Math.toRadians(petal.degrees.toDouble())
            val px = cx + (r * petal.spread * Math.cos(rad)).toFloat()
            val py = cy + (r * petal.spread * Math.sin(rad)).toFloat()
            val pr = r * 0.46f
            paint.shader = RadialGradient(px, py, pr, petal.tint, null, Shader.TileMode.CLAMP)
            canvas.drawCircle(px, py, pr, paint)
        }
        paint.shader = RadialGradient(cx, cy, r * 0.16f, spec.core, null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 0.16f, paint)
        paint.shader = null
        return bitmap.asImageBitmap()
    }

    /** Seeded grain so acrylic reads as a material, not as a flat tint. */
    private fun renderNoise(): ImageBitmap {
        val n = 128
        val bitmap = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(n * n)
        val random = java.util.Random(0x5EEDL)
        val alpha = (NOISE_ALPHA * 255).toInt() shl 24
        for (i in pixels.indices) {
            val v = random.nextInt(256)
            pixels[i] = alpha or (v shl 16) or (v shl 8) or v
        }
        bitmap.setPixels(pixels, 0, n, 0, 0, n, n)
        return bitmap.asImageBitmap()
    }
}

/**
 * Mica: the blurred, saturated desktop behind the surface, then the mica tint,
 * then the content, then the standard Fluent edge. Composable so the backdrop
 * and palette are captured once; the sample rect itself is read in the draw
 * phase, so a dragging window re-samples without recomposing.
 */
@Composable
fun Modifier.micaBackdrop(sample: BackdropSample, radius: Dp = Fluent.Radius.window): Modifier =
    fluentBackdrop(sample, radius, acrylic = false)

/** Acrylic: stronger blur, heavier tint and grain - Start, flyouts, menus. */
@Composable
fun Modifier.acrylicBackdrop(sample: BackdropSample, radius: Dp = Fluent.Radius.flyout): Modifier =
    fluentBackdrop(sample, radius, acrylic = true)

@Composable
private fun Modifier.fluentBackdrop(sample: BackdropSample, radius: Dp, acrylic: Boolean): Modifier {
    val backdrop = LocalFluentBackdrop.current
    val palette = dev.win11we.shell.ui.theme.LocalFluentPalette.current
    return this
        .drawWithContent {
            if (backdrop != null) {
                drawBackdrop(backdrop, sample, acrylic)
                drawRect(color = if (acrylic) palette.acrylic else palette.mica)
                if (acrylic) {
                    drawRect(
                        brush = ShaderBrush(
                            ImageShader(backdrop.noise, TileMode.Repeated, TileMode.Repeated),
                        ),
                    )
                }
            } else {
                // No backdrop yet (first frame before remember ran): the tint
                // alone is still a correct Fluent surface.
                drawRect(color = if (acrylic) palette.acrylic else palette.mica)
            }
            drawContent()
        }
        .fluentStroke(palette, radius)
}

/** Samples the pre-rendered desktop at the surface's position, in the draw phase. */
private fun DrawScope.drawBackdrop(backdrop: BackdropSet, sample: BackdropSample, acrylic: Boolean) {
    val image = if (acrylic) backdrop.acrylic else backdrop.mica
    val pos = sample.position()
    val area = sample.size()
    val scaleX = image.width / backdrop.desktopWidth
    val scaleY = image.height / backdrop.desktopHeight
    val srcLeft = (pos.x * scaleX).toInt().coerceIn(0, image.width - 1)
    val srcTop = (pos.y * scaleY).toInt().coerceIn(0, image.height - 1)
    val srcRight = ((pos.x + area.width) * scaleX).toInt().coerceIn(srcLeft + 1, image.width)
    val srcBottom = ((pos.y + area.height) * scaleY).toInt().coerceIn(srcTop + 1, image.height)
    drawImage(
        image = image,
        sourceRect = IntRect(srcLeft, srcTop, srcRight, srcBottom),
        destinationRect = IntRect(0, 0, size.width.toInt(), size.height.toInt()),
    )
}
