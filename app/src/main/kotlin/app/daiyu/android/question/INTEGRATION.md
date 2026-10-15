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
