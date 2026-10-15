# Wine pipeline — execution plan (owner-approved 2026-10-13, commits direct to `main`)

Order fixed by the goal: 1 box64 → 2 Firefox ESR → 3 Wine → 4 Edge (optional).
Owner decisions on record: approve-and-go (2026-10-13); commits direct to `main`
(`build-apk` CI gates every push); owner's arm64 device runs the verification
playbooks; the `Win11-mimo-version` UI is adopted as the design language
("restyle steps 3+", scheduled behind the runtime steps).

Every step: exact file list up front, unit tests added, the off-Gradle harness kept
green (561/576 baseline, see `docs/PIPELINE_STATE.md`), GitHub Actions `build-apk`
green, device playbook for the owner. Anything not verifiable in the sandbox is
stated plainly, never implied.

## Step 1 — close out box64 (docs; binary already vendored)

Done in commit(s) accompanying this document:
- `docs/NATIVE_RUNTIME.md`, `README.md`, `docs/THIRD_PARTY.md`: stale "NOT vendored
  yet / pending one dispatch run" rows replaced with the verified facts
  (`fe5e346`, AArch64 ELF, sha256 = pin, CI APK contents verified).
- `gradlew`: re-committed with mode 755 (`git update-index --chmod=+x`), so CI's
  "Make gradlew executable" step is belt-and-braces, not load-bearing.

Owner device check (the step-1 "Done when"): install the CI debug APK →
Start ▸ All apps ▸ Diagnostics ▸ *Re-check binaries* → *Run --version probes* →
**`box64 --version` PASS** (and `proot --version` PASS). On failure: paste the
row's command/exit code/excerpt + Terminal ▸ log.

## Step 2 — Firefox ESR end-to-end

### 2a. First-load speed + honesty

| File | Change |
| --- | --- |
| `runtime/FirefoxPolicies.kt` (new) | Pure generator: `distribution/policies.json` (DisableTelemetry, DisableAppUpdate/ManualUpdate, safe-browsing off, DisablePocket, no default-browser check) + profile `user.js` (toolkit.telemetry.*, datareporting.*, browser.safebrowsing.*, app.update.auto=false, first-run/studies off; DNS+prefetch kept ON for speed) |
| `runtime/FirefoxSession.kt` | Materialise policies+user.js into the persistent profile at session start (idempotent, content-hashed rewrite) |
| `ui/xdesktop/XDesktop.kt` + `runtime/DesktopBrowserLauncher.kt` | Visible loading state in the X Desktop window: "Starting Firefox ESR… (first run builds the font cache, up to ~60 s)" from launch until the first guest window maps; replaced by the refusal reason on failure |
| tests (new/extended) | `runtime/FirefoxPoliciesTest.kt`; `runtime/FirefoxSessionTest.kt` extended for the profile materialisation |

CA certs + DNS are already handled by Provision (ca-certificates pinned,
resolv.conf written + bind-mounted) — re-verified, no change needed.

### 2b. X server: minimum set for real GTK3 clients

Present: RENDER 0.4, BIG-REQUESTS, XInput2 2.2, XTEST. Absent (honestly): XKB,
MIT-SHM, COMPOSITE, XFIXES, DAMAGE, SYNC, RANDR, SHAPE.

