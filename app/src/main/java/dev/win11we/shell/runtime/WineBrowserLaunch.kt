package dev.win11we.shell.runtime

import dev.win11we.shell.container.Container
import dev.win11we.shell.container.WineEnvironment
import java.io.File

/**
 * Launch plans for Windows browsers running under Wine + box64 INSIDE the
 * embedded X server (`DISPLAY=:100` - the shell's own in-process display, never
 * an external X app). This is the Phase-2A hardening for Chromium-family
 * guests (chrome.exe, msedge.exe, Electron apps): every flag below exists
 * because of a specific, named failure mode of THIS stack, not as cargo cult.
 *
 * Why Chromium/Electron crash (or white-screen) under Wine-in-proot without them:
 *
 * - **Sandbox**: Chromium's sandbox needs unprivileged user namespaces +
 *   seccomp-BPF. proot already ptraces every guest process (one tracer per
 *   process is the kernel rule), and Android kernels deny unprivileged
 *   CLONE_NEWUSER - so the zygote/renderer dies at spawn ("white screen",
 *   instant exit 0 from the browser process). `--no-sandbox` +
 *   `--disable-setuid-sandbox` are the only workable answer; the Electron
 *   box64 preset's `CHROME_DEVEL_SANDBOX` env covers the setuid variant.
 * - **shm**: Chromium maps its shared-memory transport in /dev/shm; the
 *   jail's shm is the app-provided bind (RuntimePaths.ensureShmDir, mode 1777,
 *   bound at /dev/shm by every launch). `--disable-dev-shm-usage` is the belt:
 *   when the bind is missing or too small, Chromium falls back to /tmp files
 *   instead of crashing the tab.
 * - **GPU**: the embedded X server has NO GLX/DRI/Vulkan (it is a software
 *   renderer, honestly - see docs/X_SERVER.md). Every GPU path must be off or
 *   compositing silently produces a white/transparent window:
 *   `--disable-gpu` (no GL compositing), `--disable-gpu-compositing` (renderer
 *   process side), `--disable-features=Vulkan`.
 * - **First-run dialogs**: under a WM-less/automated start they can steal
 *   focus forever - `--no-first-run`, `--no-default-browser-check`.
 *
 * DNS/network: the jail's resolv.conf bind (RootfsProvisioner) + Android's
 * plain IP networking cover name resolution; no network namespace is created
 * (proot does not), so nothing extra is needed - and `PROOT_NO_SECCOMP=1`
 * (set by WineEnvironment/FirefoxEnvironment) keeps proot's own seccomp filter
 * from misbehaving on Android kernels, which is the "seccomp/bpf blocking the
 * browser" class of failure on the HOST side of the jail.
 *
 * Pure argv building - unit-tested; the container launcher executes the plan
 * through the usual proot -> box64 -> wine64 chain with WineEnvironment
 * (DISPLAY=:100, isolated WINEPREFIX per container).
 */
object WineBrowserLaunch {

    /** The hardened flag set for every Chromium-family Windows guest. */
    val CHROMIUM_FLAGS: List<String> = listOf(
        "--no-sandbox",
        "--disable-setuid-sandbox",
        "--disable-gpu",
        "--disable-gpu-compositing",
        "--disable-dev-shm-usage",
        "--disable-features=Vulkan",
        "--no-first-run",
        "--no-default-browser-check",
    )

    /**
     * Windows Firefox under Wine needs no sandbox/GPU flags (Gecko has no
     * zygote), but the same first-run quieting applies. Kept separate so the
     * Chromium list is never diluted.
     */
    val FIREFOX_FLAGS: List<String> = listOf("--no-remote")

    /** exe base names (lowercase, no path) that are Chromium-family. */
    val CHROMIUM_EXES: Set<String> = setOf(
        "chrome.exe", "msedge.exe", "brave.exe", "vivaldi.exe", "opera.exe",
        "electron.exe", "msedgewebview2.exe", "chromium.exe", "iexplore.exe",
    )

    fun isChromiumFamily(exePath: String): Boolean {
        val base = exePath.substringAfterLast('\\').substringAfterLast('/').lowercase()
        return base in CHROMIUM_EXES || base.endsWith("electron.exe")
    }

    fun isFirefox(exePath: String): Boolean {
        val base = exePath.substringAfterLast('\\').substringAfterLast('/').lowercase()
        return base == "firefox.exe"
    }

