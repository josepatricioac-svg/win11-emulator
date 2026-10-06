package dev.win11we.shell.ui.explorer

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

enum class ExplorerViewMode { DETAILS, GRID }

/**
 * Navigation + view state of one Explorer window. Every method is pure file
 * system work, so the whole class is unit-testable with temp dirs.
 */
@Stable
class ExplorerState(val root: File) {
    private val backStack = mutableListOf<File>()
    private val forwardStack = mutableListOf<File>()

    var current by mutableStateOf(root)
        private set
    var query by mutableStateOf("")
    var sortKey by mutableStateOf(SortKey.NAME)
    var ascending by mutableStateOf(true)
    var viewMode by mutableStateOf(ExplorerViewMode.DETAILS)
    val selection = mutableStateListOf<File>()

    /** Bumped on every mutation so the file list recomposes (files are not observable). */
    var revision by mutableIntStateOf(0)
        private set

    fun refresh() {
        revision++
    }

    /** Root-relative path used by the breadcrumb, e.g. "Phone > Download". */
    fun trail(): List<File> {
        val trail = mutableListOf<File>()
        var node: File? = current
        while (node != null && node != root.parentFile) {
            trail.add(0, node)
            if (node == root) break
            node = node.parentFile
        }
        return trail
    }

    fun navigateTo(dir: File) {
        if (!dir.isDirectory) return
        if (dir == current) return
        backStack.add(current)
        forwardStack.clear()
        current = dir
        selection.clear()
    }

    fun up(): Boolean {
        val parent = current.parentFile ?: return false
        if (parent == root.parentFile) return false // never leave the mapped root
        navigateTo(parent)
        return true
    }

    fun back(): Boolean {
        val previous = backStack.removeLastOrNull() ?: return false
        forwardStack.add(current)
        current = previous
        selection.clear()
        return true
    }

    fun forward(): Boolean {
        val next = forwardStack.removeLastOrNull() ?: return false
        backStack.add(current)
        current = next
        selection.clear()
        return true
    }

    val canGoBack: Boolean get() = backStack.isNotEmpty()
    val canGoForward: Boolean get() = forwardStack.isNotEmpty()
    val canGoUp: Boolean get() = current != root && current.parentFile != null

    fun toggleSort(key: SortKey) {
        if (sortKey == key) ascending = !ascending else {
            sortKey = key
            ascending = true
        }
    }

    fun entries(): List<FileEntry> {
        revision // read so the list recomposes after refresh/create/rename/delete
        return FileEntries.list(current, query, sortKey, ascending)
    }

    fun select(file: File, additive: Boolean) {
        if (!additive) selection.clear()
        if (selection.contains(file)) selection.remove(file) else selection.add(file)
    }

    fun selectAll() {
        selection.clear()
        selection.addAll(entries().map { it.file })
    }

    fun clearSelection() = selection.clear()

    /** Returns the created folder, or null when the name is taken/invalid. */
    fun createFolder(name: String): File? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.contains('/') || trimmed.startsWith(".")) return null
        val target = File(current, trimmed)
        return if (target.exists()) null else if (target.mkdir()) { refresh(); target } else null
    }

    /** Renames inside the same folder; returns the new file or null on failure. */
    fun rename(file: File, newName: String): File? {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed.contains('/') || trimmed == file.name) return null
        val target = File(file.parentFile ?: return null, trimmed)
        if (target.exists()) return null
        return if (file.renameTo(target)) {
            selection.remove(file)
            refresh()
            target
        } else {
            null
        }
    }

    fun delete(file: File): Boolean {
        val removed = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (removed) {
            selection.remove(file)
            refresh()
        }
        return removed
    }
}
