package dev.win11we.shell.runtime.download

import java.io.File

/**
 * One downloadable piece of the runtime. Hashes are **pinned**: a download whose
 * SHA-256 does not match is deleted, never unpacked and never used (fail closed).
 */
data class Artifact(
    val id: String,
    val label: String,
    val url: String,
    val sha256: String,
    /** Expected size in bytes; 0 means "unknown, whatever the server says". */
    val sizeBytes: Long,
    /** Free space we insist on before starting, in bytes. */
    val requiredFreeBytes: Long,
    val description: String,
    /** True when this artifact alone is not a working install (Wine needs its deps). */
    val incompleteAlone: Boolean = false,
    val fileName: String = url.substringAfterLast('/'),
) {
    init {
        require(sha256.length == 64) { "pinned SHA-256 must be 64 hex characters" }
        // https only: Android 9+ blocks cleartext by default, and a pinned hash is not a
        // substitute for transport security when the URL also tells us the file name.
        require(url.startsWith("https://")) { "artifact url must be https: $url" }
    }

    /** Where the verified file lands. */
    fun targetFile(dir: File) = File(dir, fileName)

    /** Where an interrupted download is kept so it can be resumed with `Range`. */
    fun partialFile(dir: File) = File(dir, fileName + ".part")

    fun sizeLabel(): String = when {
        sizeBytes <= 0 -> "size unknown"
        sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
        else -> "%.1f MB".format(sizeBytes / (1024.0 * 1024.0))
    }
}

/**
 * The pinned manifest. Every hash below was taken from the publisher's own
 * checksum list/index (see docs/THIRD_PARTY.md for the retrieval date and method);
 * nothing here is guessed.
 */
object RuntimeManifest {

    /**
     * Ubuntu 24.04.5 (noble) base rootfs, arm64. Hash from Ubuntu's published SHA256SUMS
     * for the 24.04.5 release (retrieved 2026-10-15, download verified byte-for-byte).
     *
     * WHY 24.04 AND NOT 22.04 - the two pinned Wine `.deb`s below are **noble** builds:
     * `wine64` declares `Depends: libc6 (>= 2.38)`, and jammy (22.04) ships libc6 2.35.
     * On a jammy rootfs that dependency is unsatisfiable, so `dpkg -i` fails and
     * `apt-get -f install` cannot repair it (jammy's own wine64 is 6.0.3, a different
     * package). No Wine payload then ever lands in the rootfs, so `wineboot` cannot run,
     * so no prefix can be created - which surfaces as the misleading
     * "Wine prefix not found" refusal. Noble ships libc6 2.39 and wine 9.0~repack-4build3,
     * i.e. exactly the pinned debs. The rootfs and the Wine payload must be one release.
     */
    val ROOTFS = Artifact(
        id = "rootfs",
        label = "Ubuntu 24.04.5 base rootfs (arm64)",
        url = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.5/release/" +
            "ubuntu-base-24.04.5-base-arm64.tar.gz",
        sha256 = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2",
        sizeBytes = 29_936_675L,
        requiredFreeBytes = 1_500L * 1024 * 1024, // ~29 MB file, ~1.5 GB unpacked
        description = "The glibc userland proot jails into. Unpacked with tar into <files>/rootfs.",
    )

    /**
     * Ubuntu 24.04's Wine 9.0 arm64 loader packages - the SAME release as [ROOTFS], which
     * is what makes them installable (see ROOTFS's note on `libc6 (>= 2.38)`). Hashes from
     * the noble archive's own `Packages` index (SHA256 field).
     *
     * [Artifact.incompleteAlone] is true, and worth being precise about: `wine64` carries
     * ONLY the binary loader (`/usr/lib/wine/wine64`, `/usr/lib/wine/wineserver64`). The
     * real payload - `libwine (= 9.0~repack-4build3)`, which is where `wineboot` and every
     * builtin DLL live - is a hard dependency resolved by apt from the noble archive. A
     * prefix cannot be built without it, so a Wine install that only unpacked these two
     * debs would still fail at `wineboot`.
     */
    val WINE64_DEB = Artifact(
        id = "wine64-deb",
        label = "wine64 9.0~repack-4build3 (arm64 .deb)",
        url = "https://ports.ubuntu.com/pool/universe/w/wine/wine64_9.0~repack-4build3_arm64.deb",
        sha256 = "d44c0266f404ad59944ef7245d3162affa6415ee58193c5fa7cdc1aa105a46bd",
        sizeBytes = 244_164L,
        requiredFreeBytes = 200L * 1024 * 1024,
        description = "Wine 9.x arm64 binary loader. Depends on libc6 (>= 2.38) and libwine, which apt " +
            "resolves inside the rootfs - libwine is where wineboot and the builtins live.",
        incompleteAlone = true,
    )

    val WINE_ALL_DEB = Artifact(
        id = "wine-all-deb",
        label = "wine 9.0~repack-4build3 (all .deb)",
        url = "https://ports.ubuntu.com/pool/universe/w/wine/wine_9.0~repack-4build3_all.deb",
        sha256 = "5606f0f5677ac0fb882e3fdc8ce03a60232cdf2930e912ed3e42db2b9e0d5f7f",
        sizeBytes = 51_966L,
        requiredFreeBytes = 200L * 1024 * 1024,
        description = "Architecture-independent Wine front-end package.",
        incompleteAlone = true,
    )

    /**
     * Firefox ESR for linux-aarch64: Mozilla's official self-contained build (bundles its
     * own libs; links the rootfs's GTK3 stack). 153.5.0esr is the CURRENT ESR branch as of
     * 2026-10-15 (per Mozilla's product-details `FIREFOX_ESR`); the previously pinned
     * 140.17.0esr is the last release of a branch that is now past its security-support
     * window, so staying on it meant shipping a browser that no longer receives fixes.
     *
     * The SHA-256 was computed from the release tarball AFTER its SHA-512 was verified
     * byte-for-byte against Mozilla's own `SHA512SUMS` for 153.5.0esr
     * (`8e8635cc…01bc5a54`, retrieved 2026-10-15; see docs/THIRD_PARTY.md). The build
     * needs only GLIBC >= 2.28 (highest versioned symbol in `libxul.so`), so it is happy
     * on the noble rootfs's glibc 2.39. Unpacked with the guest's tar+xz to
     * `<rootfs>/opt/firefox-esr` (tarball root `firefox/` stripped).
     */
    val FIREFOX_ESR = Artifact(
        id = "firefox-esr",
        label = "Firefox ESR 153.5.0 (linux-aarch64)",
        url = "https://archive.mozilla.org/pub/firefox/releases/153.5.0esr/" +
            "linux-aarch64/en-US/firefox-153.5.0esr.tar.xz",
        sha256 = "6579949c1d2d98f0d045cec780fa24752b57984f6e52d189ccfe003cf97f3d0b",
        sizeBytes = 74_115_684L,
        requiredFreeBytes = 550L * 1024 * 1024, // ~71 MB file, ~290 MB unpacked
        description = "The desktop browser engine: unpacks into the rootfs, runs under proot on the " +
            "shell's X server (:100). Needs the rootfs GUI libraries (same provisioning step as Wine).",
        incompleteAlone = true,
    )

    val ALL = listOf(ROOTFS, WINE64_DEB, WINE_ALL_DEB, FIREFOX_ESR)

    fun byId(id: String): Artifact? = ALL.firstOrNull { it.id == id }

    /** Total free space the full set insists on before any download starts. */
    val requiredFreeBytes: Long get() = ALL.sumOf { it.requiredFreeBytes }
}
