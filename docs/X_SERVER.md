# D1 — the in-app X server: status and what is left

Goal (D1): the app owns an X server on a private unix socket, one fullscreen native
surface runs `explorer.exe /desktop`, and touch/mouse/keyboard become X input.
No Termux, no XSDL, no adb.

**Milestone status:** D1a (protocol core + socket) done, D1b (renderer + input + WM)
done, **D1c (real-client compatibility) done**. What remains for D1 is the guest
launch itself, which is blocked on vendoring the R1 native binaries - not on server code.

## D1c: what changed and why it was blocking real clients

### The opcode tail was wrong (fixed)

Before D1c the request table in `x11/X11.kt` diverged from the X11R6 specification
from opcode 92 upward - e.g. it put `QueryExtension` at 112 and `SetCloseDownMode`
at 98. The truth (xorgproto `Xproto.h`) is the opposite: **QueryExtension is 98** and
**SetCloseDownMode is 112**. A real libX11 client asks `QueryExtension` (98) early in
`XOpenDisplay`; the server treated that as a no-op *without a reply*, so the client
would block forever. `GetKeyboardMapping` (101), `ListExtensions` (99), `Bell` (104),
the screensaver pair (107/108) and the mapping requests (116-119) were all off too.
The whole 1-127 table is now spec-accurate and `XProtocolTest` pins the numbers with
their provenance. Error codes were corrected the same way (BadAlloc is 11, BadGC 13,
BadName 15; the repo had BadAlloc=17 and BadGC=11).

### The keymap collided with itself (fixed)

The old map derived letter keycodes as `androidKeyCode + 9`, which lays a-z out
alphabetically at 38..63. That block contained the server's own modifier keycodes:
keycode 50 was both 'm' and Shift_L (hardware 'm' typed Shift), and punctuation fell
back to `unicodeChar + 8`, so hardware '-' arrived as keycode 53 = 'p'. The map is
now the true **evdev+8** layout a real X.org server advertises (q-p = 24..33,
a-l = 38..46, z-m = 52..58, digits 10..19, symbols at their evdev positions,
Shift_L = 50, Shift_R = 62), with a complete US-layout ASCII table including shifted
keysyms, and a dynamic Latin-1 range (keycode = code point + 8 for 0xA0..0xF7) so
accented characters round-trip through `GetKeyboardMapping`. Tests were updated to
the spec positions - deliberately, as with the D1b test fixes.

### Newly implemented core requests

- **Focus**: `SetInputFocus`/`GetInputFocus` with FocusIn/FocusOut events, focus
  reverting on destroy/unmap, and keyboard events going to the *focus* window
  (PointerRoot by default, which follows the pointer - the old behaviour).
  `QueryKeymap` reports the real physical key state (256-bit map).
- **Grabs**: `GrabPointer`/`GrabKeyboard` now redirect events to the grab window for
  every connection (other clients see nothing while grabbed - what Wine's menus
  rely on), with owner-events dual delivery, confine-to clamping,
  `ChangeActivePointerGrab`, and AlreadyGrabbed statuses. `GrabButton`/`GrabKey`
  passive grabs activate into implicit grabs on a matching press and end on release
  (same-connection activation only; cross-client passive grabs stay recorded but
  inert - documented limit). `AllowEvents`/`GrabServer` are accepted no-ops (we
  never freeze).
- **Pointer**: `WarpPointer` (with the source-rect containment rule), warps generate
  MotionNotify and sync every connection plus the software cursor through
  `XDocument.onWarp`. EnterNotify/LeaveNotify fire on window crossings.
  `GetMotionEvents` answers with an empty (legal) history.
- **Fonts**: `OpenFont` accepts any name and serves it with the built-in **fixed
  6x13** metrics; `QueryFont` returns the spec 60-byte reply, `QueryTextExtents`
  computes 6px-per-char widths, `ListFonts`/`ListFontsWithInfo` (info + terminator
  reply pair) answer "fixed", `SetFontPath`/`GetFontPath` are handled, `CloseFont`
  frees. Glyph rasterisation itself stays the monospace approximation in the
  renderer - metrics are now consistent with what clients measure.
- **Colours**: a ~90-entry rgb.txt table (`x11/XColors.kt`) plus `rgb:RR/GG/BB` and
  `#RGB`/`#RRGGBB` parsing power `LookupColor`, `AllocNamedColor` (TrueColor pixel =
  the RGB value) and `QueryColors`; `AllocColor` now returns the real pixel instead
  of 0; cells/planes/store/free/install/list-colormaps are answered spec-shaped.
