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

    /** Ubuntu 22.04.5 base rootfs, arm64. Hash from Ubuntu's published SHA256SUMS. */
    val ROOTFS = Artifact(
        id = "rootfs",
        label = "Ubuntu 22.04.5 base rootfs (arm64)",
        url = "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04.5/release/" +
            "ubuntu-base-22.04.5-base-arm64.tar.gz",
        sha256 = "075d4abd2817a5023ab0a82f5cb314c5ec0aa64a9c0b40fd3154ca3bfdae979f",
        sizeBytes = 27_671_306L,
        requiredFreeBytes = 1_500L * 1024 * 1024, // ~26 MB file, ~1.4 GB unpacked
        description = "The glibc userland proot jails into. Unpacked with tar into <files>/rootfs.",
    )

    /**
     * Ubuntu 24.04's Wine 9.0 arm64 loader packages. Hashes from the archive's own
     * `Packages` index (SHA256 field). [Artifact.incompleteAlone] is true: these are the
     * front-ends, the real payload (`wine-development` + libraries) and their dependency
     * tree still have to be provisioned inside the rootfs (R4/P2).
     */
    val WINE64_DEB = Artifact(
        id = "wine64-deb",
        label = "wine64 9.0~repack-4build3 (arm64 .deb)",
        url = "https://ports.ubuntu.com/pool/universe/w/wine/wine64_9.0~repack-4build3_arm64.deb",
        sha256 = "d44c0266f404ad59944ef7245d3162affa6415ee58193c5fa7cdc1aa105a46bd",
        sizeBytes = 244_164L,
        requiredFreeBytes = 200L * 1024 * 1024,
        description = "Wine 9.x arm64 loader. Needs wine-development and its libraries inside the rootfs.",
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

    val ALL = listOf(ROOTFS, WINE64_DEB, WINE_ALL_DEB)

    fun byId(id: String): Artifact? = ALL.firstOrNull { it.id == id }

    /** Total free space the full set insists on before any download starts. */
    val requiredFreeBytes: Long get() = ALL.sumOf { it.requiredFreeBytes }
}
