package app.daiyu.android.voice.control

import android.os.Looper
import app.daiyu.android.ChatController
import app.daiyu.android.domain.ModelFailure
import app.daiyu.android.domain.ModelHistory
import app.daiyu.android.domain.RequestField
import app.daiyu.android.domain.RequestRejection
import app.daiyu.android.domain.ConversationId
import app.daiyu.android.domain.Message
import app.daiyu.android.domain.MessageBlock
import app.daiyu.android.domain.RunStatus
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
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
    val unsupportedReasoning = if (status == VoiceBodyStatus.FAILED)
        app.daiyu.android.domain.RequestReasoningEffort.entries.firstOrNull { it.unsupportedMessage == message.error }
        else null
    return VoiceAssistantBody(owner, message.blocks.filterIsInstance<MessageBlock.Text>().joinToString("") { it.text },
        status, searchCompletion = browserSearchCompletion(message, status),
        unsupportedReasoning = unsupportedReasoning, responseFailure = voiceResponseFailure(message, status),
        browsing = status == VoiceBodyStatus.STREAMING && message.blocks.filterIsInstance<MessageBlock.Tool>().any {
            it.invocation.name in setOf("browser_read", "browser_write") && it.result == null
        })
}

/** The browser adapter emits this envelope only after its result and persistence are confirmed. */
private fun browserSearchCompletion(message: Message, status: VoiceBodyStatus): String? {
    if (status !in setOf(VoiceBodyStatus.STREAMING, VoiceBodyStatus.COMPLETE)) return null
    if (message.blocks.filterIsInstance<MessageBlock.Tool>().any {
        it.invocation.name in setOf("browser_read", "browser_write") && it.result == null
    }) return null
    val tool = message.blocks.filterIsInstance<MessageBlock.Tool>().lastOrNull {
        it.invocation.name in setOf("browser_read", "browser_write")
    } ?: return null
    val payload = tool.result?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
    if (payload.opt("text") !is String || payload.optJSONObject("browser_control") == null ||
        !payload.optBoolean("ok", true) || payload.has("error")) return null
    return "browser:${message.runId.value}:${tool.invocation.id.value}"
}

/** Retain the last new receipt while either source is busy; old source snapshots cannot replay it. */
internal class VoiceCallSearchActivity {
    private var browser: String? = null
    private var native: String? = null
    private var completion: String? = null
    fun project(body: VoiceAssistantBody, search: VoiceNativeSearchState): VoiceAssistantBody {
        body.searchCompletion?.takeIf { it != browser }?.let { browser = it; completion = it }
        search.completion?.takeIf { it != native }?.let { native = it; completion = "native:$it" }
        return body.withNativeSearch(search).copy(searchCompletion = completion.takeIf {
            body.status in setOf(VoiceBodyStatus.STREAMING, VoiceBodyStatus.COMPLETE)
        })
    }
}

private val knownResponseFailures = ModelFailure.entries.map { it.userMessage } +
    RequestRejection.entries.map { it.userMessage }.filter { it.isNotEmpty() }
private val responseFailureSuffix = Regex("^(?:（HTTP ([3-5][0-9]{2})）)?(?:，涉及字段：([a-z_]+))?$")

/** Reconstruct only closed diagnostics; never copy an arbitrary provider or tool error. */
internal fun voiceResponseFailure(message: Message, status: VoiceBodyStatus): String? {
    if (status == VoiceBodyStatus.CANCELLED) return "回答已中止，可以继续说"
    if (status != VoiceBodyStatus.FAILED) return null
    val unknownTool = message.blocks.filterIsInstance<MessageBlock.Tool>().firstOrNull {
        it.result == ModelHistory.UNKNOWN_TOOL_RESULT
    }
    if (unknownTool != null) return if (unknownTool.invocation.name in setOf("browser_read", "browser_write"))
        "浏览器执行结果未确认；本轮已停止，未自动重试" else "工具执行结果未确认；本轮已停止，未自动重试"
    val error = message.error ?: return null
    app.daiyu.android.domain.RequestReasoningEffort.entries.firstOrNull { it.unsupportedMessage == error }
        ?.let { return it.unsupportedMessage }
    for (base in knownResponseFailures) {
        if (!error.startsWith(base)) continue
        val suffix = responseFailureSuffix.matchEntire(error.removePrefix(base)) ?: continue
        val statusCode = suffix.groupValues[1].takeIf { it.isNotEmpty() }
        val field = suffix.groupValues[2].takeIf { it.isNotEmpty() }
        if (field != null && RequestField.entries.none { it.wireName == field }) continue
        return base + (statusCode?.let { "（HTTP $it）" } ?: "") +
            (field?.let { "，涉及字段：$it" } ?: "")
    }
    return null
}

internal fun VoiceAssistantBody.withNativeSearch(search: VoiceNativeSearchState): VoiceAssistantBody = copy(
    searching = status == VoiceBodyStatus.STREAMING && search.searching,
    searchCompletion = (search.completion ?: searchCompletion).takeIf { status in setOf(VoiceBodyStatus.STREAMING, VoiceBodyStatus.COMPLETE) })

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
        var currentBody: VoiceAssistantBody? = null
        var searchState = controller.nativeSearchState(owner)
        val activity = VoiceCallSearchActivity()
        fun matching(value: Message?) = value?.takeIf {
            it.role == Message.ASSISTANT_ROLE && it.id.value == owner.messageId &&
                it.runId.value == owner.runId && it.conversationId.value == owner.conversationId
        }
        fun publish(message: Message) {
            if (closed.get()) return
            val snapshot = try { universalAssistantBody(owner, message, questionWaiting(message)) }
            catch (_: Exception) { VoiceAssistantBody(owner, "", VoiceBodyStatus.UNKNOWN) }
            snapshot?.let { value ->
                currentBody = value
                body(activity.project(value, searchState))
            }
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
        val searchObservation = controller.observeNativeSearch(owner) { search ->
            if (!closed.get()) {
                searchState = search
                currentBody?.takeIf { it.status == VoiceBodyStatus.STREAMING }?.let {
                    body(activity.project(it, search))
                }
            }
        }
        controller.observe(listener)
        try {
            listener() // Replay AFTER registration, including a response completed before subscribe.
        } catch (failure: Throwable) {
            closed.set(true); searchObservation.close(); controller.unobserve(listener); scope.cancel()
            throw failure
        }
        return VoiceControlCancellation {
            closed.set(true)
            searchObservation.close()
            controller.unobserve(listener)
            scope.cancel() // No waits for main callbacks or DB I/O under controller/audio locks.
        }
    }
}
