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

## What to put in `jniLibs/arm64-v8a/`

| File | Upstream | Licence | Required |
| --- | --- | --- | --- |
| `libbox64.so` | [box64](https://github.com/ptitSeb/box64) (rename of the `box64` executable) | MIT | yes |
| `libproot.so` | proot, Android fork (see below) | **GPL-2.0** | yes |
| `libproot-loader.so` | proot's ptrace loader | **GPL-2.0** | yes |
| `libproot-loader32.so` | proot's 32-bit loader | **GPL-2.0** | optional |

Nothing is committed in this repository yet — the directory holds a `README.txt` only.
Until binaries are vendored the APK contains no native payload, and Diagnostics says
"Not installed" with a pointer to this file. **The main build never depends on them**, so
CI stays green either way.

### Where the binaries come from

**Preferred: `.github/workflows/build-native.yml`** (`workflow_dispatch` only — it can
never affect `build-apk`). It builds box64 from a pinned ref with the flags below, strips
the binary, renames it to `libbox64.so`, writes `PROVENANCE-box64.txt` (upstream URL, ref,
commit, licence) and `SHA256SUMS.txt`, and uploads an artifact:

```bash
cmake -S box64 -B box64/build \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
  -DCMAKE_BUILD_TYPE=Release -DANDROID=ON \
  -DWINLATOR_GLIBC=ON \            # glibc rootfs mode: exactly this project's architecture
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DCMAKE_C_FLAGS_RELEASE="-O1 -DNDEBUG" -DCMAKE_CXX_FLAGS_RELEASE="-O1 -DNDEBUG"
```

`WINLATOR_GLIBC=ON` is box64's own option for a glibc-based container (it changes the
libc wrapping and the dynacache default). `-O1` is box64's Android cap; without it the
default `-O3` is used. Box64 default pin: **`v0.4.5-1`**, commit
`e99ca51299ec897e6a96da6d00983d90406a836f`.

The same workflow has a `proot` job that is `continue-on-error: true`, because **upstream
proot does not build against bionic**: it needs `libtalloc` (whose soname must also be
renamed to satisfy the `lib*.so` rule) plus assorted glibc-isms — which is why every
Android project carries a patched fork. Vendor proot + loaders from such a fork and record
its URL, commit, licence (GPL-2.0) and SHA-256.

I could not produce the binaries in my sandbox to prove the flags: clang runs out of
memory on `src/tools/gtkclass.c` (270 KB) even at `-O1 -g0` with ~890 MB free. The flag
set above is what that attempt validated up to the OOM, so treat the workflow as
**untested** until it has produced an artifact once. Nothing is vendored in git.

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
| SHA-256 pinning of shipped binaries (fail closed) | done, 6 unit tests |
| `build-native.yml` (opt-in box64 build, proot attempt) | written, **untested** |
| Inventory / permissions / `PrepareResult` | done, 9 unit tests |
| `ProcessRunner` seam + `--version` probes with explicit env | done, 7 unit tests |
| proot command line incl. fixed prefix bind + extra drives | done, 6 unit tests |
| R4's self-test plan (box64, `wine64 --version`, `wine64 cmd /c echo hello`) | plan built + tested, execution needs R2/R3 |
| Diagnostics window | done (UI shows real inventory/probe state) |
| R2 launch path (validate -> command -> env -> stream -> status) | done, 18 unit tests; refuses to run without binaries/rootfs |
| R2 `ContainerService` (foreground, Stop all, kills children on destroy) | done; never started in practice until binaries exist |
| R3 download (pinned SHA-256, resume, space check, SAF import) | done, 25 unit tests; extraction gated on proot |
| R4 self-test (3 steps, verdicts, failing command + excerpt) | done, 12 unit tests; can only go green once R1 binaries + R3 rootfs exist |
| The binaries themselves | **not vendored** — see above; box64 v0.4.5-1 could not be compiled in this sandbox (clang OOM) |
