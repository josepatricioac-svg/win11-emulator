package dev.win11we.shell.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.win11we.shell.R

/** Left-hand navigation of the Settings app. */
enum class SettingsSection(val label: String, @DrawableRes val iconRes: Int) {
    PERSONALISATION("Personalisation", R.drawable.ic_palette),
    APPS("Apps", R.drawable.ic_view_grid),
    STORAGE("Storage", R.drawable.ic_storage),
    CONTAINERS("Containers", R.drawable.ic_app_container),
    DIAGNOSTICS("Diagnostics", R.drawable.ic_diagnostics),
    ABOUT("About", R.drawable.ic_info),
}

/** A searchable settings entry: section + label + the words that find it. */
data class SettingsEntry(val id: String, val label: String, val section: SettingsSection, val keywords: String = "")

val SettingsCatalogue: List<SettingsEntry> = listOf(
    SettingsEntry("theme", "App theme", SettingsSection.PERSONALISATION, "dark light colour color appearance"),
    SettingsEntry("taskbar", "Taskbar alignment", SettingsSection.PERSONALISATION, "centre center left icons"),
    SettingsEntry("animations", "Window animations", SettingsSection.PERSONALISATION, "motion open close minimise"),
    SettingsEntry("browser", "Default browser", SettingsSection.APPS, "firefox web http link"),
    SettingsEntry("pins", "Taskbar pins", SettingsSection.APPS, "browser containers show hide"),
    SettingsEntry("rootfs", "Rootfs location", SettingsSection.STORAGE, "ubuntu wine prefix path download"),
    SettingsEntry("space", "Free space", SettingsSection.STORAGE, "disk storage space"),
    SettingsEntry("preset", "Box64 preset", SettingsSection.CONTAINERS, "office dotnet electron general"),
    SettingsEntry("runtime", "Runtime status", SettingsSection.CONTAINERS, "proot box64 wine not installed"),
    SettingsEntry("session", "Boot into X Desktop session", SettingsSection.CONTAINERS, "emulation boot single surface x11 start"),
    SettingsEntry("selftest", "Self-test", SettingsSection.DIAGNOSTICS, "wine64 box64 version log"),
    SettingsEntry("version", "Version", SettingsSection.ABOUT, "build version sdk"),
)

/** Search is a pure function over the catalogue, so it is unit-tested. */
object SettingsSearch {
    fun filter(query: String, entries: List<SettingsEntry> = SettingsCatalogue): List<SettingsEntry> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return entries
        val words = needle.split(' ').filter { it.isNotBlank() }
        return entries.filter { entry ->
            val haystack = (entry.label + " " + entry.keywords + " " + entry.section.label).lowercase()
            words.all { haystack.contains(it) }
        }
    }
}

/** Per-window Settings state: the visible section and the search query. */
@Stable
class SettingsState {
    var section by mutableStateOf(SettingsSection.PERSONALISATION)
    var query by mutableStateOf("")
    val searching: Boolean get() = query.isNotBlank()
    fun results(): List<SettingsEntry> = SettingsSearch.filter(query)
}
