package dev.win11we.shell.x11

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The X display the shell owns. There is exactly one server and one screen, so
 * this is a plain object: [XDocument] is the shared window tree every client sees.
 */
object XServer {
    private const val TAG = "XServer"

    /** The display number; clients see `DISPLAY=:100`. */
    const val DISPLAY_NUMBER = 100
    const val DISPLAY = ":$DISPLAY_NUMBER"

    /**
     * Abstract-namespace socket name. X clients connect to the abstract socket
     * `@/tmp/.X11-unix/X<display>`, and proot does not isolate the abstract
     * namespace, so a guest process reaches it without any bind mount.
     */
    fun abstractName(displayNumber: Int = DISPLAY_NUMBER) = "/tmp/.X11-unix/X$displayNumber"

    /** Filesystem socket, for clients that only try the path form (bind it into the guest). */
    fun socketFile(dir: File, displayNumber: Int = DISPLAY_NUMBER) = File(dir, "X$displayNumber")

    @Volatile
    var document: XDocument = defaultDocument(1280, 800)
        private set

    /** The server's pixel memory; the Compose renderer (D1b) reads it. */
    val displayList = XDisplayList()

    /** Bumped on WM-driven changes so the renderer redraws even with no new drawing. */
    @Volatile
    var contentRevision: Long = 0
        private set

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var clientCount: Int = 0
        private set

    @Volatile
    var lastError: String? = null
        private set

    // --- protocol observability -------------------------------------------

    /**
     * Where per-connection protocol reports go. The x11 package must stay free of
     * Android and UI types (it is the one part of the shell that can be unit
     * tested and run off-device), so the shell installs this hook: XDesktop wires
     * it to `SharedLog`, which is what Terminal and Diagnostics display.
     *
     * Called on the client thread when a connection ends. A report names every
     * extension the client queried, every opcode we could not implement and every
     * dispatch that threw - i.e. exactly the information a silent `_exit(1)` from
     * GTK otherwise destroys.
     */
    @Volatile
    var protocolReporter: ((String) -> Unit)? = null

    /** The most recent report, for the Diagnostics screen. */
    @Volatile
    var lastProtocolReport: String? = null
        private set

    private val connectionCounter = java.util.concurrent.atomic.AtomicLong(0)

    /** Reports of the connections that are alive right now (Diagnostics). */
    fun liveProtocolReports(): List<String> = connections().map { it.trace.summary() }

    /**
     * Feeds one connection's report to [protocolReporter] and caches it.
     * `internal` so the wiring is unit-testable: [serve] itself needs Android
     * sockets, this part does not.
     */
    internal fun report(connection: XConnection) {
        val text = connection.trace.summary()
        lastProtocolReport = text
        val reporter = protocolReporter ?: return
        runCatching { reporter(text) }
    }

    private val connections = mutableListOf<XConnection>()
    private val outputs = HashMap<XConnection, java.io.OutputStream>()
    private val shutdown = AtomicBoolean(false)
    private var serverSocket: LocalServerSocket? = null
    private var acceptThread: Thread? = null
    private var windowManager: XWindowManager? = null

    /**
     * The authoritative pointer position in screen coordinates (D1c): updated by
     * input dispatch and by client warps, read by the software-cursor renderer.
     */
    @Volatile
    var pointerX: Int = 0
        private set

    @Volatile
    var pointerY: Int = 0
        private set

    // --- P1: Android clipboard <-> X selections bridge -----------------------

    @Volatile
    private var clipboardBridge: XClipboardBridge? = null

    /** Internal connection that speaks the wire protocol as the shell's paste client. */
    private var shellConnection: XConnection? = null

    /** Loop guard: the last text pushed *from* a guest onto the Android clipboard. */
    @Volatile
    private var lastGuestClipboardText: String? = null

    fun lastGuestClipboardText(): String? = lastGuestClipboardText

