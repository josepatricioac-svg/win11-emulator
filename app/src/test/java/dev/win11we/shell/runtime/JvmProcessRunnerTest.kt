package dev.win11we.shell.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test

/**
 * [JvmProcessRunner] spawns real processes, so these tests run only on POSIX
 * hosts (CI is Linux) and are skipped elsewhere - the rest of the suite drives
 * everything through the [ProcessRunner] seam instead. They pin the two
 * properties the apt-in-proot provisioning depends on: concurrent pipe drains
 * (a child writing >64 KiB to stderr while stdout is read must not deadlock)
 * and an honest timeout (a hung child is killed near timeoutMillis and reports
 * "no exit code" instead of blocking forever).
 */
class JvmProcessRunnerTest {

    @Before
    fun posixHostsOnly() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
    }

    private val runner = JvmProcessRunner()
    private val env = mapOf("PATH" to "/bin:/usr/bin")

    @Test
    fun `drains a full stderr pipe without deadlocking`() {
        // ~4000 lines on stderr (far past the 64 KiB pipe buffer) plus stdout
        // output: the old sequential stdout-then-stderr drain blocked forever
        // on exactly this shape, which apt-get produces during provisioning.
        val script = "i=0; while [ \$i -lt 4000 ]; do echo stderr-line-\$i-aaaaaaaaaaaaaaaaaaaaaaa >&2; i=\$((i+1)); done; echo done-stdout"
        val outcome = runner.run(listOf("/bin/sh", "-c", script), env, null, 60_000)
        assertEquals(outcome.error, 0, outcome.exitCode)
        assertTrue(outcome.stdout.contains("done-stdout"))
        assertTrue(outcome.stderr.contains("stderr-line-3999"))
    }

    @Test
    fun `timeout kills a hung child and reports no exit code`() {
        val start = System.currentTimeMillis()
        val outcome = runner.run(listOf("/bin/sh", "-c", "sleep 30"), env, null, 500)
        val elapsed = System.currentTimeMillis() - start
        assertNull(outcome.exitCode)
        assertTrue(outcome.error!!.contains("Timed out after 500ms"))
        assertTrue("watchdog must fire near timeoutMillis, took ${elapsed}ms", elapsed < 10_000)
    }

    @Test
    fun `the explicit environment reaches the child and nothing is inherited`() {
        val outcome = runner.run(
            listOf("/bin/sh", "-c", "echo var=\$MY_VAR unset=\${SOME_HOST_VAR:-gone}"),
            mapOf("MY_VAR" to "hello"),
            null,
            10_000,
        )
        assertEquals(outcome.error, 0, outcome.exitCode)
        assertTrue(outcome.stdout.contains("var=hello"))
        assertTrue(outcome.stdout.contains("unset=gone"))
    }
}
