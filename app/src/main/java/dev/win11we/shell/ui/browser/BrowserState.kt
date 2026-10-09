package dev.win11we.shell.ui.browser

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Result of handing a URL to a desktop engine; the message is UI-ready. */
data class DesktopLaunchOutcome(val success: Boolean, val message: String)

/**
 * Per-window state of the Browser app: the address bar text, the resolved URL
 * and which package it would be handed to. The shell renders no web content
 * itself - it drives a real browser: an Android one via intent, or (D1/R3)
 * Firefox ESR inside the rootfs on the shell's own X server.
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
    /**
     * Launches a ready desktop engine inside the container; null where the shell
     * cannot launch (pure tests) - a ready engine without a launcher stays
     * blocked, exactly like the roadmap-gating contract requires.
     */
    val launchDesktopBrowser: ((engineId: String, url: String) -> DesktopLaunchOutcome)? = null,
) {
    /** Android browsers as engines, for the resolver. */
    fun engines(): List<BrowserEngine.AndroidBrowser> =
        installed.map { BrowserEngine.AndroidBrowser(it.packageName, it.label, it.isDefault) }

    /** The engine this window will use right now. */
    fun engine(): BrowserEngine =
        EngineResolver.resolve(chosenEngineId ?: preferredPackage?.let { "android:$it" }, engines(), desktopEngineReady)

    /** True when the chosen desktop engine can actually be launched by the shell now. */
    fun desktopLaunchable(): Boolean {
        val engine = engine()
        return engine is BrowserEngine.DesktopBrowser &&
            desktopEngineReady(engine.id) && launchDesktopBrowser != null
    }

    /** True when the chosen engine needs the container runtime and cannot launch yet. */
    fun engineBlocked(): Boolean = engine() is BrowserEngine.DesktopBrowser && !desktopLaunchable()
    var query by mutableStateOf(initialQuery)
    var lastUrl by mutableStateOf<String?>(null)
        private set
    var lastTarget by mutableStateOf<String?>(null)
        private set
    var launchFailed by mutableStateOf(false)
        private set

    /** Last desktop-launch report (success or the named refusal), shown in the window. */
    var desktopMessage by mutableStateOf<String?>(null)
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

    /**
     * Resolves the address bar and hands the URL to the desktop engine's
     * launcher. @return whether the launch was accepted; [desktopMessage]
     * always says what happened, including the named missing step.
     */
    fun launchDesktop(): Boolean {
        val engine = engine()
        if (engine !is BrowserEngine.DesktopBrowser || !desktopLaunchable()) return false
        val url = Urls.parse(query) ?: return false
        val outcome = launchDesktopBrowser?.invoke(engine.id, url)
            ?: return false
        lastUrl = url
        launchFailed = !outcome.success
        desktopMessage = outcome.message
        if (outcome.success) lastTarget = engine.id
        return outcome.success
    }

    fun markLaunchFailed() {
        launchFailed = true
    }

    fun chooseBrowser(packageName: String) {
        lastTarget = packageName
    }

    fun history(): List<String> = listOfNotNull(lastUrl)
}
