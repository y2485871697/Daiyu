package app.daiyu.android.backup

import app.daiyu.android.media.MessageImageOwner
import app.daiyu.android.media.MessageImageReference

/** An app-scoped message-asset capability, never a filesystem path or a URI.
 *
 * The host must serialize backup/import with message acceptance/deletion using its common
 * asset/DB lock. [read] must verify the store's owner manifest as well as original size/digest.
 * [restore] receives one complete owner batch, validated against persisted package messages,
 * not authority obtained from an untrusted reference alone. It must validate all bytes before
 * atomically publishing a new owner; an existing identical batch is verified, never replaced.
 * Conflicting existing assets MUST fail without modification. Batches are sorted by image id;
 * equivalence of an existing batch is by id/metadata, not order or original draft provenance.
 *
 * No deletion/rollback capability is exposed: a later SQLite rollback may leave an unreferenced
 * immutable batch, but may never remove or overwrite an existing message's assets. The host's
 * DB-authorized orphan reconciliation, not an import finally block, owns eventual cleanup.
 */
interface BackupImageAssets {
    fun read(owner: MessageImageOwner, reference: MessageImageReference): ByteArray
    fun restore(owner: MessageImageOwner, references: List<MessageImageReference>, contents: List<ByteArray>)
}
