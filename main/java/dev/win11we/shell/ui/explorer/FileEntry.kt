package dev.win11we.shell.ui.explorer

import java.io.File
import java.util.Locale

/** One row/tile in the file view. */
data class FileEntry(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedMillis: Long,
) {
    val extension: String
        get() = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
}

enum class SortKey { NAME, DATE, TYPE, SIZE }

/** Pure helpers: no Android framework types, so they are unit-testable on the JVM. */
object FileEntries {

    fun of(file: File): FileEntry = FileEntry(
        file = file,
        name = file.name,
        isDirectory = file.isDirectory,
        sizeBytes = if (file.isDirectory) 0L else file.length(),
        modifiedMillis = file.lastModified(),
    )

    /**
     * Lists [dir], drops hidden and unreadable entries, filters by [query]
     * (case-insensitive substring) and sorts: folders first, then [key].
     */
    fun list(dir: File, query: String = "", key: SortKey = SortKey.NAME, ascending: Boolean = true): List<FileEntry> {
        val children = dir.listFiles() ?: return emptyList()
        return children
            .filter { !it.name.startsWith(".") }
            .map(::of)
            .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
            .sortedWith(comparator(key, ascending))
    }

    fun comparator(key: SortKey, ascending: Boolean): Comparator<FileEntry> {
        // Every key breaks ties on the name, case-insensitively, like Explorer does.
        val byName: Comparator<FileEntry> = compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        val byKey: Comparator<FileEntry> = when (key) {
            SortKey.NAME -> byName
            SortKey.DATE -> compareBy<FileEntry> { it.modifiedMillis }.then(byName)
            SortKey.TYPE -> compareBy<FileEntry> { it.extension }.then(byName)
            SortKey.SIZE -> compareBy<FileEntry> { it.sizeBytes }.then(byName)
        }
        val directed = if (ascending) byKey else byKey.reversed()
        // Folders first, then the chosen key, then the name so equal keys stay stable.
        return compareByDescending<FileEntry> { it.isDirectory }
            .then(directed)
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    }

    /** Windows-style sizes: bytes below 1 KB, then KB/MB/GB with one decimal. */
    fun formatSize(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes < 1024L -> "$bytes bytes"
        bytes < 1024L * 1024L -> trim(bytes / 1024.0) + " KB"
        bytes < 1024L * 1024L * 1024L -> trim(bytes / (1024.0 * 1024.0)) + " MB"
        else -> trim(bytes / (1024.0 * 1024.0 * 1024.0)) + " GB"
    }

    private fun trim(value: Double): String =
        if (value >= 100) value.toLong().toString() else String.format(Locale.ROOT, "%.1f", value)

    /** Human type names for the Details column. */
    fun typeLabel(entry: FileEntry): String = when {
        entry.isDirectory -> "File folder"
        else -> when (entry.extension) {
            "exe" -> "Application"
            "msi" -> "Windows Installer package"
            "lnk" -> "Shortcut"
            "dll" -> "Application extension"
            "txt", "log", "md", "ini", "cfg", "json", "xml", "yml", "yaml", "csv" -> "Text document"
            "doc", "docx" -> "Word document"
            "xls", "xlsx" -> "Excel workbook"
            "ppt", "pptx" -> "PowerPoint presentation"
            "pdf" -> "PDF document"
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg" -> "Image"
            "mp3", "wav", "flac", "ogg", "m4a" -> "Audio file"
            "mp4", "mkv", "avi", "mov", "webm" -> "Video file"
            "zip", "rar", "7z", "tar", "gz" -> "Compressed archive"
            "" -> "File"
            else -> entry.extension.uppercase(Locale.ROOT) + " file"
        }
    }
}
