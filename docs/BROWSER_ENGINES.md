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
| ~~Android browser via intent~~ | ~~`ACTION_VIEW` out of the shell~~ | Mobile feature set only | **REMOVED (owner decision, 2026-10-14)**: detection, intents and the `<queries>` manifest block are gone - a not-ready engine is blocked and named, never rerouted off-device |

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

- `BrowserEngine` sealed model: **`DesktopBrowser` only** - the `AndroidBrowser` and
  `SystemDefault` variants were deleted together with `Browsers.kt` (PackageManager
  detection, icon loading, `ACTION_VIEW` intents, the package cache) and the
  manifest's `<queries>` block. What survives of that file is the pure `Urls`
  parser, now `ui/browser/Urls.kt`.
- `DesktopBrowserCatalogue`: the four engines above, each with detail text, licence and
  the roadmap step that unlocks it. `available == false` until the runtime reports ready
  (R4 self-test is the gate).
- `EngineResolver`: chosen engine when catalogued, else `DEFAULT` (Firefox ESR).
  Readiness gates the LAUNCH (`BrowserState.engineBlocked` + `blockingNote`), never
  the choice - the old silent Android fallback is gone by design. A stale stored
  `android:...` id resolves to the default instead of crashing.
- Browser window: the desktop catalogue (selectable, labelled with the exact missing
  step); picking one persists `shell.browserEngine`. Open launches inside the
  container or names the blocker in a toast - it never leaves the emulator.
- Settings ▸ Apps lists the same catalogue (no installed-package rows anymore);
  Task Manager can end a browser window like any other.

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
   `proot -r … --link2symlink --kill-on-exit -b <profile>:/root/.mozilla
   -b <x11 dir>:/tmp/.X11-unix -w /root -- /opt/firefox-esr/firefox -P shell
   <url>` — **no box64** (native arm64), an explicit `FirefoxEnvironment`
   (`DISPLAY=:100`, `MOZ_ENABLE_WAYLAND=0`, the `MOZ_DISABLE_*_SANDBOX` switches proot
   deployments need, sorted for Diagnostics diffs, plus the `PROOT_LOADER*` paths).
   The X server is ensured first (`XServer.ensureRunning`), the foreground service
   starts on success, output streams into the shared log, and every refusal names its
   reason (`BINARIES_NOT_READY`, `ROOTFS_MISSING`, …).
4. **UI**: a ready engine turns the Open button into a desktop launch — the URL from
   the address bar goes to the guest, the result is toasted and shown in the window,
   and the browser's window appears directly on the desktop (the desktop layer IS
   the X screen - no viewer window). An
   unready choice keeps the old fallback (Android Firefox / system default), exactly
   as `RoadmapGatingTest` pins it.
5. **Profile**: persistent at `<files>/browser/firefox-profile`, bound over
   `/root/.mozilla` — cookies, extensions and downloads survive app restarts.
6. **First-load speed + quiet background (Goal 2 step 2)**: `FirefoxPolicies`
   materialises three files idempotently at session build
   (`RuntimeHolder.firefoxRequest`): `<rootfs>/opt/firefox-esr/distribution/
   policies.json` — enforced, so the UI cannot re-enable them — turning off
   telemetry, studies/normandy, app auto-update (ManualAppUpdateOnly), Pocket,
   the whole safe-browsing family *including its remote lookups* (on a fresh
   profile each of those waits on a network round trip before the first paint —
   the biggest first-load stallers), first-run/welcome pages and the
   default-browser check, while `NetworkPrediction` keeps DNS/link prefetch ON
   for speed; the profile's `firefox/shell/user.js` seeding the same prefs for
   the paths the policy engine does not cover; and `firefox/profiles.ini` so
   `-P shell` resolves deterministically instead of racing first-run profile
   creation. Every key is pinned by `FirefoxPoliciesTest` (a malformed
   policies.json is silently ignored by Firefox, so the exact text is the
   contract). A failed materialisation degrades to a slower first run, named
   in the shared log — it never blocks the launch. DNS/SSL need no extra step:
   the provisioned `ca-certificates` + the written-and-bound `resolv.conf` are
   the DNS/SSL path. The desktop's session pill shows the launch instead of looking
   hung: `DesktopBrowserLauncher` publishes a `LaunchPhase`
   (STARTING/RUNNING/FAILED) that the window renders as a spinner plus
   "first run builds the fontconfig cache — up to a minute" until the first
   guest X connection flips it to RUNNING; a refusal replaces it with its
   named reason.
