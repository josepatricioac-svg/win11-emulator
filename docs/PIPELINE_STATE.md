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
