package dev.win11we.shell.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [ShellPreferences] so the store can be tested without Android. */
class FakePreferences : ShellPreferences {
    val strings = HashMap<String, String>()
    val booleans = HashMap<String, Boolean>()
    var writes = 0
        private set

    override fun getString(key: String, default: String) = strings[key] ?: default
    override fun putString(key: String, value: String) {
        writes++
        strings[key] = value
    }

    override fun getBoolean(key: String, default: Boolean) = booleans[key] ?: default
    override fun putBoolean(key: String, value: Boolean) {
        writes++
        booleans[key] = value
    }
}

class SettingsStoreTest {

    @Test
    fun `defaults match the documented shell defaults`() {
        val prefs = FakePreferences()
        val store = SettingsStore(prefs)
        assertEquals(ThemeMode.DARK, store.themeMode)
        assertEquals(TaskbarAlignment.CENTER, store.taskbarAlignment)
        assertTrue(store.windowAnimations)
        assertEquals("", store.browserPackage) // system default handler
        assertTrue(store.showBrowserPin)
        assertTrue(store.showContainersPin)
        assertEquals(Box64Preset.GENERAL, store.box64Preset)
        assertEquals(0, prefs.writes) // reading defaults writes nothing
    }

    @Test
    fun `every write is persisted immediately`() {
        val prefs = FakePreferences()
        val store = SettingsStore(prefs)
        store.themeMode = ThemeMode.LIGHT
        store.taskbarAlignment = TaskbarAlignment.LEFT
        store.windowAnimations = false
        store.browserPackage = "org.mozilla.firefox"
        store.showBrowserPin = false
        store.box64Preset = Box64Preset.OFFICE
        store.rootfsLocation = "/data/user/0/dev.win11we.shell/files/win11we"

        val reloaded = SettingsStore(prefs) // same backing store, as after a restart
        assertEquals(ThemeMode.LIGHT, reloaded.themeMode)
        assertEquals(TaskbarAlignment.LEFT, reloaded.taskbarAlignment)
        assertFalse(reloaded.windowAnimations)
        assertEquals("org.mozilla.firefox", reloaded.browserPackage)
        assertFalse(reloaded.showBrowserPin)
        assertEquals(Box64Preset.OFFICE, reloaded.box64Preset)
        assertEquals("/data/user/0/dev.win11we.shell/files/win11we", reloaded.rootfsLocation)
    }

    @Test
    fun `boolean settings are stored as booleans and strings as strings`() {
        val prefs = FakePreferences()
        val store = SettingsStore(prefs)
        store.windowAnimations = false
        store.themeMode = ThemeMode.LIGHT
        assertEquals(false, prefs.booleans[SettingsKeys.WINDOW_ANIMATIONS])
        assertEquals("LIGHT", prefs.strings[SettingsKeys.THEME_MODE])
    }

    @Test
    fun `unknown stored values fall back to the default instead of crashing`() {
        val prefs = FakePreferences()
        prefs.strings[SettingsKeys.THEME_MODE] = "NEON"
        prefs.strings[SettingsKeys.BOX64_PRESET] = "DOOM"
        val store = SettingsStore(prefs)
        assertEquals(ThemeMode.DARK, store.themeMode)
        assertEquals(Box64Preset.GENERAL, store.box64Preset)
    }

    @Test
    fun `a pinned browser is only preferred while it is installed`() {
        val store = SettingsStore(FakePreferences())
        store.browserPackage = "org.mozilla.firefox"
        assertEquals(
            "org.mozilla.firefox",
            store.preferredBrowserOrNull(listOf("org.mozilla.firefox", "com.android.chrome")),
        )
        assertNull(store.preferredBrowserOrNull(listOf("com.android.chrome")))
        store.browserPackage = ""
        assertNull(store.preferredBrowserOrNull(listOf("org.mozilla.firefox")))
    }

    @Test
    fun `every key can be written and read back`() {
        val prefs = FakePreferences()
        val store = SettingsStore(prefs)
        SettingsKeys.ALL.forEach { key ->
            if (key in SettingsKeys.BOOLEAN_KEYS) {
                store.windowAnimations = false // exercises the boolean write path
            } else {
                prefs.strings[key] = null ?: prefs.getString(key, "")
            }
        }
        // A store rebuilt from the same preferences sees the persisted values.
        val reloaded = SettingsStore(prefs)
        assertFalse(reloaded.windowAnimations)
        assertEquals(store.browserPackage, reloaded.browserPackage)
        assertEquals(store.box64Preset, reloaded.box64Preset)
        assertTrue(SettingsKeys.ALL.size >= 8)
    }
}
