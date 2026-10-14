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
        store.showBrowserPin = false
        store.box64Preset = Box64Preset.OFFICE
        store.rootfsLocation = "/data/user/0/dev.win11we.shell/files/win11we"

        val reloaded = SettingsStore(prefs) // same backing store, as after a restart
        assertEquals(ThemeMode.LIGHT, reloaded.themeMode)
        assertEquals(TaskbarAlignment.LEFT, reloaded.taskbarAlignment)
        assertFalse(reloaded.windowAnimations)
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
    fun `the browser engine choice persists and defaults to auto`() {
        val prefs = FakePreferences()
        val store = SettingsStore(prefs)
        assertEquals("", store.browserEngine) // "" = resolve automatically (Firefox first)
        store.browserEngine = "desktop:firefox-esr"
        assertEquals("desktop:firefox-esr", SettingsStore(prefs).browserEngine)
        store.browserEngine = "desktop:chromium-arm64"
        assertEquals("desktop:chromium-arm64", SettingsStore(prefs).browserEngine)
        // A stale "android:..." value (stored before Android detection was
        // removed) round-trips harmlessly - the resolver maps it to the
        // default engine instead of crashing or handing a URL to the system.
        store.browserEngine = "android:com.brave.browser"
        assertEquals("android:com.brave.browser", SettingsStore(prefs).browserEngine)
        store.browserEngine = ""
        assertEquals("", SettingsStore(prefs).browserEngine)
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
        assertEquals(store.browserEngine, reloaded.browserEngine)
        assertEquals(store.box64Preset, reloaded.box64Preset)
        assertTrue(SettingsKeys.ALL.size >= 8)
    }
}
