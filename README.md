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
| R1–R4 | proot/box64 in `jniLibs`, ContainerService, rootfs download, self-test | — |
| D1–D2 | In-app X server (fullscreen surface, then one surface per top-level window) | — |
| P1–P2 | Clipboard, shortcuts, print-to-PDF, audio, fonts, SAF, Open-with, presets, backups | — |

## Animation

Windows fade in and settle from 94% scale anchored at the caption (220 ms), closing
plays the reverse and only then leaves the window list, minimizing/restoring shrinks
and grows in place (140 ms), and the snap preview cross-fades. One frame loop in
`WindowLayer` drives every window - no per-window coroutines. Settings ▸
Personalisation ▸ *Window animations* turns all of it off.

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
**Windows arm64 Edge** under Wine - selectable now, launchable once R1/R3/D1 land.
See `docs/BROWSER_ENGINES.md` for the feasibility write-up, the licensing constraints
(Edge is proprietary and must not be redistributed) and the three open decisions.

## UI tests

`./gradlew testDebugUnitTest` runs the 171 pure-JVM tests (window geometry, snap zones,
Explorer, Notepad encodings, settings, URL/engine resolution, /proc parsing, argv
splitting, the log buffer, the built-in commands, the terminal transcript/history, the
container codec/store, guest<->host path mapping and the Wine environment builder).

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
  ui/terminal/CommandExecutor.kt CommandResult, executor interface, built-in local commands
  ui/terminal/TerminalState.kt transcript, prompt, cwd, history, exit/clear handling
  ui/terminal/TerminalScreen.kt console transcript, quick commands, input row
  container/Container.kt       container model, drives, guest<->host paths, wine env builder
  container/ContainerStore.kt  containers.list codec + on-disk store + runtime interface
  ui/containers/…              Container Manager state and screen (presets, drives, env preview)
```

Unit tests (`testDebugUnitTest`, plain JVM, no Robolectric — 121 tests): snap zones,
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
