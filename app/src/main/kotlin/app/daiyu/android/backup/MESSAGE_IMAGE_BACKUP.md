# Message image references and backup boundaries

Message image blocks carry immutable, owner-bound reference metadata. Serializing
that metadata is not a copy of the referenced image bytes, and a successful
metadata round trip must not be advertised as a portable image backup.

## Existing storage paths

- `ArchiveCodec` stores message blocks and optional history segments in private
  archive blobs. Image references must use the shared strict reference codec and
  retain their conversation/message ownership. Archive decoding must still run
  the existing history validation; structural JSON validity alone is insufficient.
- `ConversationDatabase` owns the text-backup parser, message persistence and
  import transaction. `NeutralBackupService` delegates text backup validation to
  it rather than maintaining a second message codec.
- `NeutralBackupService` exports blob bytes reachable through `context_archives`
  and explicitly registered `neutral_assets`. Its `attachments` inventory counts
  `neutral_assets`, not image blocks in message/history metadata.
- The message image asset store is a separate store. There is currently no path
  in `NeutralBackupService` that enumerates it, copies its originals, remaps their
  owners on import, or verifies that imported references can resolve there.
  An archive blob containing an image reference is still only metadata for that
  image, even though the archive blob itself is included in the package.

## Failure and integration contract

Malformed reference metadata, wrong conversation/message owners and invalid
history must fail validation, not silently drop an image, emit text in its place,
retain a source URI, or bypass the strict reference codec. Rejection must not
replace existing messages or remove the source image assets. Text/neutral backup
import keeps its existing preflight and transaction behavior; failed imports do
not authorize asset-store cleanup. Source backup inputs remain read-only.

Portable original-image backup remains a separate integration task: inventory
all referenced originals (including archived history), resolve them with the
proper owner, verify size/digest and limits, include their bytes in the package,
then stage and atomically publish assets together with validated DB references.
Missing/corrupt originals must produce an explicit failure, not a nominally
complete image-backup success. Until that integration exists, backup consumers
must distinguish preserved reference metadata from available image originals.
