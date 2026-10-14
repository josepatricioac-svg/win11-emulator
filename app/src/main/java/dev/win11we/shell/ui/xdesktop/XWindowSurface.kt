package dev.win11we.shell.ui.xdesktop

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.win11we.shell.window.GuestWindowBridge
import dev.win11we.shell.x11.ImeCommitExtractor
import dev.win11we.shell.x11.XInput
import dev.win11we.shell.x11.XKeyboard
import dev.win11we.shell.x11.XServer
import kotlinx.coroutines.isActive

/**
 * Per-window state for a shell window that hosts ONE guest X window (D2).
 * Mirrors [XDesktopState] with two differences: coordinates are translated by
 * the window's desktop-relative ORIGIN instead of a letterbox scale (the X
 * screen and the shell desktop are pixel-aligned 1:1), and every layout change
 * is pushed back into the X server through [GuestWindowBridge.surfaceGeometry]
 * so the guest window's absolute X rect always equals its shell window's
 * on-screen rect - the invariant that makes override-redirect popups (menus,
 * dropdowns) land on the right pixels when the desktop layer draws them.
 */
class XWindowState(val xid: Int) {
    val renderer = XCanvasRenderer()
    val input = XInput()
    val focusRequester = FocusRequester()

    // Soft keyboard (IME): the same hidden-field trick as the desktop layer -
    // commits become real X KeyPress/KeyRelease sequences. Per-window fields
    // mean each guest window has its own keyboard focus, like a real WM.
    val ime = ImeCommitExtractor()
    val imeFocusRequester = FocusRequester()
    var imeValue by mutableStateOf(TextFieldValue(""))
        private set
    var showIme: (() -> Unit)? = null

    /** Read in the draw phase; bumped whenever the server state changed. */
    var frame by mutableLongStateOf(-1L)
        private set

    /** Bumped on pointer events so the software cursor redraws without recomposition. */
    var cursorTick by mutableLongStateOf(0L)
        private set

    /** Desktop-relative origin of this window's content, in px (input mapping + cursor). */
    @Volatile
    var originX: Int = 0
        private set

    @Volatile
    var originY: Int = 0
        private set

    private var lastRect: IntArray? = null

    /** Wired by the composable: reports layout into the bridge (map/move/resize). */
    var onGeometry: ((xPx: Int, yPx: Int, wPx: Int, hPx: Int) -> Unit)? = null

    /**
     * Called from `onGloballyPositioned` with the content rect in ROOT px and
     * the desktop origin; dedupes so dragging does not spam ConfigureNotify
     * beyond what actually changed.
     */
    fun onLayout(rootX: Float, rootY: Float, desktopX: Float, desktopY: Float, wPx: Int, hPx: Int) {
        if (wPx <= 0 || hPx <= 0) return
        val x = (rootX - desktopX).toInt()
        val y = (rootY - desktopY).toInt()
        originX = x
        originY = y
        val prev = lastRect
        if (prev != null && prev[0] == x && prev[1] == y && prev[2] == wPx && prev[3] == hPx) return
        lastRect = intArrayOf(x, y, wPx, hPx)
        onGeometry?.invoke(x, y, wPx, hPx)
    }

    /** One poll per frame while composed; cheap signature check, redraw on change. */
    fun onPoll() {
        val signature = XServer.displayList.revision * 31 +
            XServer.contentRevision * 7 +
            XServer.document.structureRevision * 3 +
            XServer.clientCount
        if (signature != frame) frame = signature
    }

    fun render(canvas: android.graphics.Canvas) {
        val document = XServer.document
        renderer.drawWindow(canvas, document, XServer.displayList, xid)
        // Software cursor, local coordinates, only while the server-side
        // pointer is inside this window (mirrors what a real WM shows).
        val window = document.window(xid) ?: return
        if (XServer.clientCount > 0) {
            val o = document.absoluteOrigin(window)
            val px = XServer.pointerX
            val py = XServer.pointerY
            if (px in o[0] until o[0] + window.width && py in o[1] until o[1] + window.height) {
                val glyph = document.effectiveCursorGlyph(px, py)
                if (glyph >= 0) {
                    renderer.drawCursor(canvas, glyph, (px - o[0]).toFloat(), (py - o[1]).toFloat())
                }
            }
        }
    }

