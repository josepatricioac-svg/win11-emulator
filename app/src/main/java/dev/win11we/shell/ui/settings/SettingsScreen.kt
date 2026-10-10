package dev.win11we.shell.ui.settings

import android.os.Build
import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.win11we.shell.BuildConfig
import dev.win11we.shell.R
import dev.win11we.shell.settings.Box64Preset
import dev.win11we.shell.settings.SettingsStore
import dev.win11we.shell.settings.TaskbarAlignment
import dev.win11we.shell.settings.ThemeMode
import dev.win11we.shell.ui.browser.Browsers
import dev.win11we.shell.runtime.DesktopEngineReadiness
import dev.win11we.shell.ui.browser.DesktopBrowserCatalogue
import dev.win11we.shell.ui.browser.DesktopEngineLabels
import dev.win11we.shell.ui.theme.AccentBlue
import dev.win11we.shell.ui.theme.CardSurface
import dev.win11we.shell.ui.theme.HoverLight
import dev.win11we.shell.ui.theme.StrokeSubtle
import dev.win11we.shell.ui.theme.TextPrimary
import dev.win11we.shell.ui.theme.TextSecondary
import dev.win11we.shell.ui.theme.TextTertiary
import dev.win11we.shell.ui.theme.ShellSurfaces
import java.io.File
import java.util.Locale

private val CardShape = RoundedCornerShape(9.dp)
private val RowShape = RoundedCornerShape(6.dp)

/** Settings window: navigation pane + the selected page (or search results). */
@Composable
fun SettingsScreen(state: SettingsState, store: SettingsStore, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxSize().background(ShellSurfaces.windowContent)) {
        Column(
            modifier = Modifier
                .width(206.dp)
                .fillMaxHeight()
                .background(Color(0x07FFFFFF))
                .verticalScroll(rememberScrollState())
                .padding(vertical = 10.dp, horizontal = 8.dp),
        ) {
            AccountHeader()
            Spacer(Modifier.height(10.dp))
            SettingsSection.entries.forEach { section ->
                val active = !state.searching && state.section == section
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RowShape)
                        .background(if (active) HoverLight else Color.Transparent)
                        .clickable(onClickLabel = section.label) {
                            state.section = section
                            state.query = ""
                        }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(3.dp).height(16.dp).clip(CircleShape).background(if (active) AccentBlue else Color.Transparent))
                    Spacer(Modifier.width(9.dp))
                    Icon(painterResource(section.iconRes), null, tint = Color.Unspecified, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = section.label,
                        color = if (active) AccentBlue else TextPrimary,
                        fontSize = 12.5.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            if (state.searching) {
                PageTitle("Search results for \"${state.query}\"")
                val results = state.results()
                if (results.isEmpty()) {
                    Hint("Nothing matches. Try \"theme\", \"browser\", \"rootfs\" or \"preset\".")
                }
                results.forEach { entry ->
                    SettingsRow(
                        title = entry.label,
                        subtitle = entry.section.label,
                        onClick = {
                            state.section = entry.section
                            state.query = ""
                        },
                    )
                }
            } else {
                when (state.section) {
                    SettingsSection.PERSONALISATION -> PersonalisationPage(store)
                    SettingsSection.APPS -> AppsPage(store)
                    SettingsSection.STORAGE -> StoragePage()
                    SettingsSection.CONTAINERS -> ContainersPage(store)
                    SettingsSection.DIAGNOSTICS -> DiagnosticsPage()
                    SettingsSection.ABOUT -> AboutPage()
                }
            }
        }
    }
}

