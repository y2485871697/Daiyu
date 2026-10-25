# F22/F23 integration API

This is a repair of the frozen Skills/Memory source snapshot recorded in `docs/refactor-input/skills-fix/manifest.json` (source commit `f88273d9b89e95c36f216bc0a147b09493ca67f9`). Task 11 recovered only the eight Skills host/test files listed in task11 coordination seed.json; Memory files were not migrated by this task. The copies under `docs/refactor-input/skills-fix/` are retained as repair inputs. Formal source placement does not wire the feature into the runtime. No Controller, database, Android manifest, build, UI, old Eta signatures, repository skill or host-assistant memory is changed. The manifest records the original frozen input hashes, not the repaired output hashes.

## Registries

- `SkillToolRegistry(context: Context)` / `MemoryToolRegistry(context: Context)`.
- `schemas: JSONArray` and alias `definitions`: each item `{name, description, parameters, sideEffect}`. The model adapter must wrap these into its own provider schema; these are not yet wired into the existing ResponsesModel enum.
- `execute(name: String, arguments: JSONObject): JSONObject`, with `{ok:false,error:...}` on rejection. Dispatch on a worker thread (standard blocking filesystem/HTTPS).
- Both expose persistently stored `enabled`; defaults: Skills true, Memory false. Capability/settings UI should set these explicitly.
- Skills `snapshot()` returns detached enabled instructions and `promptIndex()`. Capture once before a turn; later writes do not mutate that snapshot. Do not inject disabled skill bodies or resources. `setEnabled(id,value,revision)` is the app settings entry, not a model tool.
- Memory `core(budgetChars=4096)` returns headings only, revision, truncated, enabled. Disabled returns empty text and null revision without reading memory. Inject only this bounded index; call memory_get for prose.
- Side effects must be approved by the parent tool dispatcher with **all exact arguments bound**, including repository owner/repo, fixed commit, selected directories, incoming_revisions and replacements. Do not approve just a tool name, directory or old version. Do not automatically retry conflicts.

## Parameter compatibility matrix

| Tool | Arguments | Result |
| --- | --- | --- |
| skills_list | `{}` | metadata skills with `id` (`builtin:name` / `user:name`), revision, enabled, source, commit |
| skills_read | id, revision | enabled SKILL.md text; executable false |
| skills_read_resource | id, revision, path, optional max_bytes 1..262144 (default 65536) | root-contained bounded UTF-8 text; executable false |
| skills_inspect_github | url | owner, repo, fixed commit, candidates `{directory,name,description,incoming_revision}` |
| skills_install_from_github | owner, repo, commit (40 lower hex), directories[], incoming_revisions `{name: sha256}`, optional replacements `[{id:"user:name",revision:sha256}]` | installed metadata; enabled only after successful transaction |
| memory_get | optional query, page (zero based), page_size 64..16384, revision | content, SHA256 revision, next_page, total_chars |
| memory_write | content, expected_revision, purpose (`durable_fact` / `durable_preference`) | new SHA256 revision |

Inspection resolves a fixed commit, fetches its tree once and (when candidates exist) its ZIP once, validates the complete archive against the tree and git blob identities once, then slices all candidate bundles and calculates exact incoming revisions. It does not fetch raw SKILL.md or download separately per candidate. Installation is a separate operation that re-downloads at the approved fixed commit, verifies ZIP safety and git blob hashes, then verifies the approved incoming digests before publication. Each operation has one 60-second monotonic deadline, <=32 MiB aggregate transfer, <=8 MiB aggregate selected content (including overlapping selections), and worker-thread interruption checks before/after fetches, during ZIP entries/decompression, blob validation, candidate slicing and revision hashing. Connect/read timeouts are capped by remaining deadline; a blocked platform HTTPS read may observe interruption only at its timeout or the next boundary. Parent cancellation must interrupt the worker; coroutine-only cancellation is not automatically wired into this blocking API. No installation is started after a cancelled/expired download. Empty directory selects repo root. Repository-root inspection encodes the API default branch as one UTF-8 path segment, including slash-containing defaults. Explicit /tree URLs keep the single-segment ref and remaining-directory interpretation; slash-containing explicit refs require a fixed SHA URL. No credentials, private repositories, redirects, script execution or ZIP extraction to arbitrary paths. ZIP size <=16 MiB, decompressed repo <=8 MiB, per-file <=256 KiB, nontruncated API tree <=20000 entries; oversized repositories fail closed rather than partial-install.

