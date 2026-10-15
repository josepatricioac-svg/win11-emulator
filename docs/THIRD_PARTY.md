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

Browser handling: the shell launches desktop engines INSIDE the container only.
Android-browser detection, the `ACTION_VIEW` hand-off and the manifest's `<queries>`
package list were **removed** (owner decision, 2026-10-14) - the shell neither lists
nor fires intents at other apps' browsers, so no third-party package names appear in
the APK at all. **No browser engine is bundled.** Desktop engines (Firefox ESR,
Chromium, Edge arm64) are downloaded into the rootfs with pinned hashes; see
`docs/BROWSER_ENGINES.md` for licences and the redistribution rules. Firefox ESR
(Mozilla, MPL-2.0) is the default engine. Search fallback URL: DuckDuckGo (`https://duckduckgo.com/?q=`),
a plain HTTPS GET, no integration or API key.

### Restyle additions (Fluent tokens)

| Component | Version | Licence | Used for |
| --- | --- | --- | --- |
| Inter (Regular, SemiBold, Bold) | 4.1 (rsms/inter release `v4.1`, zip sha256 `9883fdd4a49d4fb66bd8177ba6625ef9a64aa45899767dde3d36aa425756b11e`) | **SIL OFL 1.1** | The shell's UI face: an open stand-in for Segoe UI Variable (metrically similar humanist sans), bundled as `app/src/main/res/font/inter_{regular,semibold,bold}.ttf`. No Microsoft font files are shipped. |

Per-file SHA-256 (as bundled):

| File | SHA-256 |
| --- | --- |
| `inter_regular.ttf` | `40d692fce188e4471e2b3cba937be967878f631ad3ebbbdcd587687c7ebe0c82` |
| `inter_semibold.ttf` | `78a843fade9d4612a5567302fb595b56976eb5fcebf4fea5a5912d638bafcde3` |
| `inter_bold.ttf` | `288316099b1e0a47a4716d159098005eef7c0066921f34e3200393dbdb01947f` |

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
publishers' own checksum data and **re-verified by downloading each artifact on
2026-10-15** (all four returned HTTP 200 with a byte-exact SHA-256 match):

| Artifact | Source of the hash | SHA-256 | Size |
| --- | --- | --- | --- |
| `ubuntu-base-24.04.5-base-arm64.tar.gz` | `https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.5/release/SHA256SUMS` | `a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2` | 29,936,675 B |
| `wine64_9.0~repack-4build3_arm64.deb` | Ubuntu `noble/universe/binary-arm64` `Packages` index, SHA256 field | `d44c0266f404ad59944ef7245d3162affa6415ee58193c5fa7cdc1aa105a46bd` | 244,164 B |
| `wine_9.0~repack-4build3_all.deb` | same index | `5606f0f5677ac0fb882e3fdc8ce03a60232cdf2930e912ed3e42db2b9e0d5f7f` | 51,966 B |
| `firefox-153.5.0esr.tar.xz` (linux-aarch64) | Mozilla's own `SHA512SUMS` for the 153.5.0esr release verified the download (SHA-512 `8e8635cc…01bc5a54` matched byte-for-byte); the SHA-256 pin was then computed from that verified tarball. Retrieved **2026-10-15** from `https://archive.mozilla.org/pub/firefox/releases/153.5.0esr/linux-aarch64/en-US/` | `6579949c1d2d98f0d045cec780fa24752b57984f6e52d189ccfe003cf97f3d0b` | 74,115,684 B |

**The rootfs and the Wine `.deb`s must be the SAME Ubuntu release.** The rootfs was
22.04.5 (jammy) while both Wine debs above are **noble** builds: `wine64` declares
`Depends: libc6 (>= 2.38)` and jammy ships libc6 2.35, so that dependency could never be
satisfied - `dpkg -i` failed, `apt-get -f install` could not repair it (jammy's own wine64
is 6.0.3, a different package), no Wine payload ever landed, `wineboot` could not run, and
no prefix could be created. That surfaced as a misleading *"Wine prefix not found"*. The
rootfs is now noble 24.04.5 (libc6 2.39), which is the release these debs were built for.
A test (`RuntimeTest`) now pins the pairing so the two cannot drift apart again.

