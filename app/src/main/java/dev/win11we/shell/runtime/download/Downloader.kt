package dev.win11we.shell.runtime.download

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

enum class DownloadOutcome {
    /** Downloaded (or resumed) and the SHA-256 matched. */
    VERIFIED,

    /** The file already existed and matched the pin; nothing was transferred. */
    ALREADY_VERIFIED,
    CANCELLED,
    HASH_MISMATCH,
    NOT_ENOUGH_SPACE,
    NETWORK_ERROR,
    RESUME_NOT_SUPPORTED,
}

data class DownloadResult(
    val outcome: DownloadOutcome,
    val message: String,
    val bytesTransferred: Long = 0L,
    val sha256: String? = null,
) {
    val success: Boolean get() = outcome == DownloadOutcome.VERIFIED || outcome == DownloadOutcome.ALREADY_VERIFIED
}

/** How much room the target filesystem has; injectable so the check is testable. */
fun interface FreeSpaceProvider {
    fun freeBytes(dir: File): Long
}

/**
 * Moves the bytes. `fromByte` enables resuming: implementations must send
 * `Range: bytes=<fromByte>-` and honour a 206 response, or throw
 * [ResumeNotSupportedException] on 200 so the caller can restart cleanly.
 */
interface Transfer {
    fun fetch(artifact: Artifact, target: File, fromByte: Long, onProgress: (Long, Long) -> Unit, cancelled: () -> Boolean): Long
}

class ResumeNotSupportedException(message: String) : IOException(message)

/**
 * The download logic, free of Android and of HTTP: free-space check, resume,
 * progress, cancellation and the fail-closed SHA-256 verdict.
 */
class DownloadSession(
    private val transfer: Transfer,
    private val freeSpace: FreeSpaceProvider,
) {

    fun download(
        artifact: Artifact,
        dir: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): DownloadResult {
        dir.mkdirs()
        val target = artifact.targetFile(dir)
        val partial = artifact.partialFile(dir)

        // A completed file is verified before we trust it; a wrong one is deleted.
        if (target.isFile) {
            val hash = sha256(target)
            return if (hash == artifact.sha256) {
                DownloadResult(
                    DownloadOutcome.ALREADY_VERIFIED,
                    "${artifact.fileName} is already present and verified (${formatBytes(target.length())}).",
                    bytesTransferred = 0L,
                    sha256 = hash,
                )
            } else {
                target.delete()
                DownloadResult(
                    DownloadOutcome.HASH_MISMATCH,
                    "The existing ${artifact.fileName} did not match the pinned SHA-256 and was deleted.",
                    sha256 = hash,
                )
            }
        }

        val resumeFrom = if (partial.isFile) partial.length() else 0L
        val remaining = (artifact.sizeBytes - resumeFrom).coerceAtLeast(0L)
        val free = freeSpace.freeBytes(dir)
        // Need room for the archive plus whatever it unpacks into.
        if (free < artifact.requiredFreeBytes || (artifact.sizeBytes > 0 && free < remaining + artifact.requiredFreeBytes / 4)) {
            return DownloadResult(
                DownloadOutcome.NOT_ENOUGH_SPACE,
                "Not enough space: ${formatBytes(free)} free, " +
                    "${formatBytes(artifact.requiredFreeBytes)} required (download plus unpacked).",
            )
        }

        return try {
            // The transport appends to <name>.part so an interrupted download can resume.
            val transferred = transfer.fetch(artifact, partial, resumeFrom, onProgress, cancelled)
            when {
                cancelled() -> DownloadResult(
                    DownloadOutcome.CANCELLED,
                    "Cancelled after ${formatBytes(transferred)}; ${formatBytes(partial.length())} kept for resume.",
                    transferred,
                )
                !partial.isFile -> DownloadResult(DownloadOutcome.NETWORK_ERROR, "No file was written.", transferred)
                else -> {
                    val hash = sha256(partial)
                    if (hash == artifact.sha256) {
                        // Only a verified file is ever promoted to its real name.
                        target.delete()
                        if (!partial.renameTo(target)) {
                            DownloadResult(DownloadOutcome.NETWORK_ERROR, "Verified but could not move into place.")
                        } else {
                            onProgress(target.length(), target.length())
                            DownloadResult(
                                DownloadOutcome.VERIFIED,
                                "${artifact.fileName} verified (SHA-256 ${hash.take(12)}…).",
                                transferred,
                                hash,
                            )
                        }
                    } else {
                        val size = partial.length()
                        partial.delete()
                        DownloadResult(
                            DownloadOutcome.HASH_MISMATCH,
                            "SHA-256 mismatch for ${artifact.fileName} (${formatBytes(size)} received): " +
                                "expected ${artifact.sha256.take(16)}…, got ${hash?.take(16) ?: "unreadable"}. The download was deleted.",
                            transferred,
                            hash,
                        )
                    }
                }
            }
        } catch (error: ResumeNotSupportedException) {
            partial.delete() // a server that ignores Range would produce a corrupt tail
            DownloadResult(DownloadOutcome.RESUME_NOT_SUPPORTED, error.message ?: "Resume refused by the server.")
        } catch (error: IOException) {
            // Keep whatever arrived so the next attempt can resume it.
            DownloadResult(DownloadOutcome.NETWORK_ERROR, "${error.javaClass.simpleName}: ${error.message}")
        }
    }

    /** Deletes the archive and any partial download. */
    fun remove(artifact: Artifact, dir: File): Boolean {
        val a = artifact.targetFile(dir).delete()
        val b = artifact.partialFile(dir).delete()
        return a || b
    }

    companion object {
        fun sha256(file: File): String? = runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()

        fun formatBytes(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        }
    }
}

