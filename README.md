# Win11 Winlator Edition

A Windows 11-**style** desktop shell for Android that will run Windows productivity
software (Wine + Box64 inside a glibc rootfs under proot) behind a native Kotlin /
Jetpack Compose UI. One self-contained APK, no Termux, no XSDL, no adb, no root.

- `arm64-v8a` only, `minSdk 28`, `targetSdk 34`
- The app owns the X server, the processes (foreground service) and the downloads.
- No Microsoft assets: fonts, icons, artwork and the wallpaper are original or open-licensed.

## Roadmap

| Step | Scope | Status |
| --- | --- | --- |
| N0 | Gradle project + Compose shell screen (wallpaper + taskbar), CI debug APK | done |
| N1 | Native taskbar (Start menu, tray, clock) + window manager (drag/resize/snap/z-order) | done |
| N2 | Apps: Explorer, Notepad, Settings, Browser, Task Manager, Terminal, Container Manager | **done** |
| R1 | proot/box64 exec from `nativeLibraryDir`, inventory/permissions, SHA-256 pins, `--version` probes, proot command builder, Diagnostics window | done (binaries not vendored) |
| R2 | `ContainerService` (foreground, Stop all), `proot -> box64 -> wine` launch with the explicit env, per-container status, logs into the shared buffer | done (inert until R1 binaries + R3 rootfs exist) |
| R3 | Rootfs/Wine download: pinned SHA-256 (fail closed), resumable via `Range`, free-space check, progress UI, SAF import | done (extraction needs R1's proot) |
| R4 | Diagnostics self-test: `box64 --version`, `wine64 --version`, `wine64 cmd /c echo hello`, green/red/skipped with the failing command + log excerpt | done |
| D1 | In-app X server on a private unix socket, fullscreen surface running `explorer.exe /desktop`, touch/mouse/keyboard | **partial** (D1a+D1b+D1c done: spec-accurate protocol core, socket, renderer, input, soft-keyboard text injection, focus/grabs/cursors/fonts/colours, XTest, SubstructureRedirect WM — 98 x11 wire tests green, verified by running them on a JVM; guest launch waits on the R1 binaries; see `docs/X_SERVER.md`) |
| P1–P2 | Clipboard, shortcuts, print-to-PDF, audio, fonts, SAF, Open-with, presets, backups | **partial** (P1 clipboard: the X selections ↔ Android text bridge is done and wire-tested — guest copies push out, Android-side changes reclaim PRIMARY/CLIPBOARD; rich targets and the remaining P1/P2 items are not started) |

## Animation

Windows fade in and settle from 94% scale anchored at the caption (220 ms), closing
plays the reverse and only then leaves the window list, minimizing/restoring shrinks
and grows in place (140 ms), and the snap preview cross-fades. One frame loop in
`WindowLayer` drives every window - no per-window coroutines. Settings ▸
Personalisation ▸ *Window animations* turns all of it off.

## Crash-safety rules

Three rules came out of a real crash report (Settings ▸ Storage ▸ Download killed the
process and the Settings window could not be reopened):

1. **No `Thread { }` without a catch-all.** A missing `INTERNET` permission made
   `URL.openConnection()` throw `SecurityException` on a bare worker thread, which takes
   the whole process with it. Download/import/extract/remove now run through one guarded
   worker helper, and `RootfsManager.download` catches `Throwable` and turns it into a
   `FAILED` state with the exception name in the message.
2. **No disk I/O during composition.** `RootfsManager` construction used to scan and hash
   archives on the UI thread; it now does nothing until `refreshAsync()` runs on a
   low-priority thread, hashes are cached per file stamp, and `freeBytes()` is cached.
3. **A broken window must not take the shell down.** `WindowContent` renders a failure card
   with Retry/Close inside the window instead of letting an exception escape into the
   Activity, which is what made one broken screen unopenable.

Also fixed: background state writes now go through `Snapshot.withMutableSnapshot` so Compose
actually sees them, and the Container Manager only starts/stops the foreground service when
the set of *running* containers changes (it used to start and immediately stop it on every
button press, which risks a foreground-service start violation).

## X server (D1) - read this before using it

`x11/` contains a from-scratch X11 core: wire marshalling, the connection handshake,
request dispatch, the window/atom/property document, a keyboard map (X keycode = evdev+8),
input translation with correct modifier masks, and a `LocalServerSocket` on the abstract
name `/tmp/.X11-unix/X100` (which proot does not isolate, so a guest `DISPLAY=:100`
reaches it with no bind mount).

**D1c made the server speak to real clients.** The opcode table now matches the X11R6
specification exactly (it was silently wrong from opcode 92 up: a real libX11 client's
`QueryExtension` - opcode 98 - used to hit our `SetCloseDownMode` no-op and the client
would hang waiting for a reply that never came). Added on top: input focus
(SetInputFocus/GetInputFocus with FocusIn/FocusOut and focus-directed keys), real
pointer/keyboard grabs with event redirection (what Wine needs for menus), passive
button/key grabs, WarpPointer, Enter/LeaveNotify, a built-in "fixed" font served through
OpenFont/QueryFont/QueryTextExtents/ListFonts, an rgb.txt colour table
(LookupColor/AllocNamedColor/QueryColors), cursor resources with a software cursor drawn
on the surface, SendEvent delivery, ListProperties/RotateProperties, vector drawing
(PolyLine/PolySegment/PolyPoint/PolyArc/PolyFillArc/FillPoly/ImageText16/PolyText8/16),
a pure-Kotlin rasteriser that makes GetImage return real pixels, the whole
keyboard/pointer/screensaver/host control tail block, and the **XTest extension**
(QueryExtension reports it; FakeInput synthesises key/button/motion events).
The soft keyboard now works: the X Desktop window owns a hidden IME field and commits
become real KeyPress/KeyRelease sequences via `XInput.injectText` (Latin-1 reach;
CJK needs XIM/XKB later). The keymap itself was fixed to the true evdev+8 layout - the
old android+9 letter block collided with the server's own modifiers ('m' *was* Shift_L)
and hardware punctuation landed on letters ('-' typed 'p').

**The server runs, renders, takes input and passes 98 wire tests (run for real on a
JVM with Kotlin 2.1.10); nothing guest-side connects yet.** The native binaries (R1)
are not vendored, so `explorer.exe /desktop` cannot be started - `ExplorerSmokeTest`
scripts its exact wire sequence instead. `XDisplayList` records every drawing request
per drawable, `XCanvasRenderer` composites the window stack into the **Start ▸ X
Desktop** window, raw MotionEvents/key events are translated through `XInput`, and
top-level maps/configures are redirected to `ShellWindowManager` (SubstructureRedirect)
so the shell places guest windows. See `docs/X_SERVER.md` for the remaining limits
(no RENDER/Composite/XShm/XKB/XIM, fixed font metrics, Latin-1 text injection).

## What is deliberately not usable yet

Nothing is stubbed by accident. Every app window opens; the parts that need the container
runtime or the X server say exactly which step they wait for, and `RoadmapGatingTest`
fails if that ever changes silently:

| Thing | State today | Unlocked by |
| --- | --- | --- |
| Container Manager | Real UI, real persisted containers; Start/Stop answer "no runtime yet" and mark the container *Not ready* | R1 binaries + **R2** service |
| Wine prefix, `wineboot`, installing apps | Not created | R2 + R3 |
| Desktop browsers (Firefox ESR, Edge arm64, Chromium) | Firefox ESR is **fully plumbed**: pinned R3 artifact, readiness checks that name the missing piece, proot launch plan through R2's launcher on the shell's X server. Until the runtime exists, selecting it falls back to an Android browser; Edge/Chromium stay listed with their gating steps | **R1 binaries** + R3 rootfs/GUI libs (Firefox ESR), **R1/R2/R3 + D2** (Edge) |
| Terminal `wine`/`box64`/`proot`/`cmd` | Answer "not available yet" and name the step | R1 + R2 |
| Task Manager container rows | Absent (no processes to show) | R2 |
| Diagnostics wine64 self-test | Command lines are built and shown, not executed | R2 + R3 + **R4** |

The single blocker for all of it is the native payload: **no `libbox64.so` / `libproot.so`
is committed** (see `docs/NATIVE_RUNTIME.md`). box64 builds from `.github/workflows/build-native.yml`
(`workflow_dispatch`); proot needs a patched Android fork and is GPL-2.0, so vendoring it
is a decision for you, not something I will slip into the repo.

## Performance

The shell is written so that the common interactions recompose as little as possible:

- **Wallpaper**: brushes and petal geometry are built once in `drawWithCache` and reused;
  they are rebuilt only when the surface size changes, not on every frame while a window
  animates above it.
- **Windows**: geometry (`offset`/`size`) and the whole open/close/minimize animation are
  read inside `graphicsLayer`/`offset` lambdas, i.e. in the layout/draw phase. Dragging,
  resizing and the 60 fps fade therefore recompose **no** Compose code at all - the app
  content inside the window is untouched.
- **Taskbar**: each pin is its own composable, so the window-list reads are scoped to that
  pin. Moving a window no longer rebuilds the bar, its tooltips or the clock.
- **First open**: the expensive one-offs are warmed at startup on a low-priority background
  thread - the PackageManager browser query (cached per process), the SHA-256 of the shipped
  binaries (cached per file stamp) and every app's payload factory (class loading, disk
  reads, container list parsing). `Browsers.invalidate()` exists for refresh-after-install.
