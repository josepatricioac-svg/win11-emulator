# Wine pipeline — verified state of `main` (snapshot 2026-10-13)

Working document of the Goal-2 effort (box64 → Firefox ESR → Wine → optional Edge).
Everything below was checked against `main@39e0f37` in a clean clone; commands and
outputs are reproducible. Companion plan: `docs/PIPELINE_PLAN.md`.

## What is already done (evidence, not docs)

**Step 1 (box64) — code-side COMPLETE.**
- `app/src/main/jniLibs/arm64-v8a/libbox64.so` exists (19,078,712 bytes).
  Local `sha256sum` = `bec2a61f2c2bee8607d41e6d5b992580ccb9cad878cc6e0a255f539957823710`
  = the pin in `assets/native/checksums.txt` (byte-for-byte match).
- ELF header verified in-tree: ELF64, LSB, `DYN` (PIE), Machine **AArch64**.
- Committed by `github-actions[bot]` in `fe5e346` (2026-10-12) — the
  `build-native.yml` dispatch run succeeded and auto-vendored binary+pin as designed
  (Actions run history has since been purged: the API lists 0 build-native runs).
- **CI APK contents verified (2026-10-13):** downloaded the `win11we-debug-apk`
  artifact of the latest `build-apk` run (run 38330641957, HEAD `39e0f378`,
  conclusion success) and inspected it: `lib/arm64-v8a/{libbox64,libproot,
  libproot-loader,libtalloc}.so` all present, all four SHA-256s match the pins.
  `useLegacyPackaging = true` ⇒ extracted to `nativeLibraryDir` at install.
- Was stale, now fixed in this repo: `docs/NATIVE_RUNTIME.md`, `README.md`,
  `docs/THIRD_PARTY.md` claimed box64 was "NOT vendored yet / pending one run".
- Device check still outstanding (owner): Diagnostics ▸ Run --version probes ⇒
  `box64 --version` PASS. README already reports it green once on device; one
  re-confirm on a current build closes step 1.

**X server (D1a–D1e)** — `x11/`: core protocol, RENDER 0.4, XTEST, BIG-REQUESTS,
Generic Event, XInput2 2.2. `XConnection.EXTENSIONS` advertises exactly those five;
QueryExtension honestly answers absent for XKB, MIT-SHM, COMPOSITE, XFIXES, DAMAGE,
SYNC, RANDR, SHAPE.

**Firefox ESR plumbing (step 2)** — pinned artifact (140.17.0esr linux-aarch64,
sha `dd579f48…850084`), downloader/extractor, readiness worker, `FirefoxSession`
(proot command + explicit env, DISPLAY=:100, sandbox-off switches, D-Bus handoff),
`RootfsProvisioner` (pinned apt list incl. GTK3/X11/fonts/ca-certificates/dbus,
resolv.conf written + bind-mounted) + `ProvisionDoctor` (really execs proot),
X self-test (xdpyinfo/xwininfo/gtk-query-settings/xeyes/xclock), clipboard bridge.

**NOT present (grep-verified):** any `policies.json` / telemetry / safe-browsing /
update disabling for Firefox; any per-session loading indicator in the X Desktop
window (only the static "Waiting for clients" placeholder).

**Wine (step 3)** — `WinePayload` (file-check only); pinned artifacts:
`wine64_9.0~repack-4build3_arm64.deb` (244 KB) + `wine_9.0~repack-4build3_all.deb`
(52 KB) from ports.ubuntu.com. R4 `SelfTestRunner` builds the 3 steps
(`box64 --version`, proot `wine64 --version`, `wine64 cmd /c echo hello` with a
"must print hello" assertion). Nothing installs the wine debs yet; no deb (`ar`
archive) extractor exists. Note: the pinned debs are **arm64-native Wine 9.0** —
`cmd.exe`/builtin Notepad are arm64 PEs and run without x86 translation; real
x86_64 Windows apps need a payload decision (wine's box64 integration vs an
x86_64 wine payload under box64) — tracked as decision 3b in the plan.

## Environment constraints (verified this session)

