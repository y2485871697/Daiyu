package app.daiyu.android.backup

import app.daiyu.android.media.MessageImageAssetStore
import app.daiyu.android.media.MessageImageOwner
import app.daiyu.android.media.MessageImageReference

/** 将消息图片存储的读取和恢复接口接入备份，复用其私有目录和清理机制，不访问草稿。
 * 宿主须使用消息存储的共同锁，将备份、导入与消息接收、删除及相关数据库操作串行化。
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
