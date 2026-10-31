# Linux installer integration boundary (R09 — production extraction)

## Implemented scope and source evidence

`terminal/install/` now contains the files-only transaction, explicit approval and single-URI
transport, the immutable `LinuxRootfsCatalog`, and real `TarLinuxRootfsExtraction` for tar.gz
and tar.xz. `RootlessLinuxInstaller.forApp(context, parentRunner)` selects that production
extractor. An explicit extraction overload remains for audited integrations and tests.
The artifact constructor is internal: this is not an arbitrary user-URL download feature.

The supplied source research identifies the authorized input root:

`/workspace/daiyu-coordination/main-01/backend-kit-v1/maintainer-backend-input-v1/sources/maintainer-owned/`

with baseline `2a7e7ab92b98da5a3711611017021441e7a8053c`, specifically
`app/src/main/kotlin/[historical-package-omitted]/agent/terminal/AlpineEnvironmentInstaller.kt`,
`DebianEnvironmentInstaller.kt`, `RootlessLinuxInstaller.kt` and `VerifiedArtifactDownloader.kt`.
The four metadata entries below are the fixed values supplied from that source research, not
newly discovered downloads. The extractor is independently implemented against the supplied
algorithm/security contract; no claim is made that the original full source was re-read by
this editing agent. The user has authorized migration/modification/distribution of the supplied
backend; original PolyForm notices and independent library licenses remain applicable (see
[installer notice](../THIRD_PARTY/linux-installer/NOTICE.md)). No old installBase, environment
registry, auto-mirror logic, root shell, profiles or host identity/runtime were migrated.

| Payload | Android ABI / archive architecture | Bytes | SHA-256 | Strip |
| --- | --- | ---: | --- | ---: |
| Alpine 3.24.1 | arm64-v8a / aarch64 | 4023732 | `f55a90f69052c5bd6f92cb09a8f47065970830b194c917a006fb94028e721259` | 0 |
| Alpine 3.24.1 | x86_64 / x86_64 | 3698422 | `41f73e3cf5fa919b8aa5ca6b30dc48f0da2720776d7423e2a7748211456fe081` | 0 |
| Debian 13, Termux proot-distro v4.29.0 | arm64-v8a / aarch64 | 35409704 | `3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918` | 1 |
| Debian 13, Termux proot-distro v4.29.0 | x86_64 / x86_64 | 36728936 | `4b8f33b80a10d734ff935e5934588572f860c0c38a68bf91db59af0580370716` | 1 |

For architecture `<arch>`, Alpine filenames are
`alpine-minirootfs-3.24.1-<arch>.tar.gz` under
`https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/<arch>/`, with explicitly selectable
`https://mirrors.aliyun.com/alpine/v3.24/releases/<arch>/` mirror.
Debian filenames are `debian-trixie-<arch>-pd-v4.29.0.tar.xz` under
`https://github.com/termux/proot-distro/releases/download/v4.29.0/`, with explicitly selectable
mirror formed by prefixing the complete primary URL with `https://gh-proxy.com/`.
These Debian artifacts are **Termux proot-distro release artifacts, not official Debian rootfs**.
There is no 32-bit entry or architecture fallback. Selection performs no network requests.
The existing no-redirect policy is unchanged, including for GitHub: if an approved URL redirects,
installation fails closed; these pinned URLs are not proof of present download availability.

New pinned dependencies are `org.apache.commons:commons-compress:1.28.0` and
`org.tukaani:xz:1.12`. Gzip uses `java.util.zip.GZIPInputStream`; XZ uses
`XZInputStream(raw, 128 * 1024)` (the parameter is KiB). Transport remains `HttpURLConnection`;
no OkHttp or helper binaries were added. PRoot/loader hashes must still come from the trusted
parent, not from invented installer constants.

## Parent integration contract

1. Select one of the four entries from `LinuxRootfsCatalog` and one of its explicit mirrors.
   It is only transport metadata, **not** a second installed-environment registry.
2. Use `RootlessLinuxInstaller.forApp(context, parentRunner)`.
   It fixes the production base to `File(context.filesDir, "linux-installations")`. There is no
   public arbitrary storage-root constructor. Never pass SAF/shared storage through any test
   seam or change the target to a path from a model/tool/user selection.
3. Parent supplies already trusted, hash-pinned PRoot and loader files. Build a `LinuxInstallation`
   with fresh, bounded `environmentId` and `generation`, an exact target
   `filesDir/linux-installations/<environmentId>/<generation>/rootfs`, `guestWorkingDirectory=/`,
   and `sharedMounts=emptyMap()`. The installer verifies helper hashes before invoking the runner.
   It does not download or replace helpers and never runs host `su` or chroot.
