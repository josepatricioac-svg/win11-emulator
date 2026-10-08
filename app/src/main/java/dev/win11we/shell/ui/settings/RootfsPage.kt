package dev.win11we.shell.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.win11we.shell.runtime.download.Artifact
import dev.win11we.shell.runtime.download.ArtifactState
import dev.win11we.shell.runtime.download.DownloadSession
import dev.win11we.shell.runtime.download.RootfsHolder
import dev.win11we.shell.runtime.download.RootfsManager
import dev.win11we.shell.ui.theme.AccentBlue
import dev.win11we.shell.ui.theme.CardSurface
import dev.win11we.shell.ui.theme.ShellSurfaces
import dev.win11we.shell.ui.theme.TextPrimary
import dev.win11we.shell.ui.theme.TextSecondary
import dev.win11we.shell.ui.theme.TextTertiary

private val CardShape = RoundedCornerShape(9.dp)
private val ChipShape = RoundedCornerShape(50)
private val OkGreen = Color(0xFF7BE0A8)
private val ErrorRed = Color(0xFFFF8A7A)
private val WarnAmber = Color(0xFFFFC44D)

/**
 * R3: first-run download of the Ubuntu rootfs and the Wine packages - pinned
 * SHA-256 (fail closed), resumable, with a free-space check, progress and SAF import.
 */
@Composable
fun RootfsPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val rootfs = remember { RootfsHolder.get(context) }
    var importTarget by remember { mutableStateOf<Artifact?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val artifact = importTarget
        importTarget = null
        if (uri != null && artifact != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "picked file"
            Thread {
                val result = context.contentResolver.openInputStream(uri)?.use { input ->
                    rootfs.import(artifact, input, name)
                }
                val message = result?.message ?: "Could not read that file"
                rootfs.messages[artifact.id] = message
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }.apply { isDaemon = true; start() }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        SpaceRow(rootfs)
        Spacer(Modifier.height(10.dp))
        rootfs.all().forEach { artifact ->
            ArtifactCard(
                artifact = artifact,
                rootfs = rootfs,
                onImport = {
                    importTarget = artifact
                    importLauncher.launch(arrayOf("*/*"))
                },
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(
            text = "Hashes are pinned from the publishers' own checksums (Ubuntu SHA256SUMS, the Ubuntu " +
                "archive Packages index). A download that does not match is deleted, never unpacked. " +
                "Extraction needs proot's tar (R1 binaries).",
            color = TextTertiary,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun SpaceRow(rootfs: RootfsManager) {
    val free = rootfs.freeBytes()
    val needed = dev.win11we.shell.runtime.download.RuntimeManifest.requiredFreeBytes
    val enough = free >= needed
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (enough) OkGreen else WarnAmber))
        Spacer(Modifier.width(9.dp))
        Text(
            text = "${DownloadSession.formatBytes(free)} free · ${DownloadSession.formatBytes(needed)} needed for the full set",
            color = if (enough) TextSecondary else WarnAmber,
            fontSize = 11.5.sp,
        )
    }
}

@Composable
private fun ArtifactCard(artifact: Artifact, rootfs: RootfsManager, onImport: () -> Unit) {
    val state = rootfs.states[artifact.id] ?: ArtifactState.NOT_DOWNLOADED
    val progress = rootfs.progress[artifact.id] ?: 0f
    val message = rootfs.messages[artifact.id]

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(CardSurface)
            .border(1.dp, ShellSurfaces.stroke, CardShape)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(
                        when (state) {
                            ArtifactState.READY, ArtifactState.VERIFIED -> OkGreen
                            ArtifactState.FAILED -> ErrorRed
                            ArtifactState.DOWNLOADING, ArtifactState.EXTRACTING, ArtifactState.VERIFYING -> AccentBlue
                            ArtifactState.PARTIAL -> WarnAmber
                            ArtifactState.NOT_DOWNLOADED -> TextTertiary
                        },
                    ),
            )
            Spacer(Modifier.width(9.dp))
            Text(artifact.label, color = TextPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            Text(state.label, color = TextSecondary, fontSize = 11.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(artifact.description, color = TextTertiary, fontSize = 11.sp)
        Text(
            text = "${artifact.sizeLabel()} · sha256 ${artifact.sha256.take(16)}… · ${artifact.url}",
            color = TextTertiary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )

        if (state == ArtifactState.DOWNLOADING) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Color(0x22FFFFFF))) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(AccentBlue),
                )
            }
            Text("${(progress * 100).toInt()}%", color = TextSecondary, fontSize = 11.sp)
        }
        if (message != null && state == ArtifactState.FAILED) {
            Spacer(Modifier.height(6.dp))
            Text(message, color = ErrorRed, fontSize = 11.sp)
        }
        if (artifact.incompleteAlone) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Not a complete Wine install on its own - the dependency tree is provisioned inside the rootfs.",
                color = WarnAmber,
                fontSize = 10.5.sp,
            )
        }

        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when (state) {
                ArtifactState.DOWNLOADING -> Chip("Cancel", destructive = true) { rootfs.cancel() }
                ArtifactState.NOT_DOWNLOADED, ArtifactState.PARTIAL, ArtifactState.FAILED -> {
                    Chip(if (state == ArtifactState.PARTIAL) "Resume" else "Download") {
                        Thread { rootfs.download(artifact) }.apply { isDaemon = true; start() }
                    }
                    Chip("Import...") { onImport() }
                }
                ArtifactState.VERIFIED -> {
                    if (artifact.id == dev.win11we.shell.runtime.download.RuntimeManifest.ROOTFS.id) {
                        Chip("Extract") {
                            Thread { rootfs.extract(artifact) }.apply { isDaemon = true; start() }
                        }
                    }
                    Chip("Re-verify") {
                        Thread { rootfs.download(artifact) }.apply { isDaemon = true; start() }
                    }
                    Chip("Remove", destructive = true) { rootfs.remove(artifact) }
                }
                ArtifactState.READY -> Chip("Remove", destructive = true) { rootfs.remove(artifact) }
                ArtifactState.EXTRACTING, ArtifactState.VERIFYING -> Chip("Working...") {}
            }
        }
    }
}

@Composable
private fun Chip(label: String, destructive: Boolean = false, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (destructive) ErrorRed else TextPrimary,
        fontSize = 11.5.sp,
        modifier = Modifier
            .clip(ChipShape)
            .background(if (destructive) Color(0x1FC42B1C) else Color(0x14FFFFFF))
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
