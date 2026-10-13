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
