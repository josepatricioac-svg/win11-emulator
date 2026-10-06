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

1. Which engine do we make the **default download** in R3 — Firefox ESR (Linux arm64) is
   my recommendation: smallest risk, best extension story, clean licence.
2. Do we pursue **Edge Windows/arm64** at all, given the proprietary licence and the D2
   dependency? (It is the only route to a Copilot sidebar.)
3. Rootfs flavour for the browser path: the Ubuntu 22.04 rootfs from R3 is enough for
   Firefox ESR; Chromium may want a newer mesa/libGL than the rootfs ships.
