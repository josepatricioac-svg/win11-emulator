package dev.win11we.shell.ui.browser

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.win11we.shell.R
import dev.win11we.shell.ui.theme.AccentBlue
import dev.win11we.shell.ui.theme.CardSurface
import dev.win11we.shell.ui.theme.StrokeSubtle
import dev.win11we.shell.ui.theme.TextPrimary
import dev.win11we.shell.ui.theme.TextSecondary
import dev.win11we.shell.ui.theme.TextTertiary
import dev.win11we.shell.ui.theme.ShellSurfaces

private val Pill = RoundedCornerShape(50)
private val Card = RoundedCornerShape(9.dp)

/**
 * Browser window. The shell never renders web content itself: it resolves the
 * address and hands it to a real browser, Firefox by preference.
 */
@Composable
fun BrowserApp(
    state: BrowserState,
    onChooseBrowser: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ShellSurfaces.windowContent)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(Pill)
                .background(CardSurface)
                .border(1.dp, StrokeSubtle, Pill)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_app_browser), null, tint = TextTertiary, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(9.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                if (state.query.isEmpty()) {
                    Text("Search or enter a web address", color = TextTertiary, fontSize = 13.sp)
                }
                BasicTextField(
                    value = state.query,
                    onValueChange = { state.query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp),
                    cursorBrush = SolidColor(AccentBlue),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Opens in ${state.targetLabel()}",
                color = TextSecondary,
                fontSize = 12.5.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Open",
                color = Color(0xFF0B1220),
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(Pill)
                    .background(AccentBlue)
                    .clickable(onClickLabel = "Open") {
                        val url = state.resolve()
                        if (url == null) {
                            Toast.makeText(context, "Nothing to open", Toast.LENGTH_SHORT).show()
                        } else {
                            try {
                                context.startActivity(Browsers.launchIntent(url, state.targetPackage()))
                            } catch (error: ActivityNotFoundException) {
                                state.markLaunchFailed()
                                Toast.makeText(context, "No browser could open $url", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
        if (state.launchFailed) {
            Spacer(Modifier.height(8.dp))
            Text("No browser answered. Install Firefox, or pick one below.", color = Color(0xFFFF8A7A), fontSize = 12.sp)
        }

        Spacer(Modifier.height(18.dp))
        Text("Installed browsers", color = TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "The shell hands the URL to the browser process; nothing is rendered or proxied in-app.",
            color = TextTertiary,
            fontSize = 11.5.sp,
        )
        Spacer(Modifier.height(10.dp))

        if (state.installed.isEmpty()) {
            BrowserCard(
                title = "No browser found",
                subtitle = "Install Firefox (org.mozilla.firefox) or any other browser, then reopen this window.",
                iconRes = R.drawable.ic_app_browser,
                trailing = null,
            )
        } else {
            state.installed.forEach { browser ->
                val chosen = browser.packageName == state.targetPackage()
                BrowserCard(
                    title = browser.label,
                    subtitle = browser.packageName + if (browser.isDefault) "  ·  system default" else "",
                    drawable = browser.icon,
                    iconRes = R.drawable.ic_app_browser,
                    trailing = if (chosen) "Preferred" else "Use",
                    accent = chosen,
                    onClick = {
                        state.chooseBrowser(browser.packageName)
                        onChooseBrowser(browser.packageName)
                    },
                )
                Spacer(Modifier.height(6.dp))
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            text = "Bare addresses become https:// links; anything else is searched with DuckDuckGo. " +
                "Firefox is preferred when installed - it is MPL-2.0 licensed and is never bundled in this APK.",
            color = TextTertiary,
            fontSize = 11.5.sp,
        )
    }
}

@Composable
private fun BrowserCard(
    title: String,
    subtitle: String,
    iconRes: Int,
    trailing: String?,
    drawable: android.graphics.drawable.Drawable? = null,
    accent: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Card)
            .background(if (accent) Color(0x1F4CC2FF) else CardSurface)
            .border(1.dp, if (accent) Color(0x594CC2FF) else StrokeSubtle, Card)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (drawable != null) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { context ->
                    android.widget.ImageView(context).apply { setImageDrawable(drawable) }
                },
                modifier = Modifier.size(26.dp),
            )
        } else {
            Box(Modifier.size(26.dp).clip(CircleShape).background(Color(0x224CC2FF)), contentAlignment = Alignment.Center) {
                Icon(painterResource(iconRes), null, tint = AccentBlue, modifier = Modifier.size(15.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TextTertiary, fontSize = 11.sp)
        }
        if (trailing != null) {
            Text(
                text = trailing,
                color = if (accent) AccentBlue else TextSecondary,
                fontSize = 12.sp,
                fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clip(Pill)
                    .background(if (accent) Color(0x224CC2FF) else Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
