package app.daiyu.android.domain.delegation

import app.daiyu.android.domain.ModelRequestException
import app.daiyu.android.domain.runtime.RunDiagnosticSpan
import app.daiyu.android.domain.runtime.RunDiagnosticStage
import app.daiyu.android.domain.runtime.RunDiagnostics

internal enum class ChildFailureStage { PREPARE, OPEN, RESPONSE, VALIDATE }

/** Only fixed stages and failure categories cross the persistent diagnostic boundary. */
internal class ChildFailureDiagnostics : RunDiagnostics {
    private var firstFailure: String? = null
    private var modelFailure: ModelRequestException? = null

    override fun begin(stage: RunDiagnosticStage) = RunDiagnosticSpan { failure ->
        if (failure != null) synchronized(this) {
            if (firstFailure == null) firstFailure = diagnostic(stage.name, failure)
            if (failure is ModelRequestException) modelFailure = failure
        }
    }

    @Synchronized fun detail(prefix: String, stage: ChildFailureStage, failure: Exception?): String {
        val code = firstFailure ?: diagnostic(stage.name, failure)
        val model = (failure as? ModelRequestException) ?: modelFailure
        val details = model?.detailedMessage
        return (prefix.take(199 - code.length) + ";" + code + if (details == null) "" else "\n\n" + details)
            .take(ChildTaskRecord.MAX_DETAIL_CHARS)
    }
}

private fun diagnostic(stage: String, failure: Throwable?): String {
    val type = (failure as? ModelRequestException)?.failure?.name ?: when (failure) {
        null -> "incomplete_result"
        is InterruptedException -> "interrupted"
        is SecurityException -> "security"
        is IllegalArgumentException -> "invalid_argument"
        is IllegalStateException -> "invalid_state"
        is java.io.IOException -> "io"
        else -> "exception"
    }
    return "phase=$stage;type=$type"
}
