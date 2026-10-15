# Question module integration

The registry/barrier persist answers; they are not the central tool effect ledger.
One live registry per store/owner is required. A page reattach must reuse the run registry.
Use the scoped QuestionRegistry(store, identity) constructor for production, not the standalone fallback.

QuestionCardView renders a chat row. bind requires record.callId == identity.storageKey.
QuestionCardBinding handles UI subscriptions/submissions/stop on a serial writer separate from
any blocking Runner executor. Create store and registry on a worker. Attach/detach on main;
submit/stop enqueue work. Stop fences new submissions immediately and interrupts the same
barrier off-thread. Retain the store until interruption succeeds; retry cleanup on failure.
Detach only removes UI subscription, it does not submit or interrupt a question.

Use provider call ID to derive the scoped QuestionIdentity, but never replace the outer ToolCallId
with storageKey. Historical cards are read-only. A saved answer is not proof a model continuation
or central ledger completion occurred. Unknown effects must not be replayed.

No ask_user tool is registered by this module. Central whole-batch USER_BARRIER schema,
trusted identity, ledger admission, pending continuation, owner fencing, and execution-slot release
must be wired by the integration owner before enabling it. Native QuestionHost is a legacy candidate
helper, not a substitute for the retained chat-card UI.


## Run-retained asynchronous consumption adapter

`QuestionRunSession(registry, owner, writer, completionWorker, ownerIsLive, onFailure)` reuses the SAME scoped
registry and exposes its `binding`. `writer` must be serial; the completion worker must be a separate
executor and must not consume a Runner execution slot. Both must be genuinely asynchronous, not inline executors. The constructor rejects the same executor
object; the host must also avoid wrappers around the same underlying single-thread executor.

`begin(identity, realInvocation, batchToolNames, onPending, onResult)` validates raw JSON and exact
owner/call/batch identity without storage IO, then schedules durable registration on writer. A true
return means LOCAL SCHEDULING ONLY, not durable admission or central ledger success. `onPending`
and the typed Answered/Stopped/Rejected/Unconfirmed result run on Android main. The completion
worker waits on the same barrier, so an answer saved before waiter startup is retained. Same call IDs
are not accepted twice within this live session, even after an unconfirmed outcome. No automatic retry.

`stop(done)` fences new begins and answers immediately, then closes the same barrier off-main.
`done(true)` requires durable interruption and all scheduled waiters to have exited. A rejected
executor or failed interruption returns false; retain registry, injected store and executors for explicit
cleanup retry. The session does not close external storage or shut down injected executors. Normal
run completion and startup failure must call stop too. The controller must publish its terminal fence
before stop; a callback already started cannot be revoked by this adapter.

Result callback failure fences this session, requests interruption and calls the mandatory
`onFailure(identity, exception)` report on main; failed internal cleanup reports
`question_cleanup_unconfirmed`. onFailure must record this without throwing, never initiate a retry.
Result delivery is never replayed.
The caller must treat a thrown callback as an unconfirmed continuation and explicitly finish cleanup.
Process recovery must interrupt obsolete owner records before a new run session; constructing this
adapter is not recovery authorization. Do not begin old UNKNOWN calls merely because a stored
answer exists. Reading historical cards does not need or authorize a live QuestionRunSession.

This remains a module adapter: no ask_user capability is enabled, no central PreparedTool signature
or Runner changed, and no ledger reservation manufactured. The integration owner must supply the
checkpoint's trusted owner and original provider invocation, release/suspend the public execution slot,
consume the result only under its live owner fence, and mount only Binding+Card. A typed Answered
means durable question answer, not model continuation accepted or central ledger complete.

CardBinding attachment epochs now fence the worker before stale attach/refresh storage reads as
well as before main-thread publish. Detach remains a UI operation, never question cancellation.
Drafts are still preserved only by rebinding the SAME CardView; across new-view recreation remains
an integration requirement, not a new persistence/schema guarantee of this adapter.

Host begin/stop and continuation admission must be serialized on one control thread (normally main).
ownerIsLive is a nonthrowing read and must have cross-thread memory visibility. An answer write already
started before stop may durably finish; stop prevents a new live continuation, not rollback of that write.
A pre-interrupted completion worker also fences this session and requests the same retryable cleanup.
