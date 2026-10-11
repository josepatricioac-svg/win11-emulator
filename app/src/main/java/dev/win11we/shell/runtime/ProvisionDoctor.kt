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
 * The Provision doctor: verifies every host-side prerequisite for
 * [RootfsProvisioner.provision] BEFORE spending minutes inside proot, and names
 * the first failing step in plain language. A bare "apt-get failed" is never
 * the end of the story again: either the doctor already explains why apt could
 * not work, or apt ran and its exit code + last 30 lines are on the card.
 *
 * Deliberately host-side and pure (files + one optional HTTP HEAD through the
 * [Reachability] seam): no proot, no rootfs processes, so it is fast, safe and
 * unit-tested. What it cannot verify from outside (in-jail DNS resolution,
 * dpkg lock state) is what the apt tail output is for.
 */
object ProvisionDoctor {

    /** URL -> "HTTP <code>" or "unreachable: <reason>". Injectable for tests. */
    fun interface Reachability {
        fun probe(url: String): String
    }

    /** GTK3 + fonts + CA certs install to ~250 MB; apt caches the .debs on top. */
    const val MIN_FREE_MB = 400L
    const val LOW_FREE_MB = 800L

    /** The index apt fetches first; probed over https (the app trusts system CAs). */
    const val PORTS_RELEASE_URL = "https://ports.ubuntu.com/ubuntu-ports/dists/jammy/Release"

    private const val EM_AARCH64 = 183 // 0xB7
    private const val EM_X86_64 = 62 // 0x3E

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

    fun run(
        binaries: NativeBinaries,
        rootfsDir: File,
        filesDir: File,
        includeNetwork: Boolean = true,
        reachability: Reachability = defaultReachability,
    ): DoctorReport = DoctorReport(
        listOf(
            checkBinaries(binaries),
            checkRootfsExtracted(rootfsDir),
            checkRootfsArchitecture(rootfsDir),
            checkSourcesList(rootfsDir),
            checkResolvConf(rootfsDir),
            checkDiskSpace(rootfsDir),
            checkTmpWritable(filesDir),
        ) + if (includeNetwork) listOf(checkNetwork(reachability)) else emptyList(),
    )

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
    private fun elfMachine(file: File): Int? {
        val header = ByteArray(20)
        val read = file.inputStream().use { it.read(header) }
        if (read < 20 || header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
        ) {
            return null
        }
        val littleEndian = header[5] == 1.toByte()
        val lo = header[18].toInt() and 0xFF
        val hi = header[19].toInt() and 0xFF
        return if (littleEndian) lo or (hi shl 8) else (hi shl 8) or lo
    }

    /**
     * 4. arm64 packages live on ports.ubuntu.com, not archive.ubuntu.com. The
     * pinned ubuntu-base ships the right sources.list; an imported or edited
     * rootfs may not, and apt would then 404 every index.
     */
    private fun checkSourcesList(rootfsDir: File): DoctorCheck {
        val sources = File(rootfsDir, "etc/apt/sources.list")
        val text = runCatching { if (sources.isFile) sources.readText() else "" }.getOrDefault("")
        val debLines = text.lineSequence().filter { it.trimStart().startsWith("deb ") }.toList()
        return when {
            debLines.isEmpty() -> DoctorCheck(
                "apt-sources",
                "apt sources.list",
                CheckState.FAIL,
                "No active 'deb' lines in ${sources.absolutePath} - apt has nothing to download from.",
            )
            debLines.any { "ports.ubuntu.com" in it } ->
                DoctorCheck("apt-sources", "apt sources.list", CheckState.PASS, "${debLines.size} deb lines, ports.ubuntu.com")
            debLines.any { "archive.ubuntu.com" in it || "security.ubuntu.com" in it } -> DoctorCheck(
                "apt-sources",
                "apt sources.list",
                CheckState.FAIL,
                "sources.list points at the x86 archive (archive/security.ubuntu.com); arm64 packages live on " +
                    "ports.ubuntu.com. This rootfs is the wrong flavour or its sources were edited.",
            )
            else -> DoctorCheck(
                "apt-sources",
                "apt sources.list",
                CheckState.WARN,
                "sources.list has ${debLines.size} deb lines but none name ports.ubuntu.com - check they serve arm64.",
            )
        }
    }

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

    /** 6. Free space on the partition holding the rootfs. */
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

    /** 7. The host TMPDIR the proot environment points at must be writable. */
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

    /**
     * 8. Host-side reachability of the archive apt will use. Non-blocking: the
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
