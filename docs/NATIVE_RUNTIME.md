# R1 EXEC — native binaries: what to ship, where, and under which licence

## The execution model (implemented, unit-tested)

`targetSdk 34` refuses `exec()` from the app's data directory (W^X), so the binaries are
**not** copied out and run from there. They are packaged as `lib*.so` in
`app/src/main/jniLibs/arm64-v8a/` and **executed in place from `nativeLibraryDir`**:

```
applicationInfo.nativeLibraryDir/libproot.so   --rootfs … --bind <prefix>:/root/.wine … -- box64 … wine64 …
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
| `libbox64.so` | [box64](https://github.com/ptitSeb/box64) (rename of the `box64` executable) | MIT | **NOT vendored yet** — only x86/x64 Wine payloads need it; the arm64 browser path does not (`ContainerLauncher.requiredFor()` excludes it for native guests). Build it with `.github/workflows/build-native.yml` (default ref now `v0.4.4`). |
| `libproot-loader32.so` | proot's 32-bit loader | **GPL-2.0** | **not built, deliberately**: it needs an arm32 compile target and the rootfs is arm64-only. The role is optional; 32-bit guests are unsupported until someone needs WoW64. |

All three vendored files are pinned in `app/src/main/assets/native/checksums.txt`
(fail closed: unpinned/mismatched required binaries are refused, never exec'd), and the
APK build does not depend on them otherwise — CI stays green either way.

### How the vendored proot was built (exact recipe, reproduced once in a 1 GB sandbox)

Toolchain: **Android NDK r27c**, `aarch64-linux-android28-clang` (minSdk 28), binaries
stripped with `llvm-strip`.

```bash
# 1. libtalloc: Termux's prebuilt Android aarch64 package (only needs libc.so):
#    https://packages.termux.dev/apt/termux-main/pool/main/libtalloc/libtalloc_2.5.0_aarch64.deb
#    sha256(deb) = 556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7
#    Extract usr/lib/libtalloc.so.2.5.0, then respell the SONAME in .dynstr:
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

**box64 remains unbuilt here for a hard reason:** its `src/tools/gtkclass.c` (270 KB of
tables) OOMs clang at `-O1` with ~900 MB free — the same wall hit during D1c. The CI
runner (16 GB) has no such problem; the workflow renames the output to `libbox64.so` and
writes `SHA256SUMS.txt` for pinning.

### After the files are in place

1. Copy them into `app/src/main/jniLibs/arm64-v8a/` (already named `lib*.so`).
2. Pin their hashes in `app/src/main/assets/native/checksums.txt`:
   `libbox64.so=<sha256>` (one per line, `#` comments allowed).
3. Record provenance + licence in `docs/THIRD_PARTY.md`.
4. `./gradlew assembleDebug` → install → Start ▸ All apps ▸ **Diagnostics** ▸
   *Re-check binaries*, then *Run --version probes*.

Expect two PASS rows (`box64 --version`, `proot --version`). Failure modes Diagnostics
names explicitly: `NO_DIRECTORY`, `MISSING_REQUIRED`, `PARTIAL` (what is still missing),
`PERMISSION_DENIED` (the +x bit could not be set, typical exit 126), and the two
fail-closed hash verdicts `CHECKSUM_MISMATCH` and `CHECKSUM_NOT_PINNED`.

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
| `build-native.yml` (box64 build, default `v0.4.4`) | updated with the flags validated by the D1c build attempt; still needs one CI run to produce the artifact |
| Inventory / permissions / `PrepareResult` | done; `prepare(required)` is now per-launch: box64 is only required for x86/x64 guests (`ContainerLauncher.requiredFor`) |
| `ProcessRunner` seam + `--version` probes with explicit env | done; probe/self-test environments now carry `LD_LIBRARY_PATH` + `PROOT_LOADER*` (libtalloc resolution) |
| proot command line incl. fixed prefix bind + extra drives | done; `guestPrefix` parameter added for non-Wine sessions (Firefox binds `/root/.mozilla`) |
| R4's self-test plan (box64, `wine64 --version`, `wine64 cmd /c echo hello`) | plan built + tested; `proot --version` now really runs once installed; wine steps wait on box64 + wine provisioning |
| Diagnostics window | done (now shows the vendored proot set incl. libtalloc) |
| R2 launch path (validate -> command -> env -> stream -> status) | done; validates the per-guest binary set |
| R2 `ContainerService` (foreground, Stop all, kills children on destroy) | done; the Firefox session also runs through it |
| R3 download (pinned SHA-256, resume, space check, SAF import) | done; **extraction no longer needs proot** - the in-app `TarExtractor` (gzip + xz via org.tukaani) was verified against the real pinned ubuntu-base and Firefox archives (2615 files / 207 symlinks / 0 skipped; launcher + libxul intact) |
| Rootfs provisioning (GTK3/X11/fonts/CA-certs via apt inside proot) | `RootfsProvisioner` + Settings ▸ Runtime download action; needs a device to run |
| R4 self-test (3 steps, verdicts, failing command + excerpt) | done, execution needs box64 + wine provisioning for the wine rows |
| The binaries themselves | **proot + loader + talloc vendored & pinned** (recipe above); **box64 pending one CI run** of `build-native.yml`; loader32 deliberately not built |
