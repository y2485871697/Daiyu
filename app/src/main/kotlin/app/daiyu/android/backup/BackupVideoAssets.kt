package app.daiyu.android.backup

import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.media.video.MessageVideoOwner
import app.daiyu.android.media.video.MessageVideoReference
import app.daiyu.android.media.video.VideoAssetStore

/** Backup capability only, not model-upload authorization. Host holds its existing acceptance lock. */
interface BackupVideoAssets {
    fun read(owner: MessageVideoOwner, reference: MessageVideoReference): ByteArray
    fun restore(owner: MessageVideoOwner, references: List<MessageVideoReference>, contents: List<ByteArray>)
}

/** Uses the host's existing app-scoped store; no second root, draft access, or independent lock. */
class MessageVideoBackupAssets(private val store: VideoAssetStore) : BackupVideoAssets {
    override fun read(owner: MessageVideoOwner, reference: MessageVideoReference): ByteArray {
        require(reference.owner == owner && reference.bytes in 1..NeutralBackupService.MAX_BYTES)
        return store.openOriginal(owner, reference).use { source ->
            val bytes = ByteArray(reference.bytes.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = source.read(bytes, offset, minOf(16 * 1024, bytes.size - offset))
                require(count > 0) { "备份原视频缺失" }
                offset += count
            }
            require(source.read() == -1)
            BackupVideoManifest.verify(reference, bytes)
            bytes
        }
    }

    override fun restore(owner: MessageVideoOwner, references: List<MessageVideoReference>, contents: List<ByteArray>) =
        store.restore(owner, references, contents)
}

/** One physical message's complete ordered sidecar; payload is the existing v1 codec, not a path. */
data class BackupVideoSidecar(val messageId: String, val payload: String)

/**
 * Inject ONLY at an asset-capable complete-backup entry. exportText must use the existing text
 * exporter with its video-only guard bypassed, not change the ordinary text export contract.
 * Both callbacks receive the SAME active SQLite transaction as the complete backup operation.
 * restoreSidecars must validate against the persisted physical message (including history), use
 * SQLiteMessageVideoStore.insert, and never call send/prepare or mint replacement asset IDs.
 */
class BackupVideoIntegration(
    val assets: BackupVideoAssets,
    val exportText: (SQLiteDatabase, ConversationId?) -> String,
    val restoreSidecars: (SQLiteDatabase, List<BackupVideoSidecar>) -> Unit,
)
