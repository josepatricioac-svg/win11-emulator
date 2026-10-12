# R1 EXEC — native binaries: what to ship, where, and under which licence

## The execution model (implemented, unit-tested)

`targetSdk 34` refuses `exec()` from the app's data directory (W^X), so the binaries are
**not** copied out and run from there. They are packaged as `lib*.so` in
`app/src/main/jniLibs/arm64-v8a/` and **executed in place from `nativeLibraryDir`**:

```
applicationInfo.nativeLibraryDir/libproot.so   -r … -b <prefix>:/root/.wine … -- box64 … wine64 …
```

Three build settings make that work and are already in `app/build.gradle.kts`:

| Setting | Why |
| --- | --- |
| `ndk.abiFilters += "arm64-v8a"` | only 64-bit ARM is supported (box64 and the rootfs are arm64) |
| `jniLibs.useLegacyPackaging = true` | keeps the `.so` files **extracted** to `nativeLibraryDir` instead of loaded straight from the APK |
| `packaging.jniLibs` + the `lib*.so` name rule | AGP only packages `lib*.so` from `jniLibs`, hence `libproot.so` / `libbox64.so` even though they are executables |

`runtime/NativeBinaries.kt` does the rest: inventory, presence/size, `setExecutable(true)`
(some devices drop the +x bit), and a `PrepareResult` that names the exact problem
(`NO_DIRECTORY`, `MISSING_REQUIRED`, `PARTIAL`, `PERMISSION_DENIED`, `READY`).
`runtime/RuntimeProbe.kt` execs `--version` through a `ProcessRunner` seam with a **fully
explicit environment** (nothing inherited) and returns command + exit code + a 6-line log
excerpt. `runtime/ProotCommand.kt` builds the proot command line — the prefix bind mount is
**fixed at `/root/.wine`** so Wine's own absolute paths inside the prefix stay valid — and
already produces R4's three-step self-test plan.

The **Diagnostics** app (Start ▸ All apps ▸ Diagnostics) shows all of this: status card,
`nativeLibraryDir`, per-binary rows, probe results with PASS/FAIL and the failing command,
and the log excerpt.

## What is in `jniLibs/arm64-v8a/` (updated 2026-10-10)

