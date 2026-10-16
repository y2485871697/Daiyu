# Linux rootfs installer: source, authorization and dependencies

## Authorized backend source

Required Notice: Copyright © 2026 蛮吉 (Mangi-11).

The supplied Eta adaptation-kit provenance identifies baseline
`2a7e7ab92b98da5a3711611017021441e7a8053c`, under
`/workspace/daiyu-coordination/main-01/backend-kit-v1/Eta-backend-adaptation-kit-v1/sources/eta/`.
Source research supplied for this task identified:

- `app/src/main/kotlin/io/github/mangi/eta/agent/terminal/AlpineEnvironmentInstaller.kt`
- `app/src/main/kotlin/io/github/mangi/eta/agent/terminal/DebianEnvironmentInstaller.kt`
- `app/src/main/kotlin/io/github/mangi/eta/agent/terminal/RootlessLinuxInstaller.kt`
- `app/src/main/kotlin/io/github/mangi/eta/agent/terminal/VerifiedArtifactDownloader.kt`

The original **PolyForm Noncommercial License 1.0.0**, including its Required Notice,
is retained in the repository at
[`docs/third-party/virtual-display-owner/LICENSE`](../../docs/third-party/virtual-display-owner/LICENSE).
That text is the same supplied Eta baseline license already retained for the authorized backend
migration; it is not replaced by the repository's root license. Preserve this notice and that
license when distributing the adapted installer.

The user has authorized migration, modification and distribution of the provided backend.
This records that authorization without claiming it relicenses third-party dependencies or
all rootfs packages. The four rootfs records were transcribed from the supplied source research.
`TarLinuxRootfsExtraction` is independently implemented against the supplied extraction contract
with additional empty-staging, path/link, resource, cancellation and durability checks. The
approval boundary and single-URI/no-redirect transport remain local adapters. No old installBase,
automatic mirrors, root shell or helper binaries were imported. The editing agent has not
re-read the external source package or downloaded/verified the four remote payloads.

## Newly declared runtime libraries

- **Apache Commons Compress 1.28.0** — Maven `org.apache.commons:commons-compress:1.28.0`,
  Apache Software Foundation, Apache License 2.0. The repository already retains the
  [full Apache 2.0 text](../gradle-LICENSE); upstream project:
  <https://commons.apache.org/proper/commons-compress/>.
  Preserve the library artifact's `META-INF/LICENSE.txt` and `META-INF/NOTICE.txt` and all
  applicable transitive dependency notices in the actual distribution; this source notice
  does not replace those upstream texts.
- **XZ for Java 1.12** — Maven `org.tukaani:xz:1.12`, upstream project:
  <https://tukaani.org/xz/java.html>. Preserve its upstream copyright, license and any
  public-domain notices from the resolved artifact/source distribution. This refers to XZ
  for Java, not the native XZ Utils project or its executables.

Dependency resolution, packaged upstream license/notice inclusion, Android compatibility and
license inventory must be checked against the actual resolved build by the integrating agent.
No dependency artifacts were fetched by this editing task. The library version declarations
and research provenance are not a claim that dependency tests or release compliance were run.

## Rootfs and native-helper scope

Alpine 3.24.1 rootfs entries use the supplied Alpine release URLs; Debian 13 entries are
**Termux proot-distro v4.29.0 release artifacts**, not official Debian rootfs artifacts.
Archive SHA-256/size values identify the fixed inputs but do not override licenses of packages
inside them. No rootfs is vendored by this change and no automatic download occurs.

The original installer-only change did not bundle native helpers. The subsequent R09
runtime packaging adds the exact supplied PRoot/loader pairs, corresponding source and
licenses; see [the bundled runtime notice](../proot/NOTICE.md) and its artifact hashes.
These hashes were not derived from rootfs metadata. The [earlier native-runtime inventory](../task19/native-runtime.md) remains a historical audit, not the current packaging state.
