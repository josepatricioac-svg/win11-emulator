package dev.win11we.shell.runtime

import dev.win11we.shell.runtime.download.RuntimeManifest
import dev.win11we.shell.ui.terminal.LineKind
import dev.win11we.shell.ui.terminal.LogBuffer
import dev.win11we.shell.ui.terminal.TerminalLine
import java.io.File

/**
 * Installs the Wine payload into the rootfs - the link that was missing between
 * "the wine .debs are pinned and downloadable" (R3) and "a prefix can be
 * created" ([WinePrefixManager]). Until this runs, [WinePayload.launcherIn]
 * finds nothing and every wine launch/prefix operation refuses with the chain
 * named (no payload -> no wineboot -> no prefix).
 *
 * Two install paths, fail-closed philosophy kept intact:
 *
 * 1. **Pinned debs** (preferred): R3's manifest already pins
 *    `wine64_9.0~repack-4build3_arm64.deb` + `wine_9.0~repack-4build3_all.deb`
 *    by SHA-256 (a mismatched download never reaches `<files>/downloads`). When
 *    both verified files are present they are bound into the jail and installed
 *    with `dpkg -i`, and `apt-get -f install` resolves their dependency closure
 *    from ports.ubuntu.com - the same "pinned core, distro-resolved deps"
 *    contract the browser provisioning documents.
 * 2. **apt fallback**: with no downloaded debs, `apt-get install wine64 wine`
 *    fetches exactly those versions from the noble archive the rootfs already
 *    points at (the pins in [RuntimeManifest] were taken from that archive's
 *    Packages index), so the fallback is the same software, just without the
 *    offline guarantee. `--no-install-recommends` still pulls `libwine`, because
 *    it is a hard `Depends` of wine64 - and it must, since that is where
 *    `wineboot` and every builtin DLL live (see [WinePayload.payloadGap]).
 *
 * Pure command building + the [ProcessRunner] seam, unit-tested like
 * [RootfsProvisioner]; the post-check is [WinePayload.isProvisioned] - exit 0
 * without `/usr/bin/wine64` on disk is a FAILURE, not a pass.
 */
object WineProvisioner {

    /** Guest path the downloaded debs are bound at (read-only use, but proot binds are rw). */
    const val GUEST_DEB_DIR = "/mnt/wine-debs"

    /** The apt fallback's package set: the two pinned front-ends; apt pulls the payload. */
    val WINE_PACKAGES = listOf("wine64", "wine")

    /** The verified R3 downloads this provisioner can use, when present. */
    fun pinnedDebs(downloadDir: File): List<File> = listOf(
        RuntimeManifest.WINE64_DEB.targetFile(downloadDir),
        RuntimeManifest.WINE_ALL_DEB.targetFile(downloadDir),
    )

    /** Both pinned debs downloaded AND hash-verified by R3's own promotion rule. */
    fun hasPinnedDebs(downloadDir: File): Boolean = pinnedDebs(downloadDir).all { it.isFile }

    /**
     * The guest script. `dpkg --configure -a` first for the same reason the
     * browser provision does it: a killed earlier run leaves /var/lib/dpkg/updates
     * behind and every later dpkg refuses. Then the pinned-deb path when the
     * bind exists, else the apt path. `apt-get -f install -y` after `dpkg -i`
     * completes the dependency closure (dpkg alone cannot fetch).
     */
    fun installScript(): String {
        val opts = RootfsProvisioner.APT_OPTIONS
        return "dpkg --configure -a; " +
            "if ls $GUEST_DEB_DIR/*.deb >/dev/null 2>&1; then " +
            "apt-get $opts update; " +
            "dpkg -i $GUEST_DEB_DIR/*.deb; " +
            "apt-get $opts -f install -y && apt-get $opts clean && rm -rf /var/lib/apt/lists/*; " +
            "else " +
            "apt-get $opts update && " +
            "apt-get $opts install -y --no-install-recommends ${WINE_PACKAGES.joinToString(" ")} && " +
            "apt-get $opts clean && rm -rf /var/lib/apt/lists/*; " +
            "fi"
    }

