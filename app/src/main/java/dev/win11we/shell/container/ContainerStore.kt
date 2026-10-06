package dev.win11we.shell.container

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.win11we.shell.settings.Box64Preset
import java.io.File

/**
 * `containers.list` codec. Deliberately a tiny line format instead of a JSON
 * library: no new dependency, deterministic output, and trivially testable.
 * Lines are `key=value`; `drive.<L>` and `env.<NAME>` repeat.
 */
object ContainerCodec {

    fun encode(container: Container): String = buildString {
        appendLine("id=${container.id}")
        appendLine("name=${container.name}")
        appendLine("prefix=${container.prefixPath}")
        appendLine("preset=${container.preset.name}")
        appendLine("arch=${container.arch.name}")
        appendLine("dpi=${container.dpi}")
        appendLine("winedebug=${container.wineDebug}")
        // C: (the prefix) and Z: (the guest root) are implicit: never stored.
        container.drives
            .filterNot { it.letter.uppercaseChar() == 'C' || it.letter.uppercaseChar() == 'Z' }
            .forEach { drive -> appendLine("drive.${drive.letter.uppercaseChar()}=${drive.hostPath}") }
        container.extraEnv.toSortedMap().forEach { (key, value) -> appendLine("env.$key=$value") }
    }

    /** Returns null when the text is not a container we wrote (missing id/name/prefix). */
    fun decode(text: String): Container? {
        val values = LinkedHashMap<String, String>()
        val drives = mutableListOf<DriveMapping>()
        val env = LinkedHashMap<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val index = line.indexOf('=')
            if (index <= 0) return@forEach
            val key = line.substring(0, index)
            val value = line.substring(index + 1)
            when {
                key.startsWith("drive.") -> {
                    val letter = key.removePrefix("drive.").firstOrNull() ?: return@forEach
                    drives.add(DriveMapping(letter, value))
                }
                key.startsWith("env.") -> env[key.removePrefix("env.")] = value
                else -> values[key] = value
            }
        }
        val id = values["id"]?.takeIf { it.isNotBlank() } ?: return null
        val name = values["name"]?.takeIf { it.isNotBlank() } ?: return null
        val prefix = values["prefix"]?.takeIf { it.isNotBlank() } ?: return null
        return Container(
            id = id,
            name = name,
            prefixPath = prefix,
            preset = Box64Preset.entries.firstOrNull { it.name == values["preset"] } ?: Box64Preset.GENERAL,
            arch = WineArch.entries.firstOrNull { it.name == values["arch"] } ?: WineArch.WOW64,
            dpi = values["dpi"]?.toIntOrNull() ?: 96,
            wineDebug = values["winedebug"] ?: "fixme-all",
            drives = drives.filterNot { it.letter.uppercaseChar() == 'C' || it.letter.uppercaseChar() == 'Z' },
            extraEnv = env,
        )
    }
}

/** Result of a runtime call; [message] is what the UI shows when it fails. */
data class ContainerActionResult(val success: Boolean, val message: String) {
    companion object {
        fun ok(message: String = "") = ContainerActionResult(true, message)
        fun fail(message: String) = ContainerActionResult(false, message)
    }
}

/**
 * What the UI asks the runtime to do. R2's ContainerService (foreground service,
 * proot -> box64 -> wine) implements this; until then [NotInstalledRuntime] says
 * exactly which step is missing instead of failing silently.
 */
interface ContainerRuntime {
    val ready: Boolean
    fun start(container: Container): ContainerActionResult
    fun stop(container: Container): ContainerActionResult
    fun restart(container: Container): ContainerActionResult
    fun statusOf(container: Container): ContainerStatus
    fun logExcerpt(container: Container, lines: Int = 20): String
}

class NotInstalledRuntime(private val missingStep: String = "R1 + R2") : ContainerRuntime {
    override val ready: Boolean get() = false
    private fun blocked() = ContainerActionResult.fail(
        "No container runtime yet: proot/box64 binaries ship in R1 and ContainerService starts them in $missingStep.",
    )

