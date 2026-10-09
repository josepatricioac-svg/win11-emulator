package dev.win11we.shell.x11

/**
 * Translates device input into X events and keeps the modifier/button state that
 * X clients expect in every event. Android-free, so it is unit-tested directly.
 *
 * Button numbers follow X: 1 = left/primary, 2 = middle, 3 = right, 4/5 = wheel.
 */
/** Where translated input goes. Injectable so the translator is testable. */
interface XDispatcher {
    fun pointerMove(x: Int, y: Int)
    fun button(x: Int, y: Int, button: Int, pressed: Boolean, mask: Int)
    fun key(keycode: Int, keysym: Int, pressed: Boolean, mask: Int)
}

/** The real target: the running [XServer]. */
object ServerDispatcher : XDispatcher {
    override fun pointerMove(x: Int, y: Int) = XServer.dispatchPointerMove(x, y)
    override fun button(x: Int, y: Int, button: Int, pressed: Boolean, mask: Int) =
        XServer.dispatchButton(x, y, button, pressed, mask)

    override fun key(keycode: Int, keysym: Int, pressed: Boolean, mask: Int) =
        XServer.dispatchKey(keycode, keysym, pressed, mask)
}

/** Records what was dispatched; used by tests. */
class RecordingDispatcher : XDispatcher {
    val moves = mutableListOf<String>()
    val buttons = mutableListOf<String>()
    val keys = mutableListOf<String>()
    override fun pointerMove(x: Int, y: Int) { moves.add("$x,$y") }
    override fun button(x: Int, y: Int, button: Int, pressed: Boolean, mask: Int) {
        buttons.add("b$button ${if (pressed) "down" else "up"} @$x,$y mask=${mask.toString(2)}")
    }

    override fun key(keycode: Int, keysym: Int, pressed: Boolean, mask: Int) {
        keys.add("kc$keycode ks${keysym.toString(16)} ${if (pressed) "down" else "up"} mask=${mask.toString(2)}")
    }
}

class XInput(
    private val keyboard: XKeyboard = XKeyboard.default(),
    private val dispatcher: XDispatcher = ServerDispatcher,
) {

    var modifiers: Int = 0
        private set
    var buttons: Int = 0
        private set
    var x: Int = 0
        private set
    var y: Int = 0
        private set

    fun state(): Int = modifiers or buttons

    fun onMove(x: Int, y: Int) {
        this.x = x
        this.y = y
        dispatcher.pointerMove(x, y)
    }

    /** @return the X button number, or null for pointers we do not map. */
    fun onButton(x: Int, y: Int, androidButton: Int, pressed: Boolean): Int? {
        val button = buttonFor(androidButton) ?: return null
        val bit = 1 shl (7 + button) // Button1Motion is bit 8
        buttons = if (pressed) buttons or bit else buttons and bit.inv()
        this.x = x
        this.y = y
        dispatcher.button(x, y, button, pressed, state())
        return button
    }

    fun buttonFor(androidButton: Int): Int? = when (androidButton) {
        BUTTON_PRIMARY -> 1
        BUTTON_TERTIARY -> 2
        BUTTON_SECONDARY -> 3
        BUTTON_BACK -> 8
        BUTTON_FORWARD -> 9
        else -> null
    }

    /**
     * @param unicodeChar the character Android produced, 0 when the key is not printable
     * @return the X keycode delivered, or null when the key has no X equivalent
     */
    fun onKey(androidKeyCode: Int, unicodeChar: Int, pressed: Boolean): Int? {
        val mapped = keyboard.xKeycodeFor(androidKeyCode, unicodeChar) ?: return null
        val (keycode, keysym) = mapped
        val bit = modifierBitFor(keysym)
        if (bit != 0) modifiers = if (pressed) modifiers or bit else modifiers and bit.inv()
        dispatcher.key(keycode, keysym, pressed, state())
        return keycode
    }

    fun modifierBitFor(keysym: Int): Int = modifierBit(keysym)

    fun reset() {
        modifiers = 0
        buttons = 0
    }

    /**
     * Injects committed IME text as real X key events (D1c): every character is
     * looked up in the keyboard map ([XKeyboard.stepsForChar]), Shift is pressed
     * around the characters that need it, and each key goes down and up through
     * the same dispatcher hardware keys use - so clients see ordinary KeyPress/
     * KeyRelease with consistent modifier masks, no XTest or XIM involved.
     *
     * @return the number of characters injected; the rest are outside the
     * server's Latin-1 reach (documented limit: CJK etc. need XIM/XKB, P1+).
     */
    fun injectText(text: String): Int {
        var injected = 0
        for (c in text) {
            val step = keyboard.stepsForChar(c) ?: continue
            val shiftHere = step.needsShift && modifiers and X11.MASK_SHIFT == 0
            if (shiftHere) {
                modifiers = modifiers or X11.MASK_SHIFT
                dispatcher.key(SHIFT_KEYCODE, XKeyboard.KEYSYM_SHIFT_L, true, state())
            }
            dispatcher.key(step.keycode, step.keysym, true, state())
            dispatcher.key(step.keycode, step.keysym, false, state())
            if (shiftHere) {
                modifiers = modifiers and X11.MASK_SHIFT.inv()
                dispatcher.key(SHIFT_KEYCODE, XKeyboard.KEYSYM_SHIFT_L, false, state())
            }
            injected++
        }
        return injected
    }

    /**
     * Injects one special key (Return, BackSpace, arrows ...) by keysym, for
     * IME actions that never produce characters. @return false when unmappable.
     */
    fun injectKeysym(keysym: Int): Boolean {
        val keycode = keyboard.keycodeForKeysym(keysym) ?: return false
        dispatcher.key(keycode, keysym, true, state())
        dispatcher.key(keycode, keysym, false, state())
        return true
    }

    companion object {
        // android.view.MotionEvent button constants, mirrored so this class stays Android-free.
        const val BUTTON_PRIMARY = 1
        const val BUTTON_SECONDARY = 2
        const val BUTTON_TERTIARY = 4
        const val BUTTON_BACK = 8
        const val BUTTON_FORWARD = 16

        /** The physical Left Shift keycode injection presses around shifted characters. */
        const val SHIFT_KEYCODE = XKeyboard.XKEY_SHIFT_L

        fun modifierBit(keysym: Int): Int = when (keysym) {
            XKeyboard.KEYSYM_SHIFT_L, XKeyboard.KEYSYM_SHIFT_R -> X11.MASK_SHIFT
            XKeyboard.KEYSYM_CONTROL_L, XKeyboard.KEYSYM_CONTROL_R -> X11.MASK_CONTROL
            XKeyboard.KEYSYM_ALT_L, XKeyboard.KEYSYM_ALT_R -> X11.MASK_MOD1
            XKeyboard.KEYSYM_SUPER_L, XKeyboard.KEYSYM_SUPER_R -> X11.MASK_MOD4
            XKeyboard.KEYSYM_CAPS_LOCK -> X11.MASK_LOCK
            else -> 0
        }
    }
}
