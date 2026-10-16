# R08 run-local virtual-display leaf and explicit approval contract

## Status / scope

This is a **non-wired artifact**, not an enabled feature. No change is made to `ChatController`,
`MainActivity`, `RunCatalogTools`, model settings, shared capability lists, or `vd/*`. The parent
still has to register the leaf in its **existing single ToolRegistry/ToolDispatcher/ToolLedger**,
implement a durable journal, mount an explicit approval UI, and retain/observe the owner host.
There is no second dispatcher, ledger implementation, model loop, accessibility route, shell
fallback, primary-display selector or replacement virtual-display runtime here.

New implementation files:

- `integration/tools/R08VirtualDisplayPlan.kt`: pure finite plan, exact effects, stable invocation
  identity and the parent journal interface.
- `integration/tools/R08VirtualDisplayCatalogLeaf.kt`: registration/execution leaf, adapter to the
  existing `AndroidVirtualDisplayOwnerHost`/`VirtualDisplayOwnerClient`, lifecycle observations.
- `vdapproval/VirtualDisplayApprovalHost.kt`: pure approval presentation/callback and single-use
  approved binding. This is a UI host contract, **not a mounted Activity/Compose screen**.
- `R08VirtualDisplayCatalogLeafTest.kt`: offline test sources, not executed by the implementation
  agent. No build, test command, device operation or network request was run.

## Authoritative dependencies inspected

The code uses the repository's real `RunExecutionOwner`, `ToolExecutionContext`, `PreparedTool`
context admission, `TaskAuthorization`, `SessionCapabilities`, and central `ToolDispatcher`.
`OwnerV2Contract.normalize()` calls the existing Java `OwnerProtocol.businessParameters()`;
`OwnerV2Contract.identity()` supplies the canonical effective-parameter identity used for each
business write effect. The local JSON renderer only stabilizes ordering for journal storage; it
is not another normalizer. Requests are sent by the existing client and validated by its existing
`OwnerResponseValidator`. No protocol/session fields are exposed in model arguments.

## Parent wiring: exact object instances and UI callback

The required sequence is deliberately explicit:

1. Wait for the **real Runner-issued owner**. Do not construct/reflection-forge an owner or prepare
   under a temporary owner. Select a generation in durable host state and pin it to all six run
   binding fields, including the actual owner ID. Generation allocation is outside this artifact;
   it must survive replay/recovery. A new Runner owner must not adopt an old binding implicitly.
2. Purely prepare a finite plan with
   `R08VirtualDisplayPlan.prepare(owner, capabilities, durableGeneration, writes, reads)`.
   The exact `SessionCapabilities` instance must subsequently be used by the existing parent.
   Never enable `r08.virtual_display` just because a settings page was opened.
3. Present `VirtualDisplayApprovalRequest(plan).rows` and `warning`. Render **every row** with its
   operation and effective parameters, including text/coordinates/package and the distinction
   between `start`, `initialize`, and GUI writes. Start grants only a root owner transport;
   initialization grants display creation; neither grants arbitrary GUI actions. The effect target
   pins the actual owner binding and generation. Approval is not a broad “enable GUI” checkbox.
4. Only an affirmative explicit UI action may call
   `request.answerFromUi(true) { approval -> ... }`. Dismiss/cancel uses `false` and grants nothing.
   The callback is pure: it starts no process, initializes no display and opens no connection.
5. Combine the approved effects with other **explicitly granted** task effects exactly once:

   ```kotlin
   request.answerFromUi(userExplicitlyAccepted) { approval ->
       if (approval != null) {
           // Parent owns policy and any required persistence of this grant.
           val authorization = TaskAuthorization(capabilities.sessionId, owner.runId.value,
               previouslyApprovedEffects + approval.requiredEffects)
           // Publish THIS authorization object for every parent adapter; no copies for R08.
           // Host construction is lazy/pure; no start here.
           val host = AndroidVirtualDisplayOwnerHost(applicationContext)
           val leaf = approval.bind(owner, capabilities, authorization, host, durableJournal)
           // Parent stores leaf + host + approval + same authorization before publication.
           // Parent registers leaf.registeredTools in its existing registry once.
       }
   }
   ```

   The callback must be integrated into the parent's actual asynchronous run admission flow;
   this snippet does not modify/suspend a live parent factory for you. If the run closes while UI
   is pending, revoke/discard the approval, do not publish a late leaf.
6. Use that very same `TaskAuthorization` object for the parent dispatcher. Binding pins owner,
   capabilities, and authorization by reference; copied equivalent authorizations are rejected.
   An approval binds only once. `bind()`/definitions/`prepare()`/`admit()` perform no I/O, journal
   access, startup or initialization. Do not call `start()` from any parent construction callback.
7. On worker execution, the registered model surface is only:
   - `r08_virtual_display_step({"step_id": "<approved finite choice>"})` (WRITE), and
   - `r08_virtual_display_read({"read_id": "<prepared finite choice>"})` (READ_ONLY).

   Schemas reject every additional field. Models cannot submit a token, generation, operation ID,
   classpath, binding, envelope, display ID, raw intent or arbitrary command. The minimal plan
   supports start, initialize, package launch, tap, swipe, key, text, and explicit release. It
   intentionally does **not** expose handoff or shutdown. Any later cleanup integration must use
   its own separately reviewed, exact explicit authorization; close never invokes it.

READ never starts/reconnects/initializes anything. Both admission and execution require this leaf's
previous, explicitly approved successful initialization. No global/current/default display lookup
is possible: business parameters omit `displayId`, which the Java owner resolves to its owned
virtual display. A read-only plan against a fresh leaf cannot make itself usable by creating a
display. Start and initialize should be separate dispatches; a batch containing a READ before a
previously confirmed initialization is rejected as a whole, even if it also contains initialization.