    /**
     * The exact proot command line: the shared fake-root prefix (same argument
     * rules as every other in-jail run: short options, no `--`), the deb bind
     * when the pinned files exist, then the script above.
     */
    fun command(
        prootBinary: File,
        rootfsDir: File,
        resolvConf: File,
        debDir: File?,
    ): List<String> {
        val prefix = RootfsProvisioner.prootPrefix(prootBinary, rootfsDir, resolvConf)
        val debBinds = if (debDir != null && hasPinnedDebs(debDir)) {
            listOf("-b", "${debDir.absolutePath}:$GUEST_DEB_DIR")
        } else {
            emptyList()
        }
        // debBinds are proot OPTIONS, so they must precede the guest command
        // (parsing stops at the first non-dash argument - see ProotCommand).
        return prefix + debBinds + listOf("/bin/bash", "-c", installScript())
    }

    /**
     * Runs one Wine provisioning pass on a guarded worker (the caller's job -
     * crash-safety rule 1); blocks until apt/dpkg finish or [timeoutMillis].
     * Mirrors [RootfsProvisioner.provision]: doctor pre-flight first, resolv.conf
     * written + bind-mounted, every guest line streamed to the shared log, and
     * the verdict taken from the DISK ([WinePayload.isProvisioned]), not from
     * the exit code alone.
     */
    fun provision(
        binaries: NativeBinaries,
        rootfsDir: File,
        filesDir: File,
        downloadDir: File,
        log: LogBuffer,
        runner: ProcessRunner = JvmProcessRunner(),
        timeoutMillis: Long = 45 * 60 * 1000L,
    ): ProvisionResult {
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
        // Stale-lock recovery, identical to RootfsProvisioner's: an orphaned
        // apt/dpkg from a killed run would otherwise make this install die on
        // "Could not get lock ... held by process N (apt-get)" within seconds.
        PackageLockRecovery.recoverInto(log)
        val tmpDir = File(filesDir, "tmp")
        val resolvConf = runCatching { RuntimePaths.ensureResolvConf(filesDir) }
            .getOrElse { return ProvisionResult(false, "Could not write resolv.conf: ${it.message}") }
        runCatching { RootfsProvisioner.installResolvConf(rootfsDir) }.onFailure {
            return ProvisionResult(false, "Could not write /etc/resolv.conf into the rootfs: ${it.message}")
        }
        val debs = if (hasPinnedDebs(downloadDir)) downloadDir else null
        val command = command(
            File(binaries.nativeLibraryDir, BinaryRole.PROOT.fileName),
            rootfsDir,
            resolvConf,
            debs,
        )
        log.add(
            TerminalLine(
                LineKind.SYSTEM,
                if (debs != null) {
                    "installing Wine from the pinned debs in ${downloadDir.absolutePath} (deps via apt)"
                } else {
                    "installing Wine via apt (${WINE_PACKAGES.joinToString(" ")}) - no pinned debs downloaded yet"
                },
            ),
        )
        val outcome = runner.run(
            command,
            RootfsProvisioner.environment(binaries.nativeLibraryDir, binaries.loaderPaths(), tmpDir),
            workingDir = null,
            timeoutMillis = timeoutMillis,
        )
        val tail = (outcome.stdout + "\n" + outcome.stderr).trim().lineSequence()
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(RootfsProvisioner.TAIL_LINES)
        tail.forEach { line ->
            log.add(TerminalLine(if (outcome.succeeded) LineKind.OUTPUT else LineKind.ERROR, line))
        }
        // The disk is the verdict: exit 0 without the launcher binary installed
        // is a failure with a name, not a green tick. `payloadGap` goes one step
        // further than the loader check: a Wine that installed its loader but not
        // `libwine` cannot run wineboot, so it cannot build a prefix either.
        val payload = WinePayload.launcherIn(rootfsDir)
        val gap = WinePayload.payloadGap(rootfsDir)
        return when {
            !outcome.succeeded -> {
                val verdict = if (outcome.exitCode == null) {
                    "Wine install did not finish (${outcome.error ?: "no exit code"})"
                } else {
                    "Wine install failed (exit ${outcome.exitCode})"
                }
                log.add(TerminalLine(LineKind.SYSTEM, "$verdict - last ${tail.size} output line(s) above"))
                ProvisionResult(false, "$verdict: ${tail.lastOrNull() ?: "no output"}", tail)
            }
            gap != null -> {
                val message = "dpkg/apt exited 0 but the Wine payload is incomplete: $gap - treating as FAILED."
                log.add(TerminalLine(LineKind.ERROR, message))
                ProvisionResult(false, message, tail)
            }
            else -> {
                log.add(TerminalLine(LineKind.SYSTEM, "Wine payload ready at ${payload?.absolutePath}"))
                ProvisionResult(true, "Wine payload installed (${payload?.absolutePath}). Prefixes can now be created.", tail)
            }
        }
    }
}
