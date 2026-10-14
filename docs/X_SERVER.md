# D1 — the in-app X server: status and what is left

Goal (D1): the app owns an X server on a private unix socket, one fullscreen native
surface runs `explorer.exe /desktop`, and touch/mouse/keyboard become X input.
No Termux, no XSDL, no adb.

**Milestone status:** D1a (protocol core + socket) done, D1b (renderer + input + WM)
done, D1c (real-client compatibility) done, D1d (RENDER 0.4) done, D1e (the
transport + XInput2 GTK3 needs) done, D1f (XFIXES 5.0 + SYNC 3.1) done, **D1g
(the pass-through desktop session: parent-relative coordinates, viewability,
Expose-on-map, ReparentWindow placement, frame clipping) done**. What remains
for D1 is the guest launch itself: the R1 binaries are vendored, so that is now
a device-provisioning step (Goal 2 step 2), not server code.

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

### XInput2 (XI2) - the input extension GTK3 runs on

`QueryExtension("XInputExtension")` now answers **present** (major opcode 131,
event base 65, error base 165 - after RENDER's five) at **version 2.2**.

Why this is the one that decides whether Firefox is usable rather than merely
visible: GDK3 asks for XI 2.4 at display-open time
(`_gdk_x11_device_manager_new` calls `XIQueryVersion(2, 4)`) and only falls back
to its legacy *core* device manager when the server answers `BadRequest`. The
core fallback still draws, but it is one pointer, no device model and no XI
grabs - the compatibility path GTK keeps alive and nothing else. With XI2 the
shell gets the input model a desktop browser actually expects.

**The version claim is 2.2, not 2.4.** GDK gates exactly two things on the minor
version: touch (2.2) and touchpad gestures (2.4). We report **no touch and no
gesture devices**, so a client that selects `XI_TouchBegin` simply never receives
one - Android touch arrives as single-pointer `XI_Motion`/`XI_ButtonPress` from
`XInput`, exactly as on the core path. Claiming 2.4 would invite gesture
selections we cannot honour. Real multi-touch (`XI_TouchBegin/Update/End` with
touch ids, i.e. pinch-zoom in Firefox) is the named next step and needs the
Android pointer-index plumbing in `XDesktop`, not protocol work.

**The device set is static** - one master pair with the slave pair behind it,
which is what a single-pointer server honestly has:

| id | name | use | attachment | classes |
| --- | --- | --- | --- | --- |
| 2 | Virtual core pointer | MasterPointer | 3 | Button (9), Valuator 0 "Rel X", Valuator 1 "Rel Y" |
| 3 | Virtual core keyboard | MasterKeyboard | 2 | Key (keycodes 8-255) |
| 4 | win11we touch pointer | SlavePointer | 2 | as the master pointer (source of pointer events) |
| 5 | win11we keyboard | SlaveKeyboard | 3 | as the master keyboard (source of key events) |

The valuators are deliberately **relative** ("Rel X"/"Rel Y"): GDK maps only the
"Abs \*" labels onto axes and ignores the rest, and pointer position travels in
every event's `root_x`/`event_x`, so an ignored axis loses nothing. Events report
`deviceid` = the master and `sourceid` = the slave, the way Xorg reports attached
slaves.

**Requests implemented** (minors from `XI2proto.h`): QueryVersion (47),
QueryDevice (48), SelectEvents (46), GetSelectedEvents (60), QueryPointer (40),
WarpPointer (41), ChangeCursor (42 - which is what libXi's
`XIDefineCursor`/`XIUndefineCursor` send, and what GDK calls), SetFocus (49),
GetFocus (50), GrabDevice (51), UngrabDevice (52), AllowEvents (53, accepted: we
never freeze a device), PassiveGrabDevice (54) and PassiveUngrabDevice (55) for
button and keycode grabs (they land in the same passive-grab table the core
`GrabButton`/`GrabKey` use, so activation and release behave identically),
SetClientPointer (44), GetClientPointer (45), and the device-property quartet
ListProperties (56), ChangeProperty (57, Replace/Prepend/Append), DeleteProperty
(58), GetProperty (59 - an absent property answers type `None` with zero items,
which is what GDK's vendor/libinput/Wacom probes expect, not an error).

**Refused on purpose, and named in the trace** rather than accepted-and-ignored,
because a Success answer would be a lie a client could act on: ChangeHierarchy
(43 - our device set is static, there is no hotplug to model),
BarrierReleasePointer (61 - we have no pointer barriers and never send barrier
events), and the XI **1.x** minors 1-39 (a different protocol that shares this
major opcode; `xinput list`-style clients would misread our answers). Every
refusal is `BadRequest` with the minor opcode in the error and a
`XI2.<RequestName>` entry in the protocol report.

**Events delivered** - all as GenericEvents (`deliverGenericEvent`), all to the
connection that selected them:

- `XI_KeyPress`/`XI_KeyRelease` (2/3) to the focus window, `XI_ButtonPress`/
  `XI_ButtonRelease` (4/5) and `XI_Motion` (6) to the window under the pointer,
  each with FP1616 `root_x/root_y` and window-relative `event_x/event_y`, the
  32-bit button mask (core's Button1Motion bit 8 becomes XI2's bit 0, and
  Android's back/forward buttons 8/9 survive as bits 7/8), and the modifier state
  split the way `xXIModifierInfo` wants it (base = held down, locked = CapsLock,
  effective = both).
- `XI_Enter`/`XI_Leave` (7/8) on pointer crossings with X's detail rules relative
  to the event window (Inferior when the other window is below it, Ancestor when
  above, Nonlinear between two toplevels - which is every GTK toplevel crossing),
  and `XI_FocusIn`/`XI_FocusOut` (9/10) whenever the focus moves, with the
  `focus` flag set for the window that really has it.
- Not delivered: touch, gestures, `XI_Raw*`, `XI_HierarchyChanged`,
  `XI_DeviceChanged`, `XI_PropertyEvent`, barrier events - none of which can
  happen with a static four-device set and no raw-event source.

**Three rules that make this correct rather than merely present:**

1. *XI2 delivery is independent of the core window masks.* A client that selected
   XI2 events - which is all GDK does; it selects **no** core masks - still
   receives them. The XI2 hook therefore runs before the core `eventMask` check in
   every input path, including `warpMove` (a warp is a real move: X promises
   MotionNotify, XI2 clients get `XI_Motion`).
2. *Selections are per-connection*, so events go only to the client that asked -
   and they propagate up the window tree to the first ancestor that selected the
   type, with `child` naming the window the pointer is really over. That is how
   GTK, selecting on its toplevel, receives events from its children.
3. *An XI2 grab installs a core grab with an EMPTY core mask.* The grab itself is
   server-wide (every other client stops receiving these events, as X requires),
   but GDK translates **core** events through the same XI2 translator - so a
   permissive core mask would hand it a second copy of every click it already
   received as an XI2 event. The XI2 mask is kept separately and filters the XI2
   path; `owner_events` still adds the normal delivery to the window under the
   pointer. `XIGetSelectedEvents`, `XIQueryPointer`, grab status codes
   (`Success`/`AlreadyGrabbed`) and the destroy-window cleanup of selections are
   all covered by wire tests.

Still absent (honestly reported by `QueryExtension`): XKB, RANDR, MIT-SHM,
COMPOSITE, DAMAGE, SHAPE. **XFIXES and SYNC are served (D1f, next section).**
Device traces from real Firefox/GTK3 handshakes decide which of the remaining
ones matters next.

### The transport every other extension rides on: BIG-REQUESTS + Generic Events

Two extensions carry no drawing or input semantics of their own, but nothing
bigger than a toy client works without them, so they are implemented first:

- **BIG-REQUESTS** (major opcode 132, one request: `Enable`). A normal request
  header holds its length as a CARD16 in 4-byte units - a 64 KiB ceiling that
  cairo/GTK blow through with a single window-sized `PutImage` or a long
  `SetClipRectangles`. libX11 then re-sends it in the extended form (`xBigReq`):
  the CARD16 length is **0**, the real length is a CARD32 at offset 4 and the body
  starts at offset 8. `Enable` answers with the largest request we accept
  (4 MiB - far above anything cairo produces for a window-sized buffer, and small
  enough to keep one connection's reassembly buffer bounded on a phone; Xorg
  answers 16 MiB). Requests are reassembled across socket reads, and a big
  request is **normalised back to the ordinary 4-byte-header form** before
  dispatch: every handler addresses its payload from absolute offsets
  (ChangeProperty's data at 24, SendEvent's event at 12, ImageText8's string at
  16), so one array shape keeps all of them correct for big and small requests
  alike, and no handler reads the CARD16 length field.
  A CARD16 length of 0 **without** `Enable` first is a protocol violation: the
  server sends `BadLength`, stops parsing that stream (continuing would read one
  request as many) and records the reason in the protocol trace. A big request
  larger than the advertised maximum is refused the same way rather than buffered.
- **Generic Event Extension** (major opcode 128, one request: `QueryVersion` ->
  1.0). Core events are exactly 32 bytes; XInput2's device events are not (they
  carry valuator masks, modifier/group state and a 16-bit event type). `xGenericEvent`
  is the agreed framing - byte 0 = 35, byte 1 = the owning extension's major
  opcode, bytes 4-7 = length in 4-byte units *after* the first 32 bytes, bytes
  8-9 = that extension's event type - and libX11 parses it itself, so a client
  that never enabled anything still receives XI2 events correctly.
  `XConnection.deliverGenericEvent()` is the one place that framing is written;
  it pads to a 4-byte boundary and declares exactly the units it carries.

The extension registry (`XConnection.EXTENSIONS`) keeps each name's canonical
spelling - `xdpyinfo` prints those - and lookups are case-insensitive, because
clients are not consistent about it.

### XFIXES 5.0 + SYNC 3.1 (D1f) - what GTK3 watches, what resizes sync with

**XFIXES** (major opcode 133, event base 66, error base 170: BadRegion,
BadBarrier) is the extension GDK3's clipboard machinery switches to when it is
there: `XFixesSelectSelectionInput` on PRIMARY/CLIPBOARD turns paste-side
refreshes into events (`owner-change`), which is how Firefox notices the shell's
Android-clipboard reclaims instead of holding stale ownership. Implemented:

- **QueryVersion answers 5.0, deliberately not 6.0** - the same rule as RENDER's
  0.4: v6 adds pointer barriers and the client-disconnect mode we refuse, and a
  BadRequest on something a client believed available is how GTK dies. The v6
  tail is still refused *and named* in the trace if something sends it anyway.
- **Selection watches with real event delivery**: SelectionNotify (event 66)
  with the SetSelectionOwner / SelectionWindowDestroy / SelectionClientClose
  subtypes fires from *every* ownership path - client claims, window destroys,
  connection closes, and the shell's own reclaims (which, in real X, are
  ordinary SetSelectionOwner round trips and must be visible). Watches remember
  the *selecting connection*, so GTK's root-window watch works even though the
  root has no owner; masks filter subtypes and mask 0 unregisters.
- **Cursor watches + names + images**: SelectCursorInput delivers CursorNotify
  (event 67) when a watched window's CWCursor changes (serial bumps);
  SetCursorName/GetCursorName/ChangeCursor(ByName) manage real named cursor
  resources; GetCursorImage(AndName) rasterises the built-in 16x16 glyph art
  (the same shapes the software cursor draws - honest approximation, documented
  in `XFixesCursorArt`); HideCursor/ShowCursor is honoured by the X Desktop
  surface (`effectiveCursorGlyph` answers `CURSOR_HIDDEN`, the renderer skips).
- **Real region algebra** (`XRegionOps`, pure + unit-tested): union, intersect,
  subtract (slab splitting), invert against bounds, translate, expand, extents,
  normalised (dedupe + vertical merge) so FetchRegion answers are stable.
  CreateRegionFromBitmap scans the *recorded ops* - not a snapshot, because the
  display list decodes a set depth-1 bit to opaque black, indistinguishable
  from its background - and enforces the spec's depth-1 rule (BadMatch
  otherwise); FromWindow answers bounding and clip (siblings above subtracted);
  FromGC returns the stored clip or the screen extent; FromPicture the picture
  clip or its drawable extent.
