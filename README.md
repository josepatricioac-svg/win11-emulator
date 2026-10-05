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
| N2 | Apps: Explorer, Notepad, Settings, Task Manager, Terminal, Container Manager | next |
| R1–R4 | proot/box64 in `jniLibs`, ContainerService, rootfs download, self-test | — |
| D1–D2 | In-app X server (fullscreen surface, then one surface per top-level window) | — |
| P1–P2 | Clipboard, shortcuts, print-to-PDF, audio, fonts, SAF, Open-with, presets, backups | — |

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
  ui/taskbar/TaskbarParts.kt   button/indicator, tooltips, tray, clock
  ui/start/StartMenuPanel.kt   scrim + animated Start menu (search, pinned, all apps, recommended)
  ui/window/WindowLayer.kt     z-ordered rendering + snap preview overlay
  ui/window/WindowFrame.kt     frame, caption, drag-to-move/snap, 8 resize handles
  ui/window/WindowControlButton.kt  minimize / maximize-restore / close
  ui/window/WindowContent.kt   N1 window bodies (Notepad edits text, others are placeholders)
```

Unit tests (`testDebugUnitTest`, plain JVM, no Robolectric): snap zones, maximize/restore,
halves snapping, edge/corner resize with minimums, on-screen clamping, z-order/focus,
taskbar toggle open-minimize-restore.

See `docs/THIRD_PARTY.md` for third-party components and licences.