- **No arm64 device or emulator in the dev sandbox**: x86_64, no /dev/kvm, 2 cores,
  1 GB RAM, no swap. Device verification runs on the owner's device via playbooks;
  sandbox consumes the Terminal ▸ log + XProtocolTrace output.
- **Full Gradle build impossible in-sandbox** (1 GB RAM): `./gradlew
  testDebugUnitTest` OOM-kills the daemon (two attempts, `hs_err` logs:
  "insufficient memory…"). GitHub Actions `build-apk` stays the authoritative gate.
- **Off-Gradle harness (in the dev sandbox, not in this repo):** kotlinc 2.1.10 +
  the real Compose compiler plugin (`kotlin-compose-compiler-plugin:2.1.10`) + SDK
  `android.jar` + desktop Compose 1.8.2 jars + tiny stubs (Log, R, BuildConfig,
  LocalContext, painterResource, nativeCanvas, MainActivity, AndroidView), tests
  compiled with `-Xfriend-paths` (internal visibility, like AGP's test source set).
  Result on clean `main@39e0f37`: main 61/61 candidate files compile;
  **OK (561 tests), 0 failures** — 576 `@Test` total minus SnapZoneTest (4) and
  AnimationCurveTest (5) (both need `WindowLayer` internals, which pull
  WindowFrame→WindowContent→screens→activity-compose) minus TaskbarComposeTest
  (6, Robolectric; opt-in even in Gradle).

## UI reference (`komalsito/Win11-mimo-version`)

Single file: `Windows11-Web-Emulator.apk` (4.3 MB) = Capacitor WebView wrapper
around a Next.js (Tailwind v4) web app: "A pixel-perfect Windows 11 emulator
running in the browser — window manager, File Explorer, built-in Browser and
X-server compositor". win11React-style surface: centred taskbar pins, widgets,
tray/clock, start menu (pinned grid + recommended), Explorer, Notepad
("Welcome.txt - Notepad"), Terminal, Settings, Store, Photos, Calculator,
Calendar, Mail, Weather, Edge, Recycle Bin, Task Manager, context menus
("Open in Terminal"), mica/acrylic dark+light theming. Owner decision
(2026-10-13): **adopt this UI's look, feel and design elements** in the native
shell; it is a design reference (React/DOM code is not reusable in Compose).
The repo's existing "restyle steps 1–2" (Fluent tokens, Inter, motion curves,
mica/acrylic backdrop) are the beginning of that adoption.

## Snapshot 2026-10-14 — single-window lifecycle + Prefix Manager pass (this repo, uncommitted → committed on main)

Verified against this working tree with the off-Gradle harness (kotlinc 2.1.10,
android.jar 35, desktop Compose 1.8.2 jars, stubs): **681 tests green** (the
624-test subset of the 644 baseline that the harness compiles — the excluded
suites are the Compose-UI/Robolectric ones, same rule as before — plus 57 new).

**Phase 1 (embed the X server / one window):**
- The X server was already in-process (D1a–D1g); what was missing was the
  lifecycle contract: `runtime/SessionLifecycle.kt` (pure, seam-tested) +
  `MainActivity.onDestroy` teardown in the fixed order wineserver -k → stopAll
  → `XServer.stop()` (which previously had NO callers at all).
- Boot-to-session: Settings ▸ Containers ▸ *Boot into X Desktop session*
  (default ON) — DesktopScreen ensures the display and opens the X Desktop
  window maximized; one Activity, desktop inside.
- `XDesktopState.onSurfaceSize` now keeps the X screen locked to the surface
  (`XServer.resizeScreen` + root ConfigureNotify broadcast — previously the
  screen size froze at first start and letterboxed forever).
- `PROOT_NO_SECCOMP=1` added to FirefoxEnvironment + WineEnvironment (was only
  in the provision/doctor envs).

