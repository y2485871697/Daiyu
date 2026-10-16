# Skills static compatibility checker: retained algorithm and production wiring

## Source actually inspected

This increment read the two read-only copies supplied in this worktree:

- `build/agent-input/SkillCompatibilityChecker.kt` (85 lines; original package
  `io.github.mangi.eta.agent.skill`).
- `build/agent-input/SkillCompatibilityCheckerTest.kt` (52 lines; three test methods).

The recorded saved-project checker location is:

```
/workspace/daiyu-origin-audit/full-detail-f001fb82/sources/browser/fork/app/src/main/kotlin/io/github/mangi/eta/agent/skill/SkillCompatibilityChecker.kt
```

The external location was **not** read by this agent; the main agent supplied the
in-worktree copy. The saved-project test's external path was not supplied/verified;
only the local test-copy path above is established here. See the existing
`THIRD_PARTY/task19/openminis.md`, T02 Skill, for the unresolved source record.
Upstream repository/path/SHA for the algorithm and test, and applicable file-level
license/notice attribution, remain **unclosed**. Neither a local frozen tree ID nor
another module's OpenMinis notice establishes those facts. No license is assigned
or invented here, and no third-party manifest or overall inventory is changed.

## Retained and adapted code

`SkillCompatibilityChecker.kt` retains the supplied string-based `evaluate`
algorithm, concatenation order, case folding, all marker arrays, branch priority,
iOS word-boundary expression and Android/Termux exception. The original reason
strings (including “Eta”) are intentionally unchanged. Its entry overload now
accepts the existing `SkillHeader` instead of the unavailable `SkillIndexEntry`,
using the SKILL name as both local id and name. The local result type is the simple
`available`/nullable `reason` value expected by that algorithm.

`SkillCompatibilityPolicy` is the thin host adapter: it passes name, description,
**compatibility, metadata values and body** to the retained function. The existing
frontmatter parser still returns the metadata and compatibility inputs. Its new
body accessor removes the already-validated frontmatter; arbitrary metadata keys
are not accidentally scanned as prose. SKILL.md bytes are retained verbatim, so
installed legacy and current skills do not depend on a new sidecar schema.

The first three methods in `SkillCompatibilityCheckerTest` retain the supplied
cases (MiniS Android CLI denial, HealthKit/Shortcut denial, portable hub/PDF
acceptance), with only package adjustment. Added cases exercise every retained
marker, precedence, all adapter inputs, iOS boundary/Android exception and ordinary
tool-name acceptance. No script executor or generic tool blacklist is added.
The policy is a known-marker static screen, **not** execution or full platform
certification; “available” means no retained rejection matched.

## Callable production path

1. `SkillStore.validateBundle` validates detached bytes and returns a
   `SkillBundlePreview(header, revision, compatibility)` computed by the real
   adapter. The settings-only preview path returns a denial as data, while the
   existing public `SkillStore.preview` rejects it with `SkillIncompatible`.
2. `SkillManagement.preview` calls `SkillToolRegistry.previewForManagement` for
   the selected pinned bundles, verifies name/description/revision, and exposes
   each real result in `Change.compatibility`. `SkillManagementScreen` displays
   passed/denied results (including the retained reason) and disables confirmation
   when any selection is incompatible. The management API rejects that preview
   too; UI disabling is not the enforcement boundary.
3. `SkillManagement.install` consumes the exact pending preview once, downloads
   the same owner/repo/commit/directories, recomputes each result and verifies the
   same incoming revision. `SkillStore.install` independently revalidates and
   recomputes on detached input before staging/journal/publication. This also
   protects direct/legacy domain callers. A typed compatibility denial is a known
   pre-publication rejection, not an ambiguous transaction failure.
4. `SkillStore.captureForRun` verifies the installed SKILL.md hash and reparses/
   checks it **before** creating `CapturedSkill`. This applies to old installed
   skills in both builtin/user stores. Selection filtering and enablement remain
   ahead of body checks, so unselected/disabled skills cannot poison the capture.
   The legacy `snapshot` and direct `readResource` paths use the same check after
   integrity verification. Existing captured run bodies remain frozen; no
   controller/catalog/authorization change was made.

## Concrete verification sources (not executed)

- `src/test/.../skills/SkillCompatibilityCheckerTest.kt`: copied cases plus marker
  and adapter-input coverage; `SkillCompatibilityFrontmatterTest.kt` remains the
  parser regression source from the prior increment.
- `src/androidTest/.../skills/SkillCompatibilityIntegrationTest.kt`: denied preview
  plus API/direct-store no-publication checks; compatible pinned-source/revision
  download/install and exact SKILL byte preservation; changed dependency revision
  denial; old-format on-disk skills rejected on builtin/user capture, snapshot,
  direct read and same-revision reinstall; selection/global/individual/grant
  isolation; integrity rejection before compatibility evaluation.
- `src/androidTest/.../SkillInstallScreenTest.kt`: actual passed text and denied
  reason/disabled confirmation assertions, with isolated stores and fake GitHub.

Only source inspection/editing was performed. No shell, build, test execution,
network, cross-tree writes or user-skill installation was performed. Compilation
and execution of these dedicated suites (and existing store/management/capture/UI
regressions) belong to the main agent. Source assertions are not passing-test
claims; upstream provenance/license closure also remains separate work.

## User authorization for this retained checker

The user explicitly selected `copyright_holder` in `question-4e7b3ceb-19e2-4a00-b222-8f50ffbccd33`, confirming ownership or sufficient authorization for copying, modification, integration and publication of this checker in Daiyu. This is the recorded authorization basis for integration. It does not establish an OpenMinis origin; the unmatched upstream attribution above remains unmatched. No third-party license is inferred from another repository.
