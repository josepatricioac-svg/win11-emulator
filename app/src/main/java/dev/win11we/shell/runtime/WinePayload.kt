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
     * Where a provisioned Wine could put its launcher. Ubuntu's `wine64` package
     * installs `/usr/bin/wine64` (a wrapper) plus `/usr/lib/wine/wine64`; a
     * self-contained tarball payload (the likely Goal 2 shape, see
     * docs/BROWSER_ENGINES.md) would sit under /opt.
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
