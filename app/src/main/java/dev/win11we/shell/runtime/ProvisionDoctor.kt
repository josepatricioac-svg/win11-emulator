package dev.win11we.shell.runtime

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** What one prerequisite check concluded. */
enum class CheckState { PASS, WARN, FAIL }

/**
 * One row of the Provision doctor: a prerequisite for `apt-get` inside proot,
 * what the shell could verify about it on the HOST side (no proot needed), and
 * whether a failure makes running apt pointless ([blocking]).
 */
data class DoctorCheck(
    val id: String,
    val label: String,
    val state: CheckState,
    val detail: String,
    val blocking: Boolean = true,
) {
    fun line(): String = "${state.name} $id: $detail"
}

/** The whole report; [headline] is the plain-language answer the card shows. */
data class DoctorReport(val checks: List<DoctorCheck>) {
    val passes: Int get() = checks.count { it.state == CheckState.PASS }
    val warnings: Int get() = checks.count { it.state == CheckState.WARN }
    val failures: Int get() = checks.count { it.state == CheckState.FAIL }
    val firstFailure: DoctorCheck? get() = checks.firstOrNull { it.state == CheckState.FAIL }
    val firstBlockingFailure: DoctorCheck? get() = checks.firstOrNull { it.state == CheckState.FAIL && it.blocking }
    val ok: Boolean get() = firstBlockingFailure == null

    fun summary(): String = "$passes pass, $warnings warn, $failures fail"

    /** One sentence a human can act on; names the first failing prerequisite. */
    fun headline(): String = when {
        firstBlockingFailure != null ->
            "Provisioning cannot work yet: ${firstBlockingFailure!!.label} - ${firstBlockingFailure!!.detail}"
        firstFailure != null ->
            "Prerequisites mostly pass, but: ${firstFailure!!.label} - ${firstFailure!!.detail}"
        warnings > 0 -> "All blocking prerequisites pass ($warnings warning(s)); apt should run."
        else -> "All prerequisites pass - apt should run."
    }
}

/**
 * The Provision doctor: verifies every prerequisite for
 * [RootfsProvisioner.provision] BEFORE spending minutes inside proot, and names
 * the first failing step in plain language. A bare "apt-get failed" is never
 * the end of the story again: either the doctor already explains why apt could
 * not work, or apt ran and its exit code + last 30 lines are on the card.
 *
 * Two kinds of check:
 *
 * 1. **Host-side file checks** (pure: files + one optional HTTP HEAD through the
 *    [Reachability] seam) - binaries pinned and executable, rootfs extracted,
 *    rootfs really arm64, sources point at ports.ubuntu.com, guest DNS, dpkg
 *    interrupted state, free space, writable TMPDIR.
 * 2. **One real proot smoke test** ([RootfsProvisioner.SMOKE_STEPS]) - `true`,
 *    `id` and `apt-get --version` inside the exact jail Provision uses. This
 *    exists because a green file-check report proves nothing about proot
 *    itself: the vendored termux/proot rejects a detached `--rootfs <path>`,
 *    its seccomp filter misbehaves on several Android kernels, and `-0`'s
 *    credential faking is what lets dpkg run at all. Any of those is a
 *    seconds-long failure to catch here rather than a mystery minutes into apt.
 *    It spawns nothing when an earlier prerequisite already failed (the row then
 *    says WHY it was not run), and it is injected through [ProcessRunner] so the
 *    whole doctor stays unit-testable.
 *
 * What even this cannot verify from outside (in-jail DNS resolution, the
 * archive's own answers, dpkg lock state) is what the apt tail output is for.
 */
object ProvisionDoctor {

    /** URL -> "HTTP <code>" or "unreachable: <reason>". Injectable for tests. */
    fun interface Reachability {
        fun probe(url: String): String
    }

    /** GTK3 + fonts + CA certs install to ~250 MB; apt caches the .debs on top. */
    const val MIN_FREE_MB = 400L
    const val LOW_FREE_MB = 800L

    /**
     * The index apt fetches first; probed over https (the app trusts system CAs).
     * Must be the SAME release as [dev.win11we.shell.runtime.download.RuntimeManifest.ROOTFS]
     * - the pinned rootfs is noble (24.04), so probing jammy's Release file would report
     * on an archive this rootfs never talks to.
     */
    const val PORTS_RELEASE_URL = "https://ports.ubuntu.com/ubuntu-ports/dists/noble/Release"

