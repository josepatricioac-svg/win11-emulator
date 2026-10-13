package dev.win11we.shell.ui.xdesktop

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.win11we.shell.runtime.DesktopBrowserLauncher
import dev.win11we.shell.ui.terminal.SharedLog
import dev.win11we.shell.x11.CursorGlyph
import dev.win11we.shell.x11.DrawOp
import dev.win11we.shell.x11.BlendSpec
import dev.win11we.shell.x11.GlyphRunSpec
import dev.win11we.shell.x11.ImeCommitExtractor
import dev.win11we.shell.x11.ShellWindowManager
import dev.win11we.shell.x11.XDisplayList
import dev.win11we.shell.x11.XDocument
import dev.win11we.shell.x11.XInput
import dev.win11we.shell.x11.XKeyboard
import dev.win11we.shell.x11.XRender
import dev.win11we.shell.x11.XServer
import kotlinx.coroutines.isActive

/**
 * Bitmap/Canvas renderer for the X document (D1b, extended in D1c). Every
 * drawable gets its own bitmap; recorded [DrawOp]s are replayed incrementally
 * (a cursor per drawable), then the bitmaps are composited in
 * [XDocument.paintOrder] - root first, children back-to-front - exactly like
 * X's stacking order. D1c adds the vector primitives (points, lines, arcs,
 * polygons), ImageText backgrounds and the software cursor, and (D1d) the
 * RENDER ops: Composite/FillRectangles blends through native Porter-Duff
 * xfermodes and CompositeGlyphs runs through cached ALPHA_8 glyph bitmaps.
 */
class XCanvasRenderer {
    private class Cache(val bitmap: Bitmap, val canvas: Canvas, var drawn: Int)

    private val caches = HashMap<Int, Cache>()
    private val imageCache = HashMap<DrawOp.Image, Bitmap>()
    private val glyphCache = HashMap<XRender.Glyph, Bitmap>()
    private val replaying = HashSet<Int>()
    private val paint = Paint().apply { isAntiAlias = true }
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val matrix = Matrix()
    private val arcRect = RectF()
    private val path = Path()
    private val fontMetrics = Paint.FontMetrics()

    fun draw(dst: Canvas, document: XDocument, lists: XDisplayList) {
        caches.keys.retainAll { id -> document.window(id) != null || lists.sizeOf(id) != null }
        for (window in document.paintOrder()) {
            val cache = bitmapFor(window.id, window.width, window.height, window.backgroundPixel, document, lists)
                ?: continue
            dst.drawBitmap(cache.bitmap, window.x.toFloat(), window.y.toFloat(), null)
        }
    }

    /**
     * Draws the software cursor at a *surface* position (already scaled by the
     * caller), in the shape the document says is in effect. Drawn unscaled so
     * it stays a usable size at any letterbox scale.
     */
    fun drawCursor(dst: Canvas, glyph: Int, x: Float, y: Float) {
        path.reset()
        val strokeShape: Boolean
        when (glyph) {
            CursorGlyph.XTERM -> {
                // I-beam, centred on the hot point.
                path.moveTo(x, y - 9f); path.lineTo(x, y + 9f)
                path.moveTo(x - 4f, y - 9f); path.lineTo(x + 4f, y - 9f)
                path.moveTo(x - 4f, y + 9f); path.lineTo(x + 4f, y + 9f)
                strokeShape = true
            }
            CursorGlyph.CROSSHAIR -> {
                path.moveTo(x - 9f, y); path.lineTo(x + 9f, y)
                path.moveTo(x, y - 9f); path.lineTo(x, y + 9f)
                strokeShape = true
            }
            CursorGlyph.WATCH -> {
                path.addCircle(x + 8f, y + 8f, 7f, Path.Direction.CW)
                strokeShape = true
            }
            else -> {
                // Classic arrow (XC_left_ptr and everything we approximate with it).
                path.moveTo(x, y)
                path.lineTo(x, y + 17f)
                path.lineTo(x + 4.2f, y + 13.2f)
                path.lineTo(x + 7.2f, y + 20.2f)
                path.lineTo(x + 10.4f, y + 18.8f)
                path.lineTo(x + 7.4f, y + 11.9f)
                path.lineTo(x + 12.6f, y + 11.9f)
                path.close()
                strokeShape = false
            }
        }
        if (strokeShape) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3.5f
            paint.color = Color.BLACK
            dst.drawPath(path, paint)
            paint.strokeWidth = 1.4f
            paint.color = Color.WHITE
            dst.drawPath(path, paint)
        } else {
            paint.style = Paint.Style.FILL
            paint.color = Color.BLACK
            dst.drawPath(path, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.6f
            paint.color = Color.WHITE
            dst.drawPath(path, paint)
        }
    }

