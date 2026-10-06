package dev.win11we.shell.ui.notepad

import org.junit.Assert.assertArrayEquals
import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Encoding, BOM and line-ending handling - pure JVM. */
class DocumentIOTest {

    @Test
    fun `boms are detected and stripped`() {
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hi".toByteArray()
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hi".toByteArray(Charset.forName("UTF-16LE"))
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "hi".toByteArray(Charset.forName("UTF-16BE"))

        assertEquals(DocEncoding.UTF8_BOM, DocumentIO.detectEncoding(utf8Bom))
        assertEquals(DocEncoding.UTF16_LE, DocumentIO.detectEncoding(le))
        assertEquals(DocEncoding.UTF16_BE, DocumentIO.detectEncoding(be))
        assertEquals(DocEncoding.UTF8, DocumentIO.detectEncoding("hi".toByteArray()))
        assertEquals("hi", DocumentIO.decode(utf8Bom, DocEncoding.UTF8_BOM))
        assertEquals("hi", DocumentIO.decode(le, DocEncoding.UTF16_LE))
        assertEquals("hi", DocumentIO.decode(be, DocEncoding.UTF16_BE))
    }

    @Test
    fun `bom encodings round trip byte-exactly through detect and decode`() {
        val text = "Grüße — 日本語\r\nsecond line"
        for (encoding in listOf(DocEncoding.UTF8_BOM, DocEncoding.UTF16_LE, DocEncoding.UTF16_BE)) {
            val bytes = DocumentIO.encode(text, encoding)
            val detected = DocumentIO.detectEncoding(bytes)
            assertEquals(encoding, detected)
            assertEquals(text, DocumentIO.decode(bytes, detected))
        }
    }

    @Test
    fun `ansi has no bom so the document's own encoding is used to read it back`() {
        val text = "Grüße — 日本語\r\nsecond line"
        val bytes = DocumentIO.encode(text, DocEncoding.ANSI)
        // Detection can only fall back to UTF-8 here; Notepad remembers ANSI per document.
        assertEquals(DocEncoding.UTF8, DocumentIO.detectEncoding(bytes))
        // windows-1252 covers Latin-1 plus the euro sign and em dash, but not CJK.
        assertEquals("Grüße — ???\r\nsecond line", DocumentIO.decode(bytes, DocEncoding.ANSI))
    }

    @Test
    fun `ansi maps to windows-1252 and loses what it cannot represent`() {
        val bytes = DocumentIO.encode("café € —", DocEncoding.ANSI)
        // windows-1252 holds Latin-1 plus € (0x80) and — (0x97)
        assertEquals("café € —", DocumentIO.decode(bytes, DocEncoding.ANSI))
        assertEquals("?", DocumentIO.decode(DocumentIO.encode("漢", DocEncoding.ANSI), DocEncoding.ANSI))
        assertEquals(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte(), 0x20, 0x80.toByte(), 0x20, 0x97.toByte()).toList(),
            bytes.toList())
    }

    @Test
    fun `line endings are detected from the dominant style`() {
        assertEquals(LineEndings.WINDOWS, DocumentIO.detectLineEndings("a\r\nb\r\nc"))
        assertEquals(LineEndings.UNIX, DocumentIO.detectLineEndings("a\nb\nc"))
        assertEquals(LineEndings.MAC, DocumentIO.detectLineEndings("a\rb\rc"))
        assertEquals(LineEndings.UNIX, DocumentIO.detectLineEndings("single line"))
        assertEquals(LineEndings.UNIX, DocumentIO.detectLineEndings(""))
    }

    @Test
    fun `editing normalizes and saving denormalizes`() {
        assertEquals("a\nb\nc", DocumentIO.normalize("a\r\nb\rc"))
        assertEquals("a\r\nb\r\nc", DocumentIO.denormalize("a\nb\nc", LineEndings.WINDOWS))
        assertEquals("a\rb\rc", DocumentIO.denormalize("a\r\nb\nc", LineEndings.MAC))
        assertEquals("a\nb\nc", DocumentIO.denormalize("a\r\nb\rc", LineEndings.UNIX))
    }

    @Test
    fun `a crlf document survives a normalize-detect-denormalize cycle`() {
        val original = "line1\r\nline2\r\n"
        val bytes = DocumentIO.encode(original, DocEncoding.UTF8_BOM)
        val decoded = DocumentIO.decode(bytes, DocumentIO.detectEncoding(bytes))
        val endings = DocumentIO.detectLineEndings(decoded)
        val saved = DocumentIO.encode(DocumentIO.denormalize(DocumentIO.normalize(decoded), endings), DocEncoding.UTF8_BOM)
        assertArrayEquals(bytes, saved)
    }

    @Test
    fun `position reports 1-based line and column`() {
        assertEquals(TextPosition(1, 1), TextPosition.of("", 0))
        assertEquals(TextPosition(1, 4), TextPosition.of("hello", 3))
        assertEquals(TextPosition(2, 1), TextPosition.of("hello\nworld", 6))
        assertEquals(TextPosition(3, 4), TextPosition.of("a\nbb\nccc", 8))
        // Out-of-range offsets are clamped instead of throwing.
        assertEquals(TextPosition(1, 6), TextPosition.of("hello", 99))
    }

    @Test
    fun `utf8 bom bytes are written back on save`() {
        val bytes = DocumentIO.encode("x", DocEncoding.UTF8_BOM)
        assertTrue(bytes.size == 4)
        assertEquals(0xEF.toByte(), bytes[0])
    }
}

/** The bundled windows-1252 codec must behave like the platform one. */
class Windows1252Test {

    @Test
    fun `decodes the cp1252-only range`() {
        val bytes = byteArrayOf(0x80.toByte(), 0x85.toByte(), 0x93.toByte(), 0x94.toByte(), 0x96.toByte(), 0x97.toByte())
        assertEquals("\u20AC\u2026\u201C\u201D\u2013\u2014", String(bytes, Windows1252))
    }

    @Test
    fun `undefined slots decode to a question mark`() {
        assertEquals("?", String(byteArrayOf(0x81.toByte()), Windows1252))
        assertEquals("?", String(byteArrayOf(0x8D.toByte()), Windows1252))
        assertEquals("?", String(byteArrayOf(0x9D.toByte()), Windows1252))
    }

    @Test
    fun `ascii and latin-1 range pass through`() {
        val text = "Hello, café naïve §° 0123"
        assertEquals(text, String(text.toByteArray(Windows1252), Windows1252))
    }

    @Test
    fun `round trips every mapped character`() {
        val text = "\u20AC\u201A\u0192\u201E\u2026\u2020\u2021\u02C6\u2030\u0160\u2039\u0152\u017D" +
            "\u2018\u2019\u201C\u201D\u2022\u2013\u2014\u02DC\u2122\u0161\u203A\u0153\u017E\u0178"
        assertEquals(text, String(text.toByteArray(Windows1252), Windows1252))
    }

    @Test
    fun `unmappable input degrades to a question mark`() {
        assertEquals("???", String("日本語".toByteArray(Windows1252), Windows1252))
    }
}
