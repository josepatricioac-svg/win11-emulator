package dev.win11we.shell.container

import androidx.compose.runtime.Immutable
import dev.win11we.shell.settings.Box64Preset
import java.io.File

/** Lifecycle of a container as the shell sees it. */
enum class ContainerStatus(val label: String) {
    STOPPED("Stopped"),
    STARTING("Starting"),
    RUNNING("Running"),
    ERROR("Not ready"),
}

/** One drive mapping: a Windows letter pointing at a host directory. */
@Immutable
data class DriveMapping(val letter: Char, val hostPath: String) {
    init {
        require(letter.isLetter()) { "drive letter must be a letter" }
    }

    val guestRoot: String get() = "${letter.uppercaseChar()}:\\"
}

/**
 * Everything the runtime needs to start `proot -> box64 -> wine` for one prefix.
 * Immutable and serialisable, so the UI can edit a copy and only commit on Save.
 */
@Immutable
data class Container(
    val id: String,
    val name: String,
    /** Host directory of the Wine prefix; the guest sees it as C:. */
    val prefixPath: String,
    val preset: Box64Preset = Box64Preset.GENERAL,
    val drives: List<DriveMapping> = emptyList(),
    /** Extra variables, applied last so a user can override anything. */
    val extraEnv: Map<String, String> = emptyMap(),
    val dpi: Int = 96,
    val arch: WineArch = WineArch.WOW64,
    val wineDebug: String = "fixme-all",
    val status: ContainerStatus = ContainerStatus.STOPPED,
    val lastError: String? = null,
) {
    /** C: is the prefix itself, Z: the guest root - matching Wine's own defaults. */
    val allDrives: List<DriveMapping>
        get() = (listOf(DriveMapping('C', prefixPath), DriveMapping('Z', "/")) + drives)
            .distinctBy { it.letter.uppercaseChar() }

    fun withStatus(status: ContainerStatus, error: String? = null): Container =
        copy(status = status, lastError = error)

    companion object {
        /** A fresh container: 96 dpi, WoW64, C: and Z: only, no overrides. */
        fun defaults(id: String, name: String, prefixPath: String): Container =
            Container(id = id, name = name, prefixPath = prefixPath)
    }
}

enum class WineArch(val label: String, val wineLoader: String) {
    /** 64-bit prefix that also runs 32-bit Windows apps - what Office needs. */
    WOW64("WoW64 (32 + 64 bit)", "wine64"),
    WIN64("64-bit only", "wine64"),
    ;

    /**
     * The `WINEARCH` value a prefix of this architecture is created with.
     * Both entries are `win64` ON PURPOSE: this is an arm64-only stack and
     * Ubuntu arm64 ships no 32-bit Wine loader, so a `win32` prefix cannot be
     * created here - [WinePrefixManager.inspect] treats a prefix whose registry
     * says `#arch=win32` as a mismatch instead of silently launching into it.
     * 32-bit WINDOWS APPS still run, inside a win64 prefix, through WoW64 +
     * box64 - which is what the WOW64 entry means.
     */
    val wineArch: String get() = "win64"
}


/**
 * Host path -> guest (Windows) path using the container's drive mappings.
 * Longest matching prefix wins so a mapped subdirectory beats Z:.
 * Pure function, unit-tested; R2 uses it for every "open with" handoff.
 */
object GuestPaths {

    fun toGuest(hostPath: String, container: Container): String {
        val normalized = normalize(hostPath)
        val best = container.allDrives
            .map { it to normalize(it.hostPath) }
            .filter { (_, host) -> host.isNotEmpty() && (normalized == host || normalized.startsWith(host.trimEnd('/') + "/")) }
            .maxByOrNull { (_, host) -> host.length }
            ?: return normalized
        val (drive, host) = best
        val relative = normalized.removePrefix(host.trimEnd('/')).trimStart('/')
        return if (relative.isEmpty()) {
            drive.guestRoot
        } else {
            drive.guestRoot + relative.replace('/', '\\')
        }
    }

    /** Guest path -> host path, for opening a Windows path from inside the shell. */
    fun toHost(guestPath: String, container: Container): String? {
        val letter = guestPath.firstOrNull()?.uppercaseChar() ?: return null
        if (guestPath.length < 2 || guestPath[1] != ':') return null
        val drive = container.allDrives.firstOrNull { it.letter.uppercaseChar() == letter } ?: return null
        val relative = guestPath.substring(2).trimStart('\\', '/').replace('\\', '/')
        return if (relative.isEmpty()) drive.hostPath else drive.hostPath.trimEnd('/') + "/" + relative
    }

    private fun normalize(path: String): String = path.trim().trimEnd('/').ifEmpty { "/" }
}

/**
 * Builds the **fully explicit** environment for `proot -> box64 -> wine`.
 * Nothing is inherited implicitly: R2 hands this map straight to the process
 * builder, and R4's self-test runs the same map through `wine64 --version`.
 */
