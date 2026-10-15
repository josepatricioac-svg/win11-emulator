package dev.win11we.shell.runtime

import java.io.File

/**
 * The minimal ELF-header reader: just enough to answer "which CPU is this binary
 * for?" without exec'ing anything.
 *
 * Extracted from [ProvisionDoctor]'s rootfs-architecture check (which used it to
 * catch an amd64 SAF import) so [WinePayload] can answer the same question about
 * the Wine loader: the pinned Ubuntu payload is a NATIVE arm64 ELF that must not
 * be put behind box64 (an x86_64->arm64 translator cannot carry it), while a
 * future x86_64 payload (the still-open "decision 3b" in docs/PIPELINE_PLAN.md)
 * requires exactly that. `e_machine` sits at offset 18 in every ELF variant
 * (32/64-bit, either endianness), so 20 bytes decide it.
 */
object ElfHeader {

    const val EM_X86_64 = 62 // 0x3E
    const val EM_AARCH64 = 183 // 0xB7

    /** @return the ELF header's e_machine, or null when the file is not a readable ELF. */
    fun machine(file: File): Int? {
        val header = ByteArray(20)
        val read = runCatching { file.inputStream().use { it.read(header) } }.getOrDefault(-1)
        if (read < 20 || header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
        ) {
            return null
        }
        val littleEndian = header[5] == 1.toByte()
        val b18 = header[18].toInt() and 0xFF
        val b19 = header[19].toInt() and 0xFF
        // e_machine is a 16-bit value in the file's own endianness: byte 18 is
        // the LOW byte of a little-endian header and the HIGH byte of a
        // big-endian one. (The pre-extraction ProvisionDoctor copy had the BE
        // branch swapped - caught by ElfHeaderTest; every real payload here is
        // LE, so it was latent, but a latent wrong answer is still a bug.)
        return if (littleEndian) b18 or (b19 shl 8) else (b18 shl 8) or b19
    }
}