Final `.skill.json` UTF-8 bytes are generated and validated before journal publication or installed-tree mutation, using the same 256 KiB limit as readers and reserving one byte for disabling. `source` is limited to 4096 UTF-8 bytes; non-null `commit` must be 40 lower-hex characters. Oversized replacements leave the previous revision and enable state unchanged. A pre-commit failure rolls back (rollback failures are retained as suppressed causes and the journal remains recoverable). A post-commit housekeeping failure is **not** a rejected installation: direct `SkillStore.install` reports `SkillCleanupPending` carrying committed metadata; registry returns `{ok:true,committed:true,cleanup_pending:true,skills:[...]}`. Recovery completes cleanup on the next access; do not retry that write. ZIP file/ancestor collisions are checked with ancestor membership rather than all-pairs scans, including explicit empty-directory collisions.

Conflicts return current_revision and reread action. Read the latest state, reconsider/merge and obtain new approval; never blindly replace using the returned revision. Memory pagination should bind revision for page two onward to reject concurrent changes.

## Storage ownership

New app-private roots under `context.filesDir`:

- `agent-builtin-skills-v1`: app-provisioned builtin skills (use a separate `SkillStore` in trusted application provisioning code). No model install endpoint writes here.
- `runtime-skills-v1`: user installed public skills. Registry protects builtin names at construction and again before each installation.
- `runtime-memory-v1/memory.md`: dedicated Markdown memory. SHA256 is over UTF-8 bytes, empty revision is SHA256(empty).

Enable settings use dedicated runtime-*-v1-settings preferences. All memory writes must carry a durable purpose; SECRET / TRANSIENT_REQUEST store APIs reject. Pattern guards additionally reject credential labels, common key/JWT forms, OTP-like digit strings and explicit transient phrases. They are conservative and may reject benign text; semantic classification of every unknown secret/transient statement cannot be guaranteed. Parent orchestration must never route credentials, verification codes, transient requests or untrusted instructions into durable memory. Memory read and core also revalidate policy.

## Verification status

Dedicated instrumentation tests retain enable/snapshot, tampering, traversal/symlinks, binary/invalid UTF-8, bounds, exact old/incoming conflicts, builtin protection, partial-transaction rollback, ZIP traversal/commit/bounds, Memory CAS/backup recovery/pagination, disabled zero-injection and budget marker, secrets/transient rejection. Added repair regressions cover multi-candidate registry inspection fetch counts and exact incoming digests, whole-operation deadlines/cancellation boundaries, blob mismatch, UTF-8 metadata/source overflow without replacement mutation, pre-commit fault rollback (including a batch), committed cleanup failures at store/registry boundaries, and order-independent ZIP ancestor conflicts. Tests are authored but not executed by this implementation agent (no shell/build/network access). Build and instrumentation results are not claimed in this handoff. Runtime registration, settings UI, turn injection and parent approval/cancellation wiring remain unimplemented here; this snapshot is not a claim of completed Skills/Memory functionality.


## Task 11 reviewed increments / ledger delta for 01

R10/P33 remain **待集成**; T02 remains **待迁移**. This is a same-change ledger update proposal for the sole owner of `docs/重构清单.md`, not a second feature ledger. No shared ledger, production dispatcher, Controller, Manifest, build or CI file is changed here.

