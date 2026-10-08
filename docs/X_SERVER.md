# D1 — the in-app X server: status and what is left

Goal (D1): the app owns an X server on a private unix socket, one fullscreen native
surface runs `explorer.exe /desktop`, and touch/mouse/keyboard become X input.
No Termux, no XSDL, no adb.

## What is in the tree now (D1a: protocol core + socket)

| Piece | File | Status |
| --- | --- | --- |
| Wire primitives: byte-order aware reader/writer, `pad4`, opcode/event/error/mask constants | `x11/X11.kt` | written, unit-tested for marshalling and padding |
| Document model: resource ids, window tree, map/unmap rules, atoms, properties, paint order, hit testing | `x11/XDocument.kt` | written, **unit-tested and passing** |
| Connection setup: parse the client's setup request, emit success/failure | `x11/XConnection.kt` | written, **byte layout not yet verified** |
| Request dispatch: window/atom/property/GC/drawing/extension/keyboard requests, replies, errors, events | `x11/XConnection.kt` | written, **reply framing not yet verified** |
| Keyboard: X keycode = evdev + 8, Latin-1 keysyms, Android→X mapping, `GetKeyboardMapping` table | `x11/XKeyboard.kt` | written, **unit-tested and passing** |
| Input translation: pointer move/button/key → X events with correct modifier and button masks | `x11/XInput.kt` | written, **unit-tested and passing** |
| Transport: `LocalServerSocket` on the abstract name `/tmp/.X11-unix/X100`, accept loop, per-client thread, handshake then request loop | `x11/XServer.kt` | written, **never run against a real client** |
| Renderer sink interface (`fillRect`/`putImage`/`imageText`/`clearArea`) + a recording sink | `x11/XConnection.kt` | interface only |

The abstract-namespace socket is what makes this work under proot without a bind mount:
proot does not isolate the abstract namespace, so a guest process connecting to
`DISPLAY=:100` reaches the app's socket directly. A filesystem socket path
(`<files>/tmp/.X11-unix/X100`) is also provided for clients that only try the path form;
that one needs `--bind <host dir>:/tmp/.X11-unix` in the proot command line (R2's
`ProotCommand.build(binds = …)` already takes it).

## Wire-format status: all 37 `XProtocolTest` tests pass

The 13 byte-layout tests that were `@Ignore`d during D1a are un-ignored and green
(full suite: **304 tests, 0 failures, 0 skipped**; `assembleDebug` OK). Getting there
fixed a set of real protocol bugs plus a few wrong test expectations:

**Server fixes**

- `reply()` now takes an explicit `dataByte` and writes the body verbatim at offset 8.
  The old implementation hoisted the body's first byte into offset 1, which shifted
  every reply whose handler also compensated with a leading byte/pad (GetGeometry,
  QueryPointer, TranslateCoordinates, GetProperty, GetImage, …) by 4 bytes.
- Setup reply is spec-shaped: release-number@8, resource-id-base@12, mask@16,
  motion-buffer@20, vendor-length@24, screens@28, formats@29, scanline unit/pad@32/33,
  min/max keycode@34/35, vendor@40; additional-length includes the 32 fixed bytes.
  `setupFailed`'s additional-length no longer counts the 8-byte header.
- Removed bogus `reader.skip(4)` calls in the handlers for GetSelectionOwner,
  QueryPointer, TranslateCoordinates, DeleteProperty, ChangeProperty and
  GetKeyboardMapping (`XReader` already starts at offset 4); ImageText8 now reads
  x/y@12/14 and the string@16.
- ConfigureWindow reads the value-mask at offset 8 (was 12); CreateWindow/
  ChangeWindowAttributes consume value-mask words in ascending bit order
  (background 0x2 → override-redirect 0x200 → event-mask 0x800), as does `applyGcValues`.
- GetWindowAttributes answers with the full 52-byte spec reply (map-state@26,
  your-event-mask@36, extra length 5); QueryTree reports parent 0 for the root.
- Pointer/key events put root@8 and event-window@12 (were swapped); key events pass
  root coordinates (window-relative was subtracted twice); `WM_DELETE_WINDOW`
  ClientMessage carries format@1, window@4, type@8, data@12; SelectionNotify gained
  its time field.
- Server-originated events (`sendEvent`, `onPointerMove`, `onButton`, `onKey`,
  `sendDeleteWindow`) flush to the outbox immediately instead of waiting for the next
  client request; MapWindow sends MapNotify on the un→mapped transition even while an
  unmapped ancestor keeps the window from becoming viewable; structure events reach
  parents that selected `STRUCTURE_NOTIFY` **or** `SUBSTRUCTURE_NOTIFY`; the root
  window now selects `SUBSTRUCTURE_NOTIFY` by default. Map/Unmap/Reparent notify use
  their own spec layouts instead of the Create/Destroy geometry shape.

**Test fixes** (the tests had the wrong bytes, verified against the X11R6 spec)

- Setup reply: base@12 (release-number occupies 8), vendor-length@24, keycodes@34/35,
  vendor@40.
