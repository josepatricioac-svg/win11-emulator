package dev.win11we.shell.runtime

import dev.win11we.shell.ui.terminal.LineKind
import dev.win11we.shell.ui.terminal.LogBuffer
import dev.win11we.shell.ui.terminal.TerminalLine
import java.io.File
import java.nio.file.Files

data class ProvisionResult(
    val success: Boolean,
    val message: String,
    /** Last [RootfsProvisioner.TAIL_LINES] non-blank apt/proot lines, for the failure card. */
    val tailLines: List<String> = emptyList(),
)

/**
 * Provisions the rootfs's GUI/network stack with `apt-get` INSIDE proot - the
 * step the bare Ubuntu base rootfs needs before Firefox ESR (and later Wine's
 * library tree) can run: GTK3 + its X11 stack, D-Bus, ALSA, fonts, CA certs.
 *
 * Why apt inside the jail instead of pinned `.deb` artifacts: the dependency
 * closure of libgtk-3-0 is 60+ packages that Ubuntu re-publishes with every
 * point release; pinning each one would be a maintenance trap. The R3
 * fail-closed contract still covers everything the shell itself downloads
 * (rootfs, Firefox); provisioning resolves distro-side dependencies the same
 * way every proot-based distro tool does. The package LIST is pinned here and
 * shown in the UI, and every run is logged to the shared log (Terminal ▸ log).
 *
 * Pure command/environment building + the [ProcessRunner] seam, so it is
 * unit-tested without spawning anything.
 */
object RootfsProvisioner {

    /** How many trailing apt lines a failure reports in the card and the shared log. */
    const val TAIL_LINES = 30

    /** The Firefox ESR runtime set; apt resolves the transitive closure. */
    val BROWSER_PACKAGES = listOf(
        "libgtk-3-0", // GTK3 + gdk-pixbuf, pango, cairo, atk, the X11 libs...
        // libdbus-glib-1-2 (NOT libdbus-glib-1-2-2: the "-N" suffix convention of
        // libdbus-1-3 does not carry over - verified against the jammy arm64 index
        // on 2026-10-12, where the suffixed name does not exist and one bad name
        // made the whole apt-get install fail with exit 100).
        "libdbus-glib-1-2", // Firefox's D-Bus bindings
        "libdbus-1-3",
        "libasound2", // audio backend (PulseAudio bridge arrives with P1)
        "libxt6", // X Toolkit, still used by Gecko
        "libx11-xcb1",
        "libegl1", // probed by Gecko; software rendering falls back cleanly
        "libgl1",
        "x11-apps", // xeyes/xclock: the X self-test's proof-of-contact clients
        "x11-utils", // xdpyinfo: the self-test's fast handshake + extension probe
        // gtk-query-settings: the self-test's GTK3 probe. xeyes/xclock are core X11
        // and Xaw, so they can pass while GTK3 - the toolkit Firefox is built on -
        // still dies opening the display. This one binary (a few hundred KB, and its
        // only dependency is the libgtk-3-0 already listed) calls gtk_init() and
        // prints GTK's own diagnosis when the server answers something it cannot use.
        "libgtk-3-bin",
        // The guest window manager: the X server runs pass-through (the shell
        // does not claim SubstructureRedirect), so the desktop session brings
        // its own WM. Openbox is ~1 MB, pulls no panel/desktop dependencies and
        // is a full EWMH citizen (frames, _NET_WM_SYNC_REQUEST, focus).
        "openbox",
        "fontconfig",
        "fonts-dejavu-core",
        "fonts-liberation", // metric-compatible Arial/Times stand-ins
        "ca-certificates", // TLS roots for the web
        "dbus", // dbus-daemon: the Firefox session's private D-Bus bus
        "dbus-x11", // dbus-launch/dbus-send: bus probing in the session script
    )

    /**
     * Guest DNS. Android has no /etc/resolv.conf for the jail to read, so the
     * shell writes one and binds it over the guest's. Public resolvers, in the
     * clear: DNS-over-TLS is the network's job, not ours (documented).
     */
    fun resolvConfLines(): List<String> = listOf("nameserver 1.1.1.1", "nameserver 9.9.9.9", "nameserver 8.8.8.8")

    fun ensureResolvConf(file: File): File {
        file.parentFile?.mkdirs()
        file.writeText(resolvConfLines().joinToString("\n") + "\n")
        return file
    }

