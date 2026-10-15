package dev.win11we.shell.runtime

import java.io.File

/**
 * Whether the rootfs actually carries a Wine payload - the fact a container's
 * "prefix missing" refusal has to quote.
 *
 * Why this exists: the Container Manager used to promise that a Wine prefix is
 * "built on first start", and first start then refused with "prefix not found".
 * Nothing builds a prefix: it is populated by running `wineboot` inside the
 * rootfs (Goal 2 step 2 / R4), and `wineboot` cannot run without a Wine payload,
 * which the browser Provision list deliberately does not install (Wine's
 * dependency tree is a separate, much larger provisioning decision). So the
 * honest chain is: no Wine payload -> no wineboot -> no prefix -> Start refuses.
 * This object makes the first link observable instead of a mystery.
 *
 * Pure file checks, unit-tested like the rest of the runtime package.
 */
object WinePayload {

    /**
     * Where a provisioned Wine could put its launcher.
     *
     * VERIFIED against the real noble (24.04) arm64 `.deb` contents, because the obvious
     * guess is wrong here: **Ubuntu ships no `/usr/bin/wine64`.** The file lists are:
     * - `wine64_9.0~repack-4build3_arm64.deb` -> `/usr/lib/wine/wine64` (the loader,
     *   native arm64 ELF) and `/usr/lib/wine/wineserver64`. Nothing in `/usr/bin`.
     * - `libwine_9.0~repack-4build3_arm64.deb` -> `/usr/lib/wine/wineserver` (a `sh`
     *   wrapper that execs wineserver64) plus every builtin, incl. the Windows PE
     *   `wineboot.exe` under `/usr/lib/aarch64-linux-gnu/wine/`. No `/usr/bin` entries.
     * - `wine_9.0~repack-4build3_all.deb` -> `/usr/bin/wine-stable` plus the
     *   `*-stable` tool symlinks (`wineboot-stable`, `wineserver-stable`, `regedit-stable`, ...)
     *   and `/usr/lib/wine/wineapploader`.
     * The bare names users type (`wine`, `wineboot`, `wineserver`, `regedit`, ...) are
     * created afterwards by `update-alternatives` in the `wine` package's postinst - and
     * **`wine64` is not one of them**. So `/usr/bin/wine64` only ever exists if something
     * else put it there; it is listed first purely so a hand-made or upstream-tarball
     * payload is still detected, and `usr/lib/wine/wine64` is what the pinned debs hit.
     */
    val CANDIDATES: List<String> = listOf(
        "usr/bin/wine64",
        "usr/lib/wine/wine64",
        "opt/wine/bin/wine64",
        "opt/wine-stable/bin/wine64",
    )

    /** The first Wine launcher present in [rootfsDir], or null when there is none. */
    fun launcherIn(rootfsDir: File): File? =
        CANDIDATES.map { File(rootfsDir, it) }.firstOrNull { it.isFile }

    fun isProvisioned(rootfsDir: File): Boolean = launcherIn(rootfsDir) != null

    /**
     * Whether the payload can actually BUILD a prefix, which is the question
     * [WinePrefixManager.create] depends on.
     *
     * A loader alone is not enough: `wineboot --init` is what writes `system.reg`,
     * `user.reg` and `drive_c/`, and wineboot is a **Windows PE** shipped by `libwine`
     * (`…/wine/<triplet>/wineboot.exe`), driven through the loader. A rootfs that somehow
     * ended up with the loader but not `libwine` would pass [isProvisioned] and then fail
     * at wineboot with no useful message, so name that gap up front instead.
     *
     * @return null when the payload looks complete, else the plain-language missing piece.
     */
    fun payloadGap(rootfsDir: File): String? {
        if (launcherIn(rootfsDir) == null) {
            return "no Wine loader (${CANDIDATES.first()} absent)"
        }
        val libWine = File(rootfsDir, "usr/lib/wine/wineserver")
        val wineDir = File(rootfsDir, "usr/lib/aarch64-linux-gnu/wine")
        if (!libWine.isFile && !wineDir.isDirectory) {
            return "the loader is present but libwine is not (no /usr/lib/wine/wineserver and no " +
                "/usr/lib/aarch64-linux-gnu/wine) - wineboot and the builtin DLLs are missing, " +
                "so no prefix can be built. Re-run Install Wine payload and let apt resolve libwine."
        }
        return null
    }

    /**
     * A directory that merely exists is not a prefix: wineboot writes
     * `system.reg`, `user.reg` and `drive_c/` into it. Used to tell "prefix dir
     * missing" from "prefix dir exists but was never initialised".
     */
    fun prefixLooksInitialised(prefixDir: File): Boolean =
        prefixDir.isDirectory &&
            File(prefixDir, "system.reg").isFile &&
            File(prefixDir, "user.reg").isFile &&
            File(prefixDir, "drive_c").isDirectory
}
