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

`SkillToolRegistry.captureForRun(SessionCapabilities, TaskAuthorization)` is a trusted worker-thread factory. Pass the actual host-issued grant, not IDs parsed from model JSON. It rejects session mismatch, snapshots `skills.read` / `skills.resource`, and preserves builtin precedence and `builtin:` / `user:` IDs. With no read capability or global Skills disabled it returns an empty, unauthorized handle. Capturing may lock/read/recover stores; never call it from `RegisteredTool.prepare`.

Use one `SkillRunSession` per trusted run. `availableTools` contains only `skills_list`, `skills_read`, and optionally `skills_read_resource`; it is a name set, not a central ToolCatalog. The central adapter, prompt index and reads must consume this same handle. Do not route model reads to the legacy live `execute()` fallback, and do not expose install/inspect through this read-only session.

- `indexJson(grant)` returns the canonical `{skills:[{id,description,revision}]}` model payload. Its exact encoded UTF-8 bytes, including JSON escaping, are charged against the 64 KiB index limit and the shared output budget. `index(grant)` returns an unmodifiable typed view and charges the same canonical payload; it is not a license to use a different unbounded serialization envelope.
- `read(grant,id,revision)` returns the captured complete SKILL.md. `readResource(grant,id,revision,path,maxBytes)` returns a bounded resource (default 64 KiB, maximum 256 KiB). SKILL.md resource reads use the captured body. Other resources are not cached: current enabled state and revision plus the captured hash must still match, or the read rejects. Disabling/replacing a skill does not mutate already captured body text.
- Capture holds at most 2 MiB of counted UTF-8 bodies and manifest/metadata content. Content returns have a separate shared cumulative 2 MiB budget. Body/resource methods charge content UTF-8; a future consumer must independently bound any additional encoded envelope. Index returns charge their actual canonical JSON. No truncation masquerades as complete data.
- `child(parentGrant,childGrant,childCapabilities)` requires the same session, a distinct task ID and no child write grants; permissions are intersected, never enlarged. Parent/descendants share one atomic output budget. Each read requires the exact corresponding grant object, not a newly constructed object with equal IDs.
- `close()` is idempotent. New calls reject; parent closure revokes descendants, child closure does not close its parent. Calls overlapping closure may complete or reject; this is not a drain-all-in-flight barrier. The host must keep any already-returned data under its own retention policy.

Parent executed 59 host-shim JUnit fixtures (42 inherited plus 17 added), including real Registry factory paths, frozen IDs/bodies, resource tampering/revision rejection, child permissions and closure, escaped index size and cumulative accounting, and concurrent parent/child budget contention. Exact source hashes, commands and logs are in `/workspace/daiyu-coordination/task-11/platform-scope-v1/check-index-fix/`. A failed first multi-skill fixture and a fixture compilation mistake are retained in earlier check directories; they are not passing evidence. No Android instrumentation, real network or crash-durability execution is claimed.

Same-change ledger proposal for 01: R10/P33 gains this reviewed read-only run-scope candidate; centralized advertising/Dispatcher, trusted send/child lifecycle, settings/UI, install approval/ledger and API26/API37.2 acceptance remain pending. T02 checker provenance remains unchanged. This increment changes only the Skills package and its tests/integration note; no shared ledger, Controller, Registry/Dispatcher, Manifest, build or CI file is edited. It is not a business-complete declaration.

## Trusted settings management

`SkillManagementDialog(activity).show()` creates a settings dialog without a new Activity,
permission, or model tool. Dismiss it from the host Activity lifecycle. Registry construction,
metadata reads, toggles and read-back use a dedicated worker. Settings contain no installation,
inspection or script execution action. Individual toggles pin the displayed content revision;
that revision is not a concurrent toggle version. A failure after mutation is not rollback:
refresh state before deciding another action, and never automatically repeat a write.

Global disable affects later run captures; existing captured bodies/results are not withdrawn.
Individual disable rejects later non-body resource reads while preserving the captured body.
The settings module does not close active runs or broaden their grants.
