package dev.win11we.shell.ui.window

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** Minimize / maximize-restore / close, Windows 11 proportions (46 x 40). */
@Composable
fun WindowControlButton(
    @DrawableRes iconRes: Int,
    description: String,
    close: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background = when {
        pressed && close -> Color(0xFFC42B1C)
        pressed -> Color(0x14FFFFFF)
        else -> Color.Transparent
    }
    Box(
        modifier = Modifier
            .width(46.dp)
            .fillMaxHeight()
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true),
                onClickLabel = description,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = description,
            tint = if (pressed && close) Color.White else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(11.dp),
        )
    }
}
