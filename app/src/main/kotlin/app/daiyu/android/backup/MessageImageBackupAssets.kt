package app.daiyu.android.backup

import app.daiyu.android.media.MessageImageAssetStore
import app.daiyu.android.media.MessageImageOwner
import app.daiyu.android.media.MessageImageReference

/** Production backup capability over the host's EXISTING app-scoped message asset store.
 * No second store, root directory, draft access, cleanup or independent lock is introduced.
 * The host must serialize backup/import with acceptance/deletion and the associated DB work.
 */
class MessageImageBackupAssets(private val store: MessageImageAssetStore) : BackupImageAssets {
    override fun read(owner: MessageImageOwner, reference: MessageImageReference): ByteArray =
        store.resolver.read(owner, reference)

    override fun restore(
        owner: MessageImageOwner,
        references: List<MessageImageReference>,
        contents: List<ByteArray>,
    ) = store.restore(owner, references, contents)
}
