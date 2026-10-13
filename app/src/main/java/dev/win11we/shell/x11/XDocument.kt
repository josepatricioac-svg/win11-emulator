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

/**
 * The in-process window manager the shell installs. When a window that is not
 * override-redirect asks to be mapped or configured under a parent with
 * SubstructureRedirect selected, these hooks fire instead of the change itself,
 * exactly like MapRequest/ConfigureRequest reach an external WM.
 */
interface XWindowManager {
    fun mapRequested(windowId: Int)
    fun configureRequested(windowId: Int, x: Int?, y: Int?, width: Int?, height: Int?, borderWidth: Int?)
}

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
    const val FOCUS_CHANGE = 1 shl 21
    const val PROPERTY_CHANGE = 1 shl 22
    const val OWNER_GRAB_BUTTON = 1 shl 24
}

/** Standard cursor glyph numbers from X11/cursorfont.h (the ones worth rendering). */
object CursorGlyph {
    const val X_CURSOR = 0
    const val PENCIL = 1
    const val ARROW = 2
    const val CROSSHAIR = 34
    const val HAND2 = 60
    const val LEFT_PTR = 68
    const val WATCH = 150
    const val XTERM = 152
}

/** A cursor resource: glyph-based (CreateGlyphCursor) or pixmap-based (CreateCursor). */
data class XCursorResource(
    val id: Int,
    /** The XC_* glyph number; 0 for pixmap cursors, which render as the default arrow. */
    val glyph: Int,
    val foreground: Int = 0x000000,
    val background: Int = 0xFFFFFF,
)

/** An active pointer grab (XGrabPointer): all pointer events go to [window] until ungrab. */
data class PointerGrab(
    val window: Int,
    val ownerEvents: Boolean,
    val eventMask: Int,
    val confineTo: Int,
    val cursor: Int,
    val connection: XConnection,
    /** True when a passive grab (XGrabButton) activated it: a release ends it. */
    val implicit: Boolean = false,
)

/** An active keyboard grab (XGrabKeyboard). */
data class KeyboardGrab(
    val window: Int,
    val ownerEvents: Boolean,
    val connection: XConnection,
    val implicit: Boolean = false,
)

/**
 * A passive grab (XGrabButton/XGrabKey): activates when a matching press would
 * reach [window] or one of its descendants. [detail] 0 = AnyButton/AnyKey;
 * [modifiers] 0x8000 = AnyModifier.
 */