- **Frame loop**: one `withFrameNanos` loop drives every window animation and only writes
  state while something is actually animating; steady windows cost no writes, so a desktop
  with idle windows does not recompose at 60 fps.
- `WindowState.sizeFor()` gives the layout modifier its size without allocating a
  `WindowBounds` per frame (covered by a test that pins it to `bounds()`).

Debug builds are still JIT-compiled and unoptimised; measure with a release build (P2)
before chasing anything else.

## Self-test (R4)

Start ▸ All apps ▸ **Diagnostics** ▸ *Run self-test* executes the three required checks and
shows one card per step: verdict (**PASS** green / **FAIL** red / **SKIPPED** amber), the
exact command line, exit code, duration, the reason, and the output excerpt in a console
block. A summary card reports `n passed, n failed, n skipped` and refuses to claim
"All green" while anything is skipped. The failing command and its log excerpt are also
written to the shared log (Terminal ▸ `log`).

The rule the tests enforce: a step whose prerequisites are missing is **skipped with the
reason named** (no `libbox64.so`, no `libproot.so`, no rootfs, rootfs not unpacked) and
never counted as a pass — and `wine64 cmd /c echo hello` must actually print `hello`; a
zero exit code without the output is a failure.

## Runtime download (R3)

Settings ▸ Storage ▸ **Runtime download** lists the pinned artifacts with their size,
SHA-256 prefix and URL, plus Download / Resume / Cancel / Import / Extract / Remove and a
free-space check for the whole set. The manifest covers the Ubuntu 22.04.5 arm64 rootfs,
the two Wine 9.0 loader `.deb`s and **Firefox ESR 140.17.0 (linux-aarch64)** - the
desktop browser engine, whose pin was anchored to Mozilla's own SHA512SUMS before the
SHA-256 was recorded.