    /** Field separator inside a deb822 value list; declared before its users. */
    private val WHITESPACE = Regex("\\s+")

    private const val EM_AARCH64 = ElfHeader.EM_AARCH64
    private const val EM_X86_64 = ElfHeader.EM_X86_64

    val defaultReachability = Reachability { url ->
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = true
            try {
                "HTTP ${connection.responseCode}"
            } finally {
                connection.disconnect()
            }
        } catch (error: Exception) {
            "unreachable: ${error.javaClass.simpleName}: ${error.message}"
        }
    }

    /** How long one smoke command may take before it is called a hang, not a failure. */
    const val SMOKE_TIMEOUT_MILLIS = 20_000L

    fun run(
        binaries: NativeBinaries,
        rootfsDir: File,
        filesDir: File,
        includeNetwork: Boolean = true,
        reachability: Reachability = defaultReachability,
        /**
         * The real proot smoke test ([checkProotSmoke]). On by default: a doctor
         * that never runs proot cannot prove Provision will. Tests inject a
         * [ProcessRunner] rather than turning it off.
         */
        includeProotSmoke: Boolean = true,
        runner: ProcessRunner = JvmProcessRunner(),
        smokeTimeoutMillis: Long = SMOKE_TIMEOUT_MILLIS,
    ): DoctorReport {
        val prerequisites = listOf(
            checkBinaries(binaries),
            checkRootfsExtracted(rootfsDir),
            checkRootfsArchitecture(rootfsDir),
            checkSourcesList(rootfsDir),
            checkResolvConf(rootfsDir),
            checkDpkgState(rootfsDir),
            checkDiskSpace(rootfsDir),
            checkTmpWritable(filesDir),
        )
        // The smoke test needs proot AND an unpacked rootfs; without them it can
        // only repeat the failure the file checks already named, so it reports
        // "not run" instead of spawning something that cannot work.
        val smoke = if (includeProotSmoke) {
            val blockedBy = prerequisites.firstOrNull { it.state == CheckState.FAIL && it.blocking }
            listOf(
                if (blockedBy != null) {
                    DoctorCheck(
                        SMOKE_ID, SMOKE_LABEL, CheckState.WARN,
                        "not run: ${blockedBy.id} (${blockedBy.label}) already failed - ${blockedBy.detail}",
                        blocking = false,
                    )
                } else {
                    checkProotSmoke(binaries, rootfsDir, filesDir, runner, smokeTimeoutMillis)
                },
            )
        } else {
            emptyList()
        }
        return DoctorReport(
            prerequisites + smoke + if (includeNetwork) listOf(checkNetwork(reachability)) else emptyList(),
        )
    }

    /** 1. The vendored proot set: present, executable, SHA-256-pinned (fail closed). */
    private fun checkBinaries(binaries: NativeBinaries): DoctorCheck {
        val prepare = binaries.prepare(NativeBinaries.REQUIRED_PROOT_ONLY)
        return if (prepare.status == PrepareStatus.READY) {
            DoctorCheck("native-binaries", "Native binaries (proot set)", CheckState.PASS, prepare.message)
        } else {
            DoctorCheck(
                "native-binaries",
                "Native binaries (proot set)",
                CheckState.FAIL,
                "Runtime binaries not ready: ${prepare.message}",
            )
        }
    }

    /** 2. The rootfs is unpacked far enough to run apt at all. */
    private fun checkRootfsExtracted(rootfsDir: File): DoctorCheck {
        val apt = File(rootfsDir, "usr/bin/apt-get")
        val bash = File(rootfsDir, "usr/bin/bash")
        return when {
            !apt.isFile -> DoctorCheck(
                "rootfs-extracted",
                "Rootfs extracted",
                CheckState.FAIL,
                "The rootfs is not extracted yet (no apt-get in ${rootfsDir.absolutePath}) - " +
                    "download and extract it in Runtime download first.",
            )
            !bash.isFile -> DoctorCheck(
                "rootfs-extracted",
                "Rootfs extracted",
                CheckState.FAIL,
                "The rootfs has apt-get but no /usr/bin/bash at ${rootfsDir.absolutePath} - extraction looks incomplete.",
            )
            else -> DoctorCheck("rootfs-extracted", "Rootfs extracted", CheckState.PASS, apt.absolutePath)
        }
    }

    /**
     * 3. The rootfs must be arm64: an amd64 image (easy to import by accident via
     * SAF) cannot exec under this app at all, and apt would die with "Exec format
     * error" deep inside proot. The ELF header's e_machine answers without proot.
     */
    private fun checkRootfsArchitecture(rootfsDir: File): DoctorCheck {
        val probe = File(rootfsDir, "usr/bin/bash").takeIf { it.isFile }
            ?: return DoctorCheck("rootfs-arch", "Rootfs architecture", CheckState.WARN, "no /usr/bin/bash to inspect")
        val machine = runCatching { elfMachine(probe) }.getOrNull()
        return when (machine) {
            EM_AARCH64 -> DoctorCheck("rootfs-arch", "Rootfs architecture", CheckState.PASS, "arm64 (ELF e_machine=$EM_AARCH64)")
            EM_X86_64 -> DoctorCheck(
                "rootfs-arch",
                "Rootfs architecture",
                CheckState.FAIL,
                "This rootfs is x86-64 (ELF e_machine=$EM_X86_64). The app runs arm64 guests only - " +
                    "remove it and download the pinned ubuntu-base arm64 in Runtime download.",
            )
            null -> DoctorCheck("rootfs-arch", "Rootfs architecture", CheckState.WARN, "could not read ${probe.name}'s ELF header")
            else -> DoctorCheck("rootfs-arch", "Rootfs architecture", CheckState.FAIL, "unexpected ELF e_machine=$machine (not arm64)")
        }
    }

    /** @return the ELF header's e_machine, or null when the file is not a readable ELF. */
    private fun elfMachine(file: File): Int? = ElfHeader.machine(file)

    /**
     * 4. arm64 packages live on ports.ubuntu.com, not archive.ubuntu.com. The
     * pinned ubuntu-base ships the right sources; an imported or edited rootfs
     * may not, and apt would then 404 every index.
     *
     * BOTH apt source formats are read, because which one a rootfs uses depends on
     * its release:
     * - `etc/apt/sources.list` - the classic one-line format (`deb <uri> <suite> <comp>`).
     *   Ubuntu <= 22.04 ships this.
     * - `etc/apt/sources.list.d/` `*.sources` files - the deb822 format (`Types:`/`URIs:`/
     *   `Suites:` stanzas). **Ubuntu 24.04 (noble) ships ONLY this**: its
     *   `/etc/apt/sources.list` is 270 bytes of pure comment with no `deb` line at all,
     *   and the real sources are `sources.list.d/ubuntu.sources`. Judging only
     *   sources.list therefore reported "No active 'deb' lines" and BLOCKED provisioning
     *   on a perfectly healthy noble rootfs.
     * A stanza is only counted when it is enabled (`Types:` contains `deb`; deb822
     * comments with `#` and an absent `Types:` both mean disabled).
     */
    private fun checkSourcesList(rootfsDir: File): DoctorCheck {
        val sources = File(rootfsDir, "etc/apt/sources.list")
        val oneLine = runCatching { if (sources.isFile) sources.readText() else "" }.getOrDefault("")
        val debLines = oneLine.lineSequence().filter { it.trimStart().startsWith("deb ") }.toList()

        val sourcesDir = File(rootfsDir, "etc/apt/sources.list.d")
        val deb822Files: List<File> = runCatching {
            sourcesDir.listFiles()?.filter { it.isFile && it.name.endsWith(".sources") }?.sortedBy { it.name }
        }.getOrNull() ?: emptyList()
        val deb822 = deb822Files.flatMap { deb822Uris(it) }

        val uris = debLines + deb822
        val where = when {
            debLines.isNotEmpty() && deb822.isNotEmpty() ->
                "${sources.name} (${debLines.size} deb line(s)) + ${deb822Files.size} .sources file(s)"
            deb822.isNotEmpty() -> deb822Files.joinToString(", ") { "sources.list.d/${it.name}" }
            else -> sources.absolutePath
        }
        return when {
            uris.isEmpty() -> DoctorCheck(
                "apt-sources",
                "apt sources",
                CheckState.FAIL,
                "No enabled apt source: no active 'deb' line in ${sources.absolutePath} and no " +
                    "'deb'-type stanza in ${sourcesDir.absolutePath}/*.sources - apt has nothing to download from.",
            )
            uris.any { "ports.ubuntu.com" in it } ->
                DoctorCheck("apt-sources", "apt sources", CheckState.PASS, "${uris.size} source(s) in $where, ports.ubuntu.com")
            uris.any { "archive.ubuntu.com" in it || "security.ubuntu.com" in it } -> DoctorCheck(
                "apt-sources",
                "apt sources",
                CheckState.FAIL,
                "apt sources in $where point at the x86 archive (archive/security.ubuntu.com); arm64 packages " +
                    "live on ports.ubuntu.com. This rootfs is the wrong flavour or its sources were edited.",
            )
            else -> DoctorCheck(
                "apt-sources",
                "apt sources",
                CheckState.WARN,
                "apt sources in $where have ${uris.size} entr${if (uris.size == 1) "y" else "ies"} but none name " +
                    "ports.ubuntu.com - check they serve arm64.",
            )
        }
    }

    /**
     * The `URIs:` of every ENABLED `deb` stanza in one deb822 `.sources` file.
     * Stanzas are blank-line separated; `#` comments are dropped; a stanza with no
     * `Types:` is disabled by definition. `Types:` is matched TOKEN-wise, not by
     * substring: `deb-src` (source packages only) must NOT count as `deb`, or a
     * rootfs apt cannot fetch binaries from would be reported healthy.
     * One line per returned URI so the caller's counts stay meaningful.
     */
    private fun deb822Uris(file: File): List<String> {
        val text = runCatching { file.readText() }.getOrDefault("")
        return text.split("\n\n", "\r\n\r\n")
            .map { stanza ->
                stanza.lineSequence()
                    .map { it.trimEnd('\r') }
                    .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
                    .toList()
            }
            .filter { lines -> lines.any { it.fieldIs("Types") && it.fieldValue().hasDebType() } }
            .flatMap { lines -> lines.filter { it.fieldIs("URIs") }.flatMap { it.fieldValue().split(WHITESPACE) } }
            .filter { it.isNotBlank() }
    }

    /** True when a whitespace-separated `Types:` value contains the binary `deb` token. */
    private fun String.hasDebType(): Boolean = split(WHITESPACE).any { it.equals("deb", ignoreCase = true) }

    /** True when this deb822 line's field name is [name] (case-insensitive, per the spec). */
    private fun String.fieldIs(name: String): Boolean =
        substringBefore(':', "").trim().equals(name, ignoreCase = true)

    /** Everything after the first ':' on a deb822 line, trimmed. */
    private fun String.fieldValue(): String = substringAfter(':', "").trim()

    /** 5. Guest DNS: a real, non-empty resolv.conf (provision rewrites it anyway, so a bad state is a warning). */
    private fun checkResolvConf(rootfsDir: File): DoctorCheck {
        val resolv = File(rootfsDir, "etc/resolv.conf")
        val link = java.nio.file.Files.isSymbolicLink(resolv.toPath())
        val text = runCatching { if (resolv.isFile) resolv.readText() else "" }.getOrDefault("")
        return when {
            text.contains("nameserver") && !link ->
                DoctorCheck("dns", "Guest DNS (/etc/resolv.conf)", CheckState.PASS, "nameserver line present")
            link -> DoctorCheck(
                "dns",
                "Guest DNS (/etc/resolv.conf)",
                CheckState.WARN,
                "is a symlink (systemd-resolved stub?); Provision replaces it with a real file and binds one over it.",
            )
            else -> DoctorCheck(
                "dns",
                "Guest DNS (/etc/resolv.conf)",
                CheckState.WARN,
                "empty or missing; Provision writes public resolvers into the rootfs and binds its own copy.",
            )
        }
    }

    /**
     * 6. A killed provisioning run leaves /var/lib/dpkg/updates behind; apt then
     * refuses with "dpkg was interrupted". Non-blocking because provision()
     * self-heals with `dpkg --configure -a` before apt runs - but the owner
     * should see WHY the last run died.
     */
    private fun checkDpkgState(rootfsDir: File): DoctorCheck {
        val updates = File(rootfsDir, "var/lib/dpkg/updates")
        val pending = runCatching { if (updates.isDirectory) updates.listFiles()?.size ?: 0 else 0 }.getOrDefault(0)
        return if (pending > 0) {
            DoctorCheck(
                "dpkg-state",
                "dpkg interrupted state",
                CheckState.WARN,
                "$pending file(s) in var/lib/dpkg/updates - a previous run was killed mid-configure; " +
                    "Provision runs 'dpkg --configure -a' first to repair it.",
                blocking = false,
            )
        } else {
            DoctorCheck("dpkg-state", "dpkg interrupted state", CheckState.PASS, "no interrupted configure state")
        }
    }

    /** 7. Free space on the partition holding the rootfs. */
    private fun checkDiskSpace(rootfsDir: File): DoctorCheck {
        val dir = if (rootfsDir.isDirectory) rootfsDir else rootfsDir.absoluteFile.parentFile ?: rootfsDir
        val usable = runCatching { dir.usableSpace }.getOrDefault(0L)
        val freeMb = usable / (1024 * 1024)
        return when {
            usable <= 0 -> DoctorCheck("disk-space", "Free space", CheckState.WARN, "could not stat ${dir.absolutePath}")
            freeMb < MIN_FREE_MB -> DoctorCheck(
                "disk-space",
                "Free space",
                CheckState.FAIL,
                "only ${freeMb}MB free on ${dir.absolutePath}; the GTK3/font/CA-certs closure needs ~$MIN_FREE_MB MB.",
            )
            freeMb < LOW_FREE_MB -> DoctorCheck(
                "disk-space",
                "Free space",
                CheckState.WARN,
                "${freeMb}MB free (want $LOW_FREE_MB MB to be comfortable).",
            )
            else -> DoctorCheck("disk-space", "Free space", CheckState.PASS, "${freeMb}MB free")
        }
    }

    /** 8. The host TMPDIR the proot environment points at must be writable. */
    private fun checkTmpWritable(filesDir: File): DoctorCheck {
        val tmp = File(filesDir, "tmp")
        return try {
            tmp.mkdirs()
            val probe = File(tmp, ".doctor-probe")
            probe.writeText("ok")
            probe.delete()
            DoctorCheck("tmp-writable", "Host TMPDIR", CheckState.PASS, tmp.absolutePath)
        } catch (error: Exception) {
            DoctorCheck("tmp-writable", "Host TMPDIR", CheckState.FAIL, "cannot write ${tmp.absolutePath}: ${error.message}")
        }
    }

    private const val SMOKE_ID = "proot-smoke"
    private const val SMOKE_LABEL = "proot smoke test (really exec'd)"

    /**
     * 9. The real thing: run three trivial commands inside the SAME proot jail
     * Provision uses (`-0`, the system binds, the resolv.conf bind, short
     * options, `PROOT_NO_SECCOMP=1`). Seconds, not minutes - and it catches the
     * whole class of failure a host-side check cannot see:
     *
     * - the vendored termux/proot rejecting an argument form (detached `--rootfs`),
     * - a kernel that denies ptrace or misbehaves with proot's seccomp filter,
     * - a missing +x bit (exit 126 / EACCES),
     * - a wrong-architecture rootfs (Exec format error),
     * - an incomplete rootfs (guest binary not found),
     * - `-0` not actually faking uid 0, which makes dpkg refuse outright.
     *
     * Steps run in order and stop at the first failure, each reporting the exact
     * command line so the owner can reproduce it in Terminal.
     */
    private fun checkProotSmoke(
        binaries: NativeBinaries,
        rootfsDir: File,
        filesDir: File,
        runner: ProcessRunner,
        timeoutMillis: Long,
    ): DoctorCheck {
        val tmpDir = File(filesDir, "tmp")
        val proot = File(binaries.nativeLibraryDir, BinaryRole.PROOT.fileName)
        val resolvConf = runCatching { RootfsProvisioner.ensureResolvConf(File(tmpDir, "resolv.conf")) }
            .getOrElse {
                return DoctorCheck(
                    SMOKE_ID, SMOKE_LABEL, CheckState.FAIL,
                    "could not write the guest DNS file for the smoke test: ${it.message}",
                )
            }
        val environment = RootfsProvisioner.environment(binaries.nativeLibraryDir, binaries.loaderPaths(), tmpDir)
        val passed = mutableListOf<String>()
        for (step in RootfsProvisioner.SMOKE_STEPS) {
            val command = RootfsProvisioner.smokeCommand(proot, rootfsDir, resolvConf, step.guestCommand)
            val outcome = runCatching { runner.run(command, environment, workingDir = null, timeoutMillis) }
                .getOrElse { return DoctorCheck(SMOKE_ID, SMOKE_LABEL, CheckState.FAIL, smokeFailure(step, command, null, it.message ?: it.javaClass.simpleName)) }
            val output = (outcome.stdout + "\n" + outcome.stderr).trim().lineSequence()
                .filter { it.isNotBlank() }.toList()
            val firstLine = output.firstOrNull().orEmpty()
            val healthy = outcome.exitCode == 0 && outcome.error == null &&
                (step.expectOutput == null || firstLine.contains(step.expectOutput))
            if (!healthy) {
                return DoctorCheck(
                    SMOKE_ID, SMOKE_LABEL, CheckState.FAIL,
                    smokeFailure(step, command, outcome, firstLine.ifBlank { output.joinToString(" | ") }),
                )
            }
            passed += "${step.id}: ${firstLine.ifBlank { "exit 0" }}"
        }
        return DoctorCheck(SMOKE_ID, SMOKE_LABEL, CheckState.PASS, passed.joinToString("; "))
    }

    /** Plain-language cause for one failed smoke step, including the command to rerun. */
    private fun smokeFailure(
        step: RootfsProvisioner.SmokeStep,
        command: List<String>,
        outcome: RunOutcome?,
        output: String?,
    ): String {
        val text = listOfNotNull(output, outcome?.error).joinToString(" ").lowercase()
        val cause = when {
            outcome?.error?.contains("Timed out") == true ->
                "proot hung (the kernel may deny ptrace even with PROOT_NO_SECCOMP=1, which the " +
                    "environment sets) - ${step.explains}"
            outcome?.exitCode == null ->
                "proot could not be started (${outcome?.error ?: "no exit code"}): " +
                    "exit 126/EACCES means the +x bit could not be set in nativeLibraryDir - ${step.explains}"
            "exec format error" in text ->
                "Exec format error: a guest binary in this rootfs is not arm64 (wrong flavour imported?) - ${step.explains}"
            outcome.exitCode == 127 || "not found" in text || "no such file or directory" in text ->
                "'${step.guestCommand.first()}' is missing from the rootfs - it is not unpacked or is the wrong " +
                    "distribution flavour - ${step.explains}"
            "ptrace" in text || "seccomp" in text || "operation not permitted" in text ->
                "the kernel refused ptrace/seccomp inside proot - ${step.explains}"
            "permission denied" in text ->
                "permission denied while exec'ing in the jail (SELinux or a missing +x bit) - ${step.explains}"
            step.expectOutput != null && outcome.exitCode == 0 ->
                "the answer was not what apt needs (expected '${step.expectOutput}', got " +
                    "'${output?.takeIf { it.isNotBlank() } ?: "no output"}') - ${step.explains}"
            else -> "exit ${outcome?.exitCode}${output?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""} - ${step.explains}"
        }
        return "${step.label} failed: $cause (command: ${command.joinToString(" ")})"
    }

    /**
     * 10. Host-side reachability of the archive apt will use. Non-blocking: the
     * app's own TLS path and the guest's plain-http path can disagree (captive
     * portals, corporate proxies), so only running apt proves the in-jail route.
     */
    private fun checkNetwork(reachability: Reachability): DoctorCheck {
        val result = runCatching { reachability.probe(PORTS_RELEASE_URL) }.getOrElse { "probe failed: ${it.message}" }
        return if (result == "HTTP 200") {
            DoctorCheck("network", "ports.ubuntu.com reachable", CheckState.PASS, result, blocking = false)
        } else {
            DoctorCheck(
                "network",
                "ports.ubuntu.com reachable",
                CheckState.WARN,
                "$result - the guest's http path may still work; apt's own output decides.",
                blocking = false,
            )
        }
    }
}
