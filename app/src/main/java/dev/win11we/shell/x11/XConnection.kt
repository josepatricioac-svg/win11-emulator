package dev.win11we.shell.x11

import kotlin.math.min

/** A parsed connection-setup request. */
data class SetupRequest(
    val littleEndian: Boolean,
    val majorVersion: Int,
    val minorVersion: Int,
    val authName: String,
    val authData: String,
)

/**
 * One X client connection: handshake, request parsing, replies, errors and the
 * outbound event queue. No sockets, no threads, no Android - all unit-tested.
 *
 * X reply layout: byte0 = 1, byte1 = reply-specific, bytes2-3 = sequence,
 * bytes4-7 = extra length in 4-byte units, bytes8-31 = body, then extra data.
 * X error layout: byte0 = 0, byte1 = code, bytes2-3 = sequence, bytes4-7 = bad value,
 * bytes8-9 = minor opcode, byte10 = major opcode, 21 bytes pad. Every message is 32 bytes.
 *
 * D1c covers the requests a real libX11/Wine client actually sends: focus and
 * grabs (with real event redirection), pointer warps, the font and colour
 * requests, cursors, SendEvent, ListProperties, a working GetImage, the full
 * keyboard/pointer-control tail block and the XTest extension. Wire layouts are
 * taken from xorgproto's Xproto.h / xtestproto.h.
 */