    private fun bitmapFor(
        id: Int,
        width: Int,
        height: Int,
        backgroundPixel: Int,
        document: XDocument,
        lists: XDisplayList,
    ): Cache? {
        if (width <= 0 || height <= 0) return null
        val ops = lists.ops(id)
        var cache = caches[id]
        if (cache == null || cache.bitmap.width != width || cache.bitmap.height != height) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val isWindow = document.window(id) != null
            canvas.drawColor(
                when {
                    !isWindow -> Color.TRANSPARENT // pixmaps start empty
                    backgroundPixel != 0 -> XDisplayList.opaque(backgroundPixel)
                    else -> DEFAULT_BACKGROUND
                },
            )
            cache = Cache(bitmap, canvas, 0)
            caches[id] = cache
        }
        // The guard stops a CopyArea that references its own drawable from recursing.
        if (cache.drawn < ops.size && replaying.add(id)) {
            try {
                replay(cache.canvas, ops, cache.drawn, ops.size, width, height, document, lists)
            } finally {
                replaying.remove(id)
            }
            cache.drawn = ops.size
        }
        return cache
    }

    private fun replay(
        canvas: Canvas,
        ops: List<DrawOp>,
        from: Int,
        to: Int,
        width: Int,
        height: Int,
        document: XDocument,
        lists: XDisplayList,
    ) {
        for (index in from until to) {
            when (val op = ops[index]) {
                is DrawOp.Fill -> {
                    paint.style = Paint.Style.FILL
                    paint.color = op.argb
                    canvas.drawRect(
                        op.x.toFloat(), op.y.toFloat(),
                        (op.x + op.width).toFloat(), (op.y + op.height).toFloat(),
                        paint,
                    )
                }
                is DrawOp.Clear -> {
                    // ClearArea with width/height 0 means "to the edge of the window".
                    val w = if (op.width == 0) width - op.x else op.width
                    val h = if (op.height == 0) height - op.y else op.height
                    paint.style = Paint.Style.FILL
                    paint.color = Color.WHITE
                    canvas.drawRect(
                        op.x.toFloat(), op.y.toFloat(),
                        (op.x + w).toFloat(), (op.y + h).toFloat(),
                        paint,
                    )
                }
                is DrawOp.Image -> imageFor(op)?.let {
                    canvas.drawBitmap(it, op.x.toFloat(), op.y.toFloat(), null)
                }
                is DrawOp.Text -> {
                    paint.style = Paint.Style.FILL
                    paint.textSize = TEXT_PX
                    paint.typeface = Typeface.MONOSPACE
                    if (op.background != null) {
                        // ImageText semantics: the GC background box first.
                        paint.getFontMetrics(fontMetrics)
                        val textWidth = paint.measureText(op.text)
                        paint.color = op.background
                        canvas.drawRect(
                            op.x.toFloat(), op.y + fontMetrics.ascent,
                            op.x + textWidth, op.y + fontMetrics.descent,
                            paint,
                        )
                    }
                    paint.color = op.argb
                    // X ImageText8 y is the character baseline; so is Canvas.drawText's.
                    canvas.drawText(op.text, op.x.toFloat(), op.y.toFloat(), paint)
                }
                is DrawOp.Copy -> {
                    val src = sourceBitmap(op.srcDrawable, document, lists) ?: continue
                    srcRect.set(op.srcX, op.srcY, op.srcX + op.width, op.srcY + op.height)
                    dstRect.set(
                        op.dstX.toFloat(), op.dstY.toFloat(),
                        (op.dstX + op.width).toFloat(), (op.dstY + op.height).toFloat(),
                    )
                    canvas.drawBitmap(src.bitmap, srcRect, dstRect, null)
                }
                is DrawOp.Points -> if (op.coords.size >= 2) {
                    paint.style = Paint.Style.FILL
                    paint.strokeWidth = 1f
                    paint.color = op.argb
                    canvas.drawPoints(FloatArray(op.coords.size) { op.coords[it].toFloat() }, paint)
                }
                is DrawOp.Line -> if (op.coords.size >= 4) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1f
                    paint.color = op.argb
                    if (op.pairs) {
                        canvas.drawLines(FloatArray(op.coords.size) { op.coords[it].toFloat() }, paint)
                    } else {
                        path.reset()
                        path.moveTo(op.coords[0].toFloat(), op.coords[1].toFloat())
                        for (i in 2 until op.coords.size - 1 step 2) {
                            path.lineTo(op.coords[i].toFloat(), op.coords[i + 1].toFloat())
                        }
                        canvas.drawPath(path, paint)
                    }
                }
                is DrawOp.Arc -> if (op.width > 0 && op.height > 0) {
                    arcRect.set(
                        op.x.toFloat(), op.y.toFloat(),
                        (op.x + op.width).toFloat(), (op.y + op.height).toFloat(),
                    )
                    paint.color = op.argb
                    if (op.filled) {
                        paint.style = Paint.Style.FILL
                    } else {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = 1f
                    }
                    // X angles are 1/64 degrees counter-clockwise from 3 o'clock;
                    // Canvas angles are degrees clockwise - so the sweep negates.
                    canvas.drawArc(arcRect, op.angle1 / 64f, -op.angle2 / 64f, op.filled, paint)
                }
                is DrawOp.Polygon -> if (op.coords.size >= 6) {
                    path.reset()
                    path.moveTo(op.coords[0].toFloat(), op.coords[1].toFloat())
                    for (i in 2 until op.coords.size - 1 step 2) {
                        path.lineTo(op.coords[i].toFloat(), op.coords[i + 1].toFloat())
                    }
                    path.close()
                    paint.style = Paint.Style.FILL
                    paint.color = op.argb
                    canvas.drawPath(path, paint)
                }
                is DrawOp.Blend -> replayBlend(canvas, op.spec, document, lists)
                is DrawOp.GlyphRun -> replayGlyphRun(canvas, op.spec, document, lists)
            }
        }
    }

    /**
     * RENDER Composite/FillRectangles on the platform renderer. The 12 core
     * Porter-Duff ops map onto native xfermodes; Add and Saturate have no
     * PorterDuff mode and fall back to SRC_OVER (a visible-but-minor difference
     * the JVM snapshot rasteriser does NOT share - it computes both exactly).
     */
    private fun xfermodeFor(op: Int): PorterDuffXfermode? = when (op) {
        XRender.OP_CLEAR -> PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        XRender.OP_SRC -> PorterDuffXfermode(PorterDuff.Mode.SRC)
        XRender.OP_DST -> null // dst untouched: the caller skips drawing entirely
        XRender.OP_OVER -> PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
        XRender.OP_OVER_REVERSE -> PorterDuffXfermode(PorterDuff.Mode.DST_OVER)
        XRender.OP_IN -> PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        XRender.OP_IN_REVERSE -> PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        XRender.OP_OUT -> PorterDuffXfermode(PorterDuff.Mode.SRC_OUT)
        XRender.OP_OUT_REVERSE -> PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        XRender.OP_ATOP -> PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        XRender.OP_ATOP_REVERSE -> PorterDuffXfermode(PorterDuff.Mode.DST_ATOP)
        XRender.OP_XOR -> PorterDuffXfermode(PorterDuff.Mode.XOR)
        else -> PorterDuffXfermode(PorterDuff.Mode.SRC_OVER) // ADD/SATURATE: see KDoc
    }

    private fun applyClip(canvas: Canvas, clip: List<IntArray>?): Boolean {
        if (clip == null || clip.isEmpty()) return false
        path.reset()
        clip.forEach { rect ->
            path.addRect(
                rect[0].toFloat(), rect[1].toFloat(),
                (rect[0] + rect[2]).toFloat(), (rect[1] + rect[3]).toFloat(),
                Path.Direction.CW,
            )
        }
        canvas.save()
        canvas.clipPath(path)
        return true
    }

    private fun replayBlend(canvas: Canvas, spec: BlendSpec, document: XDocument, lists: XDisplayList) {
        if (spec.width <= 0 || spec.height <= 0 || spec.op == XRender.OP_DST) return
        val clipped = applyClip(canvas, spec.clip)
        val masked = spec.maskDrawable != 0 || spec.maskColor != null
        if (masked) canvas.saveLayer(null, null)
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.colorFilter = null
        paint.alpha = 255
        if (spec.srcDrawable == 0) {
            // Solid source: one rectangle in the requested operator.
            paint.color = spec.srcColor
            paint.xfermode = xfermodeFor(spec.op)
            canvas.drawRect(
                spec.dstX.toFloat(), spec.dstY.toFloat(),
                (spec.dstX + spec.width).toFloat(), (spec.dstY + spec.height).toFloat(),
                paint,
            )
        } else {
            val source = sourceBitmap(spec.srcDrawable, document, lists)
            if (source != null) {
                paint.xfermode = xfermodeFor(spec.op)
                val tile = when (spec.srcRepeat) {
                    XRender.REPEAT_NORMAL -> Shader.TileMode.REPEAT
                    XRender.REPEAT_PAD -> Shader.TileMode.CLAMP
                    XRender.REPEAT_REFLECT -> Shader.TileMode.MIRROR
                    else -> null
                }
                if (tile == null) {
                    srcRect.set(spec.srcX, spec.srcY, spec.srcX + spec.width, spec.srcY + spec.height)
                    dstRect.set(
                        spec.dstX.toFloat(), spec.dstY.toFloat(),
                        (spec.dstX + spec.width).toFloat(), (spec.dstY + spec.height).toFloat(),
                    )
                    canvas.drawBitmap(source.bitmap, srcRect, dstRect, paint)
                } else {
                    val shader = BitmapShader(source.bitmap, tile, tile)
                    matrix.reset()
                    matrix.setTranslate((spec.dstX - spec.srcX).toFloat(), (spec.dstY - spec.srcY).toFloat())
                    shader.setLocalMatrix(matrix)
                    paint.shader = shader
                    canvas.drawRect(
                        spec.dstX.toFloat(), spec.dstY.toFloat(),
                        (spec.dstX + spec.width).toFloat(), (spec.dstY + spec.height).toFloat(),
                        paint,
                    )
                    paint.shader = null
                }
                paint.xfermode = null
            }
        }
        if (masked) {
            // Keep the drawn source only where the mask's alpha says so.
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            if (spec.maskColor != null) {
                paint.color = (spec.maskColor ushr 24 shl 24) or 0x00FFFFFF // alpha only
                canvas.drawRect(
                    spec.dstX.toFloat(), spec.dstY.toFloat(),
                    (spec.dstX + spec.width).toFloat(), (spec.dstY + spec.height).toFloat(),
                    paint,
                )
            } else {
                val mask = sourceBitmap(spec.maskDrawable, document, lists)
                if (mask != null) {
                    srcRect.set(spec.maskX, spec.maskY, spec.maskX + spec.width, spec.maskY + spec.height)
                    dstRect.set(
                        spec.dstX.toFloat(), spec.dstY.toFloat(),
                        (spec.dstX + spec.width).toFloat(), (spec.dstY + spec.height).toFloat(),
                    )
                    canvas.drawBitmap(mask.bitmap, srcRect, dstRect, paint)
                }
            }
            paint.xfermode = null
            canvas.restore()
        }
        if (clipped) canvas.restore()
    }

    private fun replayGlyphRun(canvas: Canvas, spec: GlyphRunSpec, document: XDocument, lists: XDisplayList) {
        if (spec.placements.isEmpty() || spec.op == XRender.OP_DST) return
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.colorFilter = null
        paint.alpha = 255
        val xfer = xfermodeFor(spec.op)
        if (spec.srcDrawable == 0) {
            // The common case: solid-colour text (Xft, cairo's glyph compositor).
            paint.color = spec.srcColor
            paint.xfermode = xfer
            for (placement in spec.placements) {
                val bitmap = glyphBitmap(placement.glyph) ?: continue
                canvas.drawBitmap(bitmap, placement.dstX.toFloat(), placement.dstY.toFloat(), paint)
            }
            paint.xfermode = null
        } else {
            // Image-source glyphs: draw the source, then keep it under the glyph alphas.
            val source = sourceBitmap(spec.srcDrawable, document, lists) ?: return
            var left = Int.MAX_VALUE; var top = Int.MAX_VALUE
            var right = Int.MIN_VALUE; var bottom = Int.MIN_VALUE
            for (placement in spec.placements) {
                left = minOf(left, placement.dstX); top = minOf(top, placement.dstY)
                right = maxOf(right, placement.dstX + placement.glyph.width)
                bottom = maxOf(bottom, placement.dstY + placement.glyph.height)
            }
            if (right <= left || bottom <= top) return
            canvas.saveLayer(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), null)
            val tile = when (spec.srcRepeat) {
                XRender.REPEAT_NORMAL -> Shader.TileMode.REPEAT
                XRender.REPEAT_PAD -> Shader.TileMode.CLAMP
                XRender.REPEAT_REFLECT -> Shader.TileMode.MIRROR
                else -> null
            }
            if (tile == null) {
                canvas.drawBitmap(
                    source.bitmap,
                    (-spec.srcX).toFloat(),
                    (-spec.srcY).toFloat(),
                    null,
                )
            } else {
                val shader = BitmapShader(source.bitmap, tile, tile)
                matrix.reset()
                matrix.setTranslate((-spec.srcX).toFloat(), (-spec.srcY).toFloat())
                shader.setLocalMatrix(matrix)
                paint.shader = shader
                canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
                paint.shader = null
            }
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            paint.color = Color.WHITE
            for (placement in spec.placements) {
                val bitmap = glyphBitmap(placement.glyph) ?: continue
                canvas.drawBitmap(bitmap, placement.dstX.toFloat(), placement.dstY.toFloat(), paint)
            }
            paint.xfermode = null
            canvas.restore()
        }
    }

    private fun glyphBitmap(glyph: XRender.Glyph): Bitmap? {
        glyphCache[glyph]?.let { return it }
        if (glyph.width <= 0 || glyph.height <= 0) return null
        val bitmap = Bitmap.createBitmap(glyph.width, glyph.height, Bitmap.Config.ALPHA_8)
        val pixels = IntArray(glyph.width * glyph.height) { (glyph.alpha[it].toInt() and 0xFF) shl 24 }
        bitmap.setPixels(pixels, 0, glyph.width, 0, 0, glyph.width, glyph.height)
        if (glyphCache.size > GLYPH_CACHE_LIMIT) glyphCache.clear()
        glyphCache[glyph] = bitmap
        return bitmap
    }

    private fun sourceBitmap(id: Int, document: XDocument, lists: XDisplayList): Cache? {
        val window = document.window(id)
        val width: Int
        val height: Int
        if (window != null) {
            width = window.width
            height = window.height
        } else {
            val size = lists.sizeOf(id) ?: return null
            width = size[0]
            height = size[1]
        }
        return bitmapFor(id, width, height, window?.backgroundPixel ?: 0, document, lists)
    }

    private fun imageFor(op: DrawOp.Image): Bitmap? {
        imageCache[op]?.let { return it }
        if (op.argb.isEmpty() || op.width <= 0 || op.height <= 0) return null
        val bitmap = Bitmap.createBitmap(op.width, op.height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(op.argb, 0, op.width, 0, 0, op.width, op.height)
        if (imageCache.size > IMAGE_CACHE_LIMIT) imageCache.clear()
        imageCache[op] = bitmap
        return bitmap
    }

    private companion object {
        const val DEFAULT_BACKGROUND = 0xFF1C1C1C.toInt()
        const val TEXT_PX = 14f
        const val IMAGE_CACHE_LIMIT = 512
        const val GLYPH_CACHE_LIMIT = 512
    }
}