- **The two wirings that have visible effects**: SetPictureClipRegion feeds the
  *enforced* RENDER picture clip (absolute dst-space rects, the storage
  SetPictureClipRectangles uses), and SetWindowShapeRegion's **Input** kind
  feeds hit-testing (`XWindow.inputShapeAllows` inside `windowAt`).

Honest limits (all named here rather than discovered on a device): bounding/clip
window shapes are stored and round-trip but the renderer does not clip visually
(the same documented class as backing-store/save-under); SetGCClipRegion stores
the clip for CreateRegionFromGC round-trips but core drawing does not apply GC
clips - exactly the pre-existing core `SetClipRectangles` limit ("accepted, not
rendered"); timestamps are truncated epoch millis (no server-start epoch is
tracked); Copy/CursorNotify fire on attribute changes, not on pointer motion
into a differently-cursored window (Xorg fires there too - a refinement for a
device trace to demand, not a guess).

**SYNC** (major opcode 134, event base 68: AlarmNotify/CounterNotify, error
base 171: BadCounter, BadAlarm, BadIdle) is the frame-synchronisation half GDK3
and Wine's X11 driver ask for. Implemented: **Initialize negotiates 3.1**;
ListSystemCounters honestly reports none (clients create their own); INT64
counters (Create/Set/Change/Query/Destroy) with full 64-bit range; **held
Await** - satisfied-now conditions reply immediately, unsatisfied ones are
queued and answered the moment a counter change satisfies *every* condition,
with the reply stamped with the AWAIT request's own sequence number (the
observable behaviour of a blocking Await for a pipelining client); the spec's
transition (edge, evaluated against the old->new pair) versus comparison
(level) semantics, and relative test-types resolved at Await time; alarms
(Create/Change/Query/Destroy, value lists in mask bit order) fire AlarmNotify
(32 bytes: counter_value and alarm_value as INT64 hi/lo pairs, state at
trigger) when events are requested, and their deltas re-adjust the counter
after firing; priorities are accepted and honestly zero.