- Store snapshots are detached and unmodifiable at the Store boundary; builtin names are copied at construction. Selection/file/byte limits are checked before payload copying and checked again on the copies. Access may still create lock/directories or recover an earlier transaction; this is not a zero-filesystem-I/O claim.
- Invocation shape validation completes before download/store access in `execute()`. Exact old/incoming revisions remain separate Store arguments. Invalid shape is distinct from downloaded content/revision mismatch.
- Root GitHub inspection resolves encoded default refs and pins all tree/ZIP reads to the validated SHA. DEL/C1 invalid-ref fixture uses explicit JSON Unicode escapes so it reaches ref validation rather than transport binary rejection. Production text rejection remains unchanged.
- Parent executed Store artifact 66045f6 (23 JVM fixtures, exit 0) and invocation artifact bf6ddb0 (27 fixtures, exit 0 after correcting only a test-host Context shim). Exact commands, file hashes and reports are in task11 coordination `checks/`. GitHub initial 24-fixture run had one failure from the test serialization assumption; retained as failed evidence; aggregate f1124c6 subsequently passed 38 JVM fixtures and 9-source platform-stub compilation (exit 0).
- Registry combined snapshot now also returns an unmodifiable detached copy for enabled, empty and disabled paths; artifact 9fad067 passed 36 JVM fixtures on its own baseline and static review. Final combined checks are recorded by the parent handoff. Trusted run scope, frozen permissions, session-filtered advertising, WRITE approval/ledger and production call wiring remain unimplemented/unintegrated. Do not expose local `execute` as a model dispatcher.
- OpenMinis checker preservation candidate was located, but exact upstream commit/license/NOTICE mapping remains with task19. No new scanner is represented as the retained third-party checker.

Host fixtures use test-only AtomicFile/Instrumentation/Context/preferences substitutes. Android API26/API37.2 instrumentation, real network cancellation and crash durability have not been verified for this candidate. Preserve minSdk 26. No APK was built, downloaded or installed by task11.

## Trusted read-only run scope increment

`SkillToolRegistry.captureForRun(SessionCapabilities, TaskAuthorization, selection: Set<String>? = null)` is a trusted worker-thread factory. Pass the actual host-issued grant, not IDs parsed from model JSON. It rejects session mismatch and snapshots `skills.read` / `skills.resource`. Omitting selection preserves legacy builtin precedence and `builtin:` / `user:` IDs; explicit selection uses the narrower rules below. With no read capability or global Skills disabled it returns an empty, unauthorized handle. Capturing may lock/read/recover stores; never call it from `RegisteredTool.prepare`.

Use one `SkillRunSession` per trusted run. `availableTools` contains only `skills_list`, `skills_read`, and optionally `skills_read_resource`; it is a name set, not a central ToolCatalog. The central adapter, prompt index and reads must consume this same handle. Do not route model reads to the legacy live `execute()` fallback, and do not expose install/inspect through this read-only session.

- `indexJson(grant)` returns the canonical `{skills:[{id,description,revision}]}` model payload. Its exact encoded UTF-8 bytes, including JSON escaping, are charged against the 64 KiB index limit and the shared output budget. `index(grant)` returns an unmodifiable typed view and charges the same canonical payload; it is not a license to use a different unbounded serialization envelope.
- `read(grant,id,revision)` returns the captured complete SKILL.md. `readResource(grant,id,revision,path,maxBytes)` returns a bounded resource (default 64 KiB, maximum 256 KiB). SKILL.md resource reads use the captured body. Other resources are not cached: current enabled state and revision plus the captured hash must still match, or the read rejects. Disabling/replacing a skill does not mutate already captured body text.
- Capture holds at most 2 MiB of counted UTF-8 bodies and manifest/metadata content. Content returns have a separate shared cumulative 2 MiB budget. Body/resource methods charge content UTF-8; a future consumer must independently bound any additional encoded envelope. Index returns charge their actual canonical JSON. No truncation masquerades as complete data.
- `child(parentGrant,childGrant,childCapabilities)` requires the same session, a distinct task ID and no child write grants; permissions are intersected, never enlarged. Parent/descendants share one atomic output budget. Each read requires the exact corresponding grant object, not a newly constructed object with equal IDs.
- `close()` is idempotent. New calls reject; parent closure revokes descendants, child closure does not close its parent. Calls overlapping closure may complete or reject; this is not a drain-all-in-flight barrier. The host must keep any already-returned data under its own retention policy.

