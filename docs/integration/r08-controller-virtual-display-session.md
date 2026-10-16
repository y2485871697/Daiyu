# R08 parent session helper and approval surface

## Scope and status

Added only:

- `app/src/main/kotlin/app/daiyu/android/ControllerVirtualDisplaySession.kt`
- `app/src/main/kotlin/app/daiyu/android/vdapproval/VirtualDisplayApprovalScreen.kt`
- dedicated JVM/Compose tests and this document.

This is a reusable parent integration component, **not installed routing**. No edits to
ChatController, MainActivity, RunCatalogTools, owner/client/leaf, journals or public DB.
No shell, builds, unit tests, instrumentation or real root/display tests were run by the
implementation agent. Tests below are source artifacts, not claimed passing results.

Sources examined in this worktree: `VirtualDisplayApprovalHost.kt`,
`R08VirtualDisplayPlan.kt`, `R08VirtualDisplayCatalogLeaf.kt`,
`AndroidVirtualDisplayOwnerHost.kt`, `VirtualDisplayOwnerClient.kt`,
`RunCatalogTools.kt`, `ExecutionControl.kt`, `ToolRegistry.kt`.
External candidate worktrees were not accessible via the scoped file tool and were not
retried. The journal is deliberately injected using this tree's `R08VirtualDisplayJournal`
interface: the parent initializes its real persistent journal beforehand. No alternate
journal, runner, DB, network transport or dispatcher is introduced.

## Minimal parent wiring

The parent keeps the session in its existing retained-resource record **before** allowing
Runner binding. Only construct it for a send whose DB acceptance was already confirmed.
Do not change that accepted receipt into rejection when the subsequent approval is denied.

1. Construct `ControllerVirtualDisplaySession.production(context, capabilities, frozenWrites,
   frozenReads, parent)`. Construction does not start root, allocate a generation or show UI.
   The capabilities object must be the *actual same instance* passed to RunCatalogTools.
2. Supply `Parent.reserve`: use the existing durable lease allocator to reserve/persist the
   generation and exclusive binding before returning a `Lease`. Its `journal` is the real
   `R08PersistentJournal` instance initialized by the parent, exposed through
   `R08VirtualDisplayJournal`. `durable=true` is a storage contract, not a substitute for
   fsync/atomicity. Lost reserve receipts throw and retain UNKNOWN; there is no second reserve.
3. Use the existing `RunCatalogTools.authorizationFactory` slot, on Runner's worker:

   ```kotlin
   authorizationFactory = { realOwner ->
       val authorization = vdSession.authorizationFactory(realOwner)
       vdSession.registeredTools(realOwner, capabilities, authorization)
           .forEach { registry.register(it) }
       authorization
   }
   ```

   This is before `model.open`, after the real Runner token exists. There is no temporary
   owner or separate authorization for the leaf. `Parent.combinedAuthorization` returns
   the ONE combined `TaskAuthorization` containing other genuinely approved effects plus
   this request's exact effects. If another resource pins an existing authorization,
   return that exact reference and ensure it already contains all grants. Never copy an
   authorization merely to satisfy a leaf, silently widen old approval or auto-approve
   on capability enablement. Duplicate factory invocation is rejected, never replayed.
4. `Parent.showApproval` posts the provided request to the parent's UI lane without waiting
   for that lane. Render:

   ```kotlin
   VirtualDisplayApprovalScreen(request, onDecision = vdSession::answerFromUi)
   ```

   Keep the original request and its owning session callback paired. The screen captures
   `onDecision` with `remember(request)`, rather than forwarding old effects to the latest
   callback. Even if request and session callback change in the same recomposition, the old
   request's disposal denies through its original session; the new session receives nothing
   until its own request is answered or removed. Callback-only changes do not rebind an
   existing request. `pendingApproval()` is a snapshot for parent presentation; reading it
   grants nothing. Confirm/reject are one-shot, and stale requests return false without
   acting on a newer one. Replacement, removal, back and explicit rejection all send
   rejection for the original request through that original callback. Disposal also sends
   denial after an explicit answer; the owning session's single-use identity check ignores
   it. This UI requires a BackHandler-capable Activity host; it does not install a route itself.
5. Wire parent cancellation (including navigation, controller disposal, stop and timeout)
   to `vdSession.close()` **even while authorizationFactory is waiting**. The latch is
   interruptible and close releases it, with no polling. The worker invokes
   `Parent.stopAcceptedRun(realOwner)` at most once when approval is denied/cancelled or
   setup fails. That callback targets only the already accepted run: no resend, rollback
   of message acceptance, draft restoration or unrelated run cancellation.

## Whole-batch final feedback (current leaf ticket API)

The helper does not execute a central batch. The parent brackets its existing central
executor with `vdSession.beginBatch(originalWholeBatch)` and
`vdSession.finishBatch(sessionTicket, actualFinalResults)`. The original whole batch is
not filtered to virtual-display tools. Preserve a ticket through a deferred barrier;
finish only with terminal central receipts, including persistence failures/cancellation.
A missing final callback retains the session and lease.

`Parent.beginBatch` is the small compatibility seam around the retained leaf's current
API, with no dependency on its ticket's concrete type:

```kotlin
override fun beginBatch(
    leaf: R08VirtualDisplayCatalogLeaf,
    originalWholeBatch: List<ToolInvocation>
): ControllerVirtualDisplaySession.BatchCompletion {
    val leafTicket = leaf.beginBatch(originalWholeBatch)
    return ControllerVirtualDisplaySession.BatchCompletion { finalResults ->
        leaf.finishBatch(leafTicket, finalResults)
    }
}
```

The closure strongly retains the **actual leaf ticket**. It must not rebuild a batch,
lookup another leaf, invoke an adapter directly, substitute local success, execute tools,
or manufacture a new dispatcher/ledger. The helper checks session-ticket identity and
consumes final completion once. Feedback runs outside helper locks; failure retains
UNKNOWN and is never retried automatically. Outstanding tickets and in-flight feedback
prevent lease release, including for batches rejected before any local leaf execution.

The dedicated JVM fixture now uses the same `leaf.beginBatch(originalWholeBatch)` /
`leaf.finishBatch(leafTicket, finalResults)` pairing above. It registers before dispatch,
retains the actual leaf ticket in the completion closure, and forwards the final whole-batch
results without filtering. The deleted post-hoc `observeOutcomes` API is not used. The
production session helper is unchanged; missing/extra receipt validation remains the
leaf's responsibility rather than being duplicated in this test adapter.

## Retention and physical exit

`close()` performs pure revocation and approval-wait cancellation. It does **not** release
a display or a lease, disconnect the socket, initialize, hand off, kill, join, or request
shutdown. It never clears host/leaf references. The parent calls
`observeCloseOnWorker(parentDrained = true)` only after its own Runner, dispatches and
final feedback have physically drained. This is a one-shot observation, not a timer loop.

Drain requires no binding work, outstanding batch tickets/callbacks, active leaf calls,
pending final outcomes, or sticky uncertainty. For a launched host it additionally
requires the existing host's authenticated STOPPED marker, launcher exit **0**, known
original owner PID and observed disappearance of that PID. A socket EOF, release reply,
`su` exit alone, local `initialized=false`, deadline or UNKNOWN does not qualify.
A never-launched host can drain only if the leaf never attempted start. Reserve failure,
lease-release failure or final-ledger UNKNOWN retains resources even after physical exit.

The lease-release write is attempted once, on a worker and outside locks; an ambiguous
release is not retried. Only after `CloseResult.Drained` may the parent drop its retained
session. UNKNOWN retains the same lease, host, leaf, approval, authorization and outstanding
batch closures; it is not an invitation to reconstruct or re-launch the owner.

## Explicit uninitialized shutdown: capability limitation and safe seam

The inspected 1d923481 client/owner does **not** expose an uninitialized-shutdown wire API.
The helper does not invent one, overload release, send shell commands, infer safety from
local state, initialize to enable cleanup, or claim that this maintenance path was run.
By default the production factory leaves the trusted adapter absent: an uninitialized
started owner remains UNKNOWN/retained.

A parent integrating an owner version with an existing trusted shutdown entry can pass
`uninitializedShutdown = { realHost -> ... }` to `production`. Adapter construction must
be non-launching. Its `lookupOnly(generation, binding)` authenticates that exact generation
and binding using receipt queries only, and must independently prove it has never been
initialized. The local Boolean is insufficient. `shutdownOnce` uses the existing trusted
owner entry; never execute/initialize/shell/kill or auto-retry.

Only an explicit separate maintenance action calls
`shutdownUninitializedOnceOnWorker()`. Close and the approval screen never call it.
The attempt is consumed **before lookup**, including UNKNOWN/exception, so no re-send.
It requires a closed session, a prior start, no active/pending operations and no locally
confirmed initialization. It does not grant business writes or release the lease.
Only later physical marker+exit0+original-PID-gone observation can prove drain; shutdown
cannot erase prior journal or central-result uncertainty.

## Dedicated verification artifacts

`ControllerVirtualDisplaySessionTest` uses a real Runner-issued token, central dispatcher
in the *test only*, injected memory test journal/lease and fake endpoint. It covers frozen
plans, identical capabilities/authorization, no pre-approval launch, accepted-run denial,
close during wait/reservation, stale callbacks, complete physical evidence, missing final
feedback, central UNKNOWN, ambiguous start/reserve/release and explicit once-only lookup-
gated shutdown. No production memory persistence is provided.

`VirtualDisplayApprovalScreenTest` uses an isolated ComponentActivity/Compose surface.
It checks full binding/generation, every normalized operation/effect, no decision on
open, explicit confirm/reject, stale callback indication and original-request rejection
on replacement/removal. Regression cases replace the request and session callback together,
assert that only the old session receives disposal denial and the new session is untouched,
then change only the callback and verify confirm, reject, back and removal still reach the
new request's original session. Post-answer disposal is also checked for the same pairing.
These use independent recording callbacks, not real Controller sessions. It is not a
MainActivity integration or a rooted-device test.
The UI uses scrollable selectable full text, no ellipsis, for all write/read parameters,
namespace, operation, target and change digest.

This callback-ownership/fixture follow-up is static source work only: no build, test,
instrumentation, or root/display execution was performed. Its changes are limited to the
approval screen, these two dedicated test files and this document; Controller/Main/Session
implementations and the leaf/owner implementation are unchanged. Compilation and execution
of the dedicated tests, plus real durable journal/lease and trusted uninitialized-shutdown
verification, remain separate validation work; no passing result is claimed here.
