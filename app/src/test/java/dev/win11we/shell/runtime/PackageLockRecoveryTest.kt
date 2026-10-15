package dev.win11we.shell.runtime

import dev.win11we.shell.ui.terminal.LineKind
import dev.win11we.shell.ui.terminal.LogBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The stale-lock recovery that fixes the device failure "Could not get lock
 * /var/lib/dpkg/lock-frontend - held by process 6895 (apt-get)": an apt/dpkg
 * orphaned by a killed proot run is found in the host /proc and SIGKILLed
 * before the next provision spawns its jail.
 */
class PackageLockRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var fixtureSeq = 0

    /** A fake /proc: `<pid>/cmdline` files with NUL-separated argv. */
    private fun proc(vararg processes: Pair<Int, String>): File {
        val root = tmp.newFolder("proc${fixtureSeq++}")
        processes.forEach { (pid, cmdline) ->
            val dir = File(root, pid.toString()).also { it.mkdirs() }
            File(dir, "cmdline").writeBytes(
                cmdline.split(' ').joinToString(separator = "\u0000", postfix = "\u0000").toByteArray(),
            )
        }
        return root
    }

    @Test
    fun `an orphaned apt-get is found - the exact device failure`() {
        val root = proc(6895 to "/usr/bin/apt-get -o APT::Sandbox::User=root install -y libgtk-3-0t64")
        val holders = PackageLockRecovery.holders(root)
        assertEquals(listOf(6895), holders.map { it.pid })
        assertTrue(holders.single().cmdline.contains("apt-get"))
    }

    @Test
    fun `dpkg and bare apt count as holders too`() {
        val root = proc(
            10 to "dpkg --configure -a",
            20 to "/usr/bin/apt -f install -y",
        )
        assertEquals(listOf(10, 20), PackageLockRecovery.holders(root).map { it.pid })
    }

    @Test
    fun `the provisioning shell itself is NEVER matched - substring hits would be suicide`() {
        // The recovery runs from a bash whose cmdline CONTAINS "apt-get" and
        // "dpkg" (the whole provision script), and proot's host cmdline carries
        // the same text. Only the FIRST argv token's basename may match.
        val root = proc(
            100 to "/bin/bash -c dpkg --configure -a; apt-get update && apt-get install -y wine64",
            101 to "/data/app/~~hash/dev.win11we.shell/lib/arm64/libproot.so -r /data/rootfs -0 /bin/bash -c apt-get update",
            102 to "/usr/bin/apt-get update",
        )
        val holders = PackageLockRecovery.holders(root)
        assertEquals(listOf(102), holders.map { it.pid })
    }

    @Test
    fun `a relative argv0 still matches on its basename`() {
        val root = proc(7 to "apt-get install -y ca-certificates")
        assertEquals(listOf(7), PackageLockRecovery.holders(root).map { it.pid })
    }

    @Test
    fun `self, unreadable, empty and non-numeric entries are skipped`() {
        val root = proc(
            42 to "apt-get install",
            43 to "",
        )
        File(root, "self").mkdirs() // the /proc/self symlink dir: not numeric
        File(root, "version").writeText("Linux version ...")
        val noSelf = PackageLockRecovery.holders(root, selfPid = 42)
        assertTrue(noSelf.isEmpty())
        val withSelf = PackageLockRecovery.holders(root, selfPid = -1)
        assertEquals(listOf(42), withSelf.map { it.pid }) // the empty cmdline (43) never matches
    }

    @Test
    fun `a missing proc root is a quiet no-op`() {
        assertTrue(PackageLockRecovery.holders(File(tmp.root, "does-not-exist")).isEmpty())
    }

    @Test
    fun `recover kills every holder and reports each one honestly`() {
        val root = proc(6895 to "/usr/bin/apt-get install -y libgtk-3-0t64", 6900 to "dpkg --configure -a")
        val killed = mutableListOf<Int>()
        val lines = PackageLockRecovery.recover(root) { pid -> killed += pid; true }
        assertEquals(listOf(6895, 6900), killed)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("killed stale"))
        assertTrue(lines[0].contains("6895"))
        assertTrue(lines[1].contains("dpkg"))
    }

    @Test
    fun `a holder that cannot be killed is reported as a warning, not a silent pass`() {
        val root = proc(6895 to "/usr/bin/apt-get install")
        val lines = PackageLockRecovery.recover(root) { false }
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("could NOT kill"))
        assertTrue(lines.single().contains("dpkg lock"))
    }

    @Test
    fun `a throwing killer never escapes - crash-safety rule 1`() {
        val root = proc(1 to "apt-get update")
        val lines = PackageLockRecovery.recover(root) { throw IllegalStateException("EPERM") }
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("could NOT kill"))
    }

    @Test
    fun `recoverInto streams the report into the shared log`() {
        val root = proc(6895 to "/usr/bin/apt-get install")
        val log = LogBuffer()
        // recoverInto uses the default (Android) killer; off-device it reports
        // "could NOT kill" - either way the log line must land.
        PackageLockRecovery.recoverInto(log, root)
        val lines = log.filter(LineKind.SYSTEM).map { it.text }
        assertTrue(lines.any { it.contains("lock recovery:") && it.contains("6895") })
    }

    @Test
    fun `the holder list is sorted by pid for deterministic logs`() {
        val root = proc(300 to "apt-get x", 20 to "dpkg y", 100 to "apt z")
        assertEquals(listOf(20, 100, 300), PackageLockRecovery.holders(root).map { it.pid })
        assertFalse(PackageLockRecovery.PACKAGE_MANAGER_NAMES.contains("bash"))
    }
}
