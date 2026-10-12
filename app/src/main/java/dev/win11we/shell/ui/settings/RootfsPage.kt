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
import androidx.compose.runtime.LaunchedEffect
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
import dev.win11we.shell.runtime.DesktopEngineReadiness
import dev.win11we.shell.runtime.FirefoxSession
import dev.win11we.shell.runtime.NativeBinaries
import dev.win11we.shell.runtime.PrepareStatus
import dev.win11we.shell.runtime.RootfsProvisioner
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
    // imports only used inside the composable body below
    val context = LocalContext.current
    val rootfs = remember { RootfsHolder.get(context) }
    var importTarget by remember { mutableStateOf<Artifact?>(null) }

    // Scanning hashes archives, so it happens off the UI thread and never during layout.
    LaunchedEffect(rootfs) { rootfs.refreshAsync() }

    /** Every worker is guarded: a failure becomes a message, never a dead process. */
    fun onWorker(name: String, work: () -> Unit) = startWorker(rootfs, name, work)

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val artifact = importTarget
        importTarget = null
        if (uri != null && artifact != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "picked file"
            onWorker("import") {
                val result = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input -> rootfs.import(artifact, input, name) }
                }.getOrNull()
                val message = result?.message ?: "Could not read that file"
                rootfs.setMessage(artifact.id, message)
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        SpaceRow(rootfs)
        Spacer(Modifier.height(10.dp))
        rootfs.all().forEach { artifact ->
            ArtifactCard(
                artifact = artifact,
                rootfs = rootfs,
                onWorker = { name, work -> startWorker(rootfs, name, work) },
                onImport = {
                    importTarget = artifact
                    importLauncher.launch(arrayOf("*/*"))
                },
            )
            Spacer(Modifier.height(8.dp))
        }
        ProvisionCard(rootfs, context)
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Hashes are pinned from the publishers' own checksums (Ubuntu SHA256SUMS, the Ubuntu " +
                "archive Packages index, Mozilla's SHA512SUMS). A download that does not match is deleted, " +
                "never unpacked. Extraction runs in-app (no root, no native tar): the Ubuntu rootfs and " +
                "Firefox's .tar.xz are unpacked by the shell's own TAR reader; device nodes are emulated " +
                "by proot at runtime.",
            color = TextTertiary,
            fontSize = 11.sp,
        )
    }
}

/**
 * The one-tap Firefox ESR setup: rootfs download+extract -> Firefox download+
 * install -> apt provisioning inside proot -> readiness re-evaluated. Every step
 * logs to the shared log (Terminal ▸ `log`) and the card shows the outcome;
 * a failure names the step, and an apt failure carries its exit code plus the
 * last [RootfsProvisioner.TAIL_LINES] output lines for the card. Runs on the
 * page's guarded worker (rules 1+2).
 */
private fun runFirefoxSetup(context: android.content.Context, rootfs: RootfsManager): Pair<String, List<String>> {
    val appContext = context.applicationContext
    val binaries = NativeBinaries.from(
        nativeLibraryDirPath = appContext.applicationInfo.nativeLibraryDir,
        pins = NativeBinaries.loadPinsFromAssets(appContext.assets),
    )
    val prepare = binaries.prepare(NativeBinaries.REQUIRED_PROOT_ONLY)
    if (prepare.status != PrepareStatus.READY) return "Cannot continue: ${prepare.message}" to emptyList()
    val log = dev.win11we.shell.ui.terminal.SharedLog.buffer
    fun step(line: String) =
        log.add(dev.win11we.shell.ui.terminal.TerminalLine(dev.win11we.shell.ui.terminal.LineKind.SYSTEM, "firefox setup: $line"))

    val rootfsArtifact = dev.win11we.shell.runtime.download.RuntimeManifest.ROOTFS
    if (!rootfs.rootfsReady) {
        if (rootfs.stateOf(rootfsArtifact.id) != ArtifactState.VERIFIED) {
            step("downloading the Ubuntu rootfs (27 MB)")
            val download = rootfs.download(rootfsArtifact)
            if (!download.success) return "Rootfs download failed: ${download.message}" to emptyList()
        }
        step("extracting the rootfs")
        if (!rootfs.extract(rootfsArtifact)) {
            return "Rootfs extraction failed: ${rootfs.messages[rootfsArtifact.id] ?: "see Terminal ▸ log"}" to emptyList()
        }
    }
    val firefox = dev.win11we.shell.runtime.download.RuntimeManifest.FIREFOX_ESR
    if (!FirefoxSession.isInstalled(rootfs.rootfsDir)) {
        if (rootfs.stateOf(firefox.id) != ArtifactState.VERIFIED) {
            step("downloading Firefox ESR (64 MB)")
            val download = rootfs.download(firefox)
            if (!download.success) return "Firefox download failed: ${download.message}" to emptyList()
        }
        step("installing Firefox into the rootfs")
        if (!rootfs.extract(firefox)) {
            return "Firefox install failed: ${rootfs.messages[firefox.id] ?: "see Terminal ▸ log"}" to emptyList()
        }
    }
    step("provisioning GTK3/fonts/CA certs with apt inside proot (several minutes)")
    val provision = RootfsProvisioner.provision(binaries, rootfs.rootfsDir, appContext.filesDir, log)
    if (!provision.success) return "Provisioning failed: ${provision.message}" to provision.tailLines
    return "Firefox ESR is ready - open the Browser window, pick Firefox ESR and press Open." to emptyList()
}