/**
 * Glue between the shell window and the global [XServer]: starts the server at
 * the surface size the first time it is measured, scales the X screen into the
 * window, translates Compose pointer/key input through [XInput], and (D1c)
 * injects soft-keyboard commits as real X key events.
 */
class XDesktopState {
    val renderer = XCanvasRenderer()
    val input = XInput()
    val focusRequester = FocusRequester()

    // --- D1c: soft keyboard (IME) ------------------------------------------
    val ime = ImeCommitExtractor()
    val imeFocusRequester = FocusRequester()
    var imeValue by mutableStateOf(TextFieldValue(""))
        private set

    /** Set by the composable: focuses the hidden field and shows the soft keyboard. */
    var showIme: (() -> Unit)? = null

    var started by mutableStateOf(false)
        private set
    var startError by mutableStateOf<String?>(null)
        private set

    /** Holds the last-seen server signature; a change invalidates the drawing. */
    var frame by mutableLongStateOf(-1L)
        private set
    var clients by mutableIntStateOf(0)
        private set

    /**
     * Bumped on every pointer event. The drawing lambda reads it, so the
     * software cursor follows the pointer in the draw phase - no recomposition
     * (crash-safety rule 5: values read in draw must be Compose state).
     */
    var cursorTick by mutableLongStateOf(0L)
        private set

    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    fun onSurfaceSize(widthPx: Int, heightPx: Int) {
        if (started || startError != null || widthPx <= 0 || heightPx <= 0) return
        XServer.attachWindowManager(ShellWindowManager.forServer())
        // Per-client protocol reports land in the shell log, which the Terminal and
        // Diagnostics screens both read. This is what makes a silent guest crash
        // diagnosable: the report names every extension the client queried and
        // every request we could not implement.
        XServer.protocolReporter = { line -> SharedLog.info(line) }
        if (XServer.start(widthPx, heightPx)) {
            started = true
        } else {
            startError = XServer.lastError
        }
    }

