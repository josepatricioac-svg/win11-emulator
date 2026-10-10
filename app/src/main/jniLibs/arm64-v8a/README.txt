R1 EXEC - the arm64-v8a native binaries live here.

AGP only packages files named lib*.so from jniLibs, and targetSdk 34 only allows
exec() from nativeLibraryDir, so the binaries are shipped with a lib prefix and
executed in place (jniLibs.useLegacyPackaging = true keeps them extracted):

  libproot.so            proot        (GPL-2.0)  VENDORED - exec'd as a separate process
  libproot-loader.so     proot loader (GPL-2.0)  VENDORED
  libtalloc.so           talloc       (LGPL-3.0+) VENDORED - proot's dynamic dependency
  libbox64.so            box64        (MIT)      NOT VENDORED YET - only x86/x64 Wine
                                                payloads need it; build it with
                                                .github/workflows/build-native.yml
  libproot-loader32.so   proot loader (GPL-2.0)  optional (32-bit guests; not built -
                                                the rootfs is arm64-only)

Every vendored file is pinned by SHA-256 in app/src/main/assets/native/checksums.txt
(fail closed: unpinned or mismatched required binaries are refused, never exec'd).
Provenance, build commands and licences: docs/THIRD_PARTY.md and docs/NATIVE_RUNTIME.md.
The Diagnostics app inside the shell reports what is present, executable, pinned,
and what --version said.