    /**
     * The guest command for one Windows browser: `wine64 <exe> <flags> [url]` -
     * a SYMBOLIC loader name: at spawn the launcher resolves it to the
     * provisioned payload's real guest path and wraps the command in proot,
     * adding box64 only when that payload is an x86_64 ELF (the payload-driven
     * [ContainerLauncher.needsBox64]), inside the container's OWN prefix
     * (WINEPREFIX is per-container by construction).
     *
     * Honesty note: the browser exes this plan targets are x86/x64 PEs, which
     * need an x86_64 Wine payload (the open "decision 3b" in
     * docs/PIPELINE_PLAN.md) - the pinned native arm64 payload cannot run them,
     * and nothing here pretends otherwise.
     *
     * @param exeGuestPath Windows path of the browser exe, e.g.
     *   `C:\Program Files\Google\Chrome\Application\chrome.exe`.
     * @param url optional start URL (appended last, as browsers expect).
     * @param extraFlags caller additions (e.g. `--profile-directory=…`);
     *   deduplicated against the hardened set so a caller cannot accidentally
     *   drop a safety flag by repeating it with another value.
     */
    fun plan(
        exeGuestPath: String,
        url: String? = null,
        extraFlags: List<String> = emptyList(),
        loader: String = "wine64",
    ): List<String> {
        require(exeGuestPath.isNotBlank()) { "a browser exe path is required" }
        val flags = when {
            isChromiumFamily(exeGuestPath) -> CHROMIUM_FLAGS
            isFirefox(exeGuestPath) -> FIREFOX_FLAGS
            else -> emptyList() // an unknown exe gets no assumptions
        }
        val extras = extraFlags.filterNot { extra -> flags.any { it.substringBefore('=') == extra.substringBefore('=') } }
        return listOf(loader, exeGuestPath) + flags + extras + listOfNotNull(url?.takeIf { it.isNotBlank() })
    }

    /**
     * The environment additions a Windows browser session needs ON TOP of
     * [dev.win11we.shell.container.WineEnvironment.build] (which already sets
     * DISPLAY=:100, the isolated WINEPREFIX, WINEDLLOVERRIDES and
     * PROOT_NO_SECCOMP). Chromium honours these directly:
     * - CHROME_DEVEL_SANDBOX points at a path that does not exist, which is the
     *   documented way to keep the setuid-sandbox probe from even trying.
     * - the shm fallback dir lives under the prefix's drive_c (isolated per app).
     */
    fun environmentAdditions(): Map<String, String> = mapOf(
        "CHROME_DEVEL_SANDBOX" to "/usr/local/sbin/chrome-devel-sandbox",
        "BOX64_ENV" to "CHROME_DEVEL_SANDBOX=/usr/local/sbin/chrome-devel-sandbox",
    )

    /**
     * The complete [RuntimeRequest] for "run this Windows browser exe inside
     * this container" - the plan, the container's Wine environment plus
     * [environmentAdditions], and the DNS + /dev/shm binds every session gets.
     * The launcher adds proot around it automatically - and box64 only for an
     * x86_64 payload ([ContainerLauncher.needsBox64], payload-driven) - the
     * display is the
     * shell's embedded `:100`, and the prefix is the container's own - the
     * browser can ONLY launch inside the emulator's X server, never outside it.
     *
     * This is the building block the BYO-installer flow (Edge/Chromium under
     * Wine, PIPELINE_PLAN step 4) executes; it is pure, so the whole shape is
     * unit-tested here rather than on a device.
     */
    fun request(
        container: Container,
        rootfsDir: File,
        xDisplay: String,
        exeGuestPath: String,
        url: String? = null,
        resolvConf: File? = null,
        shmDir: File? = null,
        /** Host dir of the vendored lib*.so binaries (the launcher overwrites the loader paths anyway). */
        nativeLibraryDir: String = "/data/app/lib",
    ): RuntimeRequest {
        val env = WineEnvironment.build(
            container = container,
            xDisplay = xDisplay,
            prootRootfs = rootfsDir.absolutePath,
            nativeLibraryDir = nativeLibraryDir,
        ) + environmentAdditions()
        return RuntimeRequest(
            container = container,
            rootfsDir = rootfsDir,
            xDisplay = xDisplay,
            guestCommand = plan(exeGuestPath, url, loader = container.arch.wineLoader),
            binds = WinePrefixManager.operationBinds(resolvConf, shmDir),
            environment = env.toSortedMap(),
        )
    }
}