Refused and named rather than faked: **fences** (SYNC 3.0 GPU sync objects -
there is no GL/DRI path they could mean anything on) answer BadRequest with a
`SYNC.CreateFence`-style trace entry. CounterNotify streaming (Await's
event_threshold) is not delivered - Await's reply is the contract clients use.
Alarm deltas apply after evaluation without re-entry, so a notify carries the
pre-delta counter value (a documented simplification of the spec's ordering;
the delta itself is part of the alarm the client holds). Counters, alarms and
held awaits die with their connection (`dropSyncOf`), per X's resource rules.

### The pass-through desktop session (D1g) - the whole desktop lives in the surface

The device verdict on the first Firefox ESR attempts (client connected - the
software cursor proved it - but no window ever appeared) forced the model
question, and the answer reshaped the server: **the desktop is the guest's, not
the shell's.** One fullscreen surface shows a complete X session; the shell
never places guest windows itself.

- **No shell SubstructureRedirect.** The claim is exclusive in X (second
  selector = Access error), so a shell WM would lock every guest window
  manager out. `XServer.ensureRunning` and the X Desktop surface no longer
  attach `ShellWindowManager`; the session brings its own WM (openbox for the
  browser session, `explorer.exe /desktop` for Wine later). Without a WM,
  clients map undecorated and still work. `ShellWindowManager` stays for tests
  and a possible future per-window mode - it is no longer wired in production.
