package dev.win11we.shell.x11

/**
 * The shell's in-process window manager (D1b). Clients never place their own
 * top-level windows: SubstructureRedirect on the root turns their MapWindow /
 * ConfigureWindow into [mapRequested] / [configureRequested], and this class
 * decides the geometry, then maps through [XServer.wmMapWindow] so every client
 * sees the usual MapNotify/Expose/ConfigureNotify traffic.
 *
 * Placement is a centered cascade, like the shell's native windows. Injected
 * callbacks keep it unit-testable without sockets.
 */
class ShellWindowManager(
    private val document: () -> XDocument,
    private val mapWindow: (id: Int, x: Int?, y: Int?, width: Int?, height: Int?) -> Unit,
    private val configureWindow: (id: Int, x: Int?, y: Int?, width: Int?, height: Int?) -> Unit,
) : XWindowManager {

    private var placed = 0

    override fun mapRequested(windowId: Int) {
        val doc = document()
        val window = doc.window(windowId) ?: return
        val screen = doc.screen
        val width = window.width.coerceIn(1, screen.widthPx)
        val height = window.height.coerceIn(1, screen.heightPx)
        val step = (placed++ % CASCADE_STEPS) * CASCADE_STEP_PX
        val x = ((screen.widthPx - width) / 2 + step)
            .coerceIn(0, (screen.widthPx - width).coerceAtLeast(0))
        val y = ((screen.heightPx - height) / 2 + step - CASCADE_STEP_PX)
            .coerceIn(0, (screen.heightPx - height).coerceAtLeast(0))
        mapWindow(windowId, x, y, width, height)
    }

    override fun configureRequested(
        windowId: Int,
        x: Int?,
        y: Int?,
        width: Int?,
        height: Int?,
        borderWidth: Int?,
    ) {
        val screen = document().screen
        configureWindow(
            windowId,
            x,
            y,
            width?.coerceIn(1, screen.widthPx),
            height?.coerceIn(1, screen.heightPx),
        )
        @Suppress("UNUSED_EXPRESSION")
        borderWidth // border changes are not rendered yet
    }

    /** Convenience wiring against the live singleton. */
    companion object {
        const val CASCADE_STEP_PX = 32
        const val CASCADE_STEPS = 8

        fun forServer(server: XServer = XServer): ShellWindowManager = ShellWindowManager(
            document = { server.document },
            mapWindow = { id, x, y, w, h -> server.wmMapWindow(id, x, y, w, h) },
            configureWindow = { id, x, y, w, h -> server.wmConfigureWindow(id, x, y, w, h) },
        )
    }
}