7. **The session is a desktop, not a bare window (approach change, 2026-10-13)**:
   the X server runs PASS-THROUGH - the shell never claims SubstructureRedirect
   (the claim is exclusive and would lock a guest window manager out) - and the
   session script starts **openbox** before Firefox, waiting up to ~5 s for its
   `_NET_SUPPORTING_WM_CHECK` announcement (bounded: a dead WM must never hang
   the launch). Openbox frames, places and focuses Firefox like the real EWMH
   WM it is (`_NET_WM_SYNC_REQUEST` rides the D1f SYNC counters), and the whole
   desktop renders inside the single desktop surface. URL handoffs skip the
   WM start - the session's openbox is already up. Two more first-load fixes
   ride along: `MOZ_FORCE_DISABLE_E10S=1` collapses Firefox to one process
   (proot ptraces every fork; the multi-process model was most of the stall,
   and a Firefox that no longer honours the switch ignores it), and Provision
   runs `fc-cache -f` so the fontconfig cache is built at provision time
   instead of during the first paint. Without openbox provisioned the session
   still runs - windows map undecorated - and readiness names the gap.

Honest limits while gated (each named by the readiness message, nothing silent):

- The rootfs must carry the GTK3/dbus-glib/ALSA stack Firefox links against; the bare
  Ubuntu base does not. The **Provision** action installs it with apt inside proot
  (pinned package list, `--no-install-recommends`, full log in Terminal ▸ log); this
  step is unpinned-by-hash by design (distro-side dependency resolution, like every
  proot-distro tool) and needs network on the device. The command runs apt as proot's
  fake root (`-0` — without it dpkg refuses outright; and note this proot fork has
  **no `--` end-of-options marker** — the guest command follows the last option
  directly, see `docs/NATIVE_RUNTIME.md`) with `APT::Sandbox::User=root`
  (apt's `_apt` fetch user can write nowhere under fake-root ownership) and dpkg's
  `--force-unsafe-io`/`--force-confold`, under `PROOT_NO_SECCOMP=1`; `/etc/resolv.conf`
  is written into the rootfs as a real file **and** bind-mounted over (imported images
  may ship systemd-resolved's dangling symlink, which no bind can fix). The pinned
  ubuntu-base already points apt at `http://ports.ubuntu.com/ubuntu-ports` (correct for
  arm64, and plain http, so it works before `ca-certificates` exists), and
  `apt-get update` runs first. The pinned list is verified against the jammy arm64
  index and pinned by a test: one wrong name (`libdbus-glib-1-2-2`, an analogy with
  `libdbus-1-3` that does not exist - the real package is `libdbus-glib-1-2`) failed
  the whole install with exit 100 on a real device. The pinned list also installs `x11-apps`,
  `x11-utils` (xeyes/xclock/xdpyinfo) and `libgtk-3-bin` (`gtk-query-settings`),
  which feed Diagnostics' one-tap **X self-test** - proof of X11 contact before
  Firefox, and proof that **GTK3 itself can open a display**, which is the
  handshake Firefox actually depends on (see `docs/X_SERVER.md`). A failure reports the exit code and the last 30 apt
  output lines in the Provision card and the shared log — no more bare "apt-get failed".
  Before any of that runs, the **Provision doctor** ("Run doctor" chip on the card, and
  the automatic pre-flight inside Provision) checks every prerequisite and names the
  first blocking failure in plain language: proot set pinned+executable, rootfs
  extracted, rootfs is really arm64 (ELF `e_machine`, catches amd64 SAF imports),
  sources.list serves `ports.ubuntu.com`, guest resolv.conf state, interrupted-dpkg state
  (a killed earlier run - provision repairs it with `dpkg --configure -a` first), ≥400 MB
  free, writable host TMPDIR — plus a non-blocking https probe of ports.ubuntu.com (host
  TLS and the guest's http path can disagree, so only apt's own output decides the
  in-jail route).
  Two environment facts are device-proven and pinned by tests: the guest `PATH` must be
  the root's (`/usr/local/sbin:…:/sbin:/bin`), because dpkg aborts configuration with
  *"error: 2 expected programs not found in PATH"* unless `ldconfig` and
  `start-stop-daemon` (both in `/sbin`) are reachable; and the vendored fork's temp module
  reads **`PROOT_TMP_DIR`**, not `TMPDIR` (`src/path/temp.c`), falling back to a host
  `/tmp` Android does not have - hence the "can't canonicalize /tmp/" warnings until it
  is set. The doctor's smoke test gained a fourth step for exactly this class
  (`command -v ldconfig && command -v start-stop-daemon` inside the jail), so a broken
  guest PATH is named in seconds instead of as an exit-100 apt failure minutes later.
  The doctor's last row is not a file check: it **really execs proot** with three trivial
  guest commands in the exact jail Provision uses (`-0`, the `/proc`+`/sys`+`/dev`+
  resolv.conf binds, short options, `PROOT_NO_SECCOMP=1`) - `/bin/true` (can proot enter
  the jail and exec at all), `/usr/bin/id` (does `-0`'s credential faking really give the
  guest uid 0, without which dpkg refuses outright) and `/usr/bin/apt-get --version`
  (does the program Provision calls actually run). Steps run in order and stop at the
  first failure; the row then names the cause in plain language - cannot be started
  (exit 126/EACCES = the +x bit), `Exec format error` (wrong-architecture rootfs),
  exit 127/not found (incomplete rootfs), ptrace/seccomp refused, hung (timeout), or an
  answer that is not what apt needs - **and prints the exact command line**, so it can be
  re-run from Terminal. This exists because a green file-check report proves nothing
  about proot itself: the vendored termux/proot rejects a detached `--rootfs <path>`, its
  seccomp filter misbehaves on several Android kernels, and both used to surface only as
  a bare "apt-get failed" minutes into a provision. When an earlier prerequisite already
  failed the row reports *not run* (with the reason) instead of spawning something that
  cannot work. The smoke runs through the same injectable `ProcessRunner` seam as apt, so
  the whole doctor stays unit-tested without spawning anything.
- One session at a time. A second URL no longer dies on `ALREADY_RUNNING`: the
  session starts a private `dbus-daemon --session` at a **fixed** guest address
  (`unix:path=/tmp/shell-dbus-session`, `dbus` + `dbus-x11` are in the pinned
  provisioning list) and exports `DBUS_SESSION_BUS_ADDRESS`, so handing over a URL
  runs the same wrapped command as a short-lived sibling process - firefox's own
  remote client finds the running instance over that bus and forwards the URL,
  then exits. Without the `dbus` packages the prelude degrades to "no bus": the
  browser still starts, only the handoff is unavailable, and the handoff process's
  exit code plus output land in Terminal ▸ log rather than being claimed as success.
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

## Hardened launch plan (2026-10-14 pass)

The crash/white-screen class of failures this document used to leave open now has
named fixes, all implemented and unit-tested (details + rationale per flag:
`docs/WINE_RUNTIME.md` §4):

- **Firefox ESR (native arm64)**: `/dev/shm` bind (Android's `/dev` has none),
  `PROOT_NO_SECCOMP=1` (proot's seccomp filter misbehaves on Android kernels),
  software-rendering prefs + `LIBGL_ALWAYS_SOFTWARE`/`GALLIUM_DRIVER=llvmpipe`
  (the embedded display has no GLX/DRI — GPU probing is how a first paint turns
  white), sandbox-off env and E10S-off as before, resolv.conf DNS as before.
- **Chromium/Electron under Wine** (the BYO-installer engines, Edge step 4):
  `runtime/WineBrowserLaunch.kt` carries the hardened argv
  (`--no-sandbox --disable-setuid-sandbox --disable-gpu --disable-gpu-compositing
  --disable-dev-shm-usage --disable-features=Vulkan --no-first-run
  --no-default-browser-check`), the `CHROME_DEVEL_SANDBOX` env additions and a
  complete `RuntimeRequest` factory bound to the container's isolated prefix and
  `DISPLAY=:100`. Installing the browser .exe itself still has no UI — the
  catalogue rows stay honestly gated.