Parent executed 59 host-shim JUnit fixtures (42 inherited plus 17 added), including real Registry factory paths, frozen IDs/bodies, resource tampering/revision rejection, child permissions and closure, escaped index size and cumulative accounting, and concurrent parent/child budget contention. Exact source hashes, commands and logs are in `/workspace/daiyu-coordination/task-11/platform-scope-v1/check-index-fix/`. A failed first multi-skill fixture and a fixture compilation mistake are retained in earlier check directories; they are not passing evidence. No Android instrumentation, real network or crash-durability execution is claimed.

Same-change ledger proposal for 01: R10/P33 gains this reviewed read-only run-scope candidate; centralized advertising/Dispatcher, trusted send/child lifecycle, settings/UI, install approval/ledger and API26/API37.2 acceptance remain pending. T02 checker provenance remains unchanged. This increment changes only the Skills package and its tests/integration note; no shared ledger, Controller, Registry/Dispatcher, Manifest, build or CI file is edited. It is not a business-complete declaration.

## Trusted settings management

`SkillManagementScreen(managementFactory, onBack)` owns a worker and a `SkillManagement`
instance for one page visit. Registry construction, metadata reads, public GitHub inspection,
preview, installation, toggles and read-back are off the main thread. No new Activity,
permission, model tool, database schema or run entry-point is introduced. Individual toggles
pin the displayed content revision (not a concurrent toggle version).

### Settings installation protocol (R10/P33 increment)

1. `inspect(url)` validates the public URL and uses the existing `GitHubSkills.inspect` path.
   Its opaque `Inspection` retains the resolved 40-hex commit and detached candidate metadata.
   A new inspection invalidates the previous inspection and pending confirmation.
2. The user selects directories (including the empty-string repository root). `preview` accepts
   only candidates belonging to that exact inspection, downloads at its commit and reuses
   `SkillStore.previewForManagement` for file/tree/metadata limits, content revisions and real
   compatibility results. Duplicate names, builtin names and unavailable local metadata reject
   before issuing a confirmation. Incompatible selections remain visible with their reason,
   but cannot be confirmed or installed.
3. The opaque `Preview` freezes the selected directories, incoming revisions and currently
   installed user revisions. The UI displays all of these, including full old/new revisions,
   and requires a separate checkbox for **each** replacement. No old revision means create-only.
4. `install(preview, approvedReplacements)` accepts only the currently pending preview object
   from that manager and exact complete `user:id -> old revision` map. The preview is consumed
   before network or write, including on invalid approval, conflict or failure. Copies, other
   managers, canceled/superseded previews and double submissions cannot reuse approval.
5. Installation downloads from the **same commit** (never resolves the branch again), rechecks
   incoming content, rechecks builtins, and calls the existing all-or-nothing `SkillStore.install`
   transaction with both exact maps. No ZIP/extraction implementation or script executor changed.
   Global enablement is preserved; successfully installed skills have their individual flag on.

`Installed(cleanupPending=true)` is a committed success, not rollback. Unknown publication is
reported as `Unknown`, without automatic retry. The page separately reads back the list; a
failed read-back cannot turn committed success into a failed install. Refresh is read-only;
if another install is wanted, the user must explicitly inspect/preview/approve again. Dismissal
before submission invalidates pending approval. A page visit owns a one-way, non-blocking
admission gate independent of `SkillManagement`'s I/O lock. Confirming creates a unique ticket
and queues work; **neither the click nor queue insertion admits an installation**. Immediately
before calling `management.install`, the worker must consume that ticket with a CAS on the
same atomic state that `dispose` synchronously and permanently revokes. If revoke wins, that
queued task cannot download or install, even though queued cleanup has not run yet. If admission
wins, the one-shot task may finish across departure; the gate holds no lock around network or
storage and disposal does not wait for either. Disposal is idempotent and an old page owner
cannot become active again. Late inspection/preview/install callbacks are ignored by the old
controller and cannot update a new page or use its active state/preview. Unused manager approval
is separately cleared by ordered worker cleanup; cleanup is not the admission barrier.
Inspection/download retain the existing bounded timeout behavior.

