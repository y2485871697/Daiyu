package app.daiyu.android.voice.control

import android.os.Looper
import app.daiyu.android.ChatController
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.RunStatus
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/** Only exact assistant Text blocks; no protocol/reasoning/tools/history-segment/user text. */
internal fun universalAssistantBody(owner: VoiceAssistantOwner, message: Message,
    questionWaiting: Boolean): VoiceAssistantBody? {
    if (message.role != Message.ASSISTANT_ROLE || message.id.value != owner.messageId ||
        message.runId.value != owner.runId || message.conversationId.value != owner.conversationId) return null
    val status = when {
        questionWaiting -> VoiceBodyStatus.QUESTION_WAITING
        message.status == RunStatus.RUNNING -> VoiceBodyStatus.STREAMING
        message.status == RunStatus.COMPLETED -> VoiceBodyStatus.COMPLETE
        message.status == RunStatus.FAILED -> VoiceBodyStatus.FAILED
        else -> VoiceBodyStatus.CANCELLED
    }
    return VoiceAssistantBody(owner, message.blocks.filterIsInstance<MessageBlock.Text>().joinToString("") { it.text }, status)
}

/**
 * Optional ready-to-mount parent observer. Subscribes before replay; terminal persisted body is
 * read off main even after controller.active disappears. A sequence fence rejects stale DB reads.
 * questionWaiting must inspect the exact owner's question projection, never infer from RUNNING.
 * Main may instead inject its own observer with the same subscribe-then-replay contract.
 */
internal class AndroidUniversalOwnerObserver(
    private val controller: ChatController,
    private val questionWaiting: (Message) -> Boolean,
) {
    fun observe(owner: VoiceAssistantOwner, body: (VoiceAssistantBody) -> Unit): VoiceControlCancellation {
        check(Looper.myLooper() == Looper.getMainLooper())
        val closed = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var serial = 0L // Listener, replay and delivery are all on main.
        var read: Job? = null
        fun matching(value: Message?) = value?.takeIf {
            it.role == Message.ASSISTANT_ROLE && it.id.value == owner.messageId &&
                it.runId.value == owner.runId && it.conversationId.value == owner.conversationId
        }
        fun publish(message: Message) {
            if (closed.get()) return
            val snapshot = try { universalAssistantBody(owner, message, questionWaiting(message)) }
            catch (_: Exception) { VoiceAssistantBody(owner, "", VoiceBodyStatus.UNKNOWN) }
            snapshot?.let(body)
        }
        val listener: () -> Unit = listener@{
            if (closed.get()) return@listener
            val token = ++serial
            read?.cancel()
            val active = matching(controller.active)
            if (active != null) { publish(active); return@listener }
            read = scope.launch {
                val stored = try {
                    withContext(Dispatchers.IO) {
                        val conversation = ConversationId(owner.conversationId)
                        var cursor = Long.MAX_VALUE
                        var found: Message? = null
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val rows = controller.database.messages(conversation, cursor, 200)
                            if (rows.isEmpty()) break
                            found = rows.asSequence().map { it.second }.firstOrNull { matching(it) != null }
                            if (found != null) break
                            val next = rows.first().first // Database pages are oldest-to-newest.
                            check(next < cursor) { "universal_owner_page_invalid" }
                            cursor = next
                        }
                        found
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (!closed.get() && token == serial) body(VoiceAssistantBody(owner, "", VoiceBodyStatus.UNKNOWN))
                    return@launch
                }
                if (!closed.get() && token == serial) {
                    val latest = matching(controller.active) ?: stored
                    if (latest != null) publish(latest)
                    // Missing original owner is not permission to bind a newer assistant.
                }
            }
        }
        controller.observe(listener)
        try {
            listener() // Replay AFTER registration, including a response completed before subscribe.
        } catch (failure: Throwable) {
            closed.set(true); controller.unobserve(listener); scope.cancel()
            throw failure
        }
        return VoiceControlCancellation {
            closed.set(true)
            controller.unobserve(listener)
            scope.cancel() // No waits for main callbacks or DB I/O under controller/audio locks.
        }
    }
}
