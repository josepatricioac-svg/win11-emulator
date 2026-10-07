package dev.win11we.shell.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.win11we.shell.runtime.NativeBinaries
import dev.win11we.shell.runtime.PrepareStatus
import dev.win11we.shell.runtime.ProotCommand
import dev.win11we.shell.ui.theme.AccentBlue
import dev.win11we.shell.ui.theme.CardSurface
import dev.win11we.shell.ui.theme.ShellSurfaces
import dev.win11we.shell.ui.theme.TextPrimary
import dev.win11we.shell.ui.theme.TextSecondary
import dev.win11we.shell.ui.theme.TextTertiary
import java.io.File

private val CardShape = RoundedCornerShape(10.dp)
private val ChipShape = RoundedCornerShape(50)
private val OkGreen = Color(0xFF7BE0A8)
private val ErrorRed = Color(0xFFFF8A7A)
private val WarnAmber = Color(0xFFFFC44D)

/**
 * R1 diagnostics: which binaries are packaged, whether they are executable, and
 * what `--version` answered. R4 adds the wine64 rows from [ProotCommand.selfTestPlan].
 */
@Composable
fun DiagnosticsScreen(state: DiagnosticsState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ShellSurfaces.windowContent)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Diagnostics", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(
            text = "Exec from nativeLibraryDir only - targetSdk 34 blocks exec() from app data.",
            color = TextTertiary,
            fontSize = 11.5.sp,
        )
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionChip("Re-check binaries") { state.check() }
            ActionChip(if (state.running) "Running..." else "Run --version probes") { state.runProbes() }
        }
        Spacer(Modifier.height(14.dp))

        val prepare = state.prepareResult
        StatusCard(
            title = "Runtime",
            status = when (prepare?.status) {
                PrepareStatus.READY -> Triple(OkGreen, "Ready", prepare.message)
                PrepareStatus.PARTIAL -> Triple(WarnAmber, "Partial", prepare.message)
                PrepareStatus.PERMISSION_DENIED -> Triple(ErrorRed, "Permission denied", prepare.message)
                PrepareStatus.NO_DIRECTORY -> Triple(ErrorRed, "No nativeLibraryDir", prepare.message)
                else -> Triple(ErrorRed, "Not installed", prepare?.message ?: "Binaries are not packaged in this APK.")
            },
        )
        Spacer(Modifier.height(10.dp))

        Label("nativeLibraryDir")
        Text(state.nativeLibraryDir.absolutePath, color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(14.dp))

        Label("Shipped binaries (app/src/main/jniLibs/arm64-v8a)")
        val checksums = state.binaries.verifyChecksums().associateBy { it.role }
        state.binaries.inventory().forEach { binary ->
            BinaryRow(binary, checksums[binary.role])
        }
        Spacer(Modifier.height(14.dp))

        Label("Probes")
        if (state.probes.isEmpty()) {
            Text(
                text = "Nothing run yet. Probes exec the shipped binaries with an explicit environment and " +
                    "report the command, exit code and a log excerpt.",
                color = TextTertiary,
                fontSize = 11.5.sp,
            )
        }
        state.probes.forEach { probe -> ProbeRow(probe.id, probe.passed, probe.summary, probe.command, probe.output, probe.durationMillis) }
        Spacer(Modifier.height(14.dp))

        Label("What R4 will run")
        ProotCommand.selfTestPlan(
            prootBinary = File(state.nativeLibraryDir, "libproot.so"),
            box64Binary = File(state.nativeLibraryDir, "libbox64.so"),
            rootfsDir = File("/data/user/0/dev.win11we.shell/files/rootfs"),
            prefixDir = File("/data/user/0/dev.win11we.shell/files/containers/default/prefix"),
        ).forEachIndexed { index, command ->
            Text(
                text = "${index + 1}. " + command.joinToString(" "),
                color = TextSecondary,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(14.dp))

        Label("Log excerpt")
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(Color(0xFF0B0F16)).padding(10.dp),
        ) {
            val excerpt = state.logExcerpt(12)
            Text(
                text = excerpt.ifBlank { "(empty)" },
                color = Color(0xFF9FE8B8),
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Vendoring and licences: docs/NATIVE_RUNTIME.md and docs/THIRD_PARTY.md.",
            color = TextTertiary,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = TextSecondary, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(5.dp))
}

@Composable
private fun ActionChip(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Color(0xFF0B1220),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(ChipShape)
            .background(AccentBlue)
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun StatusCard(title: String, status: Triple<Color, String, String>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(CardSurface)
            .border(1.dp, ShellSurfaces.stroke, CardShape)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(status.first))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("$title: ${status.second}", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(status.third, color = TextTertiary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun BinaryRow(binary: dev.win11we.shell.runtime.NativeBinary, checksum: dev.win11we.shell.runtime.ChecksumResult?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    when {
                        !binary.present -> ErrorRed
                        binary.executable -> OkGreen
                        else -> WarnAmber
                    },
                ),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = binary.label,
            color = TextPrimary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(168.dp),
        )
        Text(
            text = when {
                !binary.present && binary.role.required -> "missing (required)"
                !binary.present -> "missing (optional)"
                !binary.executable -> "present, not executable"
                checksum?.status == dev.win11we.shell.runtime.ChecksumStatus.MISMATCH -> "SHA-256 MISMATCH"
                checksum?.status == dev.win11we.shell.runtime.ChecksumStatus.NOT_PINNED ->
                    NativeBinaries.formatSize(binary.sizeBytes) + ", hash not pinned"
                else -> NativeBinaries.formatSize(binary.sizeBytes) + ", hash OK"
            },
            color = when {
                !binary.present || !binary.executable -> ErrorRed
                checksum?.status == dev.win11we.shell.runtime.ChecksumStatus.MISMATCH -> ErrorRed
                checksum?.status == dev.win11we.shell.runtime.ChecksumStatus.NOT_PINNED -> WarnAmber
                else -> TextSecondary
            },
            fontSize = 11.5.sp,
        )
    }
}

@Composable
private fun ProbeRow(
    id: String,
    passed: Boolean,
    summary: String,
    command: List<String>,
    output: String,
    durationMillis: Long,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(CardSurface)
            .border(1.dp, if (passed) Color(0x337BE0A8) else Color(0x59C42B1C), CardShape)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(id, color = TextPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(if (passed) "PASS" else "FAIL", color = if (passed) OkGreen else ErrorRed, fontSize = 11.sp)
            Spacer(Modifier.width(10.dp))
            Text("$summary · ${durationMillis}ms", color = TextTertiary, fontSize = 11.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(command.joinToString(" "), color = TextSecondary, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
        if (output.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(output, color = Color(0xFF9FE8B8), fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(8.dp))
    }
}
