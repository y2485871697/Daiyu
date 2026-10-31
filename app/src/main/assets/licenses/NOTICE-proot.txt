# Bundled PRoot native runtime

This independent application retains these third-party native components from the
maintainer-owned build-wrapper input (historical wrapper baseline
`2a7e7ab92b98da5a3711611017021441e7a8053c`, not an upstream PRoot commit).
The original copyright statements are preserved in the accompanying license and
source files. No external host PTY binary is used; this application's PTY remains independently built.

- PRoot v5.1.107.92: GPL-2.0-or-later.
- talloc 2.4.3: LGPL-3.0-or-later, statically linked.
- libandroid-shmem v0.7: BSD-3-Clause, statically linked.
- The supplied combined PRoot executable is distributed under GPL-3.0-or-later,
  as recorded by the supplied component provenance, not under the wrapper's
  PolyForm Noncommercial license. This notice does not replace component licenses.

## Reproducible input identification

`artifacts.json` records all 12 exact source-kit paths, byte lengths and SHA-256
values. The integrator checked every file against the supplied `SHA256SUMS`
before copying, then checked the destination bytes. The four native payloads are
paired arm64-v8a and x86_64 `libproot_exec.so`/`libproot_loader.so` files.

The original corresponding source archives are packaged in the APK at
`assets/native-sources/{proot,talloc,shmem,daiyu-native-build}.tgz`.
The build archive retains `scripts/build-terminal-native.sh`, its two patches,
native build README, and version catalog; its included historical PTY source
serves source provenance only and is not compiled or installed as this app's PTY.
Extract the source/build archives in a separate build workspace and follow the
preserved native README/script. No build script is executed by installing or
opening the application.

Component license texts are packaged at `assets/licenses/GPL-3.0.txt`,
`proot-5.1.107.92.txt`, `talloc-2.4.3.txt`, and `libandroid-shmem-0.7.txt`.
This notice is also packaged at `assets/licenses/NOTICE-proot.txt`.

## Runtime and verification boundary

The supplied build script selects NDK 29.0.14206865 and Android API 34. These are
prebuilt artifacts: setting this app's minSdk to 26 or its own PTY CMake platform
to 26 does not rebuild them. The production helper provider must reject API <34
and unsupported/process-mismatched ABIs until independently rebuilt and verified.
The application minSdk remains 26; existing 32-bit Android PTY support is unchanged.

Use only this application's installed `nativeLibraryDir` files, verified against
the frozen hashes. Set `PROOT_LOADER` to the matching installed loader and
`PROOT_TMP_DIR` to an existing private writable directory. Do not copy executable
payloads into writable app storage or reuse another application's executable.
The existing `useLegacyPackaging=true` provides the native extraction configuration;
actual merged Manifest, APK inclusion, device extraction, loader execution,
page-size compatibility and supported-device Linux startup remain to be tested.
No rootfs archive is bundled, downloaded or installed by this source-only change.

The native build wrapper and host integration are the maintainer's original post-fork work. This does not change PRoot, talloc or libandroid-shmem authorship or licensing. The renamed build-source archive is recorded with its new exact digest; unchanged third-party source archives retain their original digests.