data class PassiveGrab(
    val window: Int,
    val eventMask: Int,
    val detail: Int,
    val modifiers: Int,
    val isKey: Boolean,
    val connection: XConnection,
    val ownerEvents: Boolean = false,
    val confineTo: Int = 0,
    val cursor: Int = 0,
) {
    fun matches(detail: Int, stateMask: Int): Boolean =
        (this.detail == 0 || this.detail == detail) &&
            (modifiers == ANY_MODIFIER ||
                (modifiers and MODIFIER_BITS) == (stateMask and MODIFIER_BITS))

    companion object {
        const val ANY_MODIFIER = 1 shl 15
        const val MODIFIER_BITS = 0x1F // Shift..Mod5, as X compares them
    }
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
    /** CWCursor: cursor resource id, 0 = inherit from the parent (X semantics). */
    var cursor: Int = 0,
    /**
     * XFIXES SetWindowShapeRegion rectangles (D1f), root coordinates; null = unshaped.
     * Only the INPUT shape has an effect (hit-testing); bounding/clip shapes are stored
     * and round-trip through CreateRegionFromWindow, but the renderer does not clip
     * visually - the documented limit shared with backing-store/save-under.
     */
    var boundingShape: List<IntArray>? = null,
    var clipShape: List<IntArray>? = null,
    var inputShape: List<IntArray>? = null,
    val children: MutableList<Int> = mutableListOf(),
    val properties: MutableMap<Int, WindowProperty> = mutableMapOf(),
) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height

    fun contains(px: Int, py: Int): Boolean = px in x until right && py in y until bottom

    /** XFIXES input-shape-aware hit test: a shaped window only owns points inside its region. */
    fun inputShapeAllows(px: Int, py: Int): Boolean =
        inputShape?.let { XRegionOps.contains(it, px, py) } ?: true
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
    screen: ScreenConfig,
    private val idBase: Int = X11.RESOURCE_ID_BASE,
    private val idMask: Int = X11.RESOURCE_ID_MASK,
) {
    var screen: ScreenConfig = screen
        private set

    /** The shell's in-process WM; null until [XServer.attachWindowManager] runs. */
    var windowManager: XWindowManager? = null

    private var nextId = 1
    val windows = LinkedHashMap<Int, XWindow>()
    private val atoms = LinkedHashMap<String, Int>()
    private val atomNames = LinkedHashMap<Int, String>()

    /** Which connection holds SubstructureRedirect on a window (X allows only one). */
    private val redirectOwners = HashMap<Int, XConnection>()

    /**
     * Which connection created which window. SendEvent and focus/grab events may
     * target a window owned by *another* connection, and events must reach the
     * owner's socket - not the sender's. Root-owned windows map to nobody; the
     * server broadcasts structure events to every connection separately.
     */
    private val windowOwners = HashMap<Int, XConnection>()

    fun ownerOf(windowId: Int): XConnection? = synchronized(windowOwners) { windowOwners[windowId] }

    fun setOwner(windowId: Int, connection: XConnection) = synchronized(windowOwners) {
        windowOwners[windowId] = connection
        Unit
    }

    /** Called when a connection dies: its windows lose their owner tag. */
    fun releaseOwners(connection: XConnection) = synchronized(windowOwners) {
        windowOwners.entries.removeAll { it.value === connection }
    }

    /**
     * Destroys every window this connection created (and their subtrees), as a real X
     * server does when a client disconnects. Without this, a reconnecting client that
     * reuses its deterministic resource ids collides with its own stale windows and
     * gets BadWindow from CreateWindow (GTK treats that as fatal).
     *
     * @return the ids of all destroyed windows, so the caller can purge them from the
     *   display list / raster cache.
     */
    fun destroyWindowsOwnedBy(connection: XConnection): List<Int> {
        val owned = synchronized(windowOwners) {
            windowOwners.entries.filter { it.value === connection }.map { it.key }.toList()
        }
        val destroyed = mutableListOf<Int>()
        owned.forEach { top ->
            collectSubtree(top, destroyed)
            destroyWindow(top)
        }
        return destroyed
    }

    private fun collectSubtree(id: Int, out: MutableList<Int>) {
        val window = windows[id] ?: return
        out.add(id)
        window.children.toList().forEach { collectSubtree(it, out) }
    }

    // --- input focus (server-wide, like X) ---------------------------------

    companion object FocusDefaults {
        /** SetInputFocus revert/focus values: None = no events, PointerRoot = follow the pointer. */
        const val FOCUS_NONE = 0
        const val FOCUS_POINTER_ROOT = 1

        /** [effectiveCursorGlyph] answer when XFIXES HideCursor covers the point. */
        const val CURSOR_HIDDEN = -1

        /**
         * The shell's internal window: never mapped, never parented to the root
         * (invisible to QueryTree/paintOrder/hit-testing), but a real window so
         * it can own selections and carry properties. Reserved near the top of
         * the resource-id space, where client id allocation never arrives.
         */
        const val SHELL_WINDOW_ID = X11.RESOURCE_ID_BASE or 0x001FFFFE
    }

    /** The current input focus: [FOCUS_NONE], [FOCUS_POINTER_ROOT] or a window id. */
    @Volatile
    var focus: Int = FOCUS_POINTER_ROOT
        private set

    /** Where focus reverts when the focused window becomes unviewable. */
    @Volatile
    var focusRevertTo: Int = FOCUS_POINTER_ROOT
        private set

    /** @return the old focus, or null when the request was invalid (BadWindow/BadMatch). */
    fun setInputFocus(windowId: Int, revertTo: Int): Int? {
        if (windowId != FOCUS_NONE && windowId != FOCUS_POINTER_ROOT && !windows.containsKey(windowId)) return null
        if (revertTo !in 0..2) return null
        val old = focus
        focus = windowId
        focusRevertTo = revertTo
        return old
    }

    /** Focus reverts when its window is destroyed or unmapped. */
    private fun revertFocusIf(lostId: Int) {
        if (focus == lostId) {
            focus = if (focusRevertTo == 2) {
                windows[lostId]?.parent ?: FOCUS_POINTER_ROOT // RevertToParent
            } else {
                focusRevertTo
            }
        }
    }

    // --- grabs (server-wide) ------------------------------------------------

    @Volatile
    var pointerGrab: PointerGrab? = null
        private set

    @Volatile
    var keyboardGrab: KeyboardGrab? = null
        private set

    private val passiveGrabs = mutableListOf<PassiveGrab>()

    fun grabPointer(grab: PointerGrab): Boolean {
        if (pointerGrab != null) return false // AlreadyGrabbed
        pointerGrab = grab
        return true
    }

    fun ungrabPointer(connection: XConnection) {
        pointerGrab?.takeIf { it.connection === connection }?.let { pointerGrab = null }
    }

    /** Ends a grab only when it was implicitly activated by a passive grab. */
    fun ungrabImplicitPointer(connection: XConnection) {
        pointerGrab?.takeIf { it.connection === connection && it.implicit }?.let { pointerGrab = null }
    }

    fun ungrabImplicitKeyboard(connection: XConnection) {
        keyboardGrab?.takeIf { it.connection === connection && it.implicit }?.let { keyboardGrab = null }
    }

    fun changePointerGrab(connection: XConnection, eventMask: Int, cursor: Int): Boolean {
        val current = pointerGrab ?: return false
        if (current.connection !== connection) return false
        pointerGrab = current.copy(eventMask = eventMask, cursor = cursor)
        return true
    }

    fun grabKeyboard(grab: KeyboardGrab): Boolean {
        if (keyboardGrab != null) return false
        keyboardGrab = grab
        return true
    }

    fun ungrabKeyboard(connection: XConnection) {
        keyboardGrab?.takeIf { it.connection === connection }?.let { keyboardGrab = null }
    }

    fun addPassiveGrab(grab: PassiveGrab) = synchronized(passiveGrabs) {
        // A re-grab replaces the identical (window, detail, modifiers, isKey) entry.
        passiveGrabs.removeAll {
            it.window == grab.window && it.detail == grab.detail &&
                it.modifiers == grab.modifiers && it.isKey == grab.isKey
        }
        passiveGrabs.add(grab)
    }

    fun removePassiveGrab(window: Int, detail: Int, modifiers: Int, isKey: Boolean) =
        synchronized(passiveGrabs) {
            passiveGrabs.removeAll {
                it.window == window && it.detail == detail && it.modifiers == modifiers && it.isKey == isKey
            }
        }

    fun passiveGrabs(): List<PassiveGrab> = synchronized(passiveGrabs) { passiveGrabs.toList() }

    /** First passive grab on [windowId] or an ancestor that matches the press. */
    fun passiveGrabFor(windowId: Int, detail: Int, stateMask: Int, isKey: Boolean): PassiveGrab? {
        var current: Int? = windowId
        val grabs = passiveGrabs()
        while (current != null) {
            grabs.firstOrNull { it.window == current && it.isKey == isKey && it.matches(detail, stateMask) }
                ?.let { return it }
            current = windows[current]?.parent
        }
        return null
    }

    /** Drops every grab held by a connection that just disconnected (no stuck grabs). */
    fun releaseGrabs(connection: XConnection) {
        ungrabPointer(connection)
        ungrabKeyboard(connection)
        synchronized(passiveGrabs) { passiveGrabs.removeAll { it.connection === connection } }
    }

    // --- cursors (server-wide: the renderer resolves them from any thread) ---

    private val cursors = HashMap<Int, XCursorResource>()

    fun addCursor(cursor: XCursorResource) = synchronized(cursors) { cursors[cursor.id] = cursor }

    fun removeCursor(id: Int) = synchronized(cursors) { cursors.remove(id) }

    fun cursor(id: Int): XCursorResource? = synchronized(cursors) { cursors[id] }

    /**
     * The cursor glyph in effect at a root-coordinate point: the topmost window
     * under the point and its ancestors are walked until one sets CWCursor,
     * exactly like X. Unknown ids (pixmap cursors) render as the default arrow.
     */
    fun effectiveCursorGlyph(px: Int, py: Int): Int {
        var window = windowAt(px, py)
        while (window != null) {
            if (cursorHiddenAt(window.id)) return CURSOR_HIDDEN
            if (window.cursor != 0) return cursor(window.cursor)?.glyph ?: CursorGlyph.LEFT_PTR
            window = window.parent?.let { windows[it] }
        }
        return CursorGlyph.LEFT_PTR
    }

    /** The cursor resource id in effect at the point (0 = the default arrow). */
    fun effectiveCursorId(px: Int, py: Int): Int {
        var window = windowAt(px, py)
        while (window != null) {
            if (window.cursor != 0) return window.cursor
            window = window.parent?.let { windows[it] }
        }
        return 0
    }

    // --- selections (server-wide ownership, P1 clipboard bridge) ------------

    private val selectionOwners = HashMap<Int, SelectionOwner>()

    /**
     * Set by XServer when the Android clipboard bridge is attached: answers
     * shell-owned conversions inline. Takes the target ATOM, returns the raw
     * property bytes or null (unknown target -> a None conversion, as in X).
     */
    var clipboardTextProvider: ((targetAtom: Int) -> ByteArray?)? = null

    /** Set by XServer: fired after a *client* claims a selection (guest-copy push). */
    var onSelectionOwnerChanged: ((atom: Int, owner: SelectionOwner) -> Unit)? = null

    fun selectionOwner(atom: Int): SelectionOwner? = synchronized(selectionOwners) { selectionOwners[atom] }

    /**
     * Records a new owner ([window] 0/None clears). @return the effective owner,
     * or null when the selection now has none. The caller fires
     * [onSelectionOwnerChanged] - kept out of here so reclaims stay silent.
     */
    fun setSelectionOwner(atom: Int, window: Int, connection: XConnection?): SelectionOwner? {
        val owner = if (window == 0) null else SelectionOwner(window, connection)
        synchronized(selectionOwners) {
            if (owner == null) selectionOwners.remove(atom) else selectionOwners[atom] = owner
        }
        // XFIXES watchers see EVERY ownership change (including shell reclaims -
        // in real X those are ordinary SetSelectionOwner round trips).
        fireSelectionNotify(atom, XFixes.SUBTYPE_SELECTION_SET, window)
        return owner
    }

    /**
     * The Android clipboard changed outside the guest (shell Notepad copy,
     * another app): the shell takes both text selections back so guest pastes
     * get the fresh text. Only legal while the bridge is attached.
     */
    fun reclaimSelectionsToShell() {
        if (clipboardTextProvider == null) return
        val shell = SelectionOwner(SHELL_WINDOW_ID, null)
        synchronized(selectionOwners) {
            atomId("PRIMARY")?.let { selectionOwners[it] = shell }
            atomId("CLIPBOARD")?.let { selectionOwners[it] = shell }
        }
        atomId("PRIMARY")?.let { fireSelectionNotify(it, XFixes.SUBTYPE_SELECTION_SET, SHELL_WINDOW_ID) }
        atomId("CLIPBOARD")?.let { fireSelectionNotify(it, XFixes.SUBTYPE_SELECTION_SET, SHELL_WINDOW_ID) }
    }

    /** Ownership dies with its window (X semantics); the shell reclaims when bridged. */
    private fun dropSelectionsOfWindow(windowId: Int) {
        val lost = synchronized(selectionOwners) {
            selectionOwners.entries.filter { it.value.window == windowId }.map { it.key }
        }
        lost.forEach { atom ->
            val shell = if (clipboardTextProvider != null) SelectionOwner(SHELL_WINDOW_ID, null) else null
            synchronized(selectionOwners) {
                if (shell == null) selectionOwners.remove(atom) else selectionOwners[atom] = shell
            }
            fireSelectionNotify(atom, XFixes.SUBTYPE_SELECTION_DESTROY, shell?.window ?: 0)
        }
    }

    /** Detaching the bridge: shell-owned selections go back to "nobody". */
    fun dropShellSelections() {
        val lost = synchronized(selectionOwners) {
            val atoms = selectionOwners.entries.filter { it.value.isShell }.map { it.key }.toList()
            selectionOwners.entries.removeAll { it.value.isShell }
            atoms
        }
        lost.forEach { fireSelectionNotify(it, XFixes.SUBTYPE_SELECTION_DESTROY, 0) }
    }

    /** A disconnected client's ownerships fall back the same way. */
    fun releaseSelections(connection: XConnection) {
        val lost = synchronized(selectionOwners) {
            selectionOwners.entries.filter { it.value.connection === connection }.map { it.key }
        }
        lost.forEach { atom ->
            val shell = if (clipboardTextProvider != null) SelectionOwner(SHELL_WINDOW_ID, null) else null
            synchronized(selectionOwners) {
                if (shell == null) selectionOwners.remove(atom) else selectionOwners[atom] = shell
            }
            fireSelectionNotify(atom, XFixes.SUBTYPE_CLIENT_CLOSE, shell?.window ?: 0)
        }
    }

    // --- keyboard physical state (for QueryKeymap) ---------------------------

    private val pressedKeycodes = HashSet<Int>()

    fun noteKey(keycode: Int, pressed: Boolean) = synchronized(pressedKeycodes) {
        if (pressed) pressedKeycodes.add(keycode) else pressedKeycodes.remove(keycode)
    }

    /** The 32-byte (256-bit) map QueryKeymap returns: bit N = keycode N is down. */
    fun keymapBits(): ByteArray = synchronized(pressedKeycodes) {
        val out = ByteArray(32)
        pressedKeycodes.forEach { code ->
            if (code in 0..255) out[code / 8] = (out[code / 8].toInt() or (1 shl (code % 8))).toByte()
        }
        out
    }

    fun redirectOwner(windowId: Int): XConnection? = synchronized(redirectOwners) { redirectOwners[windowId] }

    /** @return false when another connection already owns the redirect (Access error). */
    fun claimRedirect(windowId: Int, connection: XConnection): Boolean = synchronized(redirectOwners) {
        val current = redirectOwners[windowId]
        if (current != null && current !== connection) return false
        redirectOwners[windowId] = connection
        true
    }

    fun releaseRedirect(connection: XConnection) = synchronized(redirectOwners) {
        redirectOwners.entries.removeAll { it.value === connection }
    }

    /** Called when the native surface changed; keeps windows and clients alive. */
    fun setScreenSize(widthPx: Int, heightPx: Int) {
        screen = screen.copy(widthPx = widthPx, heightPx = heightPx)
        windows[screen.rootId]?.let { it.width = widthPx; it.height = heightPx }
    }

    /** Set by XServer: invoked when an event was queued onto another connection. */
    var externalFlush: ((XConnection) -> Unit)? = null

    /**
     * Set by XServer: invoked when a client warped the pointer (WarpPointer or
     * XTest FakeInput), so the server can sync the other connections and the
     * software cursor. [origin] already moved itself and must be skipped.
     */
    var onWarp: ((x: Int, y: Int, origin: XConnection) -> Unit)? = null

    init {
        // The root window exists before any client connects. It listens for structure
        // changes on its children so the shell (and tests) always see Create/Map/Destroy.
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
            eventMask = EventMask.SUBSTRUCTURE_NOTIFY,
        )
        // A few atoms every client expects to exist with the standard numbering.
        listOf("PRIMARY", "SECONDARY", "ATOM", "STRING", "WM_NAME", "WM_CLASS", "WM_PROTOCOLS", "WM_DELETE_WINDOW")
            .forEachIndexed { index, name -> internAtom(name, index + 1) }
        // The selection targets the P1 clipboard bridge negotiates (no forced ids:
        // only the eight atoms above are protocol-numbered). Every client interns
        // by name and gets the same server-wide ids.
        listOf("CLIPBOARD", "TARGETS", "UTF8_STRING", "TEXT").forEach { internAtom(it) }
        // The shell's internal selection/property window (see SHELL_WINDOW_ID).
        windows[SHELL_WINDOW_ID] = XWindow(
            id = SHELL_WINDOW_ID,
            parent = null,
            x = 0,
            y = 0,
            width = 1,
            height = 1,
            depth = 0,
        )
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
        if (id == rootId || id == SHELL_WINDOW_ID) return false // internal windows are immortal
        window.children.toList().forEach { destroyWindow(it) }
        window.parent?.let { parent -> windows[parent]?.children?.remove(id) }
        windows.remove(id)
        revertFocusIf(id)
        dropSelectionsOfWindow(id)
        dropWatchesOfWindow(id)
        synchronized(redirectOwners) { redirectOwners.remove(id) }
        synchronized(windowOwners) { windowOwners.remove(id) }
        synchronized(passiveGrabs) { passiveGrabs.removeAll { it.window == id } }
        pointerGrab?.takeIf { it.window == id }?.let { pointerGrab = null }
        keyboardGrab?.takeIf { it.window == id }?.let { keyboardGrab = null }
        return true
    }

    /** CirculateWindow: move a child to the top (place=0) or bottom (place=1) of the stack. */
    fun circulate(id: Int, place: Int): Boolean {
        val window = windows[id] ?: return false
        val parent = window.parent?.let { windows[it] } ?: return false
        if (parent.children.size < 2) return true
        parent.children.remove(id)
        if (place == 0) parent.children.add(id) else parent.children.add(0, id)
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
        revertFocusIf(id)
        dropSelectionsOfWindow(id)
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
    fun windowAt(px: Int, py: Int): XWindow? =
        paintOrder().lastOrNull { it.contains(px, py) && it.inputShapeAllows(px, py) }

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

    /** Mapped windows painted above [id] (CreateRegionFromWindow's Clip kind). */
    fun windowsAbove(id: Int): List<XWindow> =
        paintOrder().dropWhile { it.id != id }.drop(1).filter { it.mapped }

    // --- XFIXES (D1f): watches, cursor names/hiding, the server clock ----------

    /** One SelectSelectionInput registration; events go to the selecting client. */
    class SelectionWatch(val window: Int, var mask: Int, val connection: XConnection)

    /** One SelectCursorInput registration. */
    class CursorWatch(val window: Int, var mask: Int, val connection: XConnection)

    private val selectionWatchers = HashMap<Int, MutableList<SelectionWatch>>() // atom -> watches
    private val cursorWatchers = mutableListOf<CursorWatch>()
    private val cursorNames = HashMap<Int, String>() // cursor id -> name (SetCursorName)
    private val cursorHiddenWindows = HashSet<Int>() // HideCursor'd windows
    private var cursorSerial = 1L

    /**
     * The X timestamp (CARD32 milliseconds). We keep no server-start epoch;
     * truncated epoch millis preserve order and uniqueness, which is all the
     * consumers here (XFIXES events, SYNC alarms) need.
     */
    fun timestamp(): Int = (System.currentTimeMillis() and 0xFFFFFFFFL).toInt()

    fun setSelectionWatch(atom: Int, window: Int, mask: Int, connection: XConnection) {
        synchronized(selectionWatchers) {
            val list = selectionWatchers.getOrPut(atom) { mutableListOf() }
            list.removeAll { it.window == window && it.connection === connection }
            if (mask != 0) list += SelectionWatch(window, mask, connection)
        }
    }

    fun setCursorWatch(window: Int, mask: Int, connection: XConnection) {
        synchronized(cursorWatchers) {
            cursorWatchers.removeAll { it.window == window && it.connection === connection }
            if (mask != 0) cursorWatchers += CursorWatch(window, mask, connection)
        }
    }

    fun dropWatchesOfWindow(windowId: Int) {
        synchronized(selectionWatchers) {
            selectionWatchers.values.forEach { list -> list.removeAll { it.window == windowId } }
        }
        synchronized(cursorWatchers) { cursorWatchers.removeAll { it.window == windowId } }
        synchronized(cursorHiddenWindows) { cursorHiddenWindows.remove(windowId) }
    }

    fun dropWatchesOf(connection: XConnection) {
        synchronized(selectionWatchers) {
            selectionWatchers.values.forEach { list -> list.removeAll { it.connection === connection } }
        }
        synchronized(cursorWatchers) { cursorWatchers.removeAll { it.connection === connection } }
    }

    fun cursorHiddenAt(windowId: Int): Boolean =
        synchronized(cursorHiddenWindows) { cursorHiddenWindows.contains(windowId) }

    fun setCursorHiddenForWindow(windowId: Int, hidden: Boolean) {
        synchronized(cursorHiddenWindows) {
            if (hidden) cursorHiddenWindows += windowId else cursorHiddenWindows -= windowId
        }
    }

    fun setCursorName(cursorId: Int, name: String) {
        synchronized(cursorNames) {
            cursorNames[cursorId] = name
            cursorSerial++
        }
    }

    fun cursorName(cursorId: Int): String? = synchronized(cursorNames) { cursorNames[cursorId] }

    fun cursorIdByName(name: String): Int? =
        synchronized(cursorNames) { cursorNames.entries.firstOrNull { it.value == name }?.key }

    fun currentCursorSerial(): Long = synchronized(cursorNames) { cursorSerial }

    /**
     * XFixesSelectionNotify to everyone watching [atom] with the subtype's mask
     * bit. Each event is written by the *selecting* connection (byte order and
     * sequence numbers are per-connection facts); watches on the root window -
     * GTK's clipboard idiom - work because the watch remembers its client, not
     * the window's owner.
     */
    fun fireSelectionNotify(atom: Int, subtype: Int, ownerWindow: Int) {
        val maskBit = XFixes.maskForSubtype(subtype)
        if (maskBit == 0) return
        val watches = synchronized(selectionWatchers) {
            selectionWatchers[atom]?.filter { it.mask and maskBit != 0 }?.toList() ?: emptyList()
        }
        if (watches.isEmpty()) return
        val now = timestamp()
        watches.forEach { watch ->
            watch.connection.deliverSelectionNotify(
                window = watch.window,
                subtype = subtype,
                owner = ownerWindow,
                selection = atom,
                timestamp = now,
                selectionTimestamp = now,
            )
        }
    }

    /** XFixesCursorNotify to the window's watchers when its displayed cursor changed. */
    fun fireCursorNotify(windowId: Int) {
        val watches = synchronized(cursorWatchers) {
            cursorWatchers.filter {
                it.window == windowId && it.mask and XFixes.MASK_DISPLAY_CURSOR != 0
            }.toList()
        }
        if (watches.isEmpty()) return
        val serial = synchronized(cursorNames) { ++cursorSerial }
        val cursorId = windows[windowId]?.cursor ?: 0
        val nameAtom = cursorName(cursorId)?.let { internAtom(it) } ?: 0
        val now = timestamp()
        watches.forEach { watch ->
            watch.connection.deliverCursorNotify(
                windowId, XFixes.SUBTYPE_DISPLAY_CURSOR, serial, now, nameAtom,
            )
        }
    }

    // --- SYNC (D1f): counters and alarms are server-wide resources --------------

    private val syncCounters = HashMap<Int, XSync.Counter>()
    private val syncAlarms = HashMap<Int, XSync.Alarm>()
    private val syncAwaits = mutableListOf<PendingAwait>()

    /** A held Await; its reply is delivered when a counter change satisfies every condition. */
    class PendingAwait(
        val connection: XConnection,
        val conditions: List<XSync.Condition>,
        val opcode: Int,
        val sequence: Int,
    )

    fun syncCounter(id: Int): XSync.Counter? = synchronized(syncCounters) { syncCounters[id] }

    fun putSyncCounter(counter: XSync.Counter) {
        synchronized(syncCounters) { syncCounters[counter.id] = counter }
    }

    fun removeSyncCounter(id: Int): XSync.Counter? = synchronized(syncCounters) { syncCounters.remove(id) }

    fun syncAlarm(id: Int): XSync.Alarm? = synchronized(syncAlarms) { syncAlarms[id] }

    fun putSyncAlarm(alarm: XSync.Alarm) {
        synchronized(syncAlarms) { syncAlarms[alarm.id] = alarm }
    }

    fun removeSyncAlarm(id: Int): XSync.Alarm? = synchronized(syncAlarms) { syncAlarms.remove(id) }

    fun addPendingAwait(await: PendingAwait) {
        synchronized(syncAwaits) { syncAwaits += await }
    }

    fun dropSyncOf(connection: XConnection) {
        synchronized(syncCounters) { syncCounters.entries.removeAll { it.value.owner === connection } }
        synchronized(syncAlarms) { syncAlarms.entries.removeAll { it.value.owner === connection } }
        synchronized(syncAwaits) { syncAwaits.removeAll { it.connection === connection } }
    }

    /**
     * Evaluates held Awaits and alarms against the counter change [oldValue] ->
     * [counter].value, applies fired alarms' deltas, and returns what the
     * changing connection must deliver. Transition conditions are edge-tested
     * against the changed counter (level-tested against every other one);
     * comparison conditions are level-tested everywhere - the spec's distinction.
     * Alarm deltas apply after evaluation without re-entry, so a notify carries
     * the pre-delta counter value (documented simplification of the spec's
     * ordering; the delta itself is part of the alarm the client holds).
     */
    fun onCounterChanged(
        counter: XSync.Counter,
        oldValue: Long,
    ): Pair<List<PendingAwait>, List<XSync.Alarm>> {
        val satisfied = synchronized(syncAwaits) {
            val done = syncAwaits.filter { await -> awaitSatisfied(await, counter, oldValue) }
            syncAwaits.removeAll(done.toSet())
            done
        }
        val transition = XSync.VALUE_POSITIVE_TRANSITION
        val negative = XSync.VALUE_NEGATIVE_TRANSITION
        val fired = synchronized(syncAlarms) {
            syncAlarms.values.filter { alarm ->
                alarm.counterId == counter.id &&
                    XSync.holds(
                        XSync.Condition(counter.id, alarm.valueType, alarm.value),
                        oldValue,
                        counter.value,
                        crossing = alarm.valueType == transition || alarm.valueType == negative,
                    )
            }.toList()
        }
        fired.forEach { alarm ->
            if (alarm.delta != 0L) counter.value += alarm.delta
            alarm.lastSeen = counter.value
        }
        return satisfied to fired
    }

    private fun awaitSatisfied(await: PendingAwait, changed: XSync.Counter, oldValue: Long): Boolean =
        await.conditions.all { condition ->
            val current = synchronized(syncCounters) { syncCounters[condition.counterId] }
                ?: return@all false
            val transition = condition.valueType == XSync.VALUE_POSITIVE_TRANSITION ||
                condition.valueType == XSync.VALUE_NEGATIVE_TRANSITION
            if (transition && condition.counterId == changed.id) {
                XSync.holds(condition, oldValue, changed.value, crossing = true)
            } else {
                XSync.holds(condition, current.value, current.value, crossing = false)
            }
        }
}