Global disable affects later run captures; existing captured bodies/results are not withdrawn.
Installed replacements appear on the next run. Captured instructions remain unchanged, while
non-body resources retain the existing current-revision/enabled/hash rejection policy. The
settings module does not close active runs, freeze additional resource bytes, broaden grants,
or register inspect/install/WRITE with `RunCatalogTools` or the model dispatcher.

### Retained static compatibility checker

The supplied saved checker is now reused by a thin `SkillCompatibilityPolicy` adapter.
Settings preview returns each real compatibility result alongside the exact checked revision;
the UI displays passed/denied text and disables incompatible confirmation. Management also
rejects that preview, and re-downloads/rechecks the same commit/directories/revisions at install.
`SkillStore.install` independently checks detached bytes before staging or journaling.
`captureForRun`, legacy `snapshot` and direct resource reads reparse installed SKILL.md after
hash verification, including skills installed before this policy existed. Selected/enabled
filtering still precedes body checks. Metadata values, compatibility and actual body are all
inputs; ordinary tool names are not a new blacklist and no executor is added. A passed result
is only absence of known static rejection markers, not a promise of runtime success.

See `docs/skills-compatibility-source.md` for exact inspected copy paths, retained/adapted code,
production call sites and dedicated test sources. Upstream path/SHA and file-level license
closure remain unverified; functional wiring does not establish provenance or full audit
acceptance. No overall inventory or third-party manifest was changed.

### Verification handoff (not executed by this implementation agent)

`SkillInstallManagementTest` adds isolated, injected-response fixtures for root/subdirectory
selection, fixed commit, precise incoming and replacement revisions, single consumption,
cross-manager/forged/superseded previews, cancellation, concurrent submissions, duplicate names,
builtin preflight/recheck, concurrent local replacement, changed download, committed cleanup,
Unknown no-replay, main-thread rejection, global enablement and current-vs-next-run behavior.
All disk writes in those test methods target temporary fixture roots; their preferences are
uniquely namespaced. `SkillInstallScreenTest` retains three real Compose cases for create-only
confirmation/result, explicit replacement approval/cancel, and leaving an unused preview. It also
uses the same production page controller with a manually drained worker and UI callback queue to
cover four lifecycle boundaries deterministically: a late inspection delivered after departure;
an old confirmation callback and old preview presented while a new page has its own preview;
a confirmed but queued/unadmitted install revoked before worker start (zero additional fetches
and zero writes); and a worker admitted before departure (one install, no old/new page result
mutation). The last case holds an injected fetch at a latch while disposal returns, then releases
it and requires an explicit refresh to populate the new page. Duplicate confirmations and
idempotent disposal are checked too. A canceled-inspection/retry case also verifies that an old
callback cannot clear the newer operation's busy flag. No test uses real GitHub or app-installed
skills. Existing management, GitHub, store and run-snapshot regression suites should also be
executed by the parent.

This agent ran no builds, tests, network, skill installation or credential reads. Required parent
checks: source/type compilation including androidTest; those isolated instrumentation tests on
API26 and API37.2; UI smoke for URL input -> inspect -> select (including root) -> preview ->
each replacement checkbox -> submit/read-back; cancel before submission and leave while queued;
Unknown/cleanup messaging; refresh after stale revision; old run instructions/new run metadata
and old-resource rejection; model catalog still read-only. No test count here implies passage.

## Assistant-selected read-only run scope

`captureForRun(capabilities, authorization, selection: Set<String>? = null)` now accepts a
host-owned selection. The parent must pass the **already frozen**
`AssistantRunContext.enabledSkillIds.toSet()`, not reread the current assistant settings or
accept selection from model JSON. The factory copies that set before capture. No mutable
selection is retained in `SkillRunSession`.

Normalization deliberately cannot widen a selection:

| Input | Meaning |
| --- | --- |
| Omitted or `null` | Backward-compatible enabled catalog, with enabled builtins shadowing same-name user entries. This is not the assistant path. |
| `emptySet()` | No selected skills. Authorized read tools may remain advertised, but the index is empty and every skill read rejects. Never convert empty to null. |
| `builtin:name` / `user:name` | Exact, case-sensitive origin and name. No fallback to another origin if missing or disabled. Both same-name origins appear only when both exact IDs were explicitly requested. |
| Bare `name` (e.g. `demo`) | Resolve only if that installed directory name exists in exactly one origin. If both origins contain it, ignore this ambiguous token (fail closed), even when a namesake is disabled or has damaged metadata. An additional explicit ID still selects its exact origin. |
| Unknown/malformed token | Select nothing for that token; never reinterpret as all skills. No trimming, lowercasing, wildcard matching or prefix repair. Names use `[a-z0-9]+(?:-[a-z0-9]+)*`, length 1..64. |

All selections still require global enablement, individual enablement and `skills.read`.
`skills_read_resource` additionally requires `skills.resource`; selection is not a grant.
Selection filters directory names **before** per-skill metadata/body validation and byte
accounting. Only selected enabled bodies/manifests enter the capture budget. Thus unselected
corrupt SKILL.md or `.skill.json` cannot break a selected run. A selected damaged skill still
fails capture; root safety, store locking and transaction recovery failures remain errors
(not silently ignored). Directory-name inventory also initializes the default user store's
builtin-name protection, avoiding eager parsing of unselected builtin metadata. Installation
and settings approval protocols are otherwise unchanged.

The returned single scope supplies catalog/index, SKILL.md and resource reads. Unselected IDs
are absent even if physically installed. `SkillRunSession` is unchanged: it requires the same
host authorization **object** (`===`), keeps frozen bodies, and checks the captured revision
and hash plus current enabled state for non-body resources. Selection changes affect only a
new capture; neither changing a caller's mutable set nor editing an assistant can widen an
already returned scope. Capture is not a cross-store transaction: directory inventories and
per-store snapshots are separately locked. A newly installed skill is not discovered again
mid-capture to widen an explicit selection.

### Parent Controller integration example (handoff, not edited here)

At the existing `RunCatalogTools` skill-factory callback, capture the assistant selection once
from the run context and pass it explicitly. Keep the same host `authorization` object:

```kotlin
val selected = assistantContext.enabledSkillIds.toSet() // empty stays empty
val tools = RunCatalogTools(
    registry, capabilities, toolLedger, conversationId, run, devices, children,
    backgroundFactory, authorization = shizukuAuthorization, shizuku = shizuku
) { grants, authorization ->
    SkillToolRegistry(application).captureForRun(
        grants, authorization, selection = selected
    )
}
```

Use this returned handle throughout the existing dispatcher/prompt path; do not switch back
to live `execute()` or recapture later. This patch does not edit Controller, MainActivity or
RunCatalogTools, does not alter global installation management, and adds no shell execution
or child-agent tools. The main agent must review and apply the parent call-site change.

### Selection verification handoff

`SkillRunSelectionTest` adds isolated fixtures for omitted/null versus explicit empty selection,
unique bare names, unknown/malformed tokens, exact prefixes, same-name collision (including
disabled/damaged namesakes), unselected damaged metadata/bodies with default lazy user-store
construction, selected damage rejection, exclusion before the capture budget, mutable
selection changes after capture, shared index/body/resource scope, authorization object
identity, frozen body/live-resource revision semantics and global/individual/read/resource
permission intersections. Existing `SkillRegistrySnapshotTest`, `SkillRunSessionTest`,
`SkillManagementTest` and installation tests remain regression targets.

These tests were authored and statically reviewed only. This implementation agent ran no
shell, build or tests and claims no passing test count. Parent compilation and test execution
are required; API26/API37.2 instrumentation and real Controller-path acceptance are not
established by this change.


### Removed default packages

The app ships no default Skill packages and new assistants select no skills. The former
self-improving-agent, skill-creator and skill-installer packages are retired only from the
app-private builtin store, after transaction recovery and before any store operation.
User-installed packages, including namesakes, are preserved and remain installable through
ZIP/GitHub management. No fixed skill-body injection or automatic learning log was wired
into this project's runtime; its existing selected-skill read tools and memory remain intact.
The assistant editor retains its Skills section and shows only a single “None” line when
its available-skill list is empty; rendering that state does not edit saved selections.