- **Cursors**: `CreateCursor`/`CreateGlyphCursor`/`FreeCursor`/`RecolorCursor`
  resources live server-wide in `XDocument`; `CWCursor` is parsed in
  Create/ChangeWindowAttributes (previously an unconsumed value-list word);
  `QueryBestSize` clamps cursors to 64x64; `effectiveCursorGlyph()` resolves the
  X inherit-from-ancestor rule and the X Desktop surface draws a **software cursor**
  (arrow, I-beam, crosshair, watch approximation) at the server-side pointer
  position, unscaled for usability. Pixmap-based cursors render as the arrow.
- **SendEvent**: real delivery - destination resolution (root/PointerWindow/
  InputFocus/window), the 0x80 send-event flag, sequence restamping, propagate
  walking with do-not-propagate masks, and SubstructureRedirect routing for
  structure events. Cross-client delivery goes to the *owner* connection's socket
  (window ownership is tracked from CreateWindow).
- **Properties**: `ListProperties`, `RotateProperties` (with PropertyNotify).
- **Drawing**: `PolyLine`, `PolySegment`, `PolyPoint`, `PolyArc`, `PolyFillArc` and
  `FillPoly` become first-class `DrawOp`s (Canvas paths in the renderer, scanline/
  Bresenham/parametric fills in the pure-Kotlin rasteriser); `ImageText16` and
  `PolyText8/16` (CHAR2B + TEXTITEM parsing with deltas and font-shift items) draw
  text - this is how Wine's `XDrawImageString16` output reaches the screen;
  `ImageText8` now paints the GC background box first; `CopyPlane` best-effort maps
  onto CopyArea; all INT16 wire fields are sign-extended (negative blit offsets
  previously wrapped to huge positives).
