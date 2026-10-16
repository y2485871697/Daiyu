# Message image handoff (P17)

This package does not commit SQLite transactions and does not decide whether a message was accepted.
`MessageImageAssetStore` copies frozen original bytes to a separate private message root. Never store
an `ImageAttachment` draft reference as accepted history.

## Host wiring

1. Create one app-scoped `ImageAttachmentStore` and one `MessageImageAssetStore` with **distinct private
   roots**, using the same `ImageAttachmentLimits`. A suggested message root is
   `File(context.filesDir, "media-message-images")`; do not use cache or user gallery directories.
2. Freeze draft inputs with the existing `draftStore.freeze(draftOwner, attachments, supportsImages)`.
   Preserve that snapshot for the acceptance attempt. Keep a stable, recoverable message identity
   (persist/derive it from the durable pending request before publication). Retrying must not allocate
   a new message ID or silently substitute different draft contents.
3. Under the host's common acceptance/deletion/recovery lock, call
   `assets.prepare(MessageImageOwner(conversationId, messageId), draftOwner, frozen, isCancelled)`.
   This returns an ordered `List<MessageImageReference>`. It validates ownership, limits, metadata
   and digest and publishes a whole batch by one directory rename. Same owner/source/ordered batch
   is idempotent; a different batch using that owner fails closed.
4. In the actual DB transaction, persist each reference's `toJson()` alongside the message and
   idempotent acceptance receipt. No DB transaction is implemented here. The public lock must cover
   both prepare and the DB acceptance decision so orphan cleanup cannot race a future commit.
5. Only after a known successful DB commit, remove **each accepted original reference** via
   `draftStore.remove(draftOwner, frozenItem.attachment)`. Do not `clear` a live draft: newer images
   might have been added. Failure of draft cleanup must not roll back/delete committed message assets.
6. Failure/cancellation/unknown DB outcome must not consume the draft. Retain prepared assets until
   reconciled against authoritative DB state. In particular, never call message deletion in a generic
   send `finally` block. If the acceptance receipt already exists, use its saved references rather than
   trying to freeze a draft which may have been legitimately removed after the previous commit.

## Read-only history

- Persist/restore using `reference.toJson()` / `MessageImageReference.fromJson(encoded)`.
- Give history/model/preview code only `MessageImageResolver`, obtained from `assets.resolver`.
- `resolver.read(messageOwner, reference)` verifies manifest identity and the original byte digest.
- `resolver.freeze(messageOwner, references, supportsImages, isCancelled)` yields the existing
  `FrozenImageAttachment` model API. Its metadata owner is a message-derived synthetic owner; never
  send that metadata to the draft store. It cannot re-open a provider or fall back to a draft.
- `AndroidMessageImagePreviews(resolver).decodePreview(messageOwner, reference, maxEdge)` shares the
  existing bounded EXIF-aware preview implementation. Caller owns/recycles the returned bitmap.

## Reconciliation and exact cleanup

With the same common host lock held and a known message identity:

- `assets.cleanStaging(owner)` removes only that owner's abandoned unpublished staging directory.
- `assets.preparedReferences(owner)` inspects the published batch or an interrupted-deletion
  tombstone. This is **not** proof of DB acceptance, nor a substitute for history references.
- If the DB confirms references exist, retain assets; verify readability, and report missing/corrupt
  bytes without recreating them from a different source.
- Only after authoritative DB absence (or after the DB transaction deleting the message), call
  `assets.discardUnreferenced(owner, exactReferences)`. It checks the complete ordered batch, renames
  it to a tombstone, then unlinks only its exact files. Retry with the same references after failure;
  `preparedReferences` can recover a tombstone's references. Empty tombstones are also retryable.
- Unknown DB status means retain, not delete. No age-based or whole-conversation filesystem GC exists.
  The parent must durably keep pending identities/cleanup work so process death does not lose the
  ability to target reconciliation; this store deliberately does not enumerate other owners.

## Guarantees and limits

Original draft bytes are never moved or removed by message preparation, cancellation, or rollback.
Within one app-scoped store, a complete message batch becomes visible by an atomic directory rename;
partial unpublished writes can be cleaned/retried. Files are synced before publication. This is **not**
a filesystem+SQLite atomic transaction or a directory-fsync/power-loss durability guarantee. The
sequence prevents ordinary process-death/failed-acceptance paths from deleting bytes borrowed by an
accepted message. Cross-process mutation and adversarial same-UID filesystem races are not supported.
The trusted app root is anchored once (including Android's platform parent aliases); owner directories,
metadata/data symlinks, traversal IDs and replaced root symlinks are rejected on later access.

## Validation status

New JVM tests cover handoff/reopen, idempotence, ordering/limits, exact deletion, cancellation,
publication failure, explicit unknown-DB reconciliation, deletion tombstones, corruption and path
boundaries. Android JPEG testing now uses a platform-written EXIF fixture, checks both filename and
InputStream orientation readers, retains rotation/original-byte assertions, and extends those
assertions through message handoff + draft/provider deletion. The previously hand-injected minimal
APP1 failed in the supplied API26 report before production import; the precise platform parser
internal cause is not established here. No build or test was run by this implementation agent;
parent must execute the final targeted JVM/API26/API37 regression and DB integration matrix.