class XConnection(
    val document: XDocument,
    val sink: XSink = RecordingSink(),
    private val keyboard: XKeyboard = XKeyboard.default(),
) {
    var littleEndian: Boolean = true
        private set
    var sequence: Int = 0
        private set
    var setupDone: Boolean = false
        private set
    var lastError: Int = 0
        private set

    private val outbox = ArrayDeque<ByteArray>()
    private val pending = mutableListOf<ByteArray>()
    private var carry = ByteArray(0)

    private val gcs = HashMap<Int, GcState>()
    private val pixmaps = HashMap<Int, Int>()
    private val fonts = HashMap<Int, String>()
    private val colormaps = HashSet<Int>()
    private val selections = HashMap<Int, Int>()
    private var screenSaver = intArrayOf(600, 600, 1, 1) // timeout, interval, preferBlanking, allowExposures

    private class GcState(
        var foreground: Int = 0xFF000000.toInt(),
        var background: Int? = null,
        var font: Int = 0,
    )

    /**
     * GC values arrive in bit order of the value mask, so they must be consumed
     * accordingly - reading them positionally corrupts every later request.
     */
    private fun applyGcValues(gc: GcState, reader: XReader, mask: Int) {
        // Value-mask bit order = wire order, ascending: function(0x1) before foreground(0x4).
        if (mask and 0x00000001 != 0) reader.int() // GCFunction
        if (mask and 0x00000002 != 0) reader.int() // GCPlaneMask
        if (mask and 0x00000004 != 0) gc.foreground = reader.int() // GCForeground
        if (mask and 0x00000008 != 0) gc.background = reader.int() // GCBackground
        if (mask and 0x00000010 != 0) reader.int() // GCSubwindowMode
        if (mask and 0x00000020 != 0) reader.int() // GCGraphicsExposures
        if (mask and 0x00000040 != 0) { reader.int(); reader.int() } // GCClipOrigin
        if (mask and 0x00000080 != 0) { reader.int(); reader.int() } // GCClipMask
        if (mask and 0x00000100 != 0) reader.int() // GCDashOffset
        if (mask and 0x00000200 != 0) reader.int() // GCDashList
        if (mask and 0x00000400 != 0) reader.int() // GCArcMode
        if (mask and 0x00000800 != 0) gc.font = reader.int() // GCFont
    }

    /**
     * Window attribute values, in value-mask bit order (ascending). Every set bit
     * must be consumed or the later ones land in the wrong fields; the ones we
     * model are kept in the returned holder, the rest are read and dropped. Same
     * list for CreateWindow and ChangeWindowAttributes.
     */
    private class WindowValues {
        var backgroundPixel: Int? = null
        var overrideRedirect: Boolean? = null
        var eventMask: Int? = null
        var cursor: Int? = null
        var doNotPropagate: Int? = null
    }

    /** @return null when a value was invalid and the error was already sent. */
    private fun readWindowValues(reader: XReader, mask: Int, opcode: Int): WindowValues? {
        val values = WindowValues()
        if (mask and 0x00000001 != 0) reader.int() // CWBackPixmap (not rendered)
        if (mask and 0x00000002 != 0) values.backgroundPixel = reader.int()
        if (mask and 0x00000004 != 0) reader.int() // CWBorderPixmap
        if (mask and 0x00000008 != 0) reader.int() // CWBorderPixel (borders are not rendered yet)
        if (mask and 0x00000010 != 0) reader.int() // CWBitGravity
        if (mask and 0x00000020 != 0) reader.int() // CWWinGravity
        if (mask and 0x00000040 != 0) reader.int() // CWBackingStore
        if (mask and 0x00000080 != 0) reader.int() // CWBackingPlanes
        if (mask and 0x00000100 != 0) reader.int() // CWBackingPixel
        if (mask and 0x00000200 != 0) values.overrideRedirect = reader.int() != 0
        if (mask and 0x00000400 != 0) reader.int() // CWSaveUnder
        if (mask and 0x00000800 != 0) values.eventMask = reader.int()
        if (mask and 0x00001000 != 0) values.doNotPropagate = reader.int()
        if (mask and 0x00002000 != 0) reader.int() // CWColormap (single TrueColor map)
        if (mask and 0x00004000 != 0) {
            val cursor = reader.int()
            if (cursor != 0 && document.cursor(cursor) == null) {
                error(X11.ERR_BAD_CURSOR, opcode, cursor)
                return null
            }
            values.cursor = cursor
        }
        return values
    }

    private fun applyWindowValues(window: XWindow, values: WindowValues) {
        values.backgroundPixel?.let { window.backgroundPixel = it }
        values.overrideRedirect?.let { window.overrideRedirect = it }
        values.eventMask?.let { window.eventMask = it }
        values.cursor?.let { window.cursor = it }
        values.doNotPropagate?.let { window.doNotPropagateMask = it }
    }

    // --- pointer/keyboard state, driven by XInput --------------------------
    var rootPointerX: Int = 0
        private set
    var rootPointerY: Int = 0
        private set
    var buttonMask: Int = 0
        private set
    var keyStateMask: Int = 0
        private set

    /** The window the pointer was over last, for EnterNotify/LeaveNotify. */
    private var lastPointerWindow: Int? = null

    fun drainOutbox(): List<ByteArray> = synchronized(outbox) { outbox.toList().also { outbox.clear() } }
    fun outboxBytes(): Int = synchronized(outbox) { outbox.sumOf { it.size } }

    /**
     * Queues a raw message from another thread (WM-driven events, redirected
     * requests) and asks the server to push it to this connection's socket now;
     * an idle client would otherwise not see it until its next request.
     */
    fun deliver(message: ByteArray) {
        synchronized(outbox) { outbox.addLast(message) }
        document.externalFlush?.invoke(this)
    }

    /** Builds one 32-byte event in *this* connection's byte order and delivers it. */
    fun deliverEvent(body: (XWriter) -> Unit) {
        val writer = XWriter(littleEndian)
        body(writer)
        deliver(writer.toByteArray().copyOf(32))
    }

    /**
     * Delivers a pre-built 32-byte event to the connection that owns [windowId]
     * (SendEvent, focus and passive-grab events may target other clients); falls
     * back to this connection for ownerless windows (the root) and tests.
     */
    private fun deliverToOwner(windowId: Int, message: ByteArray) {
        val owner = document.ownerOf(windowId)
        if (owner == null || owner === this) deliver(message) else owner.deliver(message)
    }

    // --- handshake ---------------------------------------------------------

    fun parseSetup(bytes: ByteArray): SetupRequest? {
        if (bytes.size < 12) return null
        val little = when (bytes[0].toInt().toChar()) {
            'B' -> true
            'l' -> false
            else -> return null
        }
        littleEndian = little
        val reader = XReader(bytes, little, 1)
        reader.skip(1)
        val major = reader.short()
        val minor = reader.short()
        val authNameLength = reader.short()
        val authDataLength = reader.short()
        reader.skip(2)
        val authName = if (authNameLength in 0..512) reader.paddedString(authNameLength) else ""
        val authData = if (authDataLength in 0..512) reader.paddedString(authDataLength) else ""
        return SetupRequest(little, major, minor, authName, authData)
    }

    fun setupSuccess(): ByteArray {
        val screen = document.screen
        val writer = XWriter(littleEndian)
        val vendor = X11.VENDOR.toByteArray(Charsets.US_ASCII)
        val formatsLength = screen.pixmapFormats.size * 8
        val visualsLength = screen.visuals.size * 24
        val screenLength = 40 + visualsLength + 8
        // 32 fixed bytes (release .. unused) + vendor + formats + screens follow the header.
        val additional = 32 + X11.pad4(vendor.size) + formatsLength + screenLength

        writer.byte(1)
        writer.pad(1)
        writer.short(X11.PROTOCOL_MAJOR)
        writer.short(X11.PROTOCOL_MINOR)
        writer.short(additional / 4)
        writer.int(X11.RELEASE_NUMBER)
        writer.int(X11.RESOURCE_ID_BASE)
        writer.int(X11.RESOURCE_ID_MASK)
        writer.int(0) // motionBufferSize
        writer.short(vendor.size)
        writer.short(0) // maximumRequestSize: 0 means 64 KiB units
        writer.byte(1) // numberOfScreens
        writer.byte(screen.pixmapFormats.size)
        writer.byte(0) // imageByteOrder LSBFirst
        writer.byte(0) // bitmapFormatBitOrder LeastSignificant
        writer.byte(32) // bitmapFormatScanlineUnit
        writer.byte(32) // bitmapFormatScanlinePad
        writer.byte(keyboard.minKeycode)
        writer.byte(keyboard.maxKeycode)
        writer.pad(4)
        writer.bytes(vendor)
        writer.pad(X11.pad4(vendor.size) - vendor.size)
        screen.pixmapFormats.forEach { format ->
            writer.byte(format.depth)
            writer.byte(format.bitsPerPixel)
            writer.byte(format.scanlinePad)
            writer.pad(5)
        }
        writer.int(screen.rootId)
        writer.int(screen.defaultColormap)
        writer.int(screen.whitePixel)
        writer.int(screen.blackPixel)
        writer.int(0) // currentInputMasks
        writer.short(screen.widthPx)
        writer.short(screen.heightPx)
        writer.short(screen.widthMm)
        writer.short(screen.heightMm)
        writer.short(screen.minInstalledMaps)
        writer.short(screen.maxInstalledMaps)
        writer.int(screen.rootVisual)
        writer.byte(screen.backingStores)
        writer.byte(if (screen.saveUnders) 1 else 0)
        writer.byte(screen.rootDepth)
        writer.byte(1) // numberOfDepths
        writer.byte(screen.rootDepth)
        writer.pad(1)
        writer.short(screen.visuals.size)
        writer.pad(4)
        screen.visuals.forEach { visual ->
            writer.int(visual.visualId)
            writer.byte(visual.visualClass)
            writer.byte(visual.bitsPerRgb)
            writer.short(visual.colormapEntries)
            writer.int(visual.redMask)
            writer.int(visual.greenMask)
            writer.int(visual.blueMask)
            writer.pad(4)
        }
        setupDone = true
        return writer.toByteArray()
    }

    fun setupFailed(reason: String): ByteArray {
        val writer = XWriter(littleEndian)
        val bytes = reason.toByteArray(Charsets.US_ASCII).copyOf(X11.pad4(reason.length))
        writer.byte(0)
        writer.byte(bytes.size) // reasonLength
        writer.short(X11.PROTOCOL_MAJOR)
        writer.short(X11.PROTOCOL_MINOR)
        writer.short(bytes.size / 4) // additional data after the 8-byte header
        writer.bytes(bytes)
        return writer.toByteArray()
    }

    // --- request stream ----------------------------------------------------

    /** Feeds socket bytes; returns how many complete requests were handled. */
    fun onBytes(chunk: ByteArray, length: Int = chunk.size): Int {
        carry = if (carry.isEmpty()) chunk.copyOf(length) else carry + chunk.copyOf(length)
        var handled = 0
        while (carry.size >= 4) {
            val units = if (littleEndian) {
                (carry[2].toInt() and 0xFF) or ((carry[3].toInt() and 0xFF) shl 8)
            } else {
                ((carry[2].toInt() and 0xFF) shl 8) or (carry[3].toInt() and 0xFF)
            }
            val requestBytes = if (units > 0) units * 4 else 4
            if (requestBytes > carry.size) break
            val request = carry.copyOf(requestBytes)
            carry = carry.copyOfRange(requestBytes, carry.size)
            handled++
            handleRequest(request)
        }
        return handled
    }

    /** Last dispatch failure, for tests and the Diagnostics screen. */
    var lastFailure: String? = null
        private set

    private fun handleRequest(request: ByteArray) {
        sequence = (sequence + 1) and 0xFFFF
        val opcode = request[0].toInt() and 0xFF
        val data = request[1].toInt() and 0xFF
        try {
            lastFailure = null
            dispatch(opcode, data, XReader(request, littleEndian, 4), request)
        } catch (error: Exception) {
            lastFailure = "opcode $opcode: ${error.javaClass.simpleName}: ${error.message}"
            error(
                if (error is IndexOutOfBoundsException) X11.ERR_BAD_LENGTH else X11.ERR_BAD_REQUEST,
                opcode,
                0,
            )
        }
        flushPending()
    }

    private fun flushPending() {
        if (pending.isEmpty()) return
        synchronized(outbox) { pending.forEach { outbox.addLast(it) } }
        pending.clear()
    }

    private fun dispatch(opcode: Int, data: Int, reader: XReader, request: ByteArray) {
        when (opcode) {
            X11.REQ_CREATE_WINDOW -> createWindow(opcode, reader, data)
            X11.REQ_CHANGE_WINDOW_ATTRIBUTES -> changeWindowAttributes(opcode, reader)
            X11.REQ_GET_WINDOW_ATTRIBUTES -> getWindowAttributes(opcode, reader)
            X11.REQ_DESTROY_WINDOW -> {
                val id = reader.int()
                val parent = document.window(id)?.parent
                if (document.destroyWindow(id)) {
                    if (id == lastPointerWindow) lastPointerWindow = null
                    notifyStructure(X11.EV_DESTROY_NOTIFY, parent ?: document.rootId, id)
                } else {
                    error(X11.ERR_BAD_WINDOW, opcode, id)
                }
            }
            X11.REQ_DESTROY_SUBWINDOWS -> {
                val id = reader.int()
                document.window(id)?.children?.toList()?.forEach { document.destroyWindow(it) }
            }
            X11.REQ_CHANGE_SAVE_SET -> Unit // save-sets matter for crashing external WMs only
            X11.REQ_REPARENT_WINDOW -> {
                val id = reader.int()
                val newParent = reader.int()
                reader.short(); reader.short()
                val window = document.window(id)
                val target = document.window(newParent)
                if (window == null || target == null) return error(X11.ERR_BAD_WINDOW, opcode, id)
                window.parent?.let { document.window(it)?.children?.remove(id) }
                window.parent = newParent
                target.children.add(id)
                notifyStructure(X11.EV_REPARENT_NOTIFY, newParent, id)
            }
            X11.REQ_MAP_WINDOW, X11.REQ_MAP_SUBWINDOWS -> {
                val id = reader.int()
                val window = document.window(id) ?: return error(X11.ERR_BAD_WINDOW, opcode, id)
                val targets = if (opcode == X11.REQ_MAP_SUBWINDOWS) window.children.toList() else listOf(id)
                targets.forEach { target ->
                    val tw = document.window(target) ?: return@forEach
                    val parentId = if (opcode == X11.REQ_MAP_SUBWINDOWS) window.id
                    else tw.parent ?: document.rootId
                    // A WM holding SubstructureRedirect gets a MapRequest instead; the
                    // window stays unmapped until the WM maps it itself.
                    if (!tw.mapped && redirectMap(parentId, tw)) return@forEach
                    val wasMapped = tw.mapped
                    document.mapWindow(target)
                    // X sends MapNotify on every un->mapped transition, even while an
                    // unmapped ancestor keeps the window from becoming viewable yet.
                    if (!wasMapped && document.window(target) != null) {
                        notifyStructure(X11.EV_MAP_NOTIFY, parentId, target)
                    }
                }
            }
            X11.REQ_UNMAP_WINDOW -> {
                val id = reader.int()
                val parent = document.window(id)?.parent ?: document.rootId
                if (document.unmapWindow(id)) {
                    if (id == lastPointerWindow) lastPointerWindow = null
                    notifyStructure(X11.EV_UNMAP_NOTIFY, parent, id)
                } else {
                    error(X11.ERR_BAD_WINDOW, opcode, id)
                }
            }
            X11.REQ_CONFIGURE_WINDOW -> configureWindow(opcode, reader)
            X11.REQ_CIRCULATE_WINDOW -> {
                val id = reader.int()
                if (document.window(id) == null) return error(X11.ERR_BAD_WINDOW, opcode, id)
                document.circulate(id, data)
            }
            X11.REQ_GET_GEOMETRY -> getGeometry(opcode, reader)
            X11.REQ_QUERY_TREE -> queryTree(opcode, reader)
            X11.REQ_INTERN_ATOM -> internAtom(data, reader)
            X11.REQ_GET_ATOM_NAME -> getAtomName(opcode, reader)
            X11.REQ_CHANGE_PROPERTY -> changeProperty(opcode, reader, request)
            X11.REQ_DELETE_PROPERTY -> {
                val window = reader.int()
                val atom = reader.int()
                if (document.deleteProperty(window, atom)) {
                    notifyProperty(window, atom)
                } else {
                    error(X11.ERR_BAD_WINDOW, opcode, window)
                }
            }
            X11.REQ_GET_PROPERTY -> getProperty(opcode, data, reader)
            X11.REQ_LIST_PROPERTIES -> {
                val window = reader.int()
                val target = document.window(window) ?: return error(X11.ERR_BAD_WINDOW, opcode, window)
                val atoms = target.properties.keys.toList()
                reply(opcode, extraLength = atoms.size * 4) { writer ->
                    writer.short(atoms.size)
                    writer.pad(22)
                    atoms.forEach { writer.int(it) }
                }
            }
            X11.REQ_SET_SELECTION_OWNER -> {
                val owner = reader.int()
                val selection = reader.int()
                selections[selection] = owner
            }
            X11.REQ_GET_SELECTION_OWNER -> {
                val selection = reader.int()
                reply(opcode) { writer ->
                    writer.int(selections[selection] ?: 0)
                    writer.pad(20)
                }
            }
            X11.REQ_CONVERT_SELECTION -> {
                val requestor = reader.int()
                val selection = reader.int()
                val target = reader.int()
                val property = reader.int()
                // We own no selections yet: answer None so clients fall back instead of hanging.
                queue(
                    eventWriter(X11.EV_SELECTION_NOTIFY)
                        .int(0).int(requestor).int(selection).int(target).int(property).pad(8),
                )
            }
            X11.REQ_SEND_EVENT -> sendEventRequest(data, opcode, reader, request)
            X11.REQ_GRAB_POINTER -> {
                // ownerEvents in the data byte; xGrabPointerReq: grab-window@4,
                // event-mask@8, pointer/keyboard mode@10/11, confine-to@12, cursor@16, time@20.
                val ownerEvents = data != 0
                val window = reader.int()
                val eventMask = reader.short()
                reader.byte(); reader.byte() // modes (we never freeze)
                val confineTo = reader.int()
                val cursor = reader.int()
                reader.int() // time
                if (document.window(window) == null) return error(X11.ERR_BAD_WINDOW, opcode, window)
                val ok = document.grabPointer(
                    PointerGrab(window, ownerEvents, eventMask, confineTo, cursor, this),
                )
                reply(opcode, dataByte = if (ok) 0 else 1 /* AlreadyGrabbed */) { it.pad(24) }
            }
            X11.REQ_UNGRAB_POINTER -> {
                reader.int() // time
                document.ungrabPointer(this)
            }
            X11.REQ_GRAB_BUTTON -> {
                // grab-window@4, event-mask@8, modes@10/11, confine@12, cursor@16, button@20, modifiers@22.
                val ownerEvents = data != 0
                val window = reader.int()
                val eventMask = reader.short()
                reader.byte(); reader.byte()
                val confineTo = reader.int()
                val cursor = reader.int()
                val button = reader.byte()
                reader.skip(1)
                val modifiers = reader.short()
                if (document.window(window) == null) return error(X11.ERR_BAD_WINDOW, opcode, window)
                document.addPassiveGrab(
                    PassiveGrab(window, eventMask, button, modifiers, isKey = false, connection = this,
                        ownerEvents = ownerEvents, confineTo = confineTo, cursor = cursor),
                )
            }
            X11.REQ_UNGRAB_BUTTON -> {
                // button is the data byte; grab-window@4, modifiers@8 (CARD16).
                val window = reader.int()
                val modifiers = reader.short()
                document.removePassiveGrab(window, data, modifiers, isKey = false)
            }
            X11.REQ_CHANGE_ACTIVE_POINTER_GRAB -> {
                val cursor = reader.int()
                reader.int() // time
                val eventMask = reader.short()
                document.changePointerGrab(this, eventMask, cursor)
            }
            X11.REQ_GRAB_KEYBOARD -> {
                val ownerEvents = data != 0
                val window = reader.int()
                reader.int() // time
                if (document.window(window) == null) return error(X11.ERR_BAD_WINDOW, opcode, window)
                val ok = document.grabKeyboard(KeyboardGrab(window, ownerEvents, this))
                reply(opcode, dataByte = if (ok) 0 else 1) { it.pad(24) }
            }
            X11.REQ_UNGRAB_KEYBOARD -> {
                reader.int() // time
                document.ungrabKeyboard(this)
            }
            X11.REQ_GRAB_KEY -> {
                // grab-window@4, modifiers@8 (CARD16), key@10, modes@11/12.
                val ownerEvents = data != 0
                val window = reader.int()
                val modifiers = reader.short()
                val key = reader.byte()
                if (document.window(window) == null) return error(X11.ERR_BAD_WINDOW, opcode, window)
                document.addPassiveGrab(
                    PassiveGrab(window, EventMask.KEY_PRESS or EventMask.KEY_RELEASE, key, modifiers,
                        isKey = true, connection = this, ownerEvents = ownerEvents),
                )
            }
            X11.REQ_UNGRAB_KEY -> {
                // key is the data byte; grab-window@4, modifiers@8 (CARD16).
                val window = reader.int()
                val modifiers = reader.short()
                document.removePassiveGrab(window, data, modifiers, isKey = true)
            }
            X11.REQ_ALLOW_EVENTS, X11.REQ_GRAB_SERVER, X11.REQ_UNGRAB_SERVER -> Unit // we never freeze
            X11.REQ_QUERY_POINTER -> {
                val id = reader.int()
                val target = document.window(id) ?: return error(X11.ERR_BAD_WINDOW, opcode, id)
                val child = document.windowAt(rootPointerX, rootPointerY)?.takeIf { it.id != target.id }
                reply(opcode, dataByte = 1 /* sameScreen */) { writer ->
                    writer.int(document.rootId)
                    writer.int(child?.id ?: 0)
                    writer.short(rootPointerX)
                    writer.short(rootPointerY)
                    writer.short(rootPointerX - target.x)
                    writer.short(rootPointerY - target.y)
                    writer.short(buttonMask or keyStateMask)
                    writer.pad(6)
                }
            }
            X11.REQ_GET_MOTION_EVENTS -> {
                val window = reader.int()
                if (document.window(window) == null) return error(X11.ERR_BAD_WINDOW, opcode, window)
                // We keep no motion history buffer; an empty list is legal.
                reply(opcode) { writer -> writer.int(0); writer.pad(20) }
            }
            X11.REQ_TRANSLATE_COORDINATES -> {
                val src = reader.int()
                val dst = reader.int()
                val srcX = reader.shortSigned()
                val srcY = reader.shortSigned()
                val srcWindow = document.window(src) ?: return error(X11.ERR_BAD_WINDOW, opcode, src)
                val dstWindow = document.window(dst) ?: return error(X11.ERR_BAD_WINDOW, opcode, dst)
                val rootX = srcWindow.x + srcX
                val rootY = srcWindow.y + srcY
                reply(opcode, dataByte = 1 /* sameScreen */) { writer ->
                    writer.int(0) // child
                    writer.short(rootX - dstWindow.x)
                    writer.short(rootY - dstWindow.y)
                    writer.pad(16)
                }
            }
            X11.REQ_WARP_POINTER -> {
                // src@4, dst@8, src-x@12, src-y@14, src-w@16, src-h@18, dst-x@20, dst-y@22.
                val src = reader.int()
                val dst = reader.int()
                val srcX = reader.shortSigned(); val srcY = reader.shortSigned()
                val srcW = reader.short(); val srcH = reader.short()
                val dstX = reader.shortSigned(); val dstY = reader.shortSigned()
                if (src != 0) {
                    val s = document.window(src) ?: return error(X11.ERR_BAD_WINDOW, opcode, src)
                    val w = if (srcW == 0) s.width else srcW
                    val h = if (srcH == 0) s.height else srcH
                    val inside = rootPointerX in s.x + srcX until s.x + srcX + w &&
                        rootPointerY in s.y + srcY until s.y + srcY + h
                    if (!inside) return // X: the warp silently does nothing
                }
                val base = if (dst != 0) {
                    document.window(dst) ?: return error(X11.ERR_BAD_WINDOW, opcode, dst)
                } else null
                val nx = (base?.x ?: 0) + dstX
                val ny = (base?.y ?: 0) + dstY
                warpMove(nx, ny)
            }
            X11.REQ_SET_INPUT_FOCUS -> {
                val revertTo = data
                val window = reader.int()
                reader.int() // time
                val old = document.setInputFocus(window, revertTo)
                if (old == null) return error(X11.ERR_BAD_WINDOW, opcode, window)
                sendFocusChange(old, window)
            }
            X11.REQ_GET_INPUT_FOCUS -> reply(opcode, dataByte = document.focusRevertTo) { writer ->
                writer.int(document.focus)
                writer.pad(20)
            }
            X11.REQ_QUERY_KEYMAP -> reply(opcode, extraLength = 8) { writer ->
                writer.bytes(document.keymapBits())
            }
            X11.REQ_OPEN_FONT -> {
                val fid = reader.int()
                val nameLength = reader.short()
                reader.skip(2)
                val name = if (nameLength in 0..512) reader.paddedString(nameLength) else ""
                // Every name is accepted and served with the built-in fixed metrics:
                // refusing font names breaks far more clients than approximate ones.
                fonts[fid] = name
            }
            X11.REQ_CLOSE_FONT -> fonts.remove(reader.int())
            X11.REQ_QUERY_FONT -> queryFont(opcode, reader)
            X11.REQ_QUERY_TEXT_EXTENTS -> {
                val fid = reader.int()
                if (!fonts.containsKey(fid) && !gcs.containsKey(fid)) {
                    return error(X11.ERR_BAD_FONT, opcode, fid)
                }
                // Data is a CHAR2B list; oddLength (the request's data byte) marks the pad.
                val chars = if (data != 0) (request.size - 8 - 2) / 2 else (request.size - 8) / 2
                val width = FONT_CHAR_WIDTH * chars.coerceAtLeast(0)
                reply(opcode, dataByte = 0 /* LeftToRight */) { writer ->
                    writer.short(FONT_ASCENT); writer.short(FONT_DESCENT)
                    writer.short(FONT_ASCENT); writer.short(FONT_DESCENT)
                    writer.int(width); writer.int(0); writer.int(width)
                    writer.pad(4)
                }
            }
            X11.REQ_LIST_FONTS -> {
                val maxNames = reader.short()
                val names = if (maxNames == 0) emptyList() else listOf(FONT_NAME)
                val bytes = names.sumOf { X11.pad4(it.length + 1) }
                reply(opcode, extraLength = bytes) { writer ->
                    writer.short(names.size)
                    writer.pad(22)
                    names.forEach { name ->
                        writer.byte(name.length)
                        writer.paddedString(name)
                    }
                }
            }
            X11.REQ_LIST_FONTS_WITH_INFO -> {
                // One info reply for "fixed", then the nameLength=0 terminator.
                // Both replies carry this request's sequence number (multi-reply protocol).
                val name = FONT_NAME.toByteArray(Charsets.US_ASCII)
                reply(opcode, dataByte = name.size, extraLength = 28 + X11.pad4(name.size)) { writer ->
                    writeFontInfoBody(writer)
                    writer.int(0) // replies-hint: none after the terminator
                    writer.bytes(name)
                    writer.pad(X11.pad4(name.size) - name.size)
                }
                reply(opcode, dataByte = 0, extraLength = 28) { writer ->
                    writer.pad(48) // zeroed FONTINFO, nameLength 0 ends the sequence
                    writer.int(0)
                }
            }
            X11.REQ_SET_FONT_PATH -> Unit // the built-in font needs no path
            X11.REQ_GET_FONT_PATH -> {
                val path = "/usr/share/fonts/misc"
                val bytes = path.toByteArray(Charsets.US_ASCII)
                reply(opcode, extraLength = X11.pad4(bytes.size + 1)) { writer ->
                    writer.short(1)
                    writer.pad(22)
                    writer.byte(bytes.size)
                    writer.bytes(bytes)
                    writer.pad(X11.pad4(bytes.size + 1) - bytes.size - 1)
                }
            }
            X11.REQ_CREATE_GC -> {
                val id = reader.int()
                reader.int() // drawable
                val gc = GcState()
                applyGcValues(gc, XReader(request, littleEndian, 16), reader.int())
                gcs[id] = gc
            }
            X11.REQ_CHANGE_GC -> {
                val id = reader.int()
                reader.int() // drawable
                val gc = gcs.getOrPut(id) { GcState() }
                val mask = XReader(request, littleEndian, 8).int()
                applyGcValues(gc, XReader(request, littleEndian, 12), mask)
            }
            X11.REQ_FREE_GC -> gcs.remove(reader.int())
            X11.REQ_CREATE_PIXMAP -> {
                val id = reader.int()
                reader.int() // drawable
                val depth = reader.short()
                val width = reader.short()
                val height = reader.short()
                pixmaps[id] = depth.toInt()
                sink.setSize(id, width, height)
            }
            X11.REQ_FREE_PIXMAP -> {
                val id = reader.int()
                pixmaps.remove(id)
                (sink as? XDisplayList)?.remove(id)
            }
            X11.REQ_CREATE_COLORMAP -> colormaps.add(reader.int())
            X11.REQ_FREE_COLORMAP -> colormaps.remove(reader.int())
            X11.REQ_COPY_COLORMAP_AND_FREE -> {
                val id = reader.int()
                reader.int() // source map
                colormaps.add(id)
            }
            X11.REQ_INSTALL_COLORMAP, X11.REQ_UNINSTALL_COLORMAP -> Unit // single TrueColor map
            X11.REQ_LIST_INSTALLED_COLORMAPS -> {
                val window = reader.int()
                if (document.window(window) == null) return error(X11.ERR_BAD_WINDOW, opcode, window)
                reply(opcode, extraLength = 4) { writer ->
                    writer.short(1)
                    writer.pad(22)
                    writer.int(document.screen.defaultColormap)
                }
            }
            X11.REQ_ALLOC_COLOR -> {
                reader.int()
                val red = reader.short()
                val green = reader.short()
                val blue = reader.short()
                val pixel = ((red shr 8) and 0xFF shl 16) or ((green shr 8) and 0xFF shl 8) or ((blue shr 8) and 0xFF)
                reply(opcode) { writer ->
                    writer.short(red); writer.short(green); writer.short(blue)
                    writer.pad(2); writer.int(pixel); writer.pad(12)
                }
            }
            X11.REQ_ALLOC_NAMED_COLOR -> {
                val cmap = reader.int()
                val nameLength = reader.short()
                reader.skip(2)
                val name = if (nameLength in 0..512) reader.paddedString(nameLength) else ""
                if (cmap != document.screen.defaultColormap && cmap !in colormaps) {
                    return error(X11.ERR_BAD_COLORMAP, opcode, cmap)
                }
                val rgb = XColors.resolve(name) ?: return error(X11.ERR_BAD_NAME, opcode, 0)
                val (r16, g16, b16) = XColors.to16(rgb)
                reply(opcode) { writer ->
                    writer.int(rgb)
                    writer.short(r16); writer.short(g16); writer.short(b16)
                    writer.short(r16); writer.short(g16); writer.short(b16)
                    writer.pad(8)
                }
            }
            X11.REQ_LOOKUP_COLOR -> {
                reader.int() // colormap
                val nameLength = reader.short()
                reader.skip(2)
                val name = if (nameLength in 0..512) reader.paddedString(nameLength) else ""
                val rgb = XColors.resolve(name) ?: return error(X11.ERR_BAD_NAME, opcode, 0)
                val (r16, g16, b16) = XColors.to16(rgb)
                reply(opcode) { writer ->
                    writer.short(r16); writer.short(g16); writer.short(b16)
                    writer.short(r16); writer.short(g16); writer.short(b16)
                    writer.pad(12)
                }
            }
            X11.REQ_QUERY_COLORS -> {
                reader.int() // colormap
                val count = (request.size - 8) / 4
                reply(opcode, extraLength = count * 8) { writer ->
                    writer.short(count)
                    writer.pad(22)
                    repeat(count) {
                        val pixel = reader.int()
                        writer.short(((pixel shr 16) and 0xFF) * 0x101)
                        writer.short(((pixel shr 8) and 0xFF) * 0x101)
                        writer.short((pixel and 0xFF) * 0x101)
                        writer.pad(2)
                    }
                }
            }
            X11.REQ_ALLOC_COLOR_CELLS -> {
                reader.int() // colormap
                val colors = reader.short()
                reader.short() // planes
                val n = min(colors, 1)
                reply(opcode, extraLength = n * 4) { writer ->
                    writer.short(n); writer.short(0) // nPixels, nMasks
                    writer.pad(20)
                    repeat(n) { writer.int(0) }
                }
            }
            X11.REQ_ALLOC_COLOR_PLANES -> {
                reader.int()
                val colors = reader.short()
                reader.short(); reader.short() // planes, contiguous
                val n = min(colors, 1)
                reply(opcode, extraLength = n * 4) { writer ->
                    writer.short(n)
                    writer.pad(2)
                    writer.int(0); writer.int(0); writer.int(0) // red/green/blue masks
                    writer.pad(8)
                    repeat(n) { writer.int(0) }
                }
            }
            X11.REQ_FREE_COLORS, X11.REQ_STORE_COLORS, X11.REQ_STORE_NAMED_COLOR -> Unit // TrueColor
            X11.REQ_CLEAR_AREA -> {
                val exposures = data != 0
                val window = reader.int()
                val x = reader.shortSigned(); val y = reader.shortSigned()
                val width = reader.short(); val height = reader.short()
                sink.clearArea(window, x, y, width, height)
                if (exposures) exposeIfWanted(window, x, y, width, height)
            }
            X11.REQ_POLY_RECTANGLE, X11.REQ_POLY_FILL_RECTANGLE -> {
                val drawable = reader.int()
                val gc = reader.int()
                val foreground = gcs[gc]?.foreground ?: 0
                val count = (request.size - 12) / 8
                repeat(count) {
                    val x = reader.shortSigned(); val y = reader.shortSigned()
                    val width = reader.short(); val height = reader.short()
                    sink.fillRect(drawable, x, y, width, height, foreground)
                }
            }
            X11.REQ_POLY_POINT -> {
                val drawable = reader.int()
                val gc = reader.int()
                val coords = readCoordPairs(request, 12, reader)
                sink.points(drawable, coords, gcs[gc]?.foreground ?: 0)
            }
            X11.REQ_POLY_LINE -> {
                val drawable = reader.int()
                val gc = reader.int()
                val coords = readCoordPairs(request, 12, reader)
                sink.lines(drawable, coords, gcs[gc]?.foreground ?: 0, pairs = false)
            }
            X11.REQ_POLY_SEGMENT -> {
                val drawable = reader.int()
                val gc = reader.int()
                val coords = readCoordPairs(request, 12, reader)
                sink.lines(drawable, coords, gcs[gc]?.foreground ?: 0, pairs = true)
            }
            X11.REQ_POLY_ARC, X11.REQ_POLY_FILL_ARC -> {
                val drawable = reader.int()
                val gc = reader.int()
                val foreground = gcs[gc]?.foreground ?: 0
                val count = (request.size - 12) / 12
                repeat(count) {
                    val x = reader.shortSigned(); val y = reader.shortSigned()
                    val w = reader.short(); val h = reader.short()
                    val a1 = reader.shortSigned(); val a2 = reader.shortSigned()
                    sink.arc(drawable, x, y, w, h, a1, a2, opcode == X11.REQ_POLY_FILL_ARC, foreground)
                }
            }
            X11.REQ_FILL_POLY -> {
                // data byte unused; drawable@4, gc@8, shape@12, coord-mode@13, points@16.
                val drawable = reader.int()
                val gc = reader.int()
                reader.skip(4) // shape + coord-mode + pad
                val coords = readCoordPairs(request, 16, reader)
                sink.polygon(drawable, coords, gcs[gc]?.foreground ?: 0)
            }
            X11.REQ_SET_DASHES, X11.REQ_SET_CLIP_RECTANGLES, X11.REQ_COPY_GC -> Unit // accepted, not rendered
            X11.REQ_COPY_AREA -> {
                // Wire: src@4, dst@8, gc@12, src-x@16, src-y@18, dst-x@20, dst-y@22, w@24, h@26.
                val src = reader.int()
                val dst = reader.int()
                reader.int() // gc
                val srcX = reader.shortSigned(); val srcY = reader.shortSigned()
                val dstX = reader.shortSigned(); val dstY = reader.shortSigned()
                val width = reader.short(); val height = reader.short()
                sink.copyArea(src, srcX, srcY, dst, dstX, dstY, width, height)
            }
            X11.REQ_COPY_PLANE -> {
                // Best effort: a single-plane copy of a depth-24/32 drawable behaves
                // like CopyArea for our renderer; true bit-plane blits are not modelled.
                val src = reader.int()
                val dst = reader.int()
                reader.int() // gc
                val srcX = reader.shortSigned(); val srcY = reader.shortSigned()
                val dstX = reader.shortSigned(); val dstY = reader.shortSigned()
                val width = reader.short(); val height = reader.short()
                sink.copyArea(src, srcX, srcY, dst, dstX, dstY, width, height)
            }
            X11.REQ_PUT_IMAGE -> {
                // Wire: byte1 = format (2 = ZPixmap), drawable@4, src-w@12, src-h@14,
                // dst-x@16, dst-y@18, left-pad@20, depth@21, data@24.
                val drawable = reader.int()
                reader.int() // gc
                val width = reader.short()
                val height = reader.short()
                val dstX = reader.shortSigned()
                val dstY = reader.shortSigned()
                reader.byte() // leftPad
                val depth = reader.byte()
                reader.skip(2) // unused
                val pixels = request.copyOfRange(24, request.size)
                sink.putImage(drawable, dstX, dstY, width, height, depth, pixels)
            }
            X11.REQ_GET_IMAGE -> getImage(opcode, data, reader)
            X11.REQ_POLY_TEXT8 -> polyText(opcode, reader, request, sixteen = false, background = null)
            X11.REQ_POLY_TEXT16 -> polyText(opcode, reader, request, sixteen = true, background = null)
            X11.REQ_IMAGE_TEXT8 -> {
                val length = data
                val drawable = reader.int()
                val gc = reader.int()
                val x = reader.shortSigned(); val y = reader.shortSigned()
                val end = (16 + length).coerceAtMost(request.size)
                val start = 16.coerceAtMost(request.size)
                val text = String(request.copyOfRange(start, end), Charsets.US_ASCII)
                sink.imageText(drawable, x, y, text, gcs[gc]?.foreground ?: 0xFF000000.toInt(), gcs[gc]?.background)
            }
            X11.REQ_IMAGE_TEXT16 -> {
                // CHAR2B text: our fonts are Latin-1, so the low byte is the character.
                val length = data
                val drawable = reader.int()
                val gc = reader.int()
                val x = reader.shortSigned(); val y = reader.shortSigned()
                val start = 16.coerceAtMost(request.size)
                val end = (start + length * 2).coerceAtMost(request.size)
                val text = buildString {
                    var i = start
                    while (i + 1 < end) {
                        val high = request[i].toInt() and 0xFF
                        val low = request[i + 1].toInt() and 0xFF
                        append(if (high == 0 && low in 0x20..0xFF) low.toChar() else '?')
                        i += 2
                    }
                }
                sink.imageText(drawable, x, y, text, gcs[gc]?.foreground ?: 0xFF000000.toInt(), gcs[gc]?.background)
            }
            X11.REQ_CREATE_CURSOR -> {
                val cid = reader.int()
                val source = reader.int()
                val mask = reader.int()
                val foreR = reader.short(); val foreG = reader.short(); val foreB = reader.short()
                val backR = reader.short(); val backG = reader.short(); val backB = reader.short()
                if (!pixmaps.containsKey(source)) return error(X11.ERR_BAD_PIXMAP, opcode, source)
                if (mask != 0 && !pixmaps.containsKey(mask)) return error(X11.ERR_BAD_PIXMAP, opcode, mask)
                document.addCursor(
                    XCursorResource(cid, glyph = 0,
                        foreground = (foreR shr 8 shl 16) or (foreG shr 8 shl 8) or (foreB shr 8),
                        background = (backR shr 8 shl 16) or (backG shr 8 shl 8) or (backB shr 8)),
                )
            }
            X11.REQ_CREATE_GLYPH_CURSOR -> {
                val cid = reader.int()
                val sourceFont = reader.int()
                reader.int() // mask font (None allowed)
                val sourceChar = reader.short()
                reader.short() // mask char
                val foreR = reader.short(); val foreG = reader.short(); val foreB = reader.short()
                val backR = reader.short(); val backG = reader.short(); val backB = reader.short()
                if (!fonts.containsKey(sourceFont)) return error(X11.ERR_BAD_FONT, opcode, sourceFont)
                document.addCursor(
                    XCursorResource(cid, glyph = sourceChar,
                        foreground = (foreR shr 8 shl 16) or (foreG shr 8 shl 8) or (foreB shr 8),
                        background = (backR shr 8 shl 16) or (backG shr 8 shl 8) or (backB shr 8)),
                )
            }
            X11.REQ_FREE_CURSOR -> document.removeCursor(reader.int())
            X11.REQ_RECOLOR_CURSOR -> {
                val cid = reader.int()
                val foreR = reader.short(); val foreG = reader.short(); val foreB = reader.short()
                val backR = reader.short(); val backG = reader.short(); val backB = reader.short()
                val existing = document.cursor(cid) ?: return error(X11.ERR_BAD_CURSOR, opcode, cid)
                document.addCursor(
                    existing.copy(
                        foreground = (foreR shr 8 shl 16) or (foreG shr 8 shl 8) or (foreB shr 8),
                        background = (backR shr 8 shl 16) or (backG shr 8 shl 8) or (backB shr 8),
                    ),
                )
            }
            X11.REQ_QUERY_BEST_SIZE -> {
                val drawable = reader.int()
                val width = reader.short()
                val height = reader.short()
                if (document.window(drawable) == null && !pixmaps.containsKey(drawable)) {
                    return error(X11.ERR_BAD_DRAWABLE, opcode, drawable)
                }
                val best = when (data) {
                    0 -> min(width, 64) to min(height, 64) // cursors: our shapes are 64px max
                    else -> width to height // tiles/stipples: anything goes
                }
                reply(opcode) { writer ->
                    writer.short(best.first); writer.short(best.second); writer.pad(20)
                }
            }
            X11.REQ_QUERY_EXTENSION -> {
                val nameLength = reader.short()
                reader.skip(2)
                val name = if (nameLength in 0..512) reader.paddedString(nameLength) else ""
                val ext = EXTENSIONS[name.uppercase()]
                reply(opcode) { writer ->
                    writer.byte(if (ext != null) 1 else 0) // present
                    writer.byte(ext?.get(0) ?: 0) // major opcode
                    writer.byte(ext?.get(1) ?: 0) // first event
                    writer.byte(ext?.get(2) ?: 0) // first error
                    writer.pad(20)
                }
            }
            X11.REQ_LIST_EXTENSIONS -> {
                val names = EXTENSIONS.keys.toList()
                val bytes = names.sumOf { X11.pad4(it.length + 1) }
                reply(opcode, dataByte = names.size, extraLength = bytes) { writer ->
                    writer.pad(24)
                    names.forEach { name ->
                        writer.byte(name.length)
                        writer.paddedString(name)
                    }
                }
            }
            X11.REQ_GET_KEYBOARD_MAPPING -> {
                val firstKeycode = reader.byte()
                val count = reader.byte()
                val perCode = keyboard.keysymsPerKeycode
                reply(opcode, dataByte = perCode, extraLength = count * perCode * 4) { writer ->
                    writer.pad(24)
                    repeat(count) { index ->
                        repeat(perCode) { slot -> writer.int(keyboard.keysymFor(firstKeycode + index, slot)) }
                    }
                }
            }
            X11.REQ_CHANGE_KEYBOARD_MAPPING, X11.REQ_CHANGE_KEYBOARD_CONTROL -> Unit // our map is fixed
            X11.REQ_GET_KEYBOARD_CONTROL -> reply(opcode, dataByte = 1 /* global auto-repeat on */, extraLength = 20) { writer ->
                writer.int(0) // ledMask
                writer.byte(50) // keyClickPercent
                writer.byte(50) // bellPercent
                writer.short(400) // bellPitch
                writer.short(100) // bellDuration
                writer.pad(2)
                repeat(32) { writer.byte(0xFF) } // auto-repeats: all keys
            }
            X11.REQ_BELL -> Unit // the shell may surface this later; no sound yet
            X11.REQ_CHANGE_POINTER_CONTROL -> Unit
            X11.REQ_GET_POINTER_CONTROL -> reply(opcode) { writer ->
                writer.short(2); writer.short(1) // acceleration 2/1
                writer.short(4) // threshold
                writer.pad(18)
            }
            X11.REQ_SET_SCREEN_SAVER -> {
                val timeout = reader.short()
                val interval = reader.short()
                val preferBlanking = reader.byte()
                val allowExposures = reader.byte()
                screenSaver = intArrayOf(timeout, interval, preferBlanking, allowExposures)
            }
            X11.REQ_GET_SCREEN_SAVER -> reply(opcode) { writer ->
                writer.short(screenSaver[0]); writer.short(screenSaver[1])
                writer.byte(screenSaver[2]); writer.byte(screenSaver[3])
                writer.pad(18)
            }
            X11.REQ_CHANGE_HOSTS, X11.REQ_SET_ACCESS_CONTROL -> Unit // no access control
            X11.REQ_LIST_HOSTS -> reply(opcode, dataByte = 0 /* disabled */) { writer ->
                writer.short(0); writer.pad(22)
            }
            X11.REQ_SET_CLOSE_DOWN_MODE, X11.REQ_FORCE_SCREEN_SAVER -> Unit
            X11.REQ_KILL_CLIENT -> Unit // no per-client resource tracking yet; single-app server
            X11.REQ_ROTATE_PROPERTIES -> {
                val window = reader.int()
                val nAtoms = reader.short()
                val delta = reader.short().toShort().toInt() // signed
                val target = document.window(window) ?: return error(X11.ERR_BAD_WINDOW, opcode, window)
                val atoms = List(nAtoms) { reader.int() }
                if (nAtoms > 0 && delta % nAtoms != 0) {
                    val present = atoms.filter { target.properties.containsKey(it) }
                    val n = present.size
                    if (n > 0) {
                        val values = present.map { target.properties.getValue(it) }
                        val shift = ((delta % n) + n) % n
                        present.forEachIndexed { index, atom ->
                            val moved = values[(index + shift) % n]
                            document.setProperty(window, atom, moved.type, moved.format, moved.data)
                            notifyProperty(window, atom)
                        }
                    }
                }
            }
            X11.REQ_SET_POINTER_MAPPING -> {
                val n = data
                if (n < 0) return error(X11.ERR_BAD_VALUE, opcode, n)
                reply(opcode, dataByte = 0 /* Success */) { it.pad(24) }
            }
            X11.REQ_GET_POINTER_MAPPING -> reply(opcode, dataByte = 3) { writer ->
                writer.pad(24)
                writer.byte(1); writer.byte(2); writer.byte(3)
            }
            X11.REQ_SET_MODIFIER_MAPPING -> reply(opcode, dataByte = 0 /* Success */) { it.pad(24) }
            X11.REQ_GET_MODIFIER_MAPPING -> reply(opcode, dataByte = 1, extraLength = 8) { writer ->
                writer.pad(24)
                // One keycode per modifier, evdev+8 positions: Shift_L, CapsLock,
                // Control_L, Mod1=Alt_L, Mod2..3 unused, Mod4=Super_L, Mod5 unused.
                writer.byte(XKeyboard.XKEY_SHIFT_L)
                writer.byte(XKeyboard.XKEY_CAPS_LOCK)
                writer.byte(XKeyboard.XKEY_CONTROL_L)
                writer.byte(XKeyboard.XKEY_ALT_L)
                writer.byte(0)
                writer.byte(0)
                writer.byte(XKeyboard.XKEY_SUPER_L)
                writer.byte(0)
            }
            X11.REQ_NO_OPERATION -> Unit
            X11.EXT_XTEST_OPCODE -> xtestRequest(opcode, data, reader, request)
            else -> error(X11.ERR_BAD_REQUEST, opcode, 0)
        }
    }

    /**
     * INT16 coordinate pairs from [offset] to the end of the request. The
     * coord-mode byte (Origin vs Previous) is ignored: clients using Previous
     * mode are rare and the approximation is a rendering detail, not a stream
     * corruption.
     */
    private fun readCoordPairs(request: ByteArray, offset: Int, reader: XReader): IntArray {
        val count = ((request.size - offset) / 4) * 2
        val coords = IntArray(count)
        for (index in 0 until count) coords[index] = reader.shortSigned()
        return coords
    }

    // --- window requests ---------------------------------------------------

    private fun createWindow(opcode: Int, reader: XReader, depth: Int) {
        // depth arrives in the request's data byte, so the body starts with wid.
        val id = reader.int()
        val parent = reader.int()
        val x = reader.short(); val y = reader.short()
        val width = reader.short(); val height = reader.short()
        val borderWidth = reader.short()
        reader.short() // class
        val visual = reader.int()
        val mask = reader.int()
        val values = readWindowValues(reader, mask, opcode) ?: return
        // Redirect ownership is claimed before the window exists, as before.
        val createMask = values.eventMask
        if (createMask != null && claimRedirectOrError(id, createMask, opcode)) return
        val window = document.createWindow(id, parent, x, y, width, height, borderWidth, depth, visual)
        if (window == null) return error(X11.ERR_BAD_WINDOW, opcode, id)
        applyWindowValues(window, values)
        document.setOwner(id, this)
        notifyStructure(X11.EV_CREATE_NOTIFY, parent, id)
    }

    private fun changeWindowAttributes(opcode: Int, reader: XReader) {
        val id = reader.int()
        val mask = reader.int()
        val window = document.window(id) ?: return error(X11.ERR_BAD_WINDOW, opcode, id)
        val values = readWindowValues(reader, mask, opcode) ?: return
        val changeMask = values.eventMask
        if (changeMask != null && claimRedirectOrError(id, changeMask, opcode)) return
        applyWindowValues(window, values)
    }

    private fun getWindowAttributes(opcode: Int, reader: XReader) {
        val id = reader.int()
        val window = document.window(id) ?: return error(X11.ERR_BAD_WINDOW, opcode, id)
        // Spec layout: backing-store in the data byte; visual@8 .. colormap@28, then
        // 20 bytes of extra data (all/your event masks, do-not-propagate).
        reply(opcode, dataByte = 0 /* NotUseful */, extraLength = 20) { writer ->
            writer.int(window.visual)
            writer.short(1) // class: InputOutput
            writer.byte(0) // bitGravity
            writer.byte(1) // winGravity: NorthWest
            writer.int(-1) // backingPlanes
            writer.int(0) // backingPixel
            writer.byte(0) // saveUnder
            writer.byte(1) // mapIsInstalled
            writer.byte(if (window.mapped) 2 else 0) // mapState: IsViewable/IsUnmapped
            writer.byte(if (window.overrideRedirect) 1 else 0)
            writer.int(document.screen.defaultColormap)
            writer.int(window.eventMask) // allEventMasks
            writer.int(window.eventMask) // yourEventMask
            writer.short(window.doNotPropagateMask)
            writer.pad(10)
        }
    }

    private fun configureWindow(opcode: Int, reader: XReader) {
        val id = reader.int()
        val mask = reader.int() // value-mask @8: x, y, width, height, border in bit order
        val window = document.window(id) ?: return error(X11.ERR_BAD_WINDOW, opcode, id)
        val x = if (mask and 0x0001 != 0) reader.int() else null
        val y = if (mask and 0x0002 != 0) reader.int() else null
        val width = if (mask and 0x0004 != 0) reader.int() else null
        val height = if (mask and 0x0008 != 0) reader.int() else null
        val borderWidth = if (mask and 0x0010 != 0) reader.int() else null
        if (mask and 0x0020 != 0) reader.int() // CWSibling (stacking hints are not modelled)
        if (mask and 0x0040 != 0) reader.int() // CWStackMode
        // Under SubstructureRedirect the WM decides; nothing changes until it acts.
        if (redirectConfigure(window, mask, x, y, width, height, borderWidth)) return
        document.configure(id, x, y, width, height, borderWidth)
        configureNotify(window)
    }

    /**
     * MapRequest routing: when the parent has SubstructureRedirect selected by
     * somebody else, the map does not happen. A real WM client receives the
     * MapRequest event; the in-process shell WM is called directly.
     *
     * @return true when the request was redirected (caller must not map).
     */
    private fun redirectMap(parentId: Int, window: XWindow): Boolean {
        if (window.overrideRedirect) return false
        val parent = document.window(parentId) ?: return false
        if (parent.eventMask and EventMask.SUBSTRUCTURE_REDIRECT == 0) return false
        val owner = document.redirectOwner(parentId)
        if (owner === this) return false // a WM's own requests are never redirected
        if (owner != null) {
            owner.deliverEvent { writer ->
                writer.byte(X11.EV_MAP_REQUEST); writer.byte(0); writer.short(sequence)
                writer.int(parentId); writer.int(window.id); writer.pad(20)
            }
            return true
        }
        val wm = document.windowManager ?: return false
        wm.mapRequested(window.id)
        return true
    }

    /** ConfigureRequest routing; same rules as [redirectMap]. */
    private fun redirectConfigure(
        window: XWindow,
        mask: Int,
        x: Int?, y: Int?, width: Int?, height: Int?, borderWidth: Int?,
    ): Boolean {
        if (window.overrideRedirect) return false
        val parentId = window.parent ?: return false
        val parent = document.window(parentId) ?: return false
        if (parent.eventMask and EventMask.SUBSTRUCTURE_REDIRECT == 0) return false
        val owner = document.redirectOwner(parentId)
        if (owner === this) return false
        if (owner != null) {
            owner.deliverEvent { writer ->
                writer.byte(X11.EV_CONFIGURE_REQUEST); writer.byte(0); writer.short(sequence)
                writer.int(parentId); writer.int(window.id)
                writer.short(x ?: window.x); writer.short(y ?: window.y)
                writer.short(width ?: window.width); writer.short(height ?: window.height)
                writer.short(borderWidth ?: window.borderWidth)
                writer.short(0) // above-sibling: None
                writer.short(mask)
                writer.pad(6)
            }
            return true
        }
        val wm = document.windowManager ?: return false
        wm.configureRequested(window.id, x, y, width, height, borderWidth)
        return true
    }

    /**
     * Selecting SubstructureRedirect is exclusive (X: Access error for the second
     * client). @return true when an error was sent and the request must stop.
     */
    private fun claimRedirectOrError(windowId: Int, eventMask: Int, opcode: Int): Boolean {
        if (eventMask and EventMask.SUBSTRUCTURE_REDIRECT == 0) return false
        if (!document.claimRedirect(windowId, this)) {
            error(X11.ERR_ACCESS, opcode, windowId)
            return true
        }
        return false
    }

    private fun getGeometry(opcode: Int, reader: XReader) {
        val id = reader.int()
        val window = document.window(id)
        if (window == null && !pixmaps.containsKey(id)) return error(X11.ERR_BAD_DRAWABLE, opcode, id)
        // Depth sits in the reply's data byte; root, x, y, w, h, border start at offset 8.
        reply(opcode, dataByte = window?.depth ?: pixmaps[id] ?: 24) { writer ->
            writer.int(window?.parent ?: document.rootId)
            writer.short(window?.x ?: 0)
            writer.short(window?.y ?: 0)
            writer.short(window?.width ?: 0)
            writer.short(window?.height ?: 0)
            writer.short(window?.borderWidth ?: 0)
            writer.pad(10)
        }
    }

    private fun queryTree(opcode: Int, reader: XReader) {
        val id = reader.int()
        val window = document.window(id) ?: return error(X11.ERR_BAD_WINDOW, opcode, id)
        val children = window.children.toList()
        reply(opcode, extraLength = children.size * 4) { writer ->
            writer.int(document.rootId)
            writer.int(window.parent ?: 0) // the root has no parent
            writer.short(children.size)
            writer.pad(14)
            children.forEach { writer.int(it) }
        }
    }

    private fun internAtom(onlyIfExists: Int, reader: XReader) {
        val length = reader.short()
        reader.skip(2)
        val name = if (length in 0..512) reader.paddedString(length) else ""
        val existing = document.atomId(name)
        val atom = when {
            existing != null -> existing
            onlyIfExists != 0 -> 0
            name.isEmpty() -> 0
            else -> document.internAtom(name)
        }
        reply(X11.REQ_INTERN_ATOM) { writer -> writer.int(atom); writer.pad(20) }
    }

    private fun getAtomName(opcode: Int, reader: XReader) {
        val atom = reader.int()
        val name = document.atomName(atom) ?: return error(X11.ERR_BAD_ATOM, opcode, atom)
        val bytes = name.toByteArray(Charsets.US_ASCII)
        reply(opcode, extraLength = X11.pad4(bytes.size)) { writer ->
            writer.short(bytes.size)
            writer.pad(22)
            writer.bytes(bytes)
            writer.pad(X11.pad4(bytes.size) - bytes.size)
        }
    }

    private fun changeProperty(opcode: Int, reader: XReader, request: ByteArray) {
        // byte 1 is the mode; window@4, property@8, type@12, format@16, nItems@20, data@24.
        val window = reader.int()
        val property = reader.int()
        val type = reader.int()
        val format = reader.byte()
        reader.skip(3)
        val length = reader.int()
        val elementSize = when (format) {
            8 -> 1
            16 -> 2
            32 -> 4
            else -> return error(X11.ERR_BAD_VALUE, opcode, format)
        }
        if (document.window(window) == null) return error(X11.ERR_BAD_WINDOW, opcode, window)
        val start = 24 // data begins right after the 24-byte fixed part
        val end = (start + length * elementSize).coerceAtMost(request.size)
        val data = if (end > start) request.copyOfRange(start, end) else ByteArray(0)
        document.setProperty(window, property, type, format, data)
        notifyProperty(window, property)
    }

    private fun getProperty(opcode: Int, delete: Int, reader: XReader) {
        val window = reader.int()
        val atom = reader.int()
        reader.int() // type
        val longOffset = reader.int()
        reader.int() // long-length
        val property = document.property(window, atom)
        if (document.window(window) == null) return error(X11.ERR_BAD_WINDOW, opcode, window)
        if (delete != 0) document.deleteProperty(window, atom)
        val data = property?.data ?: ByteArray(0)
        val format = property?.format ?: 0
        val elementSize = when (format) {
            16 -> 2
            32 -> 4
            else -> 1
        }
        reply(opcode, dataByte = format, extraLength = X11.pad4(data.size)) { writer ->
            writer.int(property?.type ?: 0)
            writer.int(0) // bytes-after
            writer.int(if (format == 0) 0 else data.size / elementSize) // value-length, in elements
            writer.pad(12)
            writer.bytes(data)
            writer.pad(X11.pad4(data.size) - data.size)
        }
    }

    // --- fonts ---------------------------------------------------------------

    /**
     * QueryFont for the one built-in font. FONTABLE accepts a font id or a GC.
     * Fixed 6x13 metrics; no FONTPROPs and no per-char infos (clients fall back
     * to min/max bounds, which libX11 handles).
     */
    private fun queryFont(opcode: Int, reader: XReader) {
        val fid = reader.int()
        if (!fonts.containsKey(fid) && !gcs.containsKey(fid)) return error(X11.ERR_BAD_FONT, opcode, fid)
        reply(opcode, extraLength = 28) { writer -> writeFontInfoBody(writer) ; writer.int(0) }
    }

    /** The 44-byte FONTINFO body shared by QueryFont and ListFontsWithInfo. */
    private fun writeFontInfoBody(writer: XWriter) {
        // CHARINFO: leftSideBearing, rightSideBearing, characterWidth, ascent, descent, attributes.
        repeat(2) { // minBounds, maxBounds
            writer.short(0); writer.short(FONT_CHAR_WIDTH)
            writer.short(FONT_CHAR_WIDTH); writer.short(FONT_ASCENT)
            writer.short(FONT_DESCENT); writer.short(0)
            writer.pad(4) // walign, per the xQueryFontReply struct
        }
        writer.short(FONT_MIN_CHAR); writer.short(FONT_MAX_CHAR)
        writer.short('?'.code) // defaultChar
        writer.short(0) // nFontProps
        writer.byte(0) // drawDirection: LeftToRight
        writer.byte(0); writer.byte(0) // minByte1, maxByte1
        writer.byte(1) // allCharsExist
        writer.short(FONT_ASCENT); writer.short(FONT_DESCENT)
    }

    // --- GetImage ------------------------------------------------------------

    private fun getImage(opcode: Int, format: Int, reader: XReader) {
        val drawable = reader.int()
        val x = reader.shortSigned(); val y = reader.shortSigned()
        val width = reader.short(); val height = reader.short()
        reader.int() // plane-mask
        val window = document.window(drawable)
        val size = when {
            window != null -> intArrayOf(window.width, window.height)
            pixmaps.containsKey(drawable) -> (sink as? XDisplayList)?.sizeOf(drawable) ?: return error(X11.ERR_BAD_PIXMAP, opcode, drawable)
            else -> return error(X11.ERR_BAD_DRAWABLE, opcode, drawable)
        }
        val w = min(width, size[0] - x).coerceAtLeast(0)
        val h = min(height, size[1] - y).coerceAtLeast(0)
        val list = sink as? XDisplayList
        val pixels = list?.snapshot(drawable, size[0], size[1]) { id ->
            document.window(id)?.let { intArrayOf(it.width, it.height) } ?: list.sizeOf(id)
        } ?: IntArray(0)
        val depth = window?.depth ?: pixmaps[drawable] ?: 24
        // ZPixmap data is always byte-ordered B,G,R,X (setup declares LSBFirst
        // images), independent of the protocol byte order.
        reply(opcode, dataByte = depth, extraLength = w * h * 4) { writer ->
            writer.int(window?.visual ?: 0)
            writer.pad(20)
            for (row in 0 until h) {
                for (col in 0 until w) {
                    val index = (y + row) * size[0] + (x + col)
                    val argb = pixels.getOrElse(index) { 0 }
                    writer.byte(argb and 0xFF) // B
                    writer.byte((argb shr 8) and 0xFF) // G
                    writer.byte((argb shr 16) and 0xFF) // R
                    writer.byte((argb shr 24) and 0xFF) // A/X
                }
            }
        }
        @Suppress("UNUSED_EXPRESSION")
        format // XYBitmap/XYPixmap requests are served as ZPixmap (documented limit)
    }

    // --- PolyText ------------------------------------------------------------

    /**
     * PolyText8/PolyText16: TEXTITEM runs with font-shift (0xFF prefix) and delta
     * items. Runs are handed to the sink with the fixed-font advance; font shifts
     * are accepted and ignored (one built-in font).
     */
    private fun polyText(opcode: Int, reader: XReader, request: ByteArray, sixteen: Boolean, background: Int?) {
        val drawable = reader.int()
        val gc = reader.int()
        var x = reader.shortSigned(); val y = reader.shortSigned()
        val foreground = gcs[gc]?.foreground ?: 0xFF000000.toInt()
        var position = 16
        while (position < request.size) {
            val first = request[position].toInt() and 0xFF
            if (first == 0xFF) {
                position += 5 // FONT item: 1 + 4-byte fid
                continue
            }
            if (first == 0) {
                // Delta-only item: no characters, just an x shift.
                x += request.getOrNull(position + 1)?.toInt()?.toByte()?.toInt() ?: 0
                position += 2
                continue
            }
            val count = first
            val delta = request.getOrNull(position + 1)?.toInt()?.toByte()?.toInt() ?: 0
            x += delta
            val charsStart = position + 2
            val charBytes = count * (if (sixteen) 2 else 1)
            if (charsStart + charBytes > request.size) break
            val text = if (sixteen) {
                buildString {
                    for (i in 0 until count) {
                        val high = request[charsStart + i * 2].toInt() and 0xFF
                        val low = request[charsStart + i * 2 + 1].toInt() and 0xFF
                        append(if (high == 0 && low in 0x20..0xFF) low.toChar() else '?')
                    }
                }
            } else {
                String(request.copyOfRange(charsStart, charsStart + count), Charsets.US_ASCII)
            }
            if (text.isNotEmpty()) sink.imageText(drawable, x, y, text, foreground, background)
            x += count * FONT_CHAR_WIDTH
            position = charsStart + charBytes
        }
        @Suppress("UNUSED_EXPRESSION")
        opcode
    }

    // --- SendEvent -----------------------------------------------------------

    private fun sendEventRequest(propagateData: Int, opcode: Int, reader: XReader, request: ByteArray) {
        val propagate = propagateData != 0
        val destination = reader.int()
        val eventMask = reader.int()
        if (request.size < 44) return error(X11.ERR_BAD_LENGTH, opcode, 0)
        val event = request.copyOfRange(12, 44)
        val code = event[0].toInt() and 0x7F
        if (code < 2 || code > 34) return error(X11.ERR_BAD_VALUE, opcode, code)
        val destWindow = when (destination) {
            0 -> document.rootId
            1 -> document.windowAt(rootPointerX, rootPointerY)?.id ?: document.rootId
            2 -> resolveFocusWindow() ?: document.rootId
            else -> destination
        }
        if (document.window(destWindow) == null) return error(X11.ERR_BAD_WINDOW, opcode, destination)
        // Mark the event as SendEvent-synthesised (byte0 high bit) and restamp
        // the sequence number, as X does.
        event[0] = (event[0].toInt() or 0x80).toByte()
        event[2] = (sequence and 0xFF).toByte()
        event[3] = ((sequence shr 8) and 0xFF).toByte()
        var target = destWindow
        if (isStructureEvent(code)) {
            // Structure events honour SubstructureRedirect on the target itself or
            // its parent, exactly like spontaneous ones.
            val parent = document.window(target)?.parent ?: 0
            if (document.window(target)?.eventMask?.and(EventMask.SUBSTRUCTURE_REDIRECT) == 0 &&
                document.window(parent)?.eventMask?.and(EventMask.SUBSTRUCTURE_REDIRECT) != 0
            ) {
                target = parent
            }
        }
        var current: Int? = target
        while (current != null) {
            val window = document.window(current) ?: break
            if (window.eventMask and eventMask != 0) {
                deliverToOwner(current, event.copyOf(32))
                break
            }
            if (!propagate) break
            if (window.doNotPropagateMask and eventMask != 0) break
            current = window.parent
        }
    }

    /** Create/Destroy/Unmap/Map/MapRequest/Reparent/Configure/CirculateNotify. */
    private fun isStructureEvent(code: Int): Boolean = code in 16..22 || code == 26

    // --- XTest ----------------------------------------------------------------

    private fun xtestRequest(major: Int, minor: Int, reader: XReader, request: ByteArray) {
        when (minor) {
            0 -> { // GetVersion: client major@4 (byte), minor@6 (CARD16)
                val clientMajor = reader.byte()
                reader.skip(1)
                reader.short()
                val majorVersion = min(clientMajor, 2)
                reply(major, dataByte = majorVersion) { writer ->
                    writer.short(if (majorVersion >= 2) 1 else 0)
                    writer.pad(22)
                }
            }
            1 -> { // CompareCursor: window@4, cursor@8
                val window = reader.int()
                val cursor = reader.int()
                val target = document.window(window) ?: return error(X11.ERR_BAD_WINDOW, major, window)
                val same = if (cursor == 0) target.cursor == 0 else target.cursor == cursor
                reply(major, dataByte = if (same) 1 else 0) { it.pad(24) }
            }
            2 -> { // FakeInput: type@4, detail@5, time@8, root@12, root-x@24, root-y@26
                val type = reader.byte()
                val detail = reader.byte()
                reader.skip(2)
                reader.int() // time
                val root = reader.int()
                reader.skip(8)
                val relX = reader.shortSigned()
                val relY = reader.shortSigned()
                val base = if (root != 0) document.window(root) ?: return error(X11.ERR_BAD_WINDOW, major, root) else null
                when (type) {
                    X11.EV_KEY_PRESS, X11.EV_KEY_RELEASE -> {
                        if (root != 0 && base != null) movePointer(base.x + relX, base.y + relY)
                        val keysym = keyboard.keysymFor(detail, 0)
                        val pressed = type == X11.EV_KEY_PRESS
                        val bit = XInput.modifierBit(keysym)
                        keyStateMask = if (pressed) keyStateMask or bit else keyStateMask and bit.inv()
                        onKey(detail, keysym, pressed, keyStateMask)
                    }
                    X11.EV_BUTTON_PRESS, X11.EV_BUTTON_RELEASE -> {
                        if (root != 0 && base != null) movePointer(base.x + relX, base.y + relY)
                        val pressed = type == X11.EV_BUTTON_PRESS
                        val bit = 1 shl (7 + detail)
                        val mask = if (pressed) buttonMask or bit else buttonMask and bit.inv()
                        onButton(rootPointerX, rootPointerY, detail, pressed, mask)
                    }
                    X11.EV_MOTION_NOTIFY -> {
                        // root=None means the current root: rootX/rootY are absolute.
                        warpMove((base?.x ?: 0) + relX, (base?.y ?: 0) + relY)
                    }
                    else -> error(X11.ERR_BAD_VALUE, major, type)
                }
            }
            3 -> Unit // GrabControl: we never freeze the client
            else -> error(X11.ERR_BAD_REQUEST, major, minor)
        }
    }

    // --- replies, errors, events -------------------------------------------

    /**
     * Emits a reply: byte0 = 1, byte1 = [dataByte] (the reply-specific field the spec
     * places at offset 1), bytes2-3 = sequence, bytes4-7 = extra length in 4-byte units.
     * [body] writes verbatim at offset 8: the fixed 24 bytes first, then any extra data.
     */
    private fun reply(opcode: Int, dataByte: Int = 0, extraLength: Int = 0, body: (XWriter) -> Unit) {
        val bodyWriter = XWriter(littleEndian)
        body(bodyWriter)
        val writer = XWriter(littleEndian)
        writer.byte(1)
        writer.byte(dataByte)
        writer.short(sequence)
        writer.int(extraLength / 4)
        writer.bytes(bodyWriter.toByteArray())
        val out = writer.toByteArray()
        val aligned = if (out.size % 4 != 0) out.copyOf(X11.pad4(out.size)) else out
        pending.add(if (aligned.size >= 32) aligned else aligned.copyOf(32))
        lastError = X11.ERR_SUCCESS
        @Suppress("UNUSED_EXPRESSION")
        opcode
    }

    fun error(code: Int, opcode: Int, badValue: Int) {
        lastError = code
        val writer = XWriter(littleEndian)
        writer.byte(0)
        writer.byte(code)
        writer.short(sequence)
        writer.int(badValue)
        writer.short(0) // minorOpcode
        writer.byte(opcode)
        writer.pad(21) // 11 bytes written so far; X messages are always 32
        pending.add(writer.toByteArray().copyOf(32))
    }

    private fun eventWriter(code: Int): XWriter {
        val writer = XWriter(littleEndian)
        writer.byte(code)
        writer.byte(0) // detail
        writer.short(sequence)
        return writer
    }

    private fun queue(writer: XWriter) {
        pending.add(writer.toByteArray().copyOf(32))
    }

    /**
     * Delivers one event to [windowId] when that window selected for it. Used by the
     * window-manager side of the shell (and by tests); structure events go through
     * [notifyStructure] instead, because X sends those to the *parent's* listeners.
     */
    fun sendEvent(windowId: Int, code: Int, detailId: Int = 0) {
        val window = document.window(windowId) ?: return
        when (code) {
            X11.EV_EXPOSE -> exposeIfWanted(windowId, window.x, window.y, window.width, window.height)
            X11.EV_CONFIGURE_NOTIFY -> configureNotify(window)
            X11.EV_PROPERTY_NOTIFY -> notifyProperty(windowId, detailId)
            X11.EV_CREATE_NOTIFY, X11.EV_DESTROY_NOTIFY, X11.EV_MAP_NOTIFY,
            X11.EV_UNMAP_NOTIFY, X11.EV_REPARENT_NOTIFY -> notifyStructure(code, windowId, detailId)
            else -> if (selectsFor(window.eventMask, code)) {
                queue(eventWriter(code).int(windowId).int(detailId).pad(20))
            }
        }
        flushPending()
    }

    private fun selectsFor(mask: Int, code: Int): Boolean = when (code) {
        X11.EV_KEY_PRESS -> mask and EventMask.KEY_PRESS != 0
        X11.EV_KEY_RELEASE -> mask and EventMask.KEY_RELEASE != 0
        X11.EV_BUTTON_PRESS -> mask and EventMask.BUTTON_PRESS != 0
        X11.EV_BUTTON_RELEASE -> mask and EventMask.BUTTON_RELEASE != 0
        X11.EV_MOTION_NOTIFY -> mask and EventMask.POINTER_MOTION != 0
        X11.EV_ENTER_NOTIFY -> mask and EventMask.ENTER_WINDOW != 0
        X11.EV_LEAVE_NOTIFY -> mask and EventMask.LEAVE_WINDOW != 0
        X11.EV_FOCUS_IN, X11.EV_FOCUS_OUT -> mask and EventMask.FOCUS_CHANGE != 0
        X11.EV_EXPOSE -> mask and EventMask.EXPOSURE != 0
        X11.EV_CONFIGURE_NOTIFY -> mask and EventMask.STRUCTURE_NOTIFY != 0
        X11.EV_CREATE_NOTIFY, X11.EV_DESTROY_NOTIFY, X11.EV_MAP_NOTIFY, X11.EV_UNMAP_NOTIFY,
        X11.EV_REPARENT_NOTIFY -> mask and EventMask.SUBSTRUCTURE_NOTIFY != 0
        X11.EV_PROPERTY_NOTIFY -> mask and EventMask.PROPERTY_CHANGE != 0
        else -> false
    }

    /** Structure events (Create/Destroy/Map/Unmap/Reparent) go to the parent's listeners. */
    private fun notifyStructure(code: Int, parentId: Int, windowId: Int) {
        val parent = document.window(parentId) ?: return
        val wanted = EventMask.STRUCTURE_NOTIFY or EventMask.SUBSTRUCTURE_NOTIFY
        if (parent.eventMask and wanted == 0) return
        val window = document.window(windowId)
        val overrideRedirect = if (window?.overrideRedirect == true) 1 else 0
        val writer = eventWriter(code)
        when (code) {
            X11.EV_REPARENT_NOTIFY -> {
                writer.int(parentId); writer.int(windowId); writer.int(parentId)
                writer.short(window?.x ?: 0); writer.short(window?.y ?: 0)
                writer.byte(overrideRedirect); writer.pad(11)
            }
            X11.EV_MAP_NOTIFY, X11.EV_UNMAP_NOTIFY -> {
                writer.int(parentId); writer.int(windowId)
                writer.byte(overrideRedirect); writer.pad(19)
            }
            else -> { // Create/Destroy carry the geometry
                writer.int(parentId); writer.int(windowId)
                writer.short(window?.x ?: 0); writer.short(window?.y ?: 0)
                writer.short(window?.width ?: 0); writer.short(window?.height ?: 0)
                writer.short(window?.borderWidth ?: 0)
                writer.byte(overrideRedirect)
                writer.pad(9)
            }
        }
        queue(writer)
    }

    private fun configureNotify(window: XWindow) {
        if (window.eventMask and EventMask.STRUCTURE_NOTIFY == 0) return
        val writer = eventWriter(X11.EV_CONFIGURE_NOTIFY)
        writer.int(window.id); writer.int(window.id); writer.int(0) // above-sibling: None
        writer.short(window.x); writer.short(window.y)
        writer.short(window.width); writer.short(window.height)
        writer.short(window.borderWidth); writer.short(0); writer.pad(10)
        queue(writer)
    }

    private fun notifyProperty(windowId: Int, atom: Int) {
        val window = document.window(windowId) ?: return
        if (window.eventMask and EventMask.PROPERTY_CHANGE == 0) return
        val writer = eventWriter(X11.EV_PROPERTY_NOTIFY)
        writer.int(windowId); writer.int(atom); writer.byte(0); writer.pad(3); writer.int(0); writer.pad(12)
        queue(writer)
    }

    private fun exposeIfWanted(windowId: Int, x: Int, y: Int, width: Int, height: Int) {
        val window = document.window(windowId) ?: return
        if (window.eventMask and EventMask.EXPOSURE == 0) return
        val writer = eventWriter(X11.EV_EXPOSE)
        writer.int(windowId)
        writer.short(x); writer.short(y); writer.short(width); writer.short(height)
        writer.short(0); writer.pad(14)
        queue(writer)
    }

    /** FocusIn/FocusOut pair when SetInputFocus moves the focus. */
    private fun sendFocusChange(oldFocus: Int, newFocus: Int) {
        fun detailFor(from: Int, to: Int): Int = when {
            from == to -> 7 // NotifyDetailNone
            from == XDocument.FOCUS_POINTER_ROOT || to == XDocument.FOCUS_POINTER_ROOT -> 6
            from == XDocument.FOCUS_NONE || to == XDocument.FOCUS_NONE -> 5
            else -> 3 // NotifyNonlinear
        }

        fun focusEvent(code: Int, windowId: Int, detail: Int): ByteArray {
            val writer = XWriter(littleEndian)
            writer.byte(code)
            writer.byte(detail)
            writer.short(sequence)
            writer.int(System.currentTimeMillis().toInt()) // time
            writer.int(windowId) // event window
            writer.byte(0) // mode: NotifyNormal
            writer.byte(1) // sameScreenFocus
            writer.pad(18)
            return writer.toByteArray().copyOf(32)
        }

        if (oldFocus != XDocument.FOCUS_NONE && oldFocus != XDocument.FOCUS_POINTER_ROOT && oldFocus != newFocus) {
            deliverToOwner(oldFocus, focusEvent(X11.EV_FOCUS_OUT, oldFocus, detailFor(oldFocus, newFocus)))
        }
        if (newFocus != XDocument.FOCUS_NONE && newFocus != XDocument.FOCUS_POINTER_ROOT) {
            deliverToOwner(newFocus, focusEvent(X11.EV_FOCUS_IN, newFocus, detailFor(oldFocus, newFocus)))
        }
    }

    /** The window keyboard events go to right now (focus or pointer-root semantics). */
    private fun resolveFocusWindow(): Int? = when (val focus = document.focus) {
        XDocument.FOCUS_NONE -> null
        XDocument.FOCUS_POINTER_ROOT ->
            document.windowAt(rootPointerX, rootPointerY)?.id ?: document.rootId
        else -> if (document.window(focus) != null) focus else null
    }

    // --- input -------------------------------------------------------------

    /**
     * A server-side pointer move (WarpPointer, XTest FakeInput): clamps to any
     * confine-to, tracks Enter/Leave, generates the MotionNotify X promises for
     * warps, and tells the server to sync the other connections.
     */
    private fun warpMove(x: Int, y: Int) {
        movePointer(x, y)
        val target = document.windowAt(rootPointerX, rootPointerY)
        if (target != null && target.eventMask and EventMask.POINTER_MOTION != 0) {
            queue(pointerEvent(X11.EV_MOTION_NOTIFY, 0, target, rootPointerX, rootPointerY))
        }
        flushPending()
        document.onWarp?.invoke(rootPointerX, rootPointerY, this)
    }

    /** Shared pointer move: confine-to-grab clamping, Enter/Leave tracking, MotionNotify. */
    private fun movePointer(x: Int, y: Int): Pair<Int, Int> {
        var nx = x
        var ny = y
        document.pointerGrab?.takeIf { it.connection === this }?.let { grab ->
            if (grab.confineTo != 0) {
                document.window(grab.confineTo)?.let {
                    nx = nx.coerceIn(it.x, maxOf(it.x, it.right - 1))
                    ny = ny.coerceIn(it.y, maxOf(it.y, it.bottom - 1))
                }
            }
        }
        rootPointerX = nx
        rootPointerY = ny
        updateEnterLeave(nx, ny)
        return nx to ny
    }

    /** EnterNotify/LeaveNotify when the pointer crosses a window boundary. */
    private fun updateEnterLeave(x: Int, y: Int) {
        val current = document.windowAt(x, y)?.id
        val previous = lastPointerWindow
        if (current == previous) return
        if (previous != null && previous != current) {
            val window = document.window(previous)
            if (window != null && window.eventMask and EventMask.LEAVE_WINDOW != 0) {
                queue(enterLeaveEvent(X11.EV_LEAVE_NOTIFY, 3, window, 0, x, y))
            }
        }
        if (current != null) {
            val window = document.window(current)
            if (window != null && window.eventMask and EventMask.ENTER_WINDOW != 0) {
                queue(enterLeaveEvent(X11.EV_ENTER_NOTIFY, 3, window, 0, x, y))
            }
        }
        lastPointerWindow = current
        flushPending()
    }

    private fun enterLeaveEvent(code: Int, detail: Int, target: XWindow, child: Int, x: Int, y: Int): XWriter {
        // xEnterLeaveEvent: time@4, event@8, child@12, root-x@16, root-y@18,
        // event-x@20, event-y@22, state@28, mode@30, same-screen-focus@31.
        val writer = XWriter(littleEndian)
        writer.byte(code)
        writer.byte(detail)
        writer.short(sequence)
        writer.int(System.currentTimeMillis().toInt())
        writer.int(target.id)
        writer.int(child)
        writer.short(x)
        writer.short(y)
        writer.short(x - target.x)
        writer.short(y - target.y)
        writer.short(buttonMask or keyStateMask)
        writer.byte(0) // mode: NotifyNormal
        writer.byte(1) // sameScreenFocus
        return writer
    }

    fun onPointerMove(x: Int, y: Int) {
        val grab = document.pointerGrab
        if (grab != null && grab.connection !== this) return // events flow to the grab owner only
        val (nx, ny) = movePointer(x, y)
        val under = document.windowAt(nx, ny)
        if (grab != null) {
            val target = document.window(grab.window) ?: return
            val child = under?.takeIf { it.id != grab.window && isDescendantOf(it.id, grab.window) }?.id ?: 0
            if (grab.eventMask and EventMask.POINTER_MOTION != 0) {
                queue(pointerEvent(X11.EV_MOTION_NOTIFY, 0, target, nx, ny, child))
            }
            if (grab.ownerEvents && under != null && under.id != grab.window &&
                under.eventMask and EventMask.POINTER_MOTION != 0
            ) {
                queue(pointerEvent(X11.EV_MOTION_NOTIFY, 0, under, nx, ny))
            }
            flushPending()
            return
        }
        val target = under ?: return
        if (target.eventMask and EventMask.POINTER_MOTION == 0) return
        queue(pointerEvent(X11.EV_MOTION_NOTIFY, 0, target, nx, ny))
        flushPending()
    }

    fun onButton(x: Int, y: Int, button: Int, pressed: Boolean, maskAfter: Int) {
        val grab = document.pointerGrab
        if (grab != null && grab.connection !== this) return
        val (nx, ny) = movePointer(x, y)
        buttonMask = maskAfter
        val under = document.windowAt(nx, ny)
        if (grab != null) {
            val target = document.window(grab.window) ?: return
            val code = if (pressed) X11.EV_BUTTON_PRESS else X11.EV_BUTTON_RELEASE
            val wanted = if (pressed) EventMask.BUTTON_PRESS else EventMask.BUTTON_RELEASE
            val child = under?.takeIf { it.id != grab.window && isDescendantOf(it.id, grab.window) }?.id ?: 0
            if (grab.eventMask and wanted != 0) {
                queue(pointerEvent(code, button, target, nx, ny, child))
            }
            if (grab.ownerEvents && under != null && under.id != grab.window && under.eventMask and wanted != 0) {
                queue(pointerEvent(code, button, under, nx, ny))
            }
            flushPending()
            if (!pressed) document.ungrabImplicitPointer(this)
            return
        }
        // A press may activate a passive grab (XGrabButton): the press and
        // everything until the release goes to the grabbing window. Cross-client
        // passive activation is not modelled (single-app server); grabs by other
        // connections stay recorded but inert.
        if (pressed && under != null) {
            val passive = document.passiveGrabFor(under.id, button, keyStateMask, isKey = false)
            if (passive != null && passive.connection === this && passive.eventMask and EventMask.BUTTON_PRESS != 0) {
                document.grabPointer(
                    PointerGrab(passive.window, passive.ownerEvents, passive.eventMask,
                        passive.confineTo, passive.cursor, this, implicit = true),
                )
                val target = document.window(passive.window) ?: return
                queue(pointerEvent(X11.EV_BUTTON_PRESS, button, target, nx, ny))
                flushPending()
                return
            }
        }
        val target = under ?: return
        val code = if (pressed) X11.EV_BUTTON_PRESS else X11.EV_BUTTON_RELEASE
        if (pressed && target.eventMask and EventMask.BUTTON_PRESS == 0) return
        if (!pressed && target.eventMask and EventMask.BUTTON_RELEASE == 0) return
        queue(pointerEvent(code, button, target, nx, ny))
        flushPending()
    }

    fun onKey(keycode: Int, keysym: Int, pressed: Boolean, maskAfter: Int) {
        val grab = document.keyboardGrab
        if (grab != null && grab.connection !== this) return
        keyStateMask = maskAfter
        document.noteKey(keycode, pressed)
        // A matching passive key grab (XGrabKey) activates into an implicit
        // keyboard grab that lasts until the release (same-connection only).
        if (pressed && grab == null && maybeActivateKeyGrab(keycode)) return
        val focusTarget = resolveFocusWindow()
        val targetId = when {
            grab != null -> grab.window
            focusTarget == null -> return
            else -> focusTarget
        }
        val target = document.window(targetId) ?: return
        val code = if (pressed) X11.EV_KEY_PRESS else X11.EV_KEY_RELEASE
        val wanted = if (pressed) EventMask.KEY_PRESS else EventMask.KEY_RELEASE
        if (target.eventMask and wanted != 0) {
            queue(pointerEvent(code, keycode, target, rootPointerX, rootPointerY))
        }
        if (grab?.ownerEvents == true && focusTarget != null && focusTarget != grab.window) {
            val focusWindow = document.window(focusTarget)
            if (focusWindow != null && focusWindow.eventMask and wanted != 0) {
                queue(pointerEvent(code, keycode, focusWindow, rootPointerX, rootPointerY))
            }
        }
        flushPending()
        if (!pressed) document.ungrabImplicitKeyboard(this)
        // A passive key grab would activate here; keyboard passive grabs are
        // recorded but not enforced (see docs/X_SERVER.md - Wine uses active grabs).
        @Suppress("UNUSED_EXPRESSION")
        keysym
    }

    /** Passive key-grab activation on press (same-connection only, like buttons). */
    private fun maybeActivateKeyGrab(keycode: Int): Boolean {
        val target = resolveFocusWindow()?.let { document.window(it) } ?: return false
        val passive = document.passiveGrabFor(target.id, keycode, keyStateMask, isKey = true) ?: return false
        if (passive.connection !== this) return false
        document.grabKeyboard(KeyboardGrab(passive.window, passive.ownerEvents, this, implicit = true))
        val grabWindow = document.window(passive.window) ?: return false
        queue(pointerEvent(X11.EV_KEY_PRESS, keycode, grabWindow, rootPointerX, rootPointerY))
        flushPending()
        return true
    }

    private fun isDescendantOf(windowId: Int, ancestorId: Int): Boolean {
        var current = document.window(windowId)?.parent
        while (current != null) {
            if (current == ancestorId) return true
            current = document.window(current)?.parent
        }
        return false
    }

    private fun pointerEvent(code: Int, detail: Int, target: XWindow, x: Int, y: Int, child: Int = 0): XWriter {
        // Spec order: time@4, root@8, event@12, child@16, root-x@20, root-y@22,
        // event-x@24, event-y@26, state@28, same-screen@30.
        val writer = XWriter(littleEndian)
        writer.byte(code)
        writer.byte(detail)
        writer.short(sequence)
        writer.int(System.currentTimeMillis().toInt())
        writer.int(document.rootId)
        writer.int(target.id)
        writer.int(child)
        writer.short(rootPointerX)
        writer.short(rootPointerY)
        writer.short(x - target.x)
        writer.short(y - target.y)
        writer.short(buttonMask or keyStateMask)
        writer.byte(1) // sameScreenAndFocus
        writer.pad(1)
        return writer
    }

    /** Sends WM_DELETE_WINDOW to a top-level window (what a window manager does on close). */
    fun sendDeleteWindow(windowId: Int) {
        document.window(windowId) ?: return
        val protocols = document.atomId("WM_PROTOCOLS") ?: return
        val delete = document.internAtom("WM_DELETE_WINDOW")
        // ClientMessage: format in byte 1, window@4, type@8, 20 data bytes@12.
        val writer = XWriter(littleEndian)
        writer.byte(X11.EV_CLIENT_MESSAGE)
        writer.byte(32) // format
        writer.short(sequence)
        writer.int(windowId)
        writer.int(protocols)
        writer.int(delete)
        writer.pad(16)
        queue(writer)
        flushPending()
    }

    companion object {
        /** The one built-in font: fixed 6x13 metrics, monospace advance. */
        const val FONT_NAME = "fixed"
        const val FONT_CHAR_WIDTH = 6
        const val FONT_ASCENT = 11
        const val FONT_DESCENT = 2
        const val FONT_MIN_CHAR = 0x20
        const val FONT_MAX_CHAR = 0x7E

        /** name -> [majorOpcode, firstEvent, firstError]; QueryExtension answers from here. */
        val EXTENSIONS: Map<String, IntArray> = mapOf(
            "XTEST" to intArrayOf(X11.EXT_XTEST_OPCODE, 0, 0),
        )
    }
}
