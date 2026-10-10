package dev.win11we.shell.runtime

import dev.win11we.shell.ui.terminal.LineKind
import dev.win11we.shell.ui.terminal.LogBuffer
import dev.win11we.shell.ui.terminal.TerminalLine
import java.io.File

data class ProvisionResult(val success: Boolean, val message: String)

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

    /** The exact proot command line: apt-get update && install && clean, as fake root. */
    fun command(
        prootBinary: File,
        rootfsDir: File,
        resolvConf: File,
        packages: List<String> = BROWSER_PACKAGES,
    ): List<String> = listOf(
        prootBinary.absolutePath,
        "--rootfs", rootfsDir.absolutePath,
        "--link2symlink",
        "--kill-on-exit",
        "--bind", "/proc:/proc",
        "--bind", "/sys:/sys",
        "--bind", "/dev:/dev",
        "--bind", "${resolvConf.absolutePath}:/etc/resolv.conf",
        "--cwd", "/root",
        "--",
        "/bin/bash", "-c",
        "apt-get update && " +
            "apt-get install -y --no-install-recommends ${packages.joinToString(" ")} && " +
            "apt-get clean && rm -rf /var/lib/apt/lists/*",
    )

    /** Host-side environment; LD_LIBRARY_PATH carries the vendored libtalloc. */
    fun environment(nativeLibraryDir: File, loaderPaths: Map<String, String>): Map<String, String> =
        (
            mapOf(
                "PATH" to "/system/bin:/vendor/bin:/usr/bin:/bin",
                "HOME" to "/root",
                "LANG" to "C.UTF-8",
                "DEBIAN_FRONTEND" to "noninteractive",
                "LD_LIBRARY_PATH" to nativeLibraryDir.absolutePath,
            ) + loaderPaths
            ).toSortedMap()

    /**
     * Runs one provisioning pass. Called from a guarded worker thread (the
     * Settings page's worker helper); blocks until apt finishes or [timeoutMillis].
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
        val prepare = binaries.prepare(NativeBinaries.REQUIRED_PROOT_ONLY)
        if (prepare.status != PrepareStatus.READY) {
            return ProvisionResult(false, "Runtime binaries not ready: ${prepare.message}")
        }
        if (!File(rootfsDir, "usr/bin/apt-get").isFile) {
            return ProvisionResult(
                false,
                "The rootfs is not extracted yet (no apt-get in ${rootfsDir.absolutePath}) - " +
                    "download and extract it in Runtime download first.",
            )
        }
        val resolvConf = runCatching { ensureResolvConf(File(filesDir, "tmp/resolv.conf")) }
            .getOrElse { return ProvisionResult(false, "Could not write resolv.conf: ${it.message}") }
        val command = command(File(binaries.nativeLibraryDir, BinaryRole.PROOT.fileName), rootfsDir, resolvConf, packages)
        log.add(TerminalLine(LineKind.SYSTEM, "provisioning rootfs: apt-get install ${packages.size} packages"))
        val outcome = runner.run(
            command,
            environment(binaries.nativeLibraryDir, binaries.loaderPaths()),
            workingDir = null,
            timeoutMillis = timeoutMillis,
        )
        val tail = (outcome.stdout + outcome.stderr).trim().lineSequence()
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(12)
        tail.forEach { line ->
            log.add(TerminalLine(if (outcome.succeeded) LineKind.OUTPUT else LineKind.ERROR, line))
        }
        return if (outcome.succeeded) {
            ProvisionResult(true, "Rootfs provisioned (${packages.size} packages installed).")
        } else {
            ProvisionResult(
                false,
                "apt-get failed (exit ${outcome.exitCode ?: "?"}${outcome.error?.let { ", $it" } ?: ""}): " +
                    (tail.lastOrNull() ?: "no output"),
            )
        }
    }
}
