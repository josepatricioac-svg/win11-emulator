package dev.win11we.shell.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode(val label: String) { DARK("Dark"), LIGHT("Light") }

enum class TaskbarAlignment(val label: String) { CENTER("Centre"), LEFT("Left") }

/**
 * Key/value store the shell persists. An interface so the real
 * SharedPreferences-backed implementation can be unit-tested on the JVM.
 */
interface ShellPreferences {
    fun getString(key: String, default: String): String
    fun putString(key: String, value: String)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

object SettingsKeys {
    const val THEME_MODE = "shell.themeMode"
    const val TASKBAR_ALIGNMENT = "shell.taskbarAlignment"
    const val WINDOW_ANIMATIONS = "shell.windowAnimations"
    const val BROWSER_ENGINE = "shell.browserEngine"
    const val SHOW_BROWSER_PIN = "shell.showBrowserPin"
    const val SHOW_CONTAINERS_PIN = "shell.showContainersPin"
    const val BOX64_PRESET = "container.box64Preset"
    const val ROOTFS_LOCATION = "container.rootfsLocation"

    val ALL = listOf(
        THEME_MODE,
        TASKBAR_ALIGNMENT,
        WINDOW_ANIMATIONS,
        BROWSER_ENGINE,
        SHOW_BROWSER_PIN,
        SHOW_CONTAINERS_PIN,
        BOX64_PRESET,
        ROOTFS_LOCATION,
    )

    /** Keys whose value is a boolean, everything else is a string. */
    val BOOLEAN_KEYS = setOf(WINDOW_ANIMATIONS, SHOW_BROWSER_PIN, SHOW_CONTAINERS_PIN)
}

/** Box64 presets, offered per container in P2; stored now so the UI is final. */
enum class Box64Preset(val label: String, val description: String) {
    GENERAL("General", "Balanced defaults for most Win32 apps"),
    OFFICE("Office", "Tuned for Office-type productivity suites"),
    DOTNET("DotNet", "For .NET / managed runtimes"),
    ELECTRON("Electron", "For Electron-based apps"),
}

/**
 * Observable shell settings: one snapshot map of strings backed by
 * [ShellPreferences], so every read recomposes the shell and every write
 * persists immediately. Booleans are stored as "true"/"false".
 */
@Stable
class SettingsStore(private val prefs: ShellPreferences) {

    private val values = mutableStateMapOf<String, String>().apply {
        SettingsKeys.ALL.forEach { key ->
            put(
                key,
                if (key in SettingsKeys.BOOLEAN_KEYS) prefs.getBoolean(key, true).toString() else prefs.getString(key, ""),
            )
        }
    }

    var themeMode: ThemeMode
        get() = enumValue(ThemeMode.entries, SettingsKeys.THEME_MODE, ThemeMode.DARK)
        set(value) = write(SettingsKeys.THEME_MODE, value.name)

    var taskbarAlignment: TaskbarAlignment
        get() = enumValue(TaskbarAlignment.entries, SettingsKeys.TASKBAR_ALIGNMENT, TaskbarAlignment.CENTER)
        set(value) = write(SettingsKeys.TASKBAR_ALIGNMENT, value.name)

    var windowAnimations: Boolean
        get() = booleanValue(SettingsKeys.WINDOW_ANIMATIONS, true)
        set(value) = write(SettingsKeys.WINDOW_ANIMATIONS, value.toString())

    /**
     * Selected [dev.win11we.shell.ui.browser.BrowserEngine] id. Empty means
     * "resolve automatically" (Firefox on Android, then the system default).
     */
    var browserEngine: String
        get() = values[SettingsKeys.BROWSER_ENGINE].orEmpty()
        set(value) = write(SettingsKeys.BROWSER_ENGINE, value)

    var showBrowserPin: Boolean
        get() = booleanValue(SettingsKeys.SHOW_BROWSER_PIN, true)
        set(value) = write(SettingsKeys.SHOW_BROWSER_PIN, value.toString())

    var showContainersPin: Boolean
        get() = booleanValue(SettingsKeys.SHOW_CONTAINERS_PIN, true)
        set(value) = write(SettingsKeys.SHOW_CONTAINERS_PIN, value.toString())

    var box64Preset: Box64Preset
        get() = enumValue(Box64Preset.entries, SettingsKeys.BOX64_PRESET, Box64Preset.GENERAL)
        set(value) = write(SettingsKeys.BOX64_PRESET, value.name)

    /** Where the rootfs will live; empty until the R3 download picks a place. */
    var rootfsLocation: String
        get() = values[SettingsKeys.ROOTFS_LOCATION].orEmpty()
        set(value) = write(SettingsKeys.ROOTFS_LOCATION, value)

    private fun write(key: String, value: String) {
        values[key] = value
        if (key in SettingsKeys.BOOLEAN_KEYS) prefs.putBoolean(key, value.toBoolean()) else prefs.putString(key, value)
    }

    private fun booleanValue(key: String, default: Boolean): Boolean = values[key]?.toBooleanStrictOrNull() ?: default

    private fun <E : Enum<E>> enumValue(entries: List<E>, key: String, default: E): E {
        val stored = values[key] ?: return default
        return entries.firstOrNull { it.name == stored } ?: default
    }
}

/** SharedPreferences-backed store. */
class AndroidShellPreferences(private val prefs: SharedPreferences) : ShellPreferences {
    override fun getString(key: String, default: String) = prefs.getString(key, default) ?: default
    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }
}

/** Process-wide accessor so deep composables (window bodies) can reach the store. */
@Stable
class ShellSettings private constructor(val store: SettingsStore, val context: Context) {
    companion object {
        @Volatile
        private var instance: ShellSettings? = null

        fun init(context: Context, prefs: ShellPreferences): ShellSettings =
            instance ?: synchronized(this) {
                instance ?: ShellSettings(SettingsStore(prefs), context.applicationContext).also { instance = it }
            }

        fun get(): ShellSettings = instance ?: error("ShellSettings.init() was not called")
    }
}

@Composable
fun rememberSettingsStore(): SettingsStore {
    val context = LocalContext.current
    return remember {
        ShellSettings.init(
            context = context,
            prefs = AndroidShellPreferences(
                context.applicationContext.getSharedPreferences("shell", Context.MODE_PRIVATE),
            ),
        ).store
    }
}