    /**
     * Writes the guest DNS file INTO the rootfs as a real regular file. The
     * pinned ubuntu-base ships an empty regular /etc/resolv.conf (the proot bind
     * over it works), but SAF-imported rootfs images may carry systemd-resolved's
     * (dangling) symlink instead, and a bind cannot fix a dangling target:
     * replace any symlink with a real file so guest DNS never depends on bind
     * semantics. The bind in [command] stays as the refresh path.
     */
    fun installResolvConf(rootfsDir: File): File {
        val target = File(rootfsDir, "etc/resolv.conf")
        target.parentFile?.mkdirs()
        if (Files.isSymbolicLink(target.toPath())) target.delete()
        target.writeText(resolvConfLines().joinToString("\n") + "\n")
        return target
    }

    /**
     * apt/dpkg options every in-proot apt run needs (shared with
     * [WineProvisioner], which installs the Wine payload under the same rules):
     * - `APT::Sandbox::User=root`: apt drops to its `_apt` user for fetching;
     *   under proot's fake root every jail file appears root-owned, so `_apt`
     *   (uid 100) can write nowhere and fetches fail. There is no real privilege
     *   boundary to protect - the whole jail runs as the app uid - so disabling
     *   the drop is the standard proot-distro practice.
     * - `--force-unsafe-io`: skips dpkg's defensive syncs (crippling on flash +
     *   ptrace) - what every container tool sets.
     * - `--force-confold`: never prompt about config files; noninteractive cannot
     *   catch every debconf path.
     */
    const val APT_OPTIONS = "-o APT::Sandbox::User=root " +
        "-o Dpkg::Options::=--force-unsafe-io " +
        "-o Dpkg::Options::=--force-confold"

    /**
     * The proot prefix every in-jail command shares - provision AND the doctor's
     * smoke test - so the smoke really exercises the same argument form, binds
     * and fake root that apt will.
     *
     * TWO argument-form rules the vendored termux/proot imposes (both read out of
     * its own `src/cli/cli.c` + generated `src/cli/proot.h` at tag v5.1.107.96,
     * and both confirmed by a device failure):
     *
     * 1. Short options only for detached values. `-r`/`-b`/`-w` are declared with
     *    `separator = ' '`, so the value may be the next argument; `--rootfs`,
     *    `--bind`, `--cwd`, `--pwd` are declared with `separator = '='` and die
     *    with "option '--rootfs' and its value must be separated by '='".
     * 2. **There is no `--` end-of-options marker.** The parser's option table has
     *    no such entry, so `--` falls through to `unknown option '--'` and proot
     *    aborts. Parsing simply *stops* at the first argument that does not begin
     *    with '-', which is why the guest command follows the last option directly
     *    (and why it must never begin with '-'; [ProotCommand.build] guards that).
     *
     * Fake root (`-0` == `-i 0:0`). Without it dpkg refuses outright ("requested
     * operation requires superuser privilege") and apt cannot take its locks:
     * proot's own help text names package managers as -0's use case. It fakes
     * credentials and ownership only - everything still really runs as the
     * app uid.
     */
    fun prootPrefix(prootBinary: File, rootfsDir: File, resolvConf: File): List<String> = listOf(
        prootBinary.absolutePath,
        "-r", rootfsDir.absolutePath,
        "-0",
        "--link2symlink",
        "--kill-on-exit",
        "-b", "/proc:/proc",
        "-b", "/sys:/sys",
        "-b", "/dev:/dev",
        "-b", "${resolvConf.absolutePath}:/etc/resolv.conf",
        "-w", "/root",
        // NO "--" here: termux/proot has no end-of-options marker (see above).
    )

    /** The exact proot command line: apt-get update && install && clean, as fake root. */
    fun command(
        prootBinary: File,
        rootfsDir: File,
        resolvConf: File,
        packages: List<String> = BROWSER_PACKAGES,
    ): List<String> = prootPrefix(prootBinary, rootfsDir, resolvConf) + listOf(
        // No "--": see prootPrefix's rule 2 - the guest command follows the last
        // option directly, and "/bin/bash" does not start with '-' so the parser
        // stops there.
        "/bin/bash", "-c",
        // dpkg --configure -a first: a provisioning run that was killed (Stop all,
        // process death, timeout) leaves /var/lib/dpkg/updates behind, and every
        // later apt then refuses with "dpkg was interrupted". On a clean rootfs
        // this is a fast no-op, so the step is self-healing rather than a guess.
        "dpkg --configure -a; " +
            "apt-get $APT_OPTIONS update && " +
            "apt-get $APT_OPTIONS install -y --no-install-recommends ${packages.joinToString(" ")} && " +
            // Build the fontconfig cache NOW: otherwise the first Firefox start
            // spends up to a minute building it inside the jail - the visible
            // "takes forever, then a black screen" first-load stall.
            "fc-cache -f && " +
            "apt-get $APT_OPTIONS clean && rm -rf /var/lib/apt/lists/*",
    )

