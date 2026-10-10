package dev.win11we.shell.x11

/**
 * Who owns a selection right now. [connection] is null for the shell bridge:
 * the Android side owns the selection through [XDocument.SHELL_WINDOW_ID] and
 * answers conversions inline from the system clipboard.
 */
data class SelectionOwner(val window: Int, val connection: XConnection?) {
    val isShell: Boolean get() = connection == null && window == XDocument.SHELL_WINDOW_ID
}

/**
 * The seam between the X server and the Android system clipboard (P1). Pure
 * interface so the protocol core stays unit-testable; the Compose layer
 * provides the real implementation (`ui/xdesktop/XDesktopClipboard.kt`).
 */
interface XClipboardBridge {
    /**
     * Current clipboard text, or null when there is none. Called from client
     * socket threads while the app is in the foreground (a binder call, no
     * looper needed); implementations must catch everything.
     */
    fun readText(): String?

    /** Hands guest-copied text to the Android clipboard (hops to the main thread). */
    fun writeText(text: String)
}

/**
 * Text <-> selection-target encoding. X clients negotiate targets; the bridge
 * speaks plain strings, so this is the (small) translation layer:
 * UTF8_STRING/TEXT encode as UTF-8, STRING as Latin-1 (best effort, as real
 * servers do), anything richer (text/html, image targets, COMPOUND_TEXT) is
 * refused with a None conversion rather than guessed at.
 */
object SelectionCodec {
    fun encode(text: String?, targetName: String?): ByteArray? = when {
        text == null -> null
        targetName == "STRING" -> text.toByteArray(Charsets.ISO_8859_1)
        targetName == "UTF8_STRING" || targetName == "TEXT" -> text.toByteArray(Charsets.UTF_8)
        else -> null
    }

    fun decode(bytes: ByteArray, targetName: String?): String? = when (targetName) {
        "STRING" -> String(bytes, Charsets.ISO_8859_1)
        "UTF8_STRING", "TEXT" -> String(bytes, Charsets.UTF_8)
        else -> null
    }

    /** The text targets the shell bridge offers in a TARGETS conversion. */
    val TEXT_TARGETS = listOf("TARGETS", "STRING", "UTF8_STRING", "TEXT")
}
