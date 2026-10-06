package dev.win11we.shell.ui.notepad

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CharsetEncoder
import java.nio.charset.CoderResult

/**
 * Notepad's "ANSI" is windows-1252 on every Western Windows install, but that
 * charset is not guaranteed to exist in the JVM/Android charset registry, so the
 * codec is implemented here. Unmappable input becomes '?' on encode and undefined
 * slots (0x81, 0x8D, 0x8F, 0x90, 0x9D) decode to '?', exactly like Windows.
 */
object Windows1252 : Charset("windows-1252-shim", arrayOf("cp1252-shim", "windows-1252-local")) {

    /** Code points for the 0x80..0x9F range; 0 means "undefined in cp1252". */
    private val HIGH: IntArray = intArrayOf(
        0x20AC, 0, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021,
        0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0, 0x017D, 0,
        0, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014,
        0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0, 0x017E, 0x0178,
    )

    private val REVERSE: Map<Char, Byte> = buildMap {
        for (index in HIGH.indices) {
            val codePoint = HIGH[index]
            if (codePoint != 0) put(codePoint.toChar(), (0x80 + index).toByte())
        }
    }

    override fun contains(other: Charset): Boolean = other.name().equals(name(), ignoreCase = true)

    override fun newDecoder(): CharsetDecoder = object : CharsetDecoder(this, 1f, 1f) {
        override fun decodeLoop(input: ByteBuffer, output: CharBuffer): CoderResult {
            while (input.hasRemaining()) {
                if (!output.hasRemaining()) return CoderResult.OVERFLOW
                val byte = input.get().toInt() and 0xFF
                val char = when {
                    byte < 0x80 -> byte.toChar()
                    byte in 0x80..0x9F -> HIGH[byte - 0x80].let { if (it == 0) '?' else it.toChar() }
                    else -> byte.toChar() // 0xA0..0xFF matches Unicode, like Latin-1
                }
                output.put(char)
            }
            return CoderResult.UNDERFLOW
        }
    }

    override fun newEncoder(): CharsetEncoder = object : CharsetEncoder(this, 1f, 1f) {
        override fun encodeLoop(input: CharBuffer, output: ByteBuffer): CoderResult {
            while (input.hasRemaining()) {
                if (!output.hasRemaining()) return CoderResult.OVERFLOW
                val char = input.get()
                val byte = when {
                    char.code < 0x80 -> char.code.toByte()
                    // The cp1252-only range: euro sign, smart quotes, dashes, ...
                    REVERSE.containsKey(char) -> REVERSE.getValue(char)
                    // 0xA0..0xFF is identical to Latin-1.
                    char.code in 0xA0..0xFF -> char.code.toByte()
                    else -> QUESTION_MARK // unmappable, exactly like Notepad
                }
                output.put(byte)
            }
            return CoderResult.UNDERFLOW
        }
    }

    private val QUESTION_MARK: Byte = '?'.code.toByte()
}
