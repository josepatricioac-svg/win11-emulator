# Wine runtime: payload, prefixes, browser hardening (Phase 2)

Companion to `docs/NATIVE_RUNTIME.md` (proot/box64 provenance) and
`docs/X_SERVER.md` (the embedded display + lifecycle). Everything here is
implemented in `runtime/` as pure logic behind the `ProcessRunner`/worker
seams, so it is unit-tested off-device; what needs the owner's arm64 device is
called out as the **device playbook** at the bottom, and nothing claims to work
that has not been certified there.

## 1. The chain, end to end

```
pinned Wine 9.0 debs (R3 download)          <files>/downloads/wine*.deb
        │  WineProvisioner  (dpkg -i + apt -f, or apt fallback; disk-verified)
        ▼
rootfs payload                               <rootfs>/usr/lib/wine/wine64  (the pinned debs
        │  WinePrefixManager.create           install NO /usr/bin entry; the alternatives
        │  (wineboot --init && wineserver -w)  cover wine/wineboot/wineserver, not wine64)
        ▼
ONE isolated prefix per container            <files>/containers/<id>/prefix
        │  ContainerLauncher (validate → proot → [box64 ONLY for an x86_64
        │   payload - ELF-header rule] → the resolved wine loader …)
        ▼
guest windows on the embedded display        DISPLAY=:100  (x11/, in-process)
```

Each arrow refuses with the *named* missing link instead of guessing: no debs →
apt fallback (same pinned versions from `ports.ubuntu.com`); no payload →
`Initialize prefix` refuses and points at *Install Wine payload*; no prefix →
Start refuses with `PREFIX_MISSING` and the whole chain quoted.

## 2. WineProvisioner (`runtime/WineProvisioner.kt`)

- **Preferred path (fail-closed core)**: when both R3-pinned debs
  (`wine64_9.0~repack-4build3_arm64.deb`, `wine_9.0~repack-4build3_all.deb`,
  SHA-256-verified by the downloader before promotion) sit in
  `<files>/downloads`, they are bind-mounted at `/mnt/wine-debs` and installed
  with `dpkg -i /mnt/wine-debs/*.deb`, then `apt-get -f install -y` resolves the
  dependency closure from the archive. `dpkg --configure -a` runs first: a
  killed earlier provisioning must not wedge dpkg (same self-heal as the browser
  provision).
- **Fallback**: `apt-get install -y --no-install-recommends wine64 wine` — the
  manifest pins were taken from the noble `Packages` index, so apt fetches
  exactly those versions; the difference is only the offline guarantee.
