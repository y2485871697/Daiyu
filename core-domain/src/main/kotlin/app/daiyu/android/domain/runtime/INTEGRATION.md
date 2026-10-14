# Frozen runner integration contract

Production construction (the trailing publish lambda remains supported):

```kotlin
val runner = ChatRunner(model, messages, catalogTool,
    RunnerOptions(runtimeStore = databaseRuntime,
        batchExecutor = BatchToolExecutor { message, calls ->
            dispatcherAdapter.executeBatch(message, calls)
        },
        onUsage = { usage -> /* identity-scoped usage accounting */ },
        reconnect = ReconnectPolicy(maxAttempts = 0))) { message ->
    // Publish only if controller still owns message.conversationId/runId/id.
}
executor.execute { runner.run(assistant, history) }
runner.pause()
// Parent transaction appends a COMPLETED user message with the same conversationId/runId.
runner.supplement(persistedUserMessage) // Queues only while paused; never resumes.
runner.resume()
runner.stop()
```

The batch adapter must validate all invocations before any tool, preserve order, enforce the
question barrier and use the tools module's persistent equivalent-operation ledger. Runner's
`validateBatch` runs before the adapter, and mixed `ask_user` is rejected internally. The old
BatteryTool execution path is preserved for compatibility. Side-effect catalog entries on that
legacy path require a durable ToolExecutionJournal. Unknown results fail closed; Runner never
changes route or automatically retries tools.

## Atomic persistence minimum

Implement `AtomicRuntimeStore` from ExecutionControl.kt. `commitCheckpoint` is the sole message
writer when configured (ConversationStore.save is NOT also called). Persist the complete
RunCheckpoint, using an explicit versioned codec in the platform layer. Its fields are ordinary
Kotlin data, not automatically JSON-serializable:

- message (unchanged legacy Message/blocks/status/protocol shape), history
- phase enum name, tools(invocation, disposition enum name, nullable result)
- outputs(callId, output), supplements, round, requestUnconfirmed, messageReconciled
- owner (runner-generated opaque token), revision (monotonic Long)

Do not put runtime metadata in protocolItems: those are model protocol output. No credentials,
transport handles or callbacks belong in the codec. Conversation.kt was intentionally unchanged.

Minimal transaction algorithm for a NEW run:

1. First checkpoint has revision=1 and a new owner. Acquire that run only if it has no live owner
   and the persisted assistant is RUNNING. Otherwise reject (do not silently take over).
2. Subsequent writes require stored owner==checkpoint.owner AND
   stored revision==checkpoint.revision-1 AND stored phase!=TERMINAL.
3. In the same transaction CAS the checkpoint row and update the identity-matched message row.
   A terminal message implies checkpoint.phase=TERMINAL. Commit before returning.
4. Reject stale/terminal writes. Publish callbacks are outside the persistence contract and their
   exceptions do not change a committed terminal. A lost commit receipt requires idempotent
   database reconciliation, never remote retry.

`reconcileCheckpoint` runs only after the previous owner is known dead. In one transaction read
both message and checkpoint, fence the old owner, and reconcile: an already-terminal message
wins (especially COMPLETED), otherwise write INTERRUPTED; IN_FLIGHT tool records become UNKNOWN.
Set messageReconciled=true, persist a recovery owner/revision, return the actual committed
checkpoint. Repeating recovery must return that same terminal outcome. No model open or tool
execution occurs in `runner.recover(checkpoint)` / `ProcessRecovery.recover`.

Non-atomic RuntimeStore is a compatibility snapshot hook, NOT production atomic durability.
Its recovery uses a persisted messageReconciled=false intent, then
ConversationStore.reconcileRecovery, then a true acknowledgement. Override reconcileRecovery to
save RUNNING or verify an identical terminal row idempotently. The default delegates to save and
cannot alone guarantee cross-process reconciliation with the existing RUNNING-only DB update.

## Reconnect and failures

Default retries=0. Opt-in retries only invoke ResumableModelSession.resume. Its implementation
must resume transport without reposting model input/tool output and only deliver unacknowledged
events. An ordinary ModelSession never retries, even if the policy permits retries. Old sinks
are fenced by request generation and lifetime, including Usage, TextFinal and protocol output.
A paused/cancelled stream does not confirm pending tools. Supplement during tools waits until
the entire batch returns; supplementation/pause does not advance the logical completed round.

Persistence, batch validation, unsafe replay and unresumable transport failures terminate.
Cancellation signals local intent; STOPPED does not claim remote cancellation succeeded.
UI callbacks cannot downgrade durable terminals. Atomic adapters must reject/resolve ambiguous
commit receipts according to their transaction semantics.

## Verification status

Added RunnerControlTest and RunnerCheckpointTest under test/domain/runtime. No shell/build/test
execution was available to the implementation agent. Parent must compile and run these plus the
existing ChatRunnerTest/ChatRunnerRepetitionTest and Android storage/controller integration tests.
