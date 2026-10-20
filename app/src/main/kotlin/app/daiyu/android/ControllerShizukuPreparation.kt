package app.daiyu.android

import app.daiyu.android.domain.Conversation
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.RunId
import app.daiyu.android.domain.tools.TaskAuthorization
import app.daiyu.android.integration.tools.CatalogShizukuBinding
import app.daiyu.android.integration.tools.RunCatalogCloseResult
import app.daiyu.android.shizuku.*
import java.util.UUID

/** An in-memory, single-use approval, not a saved preference or a bearer proof.
 * The controller owns this exact instance until acceptance or confirmed cleanup.
 * IDs identify the future durable messages; only approve() issues the exact WRITE grant.
 */
internal class ControllerShizukuPreparation internal constructor(
    val conversation: Conversation?,
    val text: String,
    val uid: Int,
    val command: String,
    val timeoutMillis: Long,
    val conversationId: ConversationId = conversation?.id ?: ConversationId(UUID.randomUUID().toString()),
    val runId: RunId = RunId(UUID.randomUUID().toString())
) {
    private val session = ShizukuRunSession(runId.value, runId.value)
    private var submitted = false
    private var cancelled = false
    private var opened = false
    init {
        require(text.isNotBlank())
        require(conversation == null || conversation.id == conversationId)
        // Use the production validator, preserving whitespace and every command byte.
        RunShizukuCommands.writeEffect(uid, command, timeoutMillis)
        session.edit(uid, command, timeoutMillis)
    }

    val messageText: String get() = "$text\n\n本次 Shizuku 仅批准以下精确命令（UID $uid，超时 ${timeoutMillis}ms）；不得修改或自动重试：\n$command"
    @Synchronized internal fun isSubmitted(): Boolean = submitted
    @Synchronized internal fun isCancelled(): Boolean = cancelled

    /** Only invoked by the controller's explicit confirmation entry point. No I/O here. */
    @Synchronized internal fun approve(): TaskAuthorization {
        check(!submitted && !cancelled) { "shizuku_approval_consumed" }
        session.approve(session.state)
        submitted = true
        return TaskAuthorization(runId.value, runId.value, session.allowedWrites)
    }

    internal fun open(access: ShizukuAuthorization): CatalogShizukuBinding {
        claimOpen()
        return CatalogShizukuBinding.open(session, access)
    }
    /** Production transport seam only; tests never connect to Android or execute a command. */
    internal fun open(connect: (Int, String) -> ShizukuBackend): CatalogShizukuBinding {
        claimOpen()
        return CatalogShizukuBinding.open(session, connect)
    }
    @Synchronized private fun claimOpen() {
        check(submitted && !cancelled && !opened) { "shizuku_approval_consumed" }
        opened = true
    }
    internal fun cancel() {
        synchronized(this) { cancelled = true }
        session.revoke()
    }
    internal fun closeWithResult(callback: (ShizukuCloseResult) -> Unit) = session.closeWithResult(callback)

    companion object {
        /** Never parse with a fallback to root or shell. Even malformed input remains unselected. */
        fun parseUid(value: String): Int? = when (value) { "0" -> 0; "2000" -> 2000; else -> null }
        fun parseTimeout(value: String): Long? = value.takeIf { it.matches(Regex("[0-9]{1,6}")) }
            ?.toLongOrNull()?.takeIf { it in 1..120_000 }
    }
}

/** UNKNOWN is terminal for this controller lifetime, not an acknowledgement of drain. */
internal class ControllerShizukuCleanup {
    var result: RunCatalogCloseResult? = null
        private set
    val drained: Boolean get() = result == RunCatalogCloseResult.Drained
    fun observe(value: RunCatalogCloseResult) {
        if (result == null) result = value
    }
}
