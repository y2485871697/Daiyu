package app.daiyu.android.model.reconnect

import app.daiyu.android.domain.*

/** Preserve exact retained transport capability through media/runtime guards. This never opens
 * a model and never bypasses a POST approval. Checks run at admission AND event delivery. */
fun guardModelSession(session: ModelSession, checkRuntime: () -> Unit): ModelSession {
    if (session is CheckpointedResumableModelSession) return object : CheckpointedResumableModelSession, RequestRetryModelSession {
        override val retriesCurrentRequest: Boolean get() = (session as? RequestRetryModelSession)?.retriesCurrentRequest == true
        override val canResume: Boolean get() = session.canResume
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
            checkRuntime(); session.next(outputs) { checkRuntime(); sink(it) }
        }
        override fun resume(sink: (ModelEvent) -> Unit) {
            checkRuntime(); session.resume { checkRuntime(); sink(it) }
        }
        override fun nextAcknowledged(outputs: List<ToolOutput>, localSink: (ModelEvent) -> Unit,
            sink: (ModelEventBatch) -> ModelDeliveryAck) {
            checkRuntime(); session.nextAcknowledged(outputs, { checkRuntime(); localSink(it) }) { checkRuntime(); sink(it) }
        }
        override fun nextAcknowledged(outputs: List<ToolOutput>, sink: (ModelEventBatch) -> ModelDeliveryAck) {
            checkRuntime(); session.nextAcknowledged(outputs) { checkRuntime(); sink(it) }
        }
        override fun resumeAcknowledged(sink: (ModelEventBatch) -> ModelDeliveryAck) {
            checkRuntime(); session.resumeAcknowledged { checkRuntime(); sink(it) }
        }
        override fun disconnectTransport() = session.disconnectTransport()
        override fun cancel() = session.cancel()
    }
    if (session is RequestRetryModelSession) return object : RequestRetryModelSession {
        override val canResume: Boolean get() = session.canResume
        override val retriesCurrentRequest: Boolean get() = session.retriesCurrentRequest
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
            checkRuntime(); session.next(outputs) { checkRuntime(); sink(it) }
        }
        override fun resume(sink: (ModelEvent) -> Unit) {
            checkRuntime(); session.resume { checkRuntime(); sink(it) }
        }
        override fun disconnectTransport() = session.disconnectTransport()
        override fun cancel() = session.cancel()
    }
    if (session is ResumableModelSession) return object : ResumableModelSession {
        override val canResume: Boolean get() = session.canResume
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
            checkRuntime(); session.next(outputs) { checkRuntime(); sink(it) }
        }
        override fun resume(sink: (ModelEvent) -> Unit) {
            checkRuntime(); session.resume { checkRuntime(); sink(it) }
        }
        override fun cancel() = session.cancel()
    }
    return object : ModelSession {
        override fun next(outputs: List<ToolOutput>, sink: (ModelEvent) -> Unit) {
            checkRuntime(); session.next(outputs) { checkRuntime(); sink(it) }
        }
        override fun cancel() = session.cancel()
    }
}
