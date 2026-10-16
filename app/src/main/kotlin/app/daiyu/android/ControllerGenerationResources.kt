package app.daiyu.android

import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.integration.tools.GenerationDownloadAuthorization
import app.daiyu.android.integration.tools.RunGenerationTools
import app.daiyu.android.media.GenerationAssetFence
import app.daiyu.android.media.GenerationAssetOwner
import app.daiyu.android.media.MessageGenerationAssets
import app.daiyu.android.model.generation.GenerationAttempt
import app.daiyu.android.model.generation.GenerationKind
import app.daiyu.android.storage.MessageHistoryCodec
import app.daiyu.android.storage.SQLiteGenerationOutputStore
import app.daiyu.android.storage.SQLiteText
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream

/** The database provider must capture the account's actual application DB, NOT resolve whichever
 * account is active later. The existing schema has no per-message account column: a shared
 * multi-account DB is unsupported. No database is opened, migrated or closed by this helper.
 */
internal class ControllerGenerationResources(
    val accountScope: String,
    private val accountDatabase: () -> SQLiteDatabase,
    private val currentAccountScope: () -> String?,
    private val privateMediaRoot: File,
) {
    init { require(accountScope.isNotBlank() && accountScope.length <= 200) }
    private val retainedAssets = mutableMapOf<GenerationAssetFence, MessageGenerationAssets>()

    /** Worker-only. Use the exact same provider for the existing SQLite attempt/output stores. */
    fun database(): SQLiteDatabase {
        check(currentAccountScope() == accountScope) { "generation_account_changed" }
        val db = accountDatabase()
        check(currentAccountScope() == accountScope) { "generation_account_changed" }
        return db
    }

    /** Lazy factory used by token.openLeaf; strongly retained even after an ambiguous attempt. */
    @Synchronized fun assets(fence: GenerationAssetFence): MessageGenerationAssets {
        require(fence.owner.accountScope == accountScope) { "generation_account_mismatch" }
        return retainedAssets.getOrPut(fence) { MessageGenerationAssets(privateMediaRoot, fence) }
    }

    /** Checks the persisted assistant, conversation, run and EXACT original tool invocation.
     * History segments take precedence over stale legacy blocks. Existence alone is not proof.
     * Live execution/cancellation is additionally checked by the token, outside SQLite locks.
     */
    fun validateOwner(owner: GenerationAssetOwner): Boolean = runCatching {
        require(owner.accountScope == accountScope)
        val binding = owner.binding
        require(binding.toolName in setOf(RunGenerationTools.IMAGE_TOOL, RunGenerationTools.VIDEO_TOOL))
        val db = database()
        check(!db.inTransaction()) { "generation_outer_transaction" }
        db.beginTransaction()
        try {
            db.rawQuery("SELECT m.id FROM messages m JOIN conversations c ON c.id=m.conversation_id " +
                "WHERE m.id=? AND m.conversation_id=? AND m.run_id=? AND m.role='assistant'",
                arrayOf(binding.assistantMessageId, binding.conversationId, binding.runId)).use {
                check(it.moveToFirst())
            }
            val history = MessageHistoryCodec.decode(JSONArray(checkNotNull(SQLiteText.read(db,
                "messages", "history_segments", "id", binding.assistantMessageId))))
            val names = if (history.isNotEmpty()) {
                history.filter { it.role == "assistant" }.flatMap {
                    check(it.messageId.value == binding.assistantMessageId)
                    it.blocks.filterIsInstance<MessageBlock.Tool>()
                }.filter { it.invocation.id.value == binding.toolCallId }.map { it.invocation.name }
            } else {
                val blocks = JSONArray(checkNotNull(SQLiteText.read(db, "messages", "blocks", "id",
                    binding.assistantMessageId)))
                (0 until blocks.length()).map { blocks.getJSONObject(it) }
                    .filter { it.getString("type") == "tool" && it.getString("id") == binding.toolCallId }
                    .map { it.getString("name") }
            }
            check(names.size == 1 && names.single() == binding.toolName)
            check(currentAccountScope() == accountScope)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        check(currentAccountScope() == accountScope)
        true
    }.getOrDefault(false)

    /** Read back the real side table + original, never a guessed asset path or Tool.result URL.
     * The reader is app-private and must be closed on a worker (fence revocation may block).
     */
    fun openOutputs(owner: GenerationAssetOwner): ControllerGenerationOutputReader {
        check(validateOwner(owner)) { "generation_output_not_authorized" }
        val fence = GenerationAssetFence(owner, ::validateOwner)
        return ControllerGenerationOutputReader(::database, owner, assets(fence), fence, ::validateOwner)
    }

    companion object {
        /** No real connect-time DNS-pinning download adapter exists at this integration boundary.
         * Exact host allowlisting or InetAddress pre-resolution is NOT such an adapter. Thus URL
         * downloads, INCLUDING authenticated video content URLs, are unavailable by default.
         * Inline image bytes can still use the existing client. A future audited adapter must be
         * paired with a transport enforcing resolved-address pinning and no redirects/retries.
         */
        val DENY_DOWNLOADS = GenerationDownloadAuthorization { _, _ -> false }
        const val DOWNLOAD_STATUS = "URL 下载（含视频内容 URL）未启用：尚无经过审计的连接级安全适配器。"
    }
}

/** UI-safe projection: no provider URL, credential, local path, raw outcome or reusable receipt. */
internal data class ControllerGenerationOutputMetadata(
    val outputIndex: Int,
    val kind: GenerationKind,
    val mimeType: String,
    val bytes: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
)

internal class ControllerGenerationOutputReader internal constructor(
    database: () -> SQLiteDatabase,
    private val owner: GenerationAssetOwner,
    assets: MessageGenerationAssets,
    private val fence: GenerationAssetFence,
    private val validateOwner: (GenerationAssetOwner) -> Boolean,
) : AutoCloseable {
    private val store = SQLiteGenerationOutputStore(database, owner, assets)
    @Volatile private var closed = false

    /** Worker-only; null is an authorized attempt without a registered output at this index. */
    fun metadata(attempt: GenerationAttempt, outputIndex: Int): ControllerGenerationOutputMetadata? = guarded {
        store.find(attempt, outputIndex)?.let {
            ControllerGenerationOutputMetadata(it.outputIndex, it.kind, it.mimeType, it.bytes,
                it.width, it.height, it.durationMs)
        }
    }

    /** Worker-only. Caller owns/closes the read-only descriptor, including after reader.close.
     * Both persistent metadata authorization and full asset mapping/digest are verified again.
     * Never turn the descriptor into a file:// URI or expose it outside the app.
     */
    fun openOriginal(attempt: GenerationAttempt, outputIndex: Int): FileInputStream? = guarded {
        store.open(attempt, outputIndex)
    }

    private fun <T> guarded(action: () -> T): T = try {
        check(!closed && validateOwner(owner))
        action()
    } catch (_: Exception) {
        // Do not export DB/FS exception causes, paths, receipts or provider payloads.
        throw IllegalStateException("generation_output_unavailable")
    }

    override fun close() { closed = true; fence.revoke() }
}
