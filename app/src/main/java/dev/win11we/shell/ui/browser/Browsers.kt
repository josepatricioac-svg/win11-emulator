package dev.win11we.shell.ui.browser

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import java.net.URLEncoder

/** One installed browser the shell can hand a URL to. */
data class BrowserApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isDefault: Boolean,
)

/** Browsers we name explicitly so they are visible to us on API 30+. */
val KnownBrowserPackages = listOf(
    "org.mozilla.firefox" to "Firefox",
    "org.mozilla.fennec" to "Firefox (F-Droid)",
    "org.mozilla.focus" to "Firefox Focus",
    "org.torproject.torbrowser" to "Tor Browser",
    "com.android.chrome" to "Chrome",
    "com.brave.browser" to "Brave",
    "com.vivaldi.browser" to "Vivaldi",
    "com.microsoft.emmx" to "Edge",
    "com.opera.browser" to "Opera",
    "org.chromium.chrome" to "Chromium",
)

object Browsers {

    /** Firefox first: it is the shell's preferred browser when the user has not chosen one. */
    const val PREFERRED_PACKAGE = "org.mozilla.firefox"

    /**
     * PackageManager queries plus icon loading cost real milliseconds, so they happen
     * once per process (and are warmed up in the background at startup) instead of on
     * the main thread every time a Browser window opens.
     */
    @Volatile
    private var cache: List<BrowserApp>? = null

    fun installed(context: Context): List<BrowserApp> =
        cache ?: resolve(context).also { cache = it }

    /** Drops the cache; call after an install/uninstall or from a "refresh" action. */
    fun invalidate() {
        cache = null
    }

    /** Warms the cache off the caller's thread. Safe to call repeatedly. */
    fun warmUp(context: Context) {
        if (cache != null) return
        val appContext = context.applicationContext
        Thread { runCatching { cache = resolve(appContext) } }.apply {
            name = "browser-resolve"
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /**
     * Installed browsers, resolved two ways: a VIEW intent for http(s) (what the
     * system would use) plus the known package names, so browsers that are
     * installed but not registered as http handlers still show up.
     */
    fun resolve(context: Context): List<BrowserApp> {
        val packageManager = context.packageManager
        val found = LinkedHashMap<String, BrowserApp>()

        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/"))
        val resolved: List<ResolveInfo> = try {
            packageManager.queryIntentActivities(viewIntent, PackageManager.MATCH_DEFAULT_ONLY)
        } catch (error: RuntimeException) {
            emptyList()
        }
        val defaultPackage = systemDefaultPackage(context)
        for (info in resolved) {
            val activity = info.activityInfo ?: continue
            val packageName = activity.packageName
            if (packageName == context.packageName) continue // our own shell is not a browser
            if (found.containsKey(packageName)) continue
            found[packageName] = BrowserApp(
                packageName = packageName,
                label = info.loadLabel(packageManager)?.toString() ?: packageName,
                icon = runCatching { info.loadIcon(packageManager) }.getOrNull(),
                isDefault = packageName == defaultPackage,
            )
        }

        for ((packageName, fallbackLabel) in KnownBrowserPackages) {
            if (found.containsKey(packageName)) continue
            val label = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
            } catch (error: PackageManager.NameNotFoundException) {
                continue // not installed
            }
            found[packageName] = BrowserApp(
                packageName = packageName,
                label = label.ifBlank { fallbackLabel },
                icon = runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull(),
                isDefault = packageName == defaultPackage,
            )
        }

        // Firefox first, then the system default, then alphabetical.
        return found.values.sortedWith(
            compareByDescending<BrowserApp> { it.packageName == PREFERRED_PACKAGE }
                .thenByDescending { it.isDefault }
                .thenBy { it.label.lowercase() },
        )
    }

    /** The package the system would use for an http link, or null. */
    fun systemDefaultPackage(context: Context): String? = try {
        context.packageManager
            .resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/")), 0)
            ?.activityInfo?.packageName
            ?.takeIf { it != context.packageName }
    } catch (error: RuntimeException) {
        null
    }

    /**
     * Builds the launch intent: the user's chosen browser when we still have it,
     * otherwise a plain VIEW intent so Android picks the default handler.
     */
    fun launchIntent(url: String, preferredPackage: String?): Intent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        if (!preferredPackage.isNullOrBlank()) intent.setPackage(preferredPackage)
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * Turns whatever the user typed into a URL: full URLs pass through, bare
 * hostnames get https://, everything else becomes a search query.
 * Pure function, so it is unit-tested.
 */
object Urls {
    private val HOST_LIKE = Regex("^[\\w\\-]+(\\.[\\w\\-]+)+(/\\S*)?$")

    fun parse(input: String, searchTemplate: String = "https://duckduckgo.com/?q=%s"): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return trimmed
        if (trimmed.startsWith("file://", true) || trimmed.startsWith("about:", true)) return trimmed
        if (!trimmed.contains(' ') && HOST_LIKE.matches(trimmed)) return "https://$trimmed"
        if (trimmed == "localhost" || trimmed.startsWith("localhost:")) return "http://$trimmed"
        return searchTemplate.replace("%s", URLEncoder.encode(trimmed, "UTF-8"))
    }
}
