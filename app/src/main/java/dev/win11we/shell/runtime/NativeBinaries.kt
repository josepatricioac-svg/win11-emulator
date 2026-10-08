package dev.win11we.shell.runtime

import java.io.File
import java.security.MessageDigest

/** Which shipped binary a record refers to. */
enum class BinaryRole(val fileName: String, val required: Boolean) {
    /** box64 (MIT): runs x86_64 Windows binaries on arm64. */
    BOX64("libbox64.so", true),

    /** proot (GPL-2.0, exec'd as a separate process, never linked): the glibc rootfs jail. */
    PROOT("libproot.so", true),

    /** proot's ptrace loaders; without them proot cannot run anything. */
    PROOT_LOADER("libproot-loader.so", true),
    PROOT_LOADER_32("libproot-loader32.so", false),
}

/** One shipped binary: where it is, whether we can exec it, how big it is. */
data class NativeBinary(
    val role: BinaryRole,
    val file: File,
    val present: Boolean,
    val executable: Boolean,
    val sizeBytes: Long,
) {
    val label: String get() = role.fileName
}

/** What [NativeBinaries.prepare] did, so the Diagnostics screen can say why it failed. */
enum class PrepareStatus {
    READY,
    MISSING_REQUIRED,
    PARTIAL,
    PERMISSION_DENIED,
    NO_DIRECTORY,

    /** A shipped binary does not match its pinned SHA-256: refuse to exec it. */
    CHECKSUM_MISMATCH,

    /** A required binary has no pinned hash recorded, which is also a refusal. */
    CHECKSUM_NOT_PINNED,
}

/** Result of comparing a shipped binary against its pinned hash. */
enum class ChecksumStatus { MATCH, MISMATCH, NOT_PINNED, MISSING }

data class ChecksumResult(val role: BinaryRole, val status: ChecksumStatus, val actualSha256: String?)

data class PrepareResult(val status: PrepareStatus, val message: String)

/**
 * R1: the shipped native binaries live in `jniLibs/arm64-v8a` as `lib*.so` and are
 * **executed in place from `nativeLibraryDir`**. That is deliberate:
 *
 * - `targetSdk 34` refuses `exec()` from the app's data directory (W^X), so copying the
 *   binary out and running it is not an option.
 * - AGP only packages `lib*.so` from `jniLibs`, hence the `lib` prefix on `libproot.so`
 *   and `libbox64.so` even though they are executables, not libraries.
 * - `jniLibs.useLegacyPackaging = true` keeps them uncompressed *and* extracted to
 *   `nativeLibraryDir`, which is what makes them runnable.
 *
 * No binary is bundled in the repository yet; see `docs/NATIVE_RUNTIME.md` for how they
 * are vendored and what their licences are.
 */
