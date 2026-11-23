package app.daiyu.android.domain.delegation

import app.daiyu.android.domain.runtime.RequestCheckpoint

/** Request-time routing facts only. No endpoint, headers, credentials or model payload. */
data class ChildRequestModel(val providerId: String, val modelId: String) {
    init { require(providerId.isNotBlank() && providerId.length <= 200 && modelId.isNotBlank() && modelId.length <= 500) }
}
enum class ChildTaskDiagnosticPhase {
    ADMITTED, QUEUE_WAIT, QUEUE_READY, CONFIGURATION_PAUSE_REQUESTED, PAUSED, TAKEOVER,
    EXECUTION_SOFT_WARNING, REQUEST_OPEN, REQUEST_STARTED, FIRST_EVENT, REQUEST_COMPLETED, REQUEST_FAILED,
    TOOL_STARTED, TOOL_COMPLETED, COMPLETED, FAILED, CLEANUP_COMPLETED, CLEANUP_FAILED
}
data class ChildTaskDiagnosticEvent(val owner: ChildRunOwner, val profileId: String,
    val role: ChildModelRole?, val phase: ChildTaskDiagnosticPhase,
    val model: ChildRequestModel? = null, val request: RequestCheckpoint? = null,
    val requestSequence: Int? = null)
fun interface ChildTaskDiagnostics { fun record(event: ChildTaskDiagnosticEvent) }
