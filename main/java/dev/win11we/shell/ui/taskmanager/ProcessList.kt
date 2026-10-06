package dev.win11we.shell.ui.taskmanager

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.win11we.shell.window.WindowManager
import java.io.File

enum class ProcessKind(val label: String) {
    SHELL("Shell"),
    WINDOW("App window"),
    CONTAINER("Container"),
    WINDOWS_APP("Windows app"),
}

/** One row of the Task Manager process list. */
data class ShellProcess(
    val id: String,
    val name: String,
    val kind: ProcessKind,
    val memoryKb: Long = 0L,
    val cpuPercent: Float = 0f,
    val status: String = "Running",
    val windowId: Int? = null,
    val pid: Int? = null,
) {
    /** Nothing inside the container can be ended until R2 ships the service. */
    val endable: Boolean get() = kind == ProcessKind.WINDOW || kind == ProcessKind.SHELL
}

/** Everything /proc gives us about one process, parsed once per refresh. */
data class ProcSample(
    val pid: Int,
    val name: String,
    val rssKb: Long,
    val utimeTicks: Long,
    val stimeTicks: Long,
)

/**
 * Parsing of /proc is kept pure (string in, values out) so Task Manager is
 * unit-testable without a device.
 */
object ProcReader {

    private val STATUS_NAME = Regex("^Name:\\s*(.+)$", RegexOption.MULTILINE)
    private val STATUS_RSS = Regex("^VmRSS:\\s*(\\d+)\\s*kB", RegexOption.MULTILINE)

    /** `/proc/<pid>/stat`: field 2 is `(comm)` and may contain spaces, so split after ')'. */
    fun parseStat(stat: String): Triple<Int, Long, Long>? {
        val open = stat.indexOf('(')
        val close = stat.lastIndexOf(')')
        if (open < 0 || close <= open) return null
        val pid = stat.substring(0, open).trim().toIntOrNull() ?: return null
        val fields = stat.substring(close + 1).trim().split(Regex("\\s+"))
        // After ')' the fields restart at index 0 == field 3 (state); utime is field 14, stime 15.
        val utime = fields.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = fields.getOrNull(12)?.toLongOrNull() ?: return null
        return Triple(pid, utime, stime)
    }

    fun parseStatusName(status: String): String? = STATUS_NAME.find(status)?.groupValues?.get(1)?.trim()

    fun parseStatusRssKb(status: String): Long? = STATUS_RSS.find(status)?.groupValues?.get(1)?.toLongOrNull()

    /**
     * Total CPU load from `/proc/stat`, in percent. [previous] is the earlier
     * jiffies snapshot; null on the first call, which returns 0.
     */
    fun totalCpuPercent(previous: LongArray?, current: LongArray): Float {
        if (previous == null || previous.size < 5 || current.size < 5) return 0f
        val idleBefore = previous[3] + previous[4]
        val idleNow = current[3] + current[4]
        val totalBefore = previous.sum()
        val totalNow = current.sum()
        val totalDelta = totalNow - totalBefore
        val idleDelta = idleNow - idleBefore
        if (totalDelta <= 0L) return 0f
        return ((totalDelta - idleDelta) * 100f / totalDelta).coerceIn(0f, 100f)
    }

    fun parseProcStat(line: String): LongArray? {
        if (!line.startsWith("cpu")) return null
        val numbers = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        return if (numbers.size < 4) null else numbers.toLongArray()
    }

    /**
     * Per-process CPU percentage: delta of (utime+stime) against the delta of total
     * jiffies, which is how top/htop express it on a multi-core box.
     */
    fun processCpuPercent(ticksBefore: Long, ticksNow: Long, totalJiffiesDelta: Long, cores: Int): Float {
        if (totalJiffiesDelta <= 0L) return 0f
        val used = (ticksNow - ticksBefore).coerceAtLeast(0L)
        return (used * 100f * cores / totalJiffiesDelta).coerceIn(0f, 100f * cores)
    }
}

