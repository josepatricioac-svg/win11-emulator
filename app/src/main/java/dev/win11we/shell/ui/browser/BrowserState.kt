package dev.win11we.shell.ui.browser

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Result of handing a URL to a desktop engine; the message is UI-ready. */
data class DesktopLaunchOutcome(val success: Boolean, val message: String)

/**
 * Per-window state of the Browser app: the address bar text, the resolved URL
 * and the desktop engine it will be handed to. The shell renders no web
 * content itself and hands nothing to Android: the engine runs inside the
 * rootfs on the X screen that IS the desktop (D1/R3), so a launched browser
 * window appears directly on the desktop - there is no viewer and no intent.
 */
@Stable
class BrowserState(
    initialQuery: String = "",
    /** Id of the engine chosen in Settings / the Browser window; null/blank = [EngineResolver.DEFAULT]. */
    val chosenEngineId: String? = null,
    /** True once the container runtime reports the engine is usable. */
    val desktopEngineReady: (String) -> Boolean = { false },
    /**
     * Launches the chosen engine inside the container; null where the shell
     * cannot launch (pure tests) - a ready engine without a launcher stays
     * blocked, exactly like the roadmap-gating contract requires.
     */
    val launchDesktopBrowser: ((engineId: String, url: String) -> DesktopLaunchOutcome)? = null,
) {
    /** The engine this window will use right now - always a desktop engine. */
    fun engine(): BrowserEngine.DesktopBrowser = EngineResolver.resolve(chosenEngineId)

    /** True when the chosen engine is ready AND the shell has a launcher for it. */
    fun desktopLaunchable(): Boolean = desktopEngineReady(engine().id) && launchDesktopBrowser != null

    /** True when the engine cannot launch yet. Nothing silently replaces it. */
    fun engineBlocked(): Boolean = !desktopLaunchable()

    /**
     * The named missing step while blocked, so the UI never pretends a blocked
     * engine works. [missingStep] is typically `DesktopEngineReadiness.missing`;
     * before the readiness worker has reported, the engine's own roadmap
     * requirement is quoted.
     */
    fun blockingNote(missingStep: (String) -> String? = { null }): String? {
        if (desktopLaunchable()) return null
        return missingStep(engine().id) ?: "needs ${engine().requirement}"
    }

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

    /** Resolves the address bar into the URL that should be launched; null when empty. */
    fun resolve(): String? {
        val url = Urls.parse(query) ?: return null
        lastUrl = url
        lastTarget = engine().id
        launchFailed = false
        return url
    }

    /**
     * Resolves the address bar and hands the URL to the desktop engine's
     * launcher. @return whether the launch was accepted; [desktopMessage]
     * always says what happened, including the named missing step.
     */
    fun launchDesktop(): Boolean {
        if (!desktopLaunchable()) return false
        val url = Urls.parse(query) ?: return false
        val outcome = launchDesktopBrowser?.invoke(engine().id, url) ?: return false
        lastUrl = url
        launchFailed = !outcome.success
        desktopMessage = outcome.message
        if (outcome.success) lastTarget = engine().id
        return outcome.success
    }

    /** UI feedback when the user picks an engine card. */
    fun chooseEngine(id: String) {
        lastTarget = id
    }

    fun history(): List<String> = listOfNotNull(lastUrl)
}
