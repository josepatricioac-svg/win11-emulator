package dev.win11we.shell.ui.explorer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Listing, filtering, sorting and formatting - pure JVM, temp dirs only. */
class FileEntriesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fixture(): File {
        val root = tmp.root
        File(root, "Bravo").mkdir()
        File(root, "alpha").mkdir()
        File(root, "notes.txt").writeText("x".repeat(2048))
        File(root, "setup.exe").writeText("y".repeat(3 * 1024 * 1024))
        File(root, "photo.png").writeText("z".repeat(512))
        File(root, ".hidden").writeText("h")
        return root
    }

    @Test
    fun `folders come first and names sort case-insensitively`() {
        val names = FileEntries.list(fixture()).map { it.name }
        assertEquals(listOf("alpha", "Bravo", "notes.txt", "photo.png", "setup.exe"), names)
    }

    @Test
    fun `hidden entries are never listed`() {
        assertTrue(FileEntries.list(fixture()).none { it.name.startsWith(".") })
    }

    @Test
    fun `descending reverses within each group`() {
        val names = FileEntries.list(fixture(), key = SortKey.NAME, ascending = false).map { it.name }
        assertEquals(listOf("Bravo", "alpha", "setup.exe", "photo.png", "notes.txt"), names)
    }

    @Test
    fun `size sort puts folders first then smallest file`() {
        val entries = FileEntries.list(fixture(), key = SortKey.SIZE)
        assertEquals(listOf("alpha", "Bravo", "photo.png", "notes.txt", "setup.exe"), entries.map { it.name })
    }

    @Test
    fun `type sort groups by extension`() {
        val files = FileEntries.list(fixture(), key = SortKey.TYPE).filter { !it.isDirectory }
        assertEquals(listOf("setup.exe", "photo.png", "notes.txt"), files.map { it.name })
    }

    @Test
    fun `query filters case-insensitively`() {
        assertEquals(listOf("photo.png"), FileEntries.list(fixture(), query = "PHO").map { it.name })
        assertEquals(emptyList<String>(), FileEntries.list(fixture(), query = "zzz").map { it.name })
    }

    @Test
    fun `sizes are formatted the Windows way`() {
        assertEquals("", FileEntries.formatSize(0))
        assertEquals("512 bytes", FileEntries.formatSize(512))
        assertEquals("2.0 KB", FileEntries.formatSize(2048))
        assertEquals("3.0 MB", FileEntries.formatSize(3L * 1024 * 1024))
        assertEquals("1.5 GB", FileEntries.formatSize(1536L * 1024 * 1024))
    }

    @Test
    fun `type labels describe the content`() {
        val root = fixture()
        assertEquals("File folder", FileEntries.typeLabel(FileEntries.of(File(root, "alpha"))))
        assertEquals("Application", FileEntries.typeLabel(FileEntries.of(File(root, "setup.exe"))))
        assertEquals("Text document", FileEntries.typeLabel(FileEntries.of(File(root, "notes.txt"))))
        assertEquals("Image", FileEntries.typeLabel(FileEntries.of(File(root, "photo.png"))))
    }

    @Test
    fun `unreadable directories list nothing instead of crashing`() {
        assertEquals(emptyList<FileEntry>(), FileEntries.list(File(tmp.root, "does-not-exist")))
    }
}
