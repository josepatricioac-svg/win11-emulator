# Browser engines: what "a PC browser on Android" can actually mean

Goal from the product side: a browser with **desktop features** — profiles, extensions,
sidebars (incl. AI/Copilot-style sidebars), devtools, print-to-PDF, real window chrome —
not a mobile browser. Constraint from the project: **no Termux, no root, one APK**, and
the app must own the X server, the processes and the downloads.

## The two families

| Family | How it runs | Desktop features | Verdict for this project |
| --- | --- | --- | --- |
| **Linux arm64 native** (Firefox ESR, Chromium) | `proot` + Ubuntu 22.04 arm64 rootfs → X11 to our own X server | Full: profiles, extensions, sidebars, devtools | **Primary path.** Same machinery as Wine, no emulation, no GPL entanglement |
| **Windows arm64/x64** (Edge arm64, Firefox x64) | Edge arm64: `proot` + Wine (native arm64 PE, no box64).<br>Firefox x64: `proot` + Wine + **box64** | Full, plus vendor AI sidebars | Edge arm64 is the interesting one; Firefox x64 via box64 is the slow fallback |
| Android browser via intent | `ACTION_VIEW` out of the shell | Mobile feature set only | What N2 ships today; stays as the "no runtime installed" fallback |

There is **no Windows/arm64 build of Firefox** from Mozilla, so "Firefox ESR like a PC"
means the **Linux arm64 build** (Ubuntu's `firefox-esr`/`firefox` package or Mozilla's
`.tar.xz`), which is what people run in Termux/proot setups. Edge *does* ship a
**Windows arm64** build, which runs under Wine without x86 translation — that is the
cheapest route to a Copilot-sidebar browser.

## Where each engine lands on the roadmap

| Engine | Needs | Step |
| --- | --- | --- |
| Firefox ESR (Linux arm64) | rootfs download + X server + audio | **R3 + D1**, audio in P1 |
| Chromium (Linux arm64) | same as above | **R3 + D1** |
| Microsoft Edge (Windows arm64) | proot + Wine prefix + per-window X surfaces | **R1 + R2 + R3 + D2** |
| Firefox (Windows x64 via Wine + box64) | everything above, heaviest and least reliable | last |

Nothing is bundled in the APK. Engines are downloaded into the container rootfs with
pinned SHA-256 (fail closed), a free-space check and resumable transfers — that is R3's
contract — and each one gets a licence entry in `THIRD_PARTY.md` when it lands.

## What is implemented today (this step)

- `BrowserEngine` sealed model: `AndroidBrowser` / `DesktopBrowser` / `SystemDefault`.
- `DesktopBrowserCatalogue`: the four engines above, each with detail text, licence and
  the roadmap step that unlocks it. `available == false` until the runtime reports ready
  (R4 self-test is the gate).
- `EngineResolver`: chosen engine if still valid → **Firefox on Android** → system
  default → `SystemDefault`. A `desktop:` choice resolves to a real desktop engine only
  when `desktopEngineReady(id)` is true, otherwise it falls back instead of failing.
- Browser window: Android browsers (live, launchable) plus the desktop catalogue
  (selectable, clearly labelled with what it needs); picking one persists
  `shell.browserEngine`.
- Settings ▸ Apps stores the same preference; Task Manager can end a browser window like
  any other.

## Firefox ESR launch path — fully plumbed (D1c), gated on the R1 binaries

Everything that can exist without the vendored native binaries now exists; the engine
unlocks itself the moment the runtime is complete:

1. **Pinned artifact (R3)**: `RuntimeManifest.FIREFOX_ESR` — Mozilla's official
   self-contained `linux-aarch64` build, 140.17.0esr, SHA-256
   `dd579f48…850084` (the download's SHA-512 was verified against Mozilla's own
   `SHA512SUMS` before the SHA-256 was pinned; retrieved 2026-10-10). It appears in
   Settings ▸ Storage ▸ Runtime download with the usual resume/hash/space/cancel
   contract, and extracts **in-app** (pure-Kotlin TAR + xz-java, `--strip-components=1`)
   to `<rootfs>/opt/firefox-esr` — no proot needed for the unpack, verified against the
   real archive (50 files, launcher executable, libxul.so intact).
2. **Readiness (`DesktopEngineReadiness`)**: a guarded low-priority worker evaluates
   the proot binary set (pinned + exec'able — **vendored 2026-10-10**: proot v5.1.107.96
   + loader + libtalloc; box64 is NOT required for this native-arm64 guest) → rootfs
   extracted → Firefox installed → GTK3 stack provisioned (one-tap **Provision** card in
   Runtime download runs `RootfsProvisioner`: apt-get inside proot), and publishes a
   Compose-state snapshot. The Browser window's catalogue card shows **Ready** or the
   exact missing step; no disk I/O ever runs during composition (crash-safety rules 1–2).