/** One refresh of the process table. */
data class TaskSnapshot(
    val processes: List<ShellProcess>,
    val cpuPercent: Float,
    val memoryUsedMb: Long,
    val memoryTotalMb: Long,
)

/**
 * Builds [TaskSnapshot]s. Android-side numbers are real; container rows appear
 * once R2's ContainerService reports proot/box64/wine children.
 */
@Stable
class TaskSampler(
    private val context: Context,
    private val windowManager: WindowManager,
    private val containerProcesses: () -> List<ShellProcess> = { emptyList() },
) {
    private var lastTotalJiffies: LongArray? = null
    private val lastTicks = HashMap<String, Long>()
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    fun sample(): TaskSnapshot {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also { activityManager?.getMemoryInfo(it) }
        val cpuJiffies = readLines("/proc/stat").firstOrNull()?.let(ProcReader::parseProcStat)
        val totalDelta = delta(lastTotalJiffies, cpuJiffies)
        val cpuPercent = ProcReader.totalCpuPercent(lastTotalJiffies, cpuJiffies ?: LongArray(0))
        lastTotalJiffies = cpuJiffies

        val processes = mutableListOf<ShellProcess>()
        val myPid = Process.myPid()
        val selfRss = readLines("/proc/self/status")
            .firstOrNull { it.startsWith("VmRSS:") }
            ?.let(ProcReader::parseStatusRssKb) ?: 0L

        processes += ShellProcess(
            id = "shell",
            name = "Win11 Winlator Edition",
            kind = ProcessKind.SHELL,
            memoryKb = selfRss,
            cpuPercent = cpuOf("shell", totalDelta),
            status = "Foreground",
            pid = myPid,
        )

        windowManager.windows.toList().sortedBy { it.zIndex }.forEach { window ->
            processes += ShellProcess(
                id = "window:${window.id}",
                name = window.app.label,
                kind = ProcessKind.WINDOW,
                memoryKb = 0L,
                cpuPercent = 0f,
                status = when {
                    window.closing -> "Closing"
                    window.minimized -> "Suspended"
                    else -> "Running"
                },
                windowId = window.id,
            )
        }

        processes += containerProcesses()

        val usedMb = if (memoryInfo.totalMem > 0) {
            ((memoryInfo.totalMem - memoryInfo.availMem) / (1024 * 1024)).coerceAtLeast(0)
        } else {
            Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        }
        return TaskSnapshot(
            processes = processes,
            cpuPercent = cpuPercent,
            memoryUsedMb = usedMb,
            memoryTotalMb = memoryInfo.totalMem / (1024 * 1024),
        )
    }

    private fun cpuOf(id: String, totalDelta: Long): Float {
        val stat = readLines("/proc/self/stat").firstOrNull() ?: return 0f
        val parsed = ProcReader.parseStat(stat) ?: return 0f
        val ticks = parsed.second + parsed.third
        val before = lastTicks[id] ?: ticks
        lastTicks[id] = ticks
        return ProcReader.processCpuPercent(before, ticks, totalDelta, cores)
    }

    private fun delta(previous: LongArray?, current: LongArray?): Long {
        if (previous == null || current == null) return 0L
        return (current.sum() - previous.sum()).coerceAtLeast(0L)
    }

    private fun readLines(path: String): List<String> = try {
        File(path).useLines { it.toList() }
    } catch (error: Exception) {
        emptyList()
    }
}

/** Fixed-size ring of samples for the Performance sparklines. */
class History(private val capacity: Int = 60) {
    private val values = ArrayDeque<Float>()

    fun add(value: Float) {
        values.addLast(value)
        while (values.size > capacity) values.removeFirst()
    }

    fun snapshot(): List<Float> = values.toList()
    val size: Int get() = values.size
    fun clear() = values.clear()
}
