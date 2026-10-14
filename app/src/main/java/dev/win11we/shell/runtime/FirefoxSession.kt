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

    /**
     * The session D-Bus socket, at a FIXED guest path so a second invocation can
     * find the running instance's bus without any state on the host: firefox's
     * remote client talks "org.mozilla.firefox.*" over this bus, which is how a
     * later URL reaches the ALREADY-RUNNING session instead of fighting the
     * profile lock.
     */
    const val GUEST_DBUS_SOCKET = "/tmp/shell-dbus-session"
    const val GUEST_DBUS_ADDRESS = "unix:path=$GUEST_DBUS_SOCKET"

    /** POSIX single-quote escaping - the URL is user input, never interpolated raw. */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * The bash prelude every session runs through: probe the fixed bus address,
     * start a private `dbus-daemon --session` only when it is dead (removing a
     * stale socket a SIGKILL may have left), wait briefly for it, export the
     * address and `exec` firefox. When the `dbus` package is not provisioned the
     * prelude degrades silently to "no bus": firefox still runs, only the
     * remote handoff to a running instance is unavailable.
     */
    fun dbusBootstrapScript(firefoxCommand: List<String>, withDesktop: Boolean = true): String {
        val exec = firefoxCommand.joinToString(" ") { shellQuote(it) }
        return buildString {
            appendLine("ADDR='$GUEST_DBUS_ADDRESS'")
            appendLine("SOCK='$GUEST_DBUS_SOCKET'")
            appendLine("probe() { dbus-send --address=\"\$ADDR\" --dest=org.freedesktop.DBus " +
                "--type=method_call --print-reply /org/freedesktop/DBus org.freedesktop.DBus.GetId " +
                ">/dev/null 2>&1; }")
            appendLine("if ! probe && command -v dbus-daemon >/dev/null 2>&1; then")
            appendLine("  rm -f \"\$SOCK\"")
            appendLine("  dbus-daemon --session --address=\"\$ADDR\" --nofork >/dev/null 2>&1 &")
            appendLine("  i=0")
            appendLine("  while [ \$i -lt 50 ]; do probe && break; i=\$((i+1)); sleep 0.1; done")
            appendLine("fi")
            appendLine("export DBUS_SESSION_BUS_ADDRESS=\"\$ADDR\"")
            if (withDesktop) append(desktopPrelude())
            append("exec $exec")
        }
    }

    /**
     * The desktop-session prelude: start openbox as the guest window manager
     * (the shell's X server runs pass-through - it does NOT claim
     * SubstructureRedirect, so the session brings its own WM and the whole
     * desktop, frames and all, renders inside the X Desktop surface), and give
     * it up to ~5 s to announce itself through _NET_SUPPORTING_WM_CHECK before
     * Firefox maps its first window. Without openbox provisioned the session
     * still runs: windows map undecorated, and readiness names the missing
     * piece rather than the launch failing.
     */
    fun desktopPrelude(): String = buildString {
        appendLine("if command -v openbox >/dev/null 2>&1; then")
        appendLine("  openbox >/dev/null 2>&1 &")
        appendLine("  i=0")
        appendLine("  while [ \$i -lt 20 ]; do")
        appendLine("    xprop -root _NET_SUPPORTING_WM_CHECK 2>/dev/null | grep -q 'window id' && break")
        appendLine("    i=\$((i+1))")
        appendLine("    sleep 0.25")
        appendLine("  done")
        appendLine("fi")
    }

    /**
     * What the launcher actually execs: [guestCommand] wrapped in the D-Bus
     * bootstrap and (for a fresh session) the openbox desktop prelude. Same
     * command for the first launch and for handing a URL to the running
     * instance - with a live bus, firefox's own remote client forwards the URL
     * to the running session and exits; without one it starts fresh. A handoff
     * passes [withDesktop] = false: the session (and its WM) is already up.
     */
    fun sessionCommand(url: String? = null, withDesktop: Boolean = true): List<String> =
        listOf("/bin/bash", "-c", dbusBootstrapScript(guestCommand(url), withDesktop))

    /** Bind mounts for one session: persistent profile, X socket dir and guest DNS. */
    fun binds(profileDir: File, xSocketUnixDir: File, resolvConf: File): List<Bind> = listOf(
        Bind(profileDir.absolutePath, GUEST_PROFILE_DIR),
        Bind(xSocketUnixDir.absolutePath, GUEST_X11_DIR),
        // Android has no /etc/resolv.conf for the jail: the shell writes one
        // (public resolvers, see RootfsProvisioner) and binds it over the guest's.
        Bind(resolvConf.absolutePath, "/etc/resolv.conf"),
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
        // proot ptraces EVERY guest process; multi-process Firefox pays that per
        // fork, which is most of the "takes forever" first load. Single-process
        // mode is the standard proot-Firefox remedy; a Firefox that no longer
        // honours the switch simply ignores it.
        put("MOZ_FORCE_DISABLE_E10S", "1")
        put("MOZ_DISABLE_CONTENT_SANDBOX", "1")
        put("MOZ_DISABLE_GMP_SANDBOX", "1")
        put("MOZ_DISABLE_RDD_SANDBOX", "1")
        put("MOZ_DISABLE_NPAPI_SANDBOX", "1")
        put("MOZ_DISABLE_SOCKET_SANDBOX", "1")
        guestAudioSocket?.let { put("PULSE_SERVER", "unix:$it") }
    }.toSortedMap()
}