3. **Launch (`FirefoxSession` + `DesktopBrowserLauncher`)**: pure, unit-tested command
   and environment plans. The session runs through R2's `ContainerLauncher` —
   `proot --rootfs … --link2symlink --kill-on-exit --bind <profile>:/root/.mozilla
   --bind <x11 dir>:/tmp/.X11-unix --cwd /root -- /opt/firefox-esr/firefox -P shell
   <url>` — **no box64** (native arm64), an explicit `FirefoxEnvironment`
   (`DISPLAY=:100`, `MOZ_ENABLE_WAYLAND=0`, the `MOZ_DISABLE_*_SANDBOX` switches proot
   deployments need, sorted for Diagnostics diffs, plus the `PROOT_LOADER*` paths).
   The X server is ensured first (`XServer.ensureRunning`), the foreground service
   starts on success, output streams into the shared log, and every refusal names its
   reason (`BINARIES_NOT_READY`, `ROOTFS_MISSING`, …).
4. **UI**: a ready engine turns the Open button into a desktop launch — the URL from
   the address bar goes to the guest, the result is toasted and shown in the window,
   and the X Desktop window opens/focuses so the browser is actually visible. An
   unready choice keeps the old fallback (Android Firefox / system default), exactly
   as `RoadmapGatingTest` pins it.
5. **Profile**: persistent at `<files>/browser/firefox-profile`, bound over
   `/root/.mozilla` — cookies, extensions and downloads survive app restarts.

Honest limits while gated (each named by the readiness message, nothing silent):

- The rootfs must carry the GTK3/dbus-glib/ALSA stack Firefox links against; the bare
  Ubuntu base does not. The **Provision** action installs it with apt inside proot
  (pinned package list, `--no-install-recommends`, full log in Terminal ▸ log); this
  step is unpinned-by-hash by design (distro-side dependency resolution, like every
  proot-distro tool) and needs network on the device. The command runs apt as proot's
  fake root (`-0` — without it dpkg refuses outright) with `APT::Sandbox::User=root`
  (apt's `_apt` fetch user can write nowhere under fake-root ownership) and dpkg's
  `--force-unsafe-io`/`--force-confold`, under `PROOT_NO_SECCOMP=1`; `/etc/resolv.conf`
  is written into the rootfs as a real file **and** bind-mounted over (imported images
  may ship systemd-resolved's dangling symlink, which no bind can fix). The pinned
  ubuntu-base already points apt at `http://ports.ubuntu.com/ubuntu-ports` (correct for
  arm64, and plain http, so it works before `ca-certificates` exists), and
  `apt-get update` runs first. A failure reports the exit code and the last 30 apt
  output lines in the Provision card and the shared log — no more bare "apt-get failed".
- One session at a time (`ALREADY_RUNNING` while alive); handing a second URL to the
  *running* instance needs a guest D-Bus session bus (P1).
- Audio waits on P1's PulseAudio socket; the env already accepts it.
- The path-form X socket cannot be bound with public Android API
  (`LocalServerSocket` is abstract-namespace only); libX11's path→abstract→TCP
  fallback chain makes this a non-issue for Firefox, and the bound host directory
  keeps `/tmp/.X11-unix` existing inside the guest.

## Licensing / policy notes (read before R3)

- **Firefox / Firefox ESR**: MPL-2.0. Redistribution allowed with source offer; we only
  *download* it from Mozilla/Ubuntu at runtime, so no redistribution duty on the APK.
- **Chromium**: BSD-3-Clause + third-party licences. Same download-at-runtime model.
- **Microsoft Edge**: proprietary. Its licence permits installing on a user's device but
  **not** redistribution. The user must accept at download time; we must not bundle it,
  mirror it, or use Microsoft artwork/names in our UI beyond nominative mention. Needs a
  decision from you before R2/R3 wires it.
- **GPL**: `proot` is GPL-2.0. We invoke it as a separate process (shipped as a
  `lib*.so` in `jniLibs` per R1), we do not link it into our code — flagged for your
  approval as required by the project rules.
- AI sidebars (Copilot et al.) are server-side features of those browsers; they need
  network access from inside the container and the vendor's own sign-in. No Microsoft or
  Mozilla assets are shipped in this APK.

## Open decisions for you

1. ~~Which engine do we make the **default download** in R3~~ — **decided (D1c)**:
   Firefox ESR (Linux arm64), now pinned in the manifest with the full launch path
   plumbed. Smallest risk, best extension story, clean licence.
2. Do we pursue **Edge Windows/arm64** at all, given the proprietary licence and the D2
   dependency? (It is the only route to a Copilot sidebar.)
3. Rootfs flavour for the browser path: the Ubuntu 22.04 rootfs from R3 is enough for
   Firefox ESR once the GTK3/dbus-glib stack is provisioned (same dependency step Wine
   needs, R4/P2); Chromium may want a newer mesa/libGL than the rootfs ships.
