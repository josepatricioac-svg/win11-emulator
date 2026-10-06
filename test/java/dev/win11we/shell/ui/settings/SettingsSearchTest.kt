package dev.win11we.shell.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Settings search box: pure filtering over the catalogue. */
class SettingsSearchTest {

    @Test
    fun `blank query returns the whole catalogue`() {
        assertEquals(SettingsCatalogue, SettingsSearch.filter(""))
        assertEquals(SettingsCatalogue, SettingsSearch.filter("   "))
    }

    @Test
    fun `labels match case-insensitively`() {
        assertEquals(listOf("theme"), SettingsSearch.filter("THEME").map { it.id })
        assertEquals(listOf("animations"), SettingsSearch.filter("window anim").map { it.id })
    }

    @Test
    fun `keywords find settings whose label does not contain the word`() {
        assertEquals(listOf("browser"), SettingsSearch.filter("firefox").map { it.id })
        assertTrue(SettingsSearch.filter("proot").map { it.id }.contains("runtime"))
        assertTrue(SettingsSearch.filter("minimise").map { it.id }.contains("animations"))
    }

    @Test
    fun `all words must match`() {
        assertEquals(emptyList<String>(), SettingsSearch.filter("theme banana").map { it.id })
        assertEquals(listOf("browser"), SettingsSearch.filter("browser default").map { it.id })
    }

    @Test
    fun `section names are searchable too`() {
        val sections = SettingsSearch.filter("storage").map { it.section }.distinct()
        assertTrue(sections.contains(SettingsSection.STORAGE))
    }

    @Test
    fun `nonsense returns nothing instead of everything`() {
        assertEquals(emptyList<String>(), SettingsSearch.filter("zzzz").map { it.id })
    }

    @Test
    fun `every catalogue entry has a section and a unique id`() {
        assertEquals(SettingsCatalogue.size, SettingsCatalogue.map { it.id }.distinct().size)
        assertTrue(SettingsCatalogue.all { it.label.isNotBlank() })
        assertTrue(SettingsCatalogue.all { it.section in SettingsSection.entries })
    }
}
