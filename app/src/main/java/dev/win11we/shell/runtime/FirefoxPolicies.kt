package dev.win11we.shell.runtime

import java.io.File

/**
 * First-load speed and background-traffic honesty for the Firefox ESR session:
 * the enterprise policies and profile prefs the shell materialises before a
 * session starts (Goal 2 step 2).
 *
 * Why files, not command-line switches: Firefox ESR reads
 * `distribution/policies.json` from the *application* directory - here the
 * extracted R3 artifact at `<rootfs>/opt/firefox-esr` - and `user.js` from the
 * profile directory. Policies are enforced (the UI shows them read-only under
 * about:policies); `user.js` seeds the same defaults for the pieces the policy
 * engine does not cover. `profiles.ini` is written up front so `-P shell`
 * resolves the persistent profile deterministically instead of racing
 * first-run profile creation.
 *
 * What gets turned OFF (every key pinned by `FirefoxPoliciesTest`): telemetry,
 * studies/normandy, health-report upload, application auto-update (manual
 * update only - this is an ESR build), the whole safe-browsing family incl.
 * its remote lookups (on a fresh profile each of those waits on a network
 * round trip before the first page paints - the biggest first-load stallers),
 * Pocket, sponsored new-tab stories, first-run/welcome pages, the
 * default-browser check, background thumbnail captures.
 * What stays ON deliberately (speed): DNS prefetch, link prefetch.
 *
 * Pure file content + idempotent writes; unit-tested without Android.
 */
object FirefoxPolicies {

    /** policies.json location relative to the Firefox install dir (guest paths minus '/'). */
    const val POLICIES_RELATIVE = "distribution/policies.json"

    /** profiles.ini + the named profile live under `<profile bind>/firefox/`. */
    const val MOZILLA_SUBDIR = "firefox"

    fun profilesIni(profileName: String): String = buildString {
        appendLine("[Profile0]")
        appendLine("Name=$profileName")
        appendLine("IsRelative=1")
        appendLine("Path=$profileName")
        appendLine("Default=1")
        appendLine()
        appendLine("[General]")
        appendLine("StartWithLastProfile=1")
        appendLine("Version=2")
    }

    /**
     * The `user.js` seeded into the profile. Ordered: telemetry/studies first,
     * then safe-browsing, updates, UI quieting, and finally the two network
     * prefs we keep enabled on purpose (documented in the class KDoc).
     */
    fun userJs(): String = listOf(
        "toolkit.telemetry.enabled" to "false",
        "toolkit.telemetry.unified" to "false",
        "toolkit.telemetry.archive.enabled" to "false",
        "toolkit.telemetry.newProfilePing.enabled" to "false",
        "toolkit.telemetry.shutdownPingSender.enabled" to "false",
        "toolkit.telemetry.updatePing.enabled" to "false",
        "toolkit.telemetry.bhrPing.enabled" to "false",
        "toolkit.telemetry.firstShutdownPing.enabled" to "false",
        "datareporting.healthreport.uploadEnabled" to "false",
        "datareporting.policy.dataSubmissionEnabled" to "false",
        "app.shield.optoutstudies.enabled" to "false",
        "app.normandy.enabled" to "false",
        "browser.safebrowsing.malware.enabled" to "false",
        "browser.safebrowsing.phishing.enabled" to "false",
        "browser.safebrowsing.downloads.enabled" to "false",
        "browser.safebrowsing.downloads.remote.enabled" to "false",
        "browser.safebrowsing.downloads.remote.block_potentially_unwanted" to "false",
        "browser.safebrowsing.downloads.remote.block_uncommon" to "false",
        "app.update.auto" to "false",
        "extensions.pocket.enabled" to "false",
        "extensions.pocket.site" to "false",
        "browser.shell.checkDefaultBrowser" to "false",
        "browser.startup.homepage_override.mstone" to "\"ignore\"",
        "startup.homepage_welcome_url" to "\"about:blank\"",
        "startup.homepage_welcome_url.additional" to "\"\"",
        "browser.aboutwelcome.enabled" to "false",
        "browser.newtabpage.activity-stream.showSponsored" to "false",
        "browser.newtabpage.activity-stream.feeds.section.topstories" to "false",
        "browser.newtabpage.activity-stream.feeds.snippets" to "false",
        "browser.pagethumbnails.capturing_disabled" to "true",
        // --- software rendering guards (Phase-2A white-screen fix) -----------
        // The embedded X server has no GLX/DRI/Vulkan - it is a software
        // renderer by design (docs/X_SERVER.md). Firefox probing for GPU
        // acceleration inside the jail is a known white-window/stalled-paint
        // source on proot stacks, so hardware compositing and WebRender are
        // pinned OFF and the basic software path is pinned ON. These match the
        // LIBGL_ALWAYS_SOFTWARE/GALLIUM_DRIVER env in FirefoxEnvironment.
        "layers.acceleration.disabled" to "true",
        "gfx.webrender.all" to "false",
        "gfx.webrender.software" to "false",
        "media.ffmpeg.vaapi.enabled" to "false",
        "network.dns.disablePrefetch" to "false",
        "network.prefetch-next" to "true",
    ).joinToString(separator = "\n", postfix = "\n") { (key, value) ->
        "user_pref(\"$key\", $value);"
    }