| File | Change |
| --- | --- |
| `x11/XFixes.kt` (new) | XFIXES: QueryVersion, SelectEvents (+SelectionNotify/CursorNotify events), cursor-name requests, minimal regions — GDK3 uses it for clipboard-ownership watch and cursor tracking |
| `x11/XSync.kt` (new) | SYNC: QueryVersion, CreateCounter/SetCounter/DestroyCounter (+ Await minimal) — the counter half GDK/Wine use for frame sync |
| MIT-SHM | Deliberately stays absent: bionic has no SysV SHM; advertising it without attachable segments would be a lie. GDK falls back to PutImage over BIG-REQUESTS (implemented). Revisit only from a device trace showing a hard failure |
| XKB | Deferred unless a device trace shows GTK/Firefox dying on it: GDK3 falls back to the core keymap (implemented, evdev-correct). If needed: QueryExtension/UseExtension/GetMap/GetState/SelectEvents minimal |
| `x11/XConnection.kt` | Registry + dispatch for XFIXES/SYNC (opcode/event/error bases after XInput2's) |
| `x11/XFixesTest.kt`, `x11/XSyncTest.kt` (new) | Wire tests per request: framing, replies, event delivery, refusals named in the protocol trace |
| `docs/X_SERVER.md` | D1f section: what/why/limits, version claims |
| COMPOSITE/DAMAGE/RANDR/SHAPE | Left absent on purpose (GTK3 does not require them; a BadRequest kills GTK). Revisit only from device traces |

### 2c. Device verification playbook (owner runs; logs feed back)

1. Install CI debug APK (arm64, Android 9+).
2. Diagnostics: probes PASS → Settings ▸ Storage ▸ Runtime download: ROOTFS +
   FIREFOX_ESR → extract → Provision card: *Run doctor* → *Provision*.
3. Diagnostics ▸ *Run X self-test*: all five rows (esp. `gtk-query-settings` PASS).
4. Browser ▸ Firefox ESR ▸ Open `https://example.com`, then a JS-heavy site;
   touch scroll/tap, soft keyboard (URL bar + page), clipboard both directions.
5. On any failure: Terminal ▸ log + the protocol-trace lines → reprioritises 2b.

Done-when: example.com + a JS-heavy site render in the X Desktop window with
working touch/keyboard/clipboard — certified from the owner's device run.

## Step 3 — Wine payload, prefix, R4 self-test, explorer.exe

| File | Change |
| --- | --- |
| `runtime/download/DebExtractor.kt` (new) | Pure-Kotlin `ar` reader + reuse `TarExtractor` for `data.tar.*`; Zip-slip-safe |
| `runtime/WineProvisioner.kt` (new) | Installs the two pinned debs into the rootfs via proot `dpkg -i` (+ `apt-get -f install` for deps from ports.ubuntu.com) or in-app `DebExtractor` fallback; verifies `WinePayload.launcherIn` after |
| `runtime/WinePayload.kt` | Post-install verification helper |
| `runtime/ProotCommand.kt` / `runtime/ContainerLauncher.kt` | `wineboot` prefix-init step (wait for system.reg/user.reg/drive_c); container Start uses the initialised prefix |
| `ui/settings/RootfsPage.kt` | Wine payload row: provision action + doctor-style failure report (exit code + last dpkg/apt lines) |
| `runtime/SelfTestRunner.kt` | Wire steps 2/3 to the provisioned payload (shape unchanged) |
| tests (new/updated) | `DebExtractorTest`, `WineProvisionerTest`, `SelfTestRunnerTest`, `ContainerLauncherTest` (wineboot step) |
| X server | `explorer.exe /desktop` exposes its own gaps → fix trace-driven (candidates: SHAPE, DAMAGE, SYNC counters, WM hints) with wire tests each |
| docs | `docs/NATIVE_RUNTIME.md` + README status flips |

Decision 3b (recorded honestly, taken when reached): the pinned debs are
arm64-native Wine 9.0 — R4 `cmd /c echo hello` and builtin Notepad run as arm64
PEs without translation; **real x86_64 Windows apps** need either wine's box64
integration or an x86_64 wine payload under box64 (Winlator recipe).

**Part-decided (2026-10-16, owner chose "payload-driven"):** the *invocation*
half is implemented — `WinePayload.guestLauncherPath` resolves the bare
`wine64` (which Ubuntu never puts on PATH) to the provisioned loader's real
guest path, and `ContainerLauncher.needsBox64` / `SelfTestRunner` decide box64
from that loader's ELF `e_machine`: arm64 → native (box64 cannot carry an
aarch64 ELF), x86_64 → box64 prepended automatically. Wine *sessions* are
recognised structurally (`guestPrefix == /root/.wine`) so the `wineserver -k`
sweep no longer depends on the box64 rule. The remaining open half — which
x86_64 Windows-app payload to pin (wine's own box64 integration vs an x86_64
wine payload under box64) — is deferred until x86_64 apps are prioritised;
the day such a payload is provisioned, the ELF rule engages box64 with no
further launcher work.

Done-when: `wine64 cmd /c echo hello` prints "hello" (output asserted, not exit
code); `explorer.exe /desktop` opens on :100; Notepad.exe + one real Windows app
open as shell windows — certified from the owner's device run.

## Step 4 — Edge (optional, last)

User-supplied installer only (SAF import → run under wine inside the container),
never bundled/mirrored; Firefox ESR stays the default desktop browser. Files:
`ui/browser/BrowserEngine.kt` ("bring your own installer" catalogue entry),
settings import path, `docs/BROWSER_ENGINES.md` licence section. No Microsoft
assets in the APK.

## UI adoption workstream (`Win11-mimo-version` design)

Design reference only (React web UI in a WebView; not reusable as code). The repo's
"restyle steps 1–2" (Fluent tokens, Inter, motion curves, mica/acrylic) already began
the adoption. Continues as **restyle step 3+** behind the runtime steps: wallpaper
parity, taskbar (centred pins, widgets chip, tray/clock), start menu (pinned grid +
recommended), window chrome, iconography — each guarded by the existing Compose
tests + CI. Owner may re-prioritise at any checkpoint.

## Global rules

- List files before changing them (tables above are the contract per step).
- "Tests green" = off-Gradle harness (561/576) in-sandbox + GitHub Actions
  `build-apk` as the authoritative gate (sandbox cannot run Gradle at 1 GB RAM).
- Every step ships wire/unit tests; no feature is called done behind a stub.
- Exact commands + real output reported per step; unverifiable items stated plainly.
- Security note: the owner rotates the GitHub PAT that appeared in plaintext chat.