Rules the code enforces (all unit-tested): a file is written to `<name>.part` and only
promoted to its real name after its SHA-256 matches the pin; a mismatch deletes the
download; a server that ignores `Range` is treated as an error rather than producing a
corrupt tail; an interrupted download keeps its `.part` and resumes; SAF imports are held
to the same pin as network downloads; and extraction refuses to run until proot's tar
exists, because unpacking a rootfs needs `proot --rootfs / -0 tar` for device nodes and
root ownership. Hashes come from the publishers (Ubuntu `SHA256SUMS`, the Ubuntu archive
`Packages` index) and are recorded in `docs/THIRD_PARTY.md`.

## Container runtime (R2)

`ContainerService` is a foreground service (`specialUse`, with the required property,
`FOREGROUND_SERVICE` + `POST_NOTIFICATIONS`) that owns the container processes - no
Termux, no adb. Its notification has a **Stop all** action; `onDestroy` stops every child.

`ContainerLauncher` is the actual logic and contains no Android types, so all of it is
unit-tested with a fake `ProcessLauncher`:

1. **Validate before spawning** and refuse with a named reason: `BINARIES_NOT_READY`
   (which includes the fail-closed SHA-256 verdicts), `ROOTFS_MISSING`, `PREFIX_MISSING`,
   `NO_COMMAND`, `ALREADY_RUNNING`, `SPAWN_FAILED`.
