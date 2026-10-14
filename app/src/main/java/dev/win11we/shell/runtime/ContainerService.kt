package dev.win11we.shell.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.win11we.shell.MainActivity
import dev.win11we.shell.R
import dev.win11we.shell.container.Container
import dev.win11we.shell.container.ContainerActionResult
import dev.win11we.shell.container.ContainerRuntime
import dev.win11we.shell.container.ContainerStatus
import dev.win11we.shell.ui.terminal.SharedLog
import java.io.File

/**
 * Host paths of the runtime. Kept in one place (and free of Android types where it
 * matters) so R3's downloader, R4's self-test and R2's launcher all agree.
 */
object RuntimePaths {
    /** The private X socket the app's own X server will listen on (D1). */
    const val X_DISPLAY = ":100"

    fun xSocketDir(filesDir: File) = File(filesDir, "tmp")

    /**
     * Host directory bound at the guest's `/tmp/.X11-unix` so the conventional
     * socket directory exists inside the rootfs. The server itself listens on the
     * **abstract** socket (which proot passes through untouched): Android offers
     * no public API to bind a path-form local server socket, and libX11 falls
     * back from the missing path form to the abstract one automatically.
     */
    fun xSocketUnixDir(filesDir: File) = File(xSocketDir(filesDir), ".X11-unix")

    /** Where a path-form socket would live; kept for docs/tools, not bound (see above). */
    fun xSocket(filesDir: File) = File(xSocketUnixDir(filesDir), "X100")
    fun pulseSocket(filesDir: File) = File(xSocketDir(filesDir), "pulse-native")

    /** The generated guest DNS file, bound over the guest's /etc/resolv.conf. */
    fun resolvConf(filesDir: File) = File(xSocketDir(filesDir), "resolv.conf")

    /** Writes (or refreshes) [resolvConf] with the public-resolver lines. */
    fun ensureResolvConf(filesDir: File): File = RootfsProvisioner.ensureResolvConf(resolvConf(filesDir))

    /** Where R3 downloads and unpacks the Ubuntu rootfs. */
    fun defaultRootfs(filesDir: File) = File(filesDir, "rootfs")

    /**
     * THE rootfs every part of the app must agree on: the user's
     * `container.rootfsLocation` setting when set, else [defaultRootfs].
     *
     * This existed implicitly in three call sites (browser launch, container
     * launcher, the desktop layer) and NOT in two others (Diagnostics, which therefore
     * skipped all five X self-test rows whenever the setting pointed elsewhere,
     * and the download page). One function, so the self-test, Provision and the
     * launcher can never disagree about which directory they are judging.
     */
    fun configuredRootfs(filesDir: File, location: String?): File =
        location?.takeIf { it.isNotBlank() }?.let { File(it) } ?: defaultRootfs(filesDir)

    /** The Firefox ESR session's persistent profile host directory (D1/R3). */
    fun browserProfileDir(filesDir: File) = File(filesDir, "browser/firefox-profile")

    /** Where R3's downloader keeps the pinned artifacts (rootfs, debs, Firefox). */
    fun downloadDir(filesDir: File) = File(filesDir, "downloads")

    /**
     * The host directory every guest session binds at `/dev/shm`.
     *
     * Android's own `/dev` has NO `shm` entry (bionic has no SysV/POSIX shm
     * mount), and the guest tree binds `/dev` from the host - so without this,
     * Chromium-family browsers (shared-memory transport), Gecko and Wine's
     * esync all fail or crash in ways that read as "the browser is broken".
     * One directory under the app's private files dir, world-rwx + sticky
     * (mode 1777, exactly what a real /dev/shm carries), bound into every jail.
     */
    fun shmDir(filesDir: File) = File(xSocketDir(filesDir), "shm")

    /**
     * Creates [shmDir] with /dev/shm conventions: 1777 (sticky + world rwx).
     * The sticky bit needs [android.system.Os.chmod] (java.io.File cannot
     * express it); both steps are best-effort - the app uid owns the directory
     * either way, and guests run as the same uid under proot's fake root.
     */
    fun ensureShmDir(filesDir: File): File {
        val dir = shmDir(filesDir)
        dir.mkdirs()
        runCatching {
            dir.setReadable(true, false)
            dir.setWritable(true, false)
            dir.setExecutable(true, false)
        }
        runCatching { android.system.Os.chmod(dir.absolutePath, 0b1_111_111_111) } // 01777
        return dir
    }

