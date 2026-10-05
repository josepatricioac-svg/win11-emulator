package dev.win11we.shell.window

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Immutable

/** A launchable shell app. N2 replaces [placeholder] with real screens. */
@Immutable
data class AppSpec(
    val id: String,
    val label: String,
    @DrawableRes val iconRes: Int,
    val pinned: Boolean = true,
    val enabled: Boolean = true,
)
