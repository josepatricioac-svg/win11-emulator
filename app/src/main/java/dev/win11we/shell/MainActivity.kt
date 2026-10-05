package dev.win11we.shell

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.win11we.shell.ui.desktop.DesktopScreen
import dev.win11we.shell.ui.theme.Win11Theme

/**
 * The single Activity of the shell. N0 renders one screen: wallpaper + taskbar.
 * N1 adds the Start menu and the window manager on top of this surface.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            Win11Theme {
                DesktopScreen()
            }
        }
    }
}
