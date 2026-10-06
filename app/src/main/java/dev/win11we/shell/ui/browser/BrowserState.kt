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
) {
    var query by mutableStateOf(initialQuery)
    var lastUrl by mutableStateOf<String?>(null)
        private set
    var lastTarget by mutableStateOf<String?>(null)
        private set
    var launchFailed by mutableStateOf(false)
        private set

    /** Package we would use: the user's choice when it is still installed, else Firefox, else the default. */
    fun targetPackage(): String? {
        val packages = installed.map { it.packageName }
        return when {
            !preferredPackage.isNullOrBlank() && preferredPackage in packages -> preferredPackage
            Browsers.PREFERRED_PACKAGE in packages -> Browsers.PREFERRED_PACKAGE
            else -> installed.firstOrNull { it.isDefault }?.packageName ?: packages.firstOrNull()
        }
    }

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