    /**
     * The enterprise policies. Hand-built JSON (no serializer dependency), and
     * the exact text is pinned by the test - Firefox rejects the whole file on
     * a syntax error, so this string is part of the contract.
     */
    fun policiesJson(): String = """{
  "policies": {
    "DisableTelemetry": true,
    "DisableFirefoxStudies": true,
    "DisableAppUpdate": true,
    "ManualAppUpdateOnly": true,
    "DisablePocket": true,
    "DontCheckDefaultBrowser": true,
    "DisableProfileImport": true,
    "DisableSetDesktopBackground": true,
    "OfferToSaveLogins": false,
    "PasswordManagerEnabled": false,
    "OverrideFirstRunPage": "about:blank",
    "OverridePostUpdatePage": "about:blank",
    "NetworkPrediction": true,
    "Homepage": {
      "StartPage": "none"
    },
    "Preferences": {
      "browser.safebrowsing.malware.enabled": { "Value": false, "Status": "locked" },
      "browser.safebrowsing.phishing.enabled": { "Value": false, "Status": "locked" },
      "browser.safebrowsing.downloads.enabled": { "Value": false, "Status": "locked" },
      "browser.safebrowsing.downloads.remote.enabled": { "Value": false, "Status": "locked" },
      "datareporting.healthreport.uploadEnabled": { "Value": false, "Status": "locked" },
      "datareporting.policy.dataSubmissionEnabled": { "Value": false, "Status": "locked" },
      "app.update.auto": { "Value": false, "Status": "locked" },
      "extensions.pocket.enabled": { "Value": false, "Status": "locked" },
      "browser.newtabpage.activity-stream.showSponsored": { "Value": false, "Status": "locked" },
      "network.dns.disablePrefetch": { "Value": false, "Status": "default" },
      "network.prefetch-next": { "Value": true, "Status": "default" }
    }
  }
}
"""

    /** One file [materialize] writes: absolute destination + exact content. */
    data class Target(val file: File, val content: String)

    /**
     * The files for one session start. [rootfsDir] is the extracted rootfs,
     * [profileDir] the host directory bound over `/root/.mozilla`. policies.json
     * is only targeted when Firefox is actually installed (writing into a
     * missing `/opt/firefox-esr` would create a directory the extractor later
     * has to merge with - skip it honestly instead).
     */
    fun targets(
        rootfsDir: File,
        profileDir: File,
        profileName: String = FirefoxSession.PROFILE_NAME,
    ): List<Target> {
        val out = mutableListOf<Target>()
        val installDir = File(rootfsDir, FirefoxSession.GUEST_INSTALL_DIR.trimStart('/'))
        if (installDir.isDirectory) {
            out += Target(File(installDir, POLICIES_RELATIVE), policiesJson())
        }
        val mozilla = File(profileDir, MOZILLA_SUBDIR)
        out += Target(File(mozilla, "profiles.ini"), profilesIni(profileName))
        out += Target(File(File(mozilla, profileName), "user.js"), userJs())
        return out
    }

    /**
     * Idempotent write: a file whose content already matches is left alone (no
     * mtime churn, no re-read cost on later starts). @return the files actually
     * written, for the shared log line.
     */
    fun materialize(
        rootfsDir: File,
        profileDir: File,
        profileName: String = FirefoxSession.PROFILE_NAME,
    ): List<File> {
        val written = mutableListOf<File>()
        for (target in targets(rootfsDir, profileDir, profileName)) {
            if (target.file.isFile && target.file.readText() == target.content) continue
            target.file.parentFile?.mkdirs()
            target.file.writeText(target.content)
            written += target.file
        }
        return written
    }
}
