package dev.win11we.shell.ui.xdesktop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.win11we.shell.x11.XClipboardBridge
import dev.win11we.shell.x11.XServer

/**
 * The real Android side of the P1 clipboard bridge. Every call is guarded
 * (crash-safety rule 1: a clipboard SecurityException once killed this process
 * family of bugs, so nothing here may throw).
 */
class AndroidClipboardBridge(context: Context) : XClipboardBridge {
    private val appContext = context.applicationContext
    private val manager: ClipboardManager? =
        runCatching { appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager }.getOrNull()
    private val main = Handler(Looper.getMainLooper())

    override fun readText(): String? = runCatching {
        manager?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.let { item ->
            item.text?.toString() ?: item.coerceToText(appContext)?.toString()
        }
    }.getOrNull()

    override fun writeText(text: String) {
        main.post {
            runCatching { manager?.setPrimaryClip(ClipData.newPlainText("X11 selection", text)) }
        }
    }
}

/**
 * Wires the X server to the system clipboard while the desktop is composed:
 *
 * - **Guest → Android**: a client claiming PRIMARY/CLIPBOARD triggers a real
 *   wire-protocol conversion (the server's internal shell connection asks the
 *   owner for UTF8_STRING) and the resulting text lands on the system clipboard.
 * - **Android → guest**: system-clipboard changes (the shell's Notepad, any
 *   other app) make the shell reclaim both selections, so a guest's next
 *   ConvertSelection is answered inline with the fresh text.
 *
 * The loop guard ignores our own pushes (Android fires the change listener for
 * `setPrimaryClip` too). Android has ONE clipboard; PRIMARY and CLIPBOARD both
 * map onto it - documented in `docs/X_SERVER.md`.
 */
@Composable
fun ClipboardBridgeEffect() {
    val context = LocalContext.current
    val bridge = remember(context) { AndroidClipboardBridge(context) }
    DisposableEffect(bridge) {
        XServer.setClipboardBridge(bridge)
        val manager = runCatching {
            context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        }.getOrNull()
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            runCatching {
                val text = bridge.readText()
                if (text != null && text != XServer.lastGuestClipboardText()) {
                    XServer.androidClipboardChanged()
                }
            }
        }
        runCatching { manager?.addPrimaryClipChangedListener(listener) }
        onDispose {
            runCatching { manager?.removePrimaryClipChangedListener(listener) }
            XServer.setClipboardBridge(null)
        }
    }
}
