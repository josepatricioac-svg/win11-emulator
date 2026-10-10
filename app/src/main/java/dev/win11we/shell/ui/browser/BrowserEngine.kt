package dev.win11we.shell.ui.browser

import androidx.annotation.DrawableRes
import dev.win11we.shell.R

/**
 * How the shell reaches the web.
 *
 * - [AndroidBrowser] hands the URL to an installed Android app (works today).
 * - [DesktopBrowser] runs a real PC browser inside the container and shows it on
 *   the shell's X server (needs R1 proot/box64, R3 rootfs, D1 X server). Those
 *   are the builds that expose desktop-only features - profile sync, extensions,
 *   sidebars, devtools - that no Android browser has.
 */
sealed interface BrowserEngine {
    val id: String
    val label: String

    data class AndroidBrowser(
        val packageName: String,
        override val label: String,
        val isSystemDefault: Boolean = false,
    ) : BrowserEngine {
        override val id: String get() = "android:$packageName"
    }

    /** [requirement] names the roadmap step that unlocks this engine. */
    data class DesktopBrowser(
        override val id: String,
        override val label: String,
        val detail: String,
        val license: String,
        val requirement: String,
        @DrawableRes val iconRes: Int = R.drawable.ic_app_browser,
    ) : BrowserEngine {
        val available: Boolean get() = false // flips to true when the runtime reports ready (R4)
    }

    /** Let Android pick whatever handles http(s). */
    data object SystemDefault : BrowserEngine {
        override val id: String get() = "android:"
        override val label: String get() = "System default browser"
    }
}

/**
 * Desktop engines we plan to offer. Nothing here is bundled in the APK: they are
 * downloaded into the container rootfs (R3) with pinned hashes and their licences
 * recorded in docs/THIRD_PARTY.md and docs/BROWSER_ENGINES.md.
 */
object DesktopBrowserCatalogue {
    val ALL: List<BrowserEngine.DesktopBrowser> = listOf(
        BrowserEngine.DesktopBrowser(
            id = "desktop:firefox-esr",
            label = "Firefox ESR (Linux arm64)",
            detail = "Native arm64 build from the Ubuntu 22.04 rootfs, X11 + PulseAudio. Full desktop profile, " +
                "extensions and sidebar - the closest thing to a PC Firefox on this device.",
            license = "MPL-2.0",
            requirement = "R1 proot + R3 rootfs, Firefox and GUI libs + D1 X server",
        ),
        BrowserEngine.DesktopBrowser(
            id = "desktop:edge-arm64",
            label = "Microsoft Edge (Windows arm64)",
            detail = "Native Windows/arm64 MSIX under proot (no box64 translation), so the Copilot sidebar and " +
                "Windows browser features run as built. Licence must be accepted at download time.",
            license = "Proprietary - user accept",
            requirement = "R1 proot + R3 rootfs + D2 per-window surfaces",
        ),
        BrowserEngine.DesktopBrowser(
            id = "desktop:firefox-wine",
            label = "Firefox (Windows x64 via Wine + box64)",
            detail = "Fallback where the Linux build cannot be used. Heavy and the least reliable of the three.",
            license = "MPL-2.0",
            requirement = "R1 box64 + R2 Wine + D1 X server",
        ),
        BrowserEngine.DesktopBrowser(
            id = "desktop:chromium-arm64",
            label = "Chromium (Linux arm64)",
            detail = "For sites that need a Blink engine inside the container.",
            license = "BSD-3-Clause",
            requirement = "R3 rootfs + D1 X server",
        ),
    )

    fun byId(id: String): BrowserEngine.DesktopBrowser? = ALL.firstOrNull { it.id == id }
}

/**
 * UI wording for a desktop engine's state, pure so it is unit-tested. The static
 * [BrowserEngine.DesktopBrowser.requirement] is only the fallback before the
 * readiness worker has reported; once it has, the exact missing step wins, and a
 * ready engine says so instead of repeating a stale "Needs ..." roadmap label.
 */
object DesktopEngineLabels {
    fun status(engine: BrowserEngine.DesktopBrowser, ready: Boolean, missing: String?): String = when {
        ready -> "Ready"
        missing != null -> missing
        else -> "Needs ${engine.requirement}"
    }

    fun badge(ready: Boolean): String = if (ready) "Ready" else "Not ready"
}

/**
 * Resolution rules for "which engine do we use", pure so they are unit-tested:
 * explicit choice when still valid, else Firefox on Android, else the system default.
 */
object EngineResolver {

    fun resolve(
        chosenId: String?,
        androidBrowsers: List<BrowserEngine.AndroidBrowser>,
        desktopReady: (String) -> Boolean = { false },
    ): BrowserEngine {
        val installed = androidBrowsers.map { it.packageName }
        return when {
            chosenId.isNullOrBlank() -> preferFirefox(androidBrowsers)
            chosenId == BrowserEngine.SystemDefault.id -> BrowserEngine.SystemDefault
            chosenId.startsWith("desktop:") ->
                DesktopBrowserCatalogue.byId(chosenId)?.takeIf { desktopReady(chosenId) } ?: preferFirefox(androidBrowsers)
            chosenId.startsWith("android:") -> {
                val packageName = chosenId.removePrefix("android:")
                if (packageName in installed) {
                    androidBrowsers.first { it.packageName == packageName }
                } else {
                    preferFirefox(androidBrowsers)
                }
            }
            else -> preferFirefox(androidBrowsers)
        }
    }

    /** Firefox first (our stated preference), then the system default, then anything installed. */
    private fun preferFirefox(browsers: List<BrowserEngine.AndroidBrowser>): BrowserEngine =
        browsers.firstOrNull { it.packageName == Browsers.PREFERRED_PACKAGE }
            ?: browsers.firstOrNull { it.isSystemDefault }
            ?: browsers.firstOrNull()
            ?: BrowserEngine.SystemDefault

    /** Package to put on the intent, or null for "let Android choose". */
    fun targetPackage(engine: BrowserEngine): String? = when (engine) {
        is BrowserEngine.AndroidBrowser -> engine.packageName
        BrowserEngine.SystemDefault -> null
        is BrowserEngine.DesktopBrowser -> null // launched inside the container instead (R2/D1)
    }
}
