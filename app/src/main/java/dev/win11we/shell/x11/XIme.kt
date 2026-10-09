package dev.win11we.shell.x11

/**
 * Diffs a hidden IME text field against what was already injected and extracts
 * only the *committed* text (D1c soft-keyboard path). The Compose layer wires
 * value changes and resets into this; all decisions live here so they are
 * unit-testable without Android.
 *
 * Rules:
 * - While the IME has an active composition (the underlined preview), nothing
 *   is injected and the pending buffer does not advance - otherwise committing
 *   "hello" after previewing "he" would inject only "llo".
 * - Text appended after the pending buffer is the commit delta.
 * - A full replace with an empty pending buffer (some IMEs commit whole words
 *   without composition) injects the whole text.
 * - Any other edit (cursor moved mid-buffer) re-syncs silently: injecting
 *   garbage into the guest is worse than dropping an exotic edit.
 */
class ImeCommitExtractor {
    private var pending = ""

    /**
     * @param text the text field's new full value
     * @param composing true while an IME composition is open
     * @return the characters to hand to [XInput.injectText] right now (may be empty)
     */
    fun onChanged(text: String, composing: Boolean): String {
        if (composing) return ""
        val committed = when {
            text == pending -> ""
            text.startsWith(pending) -> text.substring(pending.length)
            pending.isEmpty() -> text
            else -> ""
        }
        pending = text
        return committed
    }

    /** Called after the field was cleared (post-injection) or focus was lost. */
    fun onReset() {
        pending = ""
    }
}
