package dev.win11we.shell.runtime.download

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPInputStream

/**
 * A minimal, dependency-free TAR reader for the R3 extraction step: ustar plus
 * the GNU long-name extension, which is all Ubuntu's `ubuntu-base` and Mozilla's
 * Firefox tarballs use. Runs on the device inside the app's own sandbox - no
 * root, no native tar, no proot needed for *this* half of the install (proot is
 * still what runs everything afterwards).
 *
 * Deliberate, documented behaviours:
 * - **Fail closed on traversal**: an entry whose resolved path escapes the
 *   target directory aborts the whole extraction (Zip-Slip protection).
 * - **Header checksums are verified**; a corrupt archive aborts instead of
 *   writing garbage.
 * - Device nodes, FIFOs and sockets are **skipped and counted**, not created:
 *   unprivileged Android cannot mknod, and proot emulates the standard /dev
 *   nodes (/dev/null, /dev/zero, /dev/tty, /dev/random, /dev/urandom) at
 *   runtime, which is why proot-based distros extract rootfs archives the same
 *   way.
 * - Owner/group ids are ignored (everything lands as the app user); file modes
 *   are applied, so executables stay executable.
 * - Hardlinks are created with [Files.createLink]; if the filesystem refuses,
 *   the entry falls back to a copy (Android's f2fs/ext4 data dirs support
 *   hardlinks, and proot's --link2symlink covers the rest).
 */