- GetProperty value-length is the CARD32 at offset 16, not a CARD16 at 20.
- QueryTree extra-length counts the child list (1 unit for one child).
- Pointer-event and ClientMessage assertions moved to the spec offsets.
- The keyboard-mapping assertion addressed a 64-byte reply at offset 808
  (unreachable); it now requests keycode 38 and checks `'a'`/`'A'` at offsets 32/36
  with keysyms-per-keycode in the reply's data byte.
- The pointer-input test maps the window before moving the pointer — hit testing only
  considers mapped windows, as in X.

**Environment note:** in the ~1 GB sandbox, compile with
`-Xmx380m -XX:MaxMetaspaceSize=200m -XX:ReservedCodeCacheSize=64m -Xss512k
-XX:+UseSerialGC -XX:SoftRefLRUPolicyMSPerMB=0` and
`-Pkotlin.compiler.execution.strategy=in-process`; bigger heaps get the daemon
OOM-killed mid-compile. After a sandbox reset, `rm -rf app/build .gradle .kotlin`
first — stale incremental state produced phantom "protocol bugs" more than once.

## What D1b needs (in order)

1. ~~Fix the 13 ignored tests.~~ **Done** (see above). Still worth verifying with a real
   client: `xclock`/`xeyes` inside the rootfs, or `xinit` from a Linux box over the socket.
2. ~~**Renderer**~~ **Done (D1b).** `XDisplayList` (pure Kotlin) records every drawing
   request per drawable as `DrawOp`s (`Fill`/`Image`/`Text`/`Clear`/`Copy`); ZPixmap
   depth 24/32 is converted BGRX→ARGB at record time. `XCanvasRenderer`
   (`ui/xdesktop/XDesktop.kt`) replays ops incrementally into a per-drawable `Bitmap`
   cache and composites `XDocument.paintOrder()` onto the Compose surface, letterboxed
   and scaled to the window. `CopyArea` blits between drawable bitmaps (pixmap sizes
   come from `CreatePixmap` via `XSink.setSize`). The `PutImage` wire read was fixed:
   byte 1 is the *format*, depth lives at offset 21.
3. ~~**Input surface**~~ **Done (D1b).** The X Desktop window forwards raw
   `MotionEvent`s (`pointerInteropFilter`): touch/mouse moves → `MotionNotify`, button
   state diffs → `ButtonPress/Release` (Android button constants map 1:1 onto X via
   `XInput.buttonFor`), `AXIS_VSCROLL` → buttons 4/5, hardware keys via `onKeyEvent` →
   `XKeyboard`. IME/soft-keyboard text injection is still open (needs XIM or XTest).
4. ~~**Window-manager side**~~ **Done (D1b).** `SUBSTRUCTURE_REDIRECT` is honoured:
   a client `MapWindow`/`ConfigureWindow` on a top-level, non-override-redirect window
   no longer applies; it becomes a `MapRequest`(20)/`ConfigureRequest`(23) event for a
   client WM, or a call into the in-process `XWindowManager` (only one redirect owner;
   the second selector gets `Access`(10)). `ShellWindowManager` places windows in a
   centered cascade and maps through `XServer.wmMapWindow`, which broadcasts
   `MapNotify`+`Expose` (and `ConfigureNotify` for resizes) to every connection,
   pushing to idle sockets via per-connection output streams. Close still goes through
   `sendDeleteWindow` (WM_DELETE_WINDOW).
5. **Launch**: R2's `ContainerService` starts `wine64 explorer.exe /desktop=Shell,<W>x<H>`
   with `DISPLAY=:100` and `PROOT_LOADER*` from `NativeBinaries.loaderPaths()`.
   Blocked on vendoring the native binaries (R1 decision, see `docs/NATIVE_RUNTIME.md`).
   Until then, `ExplorerSmokeTest` scripts the same request sequence `explorer.exe`
   sends over the wire and asserts WM redirect, placement, Expose, pixels in the
   display list, input dispatch and WM_DELETE_WINDOW — 316 unit tests, all green.

## How to look at it on a device

Open **Start → X Desktop** (not pinned to the taskbar). The window starts the server on
`:100` at its own pixel size and shows "Waiting for clients" until a guest connects.
Anything that speaks core X11 to the abstract socket `@/tmp/.X11-unix/X100` — e.g.
`xclock` from a proot rootfs with `DISPLAY=:100` — appears as a shell-placed window.

## Deliberate non-goals for D1

No RENDER/Composite/XRandr/XShm extensions (`QueryExtension` answers "not present", so
clients fall back to core `PutImage`), no XIM, no multi-screen, no colour maps beyond a
stub `AllocColor`, no cursors, no real font metrics (`ImageText8` is rasterised with a
monospace approximation; `OpenFont`/`PolyText8`/`ImageText16` are accepted but not
drawn). Those are the things that make a real X server big; they are not needed to run
`explorer.exe /desktop` with Wine's core-protocol path.
