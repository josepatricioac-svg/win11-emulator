package dev.win11we.shell.x11

/**
 * Where drawing requests end up. An interface so the protocol core has no Android
 * dependency: the Compose/Bitmap renderer implements it (D1b), tests record into it.
 */
interface XSink {
    fun fillRect(x: Int, y: Int, width: Int, height: Int, pixel: Int)
    fun putImage(x: Int, y: Int, width: Int, height: Int, depth: Int, data: ByteArray)
    fun imageText(x: Int, y: Int, text: String, foreground: Int, background: Int?)
    fun clearArea(x: Int, y: Int, width: Int, height: Int)
}

/** Records what a client drew. */
class RecordingSink : XSink {
    val fills = mutableListOf<String>()
    val images = mutableListOf<String>()
    val texts = mutableListOf<String>()
    val clears = mutableListOf<String>()
    override fun fillRect(x: Int, y: Int, width: Int, height: Int, pixel: Int) {
        fills.add("$x,$y ${width}x$height #${pixel.toString(16)}")
    }

    override fun putImage(x: Int, y: Int, width: Int, height: Int, depth: Int, data: ByteArray) {
        images.add("$x,$y ${width}x$height depth$depth ${data.size}B")
    }

    override fun imageText(x: Int, y: Int, text: String, foreground: Int, background: Int?) {
        texts.add("$x,$y \"$text\"")
    }

