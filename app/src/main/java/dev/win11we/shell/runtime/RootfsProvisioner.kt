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
        "libdbus-glib-1-2-2", // Firefox's D-Bus bindings
        "libdbus-1-3",
        "libasound2", // audio backend (PulseAudio bridge arrives with P1)
        "libxt6", // X Toolkit, still used by Gecko
        "libx11-xcb1",
        "libegl1", // probed by Gecko; software rendering falls back cleanly
        "libgl1",
        "fontconfig",
        "fonts-dejavu-core",
        "fonts-liberation", // metric-compatible Arial/Times stand-ins
        "ca-certificates", // TLS roots for the web
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
     * apt/dpkg options every in-proot apt run needs:
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
    private const val APT_OPTIONS = "-o APT::Sandbox::User=root " +
        "-o Dpkg::Options::=--force-unsafe-io " +
        "-o Dpkg::Options::=--force-confold"

    /** The exact proot command line: apt-get update && install && clean, as fake root. */
    fun command(
        prootBinary: File,
        rootfsDir: File,
        resolvConf: File,
        packages: List<String> = BROWSER_PACKAGES,
    ): List<String> = listOf(
        prootBinary.absolutePath,
        "--rootfs", rootfsDir.absolutePath,
        // Fake root (-0 == -i 0:0). Without it dpkg refuses outright ("requested
        // operation requires superuser privilege") and apt cannot take its locks:
        // proot's own help text names package managers as -0's use case. It fakes
        // credentials and ownership only - everything still really runs as the
        // app uid.
        "-0",
        "--link2symlink",
        "--kill-on-exit",
        "--bind", "/proc:/proc",
        "--bind", "/sys:/sys",
        "--bind", "/dev:/dev",
        "--bind", "${resolvConf.absolutePath}:/etc/resolv.conf",
        "--cwd", "/root",
        "--",
        "/bin/bash", "-c",
        "apt-get $APT_OPTIONS update && " +
            "apt-get $APT_OPTIONS install -y --no-install-recommends ${packages.joinToString(" ")} && " +
            "apt-get $APT_OPTIONS clean && rm -rf /var/lib/apt/lists/*",
    )

    /** Host-side environment; LD_LIBRARY_PATH carries the vendored libtalloc. */
    fun environment(nativeLibraryDir: File, loaderPaths: Map<String, String>, tmpDir: File? = null): Map<String, String> =
        (
            mapOf(
                "PATH" to "/system/bin:/vendor/bin:/usr/bin:/bin",
                "HOME" to "/root",
                "LANG" to "C.UTF-8",
                "DEBIAN_FRONTEND" to "noninteractive",
                // proot's seccomp-based syscall filtering misbehaves on several
                // Android kernels; PROOT_NO_SECCOMP is its documented kill switch
                // (plain ptrace: a bit slower, always correct).
                "PROOT_NO_SECCOMP" to "1",
                "LD_LIBRARY_PATH" to nativeLibraryDir.absolutePath,
            ) + (tmpDir?.let { mapOf("TMPDIR" to it.absolutePath) } ?: emptyMap()) + loaderPaths
            ).toSortedMap()

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
        val preflight = ProvisionDoctor.run(binaries, rootfsDir, filesDir, includeNetwork = false)
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
