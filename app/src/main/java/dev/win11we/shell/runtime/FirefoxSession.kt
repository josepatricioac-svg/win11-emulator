package dev.win11we.shell.runtime

import java.io.File

/**
 * Firefox ESR (Linux arm64) as the shell's desktop browser engine: the
 * guest-side facts and the exact launch plan. Everything here is pure - command
 * lines, environment, bind mounts, install checks - so all of it is unit-tested
 * without Android, like [ContainerLauncher] and [ProotCommand].
 *
 * Model: ONE firefox instance per shell (a "session"), tracked by R2's
 * [ContainerLauncher] under [SESSION_ID] - it is not a Wine container: no box64,
 * no prefix, no wineboot. The persistent profile is its own host directory bound
 * over the guest's `~/.mozilla`, so cookies, extensions and downloads survive
 * app restarts. The engine itself is Mozilla's official self-contained
 * linux-aarch64 build (R3 artifact `firefox-esr`), unpacked to
 * `<rootfs>/opt/firefox-esr`.
 *
 * Known limits while gated (all named in the readiness message, nothing silent):
 * - one session at a time; re-launching while running answers ALREADY_RUNNING
 *   (a guest D-Bus session bus for remote "open in running instance" is P1),
 * - audio waits on P1's PulseAudio socket,
 * - the rootfs must carry the GTK3/dbus-glib stack firefox links against; that
 *   provisioning is the same dependency-tree step Wine needs (R4/P2).
 */
object FirefoxSession {
    /** Launcher-visible id under which the session is tracked. */
    const val SESSION_ID = "desktop-browser-firefox-esr"
    const val SESSION_NAME = "Firefox ESR (desktop browser)"

    /** The engine id in [dev.win11we.shell.ui.browser.DesktopBrowserCatalogue]. */
    const val ENGINE_ID = "desktop:firefox-esr"

    /** Where R3's extractor unpacks Mozilla's tarball (with --strip-components=1). */
    const val GUEST_INSTALL_DIR = "/opt/firefox-esr"

    /** The profile's bind target: firefox's default location under $HOME=/root. */
    const val GUEST_PROFILE_DIR = "/root/.mozilla"

    /** The X11 socket directory as the guest sees it (bind of the host dir). */
    const val GUEST_X11_DIR = "/tmp/.X11-unix"

    /** The named profile kept inside [GUEST_PROFILE_DIR]. */
    const val PROFILE_NAME = "shell"

    /** Guest command for one session; the URL (if any) is opened on first start. */
    fun guestCommand(url: String? = null): List<String> =
        listOf("$GUEST_INSTALL_DIR/firefox", "-P", PROFILE_NAME) +
            listOfNotNull(url?.takeIf { it.isNotBlank() })

    /** Bind mounts for one session: persistent profile + the X socket directory. */
    fun binds(profileDir: File, xSocketUnixDir: File): List<Bind> = listOf(
        Bind(profileDir.absolutePath, GUEST_PROFILE_DIR),
        Bind(xSocketUnixDir.absolutePath, GUEST_X11_DIR),
    )

    /** True when the verified tarball has been unpacked into place. */
    fun isInstalled(rootfsDir: File): Boolean = File(rootfsDir, "opt/firefox-esr/firefox").isFile

    /**
     * Mozilla's build bundles its own libs but links the rootfs's GTK3 stack;
     * the bare Ubuntu base rootfs has none. Same provisioning gap as Wine's
     * dependency tree - readiness reports it instead of failing silently.
     */
    fun guiLibrariesPresent(rootfsDir: File): Boolean =
        File(rootfsDir, "usr/lib/aarch64-linux-gnu/libgtk-3.so.0").exists()
}

/**
 * The exact environment for a firefox session - fully explicit like
 * [dev.win11we.shell.container.WineEnvironment] and sorted so Diagnostics can
 * diff it. The process sandboxes are disabled through Mozilla's own env
 * switches: nested namespaces/seccomp cannot work inside proot, which is the
 * standard practice for every proot-based Firefox deployment. An unknown switch
 * is ignored by firefox, so the list errs on the complete side.
 */
object FirefoxEnvironment {
    fun build(
        xDisplay: String,
        locale: String = "C.UTF-8",
        /** Guest path of the PulseAudio socket; audio arrives with P1. */
        guestAudioSocket: String? = null,
    ): Map<String, String> = buildMap {
        put("HOME", "/root")
        put("DISPLAY", xDisplay)
        put(
            "PATH",
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:" + FirefoxSession.GUEST_INSTALL_DIR,
        )
        put("LANG", locale)
        put("LC_ALL", locale)
        put("XDG_RUNTIME_DIR", "/tmp")
        put("XDG_DATA_HOME", "/root/.local/share")
        put("XDG_CONFIG_HOME", "/root/.config")
        put("XDG_CACHE_HOME", "/root/.cache")
        put("MOZ_ENABLE_WAYLAND", "0") // X11 only: the shell's own X server
        put("MOZ_DISABLE_CONTENT_SANDBOX", "1")
        put("MOZ_DISABLE_GMP_SANDBOX", "1")
        put("MOZ_DISABLE_RDD_SANDBOX", "1")
        put("MOZ_DISABLE_NPAPI_SANDBOX", "1")
        put("MOZ_DISABLE_SOCKET_SANDBOX", "1")
        guestAudioSocket?.let { put("PULSE_SERVER", "unix:$it") }
    }.toSortedMap()
}
