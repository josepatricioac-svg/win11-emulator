package dev.win11we.shell.window

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable

/**
 * A launchable shell app. [title] lets a window rename itself (Explorer shows
 * the current folder, Notepad the buffer name); defaults to [label].
 */
@Immutable
data class AppSpec(
    val id: String,
    val label: String,
    @DrawableRes val iconRes: Int,
    val pinned: Boolean = true,
    val enabled: Boolean = true,
    val title: (@Composable (WindowState) -> String)? = null,
)
