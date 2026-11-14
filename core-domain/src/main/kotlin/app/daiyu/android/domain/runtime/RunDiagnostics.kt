package app.daiyu.android.domain.runtime

/** 观察运行诊断元数据，运行状态由执行器管理，接口排除模型和工具内容。 */
enum class RunDiagnosticStage { MODEL_OPEN, MODEL_REQUEST, MODEL_EVENT, MODEL_ACK, TOOL_EXECUTION }
fun interface RunDiagnosticSpan { fun end(failure: Throwable?) }
fun interface RunDiagnostics { fun begin(stage: RunDiagnosticStage): RunDiagnosticSpan }
