package app.daiyu.android

import android.database.sqlite.SQLiteDatabase
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.integration.tools.GenerationDownloadAuthorization
import app.daiyu.android.integration.tools.RunGenerationTools
import app.daiyu.android.media.GenerationAssetFence
import app.daiyu.android.media.GenerationAssetOwner
import app.daiyu.android.media.GenerationAssetPublicationGate
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
 * Retain ONE instance in the actual account/DB owner across sessions and history readers; creating
 * per-reader/per-session instances would split the owner publication barrier. Ordinary Stop must
 * retain it. Destructive owner changes require seal*PublicationAdmission -> worker revoke -> DB.
 */
internal class ControllerGenerationResources(
    val accountScope: String,
    private val accountDatabase: () -> SQLiteDatabase,
    private val currentAccountScope: () -> String?,
    private val privateMediaRoot: File,
    // Explicit account-owner opt-in. Never inject a boolean/host policy without its transport.
    private val downloadTransport: GenerationPublicNetworkHttp? = null,
) {
    init { require(accountScope.isNotBlank() && accountScope.length <= 200) }
    private val retainedAssets = mutableMapOf<GenerationAssetFence, MessageGenerationAssets>()
    // Only this short in-memory lock participates in seal. Never hold it during DB/FS/validators.
    private val ownerGateLock = Any()
    private data class RunKey(val conversationId: String, val runId: String)
    private val ownerGates = mutableMapOf<RunKey, GenerationAssetPublicationGate>()
    private val sealedRuns = mutableSetOf<RunKey>()
    private val sealedConversations = mutableSetOf<String>()
    private var accountSealed = false

    private fun ownerGate(owner: GenerationAssetOwner): GenerationAssetPublicationGate {
        require(owner.accountScope == accountScope) { "generation_account_mismatch" }
        val key = RunKey(owner.binding.conversationId, owner.binding.runId)
        return synchronized(ownerGateLock) {
            ownerGates.getOrPut(key) {
                GenerationAssetPublicationGate().also {
                    if (accountSealed || key in sealedRuns || key.conversationId in sealedConversations)
                        it.sealAdmission()
                }
            }
        }
    }

    /** Pure construction. Each caller owns a LOCAL fence; stable account/conversation/run scope
     * supplies the shared gate. Do not replace this Resources object for each reader or session.
     * The validator supplied by the token adds execution cancellation only for that writer.
     */
    fun createFence(owner: GenerationAssetOwner,
        validator: (GenerationAssetOwner) -> Boolean = ::validateOwner): GenerationAssetFence =
        GenerationAssetFence(owner, ownerGate(owner), validator)

    /** Pure in-memory seal, safe under a short parent control lock. Permanently covers both
     * existing and FUTURE fences in this scope. This is NOT yet permission to mutate/delete DB.
     */
    fun sealRunPublicationAdmission(conversationId: String, runId: String): ControllerGenerationPublicationRevocation =
        sealPublication(conversationId, runId)

    fun sealConversationPublicationAdmission(conversationId: String): ControllerGenerationPublicationRevocation =
        sealPublication(conversationId, null)

    fun sealAccountPublicationAdmission(): ControllerGenerationPublicationRevocation = sealPublication(null, null)

    private fun sealPublication(conversationId: String?, runId: String?): ControllerGenerationPublicationRevocation {
        require(conversationId == null || conversationId.isNotBlank())
        require(runId == null || runId.isNotBlank())
        return synchronized(ownerGateLock) {
            when {
                conversationId == null -> accountSealed = true
                runId == null -> sealedConversations.add(conversationId)
                else -> sealedRuns.add(RunKey(conversationId, runId))
            }
            val gates = ownerGates.filterKeys {
                (conversationId == null || it.conversationId == conversationId) &&
                    (runId == null || it.runId == runId)
            }.values.toList()
            gates.forEach { it.sealAdmission() }
            ControllerGenerationPublicationRevocation(gates)
        }
    }

    /** Worker-only. Use the exact same provider for the existing SQLite attempt/output stores. */
    fun database(): SQLiteDatabase {
        check(currentAccountScope() == accountScope) { "generation_account_changed" }
        val db = accountDatabase()
        check(currentAccountScope() == accountScope) { "generation_account_changed" }
        return db
    }

    /** Lazy factory used by token.openLeaf; strongly retained even after an ambiguous attempt. */
    @Synchronized fun assets(fence: GenerationAssetFence): MessageGenerationAssets {
        require(fence.publicationGate === ownerGate(fence.owner)) { "generation_unshared_publication_gate" }
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

    /** Worker-only readback; never a guessed path or Tool.result URL. Reader close only seals
     * its own fence, not this owner's gate and not any writer/other reader.
     */
    fun openOutputs(owner: GenerationAssetOwner): ControllerGenerationOutputReader {
        val fence = createFence(owner)
        fence.admit { Unit }
        return ControllerGenerationOutputReader(::database, owner, assets(fence), fence)
    }

    /** The leaf always receives a matched transport/policy pair. An extra parent policy may only
     * narrow it; it cannot enable URL downloads when no secure transport was explicitly supplied.
     * Owner, one-shot approval and reviewed exact hosts remain enforced by the original token.
     */
    fun generationNetwork(additional: GenerationDownloadAuthorization? = null): GenerationDownloadNetworkBinding {
        val transport = downloadTransport ?: return GenerationDownloadNetworkBinding.denied()
        return GenerationDownloadNetworkBinding.publicNetwork(transport, additional)
    }

    companion object {
        /** Default remains fail-closed until the account owner injects the dedicated transport. */
        val DENY_DOWNLOADS = GenerationDownloadAuthorization { _, _ -> false }
        const val DOWNLOAD_STATUS = "URL 下载须显式接入专用公网连接适配器，并保留本次生成的 owner 与下载授权。"
    }
}

/** A captured, already-sealed owner scope. Await on a worker BEFORE the destructive DB
 * transaction/account change, never inside a DB transaction or under UI/controller locks.
 * Return is a barrier for all pre-seal validators/publications, not a transport drain.
 */
internal class ControllerGenerationPublicationRevocation internal constructor(
    private val gates: List<GenerationAssetPublicationGate>,
) {
    fun revokeOnWorker() { gates.forEach { it.revokeOnWorker() } }
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
    owner: GenerationAssetOwner,
    private val assets: MessageGenerationAssets,
    private val fence: GenerationAssetFence,
) : AutoCloseable {
    private val store = SQLiteGenerationOutputStore(database, owner, assets)
    @Volatile private var closed = false

    /** Worker-only; null is an authorized attempt without a registered output at this index. */
    fun metadata(attempt: GenerationAttempt, outputIndex: Int): ControllerGenerationOutputMetadata? = guarded {
        fence.admit { store.find(attempt, outputIndex) }?.let {
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

    /** Backup's selected scalar row is not authority by itself. Recheck the persisted attempt
     * and exact registered slot, then verify every pending mapping field, bounded length, EOF
     * and hash through this SAME locally retained reader's owner fence. No SQL spans asset I/O.
     */
    fun readBackup(reference: app.daiyu.android.storage.GenerationOutputReference): ByteArray = guarded {
        val attempt = GenerationAttempt(reference.identity, reference.fingerprint, reference.protocol)
        check(fence.admit { store.find(attempt, reference.outputIndex) } == reference)
        app.daiyu.android.backup.MessageGenerationBackupAssets { assets }.read(reference)
    }

    private fun <T> guarded(action: () -> T): T = try {
        check(!closed)
        fence.admit { Unit }
        // Do not hold the gate across open(): assets take file-lock -> gate. SQL metadata-only
        // reads above can use gate -> DB; acquiring file locks under the gate would invert it.
        action()
    } catch (_: Exception) {
        // Do not export DB/FS exception causes, paths, receipts or provider payloads.
        throw IllegalStateException("generation_output_unavailable")
    }

    /** Nonblocking reader-local seal; already-returned descriptors remain caller-owned. */
    override fun close() { closed = true; fence.revokeAdmission() }
}