2. **Build the command**: `proot --rootfs <rootfs> --link2symlink --kill-on-exit
   --bind <prefix>:/root/.wine --bind <drive>:/mnt/<letter> … --cwd /root -- box64 wine64 cmd`.
   box64 is inserted only for Windows payloads (`wine*`, `*.exe`), not for native commands.
3. **Build the environment**: `WineEnvironment.build()` plus this container's
   `PROOT_LOADER`/`PROOT_LOADER_32`, sorted so Diagnostics can diff it.
4. **Stream** stdout/stderr into the shared `LogBuffer` (Terminal `log` and Diagnostics
   read the same lines) and map the exit code to `STOPPED`/`ERROR`.

`ContainerManagerState` now drives it through `LauncherRuntime`, starts the service on a
successful launch, stops it when nothing is running, and each container card shows its own
log excerpt.

**It is inert on purpose**: with no `libbox64.so`/`libproot.so` and no rootfs, Start
reports exactly which piece is missing and marks the container *Not ready*. Nothing is
guessed or faked.

## Native runtime (R1)

proot and box64 are shipped as `lib*.so` in `app/src/main/jniLibs/arm64-v8a/` and
**executed in place from `nativeLibraryDir`** — `targetSdk 34` forbids exec from app data,
and `jniLibs.useLegacyPackaging = true` keeps them extracted.