| File | Upstream | Licence | Status |
| --- | --- | --- | --- |
| `libproot.so` | [termux/proot](https://github.com/termux/proot) tag `v5.1.107.96` (commit `a179d3e8a4e045aaa1fb8cc3284f23509d96d353`) | **GPL-2.0** | **VENDORED + pinned** (owner approved: the desktop-browser request requires it; exec'd as a separate process, never linked into app code) |
| `libproot-loader.so` | same build (`src/loader/loader`) | **GPL-2.0** | **VENDORED + pinned** |
| `libtalloc.so` | Termux `libtalloc_2.5.0_aarch64.deb` (talloc 2.5.0, samba.org) | LGPL-3.0+ | **VENDORED + pinned** — proot's only dynamic dependency; SONAME respelled `libtalloc.so.2` → `libtalloc.so` (same byte length, NUL-padded) so AGP packages it and `-l:` linking records that exact name as DT_NEEDED. Found at runtime via `LD_LIBRARY_PATH=nativeLibraryDir`, which every launch/probe environment now sets. |
| `libbox64.so` | [box64](https://github.com/ptitSeb/box64) (rename of the `box64` executable) | MIT | **NOT vendored yet** — only x86/x64 Wine payloads need it; the arm64 browser path does not (`ContainerLauncher.requiredFor()` excludes it for native guests). One `workflow_dispatch` run of `.github/workflows/build-native.yml` (default ref `v0.4.4`) builds it **and commits the binary + its SHA-256 pin to `main` automatically** — see "Running build-native" below. |
| `libproot-loader32.so` | proot's 32-bit loader | **GPL-2.0** | **not built, deliberately**: it needs an arm32 compile target and the rootfs is arm64-only. The role is optional; 32-bit guests are unsupported until someone needs WoW64. |

All three vendored files are pinned in `app/src/main/assets/native/checksums.txt`
(fail closed: unpinned/mismatched required binaries are refused, never exec'd), and the
APK build does not depend on them otherwise — CI stays green either way.

### How the vendored proot was built (exact recipe, reproduced once in a 1 GB sandbox)

Toolchain: **Android NDK r27c**, `aarch64-linux-android28-clang` (minSdk 28), binaries
stripped with `llvm-strip`.

```bash
# 1. libtalloc: Termux's prebuilt Android aarch64 package (only needs libc.so):
#    https://packages.termux.dev/apt/termux-main/pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb
#    sha256(deb) = 556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7
#    Extract data/data/com.termux/files/usr/lib/libtalloc.so.2.5.0 (+ usr/include/talloc.h),
#    then respell the SONAME in .dynstr:
#    bytes "libtalloc.so.2\0" -> "libtalloc.so\0\0\0" (exactly one occurrence).

# 2. proot (termux fork = the bionic patches upstream lacks):
git clone --branch v5.1.107.96 https://github.com/termux/proot.git
cd proot
# 2a. disable the 32-bit loader (needs an arm32 target we don't ship):
sed -i 's/^(\s*)#define HAS_LOADER_32BIT true/\1\/\/ disabled/' src/arch.h   # both occurrences
# 2b. point the makefile at the staged talloc (lib/ + include/):
sed -i 's|^LDFLAGS  += -ltalloc .*|LDFLAGS  += -L$TALLOC_OUT/lib -l:libtalloc.so -Wl,-z,noexecstack|' src/GNUmakefile
sed -i 's|^CPPFLAGS += \(.*\)$|CPPFLAGS += -DARG_MAX=131072 -I$TALLOC_OUT/include \1|' src/GNUmakefile
# 2c. add the RUNPATH so libtalloc.so resolves NEXT TO the binary (no env needed):
sed -i "s|^LDFLAGS  += -ltalloc .*|LDFLAGS  += -L$TALLOC_OUT/lib -l:libtalloc.so -Wl,-z,noexecstack '-Wl,-rpath,\$\$ORIGIN'|" src/GNUmakefile
#    (the -l:libtalloc.so form records DT_NEEDED=libtalloc.so, the vendored filename)
# 2d. put the warning suppressions + the UNBUNDLE define INTO the makefile's CFLAGS
#     line (command-line CFLAGS overrides makefile `+=` and loses the define):
#     CFLAGS += -Wall -Wextra -O2 -Wno-error -Wno-implicit-function-declaration \
#       -Wno-int-conversion -Wno-incompatible-pointer-types \
#       "-DPROOT_UNBUNDLE_LOADER=\"libproot-loader.so\""
# 2e. build (needs gawk on PATH for loader-info.awk's strtonum):
make -C src -j2 CC=aarch64-linux-android28-clang LD=aarch64-linux-android28-clang \
  STRIP=llvm-strip OBJCOPY=llvm-objcopy OBJDUMP=llvm-objdump \
  PROOT_UNBUNDLE_LOADER=libproot-loader.so
# 2d. results: src/proot -> libproot.so, src/loader/loader -> libproot-loader.so
```

`PROOT_UNBUNDLE_LOADER` means the loader is not embedded in the binary; proot finds it
through the `PROOT_LOADER` environment variable, which `NativeBinaries.loaderPaths()`
always supplies. `RUNPATH=$ORIGIN` (verified with `llvm-readelf -d`: RUNPATH `$ORIGIN`,
NEEDED `libtalloc.so`, `libdl.so`, `libc.so`) makes the libtalloc lookup work even if
`LD_LIBRARY_PATH` is stripped - the launch/probe environments set it anyway (belt and
braces). Current pins: libproot.so `43e98a71e9c10501b7984f1c6a30637fd57eac6b08627bee7b22420f0a36d957`. `VERSION` reports `v5.1.107.96-dirty` (the two patches above) — honest.
`-DARG_MAX=131072` is the bionic fix from Termux's recipe; clang-18 needed
`-Wno-error` for two implicit-declaration warnings in the fork's ashmem extension.

**GPL-2.0 compliance (proot):** the binary is exec'd as a separate process, never
linked into app code. The complete corresponding source is the public upstream tag
above plus the two mechanical patches reproduced verbatim in this file, and
`build-native.yml` re-runs an equivalent build on demand.

**box64 remains unbuilt in the dev sandbox for a hard reason:** its `src/tools/gtkclass.c`
(270 KB of tables) OOMs clang at `-O1` with ~900 MB free — the same wall hit during D1c.
The CI runner (16 GB) has no such problem, and the workflow is now wired to close the gap
without any manual file handling.

### Running `build-native` (what produces `libbox64.so`)

The workflow is **`workflow_dispatch`-only**, so it can never affect `build-apk` by itself:

- **Web UI:** GitHub → repo → **Actions** tab → **build-native** (left sidebar) →
  **Run workflow** (top right) → branch `main` → keep the defaults
  (`box64_ref=v0.4.4`, `commit_box64=true`, `target_branch=main`) → **Run workflow**.
- **CLI:** `gh workflow run build-native.yml --ref main`
  (optional overrides: `-f box64_ref=<ref> -f commit_box64=false -f target_branch=<branch>`).

What the run does:

1. **box64 job** — clones box64 at the given ref and builds it with the NDK:
   `-DANDROID=ON -DARM_DYNAREC=ON -DANDROID_PLATFORM=android-28`, `RelWithDebInfo`
   (box64's ANDROID memory hack compiles at `-O1`), `make -j2` — the job limit is
   deliberate: `gtkclass.c` needs ~1–2 GB per clang process even at `-O1`, and parallel
   `-O2` jobs are how the dev sandbox died. The binary is `llvm-strip`ped, checked with
   `llvm-readelf -h` to really be AArch64, hashed, uploaded as an artifact, and —
   unless `commit_box64=false` — **committed straight to `target_branch`**:
   `app/src/main/jniLibs/arm64-v8a/libbox64.so` plus the pin line
   `libbox64.so=<sha256>` in `app/src/main/assets/native/checksums.txt` (the fail-closed
   rule is untouched: an unpinned or mismatched binary is still never exec'd; the pin and
   the binary land in the same commit). Re-running with an unchanged result commits
   nothing. The push re-triggers `build-apk`, which validates unit tests + `assembleDebug`
   with the new binary.
   *Flag note:* `-DANDROID=ON` is required and easy to miss — NDK r27's
   `android.toolchain.cmake` no longer sets the plain `ANDROID` variable box64's
   `option()` reads. Without it, box64 configures as generic Linux: the link line asks
   for `-lrt/-lpthread/-lresolv/-lutil` (merged into bionic's libc, absent from the NDK
   sysroot → guaranteed link failure) and `gtkclass.c` compiles at `-O2` (more RAM).
2. **proot job** — reproduces the vendored proot build above (same tag, same Termux
   libtalloc with the respelled SONAME, same two patches, verified with `llvm-readelf`
   guards on `RUNPATH=$ORIGIN` / `DT_NEEDED=libtalloc.so`). **Upload-only:** proot is
   GPL-2.0, so re-vendoring it stays an owner decision — this job exists so the
   corresponding-source offer remains runnable on demand.

**First dispatch run (2026-10-12, run #1): the recipe is proven, the guard was not.**
box64 `v0.4.4` configured, compiled and linked cleanly on `ubuntu-latest` with
**NDK r30** (`30.0.16248370`, Clang 21.0.0), `-DANDROID=ON -DARM_DYNAREC=ON
-DANDROID_PLATFORM=android-28 -DCMAKE_BUILD_TYPE=RelWithDebInfo` and `make -j2` —
`[100%] Built target box64` in about two minutes, no OOM, which is the whole reason
this job exists. The step then failed with **exit code 74 and no output at all**, on
the line after the build:

```bash
"$TC/bin/llvm-readelf" -h libbox64.so | grep -q AArch64   # <- exit 74
```

`grep -q` exits at its first match and closes the pipe; `llvm-readelf` then dies
writing the rest of the header, and LLVM tools exit **74 (EX_IOERR)** on a failed
write, which `set -o pipefail` reports as the step's failure. It is a *race* — the
same pattern in the proot job passed on the same run purely because readelf finished
writing first — so both jobs now capture the header to a file, `cat` it into the log
(so a real architecture mismatch is diagnosable), and only then `grep`. Rule for every
CI script here: **never pipe an LLVM/binutils tool into `grep -q` under `pipefail`.**

Reading that log needed one more trick: the Actions log host answers a 302 to a signed
Azure blob URL, and following it *with* the GitHub `Authorization` header still
attached returns `401 Server failed to authenticate the request`. Fetch the redirect
target with no auth header and the log downloads fine.

### After the files are in place

For **box64** the workflow does steps 1–2 itself (copy + pin + provenance comment in
`checksums.txt`); `docs/THIRD_PARTY.md` already carries its entry. What is left is
manual verification on a device:

1. `./gradlew assembleDebug` → install → Start ▸ All apps ▸ **Diagnostics** ▸
   *Re-check binaries*, then *Run --version probes*.
2. Expect two PASS rows (`box64 --version`, `proot --version`). Failure modes Diagnostics
   names explicitly: `NO_DIRECTORY`, `MISSING_REQUIRED`, `PARTIAL` (what is still missing),
   `PERMISSION_DENIED` (the +x bit could not be set, typical exit 126), and the two
   fail-closed hash verdicts `CHECKSUM_MISMATCH` and `CHECKSUM_NOT_PINNED`.

For **proot** (if the owner ever decides to re-vendor): download the `proot-arm64`
artifact, copy the two files into `jniLibs/arm64-v8a/`, and re-pin their hashes in
`checksums.txt` — the same fail-closed rule applies.

**Fail closed:** a required binary that is present but has no pinned hash — or the wrong
one — is *never* exec'd. R2 must check `prepare()` before starting anything.

R2 then reuses `ProotCommand.build()` and `WineEnvironment.build()` (with
`NativeBinaries.loaderPaths()` supplying `PROOT_LOADER`/`PROOT_LOADER_32`) inside
`ContainerService`.

## Status

| Piece | State |
| --- | --- |
| Exec-from-`nativeLibraryDir` model, packaging settings | done |
| SHA-256 pinning of shipped binaries (fail closed) | done; the three vendored files are pinned in `checksums.txt` |
| `build-native.yml` (box64 build + auto-vendor, default `v0.4.4`) | fixed (`-DANDROID=ON`, `-j2`, strip + arch guard) and now **auto-commits** `libbox64.so` + its pin; the proot job reproduces the vendored termux/proot build (both verified end-to-end against NDK r27c); needs one dispatch run to produce the binary |
| Inventory / permissions / `PrepareResult` | done; `prepare(required)` is now per-launch: box64 is only required for x86/x64 guests (`ContainerLauncher.requiredFor`) |
| `ProcessRunner` seam + `--version` probes with explicit env | done; probe/self-test environments now carry `LD_LIBRARY_PATH` + `PROOT_LOADER*` (libtalloc resolution) |
| proot command line incl. fixed prefix bind + extra drives | done; `guestPrefix` parameter added for non-Wine sessions (Firefox binds `/root/.mozilla`) |
| R4's self-test plan (box64, `wine64 --version`, `wine64 cmd /c echo hello`) | plan built + tested; `proot --version` now really runs once installed; wine steps wait on box64 + wine provisioning |
| Diagnostics window | done (now shows the vendored proot set incl. libtalloc) |
| R2 launch path (validate -> command -> env -> stream -> status) | done; validates the per-guest binary set |
| R2 `ContainerService` (foreground, Stop all, kills children on destroy) | done; the Firefox session also runs through it |
| R3 download (pinned SHA-256, resume, space check, SAF import) | done; **extraction no longer needs proot** - the in-app `TarExtractor` (gzip + xz via org.tukaani) was verified against the real pinned ubuntu-base and Firefox archives (2615 files / 207 symlinks / 0 skipped; launcher + libxul intact) |
| Rootfs provisioning (GTK3/X11/fonts/CA-certs via apt inside proot) | `RootfsProvisioner` (proot `-0`, `APT::Sandbox::User=root`, dpkg `--force-unsafe-io`/`--force-confold`) + **Provision doctor** that names the first blocking prerequisite **and really execs proot** (`/bin/true`, `id`, `apt-get --version` in the same jail) so an argument/ptrace/fake-root problem is caught in seconds, with the exact command line to rerun; needs a device to run |
| R4 self-test (3 steps, verdicts, failing command + excerpt) | done, execution needs box64 + wine provisioning for the wine rows |
| The binaries themselves | **proot + loader + talloc vendored & pinned** (recipe above); **box64 pending one dispatch run** of `build-native.yml`, which commits the binary + pin automatically; loader32 deliberately not built |
