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

    /**
     * What this client asked for and what we could not answer. Read by [XServer]
     * when the connection ends (it forwards the summary to the shell log) and by
     * the Diagnostics screen; see [XProtocolTrace].
     */
    val trace = XProtocolTrace()

    private val gcs = HashMap<Int, GcState>()
    private val pixmaps = HashMap<Int, Int>()

    /** RENDER pictures and glyph sets are per-connection resources, like GCs. */
    private val pictures = HashMap<Int, PictureState>()

    /** XFIXES regions are per-connection resources too (D1f). */
    private val regions = HashMap<Int, XRegion>()
    private val glyphSets = HashMap<Int, XRender.GlyphStore>()

    private class PictureState(
        val drawable: Int,
        val formatId: Int,
        /** ARGB of a solid-fill picture; -1 for drawable-backed pictures. */
        val solidColor: Int = -1,
    ) {
        var repeat: Int = XRender.REPEAT_NONE
        var componentAlpha: Boolean = false
        var clipXOrigin: Int = 0
        var clipYOrigin: Int = 0
        var clipMask: Int = 0 // pixmap id or 0
        var clipRects: List<IntArray>? = null // absolute x,y,w,h in dst space
    }
    private val fonts = HashMap<Int, String>()
    private val colormaps = HashSet<Int>()
    private var screenSaver = intArrayOf(600, 600, 1, 1) // timeout, interval, preferBlanking, allowExposures

    private class GcState(
        var foreground: Int = 0xFF000000.toInt(),
        var background: Int? = null,
        var font: Int = 0,
    ) {
        /**
         * XFIXES SetGCClipRegion (D1f): the translated region rects. Stored and
         * round-tripped through CreateRegionFromGC; core drawing does not apply
         * GC clips - the same documented limit the core SetClipRectangles has.
         */
        var clipRegionRects: List<IntArray>? = null
    }

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
        values.cursor?.let {
            if (window.cursor != it) {
                window.cursor = it
                document.fireCursorNotify(window.id)
            }
        }
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
     * Frees every resource this connection allocated, mirroring what a real X server
     * does on disconnect: per-connection GCs/pixmaps/fonts/colormaps are dropped and
     * the connection's windows are destroyed in the shared [XDocument].
     *
     * @return drawable ids (pixmaps + destroyed windows) the caller should purge from
     *   the display list so their rasterised ops do not leak across connections.
     */
    fun releaseResources(): List<Int> {
        val purged = pixmaps.keys.toList()
        gcs.clear()
        pixmaps.clear()
        fonts.clear()
        colormaps.clear()
        regions.clear()
        document.dropWatchesOf(this)
        document.dropSyncOf(this)
        return purged + document.destroyWindowsOwnedBy(this)
    }

    /**
     * Set by XServer on its internal shell connection: inspects every event
     * delivered from another connection (the clipboard bridge waits for the
     * SelectionNotify answering its own ConvertSelection). Runs guarded - an
     * interceptor failure must never break event delivery.
     */
    var eventInterceptor: ((ByteArray) -> Unit)? = null

    /**
     * Queues a raw message from another thread (WM-driven events, redirected
     * requests) and asks the server to push it to this connection's socket now;
     * an idle client would otherwise not see it until its next request.
     */
    fun deliver(message: ByteArray) {
        runCatching { eventInterceptor?.invoke(message) }
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
     * Delivers a **GenericEvent** (xorgproto's `xGenericEvent`): the transport
     * every extension event that does not fit in 32 bytes uses, XInput2's device
     * events above all.
     *
     * Layout: byte 0 = 35 (`GenericEvent`), byte 1 = [extensionOpcode] (the major
     * opcode the client got from QueryExtension, which is how it knows whose event
     * this is), bytes 2-3 = sequence, bytes 4-7 = length in 4-byte units **after**
     * the first 32 bytes, bytes 8-9 = [evtype] (the extension's own event number),
     * and [body] writes from byte 10. libX11 reads that header itself and hands the
     * rest to the extension (`XGetEventData`), so nothing has to be negotiated.
     *
     * `internal` so the framing is unit-testable on its own; XI2/XFIXES events go
     * through here.
     */
    internal fun deliverGenericEvent(extensionOpcode: Int, evtype: Int, body: (XWriter) -> Unit) {
        val bodyWriter = XWriter(littleEndian)
        body(bodyWriter)
        val payload = bodyWriter.toByteArray()
        val total = maxOf(XGenericEvent.HEADER_BYTES, X11.pad4(10 + payload.size))
        val writer = XWriter(littleEndian)
        writer.byte(XGenericEvent.EVENT_TYPE)
        writer.byte(extensionOpcode)
        writer.short(sequence)
        writer.int((total - XGenericEvent.HEADER_BYTES) / 4)
        writer.short(evtype)
        writer.bytes(payload)
        writer.pad(total - 10 - payload.size)
        deliver(writer.toByteArray())
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
        // X11 setup byte 0 selects byte order: 0x42 'B' = MSBFirst (big-endian),
        // 0x6c 'l' = LSBFirst (little-endian). Every real ARM/x86 client sends 'l'.
        val little = when (bytes[0].toInt().toChar()) {
            'B' -> false
            'l' -> true
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
        // Maximum request length in 4-byte units. libX11 stores this verbatim and
        // uses it to size its buffers, so 0 would break every client; 65535 (256 KiB)
        // is the protocol maximum and what a real server advertises.
        writer.short(65535)
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

    /**
     * True once this client sent BIG-REQUESTS' `Enable`; only then may it use the
     * extended (CARD16 length = 0, CARD32 length at offset 4) request form.
     */
    var bigRequestsEnabled: Boolean = false
        private set

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
            val request: ByteArray
            if (units == XBigRequests.BIG_REQUEST_MARKER) {
                // A CARD16 length of 0 is the BIG-REQUESTS marker: the real length
                // is the CARD32 at offset 4 and the body starts at offset 8
                // (xBigReq). Without the extension enabled this is a protocol
                // violation, and parsing on would read one request as many - so the
                // stream stops here, BadLength is sent, and the trace says why.
                if (!bigRequestsEnabled) {
                    trace.recordFailure(
                        carry[0].toInt() and 0xFF,
                        "big request (CARD16 length 0) without BIG-REQUESTS Enable - stream stopped",
                    )
                    lastFailure = "big request without BIG-REQUESTS enabled"
                    handleRequest(carry.copyOf(4), lengthError = true)
                    carry = ByteArray(0)
                    return handled
                }
                if (carry.size < XBigRequests.HEADER_BYTES) break
                val bigUnits = if (littleEndian) {
                    (carry[4].toInt() and 0xFF) or ((carry[5].toInt() and 0xFF) shl 8) or
                        ((carry[6].toInt() and 0xFF) shl 16) or ((carry[7].toInt() and 0xFF) shl 24)
                } else {
                    ((carry[4].toInt() and 0xFF) shl 24) or ((carry[5].toInt() and 0xFF) shl 16) or
                        ((carry[6].toInt() and 0xFF) shl 8) or (carry[7].toInt() and 0xFF)
                }
                if (bigUnits > XBigRequests.MAX_REQUEST_UNITS) {
                    trace.recordFailure(
                        carry[0].toInt() and 0xFF,
                        "big request of $bigUnits units exceeds the advertised maximum " +
                            "(${XBigRequests.MAX_REQUEST_UNITS}) - stream stopped",
                    )
                    lastFailure = "big request too large: $bigUnits units"
                    handleRequest(carry.copyOf(XBigRequests.HEADER_BYTES), lengthError = true)
                    carry = ByteArray(0)
                    return handled
                }
                val total = bigUnits * 4
                if (total > carry.size) break
                // Normalised to the ordinary 4-byte header form: every request
                // handler below addresses its payload from absolute offsets
                // (ChangeProperty's data at 24, SendEvent's event at 12, ...), so
                // one array shape keeps them all correct for big and small
                // requests alike. No handler reads the CARD16 length field.
                val normalised = ByteArray(4 + total - XBigRequests.HEADER_BYTES)
                normalised[0] = carry[0]
                normalised[1] = carry[1]
                carry.copyInto(normalised, 4, XBigRequests.HEADER_BYTES, total)
                carry = carry.copyOfRange(total, carry.size)
                request = normalised
            } else {
                val requestBytes = units * 4
                if (requestBytes > carry.size) break
                request = carry.copyOf(requestBytes)
                carry = carry.copyOfRange(requestBytes, carry.size)
            }
            handled++
            handleRequest(request)
        }
        return handled
    }

    /** Last dispatch failure, for tests and the Diagnostics screen. */
    var lastFailure: String? = null
        private set

    private fun handleRequest(request: ByteArray, lengthError: Boolean = false) {
        sequence = (sequence + 1) and 0xFFFF
        val opcode = request[0].toInt() and 0xFF
        val data = request[1].toInt() and 0xFF
        if (lengthError) {
            error(X11.ERR_BAD_LENGTH, opcode, 0)
            flushPending()
            return
        }
        try {
            lastFailure = null
            trace.recordRequest()
            dispatch(opcode, data, XReader(request, littleEndian, 4), request)
        } catch (error: Exception) {
            lastFailure = "opcode $opcode: ${error.javaClass.simpleName}: ${error.message}"
            trace.recordFailure(opcode, "${error.javaClass.simpleName}: ${error.message}")
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
                    xi2Selections.forget(id)
                    if (xi2Grab?.window == id) xi2Grab = null
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
                val newX = reader.shortSigned()
                val newY = reader.shortSigned()
                val window = document.window(id)
                val target = document.window(newParent)
                if (window == null || target == null) return error(X11.ERR_BAD_WINDOW, opcode, id)
                window.parent?.let { document.window(it)?.children?.remove(id) }
                window.parent = newParent
                // ReparentWindow PLACES the window at (x,y) inside its new parent
                // (X spec) - this is how a WM frame puts the client at
                // (border, border+title). Dropping the offsets (the old
                // behaviour) misplaces every framed window.
                window.x = newX
                window.y = newY
                target.children.add(id)
                document.noteStructureChange()
                notifyStructure(X11.EV_REPARENT_NOTIFY, newParent, id)
                // The parent/position change is a configuration change the client
                // must hear about (a WM's frame flow waits on this ConfigureNotify).
                configureNotify(window)
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
                    // Becoming VIEWABLE generates Expose (X semantics). Without it a
                    // GTK3 client that has not drawn yet waits on a black window -
                    // the pass-through desktop session maps through here (openbox's
                    // MapWindow, or an unmanaged client's), so this is the
                    // first-paint trigger the in-process wmMapWindow path already had.
                    if (document.isViewable(target)) exposeSubtree(target)
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
                // owner@4 (None clears), selection@8, time@12.
                val owner = reader.int()
                val selection = reader.int()
                reader.int() // time
                if (owner != 0 && document.window(owner) == null) {
                    return error(X11.ERR_BAD_WINDOW, opcode, owner)
                }
                // While the Android bridge is attached, a released text selection
                // falls back to the shell (the system clipboard still holds the
                // text), so guest pastes keep working - honest X would say None.
                val effective = if (owner == 0 && document.clipboardTextProvider != null) {
                    document.setSelectionOwner(selection, XDocument.SHELL_WINDOW_ID, null)
                } else {
                    document.setSelectionOwner(selection, owner, this)
                }
                // A client claim may trigger the shell bridge to fetch the new
                // text (guest copy -> Android clipboard); reclaims stay silent.
                if (effective != null && effective.connection === this) {
                    document.onSelectionOwnerChanged?.invoke(selection, effective)
                }
            }
            X11.REQ_GET_SELECTION_OWNER -> {
                val selection = reader.int()
                reply(opcode) { writer ->
                    writer.int(document.selectionOwner(selection)?.window ?: 0)
                    writer.pad(20)
                }
            }
            X11.REQ_CONVERT_SELECTION -> convertSelection(opcode, reader)
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
                val srcOrigin = document.absoluteOrigin(srcWindow)
                val dstOrigin = document.absoluteOrigin(dstWindow)
                val rootX = srcOrigin[0] + srcX
                val rootY = srcOrigin[1] + srcY
                reply(opcode, dataByte = 1 /* sameScreen */) { writer ->
                    writer.int(0) // child
                    writer.short(rootX - dstOrigin[0])
                    writer.short(rootY - dstOrigin[1])
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
                warpPointer(src, dst, srcX, srcY, srcW, srcH, dstX, dstY, opcode)
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
                        // Same rule as ListExtensions: the length byte and the name
                        // are padded as ONE entry (Xorg: buffer += pad_to_int32(len+1)
                        // - 1). Padding the name alone makes "fixed" 9 bytes instead
                        // of 8, so the reply outruns its length field by one byte.
                        val raw = name.toByteArray(Charsets.US_ASCII)
                        writer.byte(raw.size)
                        writer.bytes(raw)
                        writer.pad(X11.padded(raw.size + 1))
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
                // Wire layout: depth is byte 1 (the `data` field), then pid(4)
                // drawable(4) width(2) height(2) = 16 bytes total. Reading depth
                // from the stream instead would run one short past the request.
                val depth = data
                val id = reader.int()
                reader.int() // drawable
                val width = reader.short()
                val height = reader.short()
                pixmaps[id] = depth
                sink.setSize(id, width, height, depth)
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
                val ext = extension(name)
                trace.recordExtension(name.uppercase(), ext != null)
                // xQueryExtensionReply: `present` is the reply's data byte (byte 1);
                // major-opcode, first-event and first-error are the first three bytes
                // of the 24-byte payload. Writing `present` into the payload instead
                // tells the client the extension is absent even though
                // ListExtensions advertises it, so XTest was never reachable.
                reply(opcode, dataByte = if (ext != null) 1 else 0) { writer ->
                    writer.byte(ext?.get(0) ?: 0) // major opcode
                    writer.byte(ext?.get(1) ?: 0) // first event
                    writer.byte(ext?.get(2) ?: 0) // first error
                    writer.pad(21)
                }
            }
            X11.REQ_LIST_EXTENSIONS -> {
                val names = EXTENSIONS.keys.toList()
                val bytes = names.sumOf { X11.pad4(it.length + 1) }
                reply(opcode, dataByte = names.size, extraLength = bytes) { writer ->
                    writer.pad(24)
                    names.forEach { name ->
                        // Each entry is a length byte plus the name, and the WHOLE
                        // entry is padded to a 4-byte boundary (Xorg:
                        // pad_to_int32(nameLen + 1)). XWriter.paddedString pads the
                        // name alone, which writes one byte more than `bytes`
                        // declares for a 5-character name like "XTEST". The reply is
                        // then a byte longer than its length field says, so every
                        // later message is parsed one byte off: the client sees the
                        // tail of one reply as the head of the next and GTK treats
                        // the resulting nonsense as a fatal X error (_exit(1)).
                        val raw = name.toByteArray(Charsets.US_ASCII)
                        writer.byte(raw.size)
                        writer.bytes(raw)
                        writer.pad(X11.padded(raw.size + 1))
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
            X11.REQ_GET_POINTER_MAPPING -> reply(opcode, dataByte = 3, extraLength = 4) { writer ->
                writer.pad(24)
                // The 3-button map is the reply's extra data; like every STRING8-ish
                // tail it is padded to a 4-byte boundary, so it occupies 4 bytes and
                // the length field must say so. Declaring 0 here made the reply 4
                // bytes longer than advertised and desynchronised the connection.
                writer.byte(1); writer.byte(2); writer.byte(3); writer.pad(1)
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
            XRender.MAJOR_OPCODE -> renderRequest(opcode, data, reader, request)
            XInput2.MAJOR_OPCODE -> xi2Request(opcode, data, reader, request)
            XFixes.MAJOR_OPCODE -> xfixesRequest(opcode, data, reader, request)
            XSync.MAJOR_OPCODE -> xsyncRequest(opcode, data, reader, request)
            XBigRequests.MAJOR_OPCODE -> when (data) {
                // The only request BIG-REQUESTS has: from here on this client may
                // use the CARD32 length form (see onBytes).
                XBigRequests.MINOR_ENABLE -> {
                    bigRequestsEnabled = true
                    // xBigReqEnableReply is 32 bytes: max_request_size at offset 8
                    // and five CARD32 pads. No extra data, so the length field is 0.
                    reply(opcode) { writer ->
                        writer.int(XBigRequests.MAX_REQUEST_UNITS)
                        writer.pad(20)
                    }
                }
                else -> {
                    trace.recordUnimplemented(opcode, data)
                    error(X11.ERR_BAD_REQUEST, opcode, 0, minor = data)
                }
            }
            XGenericEvent.MAJOR_OPCODE -> when (data) {
                XGenericEvent.MINOR_QUERY_VERSION -> reply(opcode, dataByte = data) { writer ->
                    writer.short(XGenericEvent.VERSION_MAJOR)
                    writer.short(XGenericEvent.VERSION_MINOR)
                    writer.pad(20)
                }
                else -> {
                    trace.recordUnimplemented(opcode, data)
                    error(X11.ERR_BAD_REQUEST, opcode, 0, minor = data)
                }
            }
            else -> {
                // BadRequest is the correct protocol answer, and it is also what
                // kills a GTK client (its error handler calls _exit(1)), so the
                // opcode has to end up in the shell log: this is the list of
                // requests to implement next.
                trace.recordUnimplemented(opcode)
                error(X11.ERR_BAD_REQUEST, opcode, 0)
            }
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

    // --- selections ----------------------------------------------------------

    /**
     * ConvertSelection: requestor@4, selection@8, target@12, property@16, time@20.
     * Three cases, exactly like X:
     * - a client owns the selection: route a SelectionRequest to the owner, which
     *   answers with ChangeProperty + SendEvent(SelectionNotify) (both work here);
     * - the shell bridge owns it: answer inline from [XDocument.clipboardTextProvider];
     * - nobody owns it: SelectionNotify with property None.
     */
    private fun convertSelection(opcode: Int, reader: XReader) {
        val requestor = reader.int()
        val selection = reader.int()
        val target = reader.int()
        val property = reader.int()
        val time = reader.int()
        if (document.window(requestor) == null) return error(X11.ERR_BAD_WINDOW, opcode, requestor)
        val owner = document.selectionOwner(selection)
        when {
            owner == null -> sendSelectionNotify(requestor, selection, target, 0, time)
            // Shell-owned (or a null-connection owner): answer inline; with no
            // provider attached this degrades to an honest None conversion.
            owner.connection == null -> shellConvert(requestor, selection, target, property, time)
            else -> owner.connection.deliverEvent { writer ->
                writer.byte(X11.EV_SELECTION_REQUEST); writer.byte(0); writer.short(sequence)
                writer.int(time); writer.int(requestor); writer.int(selection)
                writer.int(target); writer.int(property); writer.pad(8)
            }
        }
    }

    /** The shell answers a conversion from the Android clipboard, inline. */
    private fun shellConvert(requestor: Int, selection: Int, target: Int, property: Int, time: Int) {
        val propName = if (property != 0) property else target
        val targetsAtom = document.atomId("TARGETS")
        val data: ByteArray?
        val format: Int
        if (target == targetsAtom) {
            val writer = XWriter(littleEndian)
            SelectionCodec.TEXT_TARGETS.forEach { name -> writer.int(document.internAtom(name)) }
            data = writer.toByteArray()
            format = 32
        } else {
            data = document.clipboardTextProvider?.invoke(target)
            format = 8
        }
        if (data == null) {
            sendSelectionNotify(requestor, selection, target, 0, time)
            return
        }
        document.setProperty(requestor, propName, target, format, data)
        sendSelectionNotify(requestor, selection, target, propName, time)
    }

    /** SelectionNotify to the requestor's connection (property 0 = refused/None). */
    private fun sendSelectionNotify(requestor: Int, selection: Int, target: Int, property: Int, time: Int) {
        val writer = XWriter(littleEndian)
        writer.byte(X11.EV_SELECTION_NOTIFY)
        writer.byte(0)
        writer.short(sequence)
        writer.int(time)
        writer.int(requestor)
        writer.int(selection)
        writer.int(target)
        writer.int(property)
        writer.pad(8)
        deliverToOwner(requestor, writer.toByteArray().copyOf(32))
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
            // An empty event-mask delivers to the destination unconditionally:
            // SelectionNotify and ClientMessage have no selectable mask, and the
            // ICCCM idiom is XSendEvent(dest, False, 0, ev) - every real server
            // (and every selection/WM flow) depends on it.
            if (eventMask == 0 || window.eventMask and eventMask != 0) {
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
                        if (root != 0 && base != null) {
                            val keyBase = document.absoluteOrigin(base)
                            movePointer(keyBase[0] + relX, keyBase[1] + relY)
                        }
                        val keysym = keyboard.keysymFor(detail, 0)
                        val pressed = type == X11.EV_KEY_PRESS
                        val bit = XInput.modifierBit(keysym)
                        keyStateMask = if (pressed) keyStateMask or bit else keyStateMask and bit.inv()
                        onKey(detail, keysym, pressed, keyStateMask)
                    }
                    X11.EV_BUTTON_PRESS, X11.EV_BUTTON_RELEASE -> {
                        if (root != 0 && base != null) {
                            val buttonBase = document.absoluteOrigin(base)
                            movePointer(buttonBase[0] + relX, buttonBase[1] + relY)
                        }
                        val pressed = type == X11.EV_BUTTON_PRESS
                        val bit = 1 shl (7 + detail)
                        val mask = if (pressed) buttonMask or bit else buttonMask and bit.inv()
                        onButton(rootPointerX, rootPointerY, detail, pressed, mask)
                    }
                    X11.EV_MOTION_NOTIFY -> {
                        // root=None means the current root: rootX/rootY are absolute.
                        val motionBase = base?.let { document.absoluteOrigin(it) } ?: intArrayOf(0, 0)
                        warpMove(motionBase[0] + relX, motionBase[1] + relY)
                    }
                    else -> error(X11.ERR_BAD_VALUE, major, type)
                }
            }
            3 -> Unit // GrabControl: we never freeze the client
            else -> {
                trace.recordUnimplemented(major, minor)
                error(X11.ERR_BAD_REQUEST, major, minor)
            }
        }
    }

    // --- RENDER (extension 0.4; see XRender for the version policy) ---------

    /**
     * RENDER request dispatch. The minor opcode arrives in the request's data
     * byte, and the layouts follow xorgproto's renderproto.h exactly. Requests
     * a 0.4 client cannot send (traps, solid fills, gradients, ARGB cursors,
     * transforms) fall through to BadRequest WITH a trace entry, so the shared
     * log names exactly what a future client asked for that we lack.
     */

    // --- XFIXES + SYNC (D1f) ---------------------------------------------------

    /** INT64 wire pair (hi, lo) to Long. */
    private fun combineInt64(hi: Int, lo: Int): Long =
        (hi.toLong() shl 32) or (lo.toLong() and 0xFFFFFFFFL)

    /** LISTofRECTANGLE trailing a request body (INT16 x, y; CARD16 width, height). */
    private fun readRectangles(reader: XReader, request: ByteArray): List<IntArray> {
        val from = reader.position
        val count = (request.size - from) / 8
        val r = XReader(request, littleEndian, from)
        return List(count) {
            intArrayOf(r.shortSigned(), r.shortSigned(), r.short(), r.short())
        }
    }

    /** XFixesSelectionNotify (32 bytes): selection ownership changed. */
    fun deliverSelectionNotify(
        window: Int,
        subtype: Int,
        owner: Int,
        selection: Int,
        timestamp: Int,
        selectionTimestamp: Int,
    ) {
        deliverEvent { writer ->
            writer.byte(XFixes.EV_SELECTION_NOTIFY)
            writer.byte(subtype)
            writer.short(sequence)
            writer.int(window)
            writer.int(owner)
            writer.int(selection)
            writer.int(timestamp)
            writer.int(selectionTimestamp)
            writer.pad(8)
        }
    }

    /** XFixesCursorNotify (32 bytes): the cursor displayed on [window] changed. */
    fun deliverCursorNotify(window: Int, subtype: Int, serial: Long, timestamp: Int, nameAtom: Int) {
        deliverEvent { writer ->
            writer.byte(XFixes.EV_CURSOR_NOTIFY)
            writer.byte(subtype)
            writer.short(sequence)
            writer.int(window)
            writer.int((serial and 0xFFFFFFFFL).toInt())
            writer.int(timestamp)
            writer.int(nameAtom)
            writer.pad(12)
        }
    }

    /** SyncAlarmNotify (32 bytes, kind 0) - delivered to the alarm's own connection. */
    fun deliverSyncAlarmNotify(alarmId: Int, counterValue: Long, alarmValue: Long, timestamp: Int) {
        deliverEvent { writer ->
            writer.byte(XSync.EV_ALARM_NOTIFY)
            writer.byte(XSync.KIND_ALARM_NOTIFY)
            writer.short(sequence)
            writer.int(alarmId)
            writer.int((counterValue shr 32).toInt())
            writer.int((counterValue and 0xFFFFFFFFL).toInt())
            writer.int((alarmValue shr 32).toInt())
            writer.int((alarmValue and 0xFFFFFFFFL).toInt())
            writer.int(timestamp)
            writer.byte(XSync.ALARM_ACTIVE) // the state the alarm had when it triggered
            writer.pad(3)
        }
    }

    /**
     * The reply of a HELD Await (XSync), stamped with the sequence number the
     * request arrived with - the client matches replies by sequence, and the
     * hold may have outlived thousands of later requests.
     */
    fun deliverAwaitReply(opcode: Int, awaitSequence: Int) {
        val writer = XWriter(littleEndian)
        writer.byte(1)
        writer.byte(0)
        writer.short(awaitSequence)
        writer.int(0)
        writer.pad(24)
        deliver(writer.toByteArray().copyOf(32))
    }

    private fun xfixesRequest(major: Int, minor: Int, reader: XReader, request: ByteArray) {
        when (minor) {
            XFixes.MINOR_QUERY_VERSION -> {
                val clientMajor = reader.int()
                val clientMinor = reader.int()
                val ansMajor = minOf(clientMajor, XFixes.VERSION_MAJOR)
                val ansMinor = if (clientMajor > XFixes.VERSION_MAJOR) {
                    XFixes.VERSION_MINOR
                } else {
                    minOf(clientMinor, XFixes.VERSION_MINOR)
                }
                reply(major) { writer ->
                    writer.int(ansMajor)
                    writer.int(ansMinor)
                    writer.pad(16)
                }
            }
            XFixes.MINOR_CHANGE_SAVE_SET -> {
                // mode/target/map + pad, then the window. Save-sets are not modelled
                // (the core ChangeSaveSet is an accepted no-op already); validate anyway.
                reader.byte(); reader.byte(); reader.byte(); reader.byte()
                val window = reader.int()
                if (window != 0 && document.window(window) == null) {
                    return error(X11.ERR_BAD_WINDOW, major, window, minor)
                }
            }
            XFixes.MINOR_SELECT_SELECTION_INPUT -> {
                val window = reader.int()
                val selection = reader.int()
                val eventMask = reader.int()
                if (window != 0 && document.window(window) == null) {
                    return error(X11.ERR_BAD_WINDOW, major, window, minor)
                }
                document.setSelectionWatch(selection, window, eventMask, this)
            }
            XFixes.MINOR_SELECT_CURSOR_INPUT -> {
                val window = reader.int()
                val eventMask = reader.int()
                if (document.window(window) == null) {
                    return error(X11.ERR_BAD_WINDOW, major, window, minor)
                }
                document.setCursorWatch(window, eventMask, this)
            }
            XFixes.MINOR_GET_CURSOR_IMAGE, XFixes.MINOR_GET_CURSOR_IMAGE_AND_NAME -> {
                val withName = minor == XFixes.MINOR_GET_CURSOR_IMAGE_AND_NAME
                val cursorId = document.effectiveCursorId(rootPointerX, rootPointerY)
                val resource = if (cursorId != 0) document.cursor(cursorId) else null
                val glyph = resource?.glyph ?: CursorGlyph.LEFT_PTR
                val fg = 0xFF000000.toInt() or (resource?.foreground ?: 0)
                val bg = 0xFF000000.toInt() or (resource?.background ?: 0xFFFFFF)
                val image = XFixesCursorArt.image(glyph, fg, bg)
                val serial = (document.currentCursorSerial() and 0xFFFFFFFFL).toInt()
                val name = if (withName && cursorId != 0) document.cursorName(cursorId) ?: "" else ""
                val nameBytes = name.toByteArray(Charsets.ISO_8859_1)
                val nameAtom = if (name.isEmpty()) 0 else document.internAtom(name)
                val namePad = X11.pad4(nameBytes.size) - nameBytes.size
                val extra = (if (withName) nameBytes.size + namePad else 0) + image.pixels.size * 4
                reply(major, extraLength = extra) { writer ->
                    writer.short(rootPointerX)
                    writer.short(rootPointerY)
                    writer.short(image.width)
                    writer.short(image.height)
                    writer.short(image.xhot)
                    writer.short(image.yhot)
                    writer.int(serial)
                    if (withName) {
                        writer.int(nameAtom)
                        writer.short(nameBytes.size)
                        writer.short(0)
                    } else {
                        writer.pad(8)
                    }
                    if (withName) {
                        writer.bytes(nameBytes)
                        writer.pad(namePad)
                    }
                    image.pixels.forEach { writer.int(it) }
                }
            }
            XFixes.MINOR_CREATE_REGION -> {
                val id = reader.int()
                if (regions.containsKey(id)) return error(X11.ERR_BAD_ID_CHOICE, major, id, minor)
                regions[id] = XRegion(XRegionOps.normalize(readRectangles(reader, request)))
            }
            XFixes.MINOR_CREATE_REGION_FROM_BITMAP -> {
                val id = reader.int()
                val pixmap = reader.int()
                if (regions.containsKey(id)) return error(X11.ERR_BAD_ID_CHOICE, major, id, minor)
                if (!pixmaps.containsKey(pixmap)) return error(X11.ERR_BAD_PIXMAP, major, pixmap, minor)
                val list = sink as? XDisplayList
                val size = list?.sizeOf(pixmap)
                if (list == null || size == null) {
                    return error(X11.ERR_BAD_PIXMAP, major, pixmap, minor)
                }
                // "Bitmap" means depth 1 (xorg answers BadMatch for anything else).
                if ((pixmaps[pixmap] ?: 0) != 1) {
                    return error(X11.ERR_BAD_MATCH, major, pixmap, minor)
                }
                // Scan the RECORDED OPS, not a snapshot: the display list decodes a
                // set depth-1 bit to opaque BLACK (pixelForDepth), and its snapshot
                // initialises to opaque black too - indistinguishable. Ops carry the
                // truth: for depth 1, Fill/Clear record argb != 0 / cleared, and
                // Image decodes per-pixel. Lines/text into a bitmap are not span
                // sources (X clients build bitmaps with PutImage/fills).
                val w = size[0]
                val h = size[1]
                val set = Array(h) { BooleanArray(w) }
                fun mark(x0: Int, y0: Int, x1: Int, y1: Int, value: Boolean) {
                    for (yy in maxOf(0, y0) until minOf(h, y1)) {
                        for (xx in maxOf(0, x0) until minOf(w, x1)) set[yy][xx] = value
                    }
                }
                for (op in list.ops(pixmap)) {
                    when (op) {
                        is DrawOp.Fill ->
                            mark(op.x, op.y, op.x + op.width, op.y + op.height, op.argb != 0)
                        is DrawOp.Clear ->
                            mark(op.x, op.y, op.x + op.width, op.y + op.height, value = false)
                        is DrawOp.Image -> {
                            for (row in 0 until op.height) {
                                for (col in 0 until op.width) {
                                    val idx = row * op.width + col
                                    if (idx >= op.argb.size) break
                                    val px = op.x + col
                                    val py = op.y + row
                                    if (px in 0 until w && py in 0 until h) {
                                        set[py][px] = (op.argb[idx] ushr 24) != 0
                                    }
                                }
                            }
                        }
                        else -> Unit
                    }
                }
                val spans = mutableListOf<IntArray>()
                for (y in 0 until h) {
                    var x = 0
                    while (x < w) {
                        if (set[y][x]) {
                            val start = x
                            while (x < w && set[y][x]) x++
                            spans += intArrayOf(start, y, x - start, 1)
                        } else {
                            x++
                        }
                    }
                }
                regions[id] = XRegion(XRegionOps.normalize(spans))
            }
            XFixes.MINOR_CREATE_REGION_FROM_WINDOW -> {
                val id = reader.int()
                val windowId = reader.int()
                val kind = reader.byte()
                if (regions.containsKey(id)) return error(X11.ERR_BAD_ID_CHOICE, major, id, minor)
                val window = document.window(windowId)
                    ?: return error(X11.ERR_BAD_WINDOW, major, windowId, minor)
                val windowOrigin = document.absoluteOrigin(window)
                val bounds = intArrayOf(windowOrigin[0], windowOrigin[1], window.width, window.height)
                val rects = if (kind == XFixes.WINDOW_REGION_CLIP) {
                    val above = document.windowsAbove(windowId).map {
                        val o = document.absoluteOrigin(it)
                        intArrayOf(o[0], o[1], it.width, it.height)
                    }
                    XRegionOps.subtract(listOf(bounds), above)
                } else {
                    XRegionOps.normalize(listOf(bounds))
                }
                regions[id] = XRegion(rects)
            }
            XFixes.MINOR_CREATE_REGION_FROM_GC -> {
                val id = reader.int()
                val gcId = reader.int()
                if (regions.containsKey(id)) return error(X11.ERR_BAD_ID_CHOICE, major, id, minor)
                val gc = gcs[gcId] ?: return error(X11.ERR_BAD_GC, major, gcId, minor)
                // An unset clip means "the whole plane" in X; a GC is drawable-less,
                // so the screen extent is the honest representable stand-in.
                val rects = gc.clipRegionRects
                    ?: listOf(intArrayOf(0, 0, document.screen.widthPx, document.screen.heightPx))
                regions[id] = XRegion(rects)
            }
            XFixes.MINOR_CREATE_REGION_FROM_PICTURE -> {
                val id = reader.int()
                val pictureId = reader.int()
                if (regions.containsKey(id)) return error(X11.ERR_BAD_ID_CHOICE, major, id, minor)
                val picture = pictures[pictureId]
                    ?: return error(XRender.ERR_PICTURE, major, pictureId, minor)
                val rects = picture.clipRects ?: run {
                    val w = document.window(picture.drawable)
                    val size = if (w != null) {
                        intArrayOf(w.width, w.height)
                    } else {
                        (sink as? XDisplayList)?.sizeOf(picture.drawable) ?: intArrayOf(0, 0)
                    }
                    listOf(intArrayOf(0, 0, size[0], size[1]))
                }
                regions[id] = XRegion(rects)
            }
            XFixes.MINOR_DESTROY_REGION -> {
                val id = reader.int()
                regions.remove(id) ?: return error(XFixes.ERR_BAD_REGION, major, id, minor)
            }
            XFixes.MINOR_SET_REGION -> {
                val id = reader.int()
                val region = regions[id] ?: return error(XFixes.ERR_BAD_REGION, major, id, minor)
                region.replace(XRegionOps.normalize(readRectangles(reader, request)))
            }
            XFixes.MINOR_COPY_REGION -> {
                val sourceId = reader.int()
                val destId = reader.int()
                val source = regions[sourceId] ?: return error(XFixes.ERR_BAD_REGION, major, sourceId, minor)
                val dest = regions[destId] ?: return error(XFixes.ERR_BAD_REGION, major, destId, minor)
                dest.replace(source.rects)
            }
            XFixes.MINOR_UNION_REGION, XFixes.MINOR_INTERSECT_REGION, XFixes.MINOR_SUBTRACT_REGION -> {
                val source1Id = reader.int()
                val source2Id = reader.int()
                val destId = reader.int()
                val s1 = regions[source1Id] ?: return error(XFixes.ERR_BAD_REGION, major, source1Id, minor)
                val s2 = regions[source2Id] ?: return error(XFixes.ERR_BAD_REGION, major, source2Id, minor)
                val dest = regions[destId] ?: return error(XFixes.ERR_BAD_REGION, major, destId, minor)
                dest.replace(
                    when (minor) {
                        XFixes.MINOR_UNION_REGION -> XRegionOps.union(s1.rects, s2.rects)
                        XFixes.MINOR_INTERSECT_REGION -> XRegionOps.intersect(s1.rects, s2.rects)
                        else -> XRegionOps.subtract(s1.rects, s2.rects)
                    },
                )
            }
            XFixes.MINOR_INVERT_REGION -> {
                val regionId = reader.int()
                val bounds = intArrayOf(
                    reader.shortSigned(), reader.shortSigned(), reader.short(), reader.short(),
                )
                val destId = reader.int()
                val region = regions[regionId] ?: return error(XFixes.ERR_BAD_REGION, major, regionId, minor)
                val dest = regions[destId] ?: return error(XFixes.ERR_BAD_REGION, major, destId, minor)
                dest.replace(XRegionOps.invert(region.rects, bounds))
            }
            XFixes.MINOR_TRANSLATE_REGION -> {
                val id = reader.int()
                val dx = reader.shortSigned()
                val dy = reader.shortSigned()
                val region = regions[id] ?: return error(XFixes.ERR_BAD_REGION, major, id, minor)
                region.replace(XRegionOps.translate(region.rects, dx, dy))
            }
            XFixes.MINOR_REGION_EXTENTS -> {
                val sourceId = reader.int()
                val destId = reader.int()
                val source = regions[sourceId] ?: return error(XFixes.ERR_BAD_REGION, major, sourceId, minor)
                val dest = regions[destId] ?: return error(XFixes.ERR_BAD_REGION, major, destId, minor)
                dest.replace(listOfNotNull(source.extents()))
            }
            XFixes.MINOR_FETCH_REGION -> {
                val id = reader.int()
                val region = regions[id] ?: return error(XFixes.ERR_BAD_REGION, major, id, minor)
                val extents = region.extents()
                reply(major, extraLength = region.rects.size * 8) { writer ->
                    writer.short(extents?.get(0) ?: 0)
                    writer.short(extents?.get(1) ?: 0)
                    writer.short(extents?.get(2) ?: 0)
                    writer.short(extents?.get(3) ?: 0)
                    writer.pad(16)
                    region.rects.forEach { r ->
                        writer.short(r[0]); writer.short(r[1]); writer.short(r[2]); writer.short(r[3])
                    }
                }
            }
            XFixes.MINOR_SET_GC_CLIP_REGION -> {
                val gcId = reader.int()
                val regionId = reader.int()
                val xOrigin = reader.shortSigned()
                val yOrigin = reader.shortSigned()
                val gc = gcs[gcId] ?: return error(X11.ERR_BAD_GC, major, gcId, minor)
                val region = regions[regionId] ?: return error(XFixes.ERR_BAD_REGION, major, regionId, minor)
                // Stored and round-tripped through CreateRegionFromGC. Core drawing
                // does not apply GC clips - the same documented limit the core
                // SetClipRectangles has ("accepted, not rendered").
                gc.clipRegionRects = XRegionOps.translate(region.rects, xOrigin, yOrigin)
            }
            XFixes.MINOR_SET_WINDOW_SHAPE_REGION -> {
                val windowId = reader.int()
                val kind = reader.byte()
                reader.byte() // pad1
                reader.short() // pad2
                val xOff = reader.shortSigned()
                val yOff = reader.shortSigned()
                val regionId = reader.int()
                val window = document.window(windowId)
                    ?: return error(X11.ERR_BAD_WINDOW, major, windowId, minor)
                val region = if (regionId == 0) {
                    null // None clears the shape
                } else {
                    regions[regionId] ?: return error(XFixes.ERR_BAD_REGION, major, regionId, minor)
                }
                val shapeOrigin = document.absoluteOrigin(window)
                val rects = region?.let {
                    XRegionOps.translate(it.rects, shapeOrigin[0] + xOff, shapeOrigin[1] + yOff)
                }
                when (kind) {
                    XFixes.SHAPE_BOUNDING -> window.boundingShape = rects
                    XFixes.SHAPE_CLIP -> window.clipShape = rects
                    XFixes.SHAPE_INPUT -> window.inputShape = rects
                    else -> return error(X11.ERR_BAD_VALUE, major, kind, minor)
                }
            }
            XFixes.MINOR_SET_PICTURE_CLIP_REGION -> {
                val pictureId = reader.int()
                val regionId = reader.int()
                val xOrigin = reader.shortSigned()
                val yOrigin = reader.shortSigned()
                val picture = pictures[pictureId]
                    ?: return error(XRender.ERR_PICTURE, major, pictureId, minor)
                val region = regions[regionId] ?: return error(XFixes.ERR_BAD_REGION, major, regionId, minor)
                // Same storage SetPictureClipRectangles uses - absolute dst-space
                // rects - so the RENDER composite path enforces the clip for real.
                picture.clipRects = XRegionOps.translate(region.rects, xOrigin, yOrigin)
                picture.clipMask = 0
            }
            XFixes.MINOR_SET_CURSOR_NAME -> {
                val cursorId = reader.int()
                val nbytes = reader.short()
                reader.short() // pad
                if (document.cursor(cursorId) == null) {
                    return error(X11.ERR_BAD_CURSOR, major, cursorId, minor)
                }
                val name = String(reader.bytes(nbytes), Charsets.ISO_8859_1)
                document.setCursorName(cursorId, name)
            }
            XFixes.MINOR_GET_CURSOR_NAME -> {
                val cursorId = reader.int()
                if (document.cursor(cursorId) == null) {
                    return error(X11.ERR_BAD_CURSOR, major, cursorId, minor)
                }
                val name = document.cursorName(cursorId) ?: ""
                val bytes = name.toByteArray(Charsets.ISO_8859_1)
                val atom = if (name.isEmpty()) 0 else document.internAtom(name)
                val pad = X11.pad4(bytes.size) - bytes.size
                reply(major, extraLength = bytes.size + pad) { writer ->
                    writer.int(atom)
                    writer.short(bytes.size)
                    writer.short(0)
                    writer.pad(16)
                    writer.bytes(bytes)
                    writer.pad(pad)
                }
            }
            XFixes.MINOR_CHANGE_CURSOR -> {
                val sourceId = reader.int()
                val destId = reader.int()
                val source = document.cursor(sourceId)
                    ?: return error(X11.ERR_BAD_CURSOR, major, sourceId, minor)
                if (document.cursor(destId) == null) {
                    return error(X11.ERR_BAD_CURSOR, major, destId, minor)
                }
                document.addCursor(source.copy(id = destId))
            }
            XFixes.MINOR_CHANGE_CURSOR_BY_NAME -> {
                val sourceId = reader.int()
                val nbytes = reader.short()
                reader.short() // pad
                val source = document.cursor(sourceId)
                    ?: return error(X11.ERR_BAD_CURSOR, major, sourceId, minor)
                val name = String(reader.bytes(nbytes), Charsets.ISO_8859_1)
                val destId = document.cursorIdByName(name)
                    ?: return error(X11.ERR_BAD_NAME, major, sourceId, minor)
                document.addCursor(source.copy(id = destId))
            }
            XFixes.MINOR_EXPAND_REGION -> {
                val sourceId = reader.int()
                val destId = reader.int()
                val left = reader.short()
                val right = reader.short()
                val top = reader.short()
                val bottom = reader.short()
                val source = regions[sourceId] ?: return error(XFixes.ERR_BAD_REGION, major, sourceId, minor)
                val dest = regions[destId] ?: return error(XFixes.ERR_BAD_REGION, major, destId, minor)
                dest.replace(XRegionOps.expand(source.rects, left, right, top, bottom))
            }
            XFixes.MINOR_HIDE_CURSOR -> {
                val windowId = reader.int()
                if (document.window(windowId) == null) {
                    return error(X11.ERR_BAD_WINDOW, major, windowId, minor)
                }
                document.setCursorHiddenForWindow(windowId, hidden = true)
            }
            XFixes.MINOR_SHOW_CURSOR -> {
                val windowId = reader.int()
                if (document.window(windowId) == null) {
                    return error(X11.ERR_BAD_WINDOW, major, windowId, minor)
                }
                document.setCursorHiddenForWindow(windowId, hidden = false)
            }
            else -> {
                // Pointer barriers and the v6 client-disconnect tail: refused and
                // named in the trace rather than accepted-and-ignored - a Success
                // answer would be a lie a client could act on. (The 5.0 version
                // claim keeps well-behaved clients from ever sending these.)
                trace.recordUnimplemented(major, minor)
                error(X11.ERR_BAD_REQUEST, major, 0, minor)
            }
        }
    }

    private fun xsyncRequest(major: Int, minor: Int, reader: XReader, request: ByteArray) {
        when (minor) {
            XSync.MINOR_INITIALIZE -> {
                val clientMajor = reader.byte()
                val clientMinor = reader.byte()
                reply(major) { writer ->
                    writer.byte(minOf(clientMajor, XSync.VERSION_MAJOR))
                    writer.byte(
                        if (clientMajor > XSync.VERSION_MAJOR) XSync.VERSION_MINOR
                        else minOf(clientMinor, XSync.VERSION_MINOR),
                    )
                    writer.pad(22)
                }
            }
            XSync.MINOR_LIST_SYSTEM_COUNTERS -> {
                // We expose no system counters; clients create their own.
                reply(major) { writer ->
                    writer.int(0)
                    writer.pad(20)
                }
            }
            XSync.MINOR_CREATE_COUNTER -> {
                val id = reader.int()
                val value = combineInt64(reader.int(), reader.int())
                if (document.syncCounter(id) != null) {
                    return error(X11.ERR_BAD_ID_CHOICE, major, id, minor)
                }
                document.putSyncCounter(XSync.Counter(id, value, this))
            }
            XSync.MINOR_SET_COUNTER -> {
                val id = reader.int()
                val value = combineInt64(reader.int(), reader.int())
                val counter = document.syncCounter(id)
                    ?: return error(XSync.ERR_BAD_COUNTER, major, id, minor)
                val old = counter.value
                counter.value = value
                syncSideEffects(counter, old)
            }
            XSync.MINOR_CHANGE_COUNTER -> {
                val id = reader.int()
                val delta = combineInt64(reader.int(), reader.int())
                val counter = document.syncCounter(id)
                    ?: return error(XSync.ERR_BAD_COUNTER, major, id, minor)
                val old = counter.value
                counter.value += delta
                syncSideEffects(counter, old)
            }
            XSync.MINOR_QUERY_COUNTER -> {
                val id = reader.int()
                val counter = document.syncCounter(id)
                    ?: return error(XSync.ERR_BAD_COUNTER, major, id, minor)
                reply(major) { writer ->
                    writer.int((counter.value shr 32).toInt())
                    writer.int((counter.value and 0xFFFFFFFFL).toInt())
                    writer.pad(16)
                }
            }
            XSync.MINOR_DESTROY_COUNTER -> {
                val id = reader.int()
                document.removeSyncCounter(id)
                    ?: return error(XSync.ERR_BAD_COUNTER, major, id, minor)
            }
            XSync.MINOR_AWAIT -> {
                val r = XReader(request, littleEndian, reader.position)
                val conditions = mutableListOf<XSync.Condition>()
                while (r.position + 28 <= request.size) {
                    val counterId = r.int()
                    val valueType = r.int()
                    val waitValue = combineInt64(r.int(), r.int())
                    val testType = r.int()
                    r.int(); r.int() // event_threshold INT64 (no CounterNotify streaming)
                    val counter = document.syncCounter(counterId)
                        ?: return error(XSync.ERR_BAD_COUNTER, major, counterId, minor)
                    // test_type Relative (1): the wait value is an offset from the
                    // counter's value at Await time.
                    val effective = if (testType == 1) waitValue + counter.value else waitValue
                    conditions += XSync.Condition(counterId, valueType, effective)
                }
                val satisfiedNow = conditions.all { condition ->
                    val counter = document.syncCounter(condition.counterId) ?: return@all false
                    XSync.holds(condition, counter.value, counter.value, crossing = false)
                }
                if (conditions.isEmpty() || satisfiedNow) {
                    reply(major) { writer -> writer.pad(24) }
                } else {
                    // Hold the reply until a counter change satisfies every condition -
                    // the observable behaviour of a blocking Await for a client that
                    // pipelined the request. The held reply keeps THIS sequence number.
                    document.addPendingAwait(
                        XDocument.PendingAwait(this, conditions, major, sequence),
                    )
                }
            }
            XSync.MINOR_CREATE_ALARM -> {
                val id = reader.int()
                val mask = reader.int()
                if (document.syncAlarm(id) != null) {
                    return error(X11.ERR_BAD_ID_CHOICE, major, id, minor)
                }
                val alarm = XSync.Alarm(id, this)
                if (!readAlarmValues(alarm, reader, mask, major, minor)) return
                document.putSyncAlarm(alarm)
            }
            XSync.MINOR_CHANGE_ALARM -> {
                val id = reader.int()
                val mask = reader.int()
                val alarm = document.syncAlarm(id)
                    ?: return error(XSync.ERR_BAD_ALARM, major, id, minor)
                if (!readAlarmValues(alarm, reader, mask, major, minor)) return
            }
            XSync.MINOR_QUERY_ALARM -> {
                val id = reader.int()
                val alarm = document.syncAlarm(id)
                    ?: return error(XSync.ERR_BAD_ALARM, major, id, minor)
                reply(major, extraLength = 8) { writer ->
                    writer.int(alarm.counterId)
                    writer.int(alarm.valueType)
                    writer.int((alarm.value shr 32).toInt())
                    writer.int((alarm.value and 0xFFFFFFFFL).toInt())
                    writer.int(0) // test_type: absolute readings only
                    writer.int((alarm.delta shr 32).toInt())
                    writer.int((alarm.delta and 0xFFFFFFFFL).toInt())
                    writer.byte(if (alarm.events) 1 else 0)
                    writer.byte(alarm.state)
                    writer.pad(2)
                }
            }
            XSync.MINOR_DESTROY_ALARM -> {
                val id = reader.int()
                document.removeSyncAlarm(id)
                    ?: return error(XSync.ERR_BAD_ALARM, major, id, minor)
            }
            XSync.MINOR_SET_PRIORITY -> {
                reader.int() // id (any XID)
                reader.int() // priority - accepted, not modelled (one app per server)
            }
            XSync.MINOR_GET_PRIORITY -> {
                reader.int()
                reply(major) { writer ->
                    writer.int(0)
                    writer.pad(20)
                }
            }
            else -> {
                // Fences (SYNC 3.0, GPU sync objects) and beyond: we have no
                // GL/DRI path they could mean anything on. Refused and named.
                trace.recordUnimplemented(major, minor)
                error(X11.ERR_BAD_REQUEST, major, 0, minor)
            }
        }
    }

    /** The CreateAlarm/ChangeAlarm value list, consumed in value-mask bit order. */
    private fun readAlarmValues(
        alarm: XSync.Alarm,
        reader: XReader,
        mask: Int,
        major: Int,
        minor: Int,
    ): Boolean {
        if (mask and XSync.CA_COUNTER != 0) {
            val counterId = reader.int()
            if (document.syncCounter(counterId) == null) {
                error(XSync.ERR_BAD_COUNTER, major, counterId, minor)
                return false
            }
            alarm.counterId = counterId
        }
        if (mask and XSync.CA_VALUE_TYPE != 0) alarm.valueType = reader.int()
        if (mask and XSync.CA_VALUE != 0) alarm.value = combineInt64(reader.int(), reader.int())
        if (mask and XSync.CA_EVENTS != 0) alarm.events = reader.int() != 0
        if (mask and XSync.CA_DELTA != 0) alarm.delta = combineInt64(reader.int(), reader.int())
        if (mask and XSync.CA_TIMEOUT != 0) alarm.timeout = reader.int()
        if (mask and XSync.CA_TYPE != 0) alarm.alarmType = reader.int()
        return true
    }

    /** Delivers held-Await replies and fired AlarmNotifies after a counter change. */
    private fun syncSideEffects(counter: XSync.Counter, oldValue: Long) {
        val (awaits, alarms) = document.onCounterChanged(counter, oldValue)
        val now = document.timestamp()
        awaits.forEach { await ->
            await.connection.deliverAwaitReply(await.opcode, await.sequence)
        }
        alarms.forEach { alarm ->
            if (alarm.events) {
                alarm.owner.deliverSyncAlarmNotify(alarm.id, counter.value, alarm.value, now)
            }
        }
    }

    private fun renderRequest(major: Int, minor: Int, reader: XReader, request: ByteArray) {
        when (minor) {
            XRender.MINOR_QUERY_VERSION -> {
                val clientMajor = reader.int()
                val clientMinor = reader.int()
                val answered =
                    if (clientMajor > XRender.VERSION_MAJOR) XRender.VERSION_MINOR
                    else minOf(clientMinor, XRender.VERSION_MINOR)
                reply(major) { writer ->
                    writer.int(XRender.VERSION_MAJOR)
                    writer.int(answered)
                    writer.pad(16)
                }
            }
            XRender.MINOR_QUERY_PICT_FORMATS -> {
                val formats = XRender.FORMATS
                val visuals = XRender.visualFormats()
                val depths = XRender.SCREEN_DEPTHS
                // 28B per xPictFormInfo; one screen (8B) + per-depth blocks (8B each)
                // + the depth-24 visuals (8B each) + one subpixel entry (4B).
                val extra = formats.size * 28 + 8 + depths.size * 8 + visuals.size * 8 + 4
                reply(major, extraLength = extra) { writer ->
                    writer.int(formats.size) // num-formats @8
                    writer.int(1) // num-screens
                    writer.int(depths.size) // num-depths
                    writer.int(visuals.size) // num-visuals
                    writer.int(1) // num-subpixel (0.6 field; always sent, clients gate on it)
                    writer.pad(4)
                    formats.forEach { f ->
                        writer.int(f.id)
                        writer.byte(1) // type: Direct
                        writer.byte(f.depth)
                        writer.short(0) // pad1
                        writer.short(f.redShift); writer.short(f.redMask)
                        writer.short(f.greenShift); writer.short(f.greenMask)
                        writer.short(f.blueShift); writer.short(f.blueMask)
                        writer.short(f.alphaShift); writer.short(f.alphaMask)
                        writer.int(0) // colormap: None
                    }
                    writer.int(depths.size) // xRenderPictScreen.depth (count)
                    writer.int(XRender.FORMAT_X8R8G8B8) // fallback
                    depths.forEach { depth ->
                        val visualsHere = if (depth == 24) visuals.size else 0
                        writer.byte(depth)
                        writer.byte(0) // pad1
                        writer.short(visualsHere)
                        writer.int(0) // pad2
                        if (depth == 24) {
                            visuals.forEach { (visualId, formatId) -> writer.int(visualId); writer.int(formatId) }
                        }
                    }
                    writer.int(0) // subpixel order: Unknown (grayscale text AA)
                }
            }
            XRender.MINOR_QUERY_PICT_INDEX_VALUES -> {
                val formatId = reader.int()
                if (XRender.format(formatId) == null) return error(XRender.ERR_PICT_FORMAT, major, formatId, minor)
                // All our formats are Direct; indexed palettes report zero values.
                reply(major) { writer -> writer.int(0); writer.pad(20) }
            }
            XRender.MINOR_CREATE_PICTURE -> {
                val pid = reader.int()
                val drawable = reader.int()
                val formatId = reader.int()
                val mask = reader.int()
                val format = XRender.format(formatId)
                    ?: return error(XRender.ERR_PICT_FORMAT, major, formatId, minor)
                val window = document.window(drawable)
                val drawableDepth = when {
                    window != null -> window.depth
                    pixmaps.containsKey(drawable) -> pixmaps.getValue(drawable)
                    else -> return error(X11.ERR_BAD_DRAWABLE, major, drawable, minor)
                }
                if (format.depth != drawableDepth) return error(X11.ERR_BAD_MATCH, major, formatId, minor)
                val state = PictureState(drawable, formatId)
                pictures[pid] = state
                readPictureValues(reader, mask, state)
            }
            XRender.MINOR_CHANGE_PICTURE -> {
                val pid = reader.int()
                val mask = reader.int()
                val state = pictures[pid] ?: return error(XRender.ERR_PICTURE, major, pid, minor)
                readPictureValues(reader, mask, state)
            }
            XRender.MINOR_SET_PICTURE_CLIP_RECTANGLES -> {
                val pid = reader.int()
                val xOrigin = reader.shortSigned()
                val yOrigin = reader.shortSigned()
                val state = pictures[pid] ?: return error(XRender.ERR_PICTURE, major, pid, minor)
                val count = (request.size - 12) / 8
                val rects = List(count) {
                    intArrayOf(
                        reader.shortSigned() + xOrigin,
                        reader.shortSigned() + yOrigin,
                        reader.short(),
                        reader.short(),
                    )
                }
                state.clipRects = rects
                state.clipMask = 0
            }
            XRender.MINOR_FREE_PICTURE -> {
                val pid = reader.int()
                pictures.remove(pid) ?: return error(XRender.ERR_PICTURE, major, pid, minor)
            }
            XRender.MINOR_COMPOSITE -> {
                val op = reader.byte()
                reader.skip(3)
                val srcId = reader.int()
                val maskId = reader.int()
                val dstId = reader.int()
                val xSrc = reader.shortSigned(); val ySrc = reader.shortSigned()
                val xMask = reader.shortSigned(); val yMask = reader.shortSigned()
                val xDst = reader.shortSigned(); val yDst = reader.shortSigned()
                val width = reader.short(); val height = reader.short()
                val src = pictures[srcId] ?: return error(XRender.ERR_PICTURE, major, srcId, minor)
                val dst = pictures[dstId] ?: return error(XRender.ERR_PICTURE, major, dstId, minor)
                val mask = if (maskId != 0) pictures[maskId] ?: return error(XRender.ERR_PICTURE, major, maskId, minor) else null
                if (op !in XRender.OP_CLEAR..XRender.OP_SATURATE) return error(XRender.ERR_PICT_OP, major, op, minor)
                sink.blend(dst.drawable, compositeSpec(op, src, mask, dst, xSrc, ySrc, xMask, yMask, xDst, yDst, width, height))
            }
            XRender.MINOR_FILL_RECTANGLES -> {
                val op = reader.byte()
                reader.skip(3)
                val dstId = reader.int()
                val red = reader.short(); val green = reader.short()
                val blue = reader.short(); val alpha = reader.short()
                val dst = pictures[dstId] ?: return error(XRender.ERR_PICTURE, major, dstId, minor)
                if (op !in XRender.OP_CLEAR..XRender.OP_SATURATE) return error(XRender.ERR_PICT_OP, major, op, minor)
                val color = XRender.color16ToArgb(red, green, blue, alpha)
                val count = (request.size - 20) / 8
                repeat(count) {
                    val x = reader.shortSigned(); val y = reader.shortSigned()
                    val width = reader.short(); val height = reader.short()
                    sink.blend(
                        dst.drawable,
                        BlendSpec(
                            op = op, srcDrawable = 0, srcColor = color, srcX = 0, srcY = 0,
                            maskDrawable = 0, maskX = 0, maskY = 0,
                            dstX = x, dstY = y, width = width, height = height,
                            srcRepeat = XRender.REPEAT_NONE, clip = clipOf(dst),
                        ),
                    )
                }
            }
            XRender.MINOR_CREATE_GLYPH_SET -> {
                val gsid = reader.int()
                val formatId = reader.int()
                val format = XRender.format(formatId)
                if (format == null || format.depth != 1 && format.depth != 4 && format.depth != 8) {
                    return error(XRender.ERR_PICT_FORMAT, major, formatId, minor)
                }
                glyphSets[gsid] = XRender.GlyphStore(formatId)
            }
            XRender.MINOR_REFERENCE_GLYPH_SET -> {
                val gsid = reader.int()
                val existing = reader.int()
                glyphSets[gsid] = glyphSets[existing] ?: return error(XRender.ERR_GLYPH_SET, major, existing, minor)
            }
            XRender.MINOR_FREE_GLYPH_SET -> {
                val gsid = reader.int()
                glyphSets.remove(gsid) ?: return error(XRender.ERR_GLYPH_SET, major, gsid, minor)
            }
            XRender.MINOR_ADD_GLYPHS -> {
                val gsid = reader.int()
                val nglyphs = reader.int()
                val store = glyphSets[gsid] ?: return error(XRender.ERR_GLYPH_SET, major, gsid, minor)
                // Every glyph costs a CARD32 id + a 12-byte xGlyphInfo before any bitmap data.
                if (nglyphs < 0 || nglyphs.toLong() * 16 > (request.size - 12).toLong()) {
                    return error(X11.ERR_BAD_LENGTH, major, nglyphs, minor)
                }
                val format = XRender.format(store.formatId) ?: return error(XRender.ERR_PICT_FORMAT, major, store.formatId, minor)
                val ids = IntArray(nglyphs) { reader.int() }
                val infos = Array(nglyphs) {
                    val width = reader.short(); val height = reader.short()
                    val x = reader.shortSigned(); val y = reader.shortSigned()
                    val xOff = reader.shortSigned(); val yOff = reader.shortSigned()
                    intArrayOf(width, height, x, y, xOff, yOff)
                }
                var offset = 12 + nglyphs * 16
                for (index in 0 until nglyphs) {
                    val info = infos[index]
                    val width = info[0]; val height = info[1]
                    val x = info[2]; val y = info[3]
                    val xOff = info[4]; val yOff = info[5]
                    val bytes = XRender.glyphDataBytes(format.depth, width, height)
                    if (bytes < 0 || offset + bytes > request.size) {
                        return error(X11.ERR_BAD_LENGTH, major, ids[index], minor)
                    }
                    val alpha = XRender.expandAlpha(format.depth, width, height, request, offset)
                        ?: return error(X11.ERR_BAD_LENGTH, major, ids[index], minor)
                    store.glyphs[ids[index]] = XRender.Glyph(width, height, x, y, xOff, yOff, alpha)
                    offset += bytes
                }
            }
            XRender.MINOR_FREE_GLYPHS -> {
                val gsid = reader.int()
                val store = glyphSets[gsid] ?: return error(XRender.ERR_GLYPH_SET, major, gsid, minor)
                val count = (request.size - 8) / 4
                repeat(count) { store.glyphs.remove(reader.int()) }
            }
            XRender.MINOR_COMPOSITE_GLYPHS_8, XRender.MINOR_COMPOSITE_GLYPHS_16, XRender.MINOR_COMPOSITE_GLYPHS_32 -> {
                val idWidth = when (minor) {
                    XRender.MINOR_COMPOSITE_GLYPHS_8 -> 1
                    XRender.MINOR_COMPOSITE_GLYPHS_16 -> 2
                    else -> 4
                }
                val op = reader.byte()
                reader.skip(3)
                val srcId = reader.int()
                val dstId = reader.int()
                reader.int() // mask-format: a hint we do not need
                val gsid = reader.int()
                val xSrc = reader.shortSigned(); val ySrc = reader.shortSigned()
                val src = pictures[srcId] ?: return error(XRender.ERR_PICTURE, major, srcId, minor)
                val dst = pictures[dstId] ?: return error(XRender.ERR_PICTURE, major, dstId, minor)
                if (op !in XRender.OP_CLEAR..XRender.OP_SATURATE) return error(XRender.ERR_PICT_OP, major, op, minor)
                val store = glyphSets[gsid] ?: return error(XRender.ERR_GLYPH_SET, major, gsid, minor)
                val elements = XRender.parseGlyphElements(request, littleEndian, idWidth, 28)
                    ?: return error(X11.ERR_BAD_LENGTH, major, 0, minor)
                // Resolve every glyph before drawing anything: a missing id is BadGlyph
                // and the whole request must not half-render (Xorg semantics).
                val placements = mutableListOf<GlyphPlacement>()
                var penX = 0
                var penY = 0
                for (element in elements) {
                    penX += element.dx
                    penY += element.dy
                    for (id in element.ids) {
                        val glyph = store.glyphs[id] ?: return error(XRender.ERR_GLYPH, major, id, minor)
                        placements.add(GlyphPlacement(penX + glyph.x, penY + glyph.y, glyph))
                        penX += glyph.xOff
                        penY += glyph.yOff
                    }
                }
                if (placements.isEmpty()) return
                val solid = src.solidColor >= 0
                sink.glyphRun(
                    dst.drawable,
                    GlyphRunSpec(
                        op = op,
                        srcColor = if (solid) src.solidColor else 0,
                        srcDrawable = if (solid) 0 else src.drawable,
                        srcX = xSrc,
                        srcY = ySrc,
                        srcRepeat = src.repeat,
                        placements = placements,
                    ),
                )
            }
            else -> {
                // Everything a RENDER 0.4 client cannot send: traps, solid fills,
                // gradients, ARGB cursors, transforms, filters. Named in the trace
                // so the shared log says exactly which one a client tried.
                trace.recordUnimplemented(major, minor)
                error(X11.ERR_BAD_REQUEST, major, 0, minor)
            }
        }
    }

    /** Reads CreatePicture/ChangePicture values in ascending value-mask bit order. */
    private fun readPictureValues(reader: XReader, mask: Int, state: PictureState) {
        if (mask and XRender.CP_REPEAT != 0) state.repeat = reader.int()
        if (mask and XRender.CP_ALPHA_MAP != 0) reader.int()
        if (mask and XRender.CP_ALPHA_X_ORIGIN != 0) reader.int()
        if (mask and XRender.CP_ALPHA_Y_ORIGIN != 0) reader.int()
        if (mask and XRender.CP_CLIP_X_ORIGIN != 0) state.clipXOrigin = reader.int()
        if (mask and XRender.CP_CLIP_Y_ORIGIN != 0) state.clipYOrigin = reader.int()
        if (mask and XRender.CP_CLIP_MASK != 0) state.clipMask = reader.int()
        if (mask and XRender.CP_GRAPHICS_EXPOSURE != 0) reader.int()
        if (mask and XRender.CP_SUBWINDOW_MODE != 0) reader.int()
        if (mask and XRender.CP_POLY_EDGE != 0) reader.int()
        if (mask and XRender.CP_POLY_MODE != 0) reader.int()
        if (mask and XRender.CP_DITHER != 0) reader.int()
        if (mask and XRender.CP_COMPONENT_ALPHA != 0) state.componentAlpha = reader.int() != 0
    }

    /** The destination picture's clip rectangles translated to absolute dst coordinates. */
    private fun clipOf(dst: PictureState): List<IntArray>? {
        val rects = dst.clipRects ?: return null
        if (dst.clipXOrigin == 0 && dst.clipYOrigin == 0) return rects
        return rects.map { intArrayOf(it[0] + dst.clipXOrigin, it[1] + dst.clipYOrigin, it[2], it[3]) }
    }

    /** Resolves a Composite request into the blend the rasterisers replay. */
    private fun compositeSpec(
        op: Int,
        src: PictureState,
        mask: PictureState?,
        dst: PictureState,
        xSrc: Int, ySrc: Int,
        xMask: Int, yMask: Int,
        xDst: Int, yDst: Int,
        width: Int, height: Int,
    ): BlendSpec {
        // An explicit mask picture wins; otherwise the dst's clip-mask pixmap (a1)
        // acts as the mask. Intersecting BOTH at once is not modelled - cairo uses
        // one or the other, and the protocol trace shows if that ever bites.
        var maskDrawable = 0
        var maskColor: Int? = null
        var maskX = xMask
        var maskY = yMask
        when {
            mask != null && mask.solidColor >= 0 -> maskColor = mask.solidColor
            mask != null -> { maskDrawable = mask.drawable }
            dst.clipMask != 0 && pixmaps.containsKey(dst.clipMask) -> {
                maskDrawable = dst.clipMask
                // The clip mask sits at the clip origin in dst space, so it samples
                // dst-aligned: mask coordinate = dst coordinate - clip origin.
                maskX = xDst - dst.clipXOrigin
                maskY = yDst - dst.clipYOrigin
            }
        }
        return BlendSpec(
            op = op,
            srcDrawable = if (src.solidColor >= 0) 0 else src.drawable,
            srcColor = if (src.solidColor >= 0) src.solidColor else 0,
            srcX = xSrc, srcY = ySrc,
            maskDrawable = maskDrawable, maskX = maskX, maskY = maskY, maskColor = maskColor,
            dstX = xDst, dstY = yDst, width = width, height = height,
            srcRepeat = src.repeat,
            clip = clipOf(dst),
        )
    }

    // --- XInput2 (XI2): the input extension GTK3/GDK uses --------------------

    /**
     * The atoms XI2's device classes label their buttons and axes with. Interned
     * on first use: they are ordinary atoms, so `GetAtomName` answers them like any
     * other, and GDK's `translate_valuator_class` looks them up by name ("Abs X"
     * etc. - ours are the "Rel *" ones, which GDK deliberately ignores).
     */
    private var xi2LabelAtoms: Map<String, Int>? = null

    private fun xi2Label(name: String): Int {
        xi2LabelAtoms?.let { return it.getValue(name) }
        val names = (1..XInput2.NUM_BUTTONS).map { "Button$it" } + listOf("Rel X", "Rel Y")
        val map = names.associateWith { document.internAtom(it) }
        xi2LabelAtoms = map
        return map.getValue(name)
    }

    /** This connection's XI2 event selections, one set per window. */
    private val xi2Selections = XInput2Selections()

    /** XI2 grab held by this connection; null when none. */
    private var xi2Grab: Xi2Grab? = null

    /** Client pointer/keyboard pair (`XISetClientPointer`); the masters by default. */
    private var xi2ClientPointer: Int = XInput2.MASTER_POINTER

    /** Device properties set through `XIChangeProperty`, keyed by device then atom. */
    private val xi2Properties = HashMap<Int, MutableMap<Int, Xi2Property>>()

    private class Xi2Grab(
        val deviceId: Int,
        val window: Int,
        val ownerEvents: Boolean,
        val mask: ByteArray,
        val keyboard: Boolean,
    )

    private data class Xi2Property(val type: Int, val format: Int, val data: ByteArray)

    /** True once this client negotiated a version; used by tests and the trace. */
    var xi2Negotiated: Boolean = false
        private set

    private fun xi2Request(major: Int, minor: Int, reader: XReader, request: ByteArray) {
        when (minor) {
            XInput2.MINOR_QUERY_VERSION -> {
                val clientMajor = reader.short()
                val clientMinor = reader.short()
                // Same rule as RENDER: never claim more than we implement, and
                // answer the client's own number when it asks for less.
                val answeredMinor =
                    if (clientMajor > XInput2.VERSION_MAJOR) XInput2.VERSION_MINOR
                    else minOf(clientMinor, XInput2.VERSION_MINOR)
                xi2Negotiated = true
                reply(major, dataByte = minor) { writer ->
                    writer.short(XInput2.VERSION_MAJOR)
                    writer.short(answeredMinor)
                    writer.pad(20)
                }
            }
            XInput2.MINOR_QUERY_DEVICE -> {
                val deviceId = reader.short()
                reader.short() // pad
                val devices = when (deviceId) {
                    XInput2.ALL_DEVICES -> XInput2.DEVICES
                    XInput2.ALL_MASTER_DEVICES -> XInput2.DEVICES.filter { XInput2.isMaster(it.id) }
                    else -> XInput2.device(deviceId)?.let { listOf(it) }
                        ?: return error(XInput2.ERR_BAD_DEVICE_ID, major, deviceId, minor)
                }
                val payload = XInput2.queryDevicePayload(
                    littleEndian = littleEndian,
                    keyboard = keyboard,
                    labels = { xi2Label(it) },
                    pointerPosition = { rootPointerX to rootPointerY },
                    devices = devices,
                )
                reply(major, dataByte = minor, extraLength = payload.size) { writer ->
                    writer.short(devices.size) // num_devices
                    writer.pad(22)
                    writer.bytes(payload)
                }
            }
            XInput2.MINOR_SELECT_EVENTS -> {
                val win = reader.int()
                val numMasks = reader.short()
                reader.short() // pad
                if (document.window(win) == null) return error(X11.ERR_BAD_WINDOW, major, win, minor)
                // Validate every device id before storing anything: a partially
                // applied selection would be worse than a clean refusal.
                val parsed = List(numMasks) {
                    val deviceId = reader.short()
                    val maskLen = reader.short() // 4-byte units
                    val mask = reader.bytes(maskLen * 4)
                    deviceId to mask
                }
                parsed.forEach { (deviceId, _) ->
                    if (!XInput2.isKnownDevice(deviceId)) {
                        return error(XInput2.ERR_BAD_DEVICE, major, deviceId, minor)
                    }
                }
                parsed.forEach { (deviceId, mask) -> xi2Selections.select(win, deviceId, mask) }
            }
            XInput2.MINOR_GET_SELECTED_EVENTS -> {
                val win = reader.int()
                if (document.window(win) == null) return error(X11.ERR_BAD_WINDOW, major, win, minor)
                val masks = xi2Selections.masksFor(win)
                val extra = masks.sumOf { 4 + it.mask.size }
                reply(major, dataByte = minor, extraLength = extra) { writer ->
                    writer.short(masks.size) // num_masks
                    writer.pad(22)
                    masks.forEach { mask ->
                        writer.short(mask.deviceId)
                        writer.short(mask.mask.size / 4)
                        writer.bytes(mask.mask)
                    }
                }
            }
            XInput2.MINOR_QUERY_POINTER -> {
                val win = reader.int()
                val deviceId = reader.short()
                reader.short() // pad
                val window = document.window(win) ?: return error(X11.ERR_BAD_WINDOW, major, win, minor)
                if (!xi2IsPointer(deviceId)) return error(XInput2.ERR_BAD_DEVICE, major, deviceId, minor)
                val under = document.windowAt(rootPointerX, rootPointerY)
                val child = childOnPath(win, under?.id)
                val (base, locked, effective) = XInput2.modifierParts(keyStateMask)
                // sz_xXIQueryPointerReply is 56 bytes and the button mask follows
                // it as extra data: 4 bytes here (buttons_len = 1 unit).
                reply(major, dataByte = minor, extraLength = 28) { writer ->
                    writer.int(document.rootId)
                    writer.int(child)
                    with(XInput2) {
                        writer.fp1616(rootPointerX)
                        writer.fp1616(rootPointerY)
                        writer.fp1616(rootPointerX - window.x)
                        writer.fp1616(rootPointerY - window.y)
                    }
                    writer.byte(1) // same_screen
                    writer.pad(1)
                    writer.short(1) // buttons_len: one 4-byte unit
                    writer.int(base); writer.int(0); writer.int(locked); writer.int(effective)
                    writer.byte(0); writer.byte(0); writer.byte(0); writer.byte(0) // group
                    writer.int(XInput2.xi2ButtonMask(buttonMask))
                }
            }
            XInput2.MINOR_WARP_POINTER -> {
                val src = reader.int()
                val dst = reader.int()
                val srcX = reader.int() shr 16
                val srcY = reader.int() shr 16
                val srcW = reader.short()
                val srcH = reader.short()
                val dstX = reader.int() shr 16
                val dstY = reader.int() shr 16
                val deviceId = reader.short()
                reader.short() // pad
                if (!xi2IsPointer(deviceId)) return error(XInput2.ERR_BAD_DEVICE, major, deviceId, minor)
                warpPointer(src, dst, srcX, srcY, srcW, srcH, dstX, dstY, major, minor)
            }
            XInput2.MINOR_CHANGE_CURSOR -> {
                // XIDefineCursor / XIUndefineCursor are libXi wrappers for this
                // request (cursor None = undefine), which is what GDK calls.
                val win = reader.int()
                val cursor = reader.int()
                val deviceId = reader.short()
                reader.short() // pad
                val window = document.window(win) ?: return error(X11.ERR_BAD_WINDOW, major, win, minor)
                if (!xi2IsPointer(deviceId)) return error(XInput2.ERR_BAD_DEVICE, major, deviceId, minor)
                if (cursor != 0 && document.cursor(cursor) == null) {
                    return error(X11.ERR_BAD_CURSOR, major, cursor, minor)
                }
                // One pointer, so a per-device cursor is the window cursor.
                window.cursor = cursor
            }
            XInput2.MINOR_SET_CLIENT_POINTER -> {
                reader.int() // window (unused: the client pointer is per-client)
                val deviceId = reader.short()
                reader.short() // pad
                if (XInput2.device(deviceId) == null) {
                    return error(XInput2.ERR_BAD_DEVICE_ID, major, deviceId, minor)
                }
                xi2ClientPointer = deviceId
            }
            XInput2.MINOR_GET_CLIENT_POINTER -> {
                reader.int() // window
                reply(major, dataByte = minor) { writer ->
                    writer.byte(1) // set: we always have a client pointer
                    writer.pad(1)
                    writer.short(xi2ClientPointer)
                    writer.pad(20)
                }
            }
            XInput2.MINOR_SET_FOCUS -> {
                val window = reader.int()
                reader.int() // time
                val deviceId = reader.short()
                reader.short() // pad
                if (!xi2IsKeyboard(deviceId)) return error(XInput2.ERR_BAD_DEVICE, major, deviceId, minor)
                // XI2 has no revert-to field; X keeps the focus until it is set
                // again, which is RevertToNone semantics.
                val old = document.setInputFocus(window, XDocument.FOCUS_NONE)
                if (old == null) return error(X11.ERR_BAD_WINDOW, major, window, minor)
                sendFocusChange(old, window)
            }
            XInput2.MINOR_GET_FOCUS -> {
                val deviceId = reader.short()
                reader.short() // pad
                if (!xi2IsKeyboard(deviceId)) return error(XInput2.ERR_BAD_DEVICE, major, deviceId, minor)
                reply(major, dataByte = minor) { writer ->
                    writer.int(document.focus)
                    writer.pad(20)
                }
            }
            XInput2.MINOR_GRAB_DEVICE -> {
                val grabWindow = reader.int()
                reader.int() // time
                val cursor = reader.int()
                val deviceId = reader.short()
                reader.byte() // grab_mode
                reader.byte() // paired_device_mode
                val ownerEvents = reader.byte() != 0
                reader.byte() // pad
                val maskLen = reader.short()
                val mask = reader.bytes(maskLen * 4)
                if (document.window(grabWindow) == null) {
                    return error(X11.ERR_BAD_WINDOW, major, grabWindow, minor)
                }
                val device = XInput2.device(deviceId)
                    ?: return error(XInput2.ERR_BAD_DEVICE_ID, major, deviceId, minor)
                if (cursor != 0 && document.cursor(cursor) == null) {
                    return error(X11.ERR_BAD_CURSOR, major, cursor, minor)
                }
                // The grab is installed in the shared document so every other
                // client stops receiving these events (X's grab semantics are
                // server-wide), but with an EMPTY core mask: GDK translates core
                // events through the same XI2 translator, so a permissive core
                // mask would hand it a second copy of every click it already gets
                // as an XI2 event. The XI2 mask lives in [xi2Grab] and filters the
                // XI2 delivery path instead.
                val status = if (device.pointer) {
                    val taken = !document.grabPointer(
                        PointerGrab(grabWindow, ownerEvents, 0, 0, cursor, this),
                    )
                    if (taken) XInput2.GRAB_ALREADY_GRABBED else XInput2.GRAB_SUCCESS
                } else {
                    val taken = !document.grabKeyboard(KeyboardGrab(grabWindow, ownerEvents, this))
                    if (taken) XInput2.GRAB_ALREADY_GRABBED else XInput2.GRAB_SUCCESS
                }
                if (status == XInput2.GRAB_SUCCESS) {
                    xi2Grab = Xi2Grab(deviceId, grabWindow, ownerEvents, mask, keyboard = !device.pointer)
                }
                reply(major, dataByte = minor) { writer ->
                    writer.byte(status)
                    writer.pad(23)
                }
            }
            XInput2.MINOR_UNGRAB_DEVICE -> {
                reader.int() // time
                val deviceId = reader.short()
                reader.short() // pad
                val device = XInput2.device(deviceId)
                    ?: return error(XInput2.ERR_BAD_DEVICE_ID, major, deviceId, minor)
                if (device.pointer) document.ungrabPointer(this) else document.ungrabKeyboard(this)
                if (xi2Grab?.deviceId == deviceId) xi2Grab = null
            }
            // We never freeze a device, so there is nothing to allow (the same
            // rule the core AllowEvents follows).
            XInput2.MINOR_ALLOW_EVENTS -> Unit
            XInput2.MINOR_PASSIVE_GRAB_DEVICE -> {
                reader.int() // time
                val grabWindow = reader.int()
                val cursor = reader.int()
                val detail = reader.int()
                val deviceId = reader.short()
                val numModifiers = reader.short()
                val maskLen = reader.short()
                val grabType = reader.byte()
                reader.byte() // grab_mode
                reader.byte() // paired_device_mode
                val ownerEvents = reader.byte() != 0
                reader.short() // pad
                val mask = reader.bytes(maskLen * 4)
                val modifiers = List(numModifiers) { reader.int() }
                if (document.window(grabWindow) == null) {
                    return error(X11.ERR_BAD_WINDOW, major, grabWindow, minor)
                }
                if (!XInput2.isKnownDevice(deviceId)) {
                    return error(XInput2.ERR_BAD_DEVICE, major, deviceId, minor)
                }
                // Button and keycode grabs map onto the core passive-grab table,
                // which is where our activation logic already lives. Enter/FocusIn
                // and touch grabs are not modelled - refused rather than accepted
                // and ignored.
                when (grabType) {
                    XInput2.GRAB_TYPE_BUTTON, XInput2.GRAB_TYPE_KEYCODE -> {
                        val coreMask = xi2MaskToCore(mask)
                        modifiers.forEach { modifier ->
                            document.addPassiveGrab(
                                PassiveGrab(
                                    window = grabWindow,
                                    eventMask = coreMask,
                                    detail = detail,
                                    modifiers = modifier,
                                    isKey = grabType == XInput2.GRAB_TYPE_KEYCODE,
                                    connection = this,
                                    ownerEvents = ownerEvents,
                                    confineTo = 0,
                                    cursor = cursor,
                                ),
                            )
                        }
                        if (modifiers.isEmpty()) {
                            document.addPassiveGrab(
                                PassiveGrab(
                                    window = grabWindow,
                                    eventMask = xi2MaskToCore(mask),
                                    detail = detail,
                                    modifiers = PassiveGrab.ANY_MODIFIER,
                                    isKey = grabType == XInput2.GRAB_TYPE_KEYCODE,
                                    connection = this,
                                    ownerEvents = ownerEvents,
                                    confineTo = 0,
                                    cursor = cursor,
                                ),
                            )
                        }
                    }
                    else -> {
                        trace.recordUnimplemented(major, minor)
                        return error(X11.ERR_BAD_REQUEST, major, grabType, minor)
                    }
                }
                // No modifier conflicts to report: num_modifiers = 0.
                reply(major, dataByte = minor) { writer ->
                    writer.short(0)
                    writer.pad(22)
                }
            }
            XInput2.MINOR_PASSIVE_UNGRAB_DEVICE -> {
                val grabWindow = reader.int()
                val detail = reader.int()
                reader.short() // deviceid
                val numModifiers = reader.short()
                val grabType = reader.byte()
                reader.byte() // pad0
                reader.short() // pad1
                val modifiers = List(numModifiers) { reader.int() }
                val isKey = grabType == XInput2.GRAB_TYPE_KEYCODE
                if (modifiers.isEmpty()) {
                    document.removePassiveGrab(grabWindow, detail, PassiveGrab.ANY_MODIFIER, isKey)
                } else {
                    modifiers.forEach { document.removePassiveGrab(grabWindow, detail, it, isKey) }
                }
            }
            XInput2.MINOR_LIST_PROPERTIES -> {
                val deviceId = reader.short()
                reader.short() // pad
                if (!XInput2.isKnownDevice(deviceId)) {
                    return error(XInput2.ERR_BAD_DEVICE_ID, major, deviceId, minor)
                }
                val atoms = xi2Properties[deviceId]?.keys?.toList() ?: emptyList()
                reply(major, dataByte = minor, extraLength = atoms.size * 4) { writer ->
                    writer.short(atoms.size)
                    writer.pad(22)
                    atoms.forEach { writer.int(it) }
                }
            }
            XInput2.MINOR_CHANGE_PROPERTY -> {
                val deviceId = reader.short()
                val mode = reader.byte()
                val format = reader.byte()
                val property = reader.int()
                val type = reader.int()
                val numItems = reader.int()
                if (!XInput2.isKnownDevice(deviceId)) {
                    return error(XInput2.ERR_BAD_DEVICE_ID, major, deviceId, minor)
                }
                val elementSize = when (format) {
                    8 -> 1; 16 -> 2; 32 -> 4
                    else -> return error(X11.ERR_BAD_VALUE, major, format, minor)
                }
                if (mode !in 0..2) return error(X11.ERR_BAD_VALUE, major, mode, minor)
                val start = 20
                val end = (start + numItems * elementSize).coerceAtMost(request.size)
                val incoming = if (end > start) request.copyOfRange(start, end) else ByteArray(0)
                val store = xi2Properties.getOrPut(deviceId) { mutableMapOf() }
                val existing = store[property]
                val data = when {
                    mode == 0 || existing == null || existing.format != format -> incoming // Replace
                    mode == 1 -> incoming + existing.data // Prepend: the new items come first
                    else -> existing.data + incoming // Append: the new items go last
                }
                store[property] = Xi2Property(type, format, data)
            }
            XInput2.MINOR_DELETE_PROPERTY -> {
                val deviceId = reader.short()
                reader.short() // pad0
                val property = reader.int()
                if (!XInput2.isKnownDevice(deviceId)) {
                    return error(XInput2.ERR_BAD_DEVICE_ID, major, deviceId, minor)
                }
                xi2Properties[deviceId]?.remove(property)
            }
            XInput2.MINOR_GET_PROPERTY -> {
                val deviceId = reader.short()
                val delete = reader.byte()
                reader.byte() // pad0
                val property = reader.int()
                reader.int() // type (AnyPropertyType or a specific one)
                reader.int() // offset
                val length = reader.int()
                if (!XInput2.isKnownDevice(deviceId)) {
                    return error(XInput2.ERR_BAD_DEVICE_ID, major, deviceId, minor)
                }
                val stored = xi2Properties[deviceId]?.get(property)
                if (delete != 0) xi2Properties[deviceId]?.remove(property)
                if (stored == null) {
                    // An absent property is not an error: type None, zero items.
                    // This is what GDK's device-property probes expect.
                    reply(major, dataByte = minor) { writer ->
                        writer.int(0) // type: None
                        writer.int(0) // bytes_after
                        writer.int(0) // num_items
                        writer.byte(0) // format
                        writer.pad(11) // pad0(1) + pad1(2) + pad2(4) + pad3(4)
                    }
                } else {
                    val elementSize = when (stored.format) { 16 -> 2; 32 -> 4; else -> 1 }
                    val items = stored.data.size / elementSize
                    val wanted = if (length <= 0) items else minOf(items, length)
                    val bytes = wanted * elementSize
                    val data = stored.data.copyOfRange(0, bytes.coerceAtMost(stored.data.size))
                    reply(major, dataByte = minor, extraLength = X11.pad4(data.size)) { writer ->
                        writer.int(stored.type)
                        writer.int((items - wanted) * elementSize) // bytes_after
                        writer.int(wanted)
                        writer.byte(stored.format)
                        writer.pad(11) // pad0(1) + pad1(2) + pad2(4) + pad3(4)
                        writer.bytes(data)
                        writer.pad(X11.padded(data.size))
                    }
                }
            }
            XInput2.MINOR_CHANGE_HIERARCHY, XInput2.MINOR_BARRIER_RELEASE_POINTER -> {
                // Refused on purpose, not accepted-and-ignored: our device set is
                // static (no hotplug to model) and we have no pointer barriers (we
                // never send barrier events, so no client should be releasing one).
                // Answering Success here would be a lie a client could act on, and
                // the trace names the request so a device log says what happened.
                trace.recordUnimplemented(major, minor)
                error(X11.ERR_BAD_REQUEST, major, 0, minor)
            }
            else -> {
                trace.recordUnimplemented(major, minor)
                error(X11.ERR_BAD_REQUEST, major, 0, minor)
            }
        }
    }

    /** Pointer-ish device ids: the masters, the slave pointer and the pseudo ids. */
    private fun xi2IsPointer(deviceId: Int): Boolean = when (deviceId) {
        XInput2.ALL_DEVICES, XInput2.ALL_MASTER_DEVICES, XInput2.MASTER_POINTER, XInput2.SLAVE_POINTER -> true
        else -> false
    }

    private fun xi2IsKeyboard(deviceId: Int): Boolean = when (deviceId) {
        XInput2.ALL_DEVICES, XInput2.ALL_MASTER_DEVICES, XInput2.MASTER_KEYBOARD, XInput2.SLAVE_KEYBOARD -> true
        else -> false
    }

    /** The child of [ancestorId] on the path to [windowId] (0 when there is none). */
    private fun childOnPath(ancestorId: Int, windowId: Int?): Int {
        var current = windowId ?: return 0
        while (true) {
            if (current == ancestorId) return 0
            val parent = document.window(current)?.parent ?: return 0
            if (parent == ancestorId) return current
            current = parent
        }
    }

    /**
     * Maps an XI2 event mask onto the core mask our grab machinery stores, for
     * passive grabs. Only the button/key bits matter there.
     */
    private fun xi2MaskToCore(mask: ByteArray): Int {
        var core = 0
        if (XInput2.maskIsSet(mask, XInput2.EV_BUTTON_PRESS)) core = core or EventMask.BUTTON_PRESS
        if (XInput2.maskIsSet(mask, XInput2.EV_BUTTON_RELEASE)) core = core or EventMask.BUTTON_RELEASE
        if (XInput2.maskIsSet(mask, XInput2.EV_KEY_PRESS)) core = core or EventMask.KEY_PRESS
        if (XInput2.maskIsSet(mask, XInput2.EV_KEY_RELEASE)) core = core or EventMask.KEY_RELEASE
        if (XInput2.maskIsSet(mask, XInput2.EV_MOTION)) core = core or EventMask.POINTER_MOTION
        if (XInput2.maskIsSet(mask, XInput2.EV_ENTER)) core = core or EventMask.ENTER_WINDOW
        if (XInput2.maskIsSet(mask, XInput2.EV_LEAVE)) core = core or EventMask.LEAVE_WINDOW
        if (XInput2.maskIsSet(mask, XInput2.EV_FOCUS_IN) || XInput2.maskIsSet(mask, XInput2.EV_FOCUS_OUT)) {
            core = core or EventMask.FOCUS_CHANGE
        }
        return core
    }

    /**
     * Delivers one XI2 DeviceEvent (key/button/motion) to this connection when it
     * selected it. Selections are per-connection, so only the client that asked
     * receives anything - and the window that gets it is the first one up the tree
     * from the pointer/focus window that selected this event type, which is how
     * GTK (selecting on its toplevel) receives events from its children.
     */
    private fun xi2DeviceEvent(
        evtype: Int,
        detail: Int,
        deviceId: Int,
        sourceId: Int,
        targetId: Int,
        rootX: Int,
        rootY: Int,
        flags: Int = 0,
    ) {
        val grab = xi2Grab
        if (grab != null) {
            // While this connection holds a device grab the event goes to the grab
            // window, filtered by the grab's own mask; owner-events adds the normal
            // delivery to the window under the pointer.
            if (XInput2.selectionCovers(grab.deviceId, deviceId) && XInput2.maskIsSet(grab.mask, evtype)) {
                xi2EmitDeviceEvent(evtype, detail, deviceId, sourceId, grab.window, childOnPath(grab.window, targetId), rootX, rootY, flags)
            }
            if (grab.ownerEvents && targetId != grab.window) {
                val receiver = xi2Receiver(targetId, deviceId, evtype) ?: return
                xi2EmitDeviceEvent(evtype, detail, deviceId, sourceId, receiver.first, receiver.second, rootX, rootY, flags)
            }
            return
        }
        val receiver = xi2Receiver(targetId, deviceId, evtype) ?: return
        xi2EmitDeviceEvent(evtype, detail, deviceId, sourceId, receiver.first, receiver.second, rootX, rootY, flags)
    }

    /** The window that receives [evtype] from [deviceId], plus the child on the path. */
    private fun xi2Receiver(targetId: Int, deviceId: Int, evtype: Int): Pair<Int, Int>? {
        var current = targetId
        var child = 0
        while (true) {
            if (xi2Selections.wants(current, deviceId, evtype)) return current to child
            val parent = document.window(current)?.parent ?: return null
            child = current
            current = parent
        }
    }

    private fun xi2EmitDeviceEvent(
        evtype: Int,
        detail: Int,
        deviceId: Int,
        sourceId: Int,
        receiverId: Int,
        childId: Int,
        rootX: Int,
        rootY: Int,
        flags: Int,
    ) {
        val receiver = document.window(receiverId) ?: return
        val (base, locked, effective) = XInput2.modifierParts(keyStateMask)
        val receiverOrigin = document.absoluteOrigin(receiver)
        val eventX = rootX - receiverOrigin[0]
        val eventY = rootY - receiverOrigin[1]
        deliverGenericEvent(XInput2.MAJOR_OPCODE, evtype) { writer ->
            with(XInput2) {
                writer.short(deviceId)
                writer.int(System.currentTimeMillis().toInt())
                writer.int(detail)
                writer.int(document.rootId)
                writer.int(receiverId)
                writer.int(childId)
                // ---- 32-byte boundary ----
                writer.fp1616(rootX)
                writer.fp1616(rootY)
                writer.fp1616(eventX)
                writer.fp1616(eventY)
                writer.short(1) // buttons_len: one 4-byte unit covers buttons 1-32
                writer.short(0) // valuators_len: position travels in root_x/event_x
                writer.short(sourceId)
                writer.short(0) // pad
                writer.int(flags)
                writer.int(base); writer.int(0); writer.int(locked); writer.int(effective)
                writer.byte(0); writer.byte(0); writer.byte(0); writer.byte(0) // group
                writer.int(xi2ButtonMask(buttonMask))
            }
        }
    }

    /** XI2 Enter/Leave/FocusIn/FocusOut (`xXIEnterEvent` - all four share it). */
    private fun xi2EmitCrossing(
        evtype: Int,
        detail: Int,
        deviceId: Int,
        sourceId: Int,
        receiverId: Int,
        childId: Int,
        rootX: Int,
        rootY: Int,
        mode: Int = XInput2.NOTIFY_NORMAL,
    ) {
        val receiver = document.window(receiverId) ?: return
        val (base, locked, effective) = XInput2.modifierParts(keyStateMask)
        val focusWindow = resolveFocusWindow()
        val hasFocus = focusWindow != null &&
            (focusWindow == receiverId || isDescendantOf(receiverId, focusWindow))
        deliverGenericEvent(XInput2.MAJOR_OPCODE, evtype) { writer ->
            with(XInput2) {
                writer.short(deviceId)
                writer.int(System.currentTimeMillis().toInt())
                writer.short(sourceId)
                writer.byte(mode)
                writer.byte(detail)
                writer.int(document.rootId)
                writer.int(receiverId)
                writer.int(childId)
                // ---- 32-byte boundary ----
                writer.fp1616(rootX)
                writer.fp1616(rootY)
                writer.fp1616(rootX - document.absoluteOrigin(receiver)[0])
                writer.fp1616(rootY - document.absoluteOrigin(receiver)[1])
                writer.byte(1) // same_screen
                writer.byte(if (hasFocus) 1 else 0) // focus
                writer.short(1) // buttons_len
                writer.int(base); writer.int(0); writer.int(locked); writer.int(effective)
                writer.byte(0); writer.byte(0); writer.byte(0); writer.byte(0) // group
                writer.int(xi2ButtonMask(buttonMask))
            }
        }
    }

    /**
     * Crossing detail, the way X names it: Inferior when the event window is below
     * the other one in the tree, Ancestor when it is above, Nonlinear otherwise
     * (which is every crossing between two GTK toplevels).
     */
    private fun xi2CrossingDetail(eventWindow: Int, otherWindow: Int?): Int = when {
        otherWindow == null || otherWindow == eventWindow -> XInput2.NOTIFY_NORMAL
        isDescendantOf(eventWindow, otherWindow) -> XInput2.NOTIFY_ANCESTOR
        isDescendantOf(otherWindow, eventWindow) -> XInput2.NOTIFY_INFERIOR
        else -> XInput2.NOTIFY_NONLINEAR
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
        val payload = bodyWriter.toByteArray()
        val units = X11.pad4(extraLength) / 4
        // The client reads exactly 32 + units*4 bytes for this reply, so the body
        // must be exactly 24 + units*4 bytes. One byte either way desynchronises
        // the connection: every later message is parsed at the wrong offset and
        // the client reads the tail of one reply as the head of the next. GTK
        // treats that as a fatal X error and _exit(1)s, with nothing on stderr -
        // which is how a wrong ListExtensions padding killed Firefox. Force the
        // wire size to the declared one and report the mismatch instead.
        val declared = 24 + units * 4
        val writer = XWriter(littleEndian)
        writer.byte(1)
        writer.byte(dataByte)
        writer.short(sequence)
        writer.int(units)
        when {
            payload.size == declared -> writer.bytes(payload)
            payload.size < declared -> {
                trace.recordReplyLengthMismatch(opcode, payload.size, declared)
                writer.bytes(payload)
                writer.pad(declared - payload.size)
            }
            else -> {
                trace.recordReplyLengthMismatch(opcode, payload.size, declared)
                writer.bytes(payload.copyOf(declared))
            }
        }
        val out = writer.toByteArray()
        pending.add(if (out.size >= 32) out else out.copyOf(32))
        lastError = X11.ERR_SUCCESS
    }

    fun error(code: Int, opcode: Int, badValue: Int, minor: Int = 0) {
        lastError = code
        trace.recordErrorSent()
        val writer = XWriter(littleEndian)
        writer.byte(0)
        writer.byte(code)
        writer.short(sequence)
        writer.int(badValue)
        writer.short(minor) // minorOpcode (extension errors)
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
            // Expose rects are WINDOW-relative (X spec): the full window.
            X11.EV_EXPOSE -> exposeIfWanted(windowId, 0, 0, window.width, window.height)
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

    /**
     * Exposes a newly-viewable window and its already-mapped descendants, each
     * through its OWN connection (the mapper may be a WM mapping other clients'
     * windows). Expose rects are window-relative: the full window, count 0.
     */
    private fun exposeSubtree(windowId: Int) {
        val window = document.window(windowId) ?: return
        document.ownerOf(windowId)?.sendEvent(windowId, X11.EV_EXPOSE)
        window.children.toList().forEach { child ->
            if (document.window(child)?.mapped == true) exposeSubtree(child)
        }
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
        // XI2 focus events go to the connection that selected them (GDK selects
        // FocusIn/FocusOut on its toplevels), not to the window's owner.
        xi2Focus(XInput2.EV_FOCUS_OUT, oldFocus, newFocus)
        xi2Focus(XInput2.EV_FOCUS_IN, newFocus, oldFocus)
    }

    /** XI2 FocusIn/FocusOut delivery, mirroring [sendFocusChange]'s core pair. */
    private fun xi2Focus(evtype: Int, windowId: Int, other: Int) {
        if (windowId == XDocument.FOCUS_NONE || windowId == XDocument.FOCUS_POINTER_ROOT) return
        if (windowId == other) return
        val deviceId = XInput2.MASTER_KEYBOARD
        val sourceId = XInput2.SLAVE_KEYBOARD
        if (!xi2Selections.wantsAnywhere(deviceId, evtype)) return
        val receiver = xi2Receiver(windowId, deviceId, evtype) ?: return
        xi2EmitCrossing(evtype, xi2CrossingDetail(receiver.first, other), deviceId, sourceId,
            receiver.first, receiver.second, rootPointerX, rootPointerY)
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
        // A warp is a real pointer move: X promises MotionNotify for it, and XI2
        // clients (GDK) get the same promise as XI_Motion.
        xi2DeviceEvent(XInput2.EV_MOTION, 0, XInput2.MASTER_POINTER, XInput2.SLAVE_POINTER,
            target?.id ?: document.rootId, rootPointerX, rootPointerY)
        if (target != null && target.eventMask and EventMask.POINTER_MOTION != 0) {
            queue(pointerEvent(X11.EV_MOTION_NOTIFY, 0, target, rootPointerX, rootPointerY))
        }
        flushPending()
        document.onWarp?.invoke(rootPointerX, rootPointerY, this)
    }

    /**
     * The WarpPointer rule, shared by the core request and XIWarpPointer: when a
     * source window is given the warp only happens if the pointer is inside the
     * (optionally shrunk) source rectangle, and X says it then silently does
     * nothing. Coordinates are INT16 for core and FP1616 for XI2 - the caller
     * converts.
     */
    private fun warpPointer(
        src: Int,
        dst: Int,
        srcX: Int,
        srcY: Int,
        srcWidth: Int,
        srcHeight: Int,
        dstX: Int,
        dstY: Int,
        opcode: Int,
        minor: Int = 0,
    ) {
        if (src != 0) {
            val s = document.window(src) ?: return error(X11.ERR_BAD_WINDOW, opcode, src, minor)
            val w = if (srcWidth == 0) s.width else srcWidth
            val h = if (srcHeight == 0) s.height else srcHeight
            val inside = rootPointerX in s.x + srcX until s.x + srcX + w &&
                rootPointerY in s.y + srcY until s.y + srcY + h
            if (!inside) return // X: the warp silently does nothing
        }
        val base = if (dst != 0) {
            document.window(dst) ?: return error(X11.ERR_BAD_WINDOW, opcode, dst, minor)
        } else null
        val warpBase = base?.let { document.absoluteOrigin(it) } ?: intArrayOf(0, 0)
        warpMove(warpBase[0] + dstX, warpBase[1] + dstY)
    }

    /** Shared pointer move: confine-to-grab clamping, Enter/Leave tracking, MotionNotify. */
    private fun movePointer(x: Int, y: Int): Pair<Int, Int> {
        var nx = x
        var ny = y
        document.pointerGrab?.takeIf { it.connection === this }?.let { grab ->
            if (grab.confineTo != 0) {
                document.window(grab.confineTo)?.let {
                    val o = document.absoluteOrigin(it)
                    nx = nx.coerceIn(o[0], maxOf(o[0], o[0] + it.width - 1))
                    ny = ny.coerceIn(o[1], maxOf(o[1], o[1] + it.height - 1))
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
            xi2Crossing(XInput2.EV_LEAVE, previous, current, x, y)
        }
        if (current != null) {
            val window = document.window(current)
            if (window != null && window.eventMask and EventMask.ENTER_WINDOW != 0) {
                queue(enterLeaveEvent(X11.EV_ENTER_NOTIFY, 3, window, 0, x, y))
            }
            xi2Crossing(XInput2.EV_ENTER, current, previous, x, y)
        }
        lastPointerWindow = current
        flushPending()
    }

    /**
     * XI2 Enter/Leave to whoever selected it, up the tree from the window the
     * pointer is entering or leaving. [other] is the window on the other side of
     * the crossing, which is what the notify detail is computed from.
     */
    private fun xi2Crossing(evtype: Int, windowId: Int, other: Int?, x: Int, y: Int) {
        val deviceId = XInput2.MASTER_POINTER
        val sourceId = XInput2.SLAVE_POINTER
        val grab = xi2Grab
        if (grab != null && !grab.keyboard) {
            if (XInput2.selectionCovers(grab.deviceId, deviceId) && XInput2.maskIsSet(grab.mask, evtype)) {
                xi2EmitCrossing(evtype, xi2CrossingDetail(grab.window, other), deviceId, sourceId,
                    grab.window, childOnPath(grab.window, windowId), x, y, mode = XInput2.NOTIFY_NORMAL)
            }
            return
        }
        val receiver = xi2Receiver(windowId, deviceId, evtype) ?: return
        xi2EmitCrossing(evtype, xi2CrossingDetail(receiver.first, other), deviceId, sourceId,
            receiver.first, receiver.second, x, y)
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
        val crossingOrigin = document.absoluteOrigin(target)
        writer.short(x - crossingOrigin[0])
        writer.short(y - crossingOrigin[1])
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
            xi2DeviceEvent(XInput2.EV_MOTION, 0, XInput2.MASTER_POINTER, XInput2.SLAVE_POINTER,
                under?.id ?: document.rootId, nx, ny)
            flushPending()
            return
        }
        // XI2 delivery is independent of the core window masks: a client that
        // selected XI2 events (GDK does, and selects no core masks at all) must
        // still get them, so this runs before the core mask check below.
        xi2DeviceEvent(XInput2.EV_MOTION, 0, XInput2.MASTER_POINTER, XInput2.SLAVE_POINTER,
            under?.id ?: document.rootId, nx, ny)
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
            xi2DeviceEvent(xi2ButtonEventType(pressed), button, XInput2.MASTER_POINTER, XInput2.SLAVE_POINTER,
                under?.id ?: document.rootId, nx, ny)
            flushPending()
            if (!pressed) {
                document.ungrabImplicitPointer(this)
                if (xi2Grab?.keyboard == false) xi2Grab = null
            }
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
                xi2DeviceEvent(XInput2.EV_BUTTON_PRESS, button, XInput2.MASTER_POINTER, XInput2.SLAVE_POINTER,
                    under?.id ?: document.rootId, nx, ny)
                flushPending()
                return
            }
        }
        xi2DeviceEvent(xi2ButtonEventType(pressed), button, XInput2.MASTER_POINTER, XInput2.SLAVE_POINTER,
            under?.id ?: document.rootId, nx, ny)
        val target = under ?: return
        val code = if (pressed) X11.EV_BUTTON_PRESS else X11.EV_BUTTON_RELEASE
        if (pressed && target.eventMask and EventMask.BUTTON_PRESS == 0) return
        if (!pressed && target.eventMask and EventMask.BUTTON_RELEASE == 0) return
        queue(pointerEvent(code, button, target, nx, ny))
        flushPending()
    }

    /** XI2 splits press and release into two event types, unlike core's detail byte. */
    private fun xi2ButtonEventType(pressed: Boolean): Int =
        if (pressed) XInput2.EV_BUTTON_PRESS else XInput2.EV_BUTTON_RELEASE

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
        // XI2 first: key events reach the client that selected them on the focus
        // window (or an ancestor of it), independent of any core mask.
        xi2DeviceEvent(
            if (pressed) XInput2.EV_KEY_PRESS else XInput2.EV_KEY_RELEASE,
            keycode, XInput2.MASTER_KEYBOARD, XInput2.SLAVE_KEYBOARD, targetId,
            rootPointerX, rootPointerY,
        )
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
        val targetOrigin = document.absoluteOrigin(target)
        writer.short(x - targetOrigin[0])
        writer.short(y - targetOrigin[1])
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

        /**
         * name -> [majorOpcode, firstEvent, firstError]; QueryExtension answers
         * from here and ListExtensions advertises these names. Names keep their
         * canonical spelling (Xorg's own), because `xdpyinfo` prints them; the
         * lookup is case-insensitive through [extension] since clients are not
         * consistent about it.
         */
        val EXTENSIONS: Map<String, IntArray> = mapOf(
            "RENDER" to intArrayOf(XRender.MAJOR_OPCODE, 0, XRender.FIRST_ERROR),
            "XTEST" to intArrayOf(X11.EXT_XTEST_OPCODE, 0, 0),
            XBigRequests.NAME to intArrayOf(XBigRequests.MAJOR_OPCODE, 0, 0),
            XGenericEvent.NAME to intArrayOf(XGenericEvent.MAJOR_OPCODE, 0, 0),
            XInput2.NAME to intArrayOf(XInput2.MAJOR_OPCODE, XInput2.FIRST_EVENT, XInput2.FIRST_ERROR),
            XFixes.NAME to intArrayOf(XFixes.MAJOR_OPCODE, XFixes.FIRST_EVENT, XFixes.FIRST_ERROR),
            XSync.NAME to intArrayOf(XSync.MAJOR_OPCODE, XSync.FIRST_EVENT, XSync.FIRST_ERROR),
        )

        private val EXTENSIONS_BY_UPPER = EXTENSIONS.keys.associateBy { it.uppercase() }

        /** Case-insensitive [EXTENSIONS] lookup; null when we do not have it. */
        fun extension(name: String): IntArray? = EXTENSIONS[EXTENSIONS_BY_UPPER[name.uppercase()]]
    }
}
