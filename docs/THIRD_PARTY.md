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

### N2 additions (Explorer, Notepad, Settings, Browser, Task Manager, Terminal, Container Manager)

No new libraries and no new binaries. Explorer is plain `java.io.File` + Compose
Foundation/Material 3, plus `androidx.core.content.ContextCompat` for the runtime
permission check (`READ_EXTERNAL_STORAGE`, `maxSdkVersion 32`). Notepad uses the
Storage Access Framework (`OpenDocument` / `CreateDocument`) and a bundled
windows-1252 codec (`ui/notepad/Windows1252.kt`, written for this project, because
the platform charset registry is not guaranteed to ship cp1252). Settings uses
`SharedPreferences` behind a testable `ShellPreferences` interface; no new libraries.

The Container Manager adds **no dependency**: `containers.list` is a hand-written
`key=value` format (no JSON library), and the Wine/Box64 environment is built from
strings only. No Wine, Box64, proot or rootfs bytes ship in the APK yet - they are
downloaded in R3 with pinned SHA-256 and will be listed here with their licences
(Wine: LGPL-2.1+, Box64: MIT, proot: GPL-2.0 invoked as a separate process, Ubuntu
rootfs: mixed per-package licences). GPL components will be discussed before R1.

The Terminal is a built-in interpreter over `java.io.File` plus `/proc` reads for
`free`/`uname`; no shell binary, no pty library and no third-party code is used yet.
A pty (for R2's proot session) will need a native helper - its licence will be recorded
here before it lands, and GPL options will be discussed first.

Task Manager reads `/proc/stat`, `/proc/self/stat` and `/proc/self/status` and uses
`ActivityManager.MemoryInfo` - no new libraries, no third-party code.

Browser handling: the shell only fires an `ACTION_VIEW` intent. **No browser engine is
bundled.** Desktop engines (Firefox ESR, Chromium, Edge arm64) are modelled and gated but
not shipped; see `docs/BROWSER_ENGINES.md` for licences and the redistribution rules. Firefox (Mozilla, MPL-2.0) is merely the preferred *target* when the user
already has it installed; its package name appears in `<queries>` so it can be listed
on API 30+. Other package names listed there (Chrome, Brave, Vivaldi, Edge, Opera,
Chromium, Tor Browser, Firefox Focus/Fennec) are used only for detection - no
affiliation or endorsement. Search fallback URL: DuckDuckGo (`https://duckduckgo.com/?q=`),
a plain HTTPS GET, no integration or API key.

### UI-test additions (opt-in, `-PuiTests=true`)

| Component | Version | Licence | Used for |
| --- | --- | --- | --- |
| Robolectric | 4.14.1 | MIT | JVM composition of the taskbar in `TaskbarComposeTest` |
| AndroidX Compose `ui-test-junit4` / `ui-test-manifest` | from the BOM | Apache-2.0 | Compose test rule and assertions |
| AndroidX Test core | 1.6.1 | Apache-2.0 | Robolectric test host |

Test-only: none of this ships in the APK.

### D1 additions (X server)

**Written from scratch for this project** against the public X11 protocol specification:
no Xorg, libX11, XSDL, Termux:X11 or other third-party X code was copied, and no
GPL-licensed X server code is involved. `android.net.LocalServerSocket`/`LocalSocket`
(framework) provide the unix-domain transport. The protocol opcode/event numbers follow
the X11 core protocol; the ones the server dispatches are pinned by `XProtocolTest`.

### R3 additions (rootfs download) — pinned artifacts

No new libraries: `HttpURLConnection`, `MessageDigest` and the Storage Access Framework
(`OpenDocument`) only. The manifest pins these artifacts; hashes were read from the
publishers' own checksum data on **2026-10-07**:

| Artifact | Source of the hash | SHA-256 | Size |
| --- | --- | --- | --- |
| `ubuntu-base-22.04.5-base-arm64.tar.gz` | `https://cdimage.ubuntu.com/ubuntu-base/releases/22.04.5/release/SHA256SUMS` | `075d4abd2817a5023ab0a82f5cb314c5ec0aa64a9c0b40fd3154ca3bfdae979f` | 27,671,306 B |
| `wine64_9.0~repack-4build3_arm64.deb` | Ubuntu `noble/universe/binary-arm64` `Packages` index, SHA256 field | `d44c0266f404ad59944ef7245d3162affa6415ee58193c5fa7cdc1aa105a46bd` | 244,164 B |
| `wine_9.0~repack-4build3_all.deb` | same index | `5606f0f5677ac0fb882e3fdc8ce03a60232cdf2930e912ed3e42db2b9e0d5f7f` | 51,966 B |

Licences: Ubuntu base rootfs is a collection of packages under their own licences
(mostly GPL-2.0+/LGPL-2.1+/MIT/BSD); Wine is LGPL-2.1+. They are **downloaded at runtime
from the publisher**, never redistributed inside this APK. The two Wine `.deb`s are the
loader/front-end packages only — the real payload (`wine-development` plus its library
tree) still has to be provisioned inside the rootfs, which is why they are flagged
`incompleteAlone` in the UI.

A download whose SHA-256 does not match the pin is deleted and never unpacked. Extraction
is refused until proot's tar is available (R1 binaries), because unpacking a rootfs needs
`proot --rootfs / -0 tar` for device nodes and root ownership.

### R2 additions (container runtime)

No new libraries: `ContainerService` uses `androidx.core` (`NotificationCompat`,
`ContextCompat.startForegroundService`) which was already a dependency, plus
`ProcessBuilder` from the JDK. No third-party code was copied.

### R1 additions (native runtime)

**No third-party binary is packaged in the APK yet.** `app/src/main/jniLibs/arm64-v8a/`
contains a `README.txt` only; the Kotlin side (inventory, permissions, probes, proot
command line) is written and unit-tested. When binaries are vendored, this table must be
filled in at the same time, with upstream URL, tag/commit and SHA-256:

| File | Upstream | Licence | Status |
| --- | --- | --- | --- |
| `libbox64.so` | box64 (ptitSeb), pinned ref `v0.4.5-1` = commit `e99ca51299ec897e6a96da6d00983d90406a836f` | MIT | not vendored; `.github/workflows/build-native.yml` builds it with `-DANDROID=ON -DWINLATOR_GLIBC=ON` |
| `libproot.so` | proot Android fork | **GPL-2.0** | not vendored; will be exec'd as a separate process, never linked |
| `libproot-loader.so` / `libproot-loader32.so` | proot | **GPL-2.0** | not vendored |
| Ubuntu 22.04 arm64 rootfs, Wine 9.x | R3 download | per-package / LGPL-2.1+ | not downloaded |

Binaries must also be pinned by SHA-256 in `app/src/main/assets/native/checksums.txt`
(template committed, all entries commented out). An unpinned or mismatched required
binary is refused, not executed.

GPL note (rule 4): proot is GPL-2.0. We only `exec()` it, we do not link against it, and
no GPL code is copied into this repository. Redistribution of the binary still requires
the GPL licence text and a source offer, which must accompany the vendored artefacts.

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