class TarExtractor(
    /** Already-decompressed tar stream (gzip via [GZIPInputStream], xz via org.tukaani). */
    input: InputStream,
    private val log: (String) -> Unit = {},
) {
    private val stream = BufferedInputStream(input, 64 * 1024)

    data class Result(
        val files: Int,
        val directories: Int,
        val symlinks: Int,
        val hardlinks: Int,
        val skipped: Int,
        val complete: Boolean,
        val error: String?,
    ) {
        val success: Boolean get() = complete && error == null
    }

    private class CorruptArchive(message: String) : Exception(message)

    fun extractTo(
        target: File,
        stripComponents: Int = 0,
        cancelled: () -> Boolean = { false },
    ): Result {
        var files = 0
        var dirs = 0
        var symlinks = 0
        var hardlinks = 0
        var skipped = 0
        var zeroBlocks = 0
        var longName: String? = null
        var longLink: String? = null
        var paxPath: String? = null
        var paxSize: Long? = null
        val header = ByteArray(512)
        val root = target.absoluteFile.toPath().normalize()
        runCatching { target.mkdirs() }
        try {
            while (true) {
                if (cancelled()) return Result(files, dirs, symlinks, hardlinks, skipped, false, "cancelled")
                if (!readFully(header)) break
                if (header.all { it == 0.toByte() }) {
                    if (++zeroBlocks >= 2) break
                    continue
                }
                zeroBlocks = 0
                verifyChecksum(header)
                val typeFlag = header[156].toInt().toChar()
                val headerSize = parseOctal(header, 124, 12).toInt()
                val mode = parseOctal(header, 100, 8).toInt()

                // POSIX/PAX extended headers (GNU tar writes one per entry for
                // xattrs/timestamps): parse path/size overrides, consume silently.
                if (typeFlag == 'x' || typeFlag == 'g') {
                    val records = readData(headerSize).toString(Charsets.UTF_8)
                    if (typeFlag == 'x') {
                        paxRecord(records, "path")?.let { paxPath = it }
                        paxRecord(records, "size")?.toLongOrNull()?.let { paxSize = it }
                    }
                    skipPadding(headerSize)
                    continue
                }

                // GNU long name / long link entries carry the real value as data.
                if (typeFlag == 'L') {
                    longName = readData(headerSize).toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                    skipPadding(headerSize)
                    continue
                }
                if (typeFlag == 'K') {
                    longLink = readData(headerSize).toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                    skipPadding(headerSize)
                    continue
                }

                val rawName = longName ?: paxPath ?: ustarName(header)
                val linkName = longLink ?: field(header, 157, 100)
                val size = paxSize?.toInt() ?: headerSize
                longName = null
                longLink = null
                paxPath = null
                paxSize = null

                val relative = strip(rawName, stripComponents)
                if (relative == null) { // entry lives entirely inside stripped prefixes
                    skipData(size)
                    continue
                }
                val out = root.resolve(relative).normalize()
                if (!out.startsWith(root)) throw CorruptArchive("entry escapes the target directory: $rawName")
                val outFile = out.toFile()

                when (typeFlag) {
                    '5' -> {
                        outFile.mkdirs()
                        applyMode(outFile, mode)
                        dirs++
                    }
                    '0', '\u0000', '7' -> {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { sink ->
                            var remaining = size.toLong()
                            val buffer = ByteArray(32 * 1024)
                            while (remaining > 0) {
                                val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (read <= 0) throw CorruptArchive("truncated entry: $rawName")
                                sink.write(buffer, 0, read)
                                remaining -= read
                            }
                        }
                        skipPadding(size)
                        applyMode(outFile, mode)
                        files++
                    }
                    '2' -> {
                        outFile.parentFile?.mkdirs()
                        runCatching {
                            Files.deleteIfExists(out)
                            Files.createSymbolicLink(out, java.nio.file.Paths.get(linkName))
                            symlinks++
                        }.onFailure { skipped++; log("symlink skipped ($rawName): ${it.message}") }
                    }
                    '1' -> {
                        val source = root.resolve(strip(linkName, stripComponents) ?: linkName).normalize()
                        outFile.parentFile?.mkdirs()
                        val linked = runCatching {
                            Files.deleteIfExists(out)
                            Files.createLink(out, source)
                        }.isSuccess
                        if (linked) {
                            hardlinks++
                        } else {
                            runCatching { source.toFile().copyTo(outFile, overwrite = true); files++ }
                                .onFailure { skipped++; log("hardlink skipped ($rawName): ${it.message}") }
                        }
                    }
                    '3', '4', '6' -> { // char/block devices and FIFOs: see class docs
                        skipData(size)
                        skipped++
                    }
                    else -> {
                        skipData(size)
                        skipped++
                        log("unknown tar entry type '$typeFlag' skipped: $rawName")
                    }
                }
            }
            return Result(files, dirs, symlinks, hardlinks, skipped, true, null)
        } catch (error: CorruptArchive) {
            return Result(files, dirs, symlinks, hardlinks, skipped, false, error.message)
        } catch (error: Exception) {
            return Result(files, dirs, symlinks, hardlinks, skipped, false, "${error.javaClass.simpleName}: ${error.message}")
        }
    }

    // --- tar plumbing ---------------------------------------------------------

    private fun readFully(buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = stream.read(buffer, read, buffer.size - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }

    private fun readData(size: Int): ByteArray {
        val data = ByteArray(size)
        if (size > 0 && !readFully(data)) throw CorruptArchive("truncated tar data")
        return data
    }

    private fun skipData(size: Int) {
        var remaining = size.toLong()
        while (remaining > 0) {
            val n = stream.skip(remaining)
            if (n <= 0) {
                if (stream.read() < 0) throw CorruptArchive("truncated tar data")
                remaining--
            } else {
                remaining -= n
            }
        }
        skipPadding(size)
    }

    private fun skipPadding(size: Int) {
        // BufferedInputStream.skip() may legally skip LESS than asked (it only
        // advances inside its current buffer), so this must loop - a silent
        // short skip here misaligns every following header.
        var remaining = ((512 - size % 512) % 512).toLong()
        while (remaining > 0) {
            val n = stream.skip(remaining)
            if (n <= 0) {
                if (stream.read() < 0) return
                remaining--
            } else {
                remaining -= n
            }
        }
    }

    private fun verifyChecksum(header: ByteArray) {
        val stored = parseOctal(header, 148, 8).toInt()
        var unsigned = 0
        var signed = 0
        for (index in 0 until 512) {
            val byte = header[index].toInt()
            val value = if (index in 148..155) ' '.code else byte
            unsigned += value and 0xFF
            signed += value
        }
        if (stored != unsigned && stored != signed) throw CorruptArchive("bad header checksum (entry at offset)")
    }

    /** Parses one "%d key=value\n" record out of a PAX extended header. */
    private fun paxRecord(data: String, key: String): String? {
        var pos = 0
        while (pos < data.length) {
            val space = data.indexOf(' ', pos)
            if (space < 0) return null
            val length = data.substring(pos, space).toIntOrNull() ?: return null
            if (length <= 0 || pos + length > data.length) return null
            val record = data.substring(space + 1, pos + length)
            val equals = record.indexOf('=')
            if (equals > 0 && record.substring(0, equals) == key) {
                return record.substring(equals + 1).trimEnd('\n')
            }
            pos += length
        }
        return null
    }

    /** Octal field; GNU base-256 (0x80-prefixed) values are supported too. */
    private fun parseOctal(header: ByteArray, offset: Int, length: Int): Long {
        if (header[offset].toInt() and 0x80 != 0) {
            var value = (header[offset].toInt() and 0x7F).toLong()
            for (index in offset + 1 until offset + length) {
                value = (value shl 8) or (header[index].toInt() and 0xFF).toLong()
            }
            return value
        }
        val text = field(header, offset, length)
        if (text.isEmpty()) return 0
        return text.toLongOrNull(8) ?: throw CorruptArchive("malformed octal field: '$text'")
    }

    private fun field(header: ByteArray, offset: Int, length: Int): String =
        String(header, offset, length, Charsets.US_ASCII).trimEnd('\u0000', ' ').trim()

    /** name + ustar prefix, with the strip-components transform applied. Null = strip away. */
    private fun ustarName(header: ByteArray): String {
        val name = field(header, 0, 100)
        val prefix = field(header, 345, 155)
        val full = if (prefix.isEmpty()) name else "$prefix/$name"
        return full
    }

    private fun strip(name: String, components: Int): String? {
        val cleaned = name.trimStart('/')
        if (components == 0) return cleaned.ifEmpty { null }
        val parts = cleaned.split('/')
        if (parts.size <= components) return null
        return parts.drop(components).joinToString("/").ifEmpty { null }
    }

    private fun applyMode(file: File, mode: Int) {
        if (mode == 0) return
        runCatching {
            // tar stores classic unix permission bits (octal 0777 mask).
            val permissions = mutableSetOf<PosixFilePermission>()
            if (mode and 0b100_000_000 != 0) permissions += PosixFilePermission.OWNER_READ // 0400
            if (mode and 0b010_000_000 != 0) permissions += PosixFilePermission.OWNER_WRITE // 0200
            if (mode and 0b001_000_000 != 0) permissions += PosixFilePermission.OWNER_EXECUTE // 0100
            if (mode and 0b000_100_000 != 0) permissions += PosixFilePermission.GROUP_READ // 0040
            if (mode and 0b000_010_000 != 0) permissions += PosixFilePermission.GROUP_WRITE // 0020
            if (mode and 0b000_001_000 != 0) permissions += PosixFilePermission.GROUP_EXECUTE // 0010
            if (mode and 0b000_000_100 != 0) permissions += PosixFilePermission.OTHERS_READ // 0004
            if (mode and 0b000_000_010 != 0) permissions += PosixFilePermission.OTHERS_WRITE // 0002
            if (mode and 0b000_000_001 != 0) permissions += PosixFilePermission.OTHERS_EXECUTE // 0001
            Files.setPosixFilePermissions(file.toPath(), permissions)
        }.onFailure {
            // Symbolic links reject chmod on some filesystems; not fatal
            // (isSymbolicLink never follows, so this checks the link itself).
            if (!Files.isSymbolicLink(file.toPath())) log("mode not applied to ${file.name}: ${it.message}")
        }
    }

    companion object {
        /** Convenience for the two archive shapes R3 ships: ubuntu-base (.tar.gz) and Firefox (.tar.xz). */
        fun open(archive: File, log: (String) -> Unit = {}): TarExtractor {
            val input = archive.inputStream().buffered()
            return when {
                archive.name.endsWith(".tar.gz") || archive.name.endsWith(".tgz") ->
                    TarExtractor(GZIPInputStream(input), log)
                archive.name.endsWith(".tar.xz") ->
                    TarExtractor(org.tukaani.xz.XZInputStream(input), log)
                else -> TarExtractor(input, log)
            }
        }
    }
}
