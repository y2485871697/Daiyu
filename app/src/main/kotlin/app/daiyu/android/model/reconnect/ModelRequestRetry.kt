package app.daiyu.android.model.reconnect

import app.daiyu.android.domain.*

/** Retains one submitted boundary across retries; continuation is rebuilt, never array-appended. */
internal class ModelRequestRetry {
    private var restore: (() -> Unit)? = null
    private var outputs = emptyList<ToolOutput>()
    var continuation: RequestContinuation = RequestContinuation("")
        private set
    private var prepared: RequestContinuation? = null
    fun prepare(value: RequestContinuation) { prepared = value }
    var available = false
        private set
    fun capture(outputs: List<ToolOutput>, restore: () -> Unit) {
        if (!replaying) {
            this.outputs = outputs.toList()
            this.restore = restore
            continuation = RequestContinuation("")
        }
        available = false
    }
    fun failed(failure: Exception, authenticationRetry: Boolean = false) {
        available = restore != null && (authenticationRetry || failure is ModelDeliveryRejectedException ||
            failure is ModelRequestException && failure.failure in setOf(
            ModelFailure.NETWORK, ModelFailure.TIMEOUT, ModelFailure.SERVER, ModelFailure.RATE_LIMIT))
    }
    fun completed() { available = false; restore = null; outputs = emptyList() }
    var replaying = false
        private set
    fun replay(send: (List<ToolOutput>) -> Unit) {
        check(available) { "No retryable model request" }
        available = false
        val retained = outputs
        checkNotNull(restore).invoke()
        continuation = prepared ?: RequestContinuation("")
        prepared = null
        replaying = true
        try { send(retained) } finally { replaying = false }
    }
}