Firefox ESR moved 140.17.0esr -> **153.5.0esr**: 153 is the current ESR branch (per
Mozilla's `product-details` `FIREFOX_ESR`), and 140.17.0esr is the last release of a branch
now past its security-support window. The linux-aarch64 build needs only GLIBC >= 2.28
(highest versioned symbol in `libxul.so`), so it is comfortable on noble's glibc 2.39.

Licences: Ubuntu base rootfs is a collection of packages under their own licences
(mostly GPL-2.0+/LGPL-2.1+/MIT/BSD); Wine is LGPL-2.1+; **Firefox ESR is MPL-2.0**.
They are **downloaded at runtime from the publisher**, never redistributed inside this
APK, so no source-offer duty attaches to the APK itself. The two Wine `.deb`s are the
loader/front-end packages only — the real payload (`libwine`, which carries `wineboot` and
every builtin DLL, plus its library tree) still has to be provisioned inside the rootfs,
which is why they are flagged
`incompleteAlone` in the UI. Mozilla's Firefox tarball is self-contained (bundles its
own libs) but links the rootfs's GTK3/dbus-glib/ALSA stack, which the bare Ubuntu base
does not ship — the readiness check reports that provisioning step explicitly instead
of failing silently.

A download whose SHA-256 does not match the pin is deleted and never unpacked. Extraction
is refused until proot's tar is available (R1 binaries), because unpacking a rootfs needs
`proot --rootfs / -0 tar` for device nodes and root ownership.

### R2 additions (container runtime)

No new libraries: `ContainerService` uses `androidx.core` (`NotificationCompat`,
`ContextCompat.startForegroundService`) which was already a dependency, plus
`ProcessBuilder` from the JDK. No third-party code was copied.

### R1 additions (native runtime)

**Vendored (proot set 2026-10-10, owner-approved for the desktop-browser milestone; box64 2026-10-12 via the `build-native` workflow):**

| File | Upstream | Licence | SHA-256 | Status |
| --- | --- | --- | --- | --- |
| `libproot.so` | termux/proot tag `v5.1.107.96`, commit `a179d3e8a4e045aaa1fb8cc3284f23509d96d353` (source zip pin `75f654fe60dea92dabff2bf083ae8bfe4f91baa6a1a374786a6bf391015eebaa` from Termux's build recipe), built with NDK r27c `aarch64-linux-android28-clang`, patches: 32-bit loader disabled, talloc linked from the Termux build, `-DARG_MAX=131072`, `RUNPATH=$ORIGIN` | **GPL-2.0** | `43e98a71e9c10501b7984f1c6a30637fd57eac6b08627bee7b22420f0a36d957` | vendored; exec'd as a separate process, never linked into app code. Complete corresponding source = the public tag + the patch recipe reproduced in `docs/NATIVE_RUNTIME.md` (GPL-2.0 source availability). |
| `libproot-loader.so` | same build (`src/loader/loader`, statically linked, no interpreter) | **GPL-2.0** | `0738d73bc8e3c0b22f9b59a019a29087195453dcb0b73ced979b00fafe1caefe` | vendored |
| `libtalloc.so` | Termux `libtalloc_2.5.0_aarch64.deb` (deb sha256 `556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7`), talloc 2.5.0 from samba.org; SONAME respelled `libtalloc.so.2`→`libtalloc.so` (same-length byte patch in `.dynstr`) | LGPL-3.0+ | `160d39bc0d7ed946f22310d1d6f20addda21b02b0972dffe929fe49951543941` | vendored; proot's only dynamic dep besides libc/libdl |
| `libbox64.so` | box64 (ptitSeb), tag `v0.4.4`, built by `.github/workflows/build-native.yml` (NDK, `-DANDROID=ON -DARM_DYNAREC=ON -DANDROID_PLATFORM=android-28`, RelWithDebInfo, `llvm-strip`ped), auto-committed 2026-10-12 (`fe5e346`) | MIT | `bec2a61f2c2bee8607d41e6d5b992580ccb9cad878cc6e0a255f539957823710` | vendored; only x86/x64 Wine guests exec it (the native arm64 Firefox path does not) |
| `libproot-loader32.so` | proot | **GPL-2.0** | — | deliberately not built (arm64-only rootfs); optional role |
| Ubuntu 24.04 arm64 rootfs, Wine 9.x, Firefox ESR | R3 runtime download | per-package / LGPL-2.1+ / MPL-2.0 | pinned in `RuntimeManifest` | not bundled in the APK |

**Library addition (R3 extraction):** `org.tukaani:xz:1.10` (xz-java) — **public domain** —
pure-Java XZ/LZMA2 decoder, because the JDK has gzip but no xz and Mozilla ships
`.tar.xz`. No native code, no new repository (Maven Central was already configured).

Binaries must also be pinned by SHA-256 in `app/src/main/assets/native/checksums.txt`
(the four vendored files are). An unpinned or mismatched required
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

proot, Box64, Wine, an Ubuntu 24.04 arm64 rootfs, an X server implementation,
PulseAudio, Liberation/Carlito/Caladea/Noto fonts — licences to be recorded here
when they land (steps R1–R4, D1–D2, P1). GPL/LGPL components will be discussed
before being added.
