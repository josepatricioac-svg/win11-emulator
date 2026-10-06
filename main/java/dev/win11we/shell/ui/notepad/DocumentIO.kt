package dev.win11we.shell.ui.notepad

import java.nio.charset.Charset

/** Character encodings Notepad can read and write. */
enum class DocEncoding(val label: String, val charsetName: String) {
    UTF8("UTF-8", "UTF-8"),
    UTF8_BOM("UTF-8 with BOM", "UTF-8"),
    UTF16_LE("UTF-16 LE", "UTF-16LE"),
    UTF16_BE("UTF-16 BE", "UTF-16BE"),
    ANSI("ANSI", "windows-1252");

    /**
     * [Windows1252] is used for ANSI because the platform registry may not
     * provide windows-1252 (Android historically does not).
     */
    val charset: Charset
        get() = if (this == ANSI) Windows1252 else Charset.forName(charsetName)
}

/** Line endings, detected on load and preserved on save. */
enum class LineEndings(val label: String, val sequence: String) {
    WINDOWS("Windows (CRLF)", "\r\n"),
    UNIX("Unix (LF)", "\n"),
    MAC("Macintosh (CR)", "\r"),
}

/**
 * Byte-level document conversion. Pure JVM (no Android types) so it is unit-tested;
 * the ContentResolver work stays in NotepadScreen.
 */
object DocumentIO {

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    /** Detects the BOM; defaults to UTF-8 without BOM, like Notepad. */
    fun detectEncoding(bytes: ByteArray): DocEncoding = when {
        bytes.size >= 3 && bytes[0] == UTF8_BOM[0] && bytes[1] == UTF8_BOM[1] && bytes[2] == UTF8_BOM[2] -> DocEncoding.UTF8_BOM
        bytes.size >= 2 && bytes[0] == UTF16_LE_BOM[0] && bytes[1] == UTF16_LE_BOM[1] -> DocEncoding.UTF16_LE
        bytes.size >= 2 && bytes[0] == UTF16_BE_BOM[0] && bytes[1] == UTF16_BE_BOM[1] -> DocEncoding.UTF16_BE
        else -> DocEncoding.UTF8
    }

    fun stripBom(bytes: ByteArray): ByteArray {
        val skip = when (detectEncoding(bytes)) {
            DocEncoding.UTF8_BOM -> if (bytes.size >= 3) 3 else 0
            DocEncoding.UTF16_LE, DocEncoding.UTF16_BE -> if (bytes.size >= 2) 2 else 0
            else -> 0
        }
        return if (skip == 0) bytes else bytes.copyOfRange(skip, bytes.size)
    }

    /** Writes the BOM back when the document had one, so a round trip is byte-exact. */
    fun prependBom(bytes: ByteArray, encoding: DocEncoding): ByteArray = when (encoding) {
        DocEncoding.UTF8_BOM -> UTF8_BOM + bytes
        DocEncoding.UTF16_LE -> UTF16_LE_BOM + bytes
        DocEncoding.UTF16_BE -> UTF16_BE_BOM + bytes
        else -> bytes
    }

    /**
     * Decodes with the detected encoding. "UTF-16LE"/"UTF-16BE" charsets in the JVM
     * do not consume a BOM, and the stripped bytes have none left, so the body is
     * decoded with those explicit charsets directly.
     */
    fun decode(bytes: ByteArray, encoding: DocEncoding): String = String(stripBom(bytes), encoding.charset)

    fun encode(text: String, encoding: DocEncoding): ByteArray =
        prependBom(text.toByteArray(encoding.charset), encoding)

    /** Detects the dominant line ending; empty or single-line text stays CRLF. */
    fun detectLineEndings(text: String): LineEndings {
        var crlf = 0
        var loneLf = 0
        var loneCr = 0
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '\r' -> if (i + 1 < text.length && text[i + 1] == '\n') { crlf++; i++ } else loneCr++
                '\n' -> loneLf++
                else -> Unit
            }
            i++
        }
        return when {
            crlf >= loneLf && crlf >= loneCr && crlf > 0 -> LineEndings.WINDOWS
            loneCr > loneLf && loneCr > 0 -> LineEndings.MAC
            else -> LineEndings.UNIX
        }
    }

    /** Edits always work on '\n'; the saved file gets the document's ending back. */
    fun normalize(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

    fun denormalize(text: String, endings: LineEndings): String =
        if (endings == LineEndings.UNIX) normalize(text) else normalize(text).replace("\n", endings.sequence)
}

/** 1-based Ln/Col for the status bar, derived from a caret offset. */
data class TextPosition(val line: Int, val column: Int) {
    companion object {
        fun of(text: String, offset: Int): TextPosition {
            val safe = offset.coerceIn(0, text.length)
            var line = 1
            var lastBreak = -1
            for (i in 0 until safe) {
                if (text[i] == '\n') {
                    line++
                    lastBreak = i
                }
            }
            return TextPosition(line = line, column = safe - lastBreak) // lastBreak is -1 on line 1
        }
    }
}
