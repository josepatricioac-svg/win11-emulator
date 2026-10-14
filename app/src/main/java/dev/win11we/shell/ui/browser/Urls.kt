package dev.win11we.shell.ui.browser

import java.net.URLEncoder

/**
 * Turns whatever the user typed into a URL: full URLs pass through, bare
 * hostnames get https://, everything else becomes a search query.
 * Pure function, so it is unit-tested.
 *
 * (This file is what survives of the old `Browsers.kt`: the installed-browser
 * detection, the ACTION_VIEW intent building and the package cache around it
 * were removed on owner decision - the shell hands URLs to desktop engines on
 * its own X screen, never to Android browsers.)
 */
object Urls {
    private val HOST_LIKE = Regex("^[\\w\\-]+(\\.[\\w\\-]+)+(/\\S*)?$")

    fun parse(input: String, searchTemplate: String = "https://duckduckgo.com/?q=%s"): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return trimmed
        if (trimmed.startsWith("file://", true) || trimmed.startsWith("about:", true)) return trimmed
        if (!trimmed.contains(' ') && HOST_LIKE.matches(trimmed)) return "https://$trimmed"
        if (trimmed == "localhost" || trimmed.startsWith("localhost:")) return "http://$trimmed"
        return searchTemplate.replace("%s", URLEncoder.encode(trimmed, "UTF-8"))
    }
}
