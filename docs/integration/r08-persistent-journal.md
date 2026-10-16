# R08 persistent owner-intent journal (unwired production component)

## Scope and authoritative contract

`app/src/main/kotlin/app/daiyu/android/integration/tools/R08PersistentJournal.kt` implements the
existing `R08VirtualDisplayJournal` contract in `R08VirtualDisplayPlan.kt`, as specified by
[r08-virtual-display-catalog-leaf.md](r08-virtual-display-catalog-leaf.md). It is the app's durable
**owner-intent bridge**, not another tool ledger, dispatcher, operation normalizer, approval
mechanism, or root-owner receipt store. The root owner's `OwnerReceiptStore` remains independent.
No existing leaf, plan, controller, Main, registry, capability list or build file is changed.
The parent remains responsible for real Runner ownership, explicit UI approval, publication,
final-result feedback, revocation and retention. This component performs no owner IPC or effects.

## Parent construction and storage prerequisites

On a worker, construct `R08PersistentJournal(trustedPrivateRoot: Path)` and pass it as the existing
journal parameter when binding the approved leaf. The root must be an **absolute, normalized,
pre-existing, dedicated 0700 app-private directory**. Its ancestors must already be durably
provisioned and must not be replaceable by untrusted writers. Do not use model-derived paths,
shared/external storage, a symlink path, the root owner's receipt directory, or a directory
containing unrelated files. Existing files must be regular 0600 files owned by the root's owner.
The root and all ancestor symlinks are rejected. New files are created 0600, not chmod-repaired.

This follows `OwnerReceiptStore`'s POSIX ownership/permission/no-symlink boundary, not a descriptor-
relative sandbox against hostile same-UID writers, bind-mount aliases, or external deletion.
Every operation requires file locks, file fsync, atomic same-directory rename and directory
fsync. Unsupported facilities are failures, **not** a fallback to weaker copying/renaming.
Before access the implementation also syncs the lock, root and root's parent directory.
Constructor failure is a redacted `IOException`; do not publish the tools on that failure.

A process-wide mutex per normalized root covers opening, locking, releasing and closing the OS
lock channel. This avoids overlapping-JVM locks and POSIX locks being released by another local
channel close. The exclusive OS file lock then serializes cooperating processes. Constructors do
not retain channels or claim lifetime ownership. Per-root mutex entries remain for the process
lifetime to avoid removal/waiter races; use a bounded set of trusted roots. Operations are blocking
worker operations; never run under the UI/controller lock. Interruption fails closed.

## Persistent format and permission rule

The only non-event file is `.r08.lock`. Events use contiguous zero-based 20-digit sequence names
ending in `.event`. Each contains a versioned, length-prefixed, strict UTF-8 binary payload plus a
SHA-256 checksum. The checksum detects damage, not malicious same-UID tampering. A claim stores
**all** of binding digest, generation, original invocation ID, operation ID, operation and exact
canonical parameter bytes. It does not replace full parameters with a hash. The operation ID is
verified against the existing length-prefixed SHA-256 binding/generation/invocation formula.

Bounds: binding/operation hashes are 64 lowercase hex characters; generation follows the plan's
1–64 character identifier rule; invocation is nonblank, control-free and at most 1024 UTF-8 bytes;
canonical parameters are at most 32 KiB UTF-8; an entire event is at most 40 KiB. Malformed Unicode
is rejected rather than silently replaced. Operations are the approved write vocabulary
`start`, `initialize`, `launch`, `input`, `release`. Canonical parameters are opaque exact bytes
from the existing prepared plan: this journal does not parse, normalize or reinterpret JSON.

A first claim is committed as follows while both locks are held:

1. Read and validate the entire history, including field bounds, checksum, strict ordering,
   identity consistency and complete-to-claim links. Corruption is not an empty journal.
2. Create the next `.pending` file exclusively and fsync its parent directory **before writing**.
3. Write the entire immutable record and fsync its file descriptor.
4. Atomically rename the pending file within the same directory; no overwrite/copy fallback.
5. Fsync the containing directory. Only after all operations and lock release succeed may the
   caller receive `EXECUTE_ONCE`.