    /** True when the rootfs archive has been unpacked (bin/ and usr/ exist). */
    fun rootfsExtracted(rootfsDir: File): Boolean =
        rootfsDir.isDirectory && File(rootfsDir, "bin").isDirectory && File(rootfsDir, "usr").isDirectory

    /**
     * mkdirs only - it makes a directory, NOT a Wine prefix. Callers are the
     * Firefox session's profile directory and [WinePrefixManager.create] (which
     * then runs the real `wineboot --init`). A directory alone is never treated
     * as a prefix: [ContainerLauncher.validate] refuses an un-initialised one
     * with that exact reason, and [WinePrefixManager.inspect] reports it as
     * UNINITIALISED rather than usable.
     */
    fun ensurePrefix(prefixPath: String): File = File(prefixPath).also { it.mkdirs() }
}

/** Process-wide holder so the service, the windows and Task Manager share one launcher. */
object RuntimeHolder {
    @Volatile
    var launcher: ContainerLauncher? = null
        private set

    @Volatile
    var rootfsDir: File? = null
        private set

    fun init(context: Context, rootfsOverride: String? = null): ContainerLauncher {
        val appContext = context.applicationContext
        val binaries = NativeBinaries.from(
            nativeLibraryDirPath = appContext.applicationInfo.nativeLibraryDir,
            pins = NativeBinaries.loadPinsFromAssets(appContext.assets),
        )
        rootfsDir = rootfsOverride?.let { File(it) } ?: RuntimePaths.defaultRootfs(appContext.filesDir)
        return launcher ?: ContainerLauncher(binaries = binaries, log = SharedLog.buffer).also { launcher = it }
    }

    /** Builds the request R2 launches; `wine64 cmd` is the default guest command. */
    fun request(
        container: Container,
        context: Context,
        guestCommand: List<String> = listOf("wine64", "cmd"),
    ): RuntimeRequest {
        val appContext = context.applicationContext
        val filesDir = appContext.filesDir
        val resolvConf = runCatching { RuntimePaths.ensureResolvConf(filesDir) }.getOrNull()
        // Every guest gets the conventional /dev/shm (Android's /dev has none):
        // Chromium/Electron transports, Gecko shm and Wine esync need it.
        val shmDir = runCatching { RuntimePaths.ensureShmDir(filesDir) }.getOrNull()
        return RuntimeRequest(
            container = container,
            rootfsDir = rootfsDir ?: RuntimePaths.defaultRootfs(filesDir),
            xDisplay = RuntimePaths.X_DISPLAY,
            audioSocket = RuntimePaths.pulseSocket(filesDir).absolutePath,
            guestCommand = guestCommand,
            binds = listOfNotNull(
                resolvConf?.let { Bind(it.absolutePath, "/etc/resolv.conf") },
                shmDir?.let { Bind(it.absolutePath, "/dev/shm") },
            ),
        )
    }