    /**
     * Attaches (or with null, detaches) the Android clipboard bridge. While
     * attached, PRIMARY and CLIPBOARD default to shell ownership, guest
     * SetSelectionOwner claims trigger a real wire-protocol fetch of the new
     * text, and guest ConvertSelection requests are answered inline from the
     * system clipboard. Called from the Compose layer (DesktopScreen).
     */
    fun setClipboardBridge(bridge: XClipboardBridge?) {
        clipboardBridge = bridge
        if (bridge == null) {
            document.clipboardTextProvider = null
            document.onSelectionOwnerChanged = null
            shellConnection?.let { document.releaseOwners(it) }
            shellConnection = null
            lastGuestClipboardText = null
            document.dropShellSelections()
            return
        }
        document.clipboardTextProvider = { targetAtom ->
            val name = document.atomName(targetAtom)
            if (name == "TARGETS") null // handled inside the connection
            else runCatching { SelectionCodec.encode(bridge.readText(), name) }.getOrNull()
        }
        val connection = shellConnection ?: XConnection(document, RecordingSink()).also { internal ->
            internal.eventInterceptor = { message -> onShellEvent(message) }
            document.setOwner(XDocument.SHELL_WINDOW_ID, internal) // route SelectionNotify to it
            shellConnection = internal
        }
        document.reclaimSelectionsToShell()
        document.onSelectionOwnerChanged = { atom, owner ->
            if (owner.connection != null) requestGuestSelection(atom)
        }
        @Suppress("UNUSED_EXPRESSION")
        connection
    }

    /** The Android clipboard changed outside the guest: the shell reclaims ownership. */
    fun androidClipboardChanged() {
        if (clipboardBridge == null) return
        document.reclaimSelectionsToShell()
    }

    /**
     * Guest copy: ask the new owner for UTF8_STRING through the *real* wire
     * protocol - the internal connection sends ConvertSelection, the owner gets
     * a SelectionRequest and answers with ChangeProperty + SendEvent, and
     * [onShellEvent] picks the text up. Runs on the claiming client's thread.
     */
    private fun requestGuestSelection(atom: Int) {
        val connection = shellConnection ?: return
        val primary = document.atomId("PRIMARY")
        val clipboard = document.atomId("CLIPBOARD")
        if (atom != primary && atom != clipboard) return
        val utf8 = document.atomId("UTF8_STRING") ?: return
        val writer = XWriter(true)
        writer.byte(X11.REQ_CONVERT_SELECTION)
        writer.byte(0)
        writer.short(6) // 24 bytes = 6 units
        writer.int(XDocument.SHELL_WINDOW_ID) // requestor
        writer.int(atom) // selection
        writer.int(utf8) // target
        writer.int(0) // property: owner's choice
        writer.int(0) // time: CurrentTime
        runCatching { connection.onBytes(writer.toByteArray()) }
    }

    /** The internal connection's event tap: SelectionNotify answers to its requests. */
    private fun onShellEvent(message: ByteArray) {
        val bridge = clipboardBridge ?: return
        val connection = shellConnection ?: return
        try {
            if (message.size < 32 || (message[0].toInt() and 0x7F) != X11.EV_SELECTION_NOTIFY) return
            val requestor = le32(message, 8)
            if (requestor != XDocument.SHELL_WINDOW_ID) return
            val target = le32(message, 16)
            val property = le32(message, 20)
            if (property == 0) return // conversion refused
            val data = document.property(XDocument.SHELL_WINDOW_ID, property)?.data
            document.deleteProperty(XDocument.SHELL_WINDOW_ID, property)
            val text = data?.let { SelectionCodec.decode(it, document.atomName(target)) }
            if (text != null && text != lastGuestClipboardText) {
                lastGuestClipboardText = text
                runCatching { bridge.writeText(text) }
            }
        } finally {
            connection.drainOutbox() // the internal connection never writes to a socket
        }
    }

    private fun le32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    init {
        // Keep every connection's pointer in sync when a client warps it
        // (WarpPointer / XTest FakeInput), and wake the renderer for the cursor.
        document.onWarp = { x, y, origin -> warpSync(x, y, origin) }
    }