    /** One guest command run inside the same jail [command] uses, for the doctor. */
    fun smokeCommand(
        prootBinary: File,
        rootfsDir: File,
        resolvConf: File,
        guestCommand: List<String>,
    ): List<String> = prootPrefix(prootBinary, rootfsDir, resolvConf) + guestCommand

    /**
     * The doctor's smoke steps: three commands that each prove one thing apt
     * depends on. They run BEFORE apt so an argument-form, ptrace/seccomp or
     * credential-faking problem is named in seconds instead of surfacing as a
     * bare "apt-get failed" minutes later.
     */
    data class SmokeStep(
        val id: String,
        val label: String,
        val guestCommand: List<String>,
        /** Text a healthy answer must contain; null means "exit 0 is enough". */
        val expectOutput: String? = null,
        /** What a failure of THIS step means for apt, in plain language. */
        val explains: String,
    )

    val SMOKE_STEPS: List<SmokeStep> = listOf(
        SmokeStep(
            id = "exec",
            label = "proot -> /bin/true",
            guestCommand = listOf("/bin/true"),
            explains = "proot cannot exec anything in this rootfs, so apt could never run.",
        ),
        SmokeStep(
            id = "fake-root",
            label = "proot -0 -> /usr/bin/id",
            guestCommand = listOf("/usr/bin/id"),
            expectOutput = "uid=0",
            explains = "the guest does not see uid 0, so dpkg would refuse to install " +
                "(\"requested operation requires superuser privilege\") and apt could not take its locks.",
        ),
        SmokeStep(
            id = "apt-get",
            label = "proot -> /usr/bin/apt-get --version",
            guestCommand = listOf("/usr/bin/apt-get", "--version"),
            expectOutput = "apt",
            explains = "apt-get itself does not run inside the jail, so Provision has nothing to call.",
        ),
        // dpkg aborts configuration with "error: 2 expected programs not found in
        // PATH" when these are unreachable - which is an environment bug (the
        // guest PATH missing /sbin), not a rootfs bug, and it used to surface only
        // minutes into a provision as exit 100.
        SmokeStep(
            id = "dpkg-tools",
            label = "proot -> /bin/sh -c 'command -v ldconfig && command -v start-stop-daemon'",
            guestCommand = listOf("/bin/sh", "-c", "command -v ldconfig && command -v start-stop-daemon"),
            expectOutput = "ldconfig",
            explains = "dpkg requires ldconfig and start-stop-daemon on the guest PATH (/sbin, /usr/sbin); " +
                "the provision environment's PATH is wrong.",
        ),
    )

    /**
     * The environment provision runs with; LD_LIBRARY_PATH carries the vendored
     * libtalloc.
     *
     * Two device-proven details:
     *
     * - `PATH` is the GUEST root's PATH, not a host-flavoured one. dpkg refuses to
     *   configure anything with "error: 2 expected programs not found in PATH"
     *   unless `ldconfig` and `start-stop-daemon` (both in /sbin) are reachable -
     *   seen on a real device as the second Provision failure after the package
     *   list. `/system/bin` and friends are dead entries inside the jail.
     * - `PROOT_TMP_DIR`, not `TMPDIR`, is what the vendored fork's temp module
     *   reads (`src/path/temp.c`: `getenv("PROOT_TMP_DIR")` else `P_tmpdir`, i.e.
     *   "/tmp"). Android has no host /tmp, so without it every run prints
     *   "proot warning: can't canonicalize /tmp/" and the f2fs-probe warning.
     *   TMPDIR is set as well for guests that honour it.
     */
    fun environment(nativeLibraryDir: File, loaderPaths: Map<String, String>, tmpDir: File? = null): Map<String, String> =
        (
            mapOf(
                "PATH" to GUEST_PATH,
                "HOME" to "/root",
                "LANG" to "C.UTF-8",
                "DEBIAN_FRONTEND" to "noninteractive",
                // proot's seccomp-based syscall filtering misbehaves on several
                // Android kernels; PROOT_NO_SECCOMP is its documented kill switch
                // (plain ptrace: a bit slower, always correct).
                "PROOT_NO_SECCOMP" to "1",
                "LD_LIBRARY_PATH" to nativeLibraryDir.absolutePath,
            ) + (tmpDir?.let { mapOf("TMPDIR" to it.absolutePath, "PROOT_TMP_DIR" to it.absolutePath) } ?: emptyMap()) +
                loaderPaths
            ).toSortedMap()