/** Real transport: `HttpURLConnection` over https, resuming with `Range`. */
class HttpTransfer(
    private val connectTimeoutMillis: Int = 20_000,
    private val readTimeoutMillis: Int = 60_000,
) : Transfer {

    override fun fetch(
        artifact: Artifact,
        target: File,
        fromByte: Long,
        onProgress: (Long, Long) -> Unit,
        cancelled: () -> Boolean,
    ): Long {
        val connection = open(artifact.url, fromByte, target.isFile)
        try {
            val code = connection.responseCode
            val resuming = fromByte > 0 && target.isFile
            if (resuming && code == HttpURLConnection.HTTP_OK) {
                throw ResumeNotSupportedException("Server ignored the Range request; restart the download.")
            }
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("HTTP $code from ${connection.url}")
            }
            if (resuming) {
                // A 206 whose Content-Range does not start where we asked would silently
                // produce a corrupt tail, so refuse it instead of appending.
                val range = connection.getHeaderField("Content-Range")
                val start = range?.substringAfter("bytes ", "")?.substringBefore('-', "")?.toLongOrNull()
                if (start != null && start != fromByte) {
                    throw ResumeNotSupportedException("Server resumed at $start instead of $fromByte.")
                }
            }
            val total = if (artifact.sizeBytes > 0) artifact.sizeBytes else connection.contentLengthLong + fromByte

            val startBytes = if (resuming) target.length() else 0L
            var written = startBytes
            java.io.FileOutputStream(target, resuming).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        if (cancelled()) break
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                }
            }
            onProgress(written, total)
            return written - startBytes // bytes transferred by *this* call
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Opens the connection, following redirects by hand: `HttpURLConnection` will not
     * cross protocols (http -> https), which cdimage mirrors do.
     */
    private fun open(url: String, fromByte: Long, hasPartial: Boolean, hops: Int = 0): HttpURLConnection {
        if (hops > MAX_REDIRECTS) throw IOException("Too many redirects from $url")
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.setRequestProperty("User-Agent", "Win11WE/1.0 (Android)")
        if (fromByte > 0 && hasPartial) connection.setRequestProperty("Range", "bytes=$fromByte-")
        connection.connect()
        val code = connection.responseCode
        val location = connection.getHeaderField("Location")
        return if (code in 300..399 && !location.isNullOrBlank()) {
            connection.disconnect()
            val next = if (location.startsWith("http")) location else URL(URL(url), location).toString()
            if (!next.startsWith("https://")) throw IOException("Refusing insecure redirect to $next")
            open(next, fromByte, hasPartial, hops + 1)
        } else {
            connection
        }
    }

    private companion object {
        const val MAX_REDIRECTS = 5
    }
}