4. `prepare(artifact, mirrorId, installation)` is pure. Display the **exact** artifact version,
   ABI, SHA-256, byte size, mirror URL, target generation/path, and fact that a PRoot probe will
   run. Preparation and merely minting a confirmation target do not read disk or open a connection.
5. Only after explicit user/authorized-parent confirmation call
   `approveDownloadAndInstall(prepared)`. This makes a one-shot in-memory capability. Do not
   infer approval from an existing directory, selected distro, previous app session, catalog
   entry or cached UI value. A capability cannot be transferred to another installer instance.
6. Call blocking `install(approval, cancellation)` on a parent-owned IO worker. Link parent
   cancellation/lifecycle teardown directly to **`cancellation.cancel()`**. Cancelling an
   unrelated coroutine alone does not stop this blocking API; do not omit the bridge. The
   downloader registers `HttpURLConnection.disconnect()` and closes the active input stream.
   The injected extraction/runner must also honor the token and stop their own work/processes.
7. `LinuxInstallProbeRunner` receives `TerminalConfiguration(backend="linux", root=false)`
   with an explicit `LinuxBackend`, pinned launcher/loader, private staging root, empty mounts,
   and the fixed command `id -u`. Use the existing parent execution runner with bounded output,
   timeout and genuine process cancellation. Do not decide the backend from path strings,
   change it to Android shell, execute the guest shell directly, or ignore the supplied config.
   The result must exit 0 and report guest UID 0; this does **not** grant Android host root.
8. A successful receipt means **files published**, not selected, trusted, leased, or tool-ready.
   Submit its `LinuxInstallation` through the existing `TrustedLinuxCatalog.freezeForApproval`
   and `approve` flow with a separate explicit installed-configuration confirmation. That API
   issues its own catalog generation; do not conflate it with the immutable installation
   directory generation. Only existing parent selection logic may subsequently call
   `LinuxEnvironmentStore.select`. This installer does none of those operations.

## Disk and cancellation semantics

- Transport: HTTPS only, one explicitly selected mirror, no redirect, retry or mirror fallback;
  identity encoding, strict actual byte count and SHA-256. Content-Length is checked if present
  but is never trusted instead of the actual stream. Failed partial archive is removed. The
  installer repeats size/hash verification before extraction, including injected transports.
- Staging and final rootfs are siblings in the same app-private generation directory. New
  transaction directories use mode 0700. Every attempt synchronously fsyncs the parent of each
  managed directory (linux-installations, environment, generation), even when it already exists,
  including filesDir for linux-installations. Existence after a failed sync is not durability
  confirmation: retries, different generations and new installer instances must reconfirm the
  entire chain before download/publication. A failure cannot produce a true durability receipt.
  The caller-owned root (filesDir) must already exist; external ancestors are not created or
  permission-modified. Path ancestors must not be symlinks. No in-place rootfs extraction.
- A generation-local `.install-lock` directory excludes cooperating instances/processes. An
  existing lock fails closed: recovery is an explicit parent action, not automatic deletion.
  App-private ownership and cooperative writers are required; this is not protection against
  arbitrary malicious code running with the same Android UID.
- A pre-existing rootfs (including an empty directory or dangling symlink) aborts **before any
  request**. `Files.move` uses no overwrite option and no `ATOMIC_MOVE` (whose specification
  permits replacing an existing target). Both paths share a parent/filesystem; there is no
  copy fallback. The directory lock closes races among supported installer writers.
- After extraction, readable contained `/bin/sh` and the injected PRoot probe are checked.
  A human-readable `.daiyu-rootfs-install` marker binds entry, hash/size, mirror URL,
  environment/generation, exact target and helper hashes. Files and directories are synced
  before publication. The final cancellation check and rename share the cancellation gate.
- Once rename is attempted, disk wins. An exception or cancellation after rename is reconciled
  through the exact bounded marker and readable shell. A visible valid rootfs returns a receipt
  with `directorySyncConfirmed=false` if durability was not acknowledged; never report it as
  absent or delete it to mimic rollback. Parent must disclose/recover this uncertainty before
  catalog approval. A true flag acknowledges sync calls, not a real power-loss experiment.
