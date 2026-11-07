package app.daiyu.android.domain.runtime

/** Non-authoritative metadata observer; no model or tool data crosses this interface. */
enum class RunDiagnosticStage { MODEL_OPEN, MODEL_REQUEST, MODEL_EVENT, MODEL_ACK, TOOL_EXECUTION }
fun interface RunDiagnosticSpan { fun end(failure: Throwable?) }
fun interface RunDiagnostics { fun begin(stage: RunDiagnosticStage): RunDiagnosticSpan }