    /** Called once per frame while visible; bumps [frame] when the server changed. */
    fun onPoll() {
        val signature = XServer.displayList.revision * 31 +
            XServer.contentRevision * 7 +
            XServer.clientCount
        if (signature != frame) {
            frame = signature
            clients = XServer.clientCount
            // A guest connected: the launcher's STARTING phase is over (the
            // loading indicator in the window yields to the client's pixels).
            if (clients > 0) DesktopBrowserLauncher.noteClientConnected()
        }
    }

    fun render(canvas: Canvas, widthPx: Int, heightPx: Int) {
        if (!started) return
        val document = XServer.document
        val screen = document.screen
        if (screen.widthPx <= 0 || screen.heightPx <= 0 || widthPx <= 0 || heightPx <= 0) return
        scale = minOf(widthPx.toFloat() / screen.widthPx, heightPx.toFloat() / screen.heightPx)
        offsetX = (widthPx - screen.widthPx * scale) / 2f
        offsetY = (heightPx - screen.heightPx * scale) / 2f
        canvas.drawColor(LETTERBOX)
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        canvas.clipRect(0, 0, screen.widthPx, screen.heightPx)
        renderer.draw(canvas, document, XServer.displayList)
        canvas.restore()
        // The software cursor is drawn unscaled so it stays usable at any zoom,
        // at the server-side pointer position (which client warps move too).
        if (XServer.clientCount > 0) {
            val px = XServer.pointerX
            val py = XServer.pointerY
            if (px in 0 until screen.widthPx && py in 0 until screen.heightPx) {
                val cursorGlyph = document.effectiveCursorGlyph(px, py)
                // XFIXES HideCursor: CURSOR_HIDDEN (-1) means "draw no sprite".
                if (cursorGlyph >= 0) {
                    renderer.drawCursor(
                        canvas,
                        cursorGlyph,
                        offsetX + px * scale,
                        offsetY + py * scale,
                    )
                }
            }
        }
    }

