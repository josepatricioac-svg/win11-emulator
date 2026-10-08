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

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var clientCount: Int = 0
        private set

    @Volatile
    var lastError: String? = null
        private set

    private val connections = mutableListOf<XConnection>()
    private val shutdown = AtomicBoolean(false)
    private var serverSocket: LocalServerSocket? = null
    private var acceptThread: Thread? = null

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

    /** Resize the root (the native surface changed); existing windows are left alone. */
    fun resizeScreen(widthPx: Int, heightPx: Int) {
        val screen = document.screen
        document = XDocument(
            screen.copy(widthPx = widthPx, heightPx = heightPx),
        )
        val root = document.window(document.rootId)
        root?.width = widthPx
        root?.height = heightPx
    }

    /** Starts listening. Returns false with [lastError] set when the socket cannot be bound. */
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
            connections.clear()
        }
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
        val connection = XConnection(document)
        synchronized(connections) { connections.add(connection) }
        clientCount = synchronized(connections) { connections.size }
        try {
            val input = client.inputStream
            val output = client.outputStream
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
            synchronized(connections) { connections.remove(connection) }
            clientCount = synchronized(connections) { connections.size }
            runCatching { client.close() }
        }
    }

    private fun flush(connection: XConnection, output: java.io.OutputStream) {
        connection.drainOutbox().forEach { message ->
            output.write(message)
        }
        output.flush()
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

    /** Feeds one input event to all clients that care (single-app server: usually one). */
    fun dispatchPointerMove(x: Int, y: Int) = connections().forEach { it.onPointerMove(x, y) }

    fun dispatchButton(x: Int, y: Int, button: Int, pressed: Boolean, mask: Int) =
        connections().forEach { it.onButton(x, y, button, pressed, mask) }

    fun dispatchKey(keycode: Int, keysym: Int, pressed: Boolean, mask: Int) =
        connections().forEach { it.onKey(keycode, keysym, pressed, mask) }

    /** Ask a top-level window to close the way a window manager does. */
    fun requestWindowClose(windowId: Int) = connections().forEach { it.sendDeleteWindow(windowId) }
}
