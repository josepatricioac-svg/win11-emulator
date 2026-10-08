package dev.win11we.shell.x11

/** One entry of the pixmap-formats list in the connection setup. */
data class PixmapFormat(val depth: Int, val bitsPerPixel: Int, val scanlinePad: Int)

/** A TrueColor visual; X clients ask for one of these by id. */
data class VisualInfo(
    val visualId: Int,
    val visualClass: Int = 4, // TrueColor
    val bitsPerRgb: Int = 8,
    val colormapEntries: Int = 256,
    val redMask: Int = 0xFF0000,
    val greenMask: Int = 0x00FF00,
    val blueMask: Int = 0x0000FF,
)

/** The single screen the server exposes; its size is the native surface size. */
data class ScreenConfig(
    val rootId: Int,
    val widthPx: Int,
    val heightPx: Int,
    val widthMm: Int = 270,
    val heightMm: Int = 170,
    val rootDepth: Int = 24,
    val rootVisual: Int = 0x21,
    val defaultColormap: Int,
    val whitePixel: Int = 0xFFFFFF,
    val blackPixel: Int = 0x000000,
    val minInstalledMaps: Int = 1,
    val maxInstalledMaps: Int = 1,
    val backingStores: Int = 0, // Never
    val saveUnders: Boolean = false,
    val currentInputDepths: List<Int> = listOf(24),
    val pixmapFormats: List<PixmapFormat> = listOf(
        PixmapFormat(depth = 1, bitsPerPixel = 1, scanlinePad = 32),
        PixmapFormat(depth = 24, bitsPerPixel = 32, scanlinePad = 32),
        PixmapFormat(depth = 32, bitsPerPixel = 32, scanlinePad = 32),
    ),
    val visuals: List<VisualInfo> = listOf(
        VisualInfo(visualId = 0x21),
        VisualInfo(visualId = 0x22, bitsPerRgb = 8),
    ),
)

/** Event-selection bits a window asked for (a subset of the X event masks). */
object EventMask {
    const val KEY_PRESS = 1 shl 0
    const val KEY_RELEASE = 1 shl 1
    const val BUTTON_PRESS = 1 shl 2
    const val BUTTON_RELEASE = 1 shl 3
    const val ENTER_WINDOW = 1 shl 4
    const val LEAVE_WINDOW = 1 shl 5
    const val POINTER_MOTION = 1 shl 6
    const val EXPOSURE = 1 shl 15
    const val STRUCTURE_NOTIFY = 1 shl 17
    const val SUBSTRUCTURE_NOTIFY = 1 shl 19
    const val SUBSTRUCTURE_REDIRECT = 1 shl 20
    const val PROPERTY_CHANGE = 1 shl 22
    const val OWNER_GRAB_BUTTON = 1 shl 24
}

/** A window in the server's tree. Geometry is in screen pixels. */
data class XWindow(
    val id: Int,
    var parent: Int?,
    var x: Int,
    var y: Int,
    var width: Int,
    var height: Int,
    var borderWidth: Int = 0,
    var depth: Int = 24,
    var visual: Int = 0x21,
    var mapped: Boolean = false,
    var overrideRedirect: Boolean = false,
    var eventMask: Int = 0,
    var backgroundPixel: Int = 0,
    var doNotPropagateMask: Int = 0,
    val children: MutableList<Int> = mutableListOf(),
    val properties: MutableMap<Int, WindowProperty> = mutableMapOf(),
) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height

    fun contains(px: Int, py: Int): Boolean = px in x until right && py in y until bottom
}

data class WindowProperty(val atom: Int, val type: Int, val format: Int, val data: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is WindowProperty && atom == other.atom && type == other.type &&
            format == other.format && data.contentEquals(other.data)

    override fun hashCode(): Int = 31 * (31 * atom + type) + data.contentHashCode()
}

/**
 * The server's document: resource ids, the window tree, atoms and properties.
 * No sockets, no threads, no Android - so all of it is unit-testable.
 */