object WineEnvironment {

    /** Box64 tuning per preset; empty map means "box64 defaults". */
    fun presetVariables(preset: Box64Preset): Map<String, String> = when (preset) {
        Box64Preset.GENERAL -> emptyMap()
        Box64Preset.OFFICE -> mapOf(
            "BOX64_DYNAREC_BIGBLOCK" to "2",
            "BOX64_DYNAREC_SAFEFLAGS" to "1",
            "BOX64_DYNAREC_FASTROUND" to "1",
            "BOX64_DYNAREC_X87DOUBLE" to "1",
            "BOX64_MAXCPU" to "4",
        )
        Box64Preset.DOTNET -> mapOf(
            "BOX64_DYNAREC_BIGBLOCK" to "3",
            "BOX64_DYNAREC_SAFEFLAGS" to "0",
            "BOX64_UNITYPLAYER" to "0",
        )
        Box64Preset.ELECTRON -> mapOf(
            "BOX64_DYNAREC_BIGBLOCK" to "2",
            "BOX64_DYNAREC_SAFEFLAGS" to "1",
            // Chromium's sandbox needs either this or a user namespace; documented in R2.
            "BOX64_ENV" to "CHROME_DEVEL_SANDBOX=/usr/local/sbin/chrome-devel-sandbox",
        )
    }

    /**
     * @param xDisplay the private X socket or display the app's own X server uses (D1).
     * @param prootRootfs host path of the Ubuntu rootfs downloaded in R3.
     * @param nativeLibraryDir where the shipped `lib*.so` binaries live (R1).
     */
    fun build(
        container: Container,
        xDisplay: String,
        prootRootfs: String,
        nativeLibraryDir: String,
        locale: String = "en_US.UTF-8",
        audioSocket: String? = null,
    ): Map<String, String> {
        val env = LinkedHashMap<String, String>()

        // --- display, audio, locale -------------------------------------------------
        env["DISPLAY"] = xDisplay
        audioSocket?.let {
            env["PULSE_SERVER"] = it
            env["PULSE_RUNTIME_PATH"] = "/run/user/1000/pulse"
        }
        env["LANG"] = locale
        env["LC_ALL"] = locale

        // --- Wine -------------------------------------------------------------------
        env["WINEPREFIX"] = "/root/.wine"               // guest path; the prefix is bind-mounted there
        env["WINEARCH"] = container.arch.wineArch       // explicit per arch (win64 on this arm64 stack; see WineArch)
        env["WINEDEBUG"] = container.wineDebug
        env["WINEDLLOVERRIDES"] = "mscoree,mshtml="     // never auto-install Gecko/Mono
        env["WINE_ESYNC_PULSE"] = "1"
        env["WINE_DO_NOT_CREATE_EXPLORER_WINDOW"] = "1"
        env["GDK_DPI_SCALE"] = "%.2f".format(container.dpi / 96.0)
        env["QT_SCALE_FACTOR"] = "%.2f".format(container.dpi / 96.0)

        // --- box64 / proot ------------------------------------------------------------
        env.putAll(presetVariables(container.preset))
        env["BOX64_NOBANNER"] = "1"
        env["PROOT_ROOTFS"] = prootRootfs
        env["PROOT_LOADER"] = "$nativeLibraryDir/libproot-loader.so"
        env["PROOT_LOADER_32"] = "$nativeLibraryDir/libproot-loader32.so"
        // proot's seccomp-based syscall filter misbehaves on several Android
        // kernels (the documented kill switch is plain ptrace) - provisioning
        // and the doctor already run with it; guest sessions must too, or
        // wine/browser children die in ways that read as "emulation broken".
        env["PROOT_NO_SECCOMP"] = "1"


        // --- paths inside the guest ---------------------------------------------------
        env["HOME"] = "/root"
        env["USER"] = "user"
        env["TMPDIR"] = "/tmp"
        env["XDG_RUNTIME_DIR"] = "/tmp/runtime"
        env["PATH"] = listOf(
            "/usr/local/sbin", "/usr/local/bin", "/usr/sbin", "/usr/bin", "/sbin", "/bin",
            "/opt/wine/bin", nativeLibraryDir,
        ).joinToString(":")
        env["LD_LIBRARY_PATH"] = listOf("/usr/lib/aarch64-linux-gnu", "/usr/lib", nativeLibraryDir)
            .joinToString(":")

        // --- the caller's overrides always win ----------------------------------------
        env.putAll(container.extraEnv)
        return env.toSortedMap()
    }

    /** The command line R2 execs; kept here so the Diagnostics screen can print it. */
    fun wineCommand(container: Container, vararg args: String): List<String> =
        listOf(container.arch.wineLoader) + args.toList()
}
