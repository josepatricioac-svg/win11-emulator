package dev.win11we.shell.x11

/**
 * The keyboard mapping the server advertises.
 *
 * Keycode convention: X11 keycode = evdev + 8 (KEY_ESC 1 -> 9, KEY_A 30 -> 38,
 * KEY_M 50 -> 58, KEY_ENTER 28 -> 36), exactly like a real X.org server with the
 * evdev driver, so clients with baked-in expectations (Wine's default tables,
 * xmodmap-style data) see a familiar map. Latin-1 keysyms equal their code point.
 *
 * Until D1c this class derived letter keycodes as `androidKeyCode + 9`, which put
 * the letters at a-z = 38..63 alphabetically. That block collided with the
 * server's own modifier keycodes: 'm' landed on keycode 50 = Shift_L (hardware
 * 'm' typed Shift!) and punctuation fell back to `unicodeChar + 8`, which sent
 * hardware '-' as keycode 53 = 'p'. The map is now an explicit Android->evdev
 * table, and every printable ASCII character has a keycode and a shifted keysym.
 */
class XKeyboard(
    val minKeycode: Int = 8,
    val maxKeycode: Int = 255,
    val keysymsPerKeycode: Int = 2,
) {
    /** One physical key action needed to produce a character (used for IME injection). */
    data class CharKeyStep(val keycode: Int, val keysym: Int, val needsShift: Boolean)

    /** Keysym for (keycode, slot). Slot 0 is the unshifted symbol; slot 1 is shifted. */
    fun keysymFor(keycode: Int, slot: Int): Int = when {
        keycode < minKeycode || keycode > maxKeycode -> 0
        slot == 1 -> SHIFTED[keycode] ?: 0
        slot == 0 -> KEYSYMS[keycode] ?: dynamicLatin1(keycode)
        else -> 0
    }

    /**
     * Keycodes 168..247 are the dynamic Latin-1 range: keycode = code point + 8
     * for 0xA0..0xF7. This is how accented characters that have no physical key
     * (é, ñ, ü ...) are injected; GetKeyboardMapping reports them consistently.
     */
    private fun dynamicLatin1(keycode: Int): Int =
        if (keycode in LATIN1_KEYCODE_MIN..LATIN1_KEYCODE_MAX) keycode - 8 else 0

    fun keysyms(keycode: Int): List<Int> = List(keysymsPerKeycode) { keysymFor(keycode, it) }

    /** The whole map, as `GetKeyboardMapping` returns it. */
    fun mapping(firstKeycode: Int = minKeycode, count: Int = maxKeycode - minKeycode + 1): List<Int> =
        (0 until count).flatMap { index -> keysyms(firstKeycode + index) }

    /** Android keycode + produced character -> (X keycode, keysym), or null when unmappable. */
    fun xKeycodeFor(androidKeyCode: Int, unicodeChar: Int): Pair<Int, Int>? {
        ANDROID_SPECIAL[androidKeyCode]?.let { (keycode, keysym) -> return keycode to keysym }
        ANDROID_LETTERS[androidKeyCode]?.let { keycode ->
            val keysym = unicodeChar.takeIf { it in 0x20..0x7E } ?: KEYSYMS[keycode] ?: 0
            return keycode to keysym
        }
        ANDROID_DIGITS[androidKeyCode]?.let { keycode ->
            val keysym = unicodeChar.takeIf { it in 0x20..0x7E } ?: KEYSYMS[keycode] ?: 0
            return keycode to keysym
        }
        ANDROID_SYMBOLS[androidKeyCode]?.let { keycode ->
            // A shifted symbol ('!' from Shift+1) keeps the *base* keycode: the
            // client recomputes the keysym from the keycode plus the Shift bit.
            val keysym = unicodeChar.takeIf { it in 0x20..0x7E } ?: KEYSYMS[keycode] ?: 0
            return keycode to keysym
        }
        stepsForCodePoint(unicodeChar)?.let { return it.keycode to it.keysym }
        return null
    }

    /** The keysteps (base key + optional Shift) that produce [codePoint], or null. */
    fun stepsForCodePoint(codePoint: Int): CharKeyStep? {
        ASCII_KEYS[codePoint]?.let { (keycode, shifted) ->
            return CharKeyStep(keycode, codePoint, shifted)
        }
        if (codePoint in 0xA0..0xF7) return CharKeyStep(codePoint + 8, codePoint, false)
        return null
    }

    /** Like [stepsForCodePoint] but keyed by Char, for the IME text-injection path. */
    fun stepsForChar(c: Char): CharKeyStep? = stepsForCodePoint(c.code)

    /**
     * Reverse lookup for [injectKeysym]-style special keys: the keycode whose
     * unshifted slot holds [keysym], or null when the map does not carry it.
     */
    fun keycodeForKeysym(keysym: Int): Int? {
        KEYCODE_BY_KEYSYM[keysym]?.let { return it }
        if (keysym in 0xA0..0xF7) return keysym + 8 // dynamic Latin-1 range
        return null
    }

    companion object {
        /** Dynamic Latin-1 keycode window: 0xA0+8 .. 0xF7+8 (0xF8+ would exceed maxKeycode). */
        const val LATIN1_KEYCODE_MIN = 0xA0 + 8
        const val LATIN1_KEYCODE_MAX = 0xF7 + 8

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
        const val KEYSYM_CAPS_LOCK = 0xFFE5
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
        private const val AKEY_ALT_RIGHT = 58
        private const val AKEY_SHIFT_LEFT = 59
        private const val AKEY_SHIFT_RIGHT = 60
        private const val AKEY_TAB = 61
        private const val AKEY_SPACE = 62
        private const val AKEY_ENTER = 66
        private const val AKEY_BACKSPACE = 67
        private const val AKEY_GRAVE = 68
        private const val AKEY_MINUS = 69
        private const val AKEY_EQUALS = 70
        private const val AKEY_LEFT_BRACKET = 71
        private const val AKEY_RIGHT_BRACKET = 72
        private const val AKEY_BACKSLASH = 73
        private const val AKEY_SEMICOLON = 74
        private const val AKEY_APOSTROPHE = 75
        private const val AKEY_SLASH = 76
        private const val AKEY_COMMA = 55
        private const val AKEY_PERIOD = 56
        private const val AKEY_CAPS_LOCK = 115
        private const val AKEY_DPAD_UP = 19
        private const val AKEY_DPAD_DOWN = 20
        private const val AKEY_DPAD_LEFT = 21
        private const val AKEY_DPAD_RIGHT = 22
        private const val AKEY_PAGE_UP = 92
        private const val AKEY_PAGE_DOWN = 93
        private const val AKEY_ESCAPE = 111
        private const val AKEY_FORWARD_DEL = 112
        private const val AKEY_CTRL_LEFT = 113
        private const val AKEY_CTRL_RIGHT = 114
        private const val AKEY_META_LEFT = 117
        private const val AKEY_META_RIGHT = 118
        private const val AKEY_MOVE_HOME = 122
        private const val AKEY_MOVE_END = 123
        private const val AKEY_INSERT = 124

        /** Physical keycodes (evdev+8), like a real X.org server advertises. */
        const val XKEY_ESC = 9
        const val XKEY_1 = 10
        const val XKEY_0 = 19
        const val XKEY_MINUS = 20
        const val XKEY_EQUALS = 21
        const val XKEY_BACKSPACE = 22
        const val XKEY_TAB = 23
        const val XKEY_Q = 24
        const val XKEY_P = 33
        const val XKEY_LEFTBRACE = 34
        const val XKEY_RIGHTBRACE = 35
        const val XKEY_ENTER = 36
        const val XKEY_CONTROL_L = 37
        const val XKEY_A = 38
        const val XKEY_L = 46
        const val XKEY_SEMICOLON = 47
        const val XKEY_APOSTROPHE = 48
        const val XKEY_GRAVE = 49
        const val XKEY_SHIFT_L = 50
        const val XKEY_BACKSLASH = 51
        const val XKEY_Z = 52
        const val XKEY_M = 58
        const val XKEY_COMMA = 59
        const val XKEY_DOT = 60
        const val XKEY_SLASH = 61
        const val XKEY_SHIFT_R = 62
        const val XKEY_ALT_L = 64
        const val XKEY_SPACE = 65
        const val XKEY_CAPS_LOCK = 66
        const val XKEY_F1 = 67
        const val XKEY_F12 = 78
        const val XKEY_CONTROL_R = 105
        const val XKEY_ALT_R = 108
        const val XKEY_HOME = 110
        const val XKEY_UP = 111
        const val XKEY_PAGE_UP = 112
        const val XKEY_LEFT = 113
        const val XKEY_RIGHT = 114
        const val XKEY_END = 115
        const val XKEY_DOWN = 116
        const val XKEY_PAGE_DOWN = 117
        const val XKEY_INSERT = 118
        const val XKEY_DELETE = 119
        const val XKEY_SUPER_L = 133
        const val XKEY_SUPER_R = 134

        /** Android letter (A..Z = 29..54, alphabetical) -> X keycode (QWERTY order). */
        private val LETTER_KEYCODES: Map<Char, Int> = buildMap {
            // The three letter rows at their physical evdev+8 keycodes, exactly
            // like a real X.org evdev map (rows are QWERTY order, not alphabet).
            "qwertyuiop".forEachIndexed { index, c -> put(c, XKEY_Q + index) } // 24..33
            "asdfghjkl".forEachIndexed { index, c -> put(c, XKEY_A + index) } // 38..46
            "zxcvbnm".forEachIndexed { index, c -> put(c, XKEY_Z + index) } // 52..58
        }

        private val ANDROID_LETTERS: Map<Int, Int> =
            LETTER_KEYCODES.entries.associate { (c, xkey) -> (ANDROID_LETTER_A + (c - 'a')) to xkey }

        /** Android digits (AKEY_0=7, AKEY_1..9=8..16) -> X keycodes 19, 10..18. */
        private val ANDROID_DIGITS: Map<Int, Int> =
            mapOf(ANDROID_DIGIT_0 to XKEY_0) + (1..9).associate { (ANDROID_DIGIT_0 + it) to (XKEY_1 + it - 1) }

        /** Android punctuation keys -> X keycodes. (Phone-only keys like STAR have no base key.) */
        private val ANDROID_SYMBOLS: Map<Int, Int> = mapOf(
            AKEY_GRAVE to XKEY_GRAVE,
            AKEY_MINUS to XKEY_MINUS,
            AKEY_EQUALS to XKEY_EQUALS,
            AKEY_LEFT_BRACKET to XKEY_LEFTBRACE,
            AKEY_RIGHT_BRACKET to XKEY_RIGHTBRACE,
            AKEY_BACKSLASH to XKEY_BACKSLASH,
            AKEY_SEMICOLON to XKEY_SEMICOLON,
            AKEY_APOSTROPHE to XKEY_APOSTROPHE,
            AKEY_SLASH to XKEY_SLASH,
            AKEY_COMMA to XKEY_COMMA,
            AKEY_PERIOD to XKEY_DOT,
            AKEY_SPACE to XKEY_SPACE,
        )

        /** Android keycode -> (X keycode, keysym) for keys outside the alphanumeric block. */
        private val ANDROID_SPECIAL: Map<Int, Pair<Int, Int>> = mapOf(
            AKEY_BACKSPACE to (XKEY_BACKSPACE to KEYSYM_BACKSPACE),
            AKEY_TAB to (XKEY_TAB to KEYSYM_TAB),
            AKEY_ENTER to (XKEY_ENTER to KEYSYM_RETURN),
            AKEY_ESCAPE to (XKEY_ESC to KEYSYM_ESCAPE),
            AKEY_FORWARD_DEL to (XKEY_DELETE to KEYSYM_DELETE),
            AKEY_MOVE_HOME to (XKEY_HOME to KEYSYM_HOME),
            AKEY_MOVE_END to (XKEY_END to KEYSYM_END),
            AKEY_PAGE_UP to (XKEY_PAGE_UP to KEYSYM_PAGE_UP),
            AKEY_PAGE_DOWN to (XKEY_PAGE_DOWN to KEYSYM_PAGE_DOWN),
            AKEY_INSERT to (XKEY_INSERT to KEYSYM_INSERT),
            AKEY_DPAD_LEFT to (XKEY_LEFT to KEYSYM_LEFT),
            AKEY_DPAD_RIGHT to (XKEY_RIGHT to KEYSYM_RIGHT),
            AKEY_DPAD_UP to (XKEY_UP to KEYSYM_UP),
            AKEY_DPAD_DOWN to (XKEY_DOWN to KEYSYM_DOWN),
            AKEY_SHIFT_LEFT to (XKEY_SHIFT_L to KEYSYM_SHIFT_L),
            AKEY_SHIFT_RIGHT to (XKEY_SHIFT_R to KEYSYM_SHIFT_R),
            AKEY_CTRL_LEFT to (XKEY_CONTROL_L to KEYSYM_CONTROL_L),
            AKEY_CTRL_RIGHT to (XKEY_CONTROL_R to KEYSYM_CONTROL_R),
            AKEY_ALT_LEFT to (XKEY_ALT_L to KEYSYM_ALT_L),
            AKEY_ALT_RIGHT to (XKEY_ALT_R to KEYSYM_ALT_R),
            AKEY_META_LEFT to (XKEY_SUPER_L to KEYSYM_SUPER_L),
            AKEY_META_RIGHT to (XKEY_SUPER_R to KEYSYM_SUPER_R),
            AKEY_CAPS_LOCK to (XKEY_CAPS_LOCK to KEYSYM_CAPS_LOCK),
        ) + (0..11).associate { index -> (ANDROID_F1 + index) to (XKEY_F1 + index to KEYSYM_F1 + index) }

        /**
         * The US-layout ASCII table: code point -> (keycode, needs Shift). Every
         * printable ASCII character is reachable, which is what IME text
         * injection and hardware keyboards both need.
         */
        private val ASCII_KEYS: Map<Int, Pair<Int, Boolean>> = buildMap {
            put(' '.code, XKEY_SPACE to false)
            for (index in 1..9) put('0'.code + index, (XKEY_1 + index - 1) to false)
            put('0'.code, XKEY_0 to false)
            // letters: both cases share the base keycode, upper needs Shift
            LETTER_KEYCODES.forEach { (c, xkey) ->
                put(c.code, xkey to false)
                put(c.uppercaseChar().code, xkey to true)
            }
            // US-layout punctuation, unshifted and shifted
            put('-'.code, XKEY_MINUS to false); put('_'.code, XKEY_MINUS to true)
            put('='.code, XKEY_EQUALS to false); put('+'.code, XKEY_EQUALS to true)
            put('['.code, XKEY_LEFTBRACE to false); put('{'.code, XKEY_LEFTBRACE to true)
            put(']'.code, XKEY_RIGHTBRACE to false); put('}'.code, XKEY_RIGHTBRACE to true)
            put(';'.code, XKEY_SEMICOLON to false); put(':'.code, XKEY_SEMICOLON to true)
            put('\''.code, XKEY_APOSTROPHE to false); put('"'.code, XKEY_APOSTROPHE to true)
            put('`'.code, XKEY_GRAVE to false); put('~'.code, XKEY_GRAVE to true)
            put('\\'.code, XKEY_BACKSLASH to false); put('|'.code, XKEY_BACKSLASH to true)
            put(','.code, XKEY_COMMA to false); put('<'.code, XKEY_COMMA to true)
            put('.'.code, XKEY_DOT to false); put('>'.code, XKEY_DOT to true)
            put('/'.code, XKEY_SLASH to false); put('?'.code, XKEY_SLASH to true)
            // shifted digit-row symbols
            put('!'.code, XKEY_1 to true); put('@'.code, 11 to true); put('#'.code, 12 to true)
            put('$'.code, 13 to true); put('%'.code, 14 to true); put('^'.code, 15 to true)
            put('&'.code, 16 to true); put('*'.code, 17 to true); put('('.code, 18 to true)
            put(')'.code, XKEY_0 to true)
        }

        /** X keycode -> unshifted keysym. */
        private val KEYSYMS: Map<Int, Int> = buildMap {
            ASCII_KEYS.forEach { (codePoint, entry) ->
                val (keycode, shifted) = entry
                if (!shifted && codePoint != ' '.code) put(keycode, codePoint)
            }
            put(XKEY_SPACE, 0x20)
            ANDROID_SPECIAL.values.forEach { (keycode, keysym) -> put(keycode, keysym) }
        }

        /** X keycode -> shifted keysym (slot 1 of GetKeyboardMapping). */
        private val SHIFTED: Map<Int, Int> = buildMap {
            ASCII_KEYS.forEach { (codePoint, entry) ->
                val (keycode, shifted) = entry
                if (shifted) put(keycode, codePoint)
            }
            // Modifier keys repeat their keysym in slot 1, like the evdev X map.
            put(XKEY_SHIFT_L, KEYSYM_SHIFT_L)
            put(XKEY_SHIFT_R, KEYSYM_SHIFT_R)
            put(XKEY_CONTROL_L, KEYSYM_CONTROL_L)
            put(XKEY_CONTROL_R, KEYSYM_CONTROL_R)
            put(XKEY_ALT_L, KEYSYM_ALT_L)
            put(XKEY_ALT_R, KEYSYM_ALT_R)
            put(XKEY_CAPS_LOCK, KEYSYM_CAPS_LOCK)
            put(XKEY_SUPER_L, KEYSYM_SUPER_L)
            put(XKEY_SUPER_R, KEYSYM_SUPER_R)
        }

        /** Reverse of [KEYSYMS] (+ dynamic Latin-1), for keysym-driven injection. */
        private val KEYCODE_BY_KEYSYM: Map<Int, Int> = buildMap {
            KEYSYMS.forEach { (keycode, keysym) -> if (keysym != 0) put(keysym, keycode) }
        }

        fun default() = XKeyboard()
    }
}
