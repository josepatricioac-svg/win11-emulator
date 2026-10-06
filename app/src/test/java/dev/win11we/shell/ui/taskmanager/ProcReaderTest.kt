package dev.win11we.shell.ui.taskmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** /proc parsing and the CPU maths behind Task Manager - pure JVM. */
class ProcReaderTest {

    private val statLine = "1402 (Win11 WE) S 1 1402 0 0 -1 4194560 1234 0 0 0 120 30 0 0 20 0 40 0 999 1234567 500"

    @Test
    fun `stat parsing survives spaces and parentheses in the process name`() {
        val parsed = ProcReader.parseStat(statLine)
        assertEquals(Triple(1402, 120L, 30L), parsed)

        val tricky = ProcReader.parseStat("77 (proot (box64)) R 1 77 0 0 -1 4194560 10 0 0 0 7 3 0 0 20 0 4 0 5 1000 10")
        assertEquals(Triple(77, 7L, 3L), tricky)
    }

    @Test
    fun `malformed stat lines are rejected instead of throwing`() {
        assertNull(ProcReader.parseStat("garbage"))
        assertNull(ProcReader.parseStat("12 no-parens S 1 2 3"))
        assertNull(ProcReader.parseStat(""))
    }

    @Test
    fun `status name and rss are extracted`() {
        val status = "Name:\twine64\nUmask:\t0022\nVmPeak:\t 100 kB\nVmRSS:\t  43210 kB\nThreads:\t4\n"
        assertEquals("wine64", ProcReader.parseStatusName(status))
        assertEquals(43210L, ProcReader.parseStatusRssKb(status))
        assertNull(ProcReader.parseStatusRssKb("Name:\tswap\n"))
    }

    @Test
    fun `total cpu is the busy share of the jiffies delta`() {
        val before = ProcReader.parseProcStat("cpu  1000 0 500 8000 400 0 100 0 0 0")!!
        val after = ProcReader.parseProcStat("cpu  1100 0 500 8200 400 0 100 0 0 0")!!
        // before: busy 1600, idle 8400, total 10000
        // after:  busy 1700, idle 8600, total 10300  ->  100 busy of 300 total = 33.3%
        assertEquals(33.33f, ProcReader.totalCpuPercent(before, after), 0.02f)

        val busyOnly = ProcReader.parseProcStat("cpu  1300 0 500 8000 400 0 100 0 0 0")!!
        // busy 1900, idle 8400 -> the whole 300-jiffy delta was busy time = 100%
        assertEquals(100f, ProcReader.totalCpuPercent(before, busyOnly), 0.01f)
    }

    @Test
    fun `the first sample reports zero and nonsense input is ignored`() {
        val after = ProcReader.parseProcStat("cpu  1100 0 500 8200 400 0 100 0 0 0")!!
        assertEquals(0f, ProcReader.totalCpuPercent(null, after), 0.001f)
        assertEquals(0f, ProcReader.totalCpuPercent(after, after), 0.001f)
        assertNull(ProcReader.parseProcStat("intr 1 2 3"))
        assertNull(ProcReader.parseProcStat("cpu  1 2"))
    }

    @Test
    fun `per-process cpu scales with the core count like top does`() {
        // 50 ticks of one process out of 100 total jiffies on 4 cores -> 200%
        assertEquals(200f, ProcReader.processCpuPercent(100L, 150L, 100L, cores = 4), 0.01f)
        assertEquals(0f, ProcReader.processCpuPercent(100L, 150L, 0L, cores = 4), 0.001f)
        // a counter reset must not produce a negative percentage
        assertEquals(0f, ProcReader.processCpuPercent(500L, 100L, 100L, cores = 4), 0.001f)
    }

    @Test
    fun `history is a bounded ring buffer`() {
        val history = History(capacity = 3)
        assertEquals(0, history.size)
        listOf(1f, 2f, 3f, 4f, 5f).forEach(history::add)
        assertEquals(listOf(3f, 4f, 5f), history.snapshot())
        history.clear()
        assertEquals(0, history.size)
    }

    @Test
    fun `only windows and the shell itself can be ended by the shell`() {
        assertTrue(ShellProcess("window:1", "Notepad", ProcessKind.WINDOW, windowId = 1).endable)
        assertTrue(ShellProcess("shell", "Shell", ProcessKind.SHELL).endable)
        assertTrue(!ShellProcess("proot", "proot", ProcessKind.CONTAINER, pid = 4242).endable)
        assertTrue(!ShellProcess("wine", "WINWORD.EXE", ProcessKind.WINDOWS_APP, pid = 99).endable)
    }
}
