R1 EXEC - put the arm64-v8a native binaries here.

AGP only packages files named lib*.so from jniLibs, and targetSdk 34 only allows
exec() from nativeLibraryDir, so the binaries are shipped with a lib prefix and
executed in place (jniLibs.useLegacyPackaging = true keeps them extracted):

  libbox64.so            box64        (MIT)      required
  libproot.so            proot        (GPL-2.0)  required, exec'd as a separate process
  libproot-loader.so     proot loader (GPL-2.0)  required
  libproot-loader32.so   proot loader (GPL-2.0)  optional (32-bit guests)

Nothing is committed here yet. See docs/NATIVE_RUNTIME.md for how to obtain/build
them, and docs/THIRD_PARTY.md for licences. The Diagnostics app inside the shell
reports exactly what is present, whether it is executable, and what --version said.
