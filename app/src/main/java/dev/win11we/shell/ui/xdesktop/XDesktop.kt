package dev.win11we.shell.ui.xdesktop

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import dev.win11we.shell.x11.DrawOp
import dev.win11we.shell.x11.ShellWindowManager
import dev.win11we.shell.x11.XDisplayList
import dev.win11we.shell.x11.XDocument
import dev.win11we.shell.x11.XInput
import dev.win11we.shell.x11.XServer
import kotlinx.coroutines.isActive

/**
 * Bitmap/Canvas renderer for the X document (D1b). Every drawable gets its own
 * bitmap; recorded [DrawOp]s are replayed incrementally (a cursor per drawable),
 * then the bitmaps are composited in [XDocument.paintOrder] - root first,
 * children back-to-front - exactly like X's stacking order.
 */
class XCanvasRenderer {
    private class Cache(val bitmap: Bitmap, val canvas: Canvas, var drawn: Int)

    private val caches = HashMap<Int, Cache>()
    private val imageCache = HashMap<DrawOp.Image, Bitmap>()
    private val replaying = HashSet<Int>()
    private val paint = Paint().apply { isAntiAlias = true }
    private val srcRect = Rect()
    private val dstRect = RectF()

    fun draw(dst: Canvas, document: XDocument, lists: XDisplayList) {
        caches.keys.retainAll { id -> document.window(id) != null || lists.sizeOf(id) != null }
        for (window in document.paintOrder()) {
            val cache = bitmapFor(window.id, window.width, window.height, window.backgroundPixel, document, lists)
                ?: continue
            dst.drawBitmap(cache.bitmap, window.x.toFloat(), window.y.toFloat(), null)
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
                    paint.color = op.argb
                    paint.textSize = TEXT_PX
                    paint.typeface = Typeface.MONOSPACE
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
            }
        }
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
    }
}

/**
 * Glue between the shell window and the global [XServer]: starts the server at
 * the surface size the first time it is measured, scales the X screen into the
 * window, and translates Compose pointer/key input through [XInput].
 */
class XDesktopState {
    val renderer = XCanvasRenderer()
    val input = XInput()
    val focusRequester = FocusRequester()

    var started by mutableStateOf(false)
        private set
    var startError by mutableStateOf<String?>(null)
        private set

    /** Holds the last-seen server signature; a change invalidates the drawing. */
    var frame by mutableLongStateOf(-1L)
        private set
    var clients by mutableIntStateOf(0)
        private set

    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    fun onSurfaceSize(widthPx: Int, heightPx: Int) {
        if (started || startError != null || widthPx <= 0 || heightPx <= 0) return
        XServer.attachWindowManager(ShellWindowManager.forServer())
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
                runCatching { focusRequester.requestFocus() }
                return true
            }
            MotionEvent.ACTION_BUTTON_PRESS -> {
                input.onMove(x, y)
                val pressed = event.buttonState and lastButtons.inv()
                val button = lowestButton(pressed)
                if (button != null) input.onButton(x, y, button, true)
                lastButtons = event.buttonState
                runCatching { focusRequester.requestFocus() }
                return button != null
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val released = lastButtons and event.buttonState.inv()
                input.onButton(x, y, lowestButton(released) ?: MotionEvent.BUTTON_PRIMARY, false)
                lastButtons = event.buttonState
                return true
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                val released = lastButtons and event.buttonState.inv()
                val button = lowestButton(released)
                if (button != null) input.onButton(x, y, button, false)
                lastButtons = event.buttonState
                return button != null
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                input.onMove(x, y)
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
    LaunchedEffect(state.started) {
        if (!state.started) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { state.onPoll() }
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black)
            .onSizeChanged { state.onSurfaceSize(it.width, it.height) }
            .drawBehind {
                // Reading frame subscribes this draw to the server signature poll.
                state.frame.let {
                    state.render(drawContext.canvas.nativeCanvas, size.width.toInt(), size.height.toInt())
                }
            }
            .pointerInteropFilter { event -> state.onMotionEvent(event) }
            .focusRequester(state.focusRequester)
            .focusable()
            .onKeyEvent { state.onKey(it) },
    ) {
        if (!state.started) {
            Text(
                text = state.startError?.let { "X server failed to start: $it" }
                    ?: "Starting X server on ${XServer.DISPLAY}…",
                color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
        } else if (state.clients == 0) {
            Text(
                text = "X server running on ${XServer.DISPLAY}\nWaiting for clients - start a container to run Windows apps.",
                color = androidx.compose.ui.graphics.Color(0xFFB8B8B8),
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
        }
    }
}