    /** Raw MotionEvent translation at 1:1 (window-local px + origin = X screen px). */
    fun onMotionEvent(event: MotionEvent): Boolean {
        val x = event.x.toInt() + originX
        val y = event.y.toInt() + originY
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

    private fun lowestButton(state: Int): Int? = when {
        state and MotionEvent.BUTTON_PRIMARY != 0 -> MotionEvent.BUTTON_PRIMARY
        state and MotionEvent.BUTTON_SECONDARY != 0 -> MotionEvent.BUTTON_SECONDARY
        state and MotionEvent.BUTTON_TERTIARY != 0 -> MotionEvent.BUTTON_TERTIARY
        state and MotionEvent.BUTTON_BACK != 0 -> MotionEvent.BUTTON_BACK
        state and MotionEvent.BUTTON_FORWARD != 0 -> MotionEvent.BUTTON_FORWARD
        else -> null
    }

    fun requestImeFocus() {
        runCatching { imeFocusRequester.requestFocus() }
        runCatching { showIme?.invoke() }
    }

    fun onImeValueChanged(value: TextFieldValue) {
        val committed = ime.onChanged(value.text, value.composition != null)
        if (committed.isNotEmpty()) input.injectText(committed)
        if (value.composition == null && value.text.isNotEmpty()) {
            imeValue = TextFieldValue("")
            ime.onReset()
        } else {
            imeValue = value
        }
    }

    fun onImeAction() {
        input.injectKeysym(XKeyboard.KEYSYM_RETURN)
        imeValue = TextFieldValue("")
        ime.onReset()
    }

    fun onPreviewKey(event: KeyEvent): Boolean {
        val native = event.nativeKeyEvent
        val printable = native.unicodeChar != 0 &&
            native.keyCode != android.view.KeyEvent.KEYCODE_ENTER
        if (printable && !native.isCtrlPressed && !native.isMetaPressed) return false
        return onKey(event)
    }

    fun onKey(event: KeyEvent): Boolean {
        val native = event.nativeKeyEvent
        val mapped = input.onKey(native.keyCode, native.unicodeChar, event.type == KeyEventType.KeyDown)
        return mapped != null
    }

    private companion object {
        const val WHEEL_UP = 4
        const val WHEEL_DOWN = 5
    }
}

/**
 * The body of a shell window that hosts one guest X window (D2): renders that
 * window's subtree 1:1, routes touch/mouse/keyboard/IME into the server at the
 * window's absolute coordinates, and pushes every layout change back through
 * the [GuestWindowBridge] so the X geometry always equals the shell geometry.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun XWindowSurface(
    xid: Int,
    bridge: GuestWindowBridge?,
    modifier: Modifier = Modifier,
    /** Tap inside the guest window: the shell focuses this window. */
    onUserInteraction: () -> Unit = {},
) {
    val state = remember(xid) { XWindowState(xid) }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(xid) {
        while (isActive) {
            withFrameNanos { state.onPoll() }
        }
    }
    DisposableEffect(keyboardController, state) {
        state.showIme = { keyboardController?.show() }
        onDispose { state.showIme = null }
    }
    LaunchedEffect(bridge, state) {
        state.onGeometry = { x, y, w, h -> bridge?.surfaceGeometry(xid, x, y, w, h) }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onGloballyPositioned { coords ->
                val root = coords.positionInRoot()
                state.onLayout(
                    root.x, root.y,
                    bridge?.desktopOriginX ?: 0f, bridge?.desktopOriginY ?: 0f,
                    coords.size.width, coords.size.height,
                )
            }
            .drawBehind {
                // frame/cursorTick are Compose state read in the draw phase:
                // the poll loop drives redraws without recomposition.
                state.frame.let { state.render(drawContext.canvas.nativeCanvas) }
                @Suppress("UNUSED_EXPRESSION")
                state.cursorTick
            }
            .pointerInteropFilter { event ->
                onUserInteraction()
                state.onMotionEvent(event)
            }
            .onPreviewKeyEvent { state.onPreviewKey(it) }
            .focusRequester(state.focusRequester)
            .focusable()
            .onKeyEvent { state.onKey(it) },
    ) {
        // The hidden IME field: committed text becomes X key events, so any
        // guest app that takes keyboard focus can be typed into.
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
    }
}
