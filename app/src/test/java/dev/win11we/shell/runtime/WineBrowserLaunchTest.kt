package dev.win11we.shell.runtime

import dev.win11we.shell.container.Container
import dev.win11we.shell.container.WineArch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WineBrowserLaunchTest {

    @Test
    fun `chromium-family exes are recognised whatever the path style`() {
        assertTrue(WineBrowserLaunch.isChromiumFamily("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"))
        assertTrue(WineBrowserLaunch.isChromiumFamily("c:/edge/msedge.exe"))
        assertTrue(WineBrowserLaunch.isChromiumFamily("MSEDGE.EXE"))
        assertTrue(WineBrowserLaunch.isChromiumFamily("electron.exe"))
        assertTrue(WineBrowserLaunch.isChromiumFamily("C:\\app\\MyApp-electron.exe"))
        assertFalse(WineBrowserLaunch.isChromiumFamily("C:\\Program Files\\Firefox\\firefox.exe"))
        assertFalse(WineBrowserLaunch.isChromiumFamily("C:\\Windows\\notepad.exe"))
    }

    @Test
    fun `the chromium plan carries every hardening flag - each one a named crash fix`() {
        val plan = WineBrowserLaunch.plan("C:\\Chrome\\chrome.exe", url = "https://example.org")
        assertEquals("wine64", plan.first())
        assertEquals("C:\\Chrome\\chrome.exe", plan[1])
        listOf(
            "--no-sandbox",              // user namespaces are unavailable under proot
            "--disable-setuid-sandbox",  // no setuid helper can exist in the jail
            "--disable-gpu",             // the embedded X server has no GLX/DRI
            "--disable-gpu-compositing",
            "--disable-dev-shm-usage",   // /dev/shm is app-provided; this is the fallback belt
            "--no-first-run",
            "--no-default-browser-check",
        ).forEach { flag -> assertTrue("missing $flag in $plan", plan.contains(flag)) }
        assertEquals("https://example.org", plan.last())
    }

    @Test
    fun `firefox under wine gets no chromium flags`() {
        val plan = WineBrowserLaunch.plan("C:\\Firefox\\firefox.exe", url = "https://example.org")
        assertTrue(plan.contains("--no-remote"))
        assertFalse(plan.contains("--no-sandbox"))
        assertFalse(plan.contains("--disable-gpu"))
        assertEquals("https://example.org", plan.last())
    }

    @Test
    fun `an unknown exe gets no assumed flags but keeps the url`() {
        val plan = WineBrowserLaunch.plan("C:\\tools\\whatever.exe", url = "u")
        assertEquals(listOf("wine64", "C:\\tools\\whatever.exe", "u"), plan)
    }

    @Test
    fun `extra flags are appended but can never replace a hardened flag value`() {
        val plan = WineBrowserLaunch.plan(
            "chrome.exe",
            extraFlags = listOf("--profile-directory=Guest", "--no-sandbox"),
        )
        // The duplicate --no-sandbox is deduplicated; the profile flag survives.
        assertEquals(1, plan.count { it == "--no-sandbox" })
        assertTrue(plan.contains("--profile-directory=Guest"))
    }

    @Test
    fun `a caller-supplied flag with the same name as a hardened one keeps the hardened value`() {
        val plan = WineBrowserLaunch.plan("chrome.exe", extraFlags = listOf("--disable-gpu=maybe"))
        assertEquals(1, plan.count { it.substringBefore('=') == "--disable-gpu" })
        assertTrue(plan.contains("--disable-gpu"))
        assertFalse(plan.contains("--disable-gpu=maybe"))
    }

    @Test
    fun `the 32-bit loader can be requested for a wow64 prefix's 32-bit browser`() {
        val plan = WineBrowserLaunch.plan("chrome.exe", loader = "wine")
        assertEquals("wine", plan.first())
    }

    @Test
    fun `a blank exe path is refused`() {
        try {
            WineBrowserLaunch.plan(" ")
            throw AssertionError("a blank exe must be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("exe"))
        }
    }

    @Test
    fun `the environment additions keep the setuid-sandbox probe from ever succeeding`() {
        val env = WineBrowserLaunch.environmentAdditions()
        assertEquals("/usr/local/sbin/chrome-devel-sandbox", env["CHROME_DEVEL_SANDBOX"])
        assertTrue(env.getValue("BOX64_ENV").contains("CHROME_DEVEL_SANDBOX"))
    }

    @Test
    fun `the request factory composes plan + wine env + hardened additions + binds`() {
        val container = Container(id = "edge", name = "Edge", prefixPath = "/p/prefix", arch = WineArch.WOW64)
        val request = WineBrowserLaunch.request(
            container = container,
            rootfsDir = File("/rootfs"),
            xDisplay = ":100",
            exeGuestPath = "C:\\Edge\\msedge.exe",
            url = "https://example.org",
            resolvConf = File("/r"),
            shmDir = File("/s"),
        )
        // The display is the shell's embedded server - the browser can only run inside it.
        assertEquals(":100", request.xDisplay)
        assertEquals(request.guestCommand.first(), container.arch.wineLoader)
        assertTrue(request.guestCommand.contains("--no-sandbox"))
        assertTrue(request.guestCommand.contains("--disable-dev-shm-usage"))
        assertEquals("https://example.org", request.guestCommand.last())
        // Isolated prefix + explicit wine env + the hardened additions, sorted.
        val env = request.environment!!
        assertEquals("/root/.wine", env["WINEPREFIX"])
        assertEquals(":100", env["DISPLAY"])
        assertEquals("win64", env["WINEARCH"])
        assertEquals("1", env["PROOT_NO_SECCOMP"])
        assertEquals("/usr/local/sbin/chrome-devel-sandbox", env["CHROME_DEVEL_SANDBOX"])
        assertEquals(env.keys.toList(), env.keys.sorted())
        // DNS + /dev/shm ride along, exactly like every other session.
        assertEquals(listOf("/r:/etc/resolv.conf", "/s:/dev/shm"), request.binds.map { it.argument })
    }

    @Test
    fun `a 32-bit-only browser exe would ride the same plan under the wow64 prefix`() {
        // The loader stays wine64 (arm64 has no 32-bit loader); box64 translates
        // the x86 PE. The plan shape is what matters here.
        val request = WineBrowserLaunch.request(
            container = Container(id = "c", name = "C", prefixPath = "/p"),
            rootfsDir = File("/rootfs"),
            xDisplay = ":100",
            exeGuestPath = "C:\\Program Files (x86)\\chrome.exe",
        )
        assertEquals("wine64", request.guestCommand.first())
        assertTrue(request.guestCommand.contains("--disable-gpu"))
    }
}
