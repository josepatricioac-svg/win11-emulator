# Third-party components and licences

Updated at every step. Nothing here is a Microsoft asset.

## N0 — build tooling and libraries

| Component | Version | Licence | Used for |
| --- | --- | --- | --- |
| Android Gradle Plugin | 8.9.1 | Apache-2.0 | Build |
| Gradle (wrapper) | 8.11.1 | Apache-2.0 | Build; `gradle/wrapper/gradle-wrapper.jar` is the upstream jar from the `gradle/gradle` v8.11.1 tag (SHA-256 verified against services.gradle.org) |
| Kotlin + Compose compiler plugin | 2.1.10 | Apache-2.0 | Language |
| AndroidX Compose BOM | 2025.07.00 (foundation/ui 1.8.3, material3 1.3.1) | Apache-2.0 | UI (ui, ui-graphics, material3, ui-tooling). Bumped in N2 for the new `BasicTextField`/`TextFieldState` + `UndoState` APIs. 1.9+/1.12 were rejected: they require compileSdk 36 / AGP 9.1. |
| AndroidX core-ktx / activity-compose / lifecycle-runtime-ktx | 1.15.0 / 1.10.1 / 2.8.7 | Apache-2.0 | Activity, edge-to-edge, lifecycle |
| JUnit 4 | 4.13.2 | EPL-1.0 | Unit tests (test scope only) |

### N1 additions

| Component | Version | Licence | Used for |
| --- | --- | --- | --- |
| Compose Material 3 tooltips (`TooltipBox`, `PlainTooltip`) | from the BOM above | Apache-2.0 | Taskbar/tray/clock tooltips |
| Compose Foundation `combinedClickable` | from the BOM above | Apache-2.0 | Long-press tooltip anchors |

No new external binaries, no GPL code, no WebView.

### N2 additions (Explorer, Notepad, Settings, Browser)

No new libraries and no new binaries. Explorer is plain `java.io.File` + Compose
Foundation/Material 3, plus `androidx.core.content.ContextCompat` for the runtime
permission check (`READ_EXTERNAL_STORAGE`, `maxSdkVersion 32`). Notepad uses the
Storage Access Framework (`OpenDocument` / `CreateDocument`) and a bundled
windows-1252 codec (`ui/notepad/Windows1252.kt`, written for this project, because
the platform charset registry is not guaranteed to ship cp1252). Settings uses
`SharedPreferences` behind a testable `ShellPreferences` interface; no new libraries.

Browser handling: the shell only fires an `ACTION_VIEW` intent. **No browser engine is
bundled.** Firefox (Mozilla, MPL-2.0) is merely the preferred *target* when the user
already has it installed; its package name appears in `<queries>` so it can be listed
on API 30+. Other package names listed there (Chrome, Brave, Vivaldi, Edge, Opera,
Chromium, Tor Browser, Firefox Focus/Fennec) are used only for detection - no
affiliation or endorsement. Search fallback URL: DuckDuckGo (`https://duckduckgo.com/?q=`),
a plain HTTPS GET, no integration or API key.

## Assets

| Asset | Origin | Licence |
| --- | --- | --- |
| `ic_search` | Material Symbols / Material Design Icons path data | Apache-2.0 |
| Window caption glyphs (`ic_minimize`, `ic_maximize`, `ic_restore`, `ic_close`), tray glyphs (`ic_wifi`, `ic_volume`, `ic_battery`, `ic_chevron_up`, `ic_power`, `ic_person`, `ic_all_apps`) | Original, drawn for this project | Project licence |
| Explorer chrome (`ic_arrow_left/right/up/down`, `ic_refresh`, `ic_view_list`, `ic_view_grid`, `ic_new_folder`, `ic_home`, `ic_desktop`) | Original, drawn for this project | Project licence |
| File-type icons (`ic_file_generic`, `ic_file_text`, `ic_file_app`, `ic_file_shortcut`, `ic_picture`, `ic_archive`, `ic_drive`, `ic_drive_wine`) | Original, drawn for this project | Project licence |
| App tiles (`ic_app_explorer`, `ic_app_notepad`, `ic_app_settings`, `ic_app_terminal`, `ic_app_taskmgr`, `ic_app_container`) | Original, drawn for this project | Project licence |
| `ic_start` (four-petal mark), `ic_launcher_foreground` (bloom) | Original, drawn for this project | Project licence |
| Desktop wallpaper (`BloomWallpaper.kt`) | Original, drawn procedurally at runtime | Project licence |
| Palette, strings, app name | Original — "Windows 11-style", no Microsoft trademarks or artwork | Project licence |

## Planned (not yet in the APK)

proot, Box64, Wine, an Ubuntu 22.04 arm64 rootfs, an X server implementation,
PulseAudio, Liberation/Carlito/Caladea/Noto fonts — licences to be recorded here
when they land (steps R1–R4, D1–D2, P1). GPL/LGPL components will be discussed
before being added.