    override fun start(container: Container) = blocked()
    override fun stop(container: Container) = blocked()
    override fun restart(container: Container) = blocked()
    override fun statusOf(container: Container) = ContainerStatus.STOPPED
    override fun logExcerpt(container: Container, lines: Int) = "(no runtime, no logs yet)"
}

/**
 * Owns the container list on disk. The directory layout is the one R2/R3 use:
 * `<base>/<id>/container` (this file) and `<base>/<id>/prefix` (the Wine prefix).
 */
@Stable
class ContainerStore(private val baseDir: File) {

    var containers by mutableStateOf<List<Container>>(emptyList())
        private set
    var defaultId by mutableStateOf<String?>(null)
        private set

    /** Reads `containers.list`; missing or corrupt entries are skipped, never fatal. */
    fun load(): List<Container> {
        val file = listFile()
        val loaded = if (file.isFile) {
            file.readText().split(Regex("(?m)^---$"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull(ContainerCodec::decode)
        } else {
            emptyList()
        }
        containers = loaded
        defaultId = loaded.firstOrNull()?.id
        return loaded
    }

    /** Writes the whole list atomically (temp file + rename) so a crash cannot corrupt it. */
    fun save(): Boolean {
        val file = listFile()
        file.parentFile?.mkdirs()
        val text = containers.joinToString(separator = "---\n") { ContainerCodec.encode(it) }
        val temp = File(file.parentFile, file.name + ".tmp")
        return try {
            temp.writeText(text)
            if (file.exists() && !file.delete()) return false
            temp.renameTo(file)
        } catch (error: Exception) {
            false
        }
    }

    fun byId(id: String): Container? = containers.firstOrNull { it.id == id }

    /** Creates the container directory (the prefix itself is created by Wine in R2). */
    fun create(name: String, preset: Box64Preset = Box64Preset.GENERAL, id: String = newId(name)): Container? {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return null
        if (containers.any { it.name.equals(cleanName, ignoreCase = true) }) return null
        val dir = File(baseDir, id)
        if (!dir.exists() && !dir.mkdirs()) return null
        val container = Container.defaults(id = id, name = cleanName, prefixPath = File(dir, "prefix").absolutePath)
            .copy(preset = preset)
        containers = containers + container
        if (defaultId == null) defaultId = container.id
        return if (save()) container else null
    }

    fun rename(id: String, newName: String): Container? {
        val cleanName = newName.trim()
        if (cleanName.isEmpty()) return null
        if (containers.any { it.id != id && it.name.equals(cleanName, ignoreCase = true) }) return null
        return update(id) { it.copy(name = cleanName) }
    }

    fun update(id: String, change: (Container) -> Container): Container? {
        val current = byId(id) ?: return null
        val updated = change(current)
        containers = containers.map { if (it.id == id) updated else it }
        return if (save()) updated else null
    }

    /** Deletes the record; the prefix directory is removed too (it can be gigabytes). */
    fun delete(id: String, deleteFiles: Boolean = true): Boolean {
        val target = byId(id) ?: return false
        containers = containers.filterNot { it.id == id }
        if (defaultId == id) defaultId = containers.firstOrNull()?.id
        if (deleteFiles) runCatching { File(baseDir, id).deleteRecursively() }
        return save()
    }

    fun setDefault(id: String) {
        if (byId(id) == null) return
        defaultId = id
        // The default container is stored first, so a reload keeps the choice.
        containers = listOf(byId(id)!!) + containers.filterNot { it.id == id }
        save()
    }

    fun isDefault(id: String): Boolean = defaultId == id

    fun prefixSizeKb(id: String): Long {
        val dir = File(baseDir, id)
        if (!dir.isDirectory) return 0L
        var total = 0L
        dir.walkTopDown().filter { it.isFile }.forEach { total += it.length() / 1024L }
        return total
    }

    private fun listFile() = File(baseDir, "containers.list")

    private fun newId(name: String): String {
        val slug = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "container" }
        var candidate = slug
        var index = 2
        while (File(baseDir, candidate).exists() || containers.any { it.id == candidate }) {
            candidate = "$slug-$index"
            index++
        }
        return candidate
    }
}
