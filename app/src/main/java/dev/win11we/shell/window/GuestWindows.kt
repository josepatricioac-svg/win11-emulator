package dev.win11we.shell.window

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.win11we.shell.R
import dev.win11we.shell.x11.XServer
import dev.win11we.shell.x11.XWindowManager

/**
 * Payload of a shell window that hosts exactly ONE guest X top-level window
 * (D2: "apps open as apps"). The window body renders that X window's subtree;
 * the shell frame provides the caption, drag, resize, snap, minimize and
 * close - so a guest application behaves like any other shell window.
 */
class GuestWindowPayload(val xid: Int)

/**
 * Provides the active bridge to the window bodies (WindowContent routes a
 * [GuestWindowPayload] to the X surface, which reports geometry through it).
 * Null outside the desktop composition (tests, previews).
 */
val LocalGuestBridge = staticCompositionLocalOf<GuestWindowBridge?> { null }

/**
 * The shell-side window manager (D2). The shell CLAIMS SubstructureRedirect
 * (via [XServer.attachWindowManager]) and turns every mapped guest top-level
 * window into a regular shell window - floating, draggable, resizable,
 * minimizable, closable - instead of letting the guests paint onto one
 * full-screen X desktop. This is the model the owner asked for: "firefox
 * actually opens as an app, in a floating window with resize options like
 * everything else" (like termux-x11, where apps are windows).
 *
 * Division of labour:
 * - NORMAL top-levels: `MapWindow` is redirected here ([mapRequested]); a
 *   shell window opens; when its surface reports the real pixel geometry,
 *   [surfaceGeometry] maps the X window at exactly the shell window's
 *   on-screen rectangle (MapNotify + Expose + ConfigureNotify - the sequence
 *   GTK waits for before its first paint). Client resizes come back through
 *   [configureRequested] and update the shell window's size.
 * - OVERRIDE-REDIRECT windows (menus, dropdowns, tooltips): bypass the WM by
 *   X design, stay unmanaged, and are composited by the desktop surface's
 *   popup layer at absolute coordinates - which line up because managed X
 *   windows carry their shell window's screen geometry.
 *
 * Lifecycle (all in [sync], one call per frame from the desktop):
 * - guest window unmapped/destroyed -> its shell window closes;
 * - shell window closed by the user -> WM_DELETE_WINDOW to the guest, and a
 *   bounded escalation to DestroyWindow when the guest ignores it (a window
 *   that cannot be closed is worse than a rude close);
 * - shell window closed before its surface ever reported geometry -> the
 *   still-unmapped X window is destroyed (no zombies).
 *
 * Deliberately NOT covered (documented, not silently): a full-desktop guest
 * session (`explorer.exe /desktop` for Wine) wants to be the WM itself - that
 * mode needs the bridge detached first; the browser sessions this ships with
 * are per-window by design (no openbox is started for them anymore).
 */