- **Parent-relative coordinates, resolved through `XDocument.absoluteOrigin`.**
  X child windows position against their parent; the model used to treat every
  x/y as absolute, which only works while nothing reparents. A WM frame is
  exactly that: openbox reparents each client to (border, border+title) inside
  its frame. Hit-testing (`windowAt`), core event coordinates
  (`pointerEvent`, `enterLeaveEvent`), XI2 device/crossing events,
  TranslateCoordinates, warp bases and grab confine clamps, XTest FakeInput
  bases, and the XFIXES region/shape conversions all resolve absolute origins
  now. `GetGeometry` and `ConfigureNotify` keep the spec's parent-relative
  answers (they were already correct under the new model).
- **Mapped vs viewable are different states** (they were conflated): a child
  mapped under an unmapped parent RECORDS its map (real X semantics) and
  becomes viewable when the ancestor maps; `paintOrder` stops at unmapped
  ancestors, so shadowed subtrees neither draw nor take input.
- **Expose on becoming viewable.** A direct (non-WM) `MapWindow` - which is
  how openbox maps its clients and how unmanaged clients map - now generates
  Expose for the window and its already-mapped descendants, through the
  windows' own connections. Without it a GTK3 client that has not painted yet
  waits on a black window: the first-paint trigger the in-process
  `wmMapWindow` path always had, now on the wire path too. Expose rects are
  window-relative (0,0,w,h) per spec - they used to carry absolute offsets.