**Phase 2A (browser):** `/dev/shm` provider (`RuntimePaths.ensureShmDir`, mode
1777 best-effort) bound into every session; Firefox software-render prefs +
`LIBGL_ALWAYS_SOFTWARE`/`GALLIUM_DRIVER` (white-screen guard — the display has
no GLX, by design); `runtime/WineBrowserLaunch.kt` = hardened Chromium/
Electron-under-Wine launch plans + a complete `RuntimeRequest` factory (pure,
tested; UI wiring waits on the BYO-installer step, as documented).

**Phase 2B (Wine):** `runtime/WineProvisioner.kt` (pinned debs via dpkg + apt -f,
apt fallback, disk-verified verdict) and `runtime/WinePrefixManager.kt`
(create/list/validate/repair/delete; wineboot --init + wineserver -w; registry
header + #arch validation; stale .update-lock cleanup; graceful kill before
destroy) + `runtime/WinePrefixOps.kt` (guarded workers) + Container Manager UI
(Install Wine payload / Initialize prefix / Repair + state chips). Deliberate
pin updates: ContainerLauncherTest (refusal now names *Initialize prefix*),
FirefoxPoliciesTest, FirefoxSessionTest, ContainerTest.

**Not done / not claimed:** no device certification yet (playbook in
docs/WINE_RUNTIME.md §6); no UI for installing arbitrary Windows apps into a
prefix; `win32` prefixes stay unavailable on arm64 (named, not silently);
RoadmapGatingTest untouched (desktop-engine catalogue stays statically gated —
readiness is the dynamic half, as before).


## Snapshot 2026-10-14 (2) — the X Desktop app is deleted: the desktop layer IS the X screen

Owner directive: "delete the X Desktop app itself and make the whole desktop run
inside the X server … no need to open an app to view things like the browser."
Implemented exactly that, on top of the lifecycle/Prefix-Manager pass above:

- **Deleted**: the `xdesktop` ShellApp entry, the WindowContent branch,
  `XDesktopWindow` (the viewer composable), BrowserApp's `onRequestXDesktop`
  plumbing, and the `shell.bootToDesktopSession` setting (key, store var,
  catalogue entry, toggle) — the server now boots unconditionally with the
  shell, there is no window to boot into.
- **Added**: `XDesktopSurface` (same file, `ui/xdesktop/XDesktop.kt`) composed
  by DesktopScreen UNDER the window layer, full-bleed. Visibility rule: paints
  nothing while no non-root X window is mapped (wallpaper shows through); the
  moment a session maps one, the guest desktop (root background, openbox frames,
  apps, software cursor) takes over the surface. Desktop taps route into X and
  dismiss the Start menu; the hidden IME field now serves the whole desktop.
  A small session pill (STARTING spinner / FAILED reason) replaces the old
  in-window placeholder text.
- **X plumbing**: `XDocument.structureRevision` (bumped by create/destroy/map/
  unmap/reparent/configure/circulate/resize) joins the poll signature, so a
  guest window appearing is detected on the same frame even before its first
  paint; `XDesktopState.guestWindows` drives the visibility gate.
- **Messages/pins updated deliberately**: RoadmapGatingTest's app-id list loses
  `xdesktop` (comment explains why); XSelfTest's "open Start ▸ X Desktop" skip
  reason → "boots with the desktop" (XSelfTestTest pin follows); Diagnostics and
  DesktopBrowserLauncher copy no longer name a viewer window.
- Docs synced: README (diagram, bullets, lifecycle section, browser story,
  Layout), docs/X_SERVER.md (device section + lifecycle), WINE_RUNTIME playbook,
  BROWSER_ENGINES. PIPELINE_STATE's earlier snapshot stays as the record of the
  previous pass. versionName 0.1.0-d1h.2.