- Runs in the shared fake-root proot prefix (`-0`, short options, no `--` — the
  vendored fork's argument rules), with the doctor's pre-flight (host checks +
  real proot smoke test) before apt is attempted, `PROOT_NO_SECCOMP=1`,
  `PROOT_TMP_DIR`, resolv.conf written **and** bind-mounted.
- **Stale-lock recovery** (`runtime/PackageLockRecovery.kt`, shared with the
  browser provision): an `apt-get`/`dpkg` orphaned by a SIGKILLed earlier proot
  (`--kill-on-exit` never ran) holds the dpkg flocks and used to wedge every
  later install with "Could not get lock … held by process N (apt-get)". Before
  the jail spawns, the host `/proc` is scanned for same-uid processes whose
  FIRST argv token is `apt-get`/`apt`/`dpkg` (substring matching would kill the
  carrier shell/proot - pinned by tests), each hit is SIGKILLed and logged, and
  `dpkg --configure -a` heals the rest. A legitimately concurrent install is
  reclaimed too: one provisioning run at a time, newest wins, everything named
  in Terminal ▸ log.
- **The disk is the verdict**: exit 0 without `WinePayload.launcherIn(rootfs)`
  finding a launcher is reported as FAILED with the paths it searched.
- UI: Container Manager header ▸ *Install Wine payload* (visible exactly when
  binaries are ready and the payload is missing); the foreground service is
  held for the duration and released after.

## 3. WinePrefixManager (`runtime/WinePrefixManager.kt`) — create/list/validate/repair/delete

**Isolation (why prefixes cannot overlap or touch `~/.wine`):**
every container owns `<files>/containers/<id>/prefix` (minted by
`ContainerStore.create`, duplicate ids refused), and every guest launch binds
exactly one prefix at the fixed guest path `/root/.wine`
(`ProotCommand.GUEST_PREFIX`) with `WINEPREFIX=/root/.wine` in the fully
explicit environment. The Android host's home is never mounted into the jail at
all. `WINEARCH` comes from the container arch, `WINEDLLOVERRIDES=mscoree,mshtml=`
(no silent Gecko/Mono downloads), `DISPLAY=:100` (the embedded server) —
identical at init time and at run time, because both use
`WineEnvironment.build`.

**Create**: `wineboot --init && wineserver -w` in the usual jail. `wineserver -w`
is not decoration: wineserver writes the registry files when it exits, so the
wait is what makes "prefix exists on disk" true. Success = the post-run
**inspection verdict is READY**, not "exit 0".

**Validate (`inspect`)** — the four honest states:

| State | Meaning |
| --- | --- |
| MISSING | no directory at all |
| UNINITIALISED | directory exists, wineboot never ran (no markers) |
| CORRUPT | partial markers, unreadable registry, registry without a Wine header (`# WINE REGISTRY Version…`/`REGEDIT4`), or `#arch=` ≠ container arch |
| READY | markers present, headers valid, `drive_c/` exists (stale `.update-lock` reported as a note) |

**Corruption model & repair**: wineserver flushes the prefix registry when it
exits *gracefully*; a SIGKILLed one (process death, `--kill-on-exit`, the fast
per-container Stop button) can leave a stale `.update-lock` and lose the last
registry writes. Therefore:

- `SessionLifecycle.shutdown` (app exit) runs a **bounded `wineserver -k`** per
  running Wine session *before* destroying anything — the "wineserver -k on app
  exit" contract;
- **Repair** = graceful kill → remove stale lock → restore rwx on the prefix
  dirs → re-run `wineboot --init` (wineboot tops up missing registry content in
  place);
- **Delete** = stop the running container first (the UI never deletes files out
  from under a live guest), then remove the tree.

**`WINEARCH=win32` is deliberately not offered**: arm64 Ubuntu ships no 32-bit
Wine loader, so a win32 prefix cannot be created honestly here; both container
arches are win64 prefixes (32-bit *apps* run inside via WoW64 + box64), and
`inspect` flags a registry whose `#arch=win32` as a mismatch instead of
launching into it.

UI: Container Manager ▸ each card ▸ *Wine prefix (isolated)* row — state chip +
verdict detail + *Initialize prefix* / *Repair*, disabled while an operation
runs; all of it on guarded workers (`runtime/WinePrefixOps.kt`), results through
the status line, output in Terminal ▸ log.

## 4. Browser hardening (Phase 2A)

**Firefox ESR (native arm64, the shipping desktop engine):**
- `/dev/shm`: Android's `/dev` has no shm entry, so
  `RuntimePaths.ensureShmDir` creates `<files>/tmp/shm` (mode 1777 via
  `Os.chmod`, best effort) and **every** session binds it at the guest's
  `/dev/shm` — Gecko/Chromium shm transports and Wine's esync all ask for it.
- `PROOT_NO_SECCOMP=1` in both session environments (Firefox + Wine): proot's
  seccomp filter misbehaves on several Android kernels; provisioning already ran
  with the documented kill switch, sessions must too — this is the
  "seccomp/bpf blocking the browser" fix on the host side of the jail.
- Sandbox-off env (`MOZ_DISABLE_*_SANDBOX`, `MOZ_FORCE_DISABLE_E10S`) was
  already there — nested namespaces/seccomp cannot work inside proot.
- **White-screen guard**: the embedded X server has no GLX/DRI/Vulkan (software
  renderer, by design), so `user.js` pins `layers.acceleration.disabled=true`,
  `gfx.webrender.all=false`, `gfx.webrender.software=false`,
  `media.ffmpeg.vaapi.enabled=false`, and the env pins
  `LIBGL_ALWAYS_SOFTWARE=1` + `GALLIUM_DRIVER=llvmpipe`. GPU passthrough is
  not "broken" - it is impossible on this display, and the honest alternative
  (software rendering) is what is now enforced instead of letting Gecko probe
  GL and stall on a white window.
- DNS: resolv.conf written + bind-mounted + installed into the rootfs (a
  dangling systemd-resolved symlink in imported images cannot be bind-fixed).
- Launch is always *inside* the embedded display: `DesktopBrowserLauncher`
  ensures the X server first, then spawns through R2's launcher on `:100`.

**Chromium/Electron/Firefox under Wine** (`runtime/WineBrowserLaunch.kt`, pure +
unit-tested): the hardened argv (`--no-sandbox`, `--disable-setuid-sandbox`,
`--disable-gpu`, `--disable-gpu-compositing`, `--disable-dev-shm-usage`,
`--disable-features=Vulkan`, `--no-first-run`, `--no-default-browser-check`),
each flag mapped to its named failure mode in the KDoc; `environmentAdditions()`
(CHROME_DEVEL_SANDBOX pointed at a path that must not exist); and
`request(...)` composing the complete `RuntimeRequest` (container's isolated
prefix, the symbolic `wine64` loader resolved at spawn - box64 only for an
x86_64 payload, per the ELF-header rule - , DNS + shm binds). This is the
launch plan the BYO-installer step (PIPELINE_PLAN step 4, Edge/Chromium)
executes; installing the browser .exe into a prefix has no UI yet, and nothing
pretends otherwise.

## 5. Tests

Off-Gradle harness (kotlinc 2.1.10 + android.jar + desktop Compose jars +
stubs, the repo's documented approach — Gradle OOMs the 1 GB dev sandbox):
**681 tests green** on this pass (624 baseline + 57 new:
`SessionLifecycleTest`, `WinePrefixManagerTest`, `WineProvisionerTest`,
`WineBrowserLaunchTest`, `ContainerManagerPrefixOpsTest`, plus the deliberate
pin updates in `ContainerLauncherTest` (the refusal now names *Initialize
prefix* instead of a roadmap step), `FirefoxPoliciesTest` (render-guard prefs),
`FirefoxSessionTest` (shm bind, seccomp/SW-render env), `ContainerTest`
(`PROOT_NO_SECCOMP`, arch-derived `WINEARCH`)). GitHub Actions `build-apk`
remains the authoritative gate.

## 6. Device playbook (owner; logs feed back like D1f/D1g did)

1. Install the CI debug APK (arm64, Android 9+). Cold start: the Win11 shell
   comes up; the embedded X server is already running underneath (nothing to
   open). Guest apps - step 6 - open as regular shell windows (D2).
2. Settings ▸ Storage ▸ Runtime download: ROOTFS + FIREFOX_ESR + both WINE
   debs → extract → Provision (browser stack).
3. Container Manager: **Install Wine payload** → expect "Wine payload ready at
   …/usr/lib/wine/wine64" (the pinned debs' real loader path - Ubuntu installs
   no /usr/bin entry; Terminal ▸ log carries the dpkg/apt lines).
4. Container Manager ▸ create container "Office" ▸ **Initialize prefix** →
   expect state chip **Ready** (first run: wineboot on arm64-native Wine, tens
   of seconds). Diagnostics ▸ Run self-test: rows 2/3 (`wine64 --version`,
   `wine64 cmd /c echo hello` - both exec'ing the RESOLVED loader path, no
   box64 in front of the native arm64 payload) must now PASS — before the
   payload exists they SKIP with the reason named.
5. Start the container → a `cmd` window on :100 (the launch line in Terminal ▸
   log shows the resolved `/usr/lib/wine/wine64`); `echo hello` inside.
6. Browser ▸ Firefox ESR ▸ open example.com: the session pill shows the launch
   (and, until the first window maps, "N client(s) connected, no window mapped
   yet"), then **Firefox opens as its own shell window** - drag it, resize it
   (the X window resizes with it), minimize, close (WM_DELETE, escalated to
   DestroyWindow after ~2 s if ignored). A JS-heavy site next: no white window
   (software compositing), touch scroll, soft keyboard (tap the window first),
   clipboard both ways. Dropdown menus/completion popups (override-redirect)
   render on the desktop popup layer, aligned to the window.
7. Kill the app from Recents mid-session, relaunch: Container Manager ▸ the
   prefix row may note a stale `.update-lock` → **Repair** → Ready. Start works
   again — this is the corruption round-trip.
8. Anything failing: Terminal ▸ log + the protocol-trace lines + the row's
   exact command, as always.

## 7. The Android phantom-process killer (Android 12+) — known risk, documented on purpose

Android 12 (API 31) added **phantom process monitoring**: child processes of an
app's uid that are not themselves app processes are tracked by
`ActivityManager`, and once their number passes a system cap (stock: 32,
counting the app's own processes) the surplus is **SIGKILLed**; some builds
also kill phantoms for excess CPU. The rule exists to stop runaway process
trees from draining the battery — and a proot emulator is exactly what it
watches for. The app's own foreground-service state lowers the *app* process's
oom_adj but does **not** exempt phantom children.

Why this app is exposed: every guest process is a child of the app uid — the
proot supervisor(s), the guest tree (`bash`, `dbus-daemon`, Firefox or
`wineserver` + one process per Windows app). A multi-process Firefox or a Wine
prefix running several apps can approach the cap on its own; exceeding it
looks, from inside the app, like "the session died" — SIGKILL leaves no exit
path the app can log beyond the child disappearing.

What the app already does to keep the count low (each is a real mitigation,
not a promise):

- `MOZ_FORCE_DISABLE_E10S=1` — Firefox ESR collapses to one process instead of
  a parent + content + GPU + socket tree (this was a first-load speed fix; it
  is also the single biggest phantom-count saving).
- One guest session at a time (the browser session refuses `ALREADY_RUNNING`
  rather than stacking a second Firefox tree).
- proot `--kill-on-exit` + the `SessionLifecycle` stop order (`wineserver -k`
  → guests → X server) — no orphans linger to consume the phantom budget
  after a session ends, and Android reaps the whole cgroup if the app process
  itself dies.
- Phase 4 plans (idle SIGSTOP of minimised/background containers, lazy Wine
  start, configurable idle shutdown) shrink the resident count further.

Optional device-side toggles for users who hit the cap anyway — these need
`adb` **once, by the user's own choice**; the app itself never requires adb
(that project rule is about the app's own operation, and stays):

```
adb shell settings put global settings_enable_monitor_phantom_procs false
adb shell device_config put activity_manager max_phantom_processes 2147483647
```

Honest caveats: the first disables the monitor persistently (removing a system
safety net against runaway children — the user's trade-off to make); the
second may need re-applying after a reboot on some builds; OEM battery layers
(Samsung "sleeping apps", MIUI battery saver, …) kill independently of both
and need their own per-app exemption in the system settings. Symptom mapping
for a device report: guest windows vanish with no app-level error and
Terminal ▸ log simply stops mid-stream ⇒ suspect the phantom killer (or the
OOM reaper — `logcat -b events | grep am_kill` distinguishes them for a user
who has adb).
