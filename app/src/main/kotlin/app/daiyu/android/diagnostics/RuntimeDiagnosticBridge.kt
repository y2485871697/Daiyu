package app.daiyu.android.diagnostics

import app.daiyu.android.domain.runtime.RunDiagnosticSpan
import app.daiyu.android.domain.runtime.RunDiagnosticStage
import app.daiyu.android.domain.runtime.RunDiagnostics

internal object RuntimeDiagnosticBridge : RunDiagnostics {
    override fun input(kind: app.daiyu.android.domain.runtime.InputKind, length: Int) {
        HapticTrace.mark(when (kind) {
            app.daiyu.android.domain.runtime.InputKind.TEXT_DELTA -> RuntimeDiagnostics.Code.INPUT_TEXT_DELTA
            app.daiyu.android.domain.runtime.InputKind.TEXT_FINAL -> RuntimeDiagnostics.Code.INPUT_TEXT_FINAL
            app.daiyu.android.domain.runtime.InputKind.REASON_DELTA -> RuntimeDiagnostics.Code.INPUT_REASON_DELTA
            app.daiyu.android.domain.runtime.InputKind.REASON_FINAL -> RuntimeDiagnostics.Code.INPUT_REASON_FINAL
            app.daiyu.android.domain.runtime.InputKind.OTHER -> RuntimeDiagnostics.Code.INPUT_OTHER
        }, length)
    }
    override fun begin(stage: RunDiagnosticStage): RunDiagnosticSpan {
        val span = RuntimeDiagnostics.begin(RuntimeDiagnostics.Stage.valueOf(stage.name))
        return RunDiagnosticSpan { span.end(it) }
    }
}
