package dev.win11we.shell.x11

/**
 * The keyboard mapping the server advertises.
 *
 * Keycode convention: X11 keycodes are `evdev + 8` (KEY_ESC 1 -> keycode 9, KEY_A 30 ->
 * keycode 38, KEY_ENTER 28 -> keycode 36). Android's `KeyEvent` codes are mostly
 * `evdev + 1` for the main alphanumeric block, so `xKeycode = androidKeycode + 9` for
 * those, and the rest are mapped explicitly. Latin-1 keysyms equal their code point.
 */
class XKeyboard(
    val minKeycode: Int = 8,
    val maxKeycode: Int = 255,
    val keysymsPerKeycode: Int = 2,
) {
    /** Keysym for (keycode, slot). Slot 0 is the unshifted symbol; slot 1 is shifted. */
    fun keysymFor(keycode: Int, slot: Int): Int = when {
        keycode < minKeycode || keycode > maxKeycode -> 0
        slot == 1 -> SHIFTED[keycode] ?: 0
        slot == 0 -> KEYSYMS[keycode] ?: 0
        else -> 0
    }

    fun keysyms(keycode: Int): List<Int> = List(keysymsPerKeycode) { keysymFor(keycode, it) }

    /** The whole map, as `GetKeyboardMapping` returns it. */
    fun mapping(firstKeycode: Int = minKeycode, count: Int = maxKeycode - minKeycode + 1): List<Int> =
        (0 until count).flatMap { index -> keysyms(firstKeycode + index) }

    /** Android keycode + produced character -> (X keycode, keysym), or null when unmappable. */
    fun xKeycodeFor(androidKeyCode: Int, unicodeChar: Int): Pair<Int, Int>? {
        ANDROID_SPECIAL[androidKeyCode]?.let { (keycode, keysym) -> return keycode to keysym }
        if (androidKeyCode in ANDROID_LETTER_A..ANDROID_LETTER_Z ||
            androidKeyCode in ANDROID_DIGIT_0..ANDROID_DIGIT_9
        ) {
            val keycode = androidKeyCode + KEYCODE_OFFSET
            return keycode to (KEYSYMS[keycode] ?: unicodeChar.takeIf { it != 0 } ?: 0)
        }
        if (unicodeChar in 0x20..0x7E || unicodeChar in 0xA0..0xFF) {
            // Printable but not on the mapped block: fall back to the keysym-plus-8
            // convention, which is what a plain X map does for Latin-1.
            return (unicodeChar + 8) to unicodeChar
        }
        return null
    }

    companion object {
        const val KEYCODE_OFFSET = 9 // Android -> X for the alphanumeric block (evdev+8, android=evdev+1)

        // X keysyms
        const val KEYSYM_BACKSPACE = 0xFF08
        const val KEYSYM_TAB = 0xFF09
        const val KEYSYM_RETURN = 0xFF0D
        const val KEYSYM_ESCAPE = 0xFF1B
        const val KEYSYM_DELETE = 0xFFFF
        const val KEYSYM_HOME = 0xFF50
        const val KEYSYM_LEFT = 0xFF51
        const val KEYSYM_UP = 0xFF52
        const val KEYSYM_RIGHT = 0xFF53
        const val KEYSYM_DOWN = 0xFF54
        const val KEYSYM_PAGE_UP = 0xFF55
        const val KEYSYM_PAGE_DOWN = 0xFF56
        const val KEYSYM_END = 0xFF57
        const val KEYSYM_INSERT = 0xFF63
        const val KEYSYM_F1 = 0xFFBE
        const val KEYSYM_SHIFT_L = 0xFFE1
        const val KEYSYM_SHIFT_R = 0xFFE2
        const val KEYSYM_CONTROL_L = 0xFFE3
        const val KEYSYM_CONTROL_R = 0xFFE4
        const val KEYSYM_ALT_L = 0xFFE9
        const val KEYSYM_ALT_R = 0xFFEA
        const val KEYSYM_SUPER_L = 0xFFEB
        const val KEYSYM_SUPER_R = 0xFFEC

        // Android KeyEvent codes
        const val ANDROID_DIGIT_0 = 7
        const val ANDROID_DIGIT_9 = 16
        const val ANDROID_LETTER_A = 29
        const val ANDROID_LETTER_Z = 54
        const val ANDROID_F1 = 131
        const val ANDROID_F12 = 142
        private const val AKEY_ALT_LEFT = 57
        private const val AKEY_SHIFT_LEFT = 59
        private const val AKEY_TAB = 61
        private const val AKEY_SPACE = 62
        private const val AKEY_ENTER = 66
        private const val AKEY_BACKSPACE = 67
        private const val AKEY_DPAD_UP = 19
        private const val AKEY_DPAD_DOWN = 20
        private const val AKEY_DPAD_LEFT = 21
        private const val AKEY_DPAD_RIGHT = 22
        private const val AKEY_PAGE_UP = 92
        private const val AKEY_PAGE_DOWN = 93
        private const val AKEY_ESCAPE = 111
        private const val AKEY_FORWARD_DEL = 112
        private const val AKEY_CTRL_LEFT = 113
        private const val AKEY_META_LEFT = 117
        private const val AKEY_MOVE_HOME = 122
        private const val AKEY_MOVE_END = 123
        private const val AKEY_INSERT = 124

        /** Android keycode -> (X keycode, keysym) for keys outside the alphanumeric block. */
        private val ANDROID_SPECIAL = mapOf(
            AKEY_BACKSPACE to (22 to KEYSYM_BACKSPACE),
            AKEY_TAB to (23 to KEYSYM_TAB),
            AKEY_ENTER to (36 to KEYSYM_RETURN),
            AKEY_SPACE to (65 to 0x20),
            AKEY_ESCAPE to (9 to KEYSYM_ESCAPE),
            AKEY_FORWARD_DEL to (119 to KEYSYM_DELETE),
            AKEY_MOVE_HOME to (110 to KEYSYM_HOME),
            AKEY_MOVE_END to (115 to KEYSYM_END),
            AKEY_PAGE_UP to (112 to KEYSYM_PAGE_UP),
            AKEY_PAGE_DOWN to (117 to KEYSYM_PAGE_DOWN),
            AKEY_INSERT to (118 to KEYSYM_INSERT),
            AKEY_DPAD_LEFT to (113 to KEYSYM_LEFT),
            AKEY_DPAD_RIGHT to (114 to KEYSYM_RIGHT),
            AKEY_DPAD_UP to (111 to KEYSYM_UP),
            AKEY_DPAD_DOWN to (116 to KEYSYM_DOWN),
            AKEY_SHIFT_LEFT to (50 to KEYSYM_SHIFT_L),
            AKEY_CTRL_LEFT to (37 to KEYSYM_CONTROL_L),
            AKEY_ALT_LEFT to (64 to KEYSYM_ALT_L),
            AKEY_META_LEFT to (133 to KEYSYM_SUPER_L),
        ) + (0..11).associate { index -> (ANDROID_F1 + index) to (67 + index to KEYSYM_F1 + index) }

        /** X keycode -> unshifted keysym, derived from the same convention. */
        private val KEYSYMS: Map<Int, Int> = buildMap {
            // letters: Android A..Z (29..54) -> X 38..63 -> 'a'..'z'
            for (index in 0..25) put(ANDROID_LETTER_A + index + KEYCODE_OFFSET, 'a'.code + index)
            // digits: Android 0..9 (7..16) -> X 16..25; KEY_1..KEY_9 are '1'..'9', KEY_0 is '0'
            for (index in 0..8) put(ANDROID_DIGIT_0 + 1 + index + KEYCODE_OFFSET, '1'.code + index)
            put(ANDROID_DIGIT_0 + KEYCODE_OFFSET, '0'.code)
            // function keys come from ANDROID_SPECIAL (evdev 59..68 -> X 67..78)
            ANDROID_SPECIAL.values.forEach { (keycode, keysym) -> put(keycode, keysym) }
        }

        /** Shifted symbols for the keys where a real X map has one. */
        private val SHIFTED: Map<Int, Int> = buildMap {
            for (index in 0..25) put(ANDROID_LETTER_A + index + KEYCODE_OFFSET, 'A'.code + index)
            put(50, KEYSYM_SHIFT_R)
            put(37, KEYSYM_CONTROL_R)
            put(64, KEYSYM_ALT_R)
            put(133, KEYSYM_SUPER_R)
        }

        fun default() = XKeyboard()
    }
}