- **ReparentWindow repositions.** The request's (x,y) were dropped on the
  floor; the spec places the window at (x,y) inside the new parent, and a
  frame flow waits on the resulting ConfigureNotify. Both happen now.
- **The renderer clips and honours InputOnly.** Each window draws at its
  absolute origin, clipped to every ancestor's rect (that is what makes a
  frame clip its client, and what keeps a child hanging outside its parent
  from bleeding over the desktop); depth-0 InputOnly windows - GTK3's
  invisible input surfaces - no longer paint black holes.

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

The x11 suites (264 tests: `XProtocolTest`, `XCompatTest`, `XRenderTest`,
`XBigRequestsTest`, `XInput2Test`, `XProtocolTraceTest`, `XRedirectTest`,
`XTextInjectionTest`, `XSnapshotTest`, `XSelectionsTest`, `XFixesTest`,
`XSyncTest`, `XChildCoordinatesTest`) are
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
- No Composite/XRandr/XShm/XKB (`QueryExtension` answers "not present", so
  clients fall back to core protocol). BIG-REQUESTS, the Generic Event
  Extension, **XInput2**, **XFIXES** (5.0) and **SYNC** (3.1) *are* served.
- XFIXES: window bounding/clip shapes store but do not clip the renderer; GC
  clips store but are not applied to core drawing (the pre-existing core
  SetClipRectangles limit); timestamps are truncated epoch millis.
- SYNC: no CounterNotify streaming, no fences (refused + named), alarm deltas
  apply after the notify without re-entry.
- XI2 reports no touch and no gesture devices (version claim 2.2, not 2.4): one
  pointer, no pinch-zoom. Touch arrives as emulated single-pointer events.
- XI2 device properties are per-connection storage, not shared server state, and
  `XIChangeHierarchy` is refused - the device set is fixed at four devices.
- A per-device cursor (`XIChangeCursor`) sets the window cursor: with one pointer
  there is no per-device sprite to keep apart.
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
  save-under and bit gravity are not modelled. Subwindow clipping IS (D1g):
  the renderer clips every window to its ancestor chain - which is what makes
  WM frames clip their clients.
- `SendEvent` bytes are delivered verbatim: two clients with *different* byte orders
  would see a mis-ordered synthesised event (single-app reality: one order).
- KillClient is accepted but does not tear down resources (no per-client ownership
  of pixmaps/GCs yet).

## How to look at it on a device

**One-tap proof of contact: Diagnostics ▸ *Run X self-test*.** Five steps, all
inside proot against the running server (clients come from `x11-utils`,
`x11-apps` and `libgtk-3-bin`, which the Provision step installs):

1. `xdpyinfo -ext RENDER` - exits immediately; PASS requires a successful
   handshake **and** RENDER in the extension list.
