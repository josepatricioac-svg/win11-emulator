package dev.win11we.shell.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The first-load fixes are a contract: Firefox rejects a malformed policies.json
 * outright (silently ignoring it would re-enable every background round trip),
 * and a user.js with a typo'd pref name is a silent no-op. So the exact keys
 * and the file layout are pinned here, plus the idempotency that keeps later
 * session starts cheap.
 */
class FirefoxPoliciesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `policies json locks the first-load blockers`() {
        val json = FirefoxPolicies.policiesJson()
        // Enforced-off background traffic:
        assertTrue(json.contains("\"DisableTelemetry\": true"))
        assertTrue(json.contains("\"DisableFirefoxStudies\": true"))
        assertTrue(json.contains("\"DisableAppUpdate\": true"))
        assertTrue(json.contains("\"ManualAppUpdateOnly\": true"))
        assertTrue(json.contains("\"DisablePocket\": true"))
        assertTrue(json.contains("\"DontCheckDefaultBrowser\": true"))
        assertTrue(json.contains("\"OverrideFirstRunPage\": \"about:blank\""))
        assertTrue(json.contains("\"OverridePostUpdatePage\": \"about:blank\""))
        assertTrue(json.contains("\"StartPage\": \"none\""))
        // Safe-browsing family locked off through the Preferences policy:
        for (pref in listOf(
            "browser.safebrowsing.malware.enabled",
            "browser.safebrowsing.phishing.enabled",
            "browser.safebrowsing.downloads.enabled",
            "browser.safebrowsing.downloads.remote.enabled",
        )) {
            assertTrue("missing locked-off pref: $pref", json.contains("\"$pref\": { \"Value\": false, \"Status\": \"locked\" }"))
        }
        // Speed prefs deliberately left enabled (default status, not locked):
        assertTrue(json.contains("\"network.dns.disablePrefetch\": { \"Value\": false, \"Status\": \"default\" }"))
        assertTrue(json.contains("\"network.prefetch-next\": { \"Value\": true, \"Status\": \"default\" }"))
    }

    @Test
    fun `policies json is balanced - firefox rejects the file on a syntax error`() {
        val json = FirefoxPolicies.policiesJson()
        var braces = 0
        var inString = false
        var escaped = false
        for (c in json) {
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> braces++
                !inString && c == '}' -> braces--
            }
            assertTrue("unbalanced policies.json", braces >= 0)
        }
        assertEquals(0, braces)
        assertFalse("no comments allowed in JSON", json.contains("//"))
    }

    @Test
    fun `user js disables the telemetry family and keeps prefetch on`() {
        val js = FirefoxPolicies.userJs()
        val lines = js.lines().filter { it.isNotBlank() }
        assertTrue(lines.all { it.startsWith("user_pref(") && it.endsWith(");") })
        assertTrue(js.contains("user_pref(\"toolkit.telemetry.enabled\", false);"))
        assertTrue(js.contains("user_pref(\"datareporting.healthreport.uploadEnabled\", false);"))
        assertTrue(js.contains("user_pref(\"app.normandy.enabled\", false);"))
        assertTrue(js.contains("user_pref(\"browser.safebrowsing.malware.enabled\", false);"))
        assertTrue(js.contains("user_pref(\"browser.safebrowsing.phishing.enabled\", false);"))
        assertTrue(js.contains("user_pref(\"app.update.auto\", false);"))
        assertTrue(js.contains("user_pref(\"extensions.pocket.enabled\", false);"))
        assertTrue(js.contains("user_pref(\"browser.aboutwelcome.enabled\", false);"))
        // Kept ON for first-load speed:
        assertTrue(js.contains("user_pref(\"network.dns.disablePrefetch\", false);"))
        assertTrue(js.contains("user_pref(\"network.prefetch-next\", true);"))
        // No pref may be listed twice with conflicting values:
        val keys = lines.map { it.substringAfter("\"").substringBefore("\"") }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `profiles ini makes -P shell deterministic`() {
        val ini = FirefoxPolicies.profilesIni("shell")
        assertTrue(ini.contains("[Profile0]"))
        assertTrue(ini.contains("Name=shell"))
        assertTrue(ini.contains("IsRelative=1"))
        assertTrue(ini.contains("Path=shell"))
        assertTrue(ini.contains("Default=1"))
        assertTrue(ini.contains("[General]"))
        assertTrue(ini.contains("Version=2"))
    }

    @Test
    fun `materialize writes the three files at their exact guest-mapped locations`() {
        val rootfs = tmp.newFolder("rootfs")
        File(rootfs, "opt/firefox-esr").mkdirs() // Firefox installed
        val profile = tmp.newFolder("profile")
        val written = FirefoxPolicies.materialize(rootfs, profile)
        assertEquals(3, written.size)
        val policies = File(rootfs, "opt/firefox-esr/distribution/policies.json")
        val ini = File(profile, "firefox/profiles.ini")
        val userJs = File(profile, "firefox/shell/user.js")
        assertTrue(policies.isFile)
        assertTrue(ini.isFile)
        assertTrue(userJs.isFile)
        assertEquals(FirefoxPolicies.policiesJson(), policies.readText())
        assertEquals(FirefoxPolicies.userJs(), userJs.readText())
        assertTrue(ini.readText().contains("Path=shell"))
    }

    @Test
    fun `no policies file before firefox is installed - profile files still land`() {
        val rootfs = tmp.newFolder("empty-rootfs") // no opt/firefox-esr
        val profile = tmp.newFolder("profile2")
        val written = FirefoxPolicies.materialize(rootfs, profile)
        assertEquals(2, written.size)
        assertFalse(File(rootfs, "opt/firefox-esr/distribution/policies.json").exists())
        // Creating a distribution directory for a missing install would leave a
        // stray tree the extractor must merge with later - it must NOT exist:
        assertFalse(File(rootfs, "opt").exists())
    }

    @Test
    fun `materialize is idempotent and rewrites only what changed`() {
        val rootfs = tmp.newFolder("rootfs3")
        File(rootfs, "opt/firefox-esr").mkdirs()
        val profile = tmp.newFolder("profile3")
        assertEquals(3, FirefoxPolicies.materialize(rootfs, profile).size)
        assertEquals(0, FirefoxPolicies.materialize(rootfs, profile).size) // nothing rewritten
        val userJs = File(profile, "firefox/shell/user.js")
        val stamp = userJs.lastModified()
        userJs.writeText("user_pref(\"tampered\", true);\n")
        val rewritten = FirefoxPolicies.materialize(rootfs, profile)
        assertEquals(listOf(userJs), rewritten) // only the tampered file
        assertEquals(FirefoxPolicies.userJs(), userJs.readText())
        assertTrue(userJs.lastModified() >= stamp)
    }
}