- `inspectPublished(prepared)` reads and verifies only; missing rootfs returns null, malformed
  marker/shell throws. It does not authorize execution or reconstruct a catalog. The production
  coordinator persists a canonical, non-authorizing request before offering installation and
  reconstructs that exact request after restart using `recoverRequest`, the pinned catalog and
  original packaged helper pair. A visible target still requires an explicit original-path
  `confirmPublishedDurability` call, then a separate registration confirmation; no download or
  probe is replayed for published files. See `R09-linux-production-install-composition.md` for
  journal formats, linked registration identity, read-only startup validation and strict
  UNKNOWN/missing/corrupt metadata behavior. Never import arbitrary markers into the catalog.

## Extraction security contract

- Requires an existing empty staging directory and rejects symlink ancestors. Ordinary files use
  CREATE_NEW. Names are normalized for `.` and repeated separators; absolute paths, NUL (including
  hidden fixed-field NUL suffixes), `..` components, backslashes, drive prefixes and duplicate
  normalized destinations are rejected. The root install marker is reserved. Strip=1 applies to
  archive paths/hardlink targets, never to guest-absolute symlink targets.
- Links are deferred until all ordinary files/directories exist. No entry can descend through a
  declared link. Guest-absolute symbolic links are rewritten to contained relative links;
  lexical escapes are rejected. Dangling guest links may exist, but can never refer outside
  staging. Hardlinks resolve forward chains iteratively, require regular non-symlink sources,
  reject cycles/missing sources, and never follow symlink ancestors.
- POSIX rwx bits are preserved (including executable bits), not uid/gid, setuid/setgid/sticky,
  xattrs or host ownership. Directory permissions are applied last. Zero-payload character devices,
  block devices and FIFOs pass the same path/namespace checks and are skipped without creating
  nodes or placeholders. Sparse and other unsupported entries remain rejected. Skipped paths
  still occupy the logical namespace, including their directory ancestors.
- At most 3 GiB of **all decompressed tar bytes**, 200000 physical headers (including hidden
  GNU/PAX metadata) and logical entries, 4096 path characters, 128 path components, and 1 MiB
  per metadata header. XZ dictionary memory limit is 128 MiB. Declared file totals are also
  checked before writes. Complete tar end records, header checksums and compressed stream
  trailers are required. Trailing nonzero/concatenated tar data is rejected.
- The explicit cancellation token is checked on reads, payload copies, tar-record processing,
  paths, hardlink resolution and permission loops; cancellation closes the archive stream.
  Cancellation during download still closes/disconnects the existing HTTP transport. No
  coroutine-only cancellation assumption or automatic mirror retry was introduced.

## Tests added and execution limits

`RootlessLinuxInstallerTest` covers pure preparation/approval (zero requests), one-shot and
owner-bound capabilities, exact target/mirror rejection, wrong hash (zero extraction/probe/
publication), cancellation before start/during extraction/at last sync, explicit Linux probe
configuration, existing empty/populated rootfs preservation, stale locks/symlink ancestors,
readable/tampered marker and escaping shell, and rename followed by cancellation or fsync error.
`VerifiedArtifactDownloaderTest` covers exact size/hash, truncation/overflow, redirects,
incorrect declared length, no-clobber destination, pre-cancel (zero connection creations), and
closing/disconnecting a blocked **fake** stream. Tests never perform network installation,
run a shell, invoke PRoot, or download a real rootfs.

New, **not yet run** tests: `LinuxRootfsCatalogTest` asserts all four metadata records, immutable
lists, no 32-bit fallback and fixed maximum quotas; `TarLinuxRootfsExtractionTest` uses tiny real
tar/gzip/xz fixtures for extraction, executable permissions, stripped layouts, forward hardlink
chains, guest-absolute links, malicious paths/duplicates/NUL, staging checks, link ancestors,
escapes, cycles/missing sources, devices/FIFO, privileged mode stripping, byte/entry/metadata
limits, truncated streams, excessive XZ dictionary memory, and explicit pre-cancellation.
`RootlessLinuxInstallerTest` adds successful ancestor-sync ordering and injected failures at
each new ancestor parent, requiring zero download/probe/publication. PRoot launches and the
install identity probe bind the fixed host `/dev` to guest `/dev`; user shared mounts cannot
override that target. The fixed chroot transport pins the same device directory by file
descriptors inside its private mount namespace before mounting proc, including device
submounts; no archive node is materialized. Its existing post-rename
error/cancellation tests still require marker reconciliation and preservation of published files.
These are source-level test additions, **not reported runtime results**. Actual full-size quota
stress and cancellation latency during large extraction remain runtime validation work.

The implementation agent has **not run a build or tests** (workspace editing tools only).
The main agent owns source compilation, focused tests and dependency integration. No claims are
made about real Android ABI/SELinux compatibility, real HTTPS provider cancellation latency,
power-failure recovery, or real Alpine/Debian boot/probe success.
