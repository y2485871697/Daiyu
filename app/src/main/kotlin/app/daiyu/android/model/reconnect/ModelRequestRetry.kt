package app.daiyu.android.model.reconnect

import app.daiyu.android.domain.*

/** A frozen CURRENT model request, not a new model session or a local tool replay. */
internal class ModelRequestRetry {
    private var restore: (() -> Unit)? = null
    private var outputs = emptyList<ToolOutput>()
    var available = false
        private set
    fun capture(outputs: List<ToolOutput>, restore: () -> Unit) {
        this.outputs = outputs.toList()
        this.restore = restore
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
        replaying = true
        try { send(retained) } finally { replaying = false }
    }
}