Any existing matching claim, including a crash after the claim but before execution, is
`LOOKUP_ONLY`. A complete, checksum-valid pending claim also only permits lookup, never
execution. A partial/empty/corrupt pending record is `UNKNOWN`. A valid pending tail prevents all
further appends/new claims; the implementation does not finish its rename, delete it or skip it.
A rename may already be visible after a reported directory-sync/close failure: an identical
later claim is still lookup-only, **not a retry permit or a durability confirmation**.
Mismatched binding, generation, operation or exact parameters for an existing invocation are
`UNKNOWN`. Reusing a supplied operation ID with other fields fails its identity check.

`claim` translates storage/validation failures to `UNKNOWN`; `complete` and `finalOutcome` throw
redacted `IOException` on failure so the existing leaf can latch uncertainty. Errors and the
journal's `toString` do not contain paths, IDs or parameters. The pre-existing interface intent
DTO is not changed; callers must not log its generated data-class `toString` or raw parameter
bytes. Storage is private but **not encrypted**.

## Observations cannot become execution

`complete` appends the full intent and the evidence, requiring an exact prior claim. It never
modifies/deletes that claim, including for `NOT_EXECUTED` and `UNKNOWN`. `finalOutcome` appends
only binding/generation/invocation and the central evidence. A central semantic replay without a
local claim is valid: it creates a final observation, **not a synthetic intent or claim**. Later
claim attempts for this identity are lookup-only and complete-without-claim still fails.

Every observation is retained, including repeated/conflicting outcomes. A later CONFIRMED does
not erase a previous UNKNOWN; there is no API that computes a last-writer-wins success, clears
uncertainty or restores permission. The parent's existing central ledger remains authoritative
for the tool outcome. The existing leaf must still receive final central outcomes immediately;
this component does not wire that callback or reconcile retained owners.

All events are immutable, with no GC/reset/delete/recovery API. Reading the full log is currently
O(number of events); host-selected roots and retention/storage capacity must be planned for a
bounded workload. Unknown files, gaps, invalid observations or truncated/corrupt records fail
closed. Do not treat reopening, deleting storage, changing roots or inventing invocation IDs as
reconciliation. A missing/deleted root fails construction; hostile deletion of an entire valid
history is outside the private-path trust boundary, not a supported reset.

## Dedicated verification sources (not executed here)

`app/src/test/kotlin/app/daiyu/android/integration/tools/R08PersistentJournalTest.kt` adds host
JUnit filesystem tests for:

- first durable claim, exact saved fields, stage order, 0600 mode, duplicate and reopen;
- independent invocations across all five prepared write-operation kinds;
- twelve concurrent threads/instances and four independent child JVMs, plus process restart;
- changed binding/generation/operation/parameters and forged operation IDs;
- every injected write boundary, including file-sync and post-rename directory-sync failures;
- full pending versus corrupt/empty interrupted claims, with no deletion/rewrite/retry;
- checksum damage, truncation, oversized records, unknown files and sequence gaps;
- local completion, central UNKNOWN, later observations without erasing UNKNOWN or the claim;
- central semantic replay with no local claim, and rejection of complete-without-claim;
- bounds, malformed Unicode, redacted diagnostics, symlink roots/ancestors/lock/event files and
  non-private root permissions.

The fault hook is an internal constructor seam; the normal constructor always uses a no-op hook.
These deterministic exceptions test the fsync failure paths, not a real hardware power cut.
The subprocess test launches only the local Java test probe, not a shell or Android owner, and
requires a JVM/POSIX test host. It derives a small classpath from the test, implementation and
Kotlin runtime locations. Unsupported POSIX hosts skip the suite; Android filesystem/device
validation is still needed before deployment. No build, test command, network request or actual
subprocess was executed by this implementation agent. The parent must compile/run and review
these artifacts before wiring.
