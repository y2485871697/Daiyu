# P09 concrete summary-model adapter (not Controller integration)

`ProviderSummaryModelResolver` implements the existing `SummaryModelResolver` seam.
Pass a lookup of **explicit summary registry entries**, keyed by the saved
`SummaryModelBinding`; a missing entry returns null, and a returned entry with a
mismatched provider/model identity throws. There is no main-model settings loader,
default model, retry, or fallback. Registry IDs are opaque: `apiModel` is the explicit
wire-name mapping and must exactly match the immutable `ProviderSnapshot.model`.
The host still owns credential authorization and obtaining the registry entry.

Every `SummaryProviderConfiguration` supplies a known context window, exact input
budget, wire output cap (or Codex local acceptance cap), additional reasoning reserve, provider/native-instruction
overhead, and total request deadline. The equality checked is:

```
inputBudgetTokens = contextWindowTokens - maxOutputTokens
                    - reasoningReserveTokens - providerOverheadTokens
```

The native instruction's conservative UTF-8-byte estimate must fit the supplied
overhead; the host must also reserve provider framing. These are local conservative
capacity estimates, not provider usage receipts or inferred model capabilities.
`ModelContextSummarizer` continues to count the complete encoded evidence/request
against that input budget. No new cut-selection/compression algorithm is introduced.

The independent `SummaryProviderFactory` uses the existing `ResponsesModel` and
`MessagesModel` constructors with copied settings, native `instructions`/`system`,
and fixed `max_output_tokens`/`max_tokens` for API Key providers. Codex OAuth uses the
same frozen login reference and per-POST credential resolver, with no second API Key;
its request omits the unsupported `max_output_tokens`. OAuth summaries enforce the
configured output limit locally: visible prose plus visible reasoning are conservatively
bounded in UTF-8 bytes, and reported total output tokens are checked when supplied.
Missing usage stays unknown; encrypted reasoning is not counted as visible prose.
Exceeding either limit cancels the request and rejects the whole summary, never truncates
it into an accepted result. This does not impose a server generation or billing cap;
the existing total deadline and final capacity checks remain in force.

The adapter always calls the explicit
empty-tool-definition overload. Provider tool flags do not authorize summary tools.
For the summary request only, custom-body options are restricted to scalar
`temperature`, `top_p`, and the protocol-validated `service_tier`. Model, output or
reasoning budgets, instructions, tool fields and unknown extensions are rejected,
not silently merged. Original provider settings are unchanged.

The concrete session is single-use. It rejects `ToolReady`, discards provisional
text/reasoning, and emits text only from the actual provider session's authoritative
`ProtocolOutput` **after** `next` successfully returns. Incomplete, failed, empty,
refusal-only and cancelled outputs cannot become a summary. Existing cancellation
registration reaches the real session/HTTPS connection.

One monotonic absolute deadline starts at `ModelContextSummarizer.summarize` entry,
**before** freezing originals, ArchiveCodec encoding, prompt construction and token
budget checks. The bound registry entry supplies its duration and the adapter's
clock; `SummaryModelTransport.openWithoutTools(history, deadline)` receives that
same deadline, never a fresh duration. Adapter validation, history freezing and
provider/session construction consume it too. Provider policy/custom-body validation
now occurs during this timed open, before any network connection. Direct legacy
`ModelPort.open` calls start their deadline at adapter entry instead.

`next` schedules real-session cancellation using only the **remaining** milliseconds
(rounded up for the timer, not for acceptance). An expired preparation fails with
`ModelFailure.TIMEOUT` without connecting. A guard immediately before the provider
connection factory also checks the absolute deadline, covering provider request
serialization even if the timer thread is delayed. A connection returned after expiry
is disconnected without sending. Terminal parsing/emission and the summarizer's
final result assembly/acceptance recheck the same deadline: neither partial nor late
success is returned. The existing deadline scheduler also supplies the injectable
monotonic clock; no separate timeout worker/framework is introduced.

This does not relabel the providers' existing 20-second connect / 60-second inactivity
read timeouts as total deadlines. Those I/O timeouts can fail earlier. Preparation
checks are cooperative (not a hard CPU preemption), and disconnect is best-effort,
so a platform that cannot unblock I/O immediately may finish work late, but cannot
connect after observing expiry or publish late success. Timeout/cancellation before
publication leaves originals and the existing archive untouched.

Offline JVM coverage is in `ProviderSummaryModelTest`: both real wire bodies,
configuration freezing, opaque/exact bindings and budgets, unsafe custom options,
no tools/no fallback, terminal-only output, provider/HTTP/network errors, no replay,
active cancellation, and independently fired total deadlines. Deterministic clock
fixtures additionally cover preparation exhaustion without a connection or original
mutation, adapter preparation, remaining-time scheduling without reset at `next`,
late connections, late successful responses without a timer callback, final acceptance,
and nanoTime wraparound. This change does not wire the Controller, change DB
transactions, or claim end-to-end P09 completion.
Builds/tests/network have not been run in the implementation agent worktree.