class XDocument(
    val screen: ScreenConfig,
    private val idBase: Int = X11.RESOURCE_ID_BASE,
    private val idMask: Int = X11.RESOURCE_ID_MASK,
) {
    private var nextId = 1
    val windows = LinkedHashMap<Int, XWindow>()
    private val atoms = LinkedHashMap<String, Int>()
    private val atomNames = LinkedHashMap<Int, String>()

    init {
        // The root window exists before any client connects.
        windows[screen.rootId] = XWindow(
            id = screen.rootId,
            parent = null,
            x = 0,
            y = 0,
            width = screen.widthPx,
            height = screen.heightPx,
            depth = screen.rootDepth,
            visual = screen.rootVisual,
            mapped = true,
        )
        // A few atoms every client expects to exist with the standard numbering.
        listOf("PRIMARY", "SECONDARY", "ATOM", "STRING", "WM_NAME", "WM_CLASS", "WM_PROTOCOLS", "WM_DELETE_WINDOW")
            .forEachIndexed { index, name -> internAtom(name, index + 1) }
    }

    val rootId: Int get() = screen.rootId

    fun newId(): Int {
        val id = idBase or (nextId and idMask)
        nextId++
        return id
    }

    fun window(id: Int): XWindow? = windows[id]

    fun createWindow(
        id: Int,
        parent: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        borderWidth: Int = 0,
        depth: Int = 24,
        visual: Int = 0x21,
    ): XWindow? {
        val owner = windows[parent] ?: return null
        if (windows.containsKey(id)) return null
        if (width <= 0 || height <= 0) return null
        val window = XWindow(
            id = id,
            parent = parent,
            x = x,
            y = y,
            width = width,
            height = height,
            borderWidth = borderWidth,
            depth = depth,
            visual = visual,
        )
        windows[id] = window
        owner.children.add(id)
        return window
    }

    fun destroyWindow(id: Int): Boolean {
        val window = windows[id] ?: return false
        if (id == rootId) return false // the root is never destroyed
        window.children.toList().forEach { destroyWindow(it) }
        window.parent?.let { parent -> windows[parent]?.children?.remove(id) }
        windows.remove(id)
        return true
    }

    fun mapWindow(id: Int): Boolean {
        val window = windows[id] ?: return false
        if (window.mapped) return false
        // A child only becomes visible when its parent is mapped, like X.
        val parent = window.parent?.let { windows[it] }
        if (parent != null && !parent.mapped) return false
        window.mapped = true
        return true
    }

    fun unmapWindow(id: Int): Boolean {
        val window = windows[id] ?: return false
        if (!window.mapped) return false
        window.mapped = false
        return true
    }

    fun configure(id: Int, x: Int?, y: Int?, width: Int?, height: Int?, borderWidth: Int?): Boolean {
        val window = windows[id] ?: return false
        x?.let { window.x = it }
        y?.let { window.y = it }
        width?.takeIf { it > 0 }?.let { window.width = it }
        height?.takeIf { it > 0 }?.let { window.height = it }
        borderWidth?.takeIf { it >= 0 }?.let { window.borderWidth = it }
        return true
    }

    /** Mapped windows in paint order: root first, children back-to-front. */
    fun paintOrder(): List<XWindow> {
        val out = mutableListOf<XWindow>()
        fun walk(id: Int) {
            val window = windows[id] ?: return
            if (window.mapped) out.add(window)
            window.children.forEach(::walk)
        }
        walk(rootId)
        return out
    }

    /** Top-most mapped window under a point, for pointer events. */
    fun windowAt(px: Int, py: Int): XWindow? = paintOrder().lastOrNull { it.contains(px, py) }

    // --- atoms -------------------------------------------------------------

    /** Interns [name]; [forcedId] is used for the pre-defined atoms. */
    fun internAtom(name: String, forcedId: Int? = null): Int {
        atoms[name]?.let { return it }
        val id = forcedId ?: (atoms.size + 1000)
        atoms[name] = id
        atomNames[id] = name
        return id
    }

    fun atomId(name: String): Int? = atoms[name]
    fun atomName(id: Int): String? = atomNames[id]

    // --- properties --------------------------------------------------------

    fun setProperty(windowId: Int, atom: Int, type: Int, format: Int, data: ByteArray): Boolean {
        val window = windows[windowId] ?: return false
        window.properties[atom] = WindowProperty(atom, type, format, data)
        return true
    }

    fun deleteProperty(windowId: Int, atom: Int): Boolean = windows[windowId]?.properties?.remove(atom) != null

    fun property(windowId: Int, atom: Int): WindowProperty? = windows[windowId]?.properties?.get(atom)

    /** Every mapped top-level window, i.e. what the shell shows as a taskbar entry. */
    fun topLevelWindows(): List<XWindow> =
        windows[rootId]?.children?.mapNotNull { windows[it] }?.filter { it.mapped } ?: emptyList()
}
