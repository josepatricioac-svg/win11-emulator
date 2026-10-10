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

    /** The Firefox ESR session's persistent profile host directory (D1/R3). */
    fun browserProfileDir(filesDir: File) = File(filesDir, "browser/firefox-profile")

    /** True when the rootfs archive has been unpacked (bin/ and usr/ exist). */
    fun rootfsExtracted(rootfsDir: File): Boolean =
        rootfsDir.isDirectory && File(rootfsDir, "bin").isDirectory && File(rootfsDir, "usr").isDirectory

    /** R3/R4 create the prefix with `wineboot` before the first real launch. */
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
        return RuntimeRequest(
            container = container,
            rootfsDir = rootfsDir ?: RuntimePaths.defaultRootfs(filesDir),
            xDisplay = RuntimePaths.X_DISPLAY,
            audioSocket = RuntimePaths.pulseSocket(filesDir).absolutePath,
            guestCommand = guestCommand,
            binds = listOfNotNull(resolvConf?.let { Bind(it.absolutePath, "/etc/resolv.conf") }),
        )
    }

    /**
     * Builds the desktop-browser session request (Firefox ESR): native arm64, so
     * no box64 and no Wine environment - [FirefoxEnvironment] instead. The
     * profile directory doubles as the request's "prefix" and is created here so
     * the launcher's validation passes on first launch.
     */
    fun firefoxRequest(context: Context, url: String? = null, rootfsOverride: String? = null): RuntimeRequest {
        val appContext = context.applicationContext
        val filesDir = appContext.filesDir
        val profile = RuntimePaths.browserProfileDir(filesDir)
        RuntimePaths.ensurePrefix(profile.absolutePath)
        val resolvConf = RuntimePaths.ensureResolvConf(filesDir)
        return RuntimeRequest(
            container = Container.defaults(FirefoxSession.SESSION_ID, FirefoxSession.SESSION_NAME, profile.absolutePath),
            rootfsDir = rootfsOverride?.let { File(it) } ?: rootfsDir ?: RuntimePaths.defaultRootfs(filesDir),
            xDisplay = RuntimePaths.X_DISPLAY,
            guestCommand = FirefoxSession.guestCommand(url),
            guestPrefix = FirefoxSession.GUEST_PROFILE_DIR,
            binds = FirefoxSession.binds(profile, RuntimePaths.xSocketUnixDir(filesDir), resolvConf),
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
