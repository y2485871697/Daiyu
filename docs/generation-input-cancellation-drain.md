# Generation input: cancellation and physical drain

This is a narrow follow-up to the generation-input candidate's P2 review. It changes only the generation-form content input adapter/host and dedicated regression coverage. Paid confirmation, generation submission/poll/download clients, content IDs, and the frozen editor/form identity comparison are unchanged. No global checklist or release notes are changed.

## Ownership and bounds

- `MainActivity` captures `applicationContext.contentResolver` **outside** its input-reader lambda. A blocked provider reader therefore does not need the Activity's resolver/getter or Activity instance.
- Each actual import creates its own `CancellationSignal`, registers `signal.cancel`, checks cancellation before open, and passes the signal to the three-argument `openAssetFileDescriptor`. Once open returns, it registers stream closure, including when the provider returns a stream after logical cancellation. Original bytes still pass through the existing bounded importer and its `use` scope. Registration references are cleared in the adapter's `finally`.
- `GenerationInputWorkers.shared` owns one process-wide import slot, one stable reader thread and two stable cancellation lanes. Signal cancellation and stream closure have separate lanes because a provider can block inside `CancellationSignal.cancel()` itself. The reader queue is bounded to one and the cancellation queue to two. There are at most two cancellation registrations (signal and stream) for the single admitted request.
- No executor is created or shut down by an Activity host. A replacement Activity can still show its form/picker, but importing while the shared slot is occupied returns the existing safe input-failure path; it does not enqueue another provider open or create another executor.
- The process manager owns only the admitted request, the application-resolver reader and a weak host drain callback. It does not retain an Activity through `current`, UI dispatch, `changed` or `finished` callbacks while a provider blocks.

## Logical cancellation is not physical completion

`invalidate()` immediately marks the request cancelled and revokes permission to apply its result. The picker callback tombstone and every existing identity check remain intact. A request that has started reading remains logically pending on its live host until its drain callback reaches the UI. A request that never started reading can be cleared immediately.

Cancellation callbacks are scheduled, not called inline on the UI thread. UI invalidation/destruction does not join, await termination, close a provider stream synchronously, or infer completion from interruption/`shutdownNow`/a cancelled Future.

The worker wrapper's `finally` records that the entire open/read/inspection/resource-close stack has returned. The shared slot is released only after that observation **and** the return of any scheduled signal/stream cancellation callbacks. Keeping those callbacks in the drain condition prevents a blocking close/cancel from accumulating more work over subsequent Activity recreations. Closed hosts suppress UI delivery; their `pending` getter continues to describe a physically undrained request until it exits. The stable manager retains capacity even if the old host is collected.

If a provider ignores cancellation, or its `close`/cancel implementation never returns, importing remains unavailable for the process lifetime. This is an intentional bounded fail-closed state, not a claim that Android can force an arbitrary provider to drain. The UI remains responsive; no timeout silently creates replacement workers. The existing input failure toast covers a replacement host encountering occupied capacity.

## Regression coverage and limits

- Existing `GenerationInputHostTest` retains picker suspension/tombstone, frozen-identity, byte-bound, MIME and dimension tests; its test executor now uses an isolated injected manager.
- `GenerationInputDrainTest` uses latch-controlled fake providers and real background lanes. It covers blocking open cancellation returning off the caller/UI path, a blocked read released by stream close while signal cancellation is still blocked, pending/capacity retention until cancellation finally, close with a non-cooperative open, a stream returned after cancellation, 32 successive replacement hosts sharing the still-occupied manager, safe reuse after drain, and rejection of all late application.
- A dedicated source regression verifies the actual Main adapter's signal/resolver/stream wiring, destruction close, process-shared default manager, weak callback, and absence of per-host executors/`shutdownNow`/termination waits.

These changes were source-inspected only in the implementation worktree. **No build, unit test, Android instrumentation test or real remote-provider test was executed here** (execution belongs to the supervising agent). The fake-provider tests do not prove that every third-party provider honors Android cancellation; bounded ownership when it does not is part of the contract above.
