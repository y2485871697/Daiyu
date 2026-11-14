package app.daiyu.android.backup

import app.daiyu.android.media.MessageGenerationAssets
import app.daiyu.android.storage.GenerationOutputReference

/** 通过宿主按账号和所有者校验的读取器，为备份读取并核对生成原件。
 * 调用方先在备份接收锁内选定持久化记录；read 内只复用读取器，不访问数据库。 */
class MessageGenerationBackupAssets(
    private val resolve: (GenerationOutputReference) -> MessageGenerationAssets,
) : BackupGenerationAssets {
    override fun read(reference: GenerationOutputReference): ByteArray {
        require(reference.bytes in 1..NeutralBackupService.MAX_BYTES)
        val assets = resolve(reference)
        val pending = assets.pending(reference.receipt)
        val owner = pending.owner
        val binding = owner.binding
        check(owner.accountScope == reference.accountScope && binding.conversationId == reference.conversationId &&
            binding.assistantMessageId == reference.assistantMessageId && binding.runId == reference.runId &&
            binding.toolCallId == reference.toolCallId && binding.toolName == reference.toolName &&
            pending.receipt == reference.receipt && pending.identity == reference.identity && pending.outputIndex == reference.outputIndex &&
            pending.kind == reference.kind && pending.mimeType == reference.mimeType && pending.bytes == reference.bytes &&
            pending.sha256 == reference.sha256 && pending.width == reference.width && pending.height == reference.height &&
            pending.durationMs == reference.durationMs) { "生成原资产映射与真实所有者不一致" }
        return assets.openOriginal(reference.receipt).use { source ->
            val bytes = ByteArray(reference.bytes.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val n = source.read(bytes, offset, minOf(16 * 1024, bytes.size - offset))
                require(n > 0); offset += n
            }
            require(source.read() == -1)
            BackupGenerationCodec.verify(reference, bytes)
            bytes
        }
    }
}
