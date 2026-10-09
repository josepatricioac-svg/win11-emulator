package dev.win11we.shell.x11

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D1c soft-keyboard path: the character -> keystep table, XInput.injectText /
 * injectKeysym dispatch sequences, and the hidden-field commit extractor.
 */
class XTextInjectionTest {

    @Test
    fun `every printable ASCII character has a keystep that round trips`() {
        val keyboard = XKeyboard.default()
        for (code in 0x20..0x7E) {
            val step = keyboard.stepsForCodePoint(code)
            assertTrue("'$code' must be injectable", step != null)
            val slot = if (step!!.needsShift) 1 else 0
            assertEquals("'$code' must survive keycode+slot round trip",
                code, keyboard.keysymFor(step.keycode, slot))
        }
    }

    @Test
    fun `latin-1 accented characters use the dynamic keycode range`() {
        val keyboard = XKeyboard.default()
        val e = keyboard.stepsForChar('é')!!
        assertEquals(0xE9 + 8, e.keycode)
        assertEquals(0xE9, e.keysym)
        assertFalse(e.needsShift)
        assertEquals(0xE9, keyboard.keysymFor(e.keycode, 0))
        val n = keyboard.stepsForChar('ñ')!!
        assertEquals(0xF1, keyboard.keysymFor(n.keycode, 0))
        // Beyond Latin-1 (CJK, emoji) has no core-protocol path yet.
        assertNull(keyboard.stepsForChar('漢'))
        assertNull(keyboard.stepsForCodePoint(0x1F600))
    }

    @Test
    fun `injectText presses shift around shifted characters only`() {
        val dispatcher = RecordingDispatcher()
        val input = XInput(dispatcher = dispatcher)
        val injected = input.injectText("Hi!")
        assertEquals(3, injected)
        // H: shift down, key down/up, shift up. i: plain down/up. !: shift again.
        // Keycodes are evdev+8: 'h' = 43, 'i' = 31, '!' = Shift + '1' (10).
        assertEquals(
            listOf(
                "kc50 ksffe1 down mask=1",
                "kc43 ks48 down mask=1",
                "kc43 ks48 up mask=1",
                "kc50 ksffe1 up mask=0",
                "kc31 ks69 down mask=0",
                "kc31 ks69 up mask=0",
                "kc50 ksffe1 down mask=1",
                "kc10 ks21 down mask=1",
                "kc10 ks21 up mask=1",
                "kc50 ksffe1 up mask=0",
            ),
            dispatcher.keys,
        )
        assertEquals("modifiers must end clean", 0, input.modifiers)
    }

    @Test
    fun `injectText skips characters the map cannot express`() {
        val dispatcher = RecordingDispatcher()
        val input = XInput(dispatcher = dispatcher)
        assertEquals(0, input.injectText("漢字"))
        assertTrue(dispatcher.keys.isEmpty())
        assertEquals(2, input.injectText("a漢b")) // 'a' and 'b' still go through
    }

    @Test
    fun `injectKeysym delivers special keys by their keycodes`() {
        val dispatcher = RecordingDispatcher()
        val input = XInput(dispatcher = dispatcher)
        assertTrue(input.injectKeysym(XKeyboard.KEYSYM_RETURN))
        assertTrue(input.injectKeysym(XKeyboard.KEYSYM_BACKSPACE))
        assertTrue(input.injectKeysym(XKeyboard.KEYSYM_LEFT))
        assertFalse(input.injectKeysym(0x123456)) // unknown keysym
        assertEquals(
            listOf(
                "kc36 ksff0d down mask=0", "kc36 ksff0d up mask=0",
                "kc22 ksff08 down mask=0", "kc22 ksff08 up mask=0",
                "kc113 ksff51 down mask=0", "kc113 ksff51 up mask=0",
            ),
            dispatcher.keys,
        )
    }

