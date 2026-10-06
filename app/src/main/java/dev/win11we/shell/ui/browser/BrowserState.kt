package dev.win11we.shell.ui.browser

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Per-window state of the Browser app: the address bar text, the resolved URL
 * and which package it would be handed to. The shell does not render web pages
 * itself - it drives a real browser (Firefox by preference).
 */
@Stable
class BrowserState(
    initialQuery: String = "",
    val installed: List<BrowserApp> = emptyList(),
    val preferredPackage: String? = null,
    /** Id of the engine chosen in Settings / the Browser window; blank = auto. */
    val chosenEngineId: String? = null,
    /** True once the container runtime reports a desktop browser is usable (R4). */
    val desktopEngineReady: (String) -> Boolean = { false },
) {
    /** Android browsers as engines, for the resolver. */
    fun engines(): List<BrowserEngine.AndroidBrowser> =
        installed.map { BrowserEngine.AndroidBrowser(it.packageName, it.label, it.isDefault) }

    /** The engine this window will use right now. */
    fun engine(): BrowserEngine =
        EngineResolver.resolve(chosenEngineId ?: preferredPackage?.let { "android:$it" }, engines(), desktopEngineReady)

    /** True when the chosen engine needs the container runtime and cannot launch yet. */
    fun engineBlocked(): Boolean = engine() is BrowserEngine.DesktopBrowser
    var query by mutableStateOf(initialQuery)
    var lastUrl by mutableStateOf<String?>(null)
        private set
    var lastTarget by mutableStateOf<String?>(null)
        private set
    var launchFailed by mutableStateOf(false)
        private set

    /** Package we would use: the user's choice when it is still installed, else Firefox, else the default. */
    fun targetPackage(): String? = EngineResolver.targetPackage(engine())
        ?: preferredPackage.takeIf { it in installed.map { browser -> browser.packageName } }

    fun targetLabel(): String =
        installed.firstOrNull { it.packageName == targetPackage() }?.label ?: "System default browser"

    /** Resolves the address bar into the intent that should be fired; null when empty. */
    fun resolve(): String? {
        val url = Urls.parse(query) ?: return null
        lastUrl = url
        lastTarget = targetPackage()
        launchFailed = false
        return url
    }

    fun markLaunchFailed() {
        launchFailed = true
    }

    fun chooseBrowser(packageName: String) {
        lastTarget = packageName
    }

    fun history(): List<String> = listOfNotNull(lastUrl)
}
