package dev.win11we.shell.runtime

import dev.win11we.shell.ui.terminal.LineKind
import dev.win11we.shell.ui.terminal.LogBuffer
import dev.win11we.shell.ui.terminal.TerminalLine
import java.io.File

/**
 * Stale apt/dpkg lock recovery - the fix for the device failure:
 *
 *   "E: Could not get lock /var/lib/dpkg/lock-frontend - it is held by
 *    process 6895 (apt-get)"  /  "Unable to lock the administration directory"
 *
 * How an orphan happens: proot's `--kill-on-exit` only runs when proot itself
 * exits normally. When the app process is SIGKILLed mid-provision (swipe from
 * recents, system kill, a runner timeout's destroyForcibly), proot dies
 * without a chance to clean up and the guest's `apt-get`/`dpkg` children can
 * survive - same uid, still holding the flocks on `/var/lib/dpkg`. Every
 * later provision then dies on the lock within seconds of starting.
 *
 * Why the app can fix this itself: the orphans run under the app's OWN uid, so
 * their `/proc/<pid>/cmdline` is readable and `kill(2)` is permitted. The
 * recovery therefore happens HOST-side, before proot is even spawned - no
 * guest-side pid translation questions, and it works even when the jail
 * itself is broken.
 *
 * Matching rule (pinned by tests): a process is a package-manager holder only
 * when the BASENAME OF ITS FIRST ARGV TOKEN is exactly `apt-get`, `apt` or
 * `dpkg`. Substring matching would be fatal here: the provisioning shell's
 * own cmdline (`bash -c "... apt-get ... dpkg ..."`) and proot's (`libproot.so
 * ... apt-get ...`) both CONTAIN those words, and killing either would kill
 * the run that is trying to recover.
 *
 * Concurrency semantics, documented honestly: a legitimately running apt from
 * a concurrent provision (e.g. Install Wine payload while Firefox setup runs)
 * is also reclaimed - one provisioning run at a time, the newest wins, and
 * every kill is named in the shared log. `dpkg --configure -a` (already the
 * first step of both provision scripts) heals whatever the killed dpkg left
 * half-configured.
 *
 * The scan is pure (a proc root parameter) and unit-tested; only the default
 * [androidKill] touches Android APIs, and every call is guarded (crash rule 1).
 */
object PackageLockRecovery {

    /** First-token basenames that may hold the dpkg/apt locks. */
    val PACKAGE_MANAGER_NAMES = setOf("apt-get", "apt", "dpkg")

    /** One lock holder: its pid and the cmdline that identified it. */
    data class Holder(val pid: Int, val cmdline: String)

    /**
     * Scans [procDir] (the host `/proc` by default) for package-manager
     * processes. Unreadable or empty cmdlines are skipped silently - other
     * uids' entries are hidden or unreadable on Android, and that is fine:
     * only same-uid processes can hold OUR rootfs's locks anyway.
     */
    fun holders(procDir: File = File("/proc"), selfPid: Int = -1): List<Holder> {
        val out = mutableListOf<Holder>()
        val entries = runCatching { procDir.listFiles() }.getOrNull() ?: return out
        for (entry in entries) {
            val pid = entry.name.toIntOrNull() ?: continue // only the numeric pid dirs
            if (pid == selfPid) continue
            val raw = runCatching { File(entry, "cmdline").readBytes() }.getOrNull() ?: continue
            val text = String(raw).replace('\u0000', ' ').trim()
            if (text.isEmpty()) continue // kernel threads have an empty cmdline
            val exe = text.substringBefore(' ').substringAfterLast('/')
            if (exe in PACKAGE_MANAGER_NAMES) out += Holder(pid, text)
        }
        return out.sortedBy { it.pid }
    }

    /**
     * Kills every stale holder and returns one honest log line per action
     * ("killed ..." / "could NOT kill ..."). [killer] defaults to a SIGKILL
     * through `android.system.Os`; tests inject a fake. Never throws.
     */
    fun recover(
        procDir: File = File("/proc"),
        killer: (Int) -> Boolean = ::androidKill,
    ): List<String> {
        val self = runCatching { android.os.Process.myPid() }.getOrDefault(-1)
        return holders(procDir, self).map { holder ->
            val killed = runCatching { killer(holder.pid) }.getOrDefault(false)
            if (killed) {
                "lock recovery: killed stale ${holder.cmdline.take(90)} (pid ${holder.pid})"
            } else {
                "lock recovery: could NOT kill ${holder.cmdline.take(90)} (pid ${holder.pid}) - " +
                    "provisioning will likely fail on the dpkg lock"
            }
        }
    }

    /** Runs [recover] and streams the lines into the shared log. */
    fun recoverInto(log: LogBuffer, procDir: File = File("/proc")) {
        runCatching { recover(procDir) }
            .getOrDefault(emptyList())
            .forEach { line -> log.add(TerminalLine(LineKind.SYSTEM, line)) }
    }

    private fun androidKill(pid: Int): Boolean = try {
        android.system.Os.kill(pid, android.system.OsConstants.SIGKILL)
        true
    } catch (error: Throwable) {
        false // EPERM/ESRCH (holder already gone) or a non-Android JVM: honest "not killed"
    }
}
