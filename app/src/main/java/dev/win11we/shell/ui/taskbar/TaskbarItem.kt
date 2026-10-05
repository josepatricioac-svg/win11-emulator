package dev.win11we.shell.ui.taskbar

import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.win11we.shell.R

/** One pinned taskbar button. [onClick] is null until the app is ported (N2). */
data class TaskbarItem(
    val id: String,
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
    val onClick: (() -> Unit)? = null,
)

/**
 * N0 list. Start is wired to [onStartClick]; the rest report "not yet" until
 * Explorer / Settings / Terminal land in N2.
 */
@Composable
fun rememberTaskbarItems(onStartClick: () -> Unit): List<TaskbarItem> {
    val context = LocalContext.current
    return remember(context, onStartClick) {
        fun stub(@StringRes labelRes: Int): () -> Unit = {
            val message = context.getString(R.string.taskbar_not_implemented, context.getString(labelRes))
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
        listOf(
            TaskbarItem("start", R.string.taskbar_start, R.drawable.ic_start, onStartClick),
            TaskbarItem("search", R.string.taskbar_search, R.drawable.ic_search, stub(R.string.taskbar_search)),
            TaskbarItem("explorer", R.string.taskbar_explorer, R.drawable.ic_folder, stub(R.string.taskbar_explorer)),
            TaskbarItem("settings", R.string.taskbar_settings, R.drawable.ic_settings, stub(R.string.taskbar_settings)),
            TaskbarItem("terminal", R.string.taskbar_terminal, R.drawable.ic_terminal, stub(R.string.taskbar_terminal)),
        )
    }
}