    override fun clearArea(x: Int, y: Int, width: Int, height: Int) {
        clears.add("$x,$y ${width}x$height")
    }
}

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
    private val colormaps = HashSet<Int>()
    private val selections = HashMap<Int, Int>()

    private class GcState(var foreground: Int = 0xFF000000.toInt(), var background: Int? = null)

    /**
     * GC values arrive in bit order of the value mask, so they must be consumed
     * accordingly - reading them positionally corrupts every later request.
     */
    private fun applyGcValues(gc: GcState, reader: XReader, mask: Int) {
        if (mask and 0x00000004 != 0) gc.foreground = reader.int() // GCForeground
        if (mask and 0x00000008 != 0) gc.background = reader.int() // GCBackground
        if (mask and 0x00000001 != 0) reader.int() // GCFunction
        if (mask and 0x00000002 != 0) reader.int() // GCPlaneMask
        if (mask and 0x00000010 != 0) reader.int() // GCSubwindowMode
        if (mask and 0x00000020 != 0) reader.int() // GCGraphicsExposures
        if (mask and 0x00000040 != 0) { reader.int(); reader.int() } // GCClipOrigin
        if (mask and 0x00000080 != 0) { reader.int(); reader.int() } // GCClipMask
        if (mask and 0x00000100 != 0) reader.int() // GCDashOffset
        if (mask and 0x00000200 != 0) reader.int() // GCDashList
        if (mask and 0x00000400 != 0) reader.int() // GCArcMode
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

    fun drainOutbox(): List<ByteArray> = outbox.toList().also { outbox.clear() }
    fun outboxBytes(): Int = outbox.sumOf { it.size }

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
        val additional = X11.pad4(vendor.size) + formatsLength + screenLength

        writer.byte(1)
        writer.pad(1)
        writer.short(X11.PROTOCOL_MAJOR)
        writer.short(X11.PROTOCOL_MINOR)
        writer.short(additional / 4)
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
        writer.short((bytes.size + 8) / 4)
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
        pending.forEach { outbox.addLast(it) }
        pending.clear()
    }

    private fun dispatch(opcode: Int, data: Int, reader: XReader, request: ByteArray) {
        when (opcode) {
            X11.REQ_CREATE_WINDOW -> createWindow(opcode, reader)
            X11.REQ_CHANGE_WINDOW_ATTRIBUTES -> changeWindowAttributes(opcode, reader)
            X11.REQ_GET_WINDOW_ATTRIBUTES -> getWindowAttributes(opcode, reader)
            X11.REQ_DESTROY_WINDOW -> {
                val id = reader.int()
                val parent = document.window(id)?.parent
                if (document.destroyWindow(id)) {
                    notifyStructure(X11.EV_DESTROY_NOTIFY, parent ?: document.rootId, id)
                } else {
                    error(X11.ERR_BAD_WINDOW, opcode, id)
                }
            }
            X11.REQ_DESTROY_SUBWINDOWS -> {
                val id = reader.int()
                document.window(id)?.children?.toList()?.forEach { document.destroyWindow(it) }
            }
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
                    if (document.mapWindow(target)) notifyStructure(X11.EV_MAP_NOTIFY, window.id, target)
                }
            }
            X11.REQ_UNMAP_WINDOW -> {
                val id = reader.int()
                val parent = document.window(id)?.parent ?: document.rootId
                if (document.unmapWindow(id)) {
                    notifyStructure(X11.EV_UNMAP_NOTIFY, parent, id)
                } else {
                    error(X11.ERR_BAD_WINDOW, opcode, id)
                }
            }
            X11.REQ_CONFIGURE_WINDOW -> configureWindow(opcode, reader)
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
            X11.REQ_SET_SELECTION_OWNER -> {
                reader.skip(4)
                selections[reader.int()] = reader.int()
            }
            X11.REQ_GET_SELECTION_OWNER -> {
                val selection = reader.int()
                reply(opcode) { writer ->
                    writer.int(selections[selection] ?: 0)
                    writer.pad(20)
                }
            }
            X11.REQ_CONVERT_SELECTION -> {
                val selection = reader.int()
                val target = reader.int()
                val property = reader.int()
                // We own no selections yet: answer None so clients fall back instead of hanging.
                queue(
                    eventWriter(X11.EV_SELECTION_NOTIFY)
                        .int(0).int(selection).int(target).int(property).int(0).pad(12),
                )
            }
            X11.REQ_SEND_EVENT -> Unit // propagate-to-other-clients is not needed for a single app
            X11.REQ_GRAB_POINTER, X11.REQ_GRAB_KEYBOARD -> reply(opcode) { writer ->
                writer.byte(0) // GrabSuccess
                writer.pad(23)
            }
            X11.REQ_UNGRAB_POINTER, X11.REQ_UNGRAB_KEYBOARD, X11.REQ_BELL -> Unit
            X11.REQ_QUERY_POINTER -> {
                val id = reader.int()
                val target = document.window(id) ?: return error(X11.ERR_BAD_WINDOW, opcode, id)
                val child = document.windowAt(rootPointerX, rootPointerY)?.takeIf { it.id != target.id }
                reply(opcode) { writer ->
                    writer.byte(1) // sameScreen
                    writer.pad(3)
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
            X11.REQ_TRANSLATE_COORDINATES -> {
                val src = reader.int()
                val dst = reader.int()
                val srcX = reader.short()
                val srcY = reader.short()
                val srcWindow = document.window(src) ?: return error(X11.ERR_BAD_WINDOW, opcode, src)
                val dstWindow = document.window(dst) ?: return error(X11.ERR_BAD_WINDOW, opcode, dst)
                val rootX = srcWindow.x + srcX
                val rootY = srcWindow.y + srcY
                reply(opcode) { writer ->
                    writer.byte(1)
                    writer.pad(3)
                    writer.int(0) // child
                    writer.short(rootX - dstWindow.x)
                    writer.short(rootY - dstWindow.y)
                    writer.pad(16)
                }
            }
            X11.REQ_CREATE_GC -> {
                val id = reader.int()
                reader.int() // drawable
                val gc = GcState()
                applyGcValues(gc, reader, reader.int())
                gcs[id] = gc
            }
            X11.REQ_CHANGE_GC -> {
                val id = reader.int()
                reader.skip(4) // valueMask is consumed by applyGcValues below
                val gc = gcs.getOrPut(id) { GcState() }
                val mask = XReader(request, littleEndian, 8).int()
                applyGcValues(gc, XReader(request, littleEndian, 12), mask)
            }
            X11.REQ_FREE_GC -> gcs.remove(reader.int())
            X11.REQ_CREATE_PIXMAP -> {
                val id = reader.int()
                reader.int() // drawable
                val depth = reader.short()
                pixmaps[id] = depth.toInt()
            }
            X11.REQ_FREE_PIXMAP -> pixmaps.remove(reader.int())
            X11.REQ_CREATE_COLORMAP -> colormaps.add(reader.int())
            X11.REQ_FREE_COLORMAP -> colormaps.remove(reader.int())
            X11.REQ_ALLOC_COLOR -> {
                reader.int()
                val red = reader.short()
                val green = reader.short()
                val blue = reader.short()
                reply(opcode) { writer ->
                    writer.short(red); writer.short(green); writer.short(blue)
                    writer.pad(2); writer.int(0); writer.pad(12)
                }
            }
            X11.REQ_CLEAR_AREA -> {
                reader.skip(4)
                val window = reader.int()
                val x = reader.short(); val y = reader.short()
                val width = reader.short(); val height = reader.short()
                sink.clearArea(x, y, width, height)
                exposeIfWanted(window, x, y, width, height)
            }
            X11.REQ_POLY_RECTANGLE -> {
                reader.int() // drawable
                val gc = reader.int()
                val foreground = gcs[gc]?.foreground ?: 0
                val count = (request.size - 12) / 8
                repeat(count) {
                    val x = reader.short(); val y = reader.short()
                    val width = reader.short(); val height = reader.short()
                    sink.fillRect(x, y, width, height, foreground)
                }
            }
            X11.REQ_POLY_RECTANGLE, X11.REQ_POLY_FILL_RECTANGLE -> {
                reader.int()
                val gc = reader.int()
                val foreground = gcs[gc]?.foreground ?: 0
                val count = (request.size - 12) / 8
                repeat(count) {
                    val x = reader.short(); val y = reader.short()
                    val width = reader.short(); val height = reader.short()
                    sink.fillRect(x, y, width, height, foreground)
                }
            }
            X11.REQ_POLY_LINE, X11.REQ_POLY_SEGMENT, X11.REQ_POLY_POINT, X11.REQ_POLY_ARC,
            X11.REQ_POLY_FILL_ARC, X11.REQ_POLY_TEXT8, X11.REQ_POLY_TEXT16, X11.REQ_COPY_AREA,
            X11.REQ_COPY_PLANE, X11.REQ_SET_DASHES, X11.REQ_SET_CLIP_RECTANGLES, X11.REQ_COPY_GC,
            X11.REQ_IMAGE_TEXT16, X11.REQ_QUERY_TEXT_EXTENTS -> Unit // accepted, not rendered yet

            X11.REQ_PUT_IMAGE -> {
                val depth = data
                reader.int() // drawable
                reader.int() // gc
                val width = reader.short()
                val height = reader.short()
                val dstX = reader.short()
                val dstY = reader.short()
                reader.skip(4) // leftPad, unused, pad
                val pixels = request.copyOfRange(24, request.size)
                sink.putImage(dstX, dstY, width, height, depth, pixels)
            }
            X11.REQ_GET_IMAGE -> reply(opcode, extraLength = 0) { writer ->
                writer.byte(24); writer.pad(3); writer.int(0); writer.int(0); writer.pad(20)
            }
            X11.REQ_IMAGE_TEXT8 -> {
                val length = data
                reader.int(); reader.int() // drawable, gc
                val x = reader.short(); val y = reader.short()
                val end = (16 + length).coerceAtMost(request.size)
                val text = String(request.copyOfRange(16, end), Charsets.US_ASCII)
                sink.imageText(x, y, text, gcs.values.firstOrNull()?.foreground ?: -1, null)
            }
            X11.REQ_QUERY_EXTENSION -> reply(opcode) { writer ->
                writer.pad(1); writer.byte(0); writer.byte(0); writer.byte(0)
                writer.short(0); writer.short(0); writer.pad(16)
            }
            X11.REQ_LIST_EXTENSIONS -> reply(opcode, extraLength = 0) { writer ->
                writer.byte(0); writer.pad(23)
            }
            X11.REQ_GET_KEYBOARD_MAPPING -> {
                val firstKeycode = reader.byte()
                val count = reader.byte()
                val perCode = keyboard.keysymsPerKeycode
                reply(opcode, extraLength = count * perCode * 4) { writer ->
                    writer.byte(perCode)
                    writer.pad(23)
                    repeat(count) { index ->
                        repeat(perCode) { slot -> writer.int(keyboard.keysymFor(firstKeycode + index, slot)) }
                    }
                }
            }
            X11.REQ_SET_SCREEN_SAVER, X11.REQ_SET_CLOSE_DOWN_MODE, X11.REQ_NO_OPERATION -> Unit
            X11.REQ_GET_SCREEN_SAVER -> reply(opcode) { writer ->
                writer.short(600); writer.short(600); writer.byte(1); writer.byte(1); writer.pad(18)
            }
            else -> error(X11.ERR_BAD_REQUEST, opcode, 0)
        }
    }

    // --- window requests ---------------------------------------------------

    private fun createWindow(opcode: Int, reader: XReader) {
        val depth = reader.byte()
        reader.skip(3)
        val id = reader.int()
        val parent = reader.int()
        val x = reader.short(); val y = reader.short()
        val width = reader.short(); val height = reader.short()
        val borderWidth = reader.short()
        reader.short() // class
        val visual = reader.int()
        val mask = reader.int()
        var backgroundPixel = 0
        var eventMask = 0
        var overrideRedirect = false
        if (mask and 0x00000002 != 0) backgroundPixel = reader.int()
        if (mask and 0x00000800 != 0) eventMask = reader.int()
        if (mask and 0x00000200 != 0) overrideRedirect = reader.int() != 0
        val window = document.createWindow(id, parent, x, y, width, height, borderWidth, depth, visual)
        if (window == null) return error(X11.ERR_BAD_WINDOW, opcode, id)
        window.backgroundPixel = backgroundPixel
        window.eventMask = eventMask
        window.overrideRedirect = overrideRedirect
        notifyStructure(X11.EV_CREATE_NOTIFY, parent, id)
    }

    private fun changeWindowAttributes(opcode: Int, reader: XReader) {
        val id = reader.int()
        val mask = reader.int()
        val window = document.window(id) ?: return error(X11.ERR_BAD_WINDOW, opcode, id)
        if (mask and 0x00000002 != 0) window.backgroundPixel = reader.int()
        if (mask and 0x00000800 != 0) window.eventMask = reader.int()
        if (mask and 0x00000200 != 0) window.overrideRedirect = reader.int() != 0
    }

    private fun getWindowAttributes(opcode: Int, reader: XReader) {
        val id = reader.int()
        val window = document.window(id) ?: return error(X11.ERR_BAD_WINDOW, opcode, id)
        reply(opcode) { writer ->
            writer.byte(0) // backingStore = NotUseful
            writer.pad(3)
            writer.int(window.visual)
            writer.short(0) // class
            writer.byte(window.depth)
            writer.byte(0) // bitGravity
            writer.byte(0) // winGravity
            writer.int(0) // saveUnder
            writer.byte(if (window.mapped) 2 else 0) // mapState: IsViewable/IsUnmapped
            writer.byte(0) // mapIsInstalled
            writer.int(window.eventMask)
            writer.int(window.doNotPropagateMask)
            writer.byte(if (window.overrideRedirect) 1 else 0)
            writer.byte(0)
            writer.short(0) // colormap
            writer.int(0) // allEventMasks
            writer.int(window.eventMask) // yourEventMask
        }
    }

    private fun configureWindow(opcode: Int, reader: XReader) {
        val id = reader.int()
        val mask = reader.short()
        reader.short()
        val window = document.window(id) ?: return error(X11.ERR_BAD_WINDOW, opcode, id)
        val x = if (mask and 0x0001 != 0) reader.int() else null
        val y = if (mask and 0x0002 != 0) reader.int() else null
        val width = if (mask and 0x0004 != 0) reader.int() else null
        val height = if (mask and 0x0008 != 0) reader.int() else null
        val borderWidth = if (mask and 0x0010 != 0) reader.int() else null
        document.configure(id, x, y, width, height, borderWidth)
        configureNotify(window)
    }

    private fun getGeometry(opcode: Int, reader: XReader) {
        val id = reader.int()
        val window = document.window(id)
        if (window == null && !pixmaps.containsKey(id)) return error(X11.ERR_BAD_DRAWABLE, opcode, id)
        reply(opcode) { writer ->
            writer.byte(window?.depth ?: pixmaps[id] ?: 24)
            writer.pad(3)
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
            writer.int(window.parent ?: document.rootId)
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
        // byte 0 of the request is the mode; the reader already starts at byte 4.
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
        val start = 24
        val end = (start + length * elementSize).coerceAtMost(request.size)
        val data = if (end > start) request.copyOfRange(start, end) else ByteArray(0)
        document.setProperty(window, property, type, format, data)
        notifyProperty(window, property)
    }

    private fun getProperty(opcode: Int, delete: Int, reader: XReader) {
        val window = reader.int()
        val atom = reader.int()
        reader.int() // type
        reader.int() // longOffset
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
        reply(opcode, extraLength = X11.pad4(data.size)) { writer ->
            writer.byte(format)
            writer.pad(3)
            writer.int(property?.type ?: 0)
            writer.int(data.size / elementSize)
            writer.int(0) // bytesAfter
            writer.short(data.size)
            writer.pad(12)
            writer.bytes(data)
            writer.pad(X11.pad4(data.size) - data.size)
        }
    }

    // --- replies, errors, events -------------------------------------------

    /** Emits a 32-byte reply; [body] writes the 24 bytes after the fixed header. */
    private fun reply(opcode: Int, extraLength: Int = 0, body: (XWriter) -> Unit) {
        val bodyWriter = XWriter(littleEndian)
        body(bodyWriter)
        val bodyBytes = bodyWriter.toByteArray()
        val writer = XWriter(littleEndian)
        writer.byte(1)
        if (bodyBytes.isNotEmpty()) writer.byte(bodyBytes[0].toInt() and 0xFF) else writer.pad(1)
        writer.short(sequence)
        writer.int(extraLength / 4)
        writer.bytes(if (bodyBytes.size > 1) bodyBytes.copyOfRange(1, bodyBytes.size) else ByteArray(0))
        val out = writer.toByteArray()
        pending.add(if (out.size >= 32) out.copyOf(out.size) else out.copyOf(32))
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
    }

    private fun selectsFor(mask: Int, code: Int): Boolean = when (code) {
        X11.EV_KEY_PRESS -> mask and EventMask.KEY_PRESS != 0
        X11.EV_KEY_RELEASE -> mask and EventMask.KEY_RELEASE != 0
        X11.EV_BUTTON_PRESS -> mask and EventMask.BUTTON_PRESS != 0
        X11.EV_BUTTON_RELEASE -> mask and EventMask.BUTTON_RELEASE != 0
        X11.EV_MOTION_NOTIFY -> mask and EventMask.POINTER_MOTION != 0
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
        if (parent.eventMask and EventMask.SUBSTRUCTURE_NOTIFY == 0) return
        val window = document.window(windowId)
        val writer = eventWriter(code)
        when (code) {
            X11.EV_CONFIGURE_NOTIFY, X11.EV_REPARENT_NOTIFY -> {
                writer.int(parentId); writer.int(windowId); writer.int(parentId)
                writer.short(window?.x ?: 0); writer.short(window?.y ?: 0)
                writer.short(window?.width ?: 0); writer.short(window?.height ?: 0)
                writer.short(window?.borderWidth ?: 0); writer.short(0); writer.pad(10)
            }
            else -> {
                writer.int(parentId); writer.int(windowId)
                writer.short(window?.x ?: 0); writer.short(window?.y ?: 0)
                writer.short(window?.width ?: 0); writer.short(window?.height ?: 0)
                writer.short(window?.borderWidth ?: 0)
                writer.byte(if (window?.overrideRedirect == true) 1 else 0)
                writer.pad(9)
            }
        }
        queue(writer)
    }

    private fun configureNotify(window: XWindow) {
        if (window.eventMask and EventMask.STRUCTURE_NOTIFY == 0) return
        val writer = eventWriter(X11.EV_CONFIGURE_NOTIFY)
        writer.int(window.id); writer.int(window.id); writer.int(window.parent ?: 0)
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

    // --- input -------------------------------------------------------------

    fun onPointerMove(x: Int, y: Int) {
        rootPointerX = x
        rootPointerY = y
        val target = document.windowAt(x, y) ?: return
        if (target.eventMask and EventMask.POINTER_MOTION == 0) return
        queue(pointerEvent(X11.EV_MOTION_NOTIFY, 0, target, x, y))
    }

    fun onButton(x: Int, y: Int, button: Int, pressed: Boolean, maskAfter: Int) {
        rootPointerX = x
        rootPointerY = y
        buttonMask = maskAfter
        val target = document.windowAt(x, y) ?: return
        val code = if (pressed) X11.EV_BUTTON_PRESS else X11.EV_BUTTON_RELEASE
        if (pressed && target.eventMask and EventMask.BUTTON_PRESS == 0) return
        if (!pressed && target.eventMask and EventMask.BUTTON_RELEASE == 0) return
        queue(pointerEvent(code, button, target, x, y))
    }

    fun onKey(keycode: Int, keysym: Int, pressed: Boolean, maskAfter: Int) {
        keyStateMask = maskAfter
        val target = document.windowAt(rootPointerX, rootPointerY) ?: document.window(document.rootId) ?: return
        val code = if (pressed) X11.EV_KEY_PRESS else X11.EV_KEY_RELEASE
        val wanted = if (pressed) EventMask.KEY_PRESS else EventMask.KEY_RELEASE
        if (target.eventMask and wanted == 0) return
        val writer = pointerEvent(code, keycode, target, rootPointerX - target.x, rootPointerY - target.y)
        queue(writer)
        @Suppress("UNUSED_EXPRESSION")
        keysym
    }

    private fun pointerEvent(code: Int, detail: Int, target: XWindow, x: Int, y: Int): XWriter {
        val writer = XWriter(littleEndian)
        writer.byte(code)
        writer.byte(detail)
        writer.short(sequence)
        writer.int(System.currentTimeMillis().toInt())
        writer.int(target.id)
        writer.int(document.rootId)
        writer.int(0) // child
        writer.short(rootPointerX)
        writer.short(rootPointerY)
        writer.short(x - target.x)
        writer.short(y - target.y)
        writer.short(buttonMask or keyStateMask)
        writer.byte(0) // sameScreenAndFocus
        writer.pad(1)
        return writer
    }

    /** Sends WM_DELETE_WINDOW to a top-level window (what a window manager does on close). */
    fun sendDeleteWindow(windowId: Int) {
        val window = document.window(windowId) ?: return
        val protocols = document.atomId("WM_PROTOCOLS") ?: return
        val delete = document.internAtom("WM_DELETE_WINDOW")
        val writer = eventWriter(X11.EV_CLIENT_MESSAGE)
        writer.int(32) // format
        writer.int(windowId)
        writer.int(protocols)
        writer.int(delete)
        writer.int(0)
        writer.pad(12)
        queue(writer)
    }
}