- **GetImage** returns real pixels: `XDisplayList.snapshot()` is a dependency-free
  rasteriser (fills, ZPixmap blits, clears, copies with recursion guard, lines,
  points, polygons, sampled arcs; text glyphs stay the platform renderer's job).
- **Control tail**: `GetKeyboardControl`, `Get/SetPointerControl`,
  `Set/GetScreenSaver`, `ChangeHosts`/`ListHosts`/`SetAccessControl`,
  `SetCloseDownMode`, `KillClient` (accepted; no per-client resource tracking),
  `ForceScreenSaver`, `Set/GetPointerMapping` (identity 1,2,3),
  `Set/GetModifierMapping` (evdev positions: Shift 50, Lock 66, Control 37,
  Mod1 64, Mod4 133), `ChangeSaveSet`, `CirculateWindow` (real restacking),
  `ChangeKeyboardMapping`/`ChangeKeyboardControl` accepted (fixed map).

### XTest extension

`QueryExtension("XTEST")` now answers *present* with major opcode 130 (dynamically
assigned, as extensions are). Implemented: `GetVersion` (2.1), `CompareCursor`,
`GrabControl` (no-op) and **`FakeInput`** - synthesised key/button/motion events
routed through the same focus/grab machinery as real input, with keycode-derived
keysyms and modifier-mask updates. This gives Wine its synthetic-input path
(`SendInput`, cursor warping) and any X automation tool a working surface.

### RENDER extension (D1d) - and the version the server claims

GTK3/cairo cannot render without RENDER: text goes through `CompositeGlyphs`,
fills through `FillRectangles`/`Composite`, and every anti-aliased edge through
an a8 mask picture. `QueryExtension("RENDER")` therefore answers *present*
(major opcode 129, error base 160), and the extension is implemented down to the
rasterisers - the pure-JVM snapshot one (`XDisplayList.rasterise`, which GetImage
and the wire tests read back) and the Android one (`XCanvasRenderer`, native
Porter-Duff xfermodes + cached ALPHA_8 glyph bitmaps).

**Implemented (everything a 0.4 client can send):** `QueryVersion`,
`QueryPictFormats` (10 standard Direct formats, screen depths, the visual→format
map for 0x21/0x22, subpixel order Unknown), `QueryPictIndexValues` (Direct
formats have no palette), `CreatePicture` (with depth/format validation),
`ChangePicture` (value masks consumed in bit order), `SetPictureClipRectangles`,
`FreePicture`, `Composite` (the 14 core Porter-Duff ops, source repeat
None/Normal/Pad/Reflect, mask pictures, clip rectangles, clip-mask pixmaps),
`FillRectangles`, `CreateGlyphSet`/`ReferenceGlyphSet`/`FreeGlyphSet`,
`AddGlyphs` (a1/a4/a8 bitmaps, 32-bit scanline padding, LSB-first bits),
`FreeGlyphs`, `CompositeGlyphs8/16/32` (pen advances, the 255→CARD32 escape,
4-byte id alignment, all-or-nothing BadGlyph semantics). Depth-8 and depth-1
pixmaps now decode (`toArgb`) because cairo's a8 span masks need them, and a
fill on an a8 pixmap records coverage-as-alpha.

**The version answer is deliberately 0.4, not 0.11** (see `XRender`'s KDoc):
clients gate optional requests on it. At 0.4 cairo composites anti-aliased spans
through a8 masks + `Composite` (all implemented) instead of `AddTraps` (0.8,
not implemented), builds "solid" sources from a repeated 1×1 pixmap instead of
`CreateSolidFill` (0.10), and Xcursor falls back to core font cursors instead of
ARGB cursors (0.5). Answering 0.11 would invite requests we must refuse with
BadRequest - and a BadRequest kills GTK (`_exit(1)` in its error handler). The
claim rises when the corresponding requests are implemented; anything beyond it
is refused **and named** in the protocol trace ("RENDER.AddTraps x42"), so the
shared log says exactly what to implement next.

Still absent (honestly reported by `QueryExtension`): XKB, XInput/XInput2,
RANDR, MIT-SHM, XFIXES, COMPOSITE, DAMAGE, SHAPE, BIG-REQUESTS, SYNC. Device
traces from real Firefox/GTK3 handshakes decide which one matters next.

### Soft keyboard (the D1b open item)

No XIM/XKB is needed for Latin-1 text: the X Desktop window hosts a 1dp transparent
`BasicTextField`; taps focus it and show the soft keyboard. Committed text is
diffed by the pure-Kotlin `ImeCommitExtractor` (composition-aware: preview text is
never injected, commits inject whole words), then `XInput.injectText` turns each
character into real KeyPress/KeyRelease pairs - Shift pressed around shifted
characters - through `XKeyboard.stepsForChar`. IME action keys become Return.
Hardware keys while the field holds focus go through `onPreviewKeyEvent`: printable
characters fall through to the field (and arrive as commits), everything else
(arrows, F-keys, Enter, Ctrl/Meta chords) is dispatched directly as X keys.
**Limit:** characters outside Latin-1 (CJK, emoji) are skipped - they need XIM/XKB
(P1+). The extractor and the whole injection path are unit-tested; the Compose
wiring follows the existing input-surface patterns.

### Selections and the Android clipboard (P1 bridge, landed with D1c)

Selections are server-wide now (ownership lives in `XDocument`, not per
connection), and the full ICCCM dance works at wire level:

- `SetSelectionOwner`/`GetSelectionOwner` across clients; ownership dies with
  the window/connection, with the shell reclaiming text selections while the
  bridge is attached.
- `ConvertSelection` routes a real `SelectionRequest` to the owning client,
  which answers with `ChangeProperty` + `SendEvent(SelectionNotify)` - and
  `SendEvent` now honours the ICCCM idiom: an **empty event-mask delivers to
  the destination unconditionally** (SelectionNotify/ClientMessage have no
  selectable mask; libX11 sends them with mask 0 everywhere).
- **Guest → Android**: when a client claims PRIMARY/CLIPBOARD, the server's
  internal shell connection asks that client for UTF8_STRING *over the real
  wire protocol*; the answered text is decoded (`SelectionCodec`) and pushed to
  the system clipboard (deduped against the last push).
- **Android → guest**: `DesktopScreen`'s `ClipboardBridgeEffect` listens for
  system-clipboard changes (Notepad copies, other apps), the shell reclaims
  both selections, and guest conversions are answered inline from
  `XClipboardBridge.readText()`. TARGETS answers `[TARGETS, STRING,
  UTF8_STRING, TEXT]`; rich targets (text/html, images, COMPOUND_TEXT) convert
  to an honest None instead of being guessed at.

Limits: Android has ONE clipboard - PRIMARY and CLIPBOARD both map onto it;
clipboard reads happen while the app is foregrounded (Android's own rule, and
Android 12+ shows its "pasted from" toast); the guest→Android push is eager
(on claim), so a guest that changes selection *contents* without re-claiming
does not re-push (X clients re-claim on every copy).

### Verification

The x11 suites (108 tests: `XProtocolTest`, `XDisplayListTest`, `XRedirectTest`,
`ExplorerSmokeTest`, `XCompatTest`, `XTextInjectionTest`, `XSnapshotTest`,
`XSelectionsTest`) were
**compiled and executed** with Kotlin 2.1.10/JDK 17 against minimal Android stubs
(only `LocalServerSocket`/`LocalSocket`/`Log`, which the wire tests never touch) -
the first time this project's protocol code was actually run rather than written
blind. Everything is green. The Compose/Android half (`XDesktop.kt`) still needs a
CI `assembleDebug` + device check.

## What D1 still needs

1. **Launch**: R2's `ContainerService` starts `wine64 explorer.exe /desktop=Shell,<W>x<H>`
   with `DISPLAY=:100` and `PROOT_LOADER*` from `NativeBinaries.loaderPaths()`.
   Blocked on vendoring the native binaries (R1 decision, see `docs/NATIVE_RUNTIME.md`).
   Until then, `ExplorerSmokeTest` scripts the same request sequence `explorer.exe`
   sends over the wire and asserts WM redirect, placement, Expose, pixels in the
   display list, input dispatch and WM_DELETE_WINDOW.
2. **First contact with a real client**: once binaries exist, run `xclock`/`xeyes`
   from the rootfs (`DISPLAY=:100`) before Wine - they exercise fonts, arcs,
   exposures and the WM path with a fraction of Wine's surface. `xinit` from a Linux
   box over an adb-forwarded socket is an alternative that needs no rootfs.
   The **Firefox ESR desktop-browser session** is fully plumbed on top of this
   server (`XServer.ensureRunning` exists precisely for it) - see
   `docs/BROWSER_ENGINES.md`; it becomes launchable when the readiness checks
   (R1 binaries, rootfs, Firefox install, GTK3 stack) all pass.
3. **On-device check of the D1c UI half**: software cursor position/scaling, hidden
   IME field focus behaviour with Gboard/Samsung keyboard, `onPreviewKeyEvent`
   routing with a hardware keyboard. None of this is verifiable without a device.

## Deliberate non-goals / known limits (D1c)

- RENDER is served at version 0.4 (see above); traps/solid-fills/gradients/
  ARGB cursors/transforms are not - the claim stays at 0.4 until they are.
- No Composite/XRandr/XShm/XKB/XInput2/XFIXES (`QueryExtension` answers "not
  present", so clients fall back to core protocol).
- One built-in font ("fixed", 6x13 metrics); `OpenFont` never refuses a name and
  glyph shapes are monospace approximations - `QueryFont` metrics match the
  renderer's advance, so layout maths stays consistent.
- Passive grabs by *other* connections are recorded but not activated (single-app
  server; Wine grabs on its own connection). No grab freezing, so `AllowEvents` is
  a no-op.
- Text injection covers Latin-1 (plus the printable ASCII set); CJK needs XIM/XKB.
- GetImage ignores XYBitmap/XYPixmap (serves ZPixmap) and returns background pixels
  where only text was drawn; GetImage on pixmaps needs the display-list sink.
- Pixmap cursors render as the default arrow; border widths, backing store,
  save-under, bit gravity and subwindow clipping are not modelled.
- `SendEvent` bytes are delivered verbatim: two clients with *different* byte orders
  would see a mis-ordered synthesised event (single-app reality: one order).
- KillClient is accepted but does not tear down resources (no per-client ownership
  of pixmaps/GCs yet).

## How to look at it on a device

**One-tap proof of contact: Diagnostics ▸ *Run X self-test*.** Four steps, all
inside proot against the running server (clients come from `x11-utils`/`x11-apps`,
which the Provision step installs):

1. `xdpyinfo -ext RENDER` - exits immediately; PASS requires a successful
   handshake **and** RENDER in the extension list.
2. `xwininfo -root` - the root-window query a WM-shaped client makes first.
3. `xeyes` - survives 6 s on core X11 only (window, expose, pointer).
4. `xclock` - survives 6 s on Xaw+Xft, i.e. the RENDER glyph path.

For the survivors PASS means *stayed connected* (a healthy X client never exits
by itself); any exit code is FAIL. Every failure carries the exit code, the
client's last output lines and the server's `XProtocolTrace` summary for that
connection, which names the exact extension/request the client died on. Read
the pattern: probes red = socket/proot path broken; probes green + xeyes red =
core protocol gap; xeyes green + xclock red = RENDER gap; all green = the
handshake Firefox needs is there.
Prerequisites that are missing (server not bound - open Start ▸ X Desktop once;
rootfs not extracted; `x11-apps` not installed) SKIP with the reason named.

Open **Start → X Desktop** (not pinned to the taskbar). The window starts the server on
`:100` at its own pixel size and shows "Waiting for clients" until a guest connects.
Anything that speaks core X11 to the abstract socket `@/tmp/.X11-unix/X100` — e.g.
`xclock` from a proot rootfs with `DISPLAY=:100` — appears as a shell-placed window.
Tap the surface to focus it, then type with the soft keyboard; the pointer shows the
client-chosen cursor shape.
