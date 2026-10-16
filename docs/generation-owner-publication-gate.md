# Generation owner publication gate: parent wiring contract

## Two different lifetimes

`GenerationAssetPublicationGate` belongs to the **persisted owner**, not a run's cancellation token or a history reader. `GenerationAssetFence` belongs to one execution or reader. A local fence checks its cancellation and its real owner validator while holding the shared publication monitor.

- `GenerationAssetFence(owner, validator)` remains source-compatible and creates an **independent** gate. This is useful for isolated callers/tests; it is not the production controller wiring.
- `GenerationAssetFence(owner, publicationGate, validator)` injects the parent-owned gate.
- `fence.revokeAdmission()` is a nonblocking local admission seal. `fence.revoke()` is a worker-only local barrier. Neither revokes sibling fences or history. Without an earlier local seal, the legacy `revoke()` lets an already admitted operation finish first.
- `gate.sealAdmission()` is a nonblocking, terminal owner admission seal. The check after the real validator closes the validator-to-publication gap when seal races a validator.
- `gate.revokeOnWorker()` seals and waits for any already admitted publication to leave the monitor. **Only its return is permission to mutate/delete the owner.** An action already inside its publication callback may finish before the barrier returns; this is intentional.

No new Android APIs are used; the synchronization uses monitors and `AtomicBoolean` and preserves minSdk 26.

## Exact minimal controller wiring

The parent must retain **one `ControllerGenerationResources` for each actual account/DB lifetime**, including accepted history reads and later sessions. Its database provider must capture that account's actual DB, not resolve whichever DB is active later. Do not allocate a Resources per session, per reader, per recomposition, or per recovered tool call. Do not discard it on ordinary Stop. There is no global/static map.

Within that instance, the stable key is `(accountScope, conversationId, runId)`; accountScope is fixed by the Resources instance. All tool bindings/assistant invocations of that run share the gate, but each fence still validates its exact persisted binding. Reconstructed equivalent owners therefore share a gate without relying on Kotlin object identity. A different run has a different gate.

`ControllerGenerationSession.openLeaf(..., retainedResources, ...)` now passes `retainedResources::createFence` through `ConfirmedGenerationPreparation.openLeaf` to `RunGenerationTools`. Nothing else is needed at this session call site. A parent assembling a leaf directly must pass:

```kotlin
val leaf = token.openLeaf(
    owner, task,
    resources::database, resources::assets, resources::validateOwner,
    networkPolicy, isCurrentOwner,
    fenceFactory = resources::createFence,
)
```

`RunGenerationTools` also exposes the optional named `fenceFactory` argument. The factory receives both the real `GenerationAssetOwner` and the writer's token-aware validator; it must retain that validator. It must not substitute a reader's validator or bypass execution cancellation. `Resources.assets(fence)` rejects independently constructed/unshared gates, rather than silently pretending a cache synchronized them.

History uses the same Resources:

```kotlin
val reader = resources.openOutputs(persistedOwner) // worker; real owner/SQL checks
try {
    val metadata = reader.metadata(attempt, outputIndex)
    reader.openOriginal(attempt, outputIndex)?.use { original -> /* private consumption */ }
} finally {
    reader.close() // nonblocking reader-local seal; does NOT revoke a writer
}
```

Ordinary run Stop/cleanup calls `session.revokeAdmission()` then schedules existing worker cleanup. **Do not seal an owner gate for Stop or reader.close.** Accepted history must remain readable after execution cleanup. The caller still owns previously returned original descriptors; no gate can recall them.

## Destructive owner change: seal -> worker barrier -> DB/change

Choose the narrowest affected persisted scope:

```kotlin
// Under the parent's short control lock if needed; pure in-memory, no validator/DB/FS/wait:
val revocation = resources.sealRunPublicationAdmission(conversationId, runId)
// Or resources.sealConversationPublicationAdmission(conversationId)
// Or resources.sealAccountPublicationAdmission()

// On the parent's worker, OUTSIDE UI/control locks and BEFORE any DB transaction:
revocation.revokeOnWorker()

// Only now may the parent begin its existing owner delete/mutation transaction,
// or publish a real account/owner switch. Transport/run draining remains separate.
```

The Resources scope seals include tombstones for **future** fences, even when no tool/reader existed at seal time. New calls/readers must never reopen a deleted run merely by getting a new fence. There is no unseal method. Do not replace Resources with a fresh instance to evade its tombstones while old workers/readers still exist. After a genuine process restart, normal persisted owner authorization still applies.

`ControllerGenerationPublicationRevocation.revokeOnWorker()` has no timeout: return is the physical gate barrier, not a best-effort cancellation acknowledgment. If its worker has not completed (or cleanup scheduling failed), retain the Resources/revocation and do not delete/switch the owner. The gate barrier is not an HTTP, Runner, or receipt-persistence drain; keep the existing cleanup/Unknown retention protocol too.

Lock order is **file lock -> publication gate -> owner validator / DB**, or gate -> DB for SQL-only work. Never acquire the gate from inside a DB transaction, and never hold the publication gate across a later file-lock acquisition. This is why the output reader gates SQL-only metadata reads but does not wrap all of `store.open()` in an outer gate. No blocking revoke/close belongs under a UI/controller lock. The Resource's gate-map lock protects only in-memory maps/seals and is never held while validating owners or doing file/DB work.

## SQL companion integration requirement

This change deliberately does not edit `SQLiteGenerationOutputStore` or the independently reviewed `MessageGenerationAssets.admitRegistration(expectedOwner)` addition. The parent must combine that companion patch: its **whole final registration transaction**, including reauthorization/readback, must execute inside `assets.admitRegistration(expectedOwner)`, which delegates to this asset's `fence.admit`. It then holds this same shared owner publication monitor. Merely doing `assets.pending()` before a transaction is not sufficient. Without that companion integration the baseline output store still has the known file-check-to-SQL gap; this patch alone does not claim otherwise.

This gate concerns generated-asset publication/registration and owner-authorized reads; it does not replace the attempt ledger, dispatcher receipt persistence, or destructive-transaction policy.

## Added regression coverage (not executed by the implementation agent)

- `GenerationAssetPublicationGateTest`: sibling local cancellation isolation; terminal owner seal including future fences; seal while validator is paused; physical barrier waiting for an already admitted publication callback; legacy independent constructor.
- `MessageGenerationAssetsTest`: actual staged publication is rejected when shared owner seal races the final validator; local reader/writer cancellation preserves already accepted original files/history until owner revocation.
- `RunGenerationToolsTest` / `ControllerGenerationPreparationTest`: injected factory reaches execution through the token, and leaf/token cleanup does not revoke a sibling history fence.
- `ControllerGenerationResourcesTest` (device, offline SQLite/static PNG): reconstructed owner/tool bindings share a run gate; scope tombstones cover future fences; independent gate rejected; reader.close preserves writer commit/registration and writer cleanup preserves history; owner seal blocks history.

No build, tests, branch, push, shell, or network operation was performed by this implementation agent. The parent must run compilation/regressions after merging the SQL companion and controller wiring.
