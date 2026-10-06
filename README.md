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
| N2 | Apps: Explorer, Notepad, Settings, Browser, Task Manager (done) · Terminal, Container Manager | in progress |
| R1–R4 | proot/box64 in `jniLibs`, ContainerService, rootfs download, self-test | — |
| D1–D2 | In-app X server (fullscreen surface, then one surface per top-level window) | — |
| P1–P2 | Clipboard, shortcuts, print-to-PDF, audio, fonts, SAF, Open-with, presets, backups | — |

## Animation

Windows fade in and settle from 94% scale anchored at the caption (220 ms), closing
plays the reverse and only then leaves the window list, minimizing/restoring shrinks
and grows in place (140 ms), and the snap preview cross-fades. One frame loop in
`WindowLayer` drives every window - no per-window coroutines. Settings ▸
Personalisation ▸ *Window animations* turns all of it off.

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