Every shipped binary must be pinned by SHA-256 in `app/src/main/assets/native/checksums.txt`;
a required binary that is unpinned or mismatched is **refused** (`CHECKSUM_NOT_PINNED` /
`CHECKSUM_MISMATCH`) rather than exec'd. `.github/workflows/build-native.yml`
(`workflow_dispatch` only, so it can never affect `build-apk`) builds box64 from a pinned
ref with `-DANDROID=ON -DWINLATOR_GLIBC=ON` and uploads `libbox64.so` + provenance +
`SHA256SUMS.txt`. No binary is committed in git yet, and proot needs a patched Android fork
(upstream does not build against bionic). `docs/NATIVE_RUNTIME.md` has the full procedure
and the licence rules (proot is GPL-2.0 and is only ever exec'd, never linked).

## Container Manager

A container = a Wine prefix + its Box64 preset + drive mappings + environment. The
manager creates/renames/deletes them (`<files>/containers/<id>/{container,prefix}`),
records them in a hand-rolled `containers.list` line format (atomic temp-file + rename,
no new dependency), picks a default, edits the preset (General/Office/.NET/Electron),
architecture (WoW64 / 64-bit), DPI, and drive letters (C: = prefix and Z: = guest root
are implicit), and shows the **exact environment map R2 will exec with**, sorted for
diffing.

`WineEnvironment.build()` is the R2 contract already implemented and unit-tested: fully
explicit `DISPLAY`, `WINEPREFIX`, `WINEARCH`, `WINEDEBUG`, `WINEDLLOVERRIDES`
(`mscoree,mshtml=` so Wine never auto-downloads Gecko/Mono), `PATH`, `LD_LIBRARY_PATH`,
locale, PulseAudio socket, `PROOT_ROOTFS`/`PROOT_LOADER`, plus per-preset `BOX64_*`
tuning, with the container's `extraEnv` applied last so a user can override anything.
`GuestPaths` converts host↔Windows paths (longest mapping wins) for the P1 "open with"
plumbing.

Start/Stop/Restart go through a `ContainerRuntime` interface; today's
`NotInstalledRuntime` answers with exactly which step is missing (R1 binaries, R2
service, R3 rootfs) instead of failing silently, and the container is marked
"Not ready" with that message.

## Terminal

N2's Terminal is an honest local console, not a fake Windows prompt: `help`, `pwd`,
`ls`/`dir [-a]`, `cd`, `cat`/`type`, `echo`, `date`, `free`, `env`, `log`, `clear`,
`version`, `uname`, `whoami`, with quote/escape-aware argument splitting, command history
on the arrow keys, exit codes, and quick-command chips. `wine`, `wine64`, `box64`, `proot`,
`cmd`, `container` and `start` all answer "not available yet" and name the step that
delivers them - they never pretend to work.

Execution sits behind a `CommandExecutor` interface, so **R2 swaps in a container
executor** (`proot -> box64 -> wine` over a pty) without touching this UI, and the
transcript is mirrored into a bounded `LogBuffer` that R2's ContainerService and R4's
Diagnostics screen read from.

## Task Manager

Processes tab: the shell process (real RSS from `/proc/self/status`, CPU% from
`/proc/self/stat` against `/proc/stat` jiffies), one row per open window with its real
status (Running / Suspended / Closing), and **End task** which routes through the window
manager - so a dirty Notepad still gets its save dialog. Performance tab: live CPU and
memory sparklines refreshed every 2 s. Container rows (proot, box64, wine, X server)
appear when R2's ContainerService reports them. Long-press any taskbar pin for a jump
list: running windows, New window, Task Manager.

## Browser

The Browser pin opens a shell window with an address bar. The shell never renders web
content: it resolves the input (URL, bare hostname -> `https://`, otherwise a search)
and hands it to a real browser via `ACTION_VIEW`. **Firefox is preferred** when
installed (`org.mozilla.firefox`), then the user's pinned choice, then Android's
default handler; Settings ▸ Apps lists what is installed and stores the preference.
Firefox is never bundled in the APK.

For desktop-class browsing (profiles, extensions, AI/Copilot-style sidebars) the shell
models a second engine family - **Linux arm64 Firefox ESR / Chromium** in the rootfs and
**Windows arm64 Edge** under Wine. **Firefox ESR's launch path is fully plumbed (D1c)**:
Mozilla's official self-contained arm64 build is a pinned R3 artifact, a guarded worker
evaluates readiness (R1 binaries → rootfs → Firefox installed → GTK3 stack) into a
Compose-state snapshot, and a ready engine launches through R2's `ContainerLauncher` as
`proot → /opt/firefox-esr/firefox -P shell <url>` with an explicit sandbox-off
environment on `DISPLAY=:100` - no box64, persistent profile, foreground service, X
Desktop window focused automatically. Until the runtime is vendored the card names the
missing piece and selection falls back to an Android browser, exactly as
`RoadmapGatingTest` pins it. See `docs/BROWSER_ENGINES.md` for the feasibility write-up,
the licensing constraints (Edge is proprietary and must not be redistributed) and the
open decisions.

## UI tests

`./gradlew testDebugUnitTest` runs the 390 pure-JVM tests (window geometry, snap zones,
Explorer, Notepad encodings, settings, URL/engine resolution, /proc parsing, argv
splitting, the log buffer, the built-in commands, the terminal transcript/history, the
container codec/store, guest<->host path mapping, the Wine environment builder, the
native-binary inventory/permission handling, SHA-256 pinning and its fail-closed verdicts,
the probe runner, the proot command line, and the R2 launcher: every refusal path
(missing/unpinned binaries, missing rootfs, missing prefix, empty command, spawn failure,
double start), the `proot -> box64 -> wine` command shape, drive binds, the explicit
environment, stop/restart/stopAll, exit-code -> status mapping, and R3's downloader:
resume offsets, hash fail-closed, space refusal, cancellation, servers that ignore
`Range`, SAF import verification, the extract gate, and the R4 self-test: command order,
proot wrapping, prefix binding, "printed hello" assertion, and skip-instead-of-pass when
box64/proot/rootfs are missing; plus the D1c x11 suites: the spec opcode table, focus and
grab event routing, warps, fonts/colours/cursors, SendEvent, GetImage against the
pure-Kotlin rasteriser, XTest FakeInput, soft-keyboard text injection and the IME commit
extractor; and the Firefox ESR session: native (never box64) proot command shape, profile
and X11 binds, the explicit sandbox-off environment, install/GTK checks, the R3 state
machine's READY marker, the pinned Mozilla artifact, and the desktop-engine launch seam
with its gating contract; and the P1 clipboard bridge: server-wide selection ownership,
cross-client SelectionRequest routing, the ICCCM mask-0 SendEvent idiom, inline shell
conversions with TARGETS, ownership lifecycle, and the end-to-end guest-copy push
through a fake bridge).

Compose tests that actually **compose** the taskbar (Robolectric) are opt-in because they
need a couple of GB of RAM:

```bash
./gradlew testDebugUnitTest -PuiTests=true      # CI runs this as a separate step
```

They exist because of a real regression: a `Popup` that was composed unconditionally drew
every taskbar pin's jump list at once and stole input focus, so nothing could be clicked.
Popup content must always be gated by `expanded`/`if (open)`.

## Build

```bash
cp local.properties.example local.properties   # set sdk.dir
./gradlew assembleDebug                         # app/build/outputs/apk/debug/
```

CI: `.github/workflows/build-apk.yml` runs `testDebugUnitTest` + `assembleDebug` on
`ubuntu-latest` (JDK 17, Android SDK preinstalled on the runner) and uploads the debug
APK as the `win11we-debug-apk` artifact.

## Layout

```
app/src/main/java/dev/win11we/shell/
  MainActivity.kt              single Activity, hosts Compose
  window/                      AppSpec, WindowState (geometry), WindowManager (z-order/focus), SnapZone
  ui/theme/Color.kt            Windows 11-style dark palette
  ui/desktop/DesktopScreen.kt  wallpaper + window layer + Start panel + taskbar
  ui/desktop/BloomWallpaper.kt procedurally drawn original wallpaper
  ui/taskbar/Taskbar.kt        bar layout: widgets chip, centred pins, tray, clock
  ui/taskbar/TaskbarParts.kt   button/indicator, tooltips, jump-list popup, tray, clock
  ui/start/StartMenuPanel.kt   scrim + animated Start menu (search, pinned, all apps, recommended)
  ui/window/WindowLayer.kt     z-ordered rendering, open/close/minimize animations, snap preview
  ui/window/WindowFrame.kt     frame, caption, drag-to-move/snap, 8 resize handles
  ui/window/WindowControlButton.kt  minimize / maximize-restore / close
  ui/window/WindowContent.kt   routes a window to its app body
  ui/explorer/FileEntry.kt     file model, listing, filtering, sorting, size/type formatting
  ui/explorer/ExplorerState.kt navigation + selection + create/rename/delete (all pure JVM)
  ui/explorer/ExplorerScreen.kt toolbar, breadcrumb, nav pane, status bar, dialogs, menus
  ui/explorer/ExplorerViews.kt details rows and tiles grid
  ui/explorer/ExplorerIcons.kt extension -> icon mapping, sidebar locations
  ui/notepad/DocumentIO.kt     encodings, BOM, line endings, Ln/Col maths
  ui/notepad/Windows1252.kt    bundled cp1252 codec (Notepad's "ANSI")
  ui/notepad/NotepadState.kt   buffer, dirty flag, find/replace, zoom, caption
  ui/notepad/NotepadMenuBar.kt File / Edit / Format / View menu bar
  ui/notepad/NotepadViews.kt   editor, find & replace bar, status bar
  ui/notepad/NotepadScreen.kt  SAF open/save/save-as, shortcuts, close guard
  settings/SettingsStore.kt    persisted, observable shell settings (+ keys, presets)
  ui/settings/SettingsUi.kt    sections, searchable settings catalogue
  ui/settings/SettingsScreen.kt Personalisation / Apps / Storage / Containers / Diagnostics / About
  ui/browser/Browsers.kt       installed-browser discovery, intent building, URL parsing
  ui/browser/BrowserState.kt   address bar, target package, launch bookkeeping
  ui/browser/BrowserApp.kt     Browser window: address bar + browser chooser
  ui/browser/BrowserEngine.kt  Android vs desktop engines, catalogue, resolver
  ui/taskmanager/ProcessList.kt  /proc parsing, CPU maths, sampler, history ring
  ui/taskmanager/TaskManagerScreen.kt Processes + Performance tabs, End task
  ui/terminal/LogBuffer.kt     bounded shared log sink + shell-style argv splitting
  ui/terminal/SharedLog.kt     the one log the Terminal, service and Diagnostics share
  ui/terminal/CommandExecutor.kt CommandResult, executor interface, built-in local commands
  ui/terminal/TerminalState.kt transcript, prompt, cwd, history, exit/clear handling
  ui/terminal/TerminalScreen.kt console transcript, quick commands, input row
  container/Container.kt       container model, drives, guest<->host paths, wine env builder
  container/ContainerStore.kt  containers.list codec + on-disk store + runtime interface
  ui/containers/…              Container Manager state and screen (presets, drives, env preview)
  runtime/NativeBinaries.kt    jniLibs inventory, +x fix, PrepareResult (R1)
  runtime/RuntimeProbe.kt      ProcessRunner seam + `--version` probes with explicit env
  runtime/ProotCommand.kt      the proot command line: fixed prefix bind, drives, R4 plan
  ui/diagnostics/…             Diagnostics window (binaries, probes, log excerpt)
  runtime/ProcessLauncher.kt   start/stream/destroy seam (real ProcessBuilder + fakes in tests)
  runtime/ContainerLauncher.kt validate -> build command+env -> start -> track status/logs (R2)
  runtime/FirefoxSession.kt  Firefox ESR desktop-browser session: guest command, env, binds, install checks
  runtime/DesktopBrowserLauncher.kt readiness worker (named missing step) + the gated launch path
  runtime/ContainerService.kt  foreground service, notification, Stop all, RuntimePaths/RuntimeHolder
  runtime/download/Artifact.kt   pinned manifest (Ubuntu 22.04.5 arm64, Wine 9.0 debs) + real hashes
  runtime/download/Downloader.kt resume/hash/space/cancel logic + https Transfer seam
  runtime/download/RootfsManager.kt per-artifact state machine, SAF import, extract gate
  x11/X11.kt                 wire primitives, spec-accurate opcodes, events, masks (D1a/D1c)
  x11/XDocument.kt           window tree, atoms, properties, hit testing, focus/grabs/cursors, WM hooks
  x11/XConnection.kt         handshake, request dispatch, replies/errors/events, XTest, XSink
  x11/XDisplayList.kt        per-drawable DrawOp store + pure-Kotlin rasteriser for GetImage (D1b/D1c)
  x11/XColors.kt             rgb.txt named-colour table + rgb:/#hex parsing (D1c)
  x11/XSelections.kt         selection ownership model, clipboard-bridge seam, target codec (P1)
  x11/XIme.kt                IME commit extraction for the soft-keyboard path (D1c)
  x11/ShellWindowManager.kt  SubstructureRedirect WM: cascaded placement of guest windows
  x11/XKeyboard.kt           true evdev+8 keycode map, US ASCII table, Latin-1 range (D1c)
  x11/XInput.kt              device input -> X events, modifier masks, injectText/injectKeysym (D1c)
  x11/XServer.kt             LocalServerSocket on /tmp/.X11-unix/X100, accept + serve, warp sync
  ui/xdesktop/XDesktop.kt    Bitmap/Canvas renderer + input surface + software cursor + hidden IME field (D1b/D1c)
  ui/xdesktop/XDesktopClipboard.kt  Android clipboard bridge + the DesktopScreen wiring effect (P1)
  ui/settings/RootfsPage.kt    Settings ▸ Storage ▸ Runtime download (progress, actions)
  runtime/SelfTestRunner.kt    the R4 self-test: 3 steps, verdicts, reasons, report/log excerpt
```

Unit tests (`testDebugUnitTest`, plain JVM, no Robolectric — 390 tests; during D1c the
whole suite was additionally compiled and executed off-device against android.jar 35 and
the pinned Compose artifacts, so the counts are measured, not estimated): snap zones,
maximize/restore, halves snapping, edge/corner resize with minimums, on-screen clamping,
z-order/focus, taskbar toggle open-minimize-restore, Explorer listing/filtering/sorting,
Windows-style size and type labels, back/forward/up/trail navigation, selection,
create/rename/delete and the revision counter that refreshes the list,
Notepad encoding/BOM round trips, CRLF-LF-CR detection and preservation, Ln/Col maths,
find next/previous with wrap and match-case, replace/replace-all, zoom clamping,
the bundled windows-1252 codec, the window close guard, the two-phase close
animation, the animation curve, settings persistence/defaults/fallbacks, settings
search, URL parsing (host vs. search), the browser-engine resolution order (Firefox
first, desktop engines gated on the runtime), `/proc/stat` + `/proc/<pid>/stat` parsing,
per-process and total CPU maths, and the history ring buffer.

See `docs/THIRD_PARTY.md` for third-party components and licences.