    /**
     * Builds the desktop-browser session request (Firefox ESR): native arm64, so
     * no box64 and no Wine environment - [FirefoxEnvironment] instead. The guest
     * command is the D-Bus-wrapped [FirefoxSession.sessionCommand], so the
     * session owns a private bus at a fixed address and a later [handoff] request
     * reaches the running instance through it. The profile directory doubles as
     * the request's "prefix" and is created here so the launcher's validation
     * passes on first launch.
     *
     * [handoff] builds the short-lived sibling process that hands a URL to the
     * ALREADY-running session: same binds, same bus address, tracked under its
     * own id so the launcher does not reject it as a double start.
     */
    fun firefoxRequest(
        context: Context,
        url: String? = null,
        rootfsOverride: String? = null,
        handoff: Boolean = false,
    ): RuntimeRequest {
        val appContext = context.applicationContext
        val filesDir = appContext.filesDir
        val profile = RuntimePaths.browserProfileDir(filesDir)
        RuntimePaths.ensurePrefix(profile.absolutePath)
        val rootfs = rootfsOverride?.let { File(it) } ?: rootfsDir ?: RuntimePaths.defaultRootfs(filesDir)
        // First-load speed (Goal 2 step 2): enterprise policies + user.js + a
        // deterministic profiles.ini, materialised idempotently before the
        // session starts. A failure here must never block the launch - it
        // degrades to a slower first run, named in the shared log
        // (crash-safety rule 1).
        runCatching {
            val written = FirefoxPolicies.materialize(rootfs, profile)
            if (written.isNotEmpty()) {
                SharedLog.info("firefox: startup files written: " + written.joinToString { it.name })
            }
        }.onFailure { failure ->
            SharedLog.info(
                "firefox: startup files NOT written (${failure.javaClass.simpleName}: " +
                    "${failure.message}) - first run stays slow",
            )
        }
        val resolvConf = RuntimePaths.ensureResolvConf(filesDir)
        val shmDir = runCatching { RuntimePaths.ensureShmDir(filesDir) }.getOrNull()
        val id = if (handoff) "${FirefoxSession.SESSION_ID}-handoff" else FirefoxSession.SESSION_ID
        val name = if (handoff) "${FirefoxSession.SESSION_NAME} - URL handoff" else FirefoxSession.SESSION_NAME
        return RuntimeRequest(
            container = Container.defaults(id, name, profile.absolutePath),
            rootfsDir = rootfs,
            xDisplay = RuntimePaths.X_DISPLAY,
            guestCommand = FirefoxSession.sessionCommand(url, withDesktop = !handoff),
            guestPrefix = FirefoxSession.GUEST_PROFILE_DIR,
            binds = FirefoxSession.binds(profile, RuntimePaths.xSocketUnixDir(filesDir), resolvConf, shmDir),
            environment = FirefoxEnvironment.build(RuntimePaths.X_DISPLAY),
        )
    }
}

/** [ContainerRuntime] backed by the launcher, so the UI stops saying "not installed". */
class LauncherRuntime(
    private val launcher: ContainerLauncher,
    private val context: Context,
    private val requestFactory: (Container) -> RuntimeRequest = { RuntimeHolder.request(it, context) },
) : ContainerRuntime {

    override val ready: Boolean get() = launcher.binaries.prepare().status == PrepareStatus.READY

    override fun start(container: Container): ContainerActionResult {
        val result = launcher.start(requestFactory(container))
        return if (result.success) {
            ContainerActionResult.ok(result.message)
        } else {
            ContainerActionResult.fail("[${result.failure}] ${result.message}")
        }
    }

    override fun stop(container: Container): ContainerActionResult =
        ContainerActionResult.ok(launcher.stop(container.id).message)

    override fun restart(container: Container): ContainerActionResult {
        val result = launcher.restart(requestFactory(container))
        return if (result.success) {
            ContainerActionResult.ok(result.message)
        } else {
            ContainerActionResult.fail("[${result.failure}] ${result.message}")
        }
    }

    override fun statusOf(container: Container): ContainerStatus = launcher.statusOf(container.id)

    override fun logExcerpt(container: Container, lines: Int): String {
        val own = launcher.log.snapshot()
            .filter { it.text.contains(container.name) || it.text.contains(container.id) }
            .takeLast(lines)
        return if (own.isEmpty()) launcher.log.tail(lines) else own.joinToString("\n") { it.text }
    }
}

/**
 * The foreground service that owns the container processes (R2). The app owns the
 * processes - no Termux, no adb: the service is started from the shell and keeps a
 * notification alive while anything is running, and it stops every child on destroy.
 */
class ContainerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        RuntimeHolder.init(applicationContext)
        SharedLog.system("ContainerService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching { startForeground(NOTIFICATION_ID, buildNotification("Container runtime idle")) }
            .onFailure { SharedLog.error("startForeground failed: ${it.message}") }
        when (intent?.action) {
            ACTION_STOP_ALL -> {
                RuntimeHolder.launcher?.stopAll()
                SharedLog.system("All containers stopped")
                stopSelf()
            }
            else -> SharedLog.system("ContainerService started")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        RuntimeHolder.launcher?.stopAll(reason = "service destroyed")
        SharedLog.system("ContainerService destroyed")
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Container runtime",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps Wine/container processes alive while the shell runs them"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ContainerService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Win11 WE container runtime")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_app_container)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .addAction(0, "Stop all", stopIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "container-runtime"
        const val NOTIFICATION_ID = 0x5147
        const val ACTION_STOP_ALL = "dev.win11we.shell.action.STOP_ALL_CONTAINERS"

        fun start(context: Context) {
            val intent = Intent(context, ContainerService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopAll(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ContainerService::class.java).setAction(ACTION_STOP_ALL),
            )
        }
    }
}