    private fun warpSync(x: Int, y: Int, origin: XConnection) {
        pointerX = x
        pointerY = y
        contentRevision++
        connections().forEach { connection ->
            if (connection === origin) return@forEach
            runCatching { connection.onPointerMove(x, y) }
            pushOutbox(connection)
        }
    }

    fun defaultDocument(widthPx: Int, heightPx: Int): XDocument {
        val rootId = X11.RESOURCE_ID_BASE
        return XDocument(
            ScreenConfig(
                rootId = rootId,
                widthPx = widthPx,
                heightPx = heightPx,
                defaultColormap = rootId + 1,
            ),
        )
    }

    /** Resize the root (the native surface changed); windows and clients stay alive. */
    fun resizeScreen(widthPx: Int, heightPx: Int) {
        val before = document.screen
        document.setScreenSize(widthPx, heightPx)
        applyWindowManager()
        if (before.widthPx != widthPx || before.heightPx != heightPx) {
            contentRevision++
            // Tell every client the root reconfigured. There is no RANDR here
            // (honestly absent - see docs/X_SERVER.md), and a root
            // ConfigureNotify is what a guest WM/GTK falls back to for screen
            // geometry changes; without it openbox keeps the old workarea after
            // a rotation or a window resize.
            broadcaster { it.sendEvent(document.rootId, X11.EV_CONFIGURE_NOTIFY) }
        }
    }

    /**
     * Installs the shell's in-process WM: it receives map/configure requests for
     * top-level windows (SubstructureRedirect semantics) and places them itself.
     */
    fun attachWindowManager(wm: XWindowManager?) {
        windowManager = wm
        applyWindowManager()
    }

    private fun applyWindowManager() {
        document.windowManager = windowManager
        val root = document.window(document.rootId) ?: return
        if (windowManager != null) {
            root.eventMask = root.eventMask or EventMask.SUBSTRUCTURE_REDIRECT
        } else {
            root.eventMask = root.eventMask and EventMask.SUBSTRUCTURE_REDIRECT.inv()
        }
    }

    /**
     * Makes sure a display exists even when no X Desktop window is open yet,
     * at a default screen size. Guest launches (the Firefox ESR desktop-browser
     * session) call this before spawning; an X Desktop window opened later
     * resizes the screen, keeping clients alive.
     *
     * The server runs PASS-THROUGH: the shell does not claim
     * SubstructureRedirect - the claim is exclusive, and a shell WM here would
     * lock every guest window manager out. The desktop session brings its own
     * WM (openbox for the browser session, `explorer.exe /desktop` for Wine
     * later); without one, clients map undecorated and still work.
     * [attachWindowManager] stays for tests and a future per-window mode.
     */
    fun ensureRunning(widthPx: Int = 1280, heightPx: Int = 800): Boolean =
        if (running) true else start(widthPx, heightPx)

    /**
     * Starts listening on the abstract-namespace socket. Returns false with
     * [lastError] set when it cannot be bound.
     *
     * There is deliberately no path-form (`/tmp/.X11-unix/X100`) listener:
     * `LocalServerSocket` only binds the abstract namespace through public API,
     * and none is needed - libX11 tries the filesystem path first, fails (proot
     * translates it to the bound-but-empty host directory), and falls back to
     * the abstract socket, which proot passes through untouched.
     */
    fun start(widthPx: Int = 1280, heightPx: Int = 800): Boolean {
        if (running) return true
        resizeScreen(widthPx, heightPx)
        shutdown.set(false)
        return try {
            val socket = LocalServerSocket(abstractName())
            serverSocket = socket
            running = true
            lastError = null
            acceptThread = Thread({ acceptLoop(socket) }, "x11-accept").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: IOException) {
            lastError = "${error.javaClass.simpleName}: ${error.message}"
            running = false
            false
        }
    }