    @Test
    fun `the keyboard reverse lookup covers every special keysym the shell uses`() {
        val keyboard = XKeyboard.default()
        assertEquals(36, keyboard.keycodeForKeysym(XKeyboard.KEYSYM_RETURN))
        assertEquals(22, keyboard.keycodeForKeysym(XKeyboard.KEYSYM_BACKSPACE))
        assertEquals(23, keyboard.keycodeForKeysym(XKeyboard.KEYSYM_TAB))
        assertEquals(9, keyboard.keycodeForKeysym(XKeyboard.KEYSYM_ESCAPE))
        assertEquals(119, keyboard.keycodeForKeysym(XKeyboard.KEYSYM_DELETE))
        assertEquals(110, keyboard.keycodeForKeysym(XKeyboard.KEYSYM_HOME))
        assertEquals(115, keyboard.keycodeForKeysym(XKeyboard.KEYSYM_END))
        assertEquals(112, keyboard.keycodeForKeysym(XKeyboard.KEYSYM_PAGE_UP))
        assertEquals(117, keyboard.keycodeForKeysym(XKeyboard.KEYSYM_PAGE_DOWN))
        assertEquals(113, keyboard.keycodeForKeysym(XKeyboard.KEYSYM_LEFT))
        assertEquals(114, keyboard.keycodeForKeysym(XKeyboard.KEYSYM_RIGHT))
        assertEquals(111, keyboard.keycodeForKeysym(XKeyboard.KEYSYM_UP))
        assertEquals(116, keyboard.keycodeForKeysym(XKeyboard.KEYSYM_DOWN))
        assertEquals(67, keyboard.keycodeForKeysym(XKeyboard.KEYSYM_F1))
        assertEquals(78, keyboard.keycodeForKeysym(XKeyboard.KEYSYM_F1 + 11)) // F12
        assertEquals(50, keyboard.keycodeForKeysym(XKeyboard.KEYSYM_SHIFT_L))
        assertEquals(62, keyboard.keycodeForKeysym(XKeyboard.KEYSYM_SHIFT_R))
        assertEquals(37, keyboard.keycodeForKeysym(XKeyboard.KEYSYM_CONTROL_L))
        assertEquals(64, keyboard.keycodeForKeysym(XKeyboard.KEYSYM_ALT_L))
        assertEquals(133, keyboard.keycodeForKeysym(XKeyboard.KEYSYM_SUPER_L))
        assertEquals(0xE9 + 8, keyboard.keycodeForKeysym(0xE9)) // dynamic Latin-1
        assertNull(keyboard.keycodeForKeysym(0xFF11)) // not a keysym this server defines
        assertEquals(70, keyboard.keycodeForKeysym(XKeyboard.KEYSYM_F1 + 3)) // F4 = 70
    }

    // --- IME commit extraction -------------------------------------------------

    @Test
    fun `composing text is held back until it commits`() {
        val ime = ImeCommitExtractor()
        assertEquals("", ime.onChanged("h", composing = true))
        assertEquals("", ime.onChanged("he", composing = true))
        assertEquals("", ime.onChanged("hel", composing = true))
        // Committing must inject the WHOLE word, not the post-preview tail.
        assertEquals("hello", ime.onChanged("hello", composing = false))
        ime.onReset()
        assertEquals("", ime.onChanged("", composing = false)) // the field clear injects nothing
    }

    @Test
    fun `plain appends and whole-word commits are injected`() {
        val ime = ImeCommitExtractor()
        assertEquals("world", ime.onChanged("world", composing = false)) // IME committed a whole word
        ime.onReset()
        assertEquals("", ime.onChanged("", composing = false))
        assertEquals("a", ime.onChanged("a", composing = false))
        assertEquals("b", ime.onChanged("ab", composing = false)) // hardware-key style append
        ime.onReset()
        assertEquals("", ime.onChanged("", composing = false))
    }

    @Test
    fun `mid-buffer edits re-sync without injecting garbage`() {
        val ime = ImeCommitExtractor()
        ime.onChanged("abc", composing = false)
        ime.onReset()
        // pending is "" after reset; a replace is treated as a fresh commit...
        assertEquals("axc", ime.onChanged("axc", composing = false))
        // ...but with a live pending buffer an edit that is not an append injects nothing.
        assertEquals("", ime.onChanged("axc", composing = false)) // unchanged
        ime.onChanged("axcQ", composing = false) // pending = axcQ
        assertEquals("", ime.onChanged("QQ", composing = false)) // exotic edit: drop it
    }
}