    /** The guest root's PATH: dpkg needs /sbin and /usr/sbin in it (see [environment]). */
    const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /**
     * Runs one provisioning pass. Called from a guarded worker thread (the
     * Settings page's worker helper); blocks until apt finishes or [timeoutMillis].
     *
     * The [ProvisionDoctor]'s blocking host-side checks run first: when one
     * fails, the result names THAT prerequisite instead of wasting minutes
     * inside proot to produce a bare "apt-get failed".
     */
    fun provision(
        binaries: NativeBinaries,
        rootfsDir: File,
        filesDir: File,
        log: LogBuffer,
        runner: ProcessRunner = JvmProcessRunner(),
        timeoutMillis: Long = 45 * 60 * 1000L,
        packages: List<String> = BROWSER_PACKAGES,
    ): ProvisionResult {
        // The doctor's pre-flight INCLUDES the real proot smoke test (true/id/
        // apt-get --version) through the same [ProcessRunner] seam: a failure
        // there is named in seconds, with the exact command line, instead of
        // surfacing minutes later as a bare "apt-get failed".
        val preflight = ProvisionDoctor.run(binaries, rootfsDir, filesDir, includeNetwork = false, runner = runner)
        if (!preflight.ok) {
            preflight.checks.forEach { check ->
                log.add(
                    TerminalLine(
                        if (check.state == CheckState.FAIL) LineKind.ERROR else LineKind.SYSTEM,
                        "doctor: ${check.line()}",
                    ),
                )
            }
            return ProvisionResult(false, preflight.headline(), preflight.checks.map { it.line() })
        }
        // Stale-lock recovery: an apt-get/dpkg orphaned by a KILLED earlier run
        // (proot SIGKILLed -> --kill-on-exit never ran) holds the /var/lib/dpkg
        // flocks and every later provision dies with "Could not get lock ...
        // held by process N (apt-get)". The orphan shares our uid, so it is
        // killed HOST-side here, before the jail is spawned; the script's first
        // step (dpkg --configure -a) then heals whatever it left half-done.
        PackageLockRecovery.recoverInto(log)
        val tmpDir = File(filesDir, "tmp")
        val resolvConf = runCatching { ensureResolvConf(File(tmpDir, "resolv.conf")) }
            .getOrElse { return ProvisionResult(false, "Could not write resolv.conf: ${it.message}") }
        // A real /etc/resolv.conf inside the rootfs too: imported images may carry
        // systemd-resolved's dangling symlink there, which no bind can fix.
        runCatching { installResolvConf(rootfsDir) }.onFailure {
            return ProvisionResult(false, "Could not write /etc/resolv.conf into the rootfs: ${it.message}")
        }
        val command = command(File(binaries.nativeLibraryDir, BinaryRole.PROOT.fileName), rootfsDir, resolvConf, packages)
        log.add(
            TerminalLine(
                LineKind.SYSTEM,
                "provisioning rootfs: apt-get install ${packages.size} packages (proot fake root -0, APT sandbox user root)",
            ),
        )
        val outcome = runner.run(
            command,
            environment(binaries.nativeLibraryDir, binaries.loaderPaths(), tmpDir),
            workingDir = null,
            timeoutMillis = timeoutMillis,
        )
        val tail = (outcome.stdout + "\n" + outcome.stderr).trim().lineSequence()
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(TAIL_LINES)
        tail.forEach { line ->
            log.add(TerminalLine(if (outcome.succeeded) LineKind.OUTPUT else LineKind.ERROR, line))
        }
        return if (outcome.succeeded) {
            ProvisionResult(true, "Rootfs provisioned (${packages.size} packages installed).", tail)
        } else {
            val verdict = when {
                outcome.exitCode == null -> "apt-get did not finish (${outcome.error ?: "no exit code"})"
                else -> "apt-get failed (exit ${outcome.exitCode}${outcome.error?.let { ", $it" } ?: ""})"
            }
            log.add(TerminalLine(LineKind.SYSTEM, "$verdict - its last ${tail.size} output line(s) are above"))
            ProvisionResult(
                false,
                "$verdict: ${tail.lastOrNull() ?: "no output"} - " +
                    "last ${tail.size} apt line(s) below and in Terminal ▸ log.",
                tail,
            )
        }
    }
}