    fun stop() {
        shutdown.set(true)
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        synchronized(connections) {
            // A dead connection must not leave a stale grab behind: an active
            // pointer/keyboard grab suppresses delivery to everyone else, so the
            // next session would see a server that swallows all input.
            connections.forEach { connection ->
                document.releaseRedirect(connection)
                document.releaseOwners(connection)
                document.releaseGrabs(connection)
                document.releaseSelections(connection)
            }
            connections.clear()
        }
        synchronized(outputs) { outputs.clear() }
        clientCount = 0
        acceptThread?.interrupt()
        acceptThread = null
    }

    private fun acceptLoop(server: LocalServerSocket) {
        while (!shutdown.get()) {
            val client = try {
                server.accept()
            } catch (error: IOException) {
                if (!shutdown.get()) lastError = "accept: ${error.message}"
                return
            }
            Thread({ serve(client) }, "x11-client").apply { isDaemon = true; start() }
        }
    }

    private fun serve(client: LocalSocket) {
        val connection = XConnection(document, displayList)
        connection.trace.label = "client#${connectionCounter.incrementAndGet()}"
        synchronized(connections) { connections.add(connection) }
        clientCount = synchronized(connections) { connections.size }
        try {
            val input = client.inputStream
            val output = client.outputStream
            synchronized(outputs) { outputs[connection] = output }
            document.externalFlush = { conn -> pushOutbox(conn) }
            val buffer = ByteArray(16 * 1024)

            // 1. The client opens with the connection setup request.
            val header = readFully(input, buffer, 12) ?: return
            val authNameLength = le(header, 6)
            val authDataLength = le(header, 8)
            val extra = X11.pad4(authNameLength) + X11.pad4(authDataLength)
            val setup = if (extra > 0) {
                val rest = readFully(input, buffer, extra) ?: return
                header + rest
            } else {
                header
            }
            val request = connection.parseSetup(setup)
            val reply = if (request == null || request.majorVersion != X11.PROTOCOL_MAJOR) {
                connection.setupFailed("This server speaks X11.${X11.PROTOCOL_MINOR} only")
            } else {
                connection.setupSuccess()
            }
            output.write(reply)
            output.flush()

            // 2. Request/response loop until the client goes away.
            while (!shutdown.get()) {
                val read = input.read(buffer)
                if (read <= 0) break
                connection.onBytes(buffer, read)
                flush(connection, output)
            }
        } catch (error: IOException) {
            Log.d(TAG, "client ended: ${error.message}")
        } finally {
            // Log what this client asked for before its state is thrown away: when
            // a GTK client dies on an X error this summary is the only evidence.
            report(connection)
            synchronized(connections) { connections.remove(connection) }
            synchronized(outputs) { outputs.remove(connection) }
            document.releaseRedirect(connection)
            // Free the client's windows/pixmaps/GCs so a reconnecting client never
            // collides with its own stale resource ids (real X does this on disconnect).
            val purged = connection.releaseResources()
            (connection.sink as? XDisplayList)?.let { dl -> purged.forEach { dl.remove(it) } }
            document.releaseOwners(connection)
            document.releaseGrabs(connection)
            document.releaseSelections(connection)
            clientCount = synchronized(connections) { connections.size }
            runCatching { client.close() }
        }
    }

    private fun flush(connection: XConnection, output: java.io.OutputStream) {
        val messages = connection.drainOutbox()
        if (messages.isEmpty()) return
        synchronized(connection) {
            messages.forEach { message -> output.write(message) }
            output.flush()
        }
    }

    /** Writes a connection's queued messages from any thread (WM-driven events). */
    private fun pushOutbox(connection: XConnection) {
        val output = synchronized(outputs) { outputs[connection] } ?: return
        try {
            flush(connection, output)
        } catch (error: IOException) {
            Log.d(TAG, "push failed: ${error.message}")
        }
    }

    private fun broadcast(action: (XConnection) -> Unit) {
        connections().forEach { connection ->
            runCatching { action(connection) }
            pushOutbox(connection)
        }
    }