- Harness: 681 tests green off-Gradle after the change; whole-tree kotlinc
  syntax check clean (the only errors in the touched Compose files are the
  documented desktop-jar artifacts — painterResource/nativeCanvas/AndroidView/
  nativeKeyEvent — that CI's real Android classpath resolves).

## Snapshot 2026-10-14 (3) — browser device-bug fix + Android-browser detection removed

Owner-reported device bug and directive, both landed in one pass:

1. **Fix**: opening the desktop browser failed with a wine-prefix refusal
   ("desktop session failed to start ... Wine prefix ... never initialised").
   Root cause: `ContainerLauncher.validate` ran the wineboot-marker checks
   against EVERY request — a latent hole since `441222b`, because the Firefox
   session's "prefix" is its profile dir bound at `/root/.mozilla`, not a Wine
   prefix at `/root/.wine`. The checks are now gated on
   `request.guestPrefix == ProotCommand.GUEST_PREFIX` (the structural wine
   signal), and `FirefoxSessionTest` carries the regression test (a firefox
   request with an initialised-binary set reaches spawn).
2. **Removal**: Android browser detection is gone — `Browsers.kt` (PackageManager
   queries, icon loading, `ACTION_VIEW` intents, cache/warm-up), the
   `AndroidBrowser`/`SystemDefault` engine variants, `BrowserState.installed/
   preferredPackage/targetPackage/targetLabel`, the manifest `<queries>` block,
   `SettingsKeys.BROWSER_PACKAGE` + `preferredBrowserOrNull`, the Settings ▸ Apps
   installed list and the BrowserApp "Installed browsers" section. What remains:
   `Urls.kt` (pure parser), the desktop catalogue, a desktop-only resolver
   (stale `android:` prefs resolve to the default engine), and honest blocking —
   an unready engine is named, never silently rerouted off-device.
   Deliberate test rewrites: BrowserTest, EngineResolverTest,
   DesktopBrowserStateTest, RoadmapGatingTest (2 browser tests),
   SettingsStoreTest. Docs synced: README §Browser + gating table + Performance,
   BROWSER_ENGINES.md, THIRD_PARTY.md.

Harness: 673 tests green (681 − 14 removed Android-detection tests + 6 new
desktop-only ones); whole-tree kotlinc syntax check clean. versionName
0.1.0-d1h.3. Device re-check: Browser ▸ Firefox ESR ▸ Open must now reach the
proot spawn (pill → openbox-framed window on the desktop) instead of the
PREFIX_MISSING refusal.

## Snapshot 2026-10-14 (4) — stale apt/dpkg lock recovery (device failure "held by process 6895")

Owner-reported: Firefox setup died with "Could not get lock /var/lib/dpkg/
lock-frontend - held by process 6895 (apt-get)". Cause: a previous provision
whose proot was SIGKILLed (swipe-away/system kill/timeout) orphaned its guest
apt-get under the app uid, still holding the flocks; --kill-on-exit cannot run
from a SIGKILL.

Fix: `runtime/PackageLockRecovery.kt` - HOST-side scan of /proc for same-uid
processes whose first argv token basename is exactly apt-get/apt/dpkg (the
substring rule that would match the provisioning bash or proot carrying the
script text is explicitly excluded and test-pinned), SIGKILL via Os.kill, one
honest log line per action. Wired into BOTH provisioners (RootfsProvisioner +
WineProvisioner) right after the doctor pre-flight, before the jail spawns;
the scripts' existing `dpkg --configure -a` heals the half-configured state.
Off-device the killer is the stubbed android.system.Os, so unit tests and CI
can never kill anything - they pin the scan/match/report logic instead
(PackageLockRecoveryTest, 11 tests).

Harness: 684 tests green. Docs: README provision paragraph + WINE_RUNTIME §2.
versionName 0.1.0-d1h.4. Device re-check: Firefox setup (or Install Wine
payload) right after a force-kill mid-provision must log "lock recovery:
killed stale ... (pid N)" and then complete instead of dying on the lock.

## Snapshot 2026-10-14 (5) — D2a: guest apps are shell apps (owner directive)

Owner report + directive: the browser "says running but nothing is visible"
(first-run font-cache gap had no honest status between client-connect and
first-map, amplified by the pass-through desktop model), and the wanted model
is termux-x11-like: "firefox actually opens as an app on a floating window
with resize options like everything else."

Implemented (D2a, all in this tree; 705 off-Gradle tests green):
- `window/GuestWindows.kt`: `GuestWindowBridge` implements `XWindowManager`,
  attached by DesktopScreen (SubstructureRedirect claim). MapRequest → real
  shell window (`openWithPayload`, GuestWindowPayload, live WM_NAME caption,
  px/dp size clamp); client ConfigureRequest → shell window size; surface
  geometry reports → `wmMapWindow`/`wmConfigureWindow` keeping the X rect ==
  the on-screen rect (the popup-alignment invariant); per-frame `sync()`
  reconciles both sides (guest gone → close shell window; shell closed →
  WM_DELETE then Destroy after ~2 s; never-mapped orphans destroyed).
- `ui/xdesktop/XWindowSurface.kt`: per-window render (`drawWindow` +
  `XDocument.subtreePaintOrder`), input at absolute coords, per-window hidden
  IME field, local software cursor.
- Desktop layer (`XDesktopSurface`) is now the POPUP layer + wallpaper +
  status pill: `draw(popupOnly=true)` composites only override-redirect
  top-levels (GTK menus/dropdowns) with no root background; new pill state
  "Session running - N client(s) connected, no window mapped yet" kills the
  visible-silence class of confusion.
- Browser sessions no longer start openbox (`withDesktop=false`): one WM per
  display, and the shell is it. The prelude remains for a future
  explorer.exe/desktop Wine session (bridge detached) - documented.
- New pure XDocument helpers: subtreePaintOrder, topLevelOf, popupTopLevels,
  windowTitle (XGuestWindowTest); bridge lifecycle (GuestWindowBridgeTest);
  openWithPayload (WindowManagerTest).
- Docs: README (diagram/bullets/browser story/roadmap D2 row/Layout),
  X_SERVER.md §D2 + device section, WINE_RUNTIME playbook, BROWSER_ENGINES.

versionName 0.1.0-d2a.1. Device checks that matter most: Firefox maps into a
shell window within the font-cache window; resize re-lays-out GTK; dropdowns
appear aligned; close terminates the session window (WM_DELETE path); the
RUNNING-unmapped pill shows during the first-run cache build; xeyes/xclock
self-test rows now visibly open (and auto-close) as shell windows.

## Snapshot 2026-10-15 — rootfs/Wine release mismatch: the real cause of "Wine prefix not found"

Owner report: "the rootfs is not available, which makes the container not find the wine
prefix" + "I need the browser firefox ESR to install". Investigated against the live
upstreams by actually downloading every pinned artifact; **all four URLs are alive and all
four hashes match the pins byte-for-byte**, so nothing was "unavailable" at the CDN. The
breakage was a release mismatch inside the manifest itself.

**Root cause (verified, not inferred).** The rootfs was pinned to jammy (22.04.5) while both
Wine `.deb`s were pinned from **noble** (24.04). `dpkg-deb -f` on the real artifacts:
`wine64_9.0~repack-4build3_arm64.deb` declares `Depends: libc6 (>= 2.38)`. jammy ships
libc6 2.35; noble ships 2.39. So on a jammy rootfs:
- `dpkg -i /mnt/wine-debs/*.deb` fails on the unsatisfiable libc6 dependency,
- `apt-get -f install` cannot repair it (jammy's own `wine64` is 6.0.3, a different package),
- `libwine` never installs, so `/usr/lib/wine/wine64` never lands,
- `WinePayload.launcherIn` returns null → `WinePrefixManager.create` refuses before
  `wineboot` → no `system.reg`/`user.reg`/`drive_c` → `ContainerLauncher.validate` reports
  `PREFIX_MISSING`. The refusal text names the prefix, which is why the symptom read as a
  missing prefix rather than an uninstallable payload.

**Fixes landed:**
1. `RuntimeManifest.ROOTFS` → **ubuntu-base-24.04.5-base-arm64.tar.gz**, sha256
   `a91d5a93…f05914f2`, 29,936,675 B (downloaded and hash-verified against Ubuntu's own
   `SHA256SUMS`). The rootfs and the Wine debs are now the same release, which is what makes
   the pinned debs installable at all. Pinned by a new `RuntimeTest`
   (`the pinned rootfs and the pinned Wine debs are the SAME Ubuntu release`) so they cannot
   drift apart silently again.
2. **`ProvisionDoctor.checkSourcesList` now understands deb822.** This was a second,
   independent blocker that the rootfs bump would have walked straight into: noble's
   `/etc/apt/sources.list` is 270 bytes of pure comment with **no `deb` line**, and the real
   sources live in `etc/apt/sources.list.d/ubuntu.sources` (`Types:`/`URIs:`/`Suites:`). The
   old check reported "No active 'deb' lines" and hard-FAILED (blocking) on a perfectly
   healthy noble rootfs, so Provision could never reach apt. The check now reads BOTH formats,
   counts only *enabled* `deb` stanzas (`Types:` matched as a TOKEN, so `deb-src` alone does
   not count), and keeps the one-line path working for imported jammy rootfses.
   `PORTS_RELEASE_URL` moved jammy → noble for the same reason.
   Verified by compiling and running the new parser standalone against the **real extracted
   noble and jammy trees**: noble PASS (2 sources), jammy PASS (10 sources), plus 8 synthetic
   cases (x86 archive, disabled stanza, CRLF, lowercase field names, multi-URI, mixed formats,
   deb-src-only, deb+deb-src).
3. **`BROWSER_PACKAGES` renamed for noble's 64-bit `time_t` transition.** Checked against the
   noble main+universe binary-arm64 `Packages` index: `libgtk-3-0`, `libasound2` and `libxt6`
   **do not exist** in noble arm64 — they are `libgtk-3-0t64`, `libasound2t64`, `libxt6t64`.
   One wrong name fails the whole `apt-get install` with exit 100 (the same failure mode the
   `libdbus-glib-1-2-2` incident is documented for). The other 15 names are unchanged.
   `RuntimeTest`'s pin follows.
4. **Firefox ESR 140.17.0esr → 153.5.0esr** (linux-aarch64). Mozilla's `product-details`
   reports `FIREFOX_ESR = 153.5.0esr`; 140.17.0esr is the last release of a branch now past
   its security-support window, so the old pin was shipping a browser that no longer receives
   fixes. SHA-512 verified byte-for-byte against Mozilla's `SHA512SUMS`
   (`8e8635cc…01bc5a54`), then SHA-256 `6579949c…f97f3d0b` computed from the verified
   tarball (74,115,684 B). Highest versioned symbol in `libxul.so` is GLIBC 2.28, so it runs
   on noble's glibc 2.39. Tar shape unchanged (53 entries, `firefox/` root, launcher mode
   0755, `libxul.so` intact) → the existing `--strip-components=1` install path is unaffected.
5. **`WinePayload` made honest about what Ubuntu actually ships.** Inspecting the real `.deb`
   file lists: Ubuntu provides **no `/usr/bin/wine64`** — `wine64` installs
   `/usr/lib/wine/wine64` + `wineserver64`; `libwine` installs `/usr/lib/wine/wineserver` and
   the builtins incl. `wineboot.exe` (a Windows PE, at
   `/usr/lib/aarch64-linux-gnu/wine/aarch64-unix/`); the bare names users type (`wine`,
   `wineboot`, `wineserver`, `regedit`, …) come from `update-alternatives` in the `wine`
   package's postinst — and `wine64` is **not** among them. New `WinePayload.payloadGap`
   separates "a loader exists" from "wineboot can actually run" (i.e. `libwine` is present),
   and is wired into both `WinePrefixManager.create` and `WineProvisioner`'s disk verdict, so
   a half-installed payload is named instead of failing later at wineboot. Test fixtures that
   modelled a payload as a single made-up `usr/bin/wine64` were made faithful.

**Harness note:** no JDK/Gradle in this sandbox, so the off-Gradle harness could not be run.
Instead: (a) every pinned artifact was re-downloaded and hash-verified against the
publisher's own checksum data; (b) the new deb822 parser was compiled and executed standalone
against the real extracted rootfs trees (10/10 checks); (c) all touched main sources were
compiled with kotlinc and their error histogram is now **identical to the pre-change HEAD
baseline** (the residual errors are kotlinc 1.3 failing on Kotlin 2.x syntax that already
existed at HEAD). That comparison caught two genuine defects in this change before commit —
a `listFiles {}` type-inference failure and a `/*` written inside a KDoc, which Kotlin treats
as a *nested* block-comment opener and which left the comment unclosed. GitHub Actions
`build-apk` remains the authoritative gate - and it earned that title: the first push of
this change came back **727 tests completed, 1 failed**. The failure was a pin this
snapshot had missed, `XSelfTestTest`'s
`BROWSER_PACKAGES.contains("libgtk-3-0")` - an exact list-membership check, so unlike
`TarExtractorTest`'s substring assertion it could not be satisfied accidentally by the
`…0t64` name. Fixed, and that weak substring pin
(`"--no-install-recommends libgtk-3-0"`, which passes for BOTH spellings and would never
have caught a revert to jammy) was tightened to the t64 name at the same time, along with
the fake-apt-output fixtures, so the suite now really pins the rename instead of
tolerating it.

**Still open / deliberately NOT changed here** (the repo's "decision 3b"): the guest command
name and the box64 wrap. `ProotCommand.selfTestPlan`, `SelfTestRunner` and
`ContainerService.request` still invoke `wine64`, which does not exist on Ubuntu (see 5) and
so will exit 127; and `ContainerLauncher.needsBox64` prefixes box64 onto any command starting
with `wine`, but the pinned payload is a **native arm64** ELF, which box64 (an x86_64→arm64
translator) cannot exec. `wineboot`/`wineserver` themselves are fine — the alternatives system
does create those. Fixing this means choosing the x86_64-Windows-app story (wine's own box64
integration vs. an x86_64 wine payload under box64), so it is flagged rather than guessed.
Firefox ESR does not touch any of it: it is native arm64, needs no Wine prefix and no box64.

versionName 0.1.0-d2a.2.

## Snapshot 2026-10-16 — payload-driven Wine invocation (the flagged 3b call, part-decided) + phantom-killer docs + harness rebuilt wider

Owner decisions on record (2026-10-16): the Wine invocation fix is
**payload-driven** (option chosen over the Winlator-recipe-now, name-only and
defer options); the owner's device run of the previous APK **failed somewhere
in the playbook** — the failing rows/logs were requested and are still
outstanding, so nothing here claims the device chain works.

**What changed (code):**
1. `runtime/ElfHeader.kt` (new): the ELF `e_machine` reader extracted from
   `ProvisionDoctor` (which now delegates). The extraction paid for itself
   immediately: `ElfHeaderTest`'s big-endian case caught a latent byte-swap in
   the original BE branch (`(hi shl 8) or lo` where byte 18 is the HIGH byte of
   a BE header). Every real payload here is LE, so it was dormant — fixed, not
   deleted, because a wrong answer on a defensive path is still a bug.
2. `WinePayload` grew the invocation facts: `guestLauncherPath` (the loader's
   GUEST path — Ubuntu's alternatives never create `wine64`, the pinned debs
   install `/usr/lib/wine/wine64`), `launcherArch` (ELF header → ARM64 /
   X86_64 / UNKNOWN — never the name), `resolveGuestCommand` (replaces a BARE
   `wine64` only, only when a payload exists on disk; `wineboot`/`wineserver`/
   path-forms untouched).
3. `ContainerLauncher`: `needsBox64`/`requiredFor` take the rootfs and answer
   from the payload's ELF arch (arm64/unknown → NO box64 — a translator is
   never prepended on a guess; x86_64 → box64, engaged automatically the day
   such a payload is pinned). `buildCommand` resolves the loader before
   wrapping. `runningWineRequests` switched to the STRUCTURAL signal
   (`guestPrefix == /root/.wine`) — with the old box64-rule filter, a native
   arm64 wine session would have skipped the shutdown `wineserver -k` sweep and
   corrupted prefixes on app exit.
4. `SelfTestRunner`: the wine rows exec the resolved loader (box64 prepended
   only for an x86_64 payload), and a rootfs WITHOUT a Wine payload now SKIPs
   rows 2/3 naming "Install Wine payload" — per R4's skip-with-reason rule,
   instead of a misleading red exit-127. `ProotCommand.selfTestPlan` now
   REQUIRES the loader path (no default spelling to drift).
5. KDoc truth pass: `WineProvisioner` (verdict is `launcherIn`+`payloadGap`,
   not `/usr/bin/wine64`), `ContainerService.request` (symbolic default),
   `WineBrowserLaunch` (box64 only for x86_64 payloads; x86/x64 browser exes
   honestly noted as needing the open 3b payload), `DiagnosticsScreen`.

**Tests:** new `ElfHeaderTest` (6: LE/BE e_machine, non-ELF, truncated,
missing/dir); `ContainerLauncherTest` +8/rewritten 2 (x86_64 payload → box64
in front + resolved tail; arm64 payload → NO box64; no payload → nothing
resolved/translated; `needsBox64` payload-driven incl. null-rootfs and
wineboot; `requiredFor` gates box64 on arch; `runningWineRequests` structural
incl. the Firefox-shaped exclusion; `guestLauncherPath`/`launcherArch`/
`resolveGuestCommand` suites with FAITHFUL ELF fixtures at the real Ubuntu
loader path); `SelfTestRunnerTest` +2/updated pins (resolved paths in the
command lines, arm64 → no box64, x86_64 → box64 before the loader, no-payload
→ SKIPPED naming Install Wine payload); `RuntimeTest` selfTestPlan pin follows
the required loader parameter. Deliberate pin rewrites are named here because
they changed EXPECTED behaviour, not to make red green.

**Harness (this sandbox, rebuilt from scratch — not in the repo):** kotlinc
2.1.10 + JDK 17 + android.jar 34 + desktop Compose 1.8.2 jars (runtime, ui,
ui-unit/geometry/graphics/text/util, foundation(+layout), material3,
animation) + androidx core/collection/annotation jars + xz + the REAL compose
compiler plugin for BOTH main and test compiles (a test compile without the
plugin mismatches @Composable lambda signatures — `Function1` vs `Function2` —
at runtime) + stubs (R with all 14 referenced ids NON-ZERO because
RoadmapGatingTest asserts `iconRes != 0`, BuildConfig, MainActivity,
LocalContext). Result: **728 tests green, 0 failures** — runtime + download +
container + ALL x11 wire suites + window (minus AnimationCurveTest, which
needs WindowLayer internals) + settings + explorer/notepad/taskmanager/
terminal/browser/settings UI-state suites. Excluded vs Gradle's 644+:
Robolectric (opt-in there too), WindowLayer/Frame/screen composables, theme.
Sandbox gotcha worth recording: tool-typed `"$ARENA_WORKSPACE"/...` literals are
rewritten to the tenant session path, but file CONTENTS are not — scripts must
derive paths via `$(cd dir && pwd -P)` (relative to the workspace root), or
`find`/`ls` inside them see a phantom FS. Also: keep "$ARENA_WORKSPACE" under the
~10k-file snapshot cap (prune extracted SDK trees to the single jars needed),
or per-call restores come back partially empty.

**Docs:** README (R2 command-build rule, R4 skip list + gating-table row,
phantom-killer bullet in Session lifecycle), WINE_RUNTIME (§1 chain, §4,
playbook steps 3–5, new §7 phantom-process killer with the honest adb-toggle
caveats), NATIVE_RUNTIME (exec-model diagram — also fixed its stale `--`
marker in the example line, bare-names rule, R4 status row), PIPELINE_PLAN
(3b part-decided note). versionName 0.1.0-d2a.3.

**Not done / blocked:** device certification of ANY of it (owner's failing run
logs outstanding — that reprioritises everything else); push to GitHub blocked
in this sandbox (no credentials — commits are local until the owner pushes or
provides a PAT); the x86_64 Windows-app payload choice (3b proper) stays open.
