package dev.win11we.shell.ui.explorer

import androidx.annotation.DrawableRes
import dev.win11we.shell.R

/** Maps an entry to a shell icon. Original tiles, no Microsoft artwork. */
@DrawableRes
fun iconFor(entry: FileEntry): Int = when {
    entry.isDirectory -> R.drawable.ic_app_explorer
    else -> when (entry.extension) {
        "exe", "msi", "bat", "cmd", "com" -> R.drawable.ic_file_app
        "lnk" -> R.drawable.ic_file_shortcut
        "txt", "log", "md", "ini", "cfg", "json", "xml", "yml", "yaml", "csv" -> R.drawable.ic_file_text
        "doc", "docx" -> R.drawable.ic_app_notepad
        "xls", "xlsx", "ppt", "pptx", "pdf" -> R.drawable.ic_app_notepad
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg" -> R.drawable.ic_picture
        "zip", "rar", "7z", "tar", "gz" -> R.drawable.ic_archive
        else -> R.drawable.ic_file_generic
    }
}

/** Sidebar locations: label, icon and the Android directory they map to. */
data class ExplorerLocation(
    val label: String,
    @DrawableRes val iconRes: Int,
    val path: String?,
    val group: String,
    val enabled: Boolean = true,
)

fun explorerLocations(storageRoot: String, prefixPath: String?): List<ExplorerLocation> = listOf(
    ExplorerLocation("Home", R.drawable.ic_home, storageRoot, "Quick access"),
    ExplorerLocation("Desktop", R.drawable.ic_desktop, "$storageRoot/Desktop", "Quick access"),
    ExplorerLocation("Downloads", R.drawable.ic_arrow_down, "$storageRoot/Download", "Quick access"),
    ExplorerLocation("Documents", R.drawable.ic_file_text, "$storageRoot/Documents", "Quick access"),
    ExplorerLocation("Pictures", R.drawable.ic_picture, "$storageRoot/Pictures", "Quick access"),
    ExplorerLocation("Phone (C:)", R.drawable.ic_drive, storageRoot, "This PC"),
    ExplorerLocation("Wine prefix (Z:)", R.drawable.ic_drive_wine, prefixPath, "This PC", enabled = prefixPath != null),
)