    /**
     * Maps a window as the WM: applies the placement, then tells every client
     * (MapNotify to root listeners, Expose to the window) and redraws.
     */
    fun wmMapWindow(id: Int, x: Int?, y: Int?, width: Int?, height: Int?) {
        // The WM - not the client - picks this geometry, so the client has to be
        // told. GTK sizes its backing surface from ConfigureNotify and does not
        // draw until one arrives that matches the mapped window; MapNotify alone
        // leaves it rendering at the position and size it asked for, which after a
        // WM placement is simply wrong (for Firefox: a black or clipped window).
        val before = document.window(id)?.let { intArrayOf(it.x, it.y, it.width, it.height) }
        document.configure(id, x, y, width, height, null)
        if (!document.mapWindow(id)) return
        contentRevision++
        val rootId = document.rootId
        val after = document.window(id)
        val placed = before != null && after != null &&
            (before[0] != after.x || before[1] != after.y ||
                before[2] != after.width || before[3] != after.height)
        broadcaster { connection ->
            if (placed) connection.sendEvent(id, X11.EV_CONFIGURE_NOTIFY)
            connection.sendEvent(rootId, X11.EV_MAP_NOTIFY, id)
            connection.sendEvent(id, X11.EV_EXPOSE)
        }
    }

    /** Applies a WM-approved geometry and notifies the client (ConfigureNotify). */
    fun wmConfigureWindow(id: Int, x: Int?, y: Int?, width: Int?, height: Int?) {
        if (!document.configure(id, x, y, width, height, null)) return
        contentRevision++
        broadcaster { it.sendEvent(id, X11.EV_CONFIGURE_NOTIFY) }
    }

    /**
     * How WM-driven changes reach clients. A seam rather than a direct call so the
     * event sequence above can be unit-tested: [serve] is the only part of this
     * object that needs Android sockets.
     */
    internal var broadcaster: ((XConnection) -> Unit) -> Unit = { action -> broadcast(action) }

    /** Test seam: point the singleton at a fresh document (production uses [start]). */
    internal fun setDocumentForTest(value: XDocument) {
        document = value
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray, count: Int): ByteArray? {
        val out = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(out, read, count - read)
            if (n <= 0) return null
            read += n
        }
        return out
    }

    private fun le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    /** Every live connection, for broadcasting input and WM messages. */
    fun connections(): List<XConnection> = synchronized(connections) { connections.toList() }

    /**
     * Registers an in-process connection (tests, and later in-app X clients) so
     * broadcasts and input dispatch reach it. Without a socket in [outputs], its
     * events simply accumulate in the connection's outbox.
     */
    fun register(connection: XConnection) {
        synchronized(connections) {
            if (connections.none { it === connection }) connections.add(connection)
        }
        clientCount = synchronized(connections) { connections.size }
    }

    fun unregister(connection: XConnection) {
        synchronized(connections) { connections.removeAll { it === connection } }
        synchronized(outputs) { outputs.remove(connection) }
        document.releaseRedirect(connection)
        document.releaseOwners(connection)
        document.releaseGrabs(connection)
        document.releaseSelections(connection)
        clientCount = synchronized(connections) { connections.size }
    }

    /** Feeds one input event to all clients that care (single-app server: usually one). */
    fun dispatchPointerMove(x: Int, y: Int) {
        pointerX = x
        pointerY = y
        connections().forEach { it.onPointerMove(x, y) }
    }

    fun dispatchButton(x: Int, y: Int, button: Int, pressed: Boolean, mask: Int) {
        pointerX = x
        pointerY = y
        connections().forEach { it.onButton(x, y, button, pressed, mask) }
    }

    fun dispatchKey(keycode: Int, keysym: Int, pressed: Boolean, mask: Int) =
        connections().forEach { it.onKey(keycode, keysym, pressed, mask) }

    /** Ask a top-level window to close the way a window manager does. */
    fun requestWindowClose(windowId: Int) = connections().forEach { it.sendDeleteWindow(windowId) }
}
