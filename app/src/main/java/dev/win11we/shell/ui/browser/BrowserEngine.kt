package dev.win11we.shell.ui.browser

import androidx.annotation.DrawableRes
import dev.win11we.shell.R

/**
 * How the shell reaches the web: ALWAYS a desktop engine inside the container.
 *
 * The Android-browser family (PackageManager detection, ACTION_VIEW hand-offs,
 * the "system default" engine and the silent fallbacks around them) was
 * REMOVED on owner decision: this shell's point is that browsing happens
 * inside the emulator, on the X screen that IS the desktop. Detection added a
 * second story, a `<queries>` manifest block, and a silent fallback that
 * masked an unready runtime - the opposite of the gating contract. An engine
 * that is not ready is now shown blocked with its named missing step, never
 * quietly rerouted off-device.
 */
sealed interface BrowserEngine {
    val id: String
    val label: String

    /**
     * A real PC browser running inside the rootfs on the shell's own X server
     * (R1 proot/box64, R3 rootfs, D1 display). Nothing is bundled in the APK:
     * engines are downloaded into the container with pinned hashes and their
     * licences recorded in docs/THIRD_PARTY.md and docs/BROWSER_ENGINES.md.
     */
    data class DesktopBrowser(
        override val id: String,
        override val label: String,
        val detail: String,
        val license: String,
        /** Names the roadmap step that unlocks this engine (fallback label). */
        val requirement: String,
        @DrawableRes val iconRes: Int = R.drawable.ic_app_browser,
    ) : BrowserEngine {
        val available: Boolean get() = false // flips to true when the runtime reports ready (R4)
    }
}

/**
 * Desktop engines we plan to offer.
 */
object DesktopBrowserCatalogue {
    val ALL: List<BrowserEngine.DesktopBrowser> = listOf(
        BrowserEngine.DesktopBrowser(
            id = "desktop:firefox-esr",
            label = "Firefox ESR (Linux arm64)",
            detail = "Native arm64 build from the Ubuntu 24.04 rootfs, X11 + PulseAudio. Full desktop profile, " +
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

    fun byId(id: String?): BrowserEngine.DesktopBrowser? = ALL.firstOrNull { it.id == id }
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
 * the explicit choice when it is a catalogued engine, else [DEFAULT]. Readiness
 * is NOT part of the choice - an unready engine stays chosen and is reported
 * blocked with its named missing step ([BrowserState.blockingNote]); the old
 * resolver silently fell back to an Android browser here, which no longer
 * exists by design.
 */
object EngineResolver {

    /** The engine the shell offers out of the box: the fully-plumbed Firefox ESR. */
    const val DEFAULT_ENGINE_ID = "desktop:firefox-esr"

    val DEFAULT: BrowserEngine.DesktopBrowser
        get() = DesktopBrowserCatalogue.byId(DEFAULT_ENGINE_ID)!!

    fun resolve(chosenId: String?): BrowserEngine.DesktopBrowser =
        DesktopBrowserCatalogue.byId(chosenId) ?: DEFAULT
}