@Composable
private fun AccountHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RowShape).background(CardSurface).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(AccentBlue.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_person), null, tint = AccentBlue, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column {
            Text("User", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("Local account", color = TextTertiary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PageTitle(title: String) {
    Text(title, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, color = TextTertiary, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun PersonalisationPage(store: SettingsStore) {
    PageTitle("Personalisation")
    Hint("Windows 11-style look. All artwork in this shell is original.")
    SettingsCard {
        RadioRow(
            title = "App theme",
            subtitle = "Applies to the shell, taskbar, Start menu and window chrome",
            options = ThemeMode.entries.map { it.label },
            selectedIndex = ThemeMode.entries.indexOf(store.themeMode),
            onSelect = { store.themeMode = ThemeMode.entries[it] },
        )
        RadioRow(
            title = "Taskbar alignment",
            subtitle = "Centre matches Windows 11; left matches Windows 10 and wide screens",
            options = TaskbarAlignment.entries.map { it.label },
            selectedIndex = TaskbarAlignment.entries.indexOf(store.taskbarAlignment),
            onSelect = { store.taskbarAlignment = TaskbarAlignment.entries[it] },
        )
        ToggleRow(
            title = "Window animations",
            subtitle = "Fade and settle when opening, closing and minimizing",
            checked = store.windowAnimations,
            onCheckedChange = { store.windowAnimations = it },
        )
    }
}

@Composable
private fun AppsPage(store: SettingsStore) {
    val context = LocalContext.current
    val installed = remember { Browsers.installed(context) }
    // Readiness is evaluated on a guarded worker (no disk I/O in composition).
    androidx.compose.runtime.LaunchedEffect(Unit) { DesktopEngineReadiness.refreshAsync(context) }
    PageTitle("Apps")
    Hint(
        "Default browser engine. Android browsers launch now; desktop engines run inside the " +
            "container on the shell's X server. Each one shows the exact step it is still waiting on.",
    )
    SettingsCard {
        installed.forEach { browser ->
            RadioRow(
                title = browser.label,
                subtitle = browser.packageName + if (browser.isDefault) "  ·  system default" else "",
                options = emptyList(),
                selectedIndex = 0,
                onSelect = {},
                checked = browser.packageName == store.browserPackage,
                onClick = { store.browserPackage = browser.packageName },
            )
        }
        if (installed.isEmpty()) {
            SettingsRow(
                title = "No browser installed",
                subtitle = "Install Firefox (org.mozilla.firefox) to make the Browser pin useful",
            )
        }
        RadioRow(
            title = "Let the system choose",
            subtitle = "Uses Android's default http handler instead of a fixed package",
            options = emptyList(),
            selectedIndex = 0,
            onSelect = {},
            checked = store.browserPackage.isBlank(),
            onClick = { store.browserPackage = "" },
        )
        DesktopBrowserCatalogue.ALL.forEach { engine ->
            RadioRow(
                title = engine.label,
                subtitle = DesktopEngineLabels.status(
                    engine,
                    DesktopEngineReadiness.ready(engine.id),
                    DesktopEngineReadiness.missing(engine.id),
                ) + "  ·  ${engine.license}",
                options = emptyList(),
                selectedIndex = 0,
                onSelect = {},
                checked = store.browserEngine == engine.id,
                onClick = { store.browserEngine = engine.id },
            )
        }
        Hint("See docs/BROWSER_ENGINES.md for what each desktop engine needs and its licence.")
        ToggleRow("Show Browser on the taskbar", "", store.showBrowserPin, { store.showBrowserPin = it })
        ToggleRow("Show Container Manager on the taskbar", "Disabled apps stay in Start ▸ All apps", store.showContainersPin, { store.showContainersPin = it })
    }
}

@Composable
private fun StoragePage() {
    val context = LocalContext.current
    val root = remember { File(context.filesDir.absolutePath.substringBeforeLast("/")) }
    val stat = remember(root) { runCatching { StatFs(root.absolutePath) }.getOrNull() }
    val freeGb = stat?.let { it.availableBytes / (1024.0 * 1024 * 1024) } ?: 0.0
    val totalGb = stat?.let { it.totalBytes / (1024.0 * 1024 * 1024) } ?: 0.0
    PageTitle("Storage")
    SettingsCard {
        SettingsRow(
            title = "Rootfs location",
            subtitle = "${root.absolutePath}/win11we  (created by the R3 download)",
        )
        SettingsRow(
            title = "Wine prefix",
            subtitle = "${root.absolutePath}/win11we/containers/default/prefix  (R2/R3)",
        )
        SettingsRow(
            title = "Free space",
            subtitle = String.format(Locale.US, "%.1f GB free of %.1f GB", freeGb, totalGb),
        )
    }
    Spacer(Modifier.height(14.dp))
    PageTitle("Runtime download")
    RootfsPage()
}

@Composable
private fun ContainersPage(store: SettingsStore) {
    PageTitle("Containers")
    SettingsCard {
        RadioRow(
            title = "Box64 preset",
            subtitle = "Default profile for new containers; per-container override arrives in P2",
            options = Box64Preset.entries.map { it.label },
            selectedIndex = Box64Preset.entries.indexOf(store.box64Preset),
            onSelect = { store.box64Preset = Box64Preset.entries[it] },
        )
        SettingsRow(title = "Runtime", subtitle = "proot / box64 / wine: not installed yet (R1, R3)")
    }
    Hint(Box64Preset.entries.first { it == store.box64Preset }.description)
}

@Composable
private fun DiagnosticsPage() {
    PageTitle("Diagnostics")
    SettingsCard {
        SettingsRow(title = "box64 --version", subtitle = "Step 1 - runs the shipped translator")
        SettingsRow(title = "wine64 --version", subtitle = "Step 2 - runs inside proot with the downloaded rootfs")
        SettingsRow(title = "wine64 cmd /c echo hello", subtitle = "Step 3 - a real Windows process must print hello")
    }
    Hint(
        "Run these from Start > All apps > Diagnostics > Run self-test. Each row is green/red/skipped with the " +
            "exact failing command and a log excerpt; steps without prerequisites are skipped, never passed. " +
            "ContainerService streams into the same log (Terminal: `log`).",
    )
}

@Composable
private fun AboutPage() {
    PageTitle("About")
    SettingsCard {
        SettingsRow(title = "App", subtitle = "Win11 Winlator Edition ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        SettingsRow(title = "Android", subtitle = "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
        SettingsRow(title = "Device", subtitle = "${Build.MANUFACTURER} ${Build.MODEL} · ${Build.SUPPORTED_ABIS.joinToString()}")
        SettingsRow(title = "Assets", subtitle = "No Microsoft fonts, wallpapers or logos; see docs/THIRD_PARTY.md")
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(CardSurface)
            .border(1.dp, StrokeSubtle, CardShape)
            .padding(vertical = 4.dp, horizontal = 4.dp),
    ) {
        content()
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RowShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 13.sp)
            if (subtitle != null) Text(subtitle, color = TextTertiary, fontSize = 11.5.sp)
        }
        if (onClick != null) {
            Icon(painterResource(R.drawable.ic_arrow_right), null, tint = TextTertiary, modifier = Modifier.size(13.dp))
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RowShape).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 13.sp)
            if (subtitle.isNotBlank()) Text(subtitle, color = TextTertiary, fontSize = 11.5.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AccentBlue,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0x33FFFFFF),
                uncheckedBorderColor = StrokeSubtle,
            ),
        )
    }
}

@Composable
private fun RadioRow(
    title: String,
    subtitle: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    checked: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val modifier = Modifier
        .fillMaxWidth()
        .clip(RowShape)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 12.dp, vertical = 11.dp)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 13.sp)
            if (subtitle.isNotBlank()) Text(subtitle, color = TextTertiary, fontSize = 11.5.sp)
            if (options.isNotEmpty()) {
                Spacer(Modifier.height(9.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEachIndexed { index, label ->
                        val active = index == selectedIndex
                        Text(
                            text = label,
                            color = if (active) Color(0xFF0B1220) else TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (active) AccentBlue else Color(0x14FFFFFF))
                                .clickable { onSelect(index) }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
                }
            }
        }
        if (onClick != null) {
            Box(
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(if (checked) AccentBlue else Color.Transparent)
                    .border(1.dp, if (checked) AccentBlue else TextTertiary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF0B1220)))
            }
        }
    }
}
