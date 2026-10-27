package app.daiyu.android.model.usage

import app.daiyu.android.domain.*
import app.daiyu.android.storage.UsageStatisticsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal object UsageAccounting {
    data class Failure(val sequence: Long, val exceptionType: String)
    private val sequence = AtomicLong()
    private val mutableFailures = MutableStateFlow<Failure?>(null)
    val failures = mutableFailures.asStateFlow()
    fun report(failure: Exception) {
        mutableFailures.value = Failure(sequence.incrementAndGet(), failure.javaClass.simpleName)
        android.util.Log.e("UsageAccounting", "用量保存失败：${failure.javaClass.simpleName}")
    }
}

/** One identity per paid next(), retained across transport-only resume. The wrapper preserves
 * optional transport interfaces; billing failures never replace a successful model response. */
internal class UsageRecordingModel(
    private val delegate: ModelPort,
    private val record: (String, String?, Long, UsageStatisticsStore.Fields) -> Unit,
    private val conversationId: String? = null,
    private val onFailure: (Exception) -> Unit = UsageAccounting::report,
    private val inferConversation: Boolean = true,
    private val clock: () -> Long = System::currentTimeMillis,
    private val requestId: () -> String = { UUID.randomUUID().toString() },
) : ModelPort {
    override fun open(history: List<Message>) = wrap(delegate.open(history), history)
    override fun open(history: List<Message>, tools: Set<ReadOnlyTool>) = wrap(delegate.open(history, tools), history)
    override fun open(history: List<Message>, definitions: List<ToolDefinition>) = wrap(delegate.open(history, definitions), history)
    override fun open(history: List<Message>, definitions: List<ToolDefinition>, images: ModelImagePlan) =
        wrap(delegate.open(history, definitions, images), history)

    private fun wrap(session: ModelSession, history: List<Message>): ModelSession {
        val owner = conversationId ?: if (inferConversation) history.lastOrNull()?.conversationId?.value else null
        return when (session) {
            is CheckpointedResumableModelSession -> Checkpointed(session, owner)
            is RequestRetryModelSession -> Retrying(session, owner)
            is ResumableModelSession -> Resumable(session, owner)
            else -> Recording(session, owner)
        }
    }
    private inner class Request(private val owner: String?) {
        private val id = requestId()
        private val at = clock()
        private var latest = UsageStatisticsStore.Fields(null, null, null, null)
        private var saved = latest
        fun observe(event: ModelEvent) {
            if (event !is ModelEvent.Usage) return
            latest = latest.merge(UsageStatisticsStore.Fields(event.inputTokens, event.outputTokens, event.cachedTokens, event.cacheCreationTokens))
        }
        fun persist() {
            if (latest.empty || latest == saved) return
            val interrupted = Thread.interrupted()
            try {
                record(id, owner, at, latest)
                saved = latest
            } catch (failure: Exception) { try { onFailure(failure) } catch (_: Exception) { } }
            finally { if (interrupted) Thread.currentThread().interrupt() }
        }
        fun deliver(event: ModelEvent, sink: (ModelEvent) -> Unit) {
            observe(event)
            persist()
            try { sink(event) } finally { persist() }
        }
        fun deliver(batch: ModelEventBatch, sink: (ModelEventBatch) -> ModelDeliveryAck): ModelDeliveryAck {
            batch.events.forEach(::observe)
            persist()
            return try { sink(batch) } finally { persist() }
        }
    }
    private open inner class Recording(protected val session: ModelSession, private val owner: String?) : ModelSession {
        protected var request: Request? = null
        protected fun start(): Request = Request(owner).also { request = it }
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
            val current = start()
            try { session.next(outputs) { current.deliver(it, sink) } } finally { current.persist() }
        }
        override fun cancel() = session.cancel()
    }
    private open inner class Resumable(private val transport: ResumableModelSession, owner: String?) :
        Recording(transport, owner), ResumableModelSession {
        override val canResume get() = transport.canResume
        override fun resume(sink: (ModelEvent) -> Unit) {
            val current = if ((transport as? RequestRetryModelSession)?.retriesCurrentRequest == true) start()
                else checkNotNull(request) { "尚无可恢复的用量请求" }
            try { transport.resume { current.deliver(it, sink) } } finally { current.persist() }
        }
    }
    private inner class Retrying(private val transport: RequestRetryModelSession, owner: String?) :
        Resumable(transport, owner), RequestRetryModelSession {
        override val retriesCurrentRequest: Boolean get() = transport.retriesCurrentRequest
        override fun disconnectTransport() = transport.disconnectTransport()
    }
    private inner class Checkpointed(private val transport: CheckpointedResumableModelSession, owner: String?) :
        Resumable(transport, owner), CheckpointedResumableModelSession, RequestRetryModelSession {
        override val retriesCurrentRequest: Boolean get() = (transport as? RequestRetryModelSession)?.retriesCurrentRequest == true
        override fun nextAcknowledged(outputs: List<ToolOutput>, sink: (ModelEventBatch) -> ModelDeliveryAck) {
            val current = start()
            try { transport.nextAcknowledged(outputs) { current.deliver(it, sink) } } finally { current.persist() }
        }
        override fun nextAcknowledged(outputs: List<ToolOutput>, localSink: (ModelEvent) -> Unit,
            sink: (ModelEventBatch) -> ModelDeliveryAck) {
            val current = start()
            try { transport.nextAcknowledged(outputs, { current.deliver(it, localSink) }, { current.deliver(it, sink) }) }
            finally { current.persist() }
        }
        override fun resumeAcknowledged(sink: (ModelEventBatch) -> ModelDeliveryAck) {
            val current = if ((transport as? RequestRetryModelSession)?.retriesCurrentRequest == true) start()
                else checkNotNull(request) { "尚无可恢复的用量请求" }
            try { transport.resumeAcknowledged { current.deliver(it, sink) } } finally { current.persist() }
        }
        override fun disconnectTransport() = transport.disconnectTransport()
    }
}
