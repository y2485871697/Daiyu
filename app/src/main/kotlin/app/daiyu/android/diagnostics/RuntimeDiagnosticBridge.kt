package app.daiyu.android.diagnostics

import app.daiyu.android.domain.runtime.RunDiagnosticSpan
import app.daiyu.android.domain.runtime.RunDiagnosticStage
import app.daiyu.android.domain.runtime.RunDiagnostics

internal object RuntimeDiagnosticBridge : RunDiagnostics {
    override fun begin(stage: RunDiagnosticStage): RunDiagnosticSpan {
        val span = RuntimeDiagnostics.begin(RuntimeDiagnostics.Stage.valueOf(stage.name))
        return RunDiagnosticSpan { span.end(it) }
    }
}