2. `xwininfo -root` - the root-window query a WM-shaped client makes first.
3. `gtk-query-settings` - **the step that decides whether Firefox can start.**
   xeyes and xclock are core X11 and Xaw, so both can pass while GTK3 - the
   toolkit Firefox is built on - still dies opening the display. This calls
   `gtk_init()`, which opens a GDK display (XI2, the keymap, XSETTINGS, the font
   stack) and aborts with GTK's own message when it cannot. PASS is exit 0. On
   failure the row quotes GTK's `(Details: serial … error_code … request_code …
   minor_code …)` line - the request code names exactly what to implement next -
   and the connection's protocol trace rides along. It takes Xlib's `-display`
   flag away (GTK parses `--display=`, and reads `DISPLAY` from the environment)
   and gets its own 90 s timeout, because a first run builds the fontconfig
   cache inside the rootfs.
4. `xeyes` - survives 6 s on core X11 only (window, expose, pointer).
5. `xclock` - survives 6 s on Xaw+Xft, i.e. the RENDER glyph path.

For the survivors PASS means *stayed connected* (a healthy X client never exits
by itself); any exit code is FAIL. Every failure carries the exit code, the
client's last output lines and the server's `XProtocolTrace` summary for that
connection, which names the exact extension/request the client died on. Read
the pattern: handshake probes red = socket/proot path broken; probes green +
xeyes red = core protocol gap; xeyes green + xclock red = RENDER gap; all of
those green + `gtk3-display` red = a GTK3/XI2/XKB gap, and GTK's own
`request_code` in the row says which; **all five green = the handshake Firefox
needs is there.**
Prerequisites that are missing (server not bound - open Start ▸ X Desktop once;
rootfs not extracted; `x11-apps`/`libgtk-3-bin` not installed) SKIP with the
reason named, so a missing diagnostic never reads as a red run.

The whole self-test runs on a guarded worker thread, not on the UI thread: the
chips are click handlers, and five proot launches (one of which may spend a
minute building a font cache) on the main thread is an ANR - the chip could not
even repaint to "Testing..." while it blocked. The same is true of the
`--version` probes and the R4 self-test. Any throwable becomes a shared-log line
instead of taking the process down (crash-safety rules 1 and 2).

Open **Start → X Desktop** (not pinned to the taskbar). The window starts the server on
`:100` at its own pixel size and shows "Waiting for clients" until a guest connects.
Anything that speaks core X11 to the abstract socket `@/tmp/.X11-unix/X100` — e.g.
`xclock` from a proot rootfs with `DISPLAY=:100` — appears in the desktop surface
(unmanaged, or framed once the session's window manager is up; D1g pass-through).
Tap the surface to focus it, then type with the soft keyboard; the pointer shows the
client-chosen cursor shape.

## Lifecycle: one window, one process (this pass)

The server has never been a separate app - it is the in-process `XServer` object,
and this section pins the *contract* around it (implemented in
`runtime/SessionLifecycle.kt`, wired in `MainActivity`/`DesktopScreen`):

- **Boot**: with Settings ▸ Containers ▸ *Boot into X Desktop session* on (the
  default), the shell calls `SessionLifecycle.boot` → `XServer.ensureRunning()`
  and opens the X Desktop window **maximized**: the emulator starts, the desktop
  is inside it, and there is exactly one window (the single `MainActivity`).
  Every other entry point that needs the display (the X Desktop surface itself,
  `DesktopBrowserLauncher`) calls the same idempotent ensure - guests can never
  race a server that is not listening yet.
- **Resize**: `XDesktopState.onSurfaceSize` now keeps the X screen locked to the
  surface: rotation or a window resize calls `XServer.resizeScreen`, which
  re-lays the root, keeps clients alive and broadcasts a root `ConfigureNotify`
  to every connection - the no-RANDR substitute a guest WM (openbox) needs to
  re-place its windows. Previously the screen size was frozen at first start and
  the renderer letterboxed forever after.
- **Stop** (`MainActivity.onDestroy`; rotation does not destroy the Activity -
  the manifest declares the configChanges): the order is fixed and unit-tested:
  1. bounded graceful `wineserver -k` for every RUNNING Wine session (the
     registry flush that keeps prefixes un-corrupted - see `docs/WINE_RUNTIME.md`),
  2. `ContainerLauncher.stopAll` - destroying the proot children takes the whole
     guest tree with them (`--kill-on-exit`),
  3. `XServer.stop()` last, so no client can reconnect into a half-torn-down
     display; `stop()` releases grabs/selections/redirects and closes the socket.
  Every step is guarded: one failing step never strands the ones after it, and
  the report (`guests stopped: yes, X server stopped: yes, wineserver kills: n/m`)
  goes to logcat + the shared log.
- **"Stop all"** (notification / Container Manager) stops guests only and keeps
  the display alive - the emulator window is still open in that case. If the
  process itself is killed, the in-process server dies with it and Android reaps
  the guest processes via the app cgroup (plus `--kill-on-exit`), so there is no
  orphan path.
- **IPC**: unchanged and internal - the private abstract unix socket
  `@/tmp/.X11-unix/X100` (`DISPLAY=:100`, chosen over `:0` to avoid colliding
  with the conventional root display; proot passes the abstract namespace
  through untouched). Input flows Compose → `XInput` → connections in-process;
  the clipboard bridge ties PRIMARY/CLIPBOARD to Android's clipboard.