class GuestWindowBridge(
    private val manager: WindowManager,
    private val desktopSizeDp: () -> DpSize,
    private val density: () -> Float,
    private val log: (String) -> Unit = {},
) : XWindowManager {

    /** xid -> the shell window hosting it. Insertion-ordered for stable logs. */
    private val hosted = LinkedHashMap<Int, WindowState>()

    /** xids whose shell window exists but whose surface has not reported geometry yet. */
    private val pendingSince = HashMap<Int, Long>()

    /** xid -> frame index when WM_DELETE was first sent (close escalation). */
    private val closeRequestedAt = HashMap<Int, Long>()

    private var frames = 0L

    /**
     * Desktop-area origin in root-window pixels, reported by the desktop
     * surface (it fills the desktop box exactly). Per-window surfaces subtract
     * it to turn their `positionInRoot` into X screen coordinates.
     */
    @Volatile
    var desktopOriginX: Float = 0f
    @Volatile
    var desktopOriginY: Float = 0f

    /** How many guest windows the bridge currently hosts (status lines). */
    val hostedCount: Int get() = hosted.size

    fun isHosted(xid: Int): Boolean = hosted.containsKey(xid)

    // ---------------------------------------------------------------- XWindowManager

    override fun mapRequested(windowId: Int) {
        val doc = XServer.document
        val window = doc.window(windowId) ?: return
        if (hosted.containsKey(windowId)) {
            // Re-map of an already-hosted window (client unmapped then mapped
            // again): map in place with its last known geometry.
            XServer.wmMapWindow(windowId, null, null, null, null)
            return
        }
        val d = density().coerceAtLeast(0.5f)
        val desktop = desktopSizeDp()
        val widthDp = (window.width / d).dp.coerceIn(MIN_WIDTH_DP, desktop.width * 0.95f)
        val heightDp = (window.height / d).dp.coerceIn(MIN_HEIGHT_DP, desktop.height * 0.95f)
        val title = doc.windowTitle(windowId) ?: "Guest application"
        val spec = AppSpec(
            id = "guest:$windowId",
            label = title,
            iconRes = R.drawable.ic_desktop,
            pinned = false,
            // The caption follows WM_NAME live: GTK sets it once the window exists.
            title = { state ->
                val xid = (state.payload as? GuestWindowPayload)?.xid
                (xid?.let { XServer.document.windowTitle(it) }) ?: title
            },
        )
        val shell = manager.openWithPayload(spec, desktop, GuestWindowPayload(windowId), DpSize(widthDp, heightDp))
        hosted[windowId] = shell
        pendingSince[windowId] = frames
        log(
            "guest window mapped -> shell window #${shell.id} \"$title\" " +
                "(xid $windowId, requested ${window.width}x${window.height}px)",
        )
        // The X window itself is mapped by surfaceGeometry once the shell
        // window's body reports its real pixel rect - mapping now would make
        // GTK paint at a size the shell is about to change anyway.
    }

    override fun configureRequested(
        windowId: Int,
        x: Int?,
        y: Int?,
        width: Int?,
        height: Int?,
        borderWidth: Int?,
    ) {
        @Suppress("UNUSED_EXPRESSION")
        x; y; borderWidth // position is the shell user's; borders are not rendered
        val shell = hosted[windowId] ?: return
        if (width == null && height == null) return
        // A floating shell window mirrors the client's requested size; a
        // maximized/snapped one keeps the shell geometry (the X window is
        // configured back to it by the surface on the next layout pass).
        if (shell.mode != WindowMode.NORMAL) return
        val d = density().coerceAtLeast(0.5f)
        val current = shell.size
        shell.size = DpSize(
            (width?.toFloat()?.div(d)?.dp) ?: current.width,
            (height?.toFloat()?.div(d)?.dp) ?: current.height,
        )
    }

    // ---------------------------------------------------------------- shell side

    /**
     * The hosting surface reports its real pixel rect (desktop-relative).
     * First report MAPS the X window there (the MapNotify/Expose/Configure
     * triple GTK waits for); later reports move/resize it, so popups the guest
     * places from its own coordinates land on the right screen pixels.
     */
    fun surfaceGeometry(xid: Int, xPx: Int, yPx: Int, wPx: Int, hPx: Int) {
        if (wPx <= 0 || hPx <= 0) return
        val window = XServer.document.window(xid) ?: return
        pendingSince.remove(xid)
        if (!window.mapped) {
            XServer.wmMapWindow(xid, xPx, yPx, wPx, hPx)
        } else {
            XServer.wmConfigureWindow(xid, xPx, yPx, wPx, hPx)
        }
    }

    /**
     * One call per frame from the desktop loop: reconciles the two window
     * systems. Every branch removes its bookkeeping - nothing may leak an
     * entry for a window that is gone on either side.
     */
    fun sync() {
        frames++
        val doc = XServer.document
        val iterator = hosted.entries.iterator()
        while (iterator.hasNext()) {
            val (xid, shell) = iterator.next()
            val xWindow = doc.window(xid)
            val shellGone = shell.closing || manager.windows.none { it.id == shell.id }
            if (pendingSince.containsKey(xid)) {
                when {
                    shellGone -> {
                        // Closed before it ever rendered: no zombie guest window.
                        runCatching { doc.destroyWindow(xid) }
                        iterator.remove(); pendingSince.remove(xid); closeRequestedAt.remove(xid)
                    }
                    xWindow == null -> {
                        iterator.remove(); pendingSince.remove(xid)
                    }
                    frames - (pendingSince[xid] ?: frames) > PENDING_GRACE_FRAMES -> {
                        log("guest window xid $xid never reached its surface - destroying")
                        runCatching { doc.destroyWindow(xid) }
                        runCatching { manager.close(shell) }
                        iterator.remove(); pendingSince.remove(xid)
                    }
                }
                continue
            }
            val xGone = xWindow == null || !xWindow.mapped
            when {
                xGone && shellGone -> {
                    iterator.remove(); closeRequestedAt.remove(xid)
                }
                xGone -> {
                    log("guest window xid $xid unmapped/destroyed by the guest - closing shell window #${shell.id}")
                    runCatching { manager.close(shell) }
                    iterator.remove(); closeRequestedAt.remove(xid)
                }
                shellGone -> {
                    val at = closeRequestedAt[xid]
                    if (at == null) {
                        closeRequestedAt[xid] = frames
                        // Polite close first: WM_DELETE_WINDOW lets the app ask
                        // about unsaved work / shut down cleanly.
                        XServer.requestWindowClose(xid)
                    } else if (frames - at > CLOSE_GRACE_FRAMES) {
                        log("guest window xid $xid ignored WM_DELETE for $CLOSE_GRACE_FRAMES frames - destroying")
                        runCatching { doc.destroyWindow(xid) }
                        iterator.remove(); closeRequestedAt.remove(xid)
                    }
                }
            }
        }
    }

    /** Drops all bookkeeping; the shell window list is the caller's business. */
    fun detach() {
        hosted.clear()
        pendingSince.clear()
        closeRequestedAt.clear()
    }

    companion object {
        val MIN_WIDTH_DP = 240.dp
        val MIN_HEIGHT_DP = 160.dp

        /** ~2 s at 60 fps for a guest to answer WM_DELETE before DestroyWindow. */
        const val CLOSE_GRACE_FRAMES = 120L

        /** ~10 s for a shell window's surface to report geometry before we give up. */
        const val PENDING_GRACE_FRAMES = 600L
    }
}