    /**
     * Raw MotionEvent translation: Android's button constants are exactly what
     * [XInput.buttonFor] expects, and ACTION_SCROLL carries the wheel axis.
     */
    fun onMotionEvent(event: MotionEvent): Boolean {
        if (!started || scale <= 0f) return false
        val x = ((event.x - offsetX) / scale).toInt()
        val y = ((event.y - offsetY) / scale).toInt()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                input.onMove(x, y)
                val pressed = event.buttonState and lastButtons.inv()
                input.onButton(x, y, lowestButton(pressed) ?: MotionEvent.BUTTON_PRIMARY, true)
                lastButtons = event.buttonState
                cursorTick++
                requestImeFocus()
                return true
            }
            MotionEvent.ACTION_BUTTON_PRESS -> {
                input.onMove(x, y)
                val pressed = event.buttonState and lastButtons.inv()
                val button = lowestButton(pressed)
                if (button != null) input.onButton(x, y, button, true)
                lastButtons = event.buttonState
                cursorTick++
                requestImeFocus()
                return button != null
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val released = lastButtons and event.buttonState.inv()
                input.onButton(x, y, lowestButton(released) ?: MotionEvent.BUTTON_PRIMARY, false)
                lastButtons = event.buttonState
                cursorTick++
                return true
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                val released = lastButtons and event.buttonState.inv()
                val button = lowestButton(released)
                if (button != null) input.onButton(x, y, button, false)
                lastButtons = event.buttonState
                cursorTick++
                return button != null
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                input.onMove(x, y)
                cursorTick++
                return true
            }
            MotionEvent.ACTION_SCROLL -> {
                val vscroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (vscroll != 0f) {
                    // Android: positive vscroll = wheel toward up; X: button 4 up, 5 down.
                    val button = if (vscroll > 0f) WHEEL_UP else WHEEL_DOWN
                    input.onButton(x, y, button, true)
                    input.onButton(x, y, button, false)
                }
                return true
            }
        }
        return false
    }

    private var lastButtons = 0

    /** The lowest-numbered pressed button in an Android button-state mask. */
    private fun lowestButton(state: Int): Int? = when {
        state and MotionEvent.BUTTON_PRIMARY != 0 -> MotionEvent.BUTTON_PRIMARY
        state and MotionEvent.BUTTON_SECONDARY != 0 -> MotionEvent.BUTTON_SECONDARY
        state and MotionEvent.BUTTON_TERTIARY != 0 -> MotionEvent.BUTTON_TERTIARY
        state and MotionEvent.BUTTON_BACK != 0 -> MotionEvent.BUTTON_BACK
        state and MotionEvent.BUTTON_FORWARD != 0 -> MotionEvent.BUTTON_FORWARD
        else -> null
    }

    /** Focuses the hidden IME field and asks the system to show the soft keyboard. */
    fun requestImeFocus() {
        runCatching { imeFocusRequester.requestFocus() }
        runCatching { showIme?.invoke() }
    }

    /**
     * A hidden-field value change: inject whatever the IME committed since the
     * last change, then clear the field so it never grows (the extractor's
     * pending buffer is reset in step, so the clear itself injects nothing).
     */
    fun onImeValueChanged(value: TextFieldValue) {
        val committed = ime.onChanged(value.text, value.composition != null)
        if (committed.isNotEmpty()) {
            input.injectText(committed)
        }
        if (value.composition == null && value.text.isNotEmpty()) {
            imeValue = TextFieldValue("")
            ime.onReset()
        } else {
            imeValue = value
        }
    }

    /** IME action keys (Done/Go/Next/Send/Search) become a Return keypress. */
    fun onImeAction() {
        input.injectKeysym(XKeyboard.KEYSYM_RETURN)
        imeValue = TextFieldValue("")
        ime.onReset()
    }

    /**
     * Preview pass over hardware keys while the hidden IME field holds focus.
     * Printable characters without Ctrl/Meta fall through to the text field
     * (they arrive as committed text and are injected by [onImeValueChanged]);
     * everything else - arrows, function keys, Enter, modifiers, chords - is
     * consumed here and dispatched as real X keys. When the field does *not*
     * have focus this returns false and the classic [onKey] path handles them.
     */
    fun onPreviewKey(event: KeyEvent): Boolean {
        if (!started) return false
        val native = event.nativeKeyEvent
        val printable = native.unicodeChar != 0 &&
            native.keyCode != android.view.KeyEvent.KEYCODE_ENTER
        if (printable && !native.isCtrlPressed && !native.isMetaPressed) return false
        return onKey(event)
    }

    fun onKey(event: KeyEvent): Boolean {
        if (!started) return false
        val native = event.nativeKeyEvent
        val mapped = input.onKey(native.keyCode, native.unicodeChar, event.type == KeyEventType.KeyDown)
        return mapped != null
    }

    private companion object {
        const val LETTERBOX = 0xFF0A0A0A.toInt()
        const val WHEEL_UP = 4
        const val WHEEL_DOWN = 5
    }
}

