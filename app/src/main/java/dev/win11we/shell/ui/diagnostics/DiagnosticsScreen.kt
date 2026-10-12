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
            ActionChip(if (state.selfTestRunning) "Testing..." else "Run self-test") { state.runSelfTest() }
            ActionChip(if (state.xSelfTestRunning) "Testing..." else "Run X self-test") { state.runXSelfTest() }
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

        Label("Self-test (R4)")
        val report = state.selfTest
        if (report == null) {
            Text(
                text = "Runs box64 --version, then wine64 --version and `wine64 cmd /c echo hello` inside proot. " +
                    "Steps without prerequisites are skipped with the reason, never passed.",
                color = TextTertiary,
                fontSize = 11.5.sp,
            )
        } else {
            SelfTestSummary(report)
            report.steps.forEachIndexed { index, step -> SelfTestRow(index + 1, step) }
        }
        Spacer(Modifier.height(14.dp))

        Label("X self-test (xeyes/xclock on :100)")
        val xReport = state.xSelfTest
        if (xReport == null) {
            Text(
                text = "Runs xdpyinfo/xwininfo (instant handshake + RENDER probes) and launches xeyes " +
                    "(core X11) and xclock (Xaw+Xft = the RENDER glyph path) inside proot against the " +
                    "shell's own X server. For the clients, PASS = stayed connected the whole window; " +
                    "a FAIL carries the exit code and the connection's protocol trace. Open Start ▸ X " +
                    "Desktop once first so the server is bound; the clients come from x11-utils/x11-apps " +
                    "(the Provision step installs them).",
                color = TextTertiary,
                fontSize = 11.5.sp,
            )
        } else {
            SelfTestSummary(xReport)
            xReport.steps.forEachIndexed { index, step -> SelfTestRow(index + 1, step) }
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
private fun SelfTestSummary(report: dev.win11we.shell.runtime.SelfTestReport) {
    val colour = when {
        report.allGreen -> OkGreen
        report.failed > 0 -> ErrorRed
        else -> WarnAmber
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(CardSurface)
            .border(1.dp, colour.copy(alpha = 0.45f), CardShape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(colour))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = when {
                    report.allGreen -> "All green"
                    report.failed > 0 -> "${report.failed} step(s) failed"
                    else -> "Incomplete - ${report.skipped} step(s) skipped"
                },
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(report.summary(), color = TextTertiary, fontSize = 11.sp)
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SelfTestRow(index: Int, step: dev.win11we.shell.runtime.SelfTestStep) {
    val colour = when (step.verdict) {
        dev.win11we.shell.runtime.StepVerdict.PASS -> OkGreen
        dev.win11we.shell.runtime.StepVerdict.FAIL -> ErrorRed
        dev.win11we.shell.runtime.StepVerdict.SKIPPED -> WarnAmber
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(CardSurface)
            .border(1.dp, colour.copy(alpha = 0.35f), CardShape)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$index.", color = TextTertiary, fontSize = 11.5.sp)
            Spacer(Modifier.width(8.dp))
            Text(step.label, color = TextPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(step.verdict.name, color = colour, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text("${step.durationMillis}ms", color = TextTertiary, fontSize = 10.5.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "$ ${step.command.joinToString(" ")}",
            color = TextSecondary,
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
        )
        if (step.exitCode != null) {
            Text("exit ${step.exitCode}", color = TextTertiary, fontSize = 10.5.sp)
        }
        step.reason?.let { reason ->
            Spacer(Modifier.height(4.dp))
            Text(reason, color = if (step.verdict == dev.win11we.shell.runtime.StepVerdict.FAIL) ErrorRed else WarnAmber,
                fontSize = 11.sp)
        }
        if (step.output.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0xFF0B0F16)).padding(9.dp),
            ) {
                Text(step.output, color = Color(0xFF9FE8B8), fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(Modifier.height(8.dp))
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
