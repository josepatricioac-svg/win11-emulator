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
| N2 | Apps: Explorer, Notepad, Settings, Browser, Task Manager, Terminal, Container Manager | **done** |
| R1 | proot/box64 exec from `nativeLibraryDir`, inventory/permissions, SHA-256 pins, `--version` probes, proot command builder, Diagnostics window | done — **all four binaries vendored & pinned**: proot + loader + libtalloc (arm64, NDK r27c) and box64 `v0.4.4` (built + auto-committed by the `build-native` workflow, 2026-10-12); provenance in `docs/NATIVE_RUNTIME.md` |
| R2 | `ContainerService` (foreground, Stop all), `proot -> box64 -> wine` launch with the explicit env, per-container status, logs into the shared buffer | done (inert until R1 binaries + R3 rootfs exist) |
| R3 | Rootfs/Wine download: pinned SHA-256 (fail closed), resumable via `Range`, free-space check, progress UI, SAF import | done — **extraction runs in-app** (pure-Kotlin TAR reader + xz-java, verified against the real pinned ubuntu-base and Firefox archives); Firefox ESR artifact pinned from Mozilla |
| R4 | Diagnostics self-test: `box64 --version`, `wine64 --version`, `wine64 cmd /c echo hello`, green/red/skipped with the failing command + log excerpt | done |
| D1 | In-app X server on a private unix socket, fullscreen surface running `explorer.exe /desktop`, touch/mouse/keyboard | **partial** (D1a–D1e done: spec-accurate protocol core, socket, renderer, input, soft-keyboard text injection, focus/grabs/cursors/fonts/colours, XTest, SubstructureRedirect WM, and **RENDER 0.4** — pictures, Composite with the 14 Porter-Duff ops, FillRectangles, the full CompositeGlyphs pipeline, both rasterisers — plus the one-tap **X self-test** (xdpyinfo/xwininfo probes, a **GTK3 display-open probe**, xeyes/xclock survivors) in Diagnostics, and **D1e**: the BIG-REQUESTS + Generic-Event transport and **XInput2 2.2** — the device list, selections, grabs and the key/button/motion/crossing/focus events GTK3 runs on, **D1f**: **XFIXES 5.0** (event-driven selection/cursor watches, real region algebra, picture-clip + input-shape wiring) plus **SYNC 3.1** (INT64 counters, held Awaits, alarms) - what GTK3's clipboard and resize sync watch for - and **D1g**: the pass-through desktop session (parent-relative coordinates via `absoluteOrigin`, mapped-vs-viewable, Expose on becoming viewable, ReparentWindow placement, ancestor-clipped rendering) so a guest WM (openbox now, `explorer.exe /desktop` later) owns the whole desktop inside the surface. 264 x11 wire tests green on a JVM; the R1 binaries are vendored, so the guest launch is a device-provisioning step; see `docs/X_SERVER.md`) |
| P1–P2 | Clipboard, shortcuts, print-to-PDF, audio, fonts, SAF, Open-with, presets, backups | **partial** (P1 clipboard: the X selections ↔ Android text bridge is done and wire-tested — guest copies push out, Android-side changes reclaim PRIMARY/CLIPBOARD; rich targets and the remaining P1/P2 items are not started) |

## Visual language and animation

The shell speaks one token set: `ui/theme/Fluent.kt` (palettes dark + light, radii,
heights, text sizes, the three shadow specs, the 1dp stroke + inset top highlight
every surface carries) and `ui/theme/FluentMotion.kt` (the two curves - decelerate
`cubic-bezier(.16,1,.3,1)` and standard `cubic-bezier(.33,0,.67,1)` - and the two
durations, fast 160 ms and medium 280 ms). The face is **Inter** (SIL OFL 1.1), an
open stand-in for Segoe UI Variable; no Microsoft asset is shipped.

Windows fade in and settle from 94% scale anchored at the caption over the medium
280 ms on the decelerate curve, closing plays the reverse and only then leaves the
window list, minimizing/restoring shrinks and grows in place over the fast 160 ms,
and the snap preview cross-fades on the same fast curve. One frame loop in
`WindowLayer` drives every window - no per-window coroutines - and geometry is still
read only inside `graphicsLayer`/`offset` lambdas. Settings ▸ Personalisation ▸
*Window animations* turns all of it off.

## Crash-safety rules

Three rules came out of a real crash report (Settings ▸ Storage ▸ Download killed the
process and the Settings window could not be reopened):

1. **No `Thread { }` without a catch-all.** A missing `INTERNET` permission made
   `URL.openConnection()` throw `SecurityException` on a bare worker thread, which takes
   the whole process with it. Download/import/extract/remove now run through one guarded
   worker helper, and `RootfsManager.download` catches `Throwable` and turns it into a
   `FAILED` state with the exception name in the message.
2. **No disk I/O during composition.** `RootfsManager` construction used to scan and hash
   archives on the UI thread; it now does nothing until `refreshAsync()` runs on a
   low-priority thread, hashes are cached per file stamp, and `freeBytes()` is cached.
3. **A broken window must not take the shell down.** `WindowContent` renders a failure card
   with Retry/Close inside the window instead of letting an exception escape into the
   Activity, which is what made one broken screen unopenable.

Also fixed: background state writes now go through `Snapshot.withMutableSnapshot` so Compose
actually sees them, and the Container Manager only starts/stops the foreground service when
the set of *running* containers changes (it used to start and immediately stop it on every
button press, which risks a foreground-service start violation).

## X server (D1) - read this before using it

`x11/` contains a from-scratch X11 core: wire marshalling, the connection handshake,
request dispatch, the window/atom/property document, a keyboard map (X keycode = evdev+8),
input translation with correct modifier masks, and a `LocalServerSocket` on the abstract
name `/tmp/.X11-unix/X100` (which proot does not isolate, so a guest `DISPLAY=:100`
reaches it with no bind mount).

**D1c made the server speak to real clients.** The opcode table now matches the X11R6
specification exactly (it was silently wrong from opcode 92 up: a real libX11 client's
`QueryExtension` - opcode 98 - used to hit our `SetCloseDownMode` no-op and the client
would hang waiting for a reply that never came). Added on top: input focus
(SetInputFocus/GetInputFocus with FocusIn/FocusOut and focus-directed keys), real
pointer/keyboard grabs with event redirection (what Wine needs for menus), passive
button/key grabs, WarpPointer, Enter/LeaveNotify, a built-in "fixed" font served through
OpenFont/QueryFont/QueryTextExtents/ListFonts, an rgb.txt colour table
(LookupColor/AllocNamedColor/QueryColors), cursor resources with a software cursor drawn
on the surface, SendEvent delivery, ListProperties/RotateProperties, vector drawing
(PolyLine/PolySegment/PolyPoint/PolyArc/PolyFillArc/FillPoly/ImageText16/PolyText8/16),
a pure-Kotlin rasteriser that makes GetImage return real pixels, the whole
keyboard/pointer/screensaver/host control tail block, and the **XTest extension**
(QueryExtension reports it; FakeInput synthesises key/button/motion events).
The soft keyboard now works: the X Desktop window owns a hidden IME field and commits
become real KeyPress/KeyRelease sequences via `XInput.injectText` (Latin-1 reach;
CJK needs XIM/XKB later). The keymap itself was fixed to the true evdev+8 layout - the
old android+9 letter block collided with the server's own modifiers ('m' *was* Shift_L)
and hardware punctuation landed on letters ('-' typed 'p').

**D1d added the RENDER extension - the piece GTK3 cannot render without.**
`QueryExtension("RENDER")` answers present (opcode 129) at a deliberately conservative
**version 0.4**, so clients only ever send requests that are implemented: pictures with
clip rectangles/masks, `Composite` with the 14 core Porter-Duff ops (repeat, mask and
clip included), `FillRectangles`, and the whole glyph pipeline (`CreateGlyphSet`,
`AddGlyphs` decoding a1/a4/a8 bitmaps, `CompositeGlyphs8/16/32` with pen advances) -
replayed by both rasterisers (the pure-JVM snapshot and the Android Canvas renderer).
Requests beyond 0.4 (AddTraps, CreateSolidFill, gradients, ARGB cursors, transforms)
are refused BadRequest **and named in the protocol trace**, so device logs say exactly
what to implement next. Proof of contact is one tap: **Diagnostics ▸ Run X self-test**
runs `xdpyinfo -ext RENDER` and `xwininfo -root` (instant handshake probes), then
launches `xeyes` (core X11) and `xclock` (Xaw+Xft = the RENDER glyph path) inside
proot - PASS means "stayed connected", FAIL carries the exit code, the client's output
and the connection's trace summary.

**D1e added the transport and the input extension GTK3 runs on.** `BIG-REQUESTS`
(so cairo's window-sized `PutImage` is not truncated at the 64 KiB CARD16 ceiling -
requests are reassembled across socket reads and normalised back to the ordinary
header form, so every handler's absolute payload offsets stay correct) and the
`Generic Event Extension` (`xGenericEvent` framing, which is how an extension event
longer than 32 bytes reaches a client). On top of that, **XInput2 at version 2.2**
(major opcode 131): a static four-device set (master pointer/keyboard plus the slave
pair behind them, button/key/relative-valuator classes), the request set GDK3 uses -
QueryDevice, SelectEvents/GetSelectedEvents, QueryPointer, WarpPointer, ChangeCursor
(what `XIDefineCursor` sends), SetFocus/GetFocus, GrabDevice/Ungrab, PassiveGrab,
client pointer, device properties - and the events it needs: KeyPress/Release,
ButtonPress/Release, Motion, Enter/Leave, FocusIn/Out, delivered as GenericEvents to
*the connection that selected them* and propagated up to the ancestor that selected
the type. Three rules keep it honest: XI2 delivery is independent of the core window
masks (GDK selects none), an XI2 grab installs a core grab with an **empty** core mask
(GDK translates core events through the same XI2 translator, so a permissive mask
would double every click), and the requests we cannot truthfully answer -
`XIChangeHierarchy`, `XIBarrierReleasePointer`, the XI 1.x minors - are refused
`BadRequest` and named in the protocol trace. The claim is 2.2 rather than 2.4 because
we report no touch or gesture devices: touch arrives as single-pointer events, and
real multi-touch (pinch-zoom) is the named next step. The extension registry keeps
canonical names for `ListExtensions` and answers `QueryExtension`
case-insensitively.

**D1f added XFIXES 5.0 and SYNC 3.1 - the two extensions GTK3 keeps asking for
after XI2.** XFIXES (opcode 133, event base 66) makes the clipboard event-driven:
GDK3's `owner-change` tracking rides SelectionNotify watches that fire on every
ownership path - including the shell's own Android-clipboard reclaims - plus
cursor watches/names/images (GetCursorImage rasterises the built-in 16x16 glyph
art), HideCursor honoured by the software cursor, and a real rectangle-set
region algebra (union/intersect/subtract/invert/translate/expand, normalised)
whose two visible wirings are the enforced RENDER picture clip
(SetPictureClipRegion) and hit-testing (SetWindowShapeRegion's Input kind). The
version claim is 5.0, not 6.0, so the pointer-barrier tail stays unreachable
(refused and named in the trace anyway). SYNC (opcode 134) gives GDK3 and Wine
their frame-sync surface: INT64 counters, a *held* Await answered by the
satisfying counter change with the request's own sequence number, the spec's
transition-vs-comparison semantics, relative waits, alarms with deltas and
AlarmNotify events - fences refused and named, because there is no GL path they
could mean anything on. 52 new wire tests pin every reply byte-for-byte.

**D1g made the desktop the guest's, not the shell's** - the device verdict on
the first Firefox attempts (client connected, cursor visible, window never
appeared) forced the model. The shell no longer claims SubstructureRedirect
(the claim is exclusive - it would lock a guest WM out); the session brings its
own window manager (openbox for the browser session, `explorer.exe /desktop`
for Wine later) and the whole desktop renders inside the one X Desktop
surface. Underneath, the server learned the coordinate truth a WM depends on:
child windows are PARENT-RELATIVE, resolved through `XDocument.absoluteOrigin`
in hit-testing, every core/XI2 event coordinate, TranslateCoordinates, warp
bases, confine clamps and the XFIXES region/shape conversions; "mapped" and
"viewable" are distinct states (a child mapped under an unmapped parent is
revealed - with Expose - when the parent maps); a direct MapWindow generates
the Expose that triggers a GTK3 client's first paint; ReparentWindow places
the window at its requested offset inside the new parent (a WM frame puts the
client at border+title) and reports the ConfigureNotify the frame flow waits
on; and the renderer draws each window at its absolute origin, clipped to its
ancestor chain, skipping InputOnly windows. `XChildCoordinatesTest` pins all
of it at wire level.

**The server runs, renders, takes input and passes 264 wire tests (run for real on a
JVM with Kotlin 2.1.10); the first device contacts (Firefox ESR) drove D1f/D1g.** The native binaries (R1)
are vendored now, so the first real guest launch (Firefox ESR, then `explorer.exe
/desktop`) is a provisioning step away - until then `ExplorerSmokeTest` scripts
explorer's exact wire sequence. `XDisplayList` records every drawing request
per drawable, `XCanvasRenderer` composites the window stack into the **Start ▸ X
Desktop** window, raw MotionEvents/key events are translated through `XInput`, and
the session runs pass-through (D1g): the shell does not claim SubstructureRedirect,
so the guest's own WM (openbox today, `explorer.exe /desktop` for Wine) frames and
places its windows inside the surface. See `docs/X_SERVER.md` for the remaining limits
(RENDER served at 0.4; no Composite/XShm/XKB/XIM, fixed font metrics, Latin-1
text injection; XFIXES/SYNC served with their documented limits - stored but
unclipped window shapes, GC clips, no fences).

## What is deliberately not usable yet

Nothing is stubbed by accident. Every app window opens; the parts that need the container
runtime or the X server say exactly which step they wait for, and `RoadmapGatingTest`
fails if that ever changes silently:

| Thing | State today | Unlocked by |
| --- | --- | --- |
| Container Manager (Wine) | Real UI, real persisted containers; `libbox64.so` is now vendored + pinned, so Start validates binaries, rootfs and then the prefix — and refuses with the whole chain named (no Wine payload → no `wineboot` → no prefix) instead of the old "created on first launch" promise nothing kept | Goal 2 step 2: Wine payload decision + `wineboot` |
| Wine prefix, `wineboot`, installing apps | Not created, and nothing pretends otherwise: `ContainerLauncher.validate` refuses with `PREFIX_MISSING`, names `wineboot`, quotes whether the rootfs even has a Wine payload (`WinePayload`), and treats an empty prefix directory as un-initialised rather than launching into it | Goal 2 step 2 (R4) |
| Desktop browsers (Firefox ESR, Edge arm64, Chromium) | Firefox ESR is **fully plumbed**: pinned R3 artifact, readiness checks that name the missing piece, proot launch plan through R2's launcher on the shell's X server, a private D-Bus session so a second URL reaches the *running* instance, and a Provision **doctor** that names the first blocking prerequisite before apt ever runs. Until the runtime exists, selecting it falls back to an Android browser; Edge/Chromium stay listed with their gating steps | **R1 binaries** + R3 rootfs/GUI libs (Firefox ESR), **R1/R2/R3 + D2** (Edge) |
| Terminal `wine`/`box64`/`proot`/`cmd` | Answer "not available yet" and name the step | R1 + R2 |
| Task Manager container rows | Absent (no processes to show) | R2 |
| Diagnostics wine64 self-test | Command lines are built and shown, not executed; the `proot --version` and `box64 --version` probes have real vendored binaries to run | wine provisioning (payload + prefix) |

The native payload is **fully vendored**: `libproot.so` + `libproot-loader.so`
(termux/proot `v5.1.107.96`, GPL-2.0, owner-approved for the browser milestone, exec'd
only as a separate process), `libtalloc.so` (LGPL-3.0+, SONAME-respelled) and
`libbox64.so` (box64 `v0.4.4`, MIT — built by the `build-native` workflow with
`-DANDROID=ON` and `-j2` because the dev sandbox OOMs on box64's `gtkclass.c`, and
auto-committed with its SHA-256 pin on 2026-10-12; only x86/x64 Wine guests exec it).
All four are pinned in `checksums.txt` (fail closed). Provenance and the exact build
recipes: `docs/NATIVE_RUNTIME.md`.

## Performance

The shell is written so that the common interactions recompose as little as possible:

- **Wallpaper**: brushes and petal geometry are built once in `drawWithCache` and reused;
  they are rebuilt only when the surface size changes, not on every frame while a window
  animates above it.
- **Windows**: geometry (`offset`/`size`) and the whole open/close/minimize animation are
  read inside `graphicsLayer`/`offset` lambdas, i.e. in the layout/draw phase. Dragging,
  resizing and the 60 fps fade therefore recompose **no** Compose code at all - the app
  content inside the window is untouched.
- **Taskbar**: each pin is its own composable, so the window-list reads are scoped to that
  pin. Moving a window no longer rebuilds the bar, its tooltips or the clock.
- **First open**: the expensive one-offs are warmed at startup on a low-priority background
  thread - the PackageManager browser query (cached per process), the SHA-256 of the shipped
  binaries (cached per file stamp) and every app's payload factory (class loading, disk
  reads, container list parsing). `Browsers.invalidate()` exists for refresh-after-install.
- **Frame loop**: one `withFrameNanos` loop drives every window animation and only writes
  state while something is actually animating; steady windows cost no writes, so a desktop
  with idle windows does not recompose at 60 fps.
- `WindowState.sizeFor()` gives the layout modifier its size without allocating a
  `WindowBounds` per frame (covered by a test that pins it to `bounds()`).

Debug builds are still JIT-compiled and unoptimised; measure with a release build (P2)
before chasing anything else.

## Self-test (R4)

Start ▸ All apps ▸ **Diagnostics** ▸ *Run self-test* executes the three required checks and
shows one card per step: verdict (**PASS** green / **FAIL** red / **SKIPPED** amber), the
exact command line, exit code, duration, the reason, and the output excerpt in a console
block. A summary card reports `n passed, n failed, n skipped` and refuses to claim
"All green" while anything is skipped. The failing command and its log excerpt are also
written to the shared log (Terminal ▸ `log`).

The rule the tests enforce: a step whose prerequisites are missing is **skipped with the
reason named** (no `libbox64.so`, no `libproot.so`, no rootfs, rootfs not unpacked) and
never counted as a pass — and `wine64 cmd /c echo hello` must actually print `hello`; a
zero exit code without the output is a failure.

## Runtime download (R3)

Settings ▸ Storage ▸ **Runtime download** lists the pinned artifacts with their size,
SHA-256 prefix and URL, plus Download / Resume / Cancel / Import / Extract / Remove and a
free-space check for the whole set. The manifest covers the Ubuntu 22.04.5 arm64 rootfs,
the two Wine 9.0 loader `.deb`s and **Firefox ESR 140.17.0 (linux-aarch64)** - the
desktop browser engine, whose pin was anchored to Mozilla's own SHA512SUMS before the
SHA-256 was recorded.

Rules the code enforces (all unit-tested): a file is written to `<name>.part` and only
promoted to its real name after its SHA-256 matches the pin; a mismatch deletes the
download; a server that ignores `Range` is treated as an error rather than producing a
corrupt tail; an interrupted download keeps its `.part` and resumes; SAF imports are held
to the same pin as network downloads. **Extraction is in-app and needs no root and no
native tar**: a pure-Kotlin TAR reader (ustar + GNU long names + PAX overrides, verified
checksums, Zip-Slip-safe, exec bits and symlinks preserved, device nodes skipped because
proot emulates the standard `/dev` entries) unpacks the gzip rootfs, and xz-java (public
domain) unpacks Mozilla's `.tar.xz` straight into `<rootfs>/opt/firefox-esr`. Both paths
were run against the real pinned archives during development. Hashes come from the
publishers (Ubuntu `SHA256SUMS`, the Ubuntu archive `Packages` index, Mozilla
`SHA512SUMS`) and are recorded in `docs/THIRD_PARTY.md`. After extraction, the
**Provision** card installs the browser's GTK3/X11/font/CA-cert stack with `apt-get`
inside proot (`RootfsProvisioner`, package list pinned in code, every line logged). Its
**Run doctor** chip first checks the host-side prerequisites — pinned proot set,
extracted rootfs, rootfs really is arm64, `ports.ubuntu.com` sources, resolv.conf, free
space, writable TMPDIR, archive reachability — and then **really execs proot** with four
trivial guest commands in the exact jail Provision uses (`/bin/true`, `id` = does `-0`
give the guest uid 0, `apt-get --version`, and `command -v ldconfig &&
start-stop-daemon` = are dpkg's required programs on the guest PATH), so an
argument-form, ptrace/seccomp, fake-root or guest-PATH problem is named in seconds, with
the exact command line to rerun, instead of letting apt die with a bare "apt-get failed"
minutes later. Two of those were found this way on a real device: this fork has no `--`
end-of-options marker at all (it aborts with `unknown option "--"`), and dpkg refuses to
configure anything unless `/sbin` is in the guest PATH; the provision environment also
sets `PROOT_TMP_DIR`, which is what the fork's temp module reads instead of `TMPDIR`.

## Container runtime (R2)

`ContainerService` is a foreground service (`specialUse`, with the required property,
`FOREGROUND_SERVICE` + `POST_NOTIFICATIONS`) that owns the container processes - no
Termux, no adb. Its notification has a **Stop all** action; `onDestroy` stops every child.

`ContainerLauncher` is the actual logic and contains no Android types, so all of it is
unit-tested with a fake `ProcessLauncher`:

1. **Validate before spawning** and refuse with a named reason: `BINARIES_NOT_READY`
   (which includes the fail-closed SHA-256 verdicts), `ROOTFS_MISSING`, `PREFIX_MISSING`,
   `NO_COMMAND`, `ALREADY_RUNNING`, `SPAWN_FAILED`.
2. **Build the command**: `proot -r <rootfs> --link2symlink --kill-on-exit
   -b <prefix>:/root/.wine -b <drive>:/mnt/<letter> … -w /root -- box64 wine64 cmd`
   (short options on purpose: the vendored termux/proot rejects a detached `--rootfs <path>`).
   box64 is inserted only for Windows payloads (`wine*`, `*.exe`), not for native commands.
3. **Build the environment**: `WineEnvironment.build()` plus this container's
   `PROOT_LOADER`/`PROOT_LOADER_32`, sorted so Diagnostics can diff it.
4. **Stream** stdout/stderr into the shared `LogBuffer` (Terminal `log` and Diagnostics
   read the same lines) and map the exit code to `STOPPED`/`ERROR`.

`ContainerManagerState` now drives it through `LauncherRuntime`, starts the service on a
successful launch, stops it when nothing is running, and each container card shows its own
log excerpt.

**It is inert on purpose**: with no `libbox64.so`/`libproot.so` and no rootfs, Start
reports exactly which piece is missing and marks the container *Not ready*. Nothing is
guessed or faked.

## Native runtime (R1)

proot and box64 are shipped as `lib*.so` in `app/src/main/jniLibs/arm64-v8a/` and
**executed in place from `nativeLibraryDir`** — `targetSdk 34` forbids exec from app data,
and `jniLibs.useLegacyPackaging = true` keeps them extracted.

Every shipped binary must be pinned by SHA-256 in `app/src/main/assets/native/checksums.txt`;
a required binary that is unpinned or mismatched is **refused** (`CHECKSUM_NOT_PINNED` /
`CHECKSUM_MISMATCH`) rather than exec'd — all four vendored files (proot, its loader,
libtalloc, box64) are pinned. `prepare()` is per-launch now: `ContainerLauncher.requiredFor()`
demands box64 only for x86/x64 guests, so the native-arm64 Firefox session is not held
hostage by it. `.github/workflows/build-native.yml` (`workflow_dispatch` only, so it can
never affect `build-apk`) builds box64 from a pinned ref and **commits `libbox64.so` plus
its `checksums.txt` pin straight to the target branch** — fail-closed pinning stays
intact, and the push re-triggers `build-apk` to validate the APK with the new binary.
Its proot job re-runs the vendored termux/proot recipe on demand (upload-only; GPL-2.0
re-vendoring is an owner decision). `docs/NATIVE_RUNTIME.md` has the full build recipe
(reproduced once in a 1 GB sandbox with NDK r27c) and the licence rules (proot is GPL-2.0
and is only ever exec'd, never linked; talloc is LGPL-3.0+ and dynamically linked by
proot, not by the app).

## Container Manager

A container = a Wine prefix + its Box64 preset + drive mappings + environment. The
manager creates/renames/deletes them (`<files>/containers/<id>/{container,prefix}`),
records them in a hand-rolled `containers.list` line format (atomic temp-file + rename,
no new dependency), picks a default, edits the preset (General/Office/.NET/Electron),
architecture (WoW64 / 64-bit), DPI, and drive letters (C: = prefix and Z: = guest root
are implicit), and shows the **exact environment map R2 will exec with**, sorted for
diffing.

`WineEnvironment.build()` is the R2 contract already implemented and unit-tested: fully
explicit `DISPLAY`, `WINEPREFIX`, `WINEARCH`, `WINEDEBUG`, `WINEDLLOVERRIDES`
(`mscoree,mshtml=` so Wine never auto-downloads Gecko/Mono), `PATH`, `LD_LIBRARY_PATH`,
locale, PulseAudio socket, `PROOT_ROOTFS`/`PROOT_LOADER`, plus per-preset `BOX64_*`
tuning, with the container's `extraEnv` applied last so a user can override anything.
`GuestPaths` converts host↔Windows paths (longest mapping wins) for the P1 "open with"
plumbing.

**What a container cannot do yet, stated plainly:** Start validates, in order, the
pinned binary set (proot + box64 both vendored now), the extracted rootfs, and the
prefix — and the prefix is where the chain stops. A prefix is *populated* by running
`wineboot` inside the rootfs; `wineboot` needs a Wine payload; no payload is
provisioned (the browser's Provision list installs GTK3/X11/fonts, not Wine). The
refusal therefore names all four links, quotes the first missing Wine path it looked
for, and points at Diagnostics ▸ Run self-test, whose three R4 rows
(`box64 --version`, `wine64 --version`, `wine64 cmd /c echo hello`) show exactly how
far the chain gets — the first is green on device now, the other two wait on Goal 2
step 2. An empty prefix directory is refused as "never initialised" rather than being
handed to wine mid-launch. `RuntimePaths.ensurePrefix` only mkdirs (the Firefox
profile is its one caller) and its KDoc says so.

Start/Stop/Restart go through a `ContainerRuntime` interface; today's
`NotInstalledRuntime` answers with exactly which step is missing (R1 binaries, R2
service, R3 rootfs) instead of failing silently, and the container is marked
"Not ready" with that message.

## Terminal

N2's Terminal is an honest local console, not a fake Windows prompt: `help`, `pwd`,
`ls`/`dir [-a]`, `cd`, `cat`/`type`, `echo`, `date`, `free`, `env`, `log`, `clear`,
`version`, `uname`, `whoami`, with quote/escape-aware argument splitting, command history
on the arrow keys, exit codes, and quick-command chips. `wine`, `wine64`, `box64`, `proot`,
`cmd`, `container` and `start` all answer "not available yet" and name the step that
delivers them - they never pretend to work.

Execution sits behind a `CommandExecutor` interface, so **R2 swaps in a container
executor** (`proot -> box64 -> wine` over a pty) without touching this UI, and the
transcript is mirrored into a bounded `LogBuffer` that R2's ContainerService and R4's
Diagnostics screen read from.

## Task Manager

Processes tab: the shell process (real RSS from `/proc/self/status`, CPU% from
`/proc/self/stat` against `/proc/stat` jiffies), one row per open window with its real
status (Running / Suspended / Closing), and **End task** which routes through the window
manager - so a dirty Notepad still gets its save dialog. Performance tab: live CPU and
memory sparklines refreshed every 2 s. Container rows (proot, box64, wine, X server)
appear when R2's ContainerService reports them. Long-press any taskbar pin for a jump
list: running windows, New window, Task Manager.

## Browser

The Browser pin opens a shell window with an address bar. The shell never renders web
content: it resolves the input (URL, bare hostname -> `https://`, otherwise a search)
and hands it to a real browser via `ACTION_VIEW`. **Firefox is preferred** when
installed (`org.mozilla.firefox`), then the user's pinned choice, then Android's
default handler; Settings ▸ Apps lists what is installed and stores the preference.
Firefox is never bundled in the APK.

For desktop-class browsing (profiles, extensions, AI/Copilot-style sidebars) the shell
models a second engine family - **Linux arm64 Firefox ESR / Chromium** in the rootfs and
**Windows arm64 Edge** under Wine. **Firefox ESR's launch path is fully plumbed (D1c/D1d)**:
Mozilla's official self-contained arm64 build is a pinned R3 artifact, a guarded worker
evaluates readiness (R1 binaries → rootfs → Firefox installed → GTK3 stack) into a
Compose-state snapshot, and a ready engine launches through R2's `ContainerLauncher` as
`proot → /bin/bash -c '<D-Bus prelude>; exec /opt/firefox-esr/firefox -P shell <url>'`
with an explicit sandbox-off environment on `DISPLAY=:100` - no box64, persistent
profile, foreground service, X Desktop window focused automatically. The first
load is quieted and sped up by `FirefoxPolicies` (policies.json + user.js +
profiles.ini, materialised idempotently: no telemetry/update/safe-browsing round
trips, prefetch kept on) and made visible by the X Desktop loading indicator -
spinner plus "first run builds the fontconfig cache" until the guest connects,
the named refusal if it does not start. The session is a full desktop: the
server runs pass-through and the session script starts **openbox** as the
guest WM (provisioned with the GTK3 stack) before Firefox, and
`MOZ_FORCE_DISABLE_E10S=1` plus a provision-time `fc-cache -f` take the two
biggest first-load stalls (per-fork ptrace cost, font-cache building) out of
the first paint. The prelude
starts a private `dbus-daemon --session` at a fixed guest address, which is what lets
a second URL reach the *running* instance instead of being refused as a double start. Until the runtime is vendored the card names the
missing piece and selection falls back to an Android browser, exactly as
`RoadmapGatingTest` pins it. See `docs/BROWSER_ENGINES.md` for the feasibility write-up,
the licensing constraints (Edge is proprietary and must not be redistributed) and the
open decisions.

## UI tests

`./gradlew testDebugUnitTest` runs the 644 pure-JVM tests (window geometry, snap zones,
Explorer, Notepad encodings, settings, URL/engine resolution, /proc parsing, argv
splitting, the log buffer, the built-in commands, the terminal transcript/history, the
container codec/store, guest<->host path mapping, the Wine environment builder, the
native-binary inventory/permission handling, SHA-256 pinning and its fail-closed verdicts,
the probe runner, the proot command line, and the R2 launcher: every refusal path
(missing/unpinned binaries, missing rootfs, missing prefix, empty command, spawn failure,
double start), the `proot -> box64 -> wine` command shape, drive binds, the explicit
environment, stop/restart/stopAll, exit-code -> status mapping, and R3's downloader:
resume offsets, hash fail-closed, space refusal, cancellation, servers that ignore
`Range`, SAF import verification, the extract gate, and the R4 self-test: command order,
proot wrapping, prefix binding, "printed hello" assertion, and skip-instead-of-pass when
box64/proot/rootfs are missing; plus the D1c x11 suites: the spec opcode table, focus and
grab event routing, warps, fonts/colours/cursors, SendEvent, GetImage against the
pure-Kotlin rasteriser, XTest FakeInput, soft-keyboard text injection and the IME commit
extractor; and the Firefox ESR session: native (never box64) proot command shape, profile
and X11 binds, the explicit sandbox-off environment, install/GTK checks, the R3 state
machine's READY marker, the pinned Mozilla artifact, and the desktop-engine launch seam
with its gating contract; the Provision doctor, including its real proot smoke run
(argument form, fake-root uid, `apt-get --version`, and every named failure cause);
the BIG-REQUESTS/Generic-Event transport (extended request framing, reassembly across
socket reads, the oversize and not-enabled refusals, generic-event padding); the whole
XInput2 surface - the device list walked class-by-class the way libXi walks it,
selection round-trips, per-connection delivery and propagation, the modifier and
button-mask encodings, crossing and focus details, grab redirection without a duplicate
core event, pointer queries, warps, per-device cursors, device properties, and the
requests that are refused and named; the X self-test's GTK3 probe (its own timeout,
no Xlib `-display` flag, exit-code verdict, and a failure row that points at GTK's own
`request_code` diagnosis); and the worker-thread rule that keeps every Diagnostics
chip off the UI thread, which is what stopped the self-test being an ANR;
and the P1 clipboard bridge: server-wide selection ownership,
cross-client SelectionRequest routing, the ICCCM mask-0 SendEvent idiom, inline shell
conversions with TARGETS, ownership lifecycle, and the end-to-end guest-copy push
through a fake bridge; the D1f XFIXES suite (the 5.0 version pin, selection watches
with all three subtypes including the cross-client root-window idiom and the shell
reclaims, mask filtering, cursor watches/names/ChangeCursor, the 16x16
GetCursorImage raster, the full region algebra through FetchRegion, FromBitmap's
depth-1 BadMatch rule and ops-based scan, the picture-clip and input-shape
wirings, and the named barrier refusal) and SYNC suite (INT64 counters, held
Awaits answered with their own sequence number, transition vs comparison vs
relative waits, alarms with deltas/events, the 40-byte QueryAlarm reply, the
named fence refusal, and connection-death cleanup); and the Firefox first-load
file set (the exact policies/user.js/profiles.ini keys, JSON balance, idempotent
materialise, install-gated policies); and the pass-through session model
(parent-relative coordinates resolved to absolute origins, mapped-vs-viewable,
Expose on becoming viewable, ReparentWindow placement + ConfigureNotify,
ancestor-clipped hit-testing and rendering).

Compose tests that actually **compose** the taskbar (Robolectric) are opt-in because they
need a couple of GB of RAM:

```bash
./gradlew testDebugUnitTest -PuiTests=true      # CI runs this as a separate step
```

They exist because of a real regression: a `Popup` that was composed unconditionally drew
every taskbar pin's jump list at once and stole input focus, so nothing could be clicked.
Popup content must always be gated by `expanded`/`if (open)`.

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
  ui/taskbar/TaskbarParts.kt   button/indicator, tooltips, jump-list popup, tray, clock
  ui/start/StartMenuPanel.kt   scrim + animated Start menu (search, pinned, all apps, recommended)
  ui/window/WindowLayer.kt     z-ordered rendering, open/close/minimize animations, snap preview
  ui/window/WindowFrame.kt     frame, caption, drag-to-move/snap, 8 resize handles
  ui/window/WindowControlButton.kt  minimize / maximize-restore / close
  ui/window/WindowContent.kt   routes a window to its app body
  ui/explorer/FileEntry.kt     file model, listing, filtering, sorting, size/type formatting
  ui/explorer/ExplorerState.kt navigation + selection + create/rename/delete (all pure JVM)
  ui/explorer/ExplorerScreen.kt toolbar, breadcrumb, nav pane, status bar, dialogs, menus
  ui/explorer/ExplorerViews.kt details rows and tiles grid
  ui/explorer/ExplorerIcons.kt extension -> icon mapping, sidebar locations
  ui/notepad/DocumentIO.kt     encodings, BOM, line endings, Ln/Col maths
  ui/notepad/Windows1252.kt    bundled cp1252 codec (Notepad's "ANSI")
  ui/notepad/NotepadState.kt   buffer, dirty flag, find/replace, zoom, caption
  ui/notepad/NotepadMenuBar.kt File / Edit / Format / View menu bar
  ui/notepad/NotepadViews.kt   editor, find & replace bar, status bar
  ui/notepad/NotepadScreen.kt  SAF open/save/save-as, shortcuts, close guard
  settings/SettingsStore.kt    persisted, observable shell settings (+ keys, presets)
  ui/settings/SettingsUi.kt    sections, searchable settings catalogue
  ui/settings/SettingsScreen.kt Personalisation / Apps / Storage / Containers / Diagnostics / About
  ui/browser/Browsers.kt       installed-browser discovery, intent building, URL parsing
  ui/browser/BrowserState.kt   address bar, target package, launch bookkeeping
  ui/browser/BrowserApp.kt     Browser window: address bar + browser chooser
  ui/browser/BrowserEngine.kt  Android vs desktop engines, catalogue, resolver
  ui/taskmanager/ProcessList.kt  /proc parsing, CPU maths, sampler, history ring
  ui/taskmanager/TaskManagerScreen.kt Processes + Performance tabs, End task
  ui/terminal/LogBuffer.kt     bounded shared log sink + shell-style argv splitting
  ui/terminal/SharedLog.kt     the one log the Terminal, service and Diagnostics share
  ui/terminal/CommandExecutor.kt CommandResult, executor interface, built-in local commands
  ui/terminal/TerminalState.kt transcript, prompt, cwd, history, exit/clear handling
  ui/terminal/TerminalScreen.kt console transcript, quick commands, input row
  container/Container.kt       container model, drives, guest<->host paths, wine env builder
  container/ContainerStore.kt  containers.list codec + on-disk store + runtime interface
  ui/containers/…              Container Manager state and screen (presets, drives, env preview)
  runtime/NativeBinaries.kt    jniLibs inventory, +x fix, PrepareResult (R1)
  runtime/RuntimeProbe.kt      ProcessRunner seam + `--version` probes with explicit env
  runtime/ProotCommand.kt      the proot command line: fixed prefix bind, drives, R4 plan
  ui/diagnostics/…             Diagnostics window (binaries, probes, log excerpt)
  runtime/ProcessLauncher.kt   start/stream/destroy seam (real ProcessBuilder + fakes in tests)
  runtime/ContainerLauncher.kt validate -> build command+env -> start -> track status/logs (R2)
  runtime/FirefoxSession.kt  Firefox ESR desktop-browser session: guest command, env, binds, install checks
  runtime/FirefoxPolicies.kt first-load file set: policies.json + user.js + profiles.ini
  runtime/RootfsProvisioner.kt apt-inside-proot provisioning of the browser's GTK3/X11/font/CA stack
  runtime/download/TarExtractor.kt pure-Kotlin TAR reader (gzip + xz) - the on-device extraction engine
  runtime/DesktopBrowserLauncher.kt readiness worker (named missing step) + the gated launch path
  runtime/ContainerService.kt  foreground service, notification, Stop all, RuntimePaths/RuntimeHolder
  runtime/download/Artifact.kt   pinned manifest (Ubuntu 22.04.5 arm64, Wine 9.0 debs) + real hashes
  runtime/download/Downloader.kt resume/hash/space/cancel logic + https Transfer seam
  runtime/download/RootfsManager.kt per-artifact state machine, SAF import, extract gate
  x11/X11.kt                 wire primitives, spec-accurate opcodes, events, masks (D1a/D1c)
  x11/XDocument.kt           window tree, atoms, properties, hit testing, focus/grabs/cursors, WM hooks
  x11/XConnection.kt         handshake, request dispatch, replies/errors/events, XTest, XSink
  x11/XDisplayList.kt        per-drawable DrawOp store + pure-Kotlin rasteriser for GetImage (D1b/D1c)
  x11/XColors.kt             rgb.txt named-colour table + rgb:/#hex parsing (D1c)
  x11/XSelections.kt         selection ownership model, clipboard-bridge seam, target codec (P1)
  x11/XFixes.kt              XFIXES 5.0: watches, region algebra, cursor art (D1f)
  x11/XSync.kt               SYNC 3.1: counters, conditions, alarms (D1f)
  x11/XIme.kt                IME commit extraction for the soft-keyboard path (D1c)
  x11/ShellWindowManager.kt  in-process WM (kept for tests / a future per-window mode; the session model is pass-through, D1g)
  x11/XKeyboard.kt           true evdev+8 keycode map, US ASCII table, Latin-1 range (D1c)
  x11/XInput.kt              device input -> X events, modifier masks, injectText/injectKeysym (D1c)
  x11/XServer.kt             LocalServerSocket on /tmp/.X11-unix/X100, accept + serve, warp sync
  ui/xdesktop/XDesktop.kt    Bitmap/Canvas renderer + input surface + software cursor + hidden IME field (D1b/D1c)
  ui/xdesktop/XDesktopClipboard.kt  Android clipboard bridge + the DesktopScreen wiring effect (P1)
  ui/settings/RootfsPage.kt    Settings ▸ Storage ▸ Runtime download (progress, actions)
  runtime/SelfTestRunner.kt    the R4 self-test: 3 steps, verdicts, reasons, report/log excerpt
```

Unit tests (`testDebugUnitTest`, plain JVM, no Robolectric — 635 tests, measured by
annotation count; the pure-JVM subset is additionally compiled and executed off-device
against JDK 17 + Kotlin 2.1.10 with minimal Android stubs, so 629 of them are run for
real outside Gradle too): snap zones,
maximize/restore, halves snapping, edge/corner resize with minimums, on-screen clamping,
z-order/focus, taskbar toggle open-minimize-restore, Explorer listing/filtering/sorting,
Windows-style size and type labels, back/forward/up/trail navigation, selection,
create/rename/delete and the revision counter that refreshes the list,
Notepad encoding/BOM round trips, CRLF-LF-CR detection and preservation, Ln/Col maths,
find next/previous with wrap and match-case, replace/replace-all, zoom clamping,
the bundled windows-1252 codec, the window close guard, the two-phase close
animation, the animation curve, settings persistence/defaults/fallbacks, settings
search, URL parsing (host vs. search), the browser-engine resolution order (Firefox
first, desktop engines gated on the runtime), `/proc/stat` + `/proc/<pid>/stat` parsing,
per-process and total CPU maths, and the history ring buffer.

See `docs/THIRD_PARTY.md` for third-party components and licences.
