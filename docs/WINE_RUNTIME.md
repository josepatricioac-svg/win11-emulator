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
rootfs payload                               <rootfs>/usr/bin/wine64
        │  WinePrefixManager.create  (wineboot --init && wineserver -w)
        ▼
ONE isolated prefix per container            <files>/containers/<id>/prefix
        │  ContainerLauncher (validate → proot → box64 → wine64 …)
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
  manifest pins were taken from the jammy `Packages` index, so apt fetches
  exactly those versions; the difference is only the offline guarantee.
- Runs in the shared fake-root proot prefix (`-0`, short options, no `--` — the
  vendored fork's argument rules), with the doctor's pre-flight (host checks +
  real proot smoke test) before apt is attempted, `PROOT_NO_SECCOMP=1`,
  `PROOT_TMP_DIR`, resolv.conf written **and** bind-mounted.
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
prefix, box64 auto-inserted by the launcher, DNS + shm binds). This is the
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

1. Install the CI debug APK (arm64, Android 9+). Cold start: the X Desktop
   session window opens **maximized** on the shell's desktop - one window, the
   (empty) display inside, "Waiting for clients". Close it → the shell. Toggle:
   Settings ▸ Containers ▸ *Boot into X Desktop session*.
2. Settings ▸ Storage ▸ Runtime download: ROOTFS + FIREFOX_ESR + both WINE
   debs → extract → Provision (browser stack).
3. Container Manager: **Install Wine payload** → expect "Wine payload ready at
   …/usr/bin/wine64" (Terminal ▸ log carries the dpkg/apt lines).
4. Container Manager ▸ create container "Office" ▸ **Initialize prefix** →
   expect state chip **Ready** (first run: wineboot on arm64-native Wine, tens
   of seconds). Diagnostics ▸ Run self-test: rows 2/3 (`wine64 --version`,
   `wine64 cmd /c echo hello`) must now PASS — they were SKIPPED before.
5. Start the container → `wine64 cmd` window on :100; `echo hello` inside.
6. Browser ▸ Firefox ESR ▸ open example.com, then a JS-heavy site: no white
   window (software compositing), touch scroll, soft keyboard, clipboard both
   ways.
7. Kill the app from Recents mid-session, relaunch: Container Manager ▸ the
   prefix row may note a stale `.update-lock` → **Repair** → Ready. Start works
   again — this is the corruption round-trip.
8. Anything failing: Terminal ▸ log + the protocol-trace lines + the row's
   exact command, as always.
