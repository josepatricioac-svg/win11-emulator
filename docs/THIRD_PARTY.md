# Third-party components and licences

Updated at every step. Nothing here is a Microsoft asset.

## N0 — build tooling and libraries

| Component | Version | Licence | Used for |
| --- | --- | --- | --- |
| Android Gradle Plugin | 8.9.1 | Apache-2.0 | Build |
| Gradle (wrapper) | 8.11.1 | Apache-2.0 | Build; `gradle/wrapper/gradle-wrapper.jar` is the upstream jar from the `gradle/gradle` v8.11.1 tag (SHA-256 verified against services.gradle.org) |
| Kotlin + Compose compiler plugin | 2.1.10 | Apache-2.0 | Language |
| AndroidX Compose BOM | 2025.02.00 | Apache-2.0 | UI (ui, ui-graphics, material3, ui-tooling) |
| AndroidX core-ktx / activity-compose / lifecycle-runtime-ktx | 1.15.0 / 1.10.1 / 2.8.7 | Apache-2.0 | Activity, edge-to-edge, lifecycle |
| JUnit 4 | 4.13.2 | EPL-1.0 | Unit tests (test scope only) |

## Assets

| Asset | Origin | Licence |
| --- | --- | --- |
| Taskbar glyphs (`ic_search`, `ic_folder`, `ic_settings`, `ic_terminal`) | Material Symbols / Material Design Icons path data | Apache-2.0 |
| `ic_start` (four-petal mark), `ic_launcher_foreground` (bloom) | Original, drawn for this project | Project licence |
| Desktop wallpaper (`BloomWallpaper.kt`) | Original, drawn procedurally at runtime | Project licence |
| Palette, strings, app name | Original — "Windows 11-style", no Microsoft trademarks or artwork | Project licence |

## Planned (not yet in the APK)

proot, Box64, Wine, an Ubuntu 22.04 arm64 rootfs, an X server implementation,
PulseAudio, Liberation/Carlito/Caladea/Noto fonts — licences to be recorded here
when they land (steps R1–R4, D1–D2, P1). GPL/LGPL components will be discussed
before being added.
