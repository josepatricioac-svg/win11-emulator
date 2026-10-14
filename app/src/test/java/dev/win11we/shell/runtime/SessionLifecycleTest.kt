package dev.win11we.shell.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The lifecycle contract: emulator start -> X server starts; emulator stop ->
 * guests stop -> X server stops, in that order, with every step resilient to
 * the one before it failing. Pure seams, so the ORDERING itself is the subject.
 */
class SessionLifecycleTest {

    @Test
    fun `boot reports success when the server starts`() {
        val report = SessionLifecycle.boot(ensureXServer = { true })
        assertTrue(report.success)
        assertTrue(report.message.contains("embedded"))
    }

    @Test
    fun `boot names the server's own error on failure`() {
        val report = SessionLifecycle.boot(ensureXServer = { false }, lastXError = { "EADDRINUSE" })
        assertFalse(report.success)
        assertTrue(report.message.contains("EADDRINUSE"))
    }

    @Test
    fun `boot survives a throwing seam - a lifecycle hook never takes the process down`() {
        val report = SessionLifecycle.boot(ensureXServer = { throw IllegalStateException("socket boom") })
        assertFalse(report.success)
        assertTrue(report.message.contains("IllegalStateException"))
    }

    @Test
    fun `shutdown runs wineserver kills, then guests, then the X server - in that order`() {
        val order = mutableListOf<String>()
        val prefixes = listOf(File("/p/a"), File("/p/b"))
        val report = SessionLifecycle.shutdown(
            winePrefixes = prefixes,
            killWineserver = { dir -> order += "kill:${dir.name}"; Pair(true, "ok") },
            stopGuests = { order += "guests" },
            stopXServer = { order += "x" },
        )
        assertEquals(listOf("kill:a", "kill:b", "guests", "x"), order)
        assertTrue(report.guestsStopped)
        assertTrue(report.xServerStopped)
        assertEquals(2, report.wineKills.count { it.killed })
    }

    @Test
    fun `a failing wineserver kill never blocks the teardown`() {
        val order = mutableListOf<String>()
        val report = SessionLifecycle.shutdown(
            winePrefixes = listOf(File("/p/a")),
            killWineserver = { throw RuntimeException("proot gone") },
            stopGuests = { order += "guests" },
            stopXServer = { order += "x" },
        )
        assertEquals(listOf("guests", "x"), order)
        assertFalse(report.wineKills.single().killed)
        assertTrue(report.wineKills.single().detail.contains("proot gone"))
        assertTrue(report.guestsStopped && report.xServerStopped)
    }

    @Test
    fun `a failing guest stop still closes the X server`() {
        var xStopped = false
        val report = SessionLifecycle.shutdown(
            stopGuests = { throw IllegalStateException("handle stuck") },
            stopXServer = { xStopped = true },
        )
        assertTrue(xStopped)
        assertFalse(report.guestsStopped)
        assertTrue(report.guestError!!.contains("handle stuck"))
        assertTrue(report.xServerStopped)
    }

    @Test
    fun `shutdown with nothing wired is a clean no-op report`() {
        val report = SessionLifecycle.shutdown()
        assertTrue(report.guestsStopped)
        assertTrue(report.xServerStopped)
        assertTrue(report.wineKills.isEmpty())
        assertTrue(report.summary().contains("guests stopped: yes"))
    }

    @Test
    fun `the summary names failures, so the shared log tells the truth`() {
        val report = SessionLifecycle.shutdown(
            stopGuests = { throw RuntimeException("boom") },
            stopXServer = {},
        )
        assertTrue(report.summary().contains("NO"))
        assertTrue(report.summary().contains("boom"))
    }
}