## Durable journal: not optional, not a temporary ID map

`R08VirtualDisplayJournal` is intentionally an interface only. A production parent must implement:

- `claim(intent): R08VirtualDisplayPermit`: serialize across every process/thread, validate the
  entire original immutable intent, atomically commit **and durably flush** before returning
  `EXECUTE_ONCE`, and return that permission at most once. Store binding key, generation, original
  invocation ID, operation ID, operation and full canonical parameters. Any pre-existing claim,
  including a crash-interrupted claim, is `LOOKUP_ONLY`; conflicts must fail closed. Corrupt or
  unavailable state is `UNKNOWN`, not an empty journal. Never erase/reset records to allow retry.
- `complete(intent, evidence)`: durably preserve the observation without restoring execution
  permission, including NOT_EXECUTED and UNKNOWN observations.
- `finalOutcome(bindingKey, generation, invocationId, evidence)`: persist the final result of the
  **central** dispatcher/ledger. Local protocol success can be downgraded by central persistence
  failure. A semantic replay may have an invocation ID without an R08 claim; handle that case
  without synthesizing an execution or inventing another ID.

The operation ID is a 64-character SHA-256 of length-prefixed `(bindingKey, generation,
context.invocation.id.value)`, not a step ID, UUID, timestamp, provisional call, or owner-provided
random value. The binding key itself is the authoritative binding digest. Replaying the same real
invocation yields the same valid ID; reusing that ID for changed operation/parameters must fail.
Start and initialize also have journal intents, even though their underlying protocol operations
use the generation receipt rather than a business operation ID. If a start claim already exists,
no new process is started. Lookup permits send only receipt requests where a client exists and
always return UNKNOWN to the tool: **receipt-only evidence is never “executed this time.”** No
implicit reconnect is implemented.

The central ledger still reserves the whole validated model batch first and controls normal
same-invocation/semantic replay. The journal is the persistent owner-intent bridge, not another
tool ledger. If production does not have this implementation yet, do not publish the tools.

## Final-result feedback, lifecycle and retention

Immediately after the parent's actual dispatcher finishes its durable outcome processing, and
before any next dispatch or lifecycle observation, call:

```kotlin
leaf.observeOutcomes(originalWholeBatch, finalCentralResults)
```

Forward results from both `execute` and `start`/receipt paths, including replay, partial failures,
and `receipt_persistence_failed`. Missing R08 outcomes or any central R08 UNKNOWN taint the leaf.
This is not optional: adapter success precedes final ledger persistence. The leaf counts locally
executed calls awaiting this callback; `heldState().pendingFinalOutcomes > 0` prevents a drained
observation. The parent must additionally drain its own dispatch/creation/feedback barriers.

`heldState()` is a nonblocking in-memory parent status snapshot. It contains revocation, active
execution count, pending final outcomes, start-attempted, initialization and uncertainty. The leaf
strongly retains its endpoint and host. Parent close ordering:

1. Seal parent admissions and call `leaf.close()` (idempotent, in-memory revocation only).
2. Allow already running dispatch/creation and final outcome callbacks to drain; never join an
   owner transport under the UI/controller lock. Revocation does not pretend to cancel an already
   sent effect. The leaf serializes worker execution but `close()` never acquires that I/O lock.
3. Call `leaf.observeClose { result -> ... }`. It returns immediately and uses one daemon for a
   single observation. Marshal its callback to the parent's lifecycle/UI executor if required.
   Map `Drained`/`Unknown` into the parent's existing close protocol; do not add another protocol.
4. Retain the leaf **and host** on UNKNOWN, including central UNKNOWN, pending results, live or
   uninitialized owner processes, transport loss, observation failure, and ambiguous startup.
   There is no automatic release, handoff, reconnect, `destroy`, terminating signal or retry.
   Re-observation is explicit; there is no polling loop. A physical owner drain alone cannot clear
   a latched central/journal UNKNOWN. Reconciliation is outside this minimal leaf.

`OwnerReply.REPLIED` means only validated protocol delivery. Only operation-specific success
flags and receipt states justify Confirmed business output. Valid negative replies, receipt-only
replies and transport loss are conservatively UNKNOWN; none is mapped to NotExecuted after an
attempt. Model output is allowlisted business data, not raw receipts/envelopes. Initialization's
STARTED generation receipt plus validated `initialized=true` proves creation for this live leaf;
receipt lookup alone does not. `release` may be an explicit approved step, but its reply is not
proof of process exit. Physical drainage still comes only from the existing host observer.

## Offline verification source and remaining uncertainty

`R08VirtualDisplayCatalogLeafTest` uses the real central dispatcher, real Runner-issued token,
real v2 client/response validator, and scripted transport. The in-memory journal is **test-only**.
Sources cover setup/prepare/batch rejection without I/O, read-before-init refusal, start separated
from init, copied authorization/capability rejection, extra model owner-field rejection, exact
write effects, canonical parameters, stable actual-call IDs, central replay, lookup-only evidence,
negative/receipt-only/disconnected responses, journal failure, central persistence failure, retained
UNKNOWN, and nonblocking close during an in-flight request without cleanup.

These are authored test cases, **not passing test results**. The parent must compile/run them and
inspect any failures. Device/root/SELinux/owner lifecycle behavior is not tested here; it inherits
all limitations and required device validation from `r08-owner-client-v2.md`. The existing host's
public API was read only; parallel changes to its shutdown protocol must be reviewed by the parent
before wiring. No claim is made that a production persistent journal, approval screen, run-factory
callback, tool registration, final-ledger feedback or parent retention hook is already installed.