/**
 * The Firefox ESR card: one-tap full setup, plus "Provision only" for rerunning
 * the apt-inside-proot step that installs GTK3, X11, D-Bus, ALSA, fonts and CA
 * certs. Everything runs on the page's guarded worker; every line lands in the
 * shared log.
 */
@Composable
private fun ProvisionCard(rootfs: RootfsManager, context: android.content.Context) {
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // Detail rows under the message: the last 30 apt lines after a provision
    // failure, or the Provision doctor's per-prerequisite rows. The shared log
    // (Terminal ▸ log) always gets the same lines.
    var detailLines by remember { mutableStateOf<List<String>>(emptyList()) }
    val canSetup = !working && !rootfs.busy
    val canProvision = canSetup && rootfs.rootfsReady

    /**
     * The Provision doctor: every prerequisite, first failure named in plain
     * language. The last row is not a file check - it really execs proot with a
     * trivial guest command (`true`, `id`, `apt-get --version`), so an argument,
     * ptrace/seccomp or fake-root problem is caught in seconds instead of
     * minutes later as a bare "apt-get failed".
     */
    fun runDoctor() {
        startWorker(rootfs, "provision-doctor") {
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                working = true
                detailLines = emptyList()
                message = "Checking prerequisites, then running a real proot smoke test (a few seconds)…"
            }
            val outcome: Pair<String, List<String>> = runCatching {
                val binaries = NativeBinaries.from(
                    nativeLibraryDirPath = context.applicationInfo.nativeLibraryDir,
                    pins = NativeBinaries.loadPinsFromAssets(context.assets),
                )
                val report = dev.win11we.shell.runtime.ProvisionDoctor.run(binaries, rootfs.rootfsDir, context.filesDir)
                val log = dev.win11we.shell.ui.terminal.SharedLog.buffer
                report.checks.forEach { check ->
                    log.add(
                        dev.win11we.shell.ui.terminal.TerminalLine(
                            when (check.state) {
                                dev.win11we.shell.runtime.CheckState.PASS -> dev.win11we.shell.ui.terminal.LineKind.INFO
                                dev.win11we.shell.runtime.CheckState.WARN -> dev.win11we.shell.ui.terminal.LineKind.SYSTEM
                                dev.win11we.shell.runtime.CheckState.FAIL -> dev.win11we.shell.ui.terminal.LineKind.ERROR
                            },
                            "doctor: ${check.line()}",
                        ),
                    )
                }
                log.add(
                    dev.win11we.shell.ui.terminal.TerminalLine(
                        dev.win11we.shell.ui.terminal.LineKind.SYSTEM,
                        "doctor: ${report.summary()}",
                    ),
                )
                report.headline() to report.checks.map { it.line() }
            }.getOrElse { ("Failed: ${it.javaClass.simpleName}: ${it.message}") to emptyList() }
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                working = false
                message = outcome.first
                detailLines = outcome.second
            }
        }
    }

    fun runSetup(fullChain: Boolean) {
        startWorker(rootfs, if (fullChain) "firefox-setup" else "provision") {
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                working = true
                detailLines = emptyList()
                message = if (fullChain) {
                    "Setting up Firefox ESR - downloads, extraction and apt can take several minutes; progress in Terminal ▸ log."
                } else {
                    "Provisioning browser libraries with apt inside proot…"
                }
            }
            val outcome: Pair<String, List<String>> = runCatching {
                if (fullChain) {
                    runFirefoxSetup(context, rootfs)
                } else {
                    val binaries = NativeBinaries.from(
                        nativeLibraryDirPath = context.applicationInfo.nativeLibraryDir,
                        pins = NativeBinaries.loadPinsFromAssets(context.assets),
                    )
                    val result = RootfsProvisioner.provision(
                        binaries = binaries,
                        rootfsDir = rootfs.rootfsDir,
                        filesDir = context.filesDir,
                        log = dev.win11we.shell.ui.terminal.SharedLog.buffer,
                    )
                    result.message to result.tailLines
                }
            }.getOrElse { ("Failed: ${it.javaClass.simpleName}: ${it.message}") to emptyList() }
            androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                working = false
                message = outcome.first
                detailLines = outcome.second
            }
            DesktopEngineReadiness.refreshAsync(context)
            rootfs.invalidateScan()
            rootfs.refreshAsync()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(CardSurface)
            .border(1.dp, Color(0x22FFFFFF), CardShape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Firefox ESR setup",
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "One tap does everything the desktop browser needs: rootfs + Firefox " +
                    "download/extract, then apt inside proot installs the GTK3/X11/font/CA stack (" +
                    RootfsProvisioner.BROWSER_PACKAGES.size + " pinned packages).",
                color = TextTertiary,
                fontSize = 11.sp,
            )
            message?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = TextSecondary, fontSize = 11.sp)
            }
            if (detailLines.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = detailLines.joinToString("\n"),
                    color = ErrorRed,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (working) "Working…" else "Set up everything",
                color = if (canSetup) Color(0xFF0B1220) else TextTertiary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(ChipShape)
                    .background(if (canSetup) AccentBlue else Color(0x22FFFFFF))
                    .then(if (canSetup) Modifier.clickable { runSetup(fullChain = true) } else Modifier)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Provision only",
                color = if (canProvision) TextSecondary else TextTertiary,
                fontSize = 11.5.sp,
                modifier = Modifier
                    .clip(ChipShape)
                    .then(if (canProvision) Modifier.clickable { runSetup(fullChain = false) } else Modifier)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Run doctor",
                color = if (canSetup) TextSecondary else TextTertiary,
                fontSize = 11.5.sp,
                modifier = Modifier
                    .clip(ChipShape)
                    .then(if (canSetup) Modifier.clickable { runDoctor() } else Modifier)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

/** Runs [work] on a guarded daemon thread; any Throwable becomes a status message. */
private fun startWorker(rootfs: RootfsManager, name: String, work: () -> Unit) {
    Thread {
        try {
            work()
        } catch (error: Throwable) {
            rootfs.reportFailure(name, error)
        }
    }.apply {
        this.name = "rootfs-$name"
        isDaemon = true
        start()
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
private fun ArtifactCard(
    artifact: Artifact,
    rootfs: RootfsManager,
    onWorker: (String, () -> Unit) -> Unit,
    onImport: () -> Unit,
) {
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
                        onWorker("download") { rootfs.download(artifact) }
                    }
                    Chip("Import...") { onImport() }
                }
                ArtifactState.VERIFIED -> {
                    if (artifact.id == dev.win11we.shell.runtime.download.RuntimeManifest.ROOTFS.id) {
                        Chip("Extract") { onWorker("extract") { rootfs.extract(artifact) } }
                    }
                    Chip("Re-verify") { onWorker("verify") { rootfs.download(artifact) } }
                    Chip("Remove", destructive = true) { onWorker("remove") { rootfs.remove(artifact) } }
                }
                ArtifactState.READY -> Chip("Remove", destructive = true) { onWorker("remove") { rootfs.remove(artifact) } }
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
