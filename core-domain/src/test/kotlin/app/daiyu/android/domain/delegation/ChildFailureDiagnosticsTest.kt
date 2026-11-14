package app.daiyu.android.domain.delegation

import app.daiyu.android.domain.runtime.RunDiagnosticStage
import org.junit.Assert.*
import org.junit.Test

class ChildFailureDiagnosticsTest {
    private class PrivateCredentialFailure : RuntimeException("token=SECRET request body")

    @Test fun diagnosticsAreBoundedAndNeverExposeMessagesOrPrivateClassNames() {
        val diagnostics = ChildFailureDiagnostics()
        val value = diagnostics.detail("child_result_unconfirmed", ChildFailureStage.RESPONSE,
            PrivateCredentialFailure())
        assertEquals("child_result_unconfirmed;phase=RESPONSE;type=exception", value)
        assertFalse(value.contains("SECRET")); assertFalse(value.contains("PrivateCredentialFailure"))
        assertEquals(200, diagnostics.detail("x".repeat(200), ChildFailureStage.VALIDATE, null).length)
    }

    @Test fun runnerFirstFailureSurvivesOuterValidationWithoutKeepingThrowableContents() {
        val diagnostics = ChildFailureDiagnostics()
        diagnostics.begin(RunDiagnosticStage.MODEL_REQUEST).end(java.io.IOException("https://PRIVATE token=SECRET"))
        diagnostics.begin(RunDiagnosticStage.MODEL_ACK).end(IllegalStateException("PRIVATE_ACK"))
        assertEquals("child_result_unconfirmed;phase=MODEL_REQUEST;type=io",
            diagnostics.detail("child_result_unconfirmed", ChildFailureStage.VALIDATE, IllegalStateException("PRIVATE")))
    }
}