class NativeBinaries(
    val nativeLibraryDir: File,
    /** Pinned SHA-256 per file name; empty/absent pins make a required binary fail closed. */
    private val pinnedChecksums: Map<String, String> = emptyMap(),
) {

    constructor(path: String) : this(File(path))

    constructor(dir: File, pins: File) : this(dir, loadPins(pins))

    val exists: Boolean get() = nativeLibraryDir.isDirectory

    fun inventory(): List<NativeBinary> = BinaryRole.entries.map { role -> record(role) }

    fun record(role: BinaryRole): NativeBinary {
        val file = File(nativeLibraryDir, role.fileName)
        val present = file.isFile
        return NativeBinary(
            role = role,
            file = file,
            present = present,
            executable = present && file.canExecute(),
            sizeBytes = if (present) file.length() else 0L,
        )
    }

    /** Hashes are cached per file (path + size + mtime): a 10 MB binary is hashed once. */
    private val hashCache = HashMap<String, Pair<FileStamp, String?>>()

    private data class FileStamp(val size: Long, val modified: Long)

    private fun cachedSha256(file: File): String? {
        val key = file.absolutePath
        val stamp = FileStamp(file.length(), file.lastModified())
        val cached = hashCache[key]
        if (cached != null && cached.first == stamp) return cached.second
        val hash = sha256(file)
        hashCache[key] = stamp to hash
        return hash
    }

    /**
     * Hashes every present binary on a background thread and caches the results, so
     * opening Diagnostics does not hash megabytes on the main thread. Safe to call twice.
     */
    fun warmChecksums() {
        val files = BinaryRole.entries.map { File(nativeLibraryDir, it.fileName) }.filter { it.isFile }
        if (files.isEmpty()) return
        Thread { files.forEach { cachedSha256(it) } }.apply {
            name = "binary-hash"
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /** Verifies every present binary against its pin. Never throws. */
    fun verifyChecksums(): List<ChecksumResult> = BinaryRole.entries.map { role ->
        val binary = record(role)
        val pin = pinnedChecksums[role.fileName]?.trim()?.lowercase()
        when {
            !binary.present -> ChecksumResult(role, ChecksumStatus.MISSING, null)
            pin.isNullOrBlank() -> ChecksumResult(role, ChecksumStatus.NOT_PINNED, cachedSha256(binary.file))
            else -> {
                val actual = cachedSha256(binary.file)
                ChecksumResult(
                    role = role,
                    status = if (actual != null && actual == pin) ChecksumStatus.MATCH else ChecksumStatus.MISMATCH,
                    actualSha256 = actual,
                )
            }
        }
    }

    fun missingRequired(): List<BinaryRole> =
        BinaryRole.entries.filter { it.required }.filter { !record(it).present }

    val isReady: Boolean get() = missingRequired().isEmpty()

    /**
     * Makes sure every shipped binary is executable. On some devices the extractor
     * drops the +x bit, and `exec()` then fails with EACCES - which is exactly the
     * failure R4's self-test has to be able to name.
     */
    fun prepare(): PrepareResult {
        if (!exists) {
            return PrepareResult(
                PrepareStatus.NO_DIRECTORY,
                "nativeLibraryDir does not exist: ${nativeLibraryDir.absolutePath}",
            )
        }
        val records = inventory()
        val present = records.filter { it.present }
        if (present.isEmpty()) {
            return PrepareResult(
                PrepareStatus.MISSING_REQUIRED,
                "No native binaries are packaged in this APK yet. See docs/NATIVE_RUNTIME.md " +
                    "(box64 and proot are vendored into app/src/main/jniLibs/arm64-v8a).",
            )
        }
        val denied = present.filterNot { binary ->
            binary.executable || binary.file.setExecutable(true, false)
        }
        if (denied.isNotEmpty()) {
            return PrepareResult(
                PrepareStatus.PERMISSION_DENIED,
                "Cannot make ${denied.joinToString { it.label }} executable in " +
                    nativeLibraryDir.absolutePath,
            )
        }
        val missing = missingRequired()
        if (missing.isNotEmpty()) {
            return PrepareResult(
                PrepareStatus.PARTIAL,
                "Found ${present.joinToString { it.label }}; still missing ${missing.joinToString { it.fileName }}",
            )
        }
        // Fail closed: an unpinned or mismatched required binary is never exec'd.
        val checksums = verifyChecksums()
        val mismatched = checksums.filter { it.status == ChecksumStatus.MISMATCH && it.role.required }
        if (mismatched.isNotEmpty()) {
            return PrepareResult(
                PrepareStatus.CHECKSUM_MISMATCH,
                "SHA-256 mismatch for ${mismatched.joinToString { it.role.fileName }} - refusing to exec. " +
                    "Expected the hash pinned in assets/native/checksums.txt.",
            )
        }
        val unpinned = checksums.filter { it.status == ChecksumStatus.NOT_PINNED && it.role.required }
        if (unpinned.isNotEmpty()) {
            return PrepareResult(
                PrepareStatus.CHECKSUM_NOT_PINNED,
                "No pinned SHA-256 for ${unpinned.joinToString { it.role.fileName }} - refusing to exec. " +
                    "Record it in app/src/main/assets/native/checksums.txt (see docs/NATIVE_RUNTIME.md).",
            )
        }
        return PrepareResult(
            PrepareStatus.READY,
            "Ready: ${present.joinToString { "${it.label} (${it.sizeBytes / 1024L} KB, SHA-256 verified)" }}",
        )
    }

    /** The `PROOT_LOADER*` values the environment builder needs (R2). */
    fun loaderPaths(): Map<String, String> = listOf(BinaryRole.PROOT_LOADER, BinaryRole.PROOT_LOADER_32)
        .filter { record(it).present }
        .associate { role ->
            val key = if (role == BinaryRole.PROOT_LOADER) "PROOT_LOADER" else "PROOT_LOADER_32"
            key to File(nativeLibraryDir, role.fileName).absolutePath
        }

    companion object {
        /** The directory the PackageManager gave us; this is where exec is allowed. */
        fun from(nativeLibraryDirPath: String, pins: Map<String, String> = emptyMap()): NativeBinaries =
            NativeBinaries(File(nativeLibraryDirPath), pins)

        /** `assets/native/checksums.txt`, or "nothing pinned" when the asset is absent. */
        fun loadPinsFromAssets(assets: android.content.res.AssetManager, path: String = "native/checksums.txt") =
            runCatching {
                assets.open(path).bufferedReader().use { it.readText() }
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .mapNotNull { line ->
                        val index = line.indexOf('=')
                        if (index <= 0) null else line.substring(0, index).trim() to line.substring(index + 1).trim().lowercase()
                    }
                    .toMap()
            }.getOrDefault(emptyMap())

        /**
         * Reads `checksums.txt`: `<lib*.so>=<sha256>` per line, `#` comments allowed.
         * Missing or unreadable file means "nothing is pinned" (fail closed for required binaries).
         */
        fun loadPins(file: File): Map<String, String> {
            if (!file.isFile) return emptyMap()
            return runCatching {
                file.readLines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .mapNotNull { line ->
                        val index = line.indexOf('=')
                        if (index <= 0) null else line.substring(0, index).trim() to line.substring(index + 1).trim().lowercase()
                    }
                    .toMap()
            }.getOrDefault(emptyMap())
        }

        fun sha256(file: File): String? = runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()

        /** Size formatting shared with the rest of the shell. */
        fun formatSize(bytes: Long): String = when {
            bytes <= 0L -> "0 KB"
            bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
            else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        }
    }
}