/**
 * The window body that shows the X screen. The server keeps running when the
 * window closes - guest sessions outlive their viewer, like a real X display.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun XDesktopWindow(modifier: Modifier = Modifier) {
    val state = remember { XDesktopState() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.started) {
        if (!state.started) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { state.onPoll() }
        }
    }
    DisposableEffect(keyboardController, state) {
        state.showIme = { keyboardController?.show() }
        onDispose { state.showIme = null }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black)
            .onSizeChanged { state.onSurfaceSize(it.width, it.height) }
            .drawBehind {
                // Reading frame subscribes this draw to the server signature poll;
                // reading cursorTick re-draws the software cursor on pointer moves
                // (both are Compose state read in the draw phase - no recomposition).
                state.frame.let {
                    state.render(drawContext.canvas.nativeCanvas, size.width.toInt(), size.height.toInt())
                }
                @Suppress("UNUSED_EXPRESSION")
                state.cursorTick
            }
            .pointerInteropFilter { event -> state.onMotionEvent(event) }
            .onPreviewKeyEvent { state.onPreviewKey(it) }
            .focusRequester(state.focusRequester)
            .focusable()
            .onKeyEvent { state.onKey(it) },
    ) {
        // The hidden IME field: a 1dp transparent BasicTextField that owns the
        // input connection. Committed text becomes X key events (XInput.injectText),
        // so any guest app that takes keyboard focus can be typed into.
        BasicTextField(
            value = state.imeValue,
            onValueChange = state::onImeValueChanged,
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(1.dp)
                .alpha(0f)
                .focusRequester(state.imeFocusRequester),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Default,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(
                onDone = { state.onImeAction() },
                onGo = { state.onImeAction() },
                onNext = { state.onImeAction() },
                onSearch = { state.onImeAction() },
                onSend = { state.onImeAction() },
            ),
        )
        if (!state.started) {
            Text(
                text = state.startError?.let { "X server failed to start: $it" }
                    ?: "Starting X server on ${XServer.DISPLAY}…",
                color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
        } else if (state.clients == 0) {
            // Goal 2 step 2: a VISIBLE loading state while a desktop browser
            // launches - the first run really takes up to a minute (fontconfig
            // cache), and a silent black window reads as a hang. A failure
            // replaces it with the named refusal instead of going quiet.
            val phase = DesktopBrowserLauncher.launchPhase
            val failure = if (phase == DesktopBrowserLauncher.LaunchPhase.FAILED) {
                DesktopBrowserLauncher.launchMessage
            } else {
                null
            }
            if (phase == DesktopBrowserLauncher.LaunchPhase.STARTING) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = androidx.compose.ui.graphics.Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.align(Alignment.Center).padding(bottom = 72.dp),
                )
            }
            Text(
                text = when {
                    phase == DesktopBrowserLauncher.LaunchPhase.STARTING ->
                        DesktopBrowserLauncher.launchMessage ?: "Starting desktop browser…"
                    failure != null ->
                        "Desktop browser failed to start:\n$failure\n\n" +
                            "Diagnostics and Terminal ▸ log carry the details."
                    else ->
                        "X server running on ${XServer.DISPLAY}\nWaiting for clients - start a container to run Windows apps.\nTap the desktop, then use the soft keyboard to type into guest apps."
                },
                color = when {
                    failure != null -> androidx.compose.ui.graphics.Color(0xFFFF9E9E)
                    phase == DesktopBrowserLauncher.LaunchPhase.STARTING ->
                        androidx.compose.ui.graphics.Color.White
                    else -> androidx.compose.ui.graphics.Color(0xFFB8B8B8)
                },
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
        }
    }
}
