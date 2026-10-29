package app.daiyu.android.domain.tools

import org.junit.Assert.*
import org.junit.Test

class ToolFailureCodeTest {
    @Test fun categoriesAreBoundedAndNeverExposeMessagesOrPrivateClassNames() {
        val failures = listOf(InterruptedException("secret"), SecurityException("secret"),
            IllegalArgumentException("secret"), IllegalStateException("secret"), java.io.IOException("secret"), Exception("secret"))
        assertEquals(listOf("interrupted", "security", "invalid_argument", "invalid_state", "io", "exception"),
            failures.map { toolFailureCode(ToolFailureStage.ADAPTER, it).removePrefix("adapter_result_unconfirmed_") })
        ToolFailureStage.entries.forEach { stage -> failures.forEach { failure ->
            val code = toolFailureCode(stage, failure)
            assertEquals(code, toolFailureDiagnostic(code)); assertFalse(code.contains("secret"))
        } }
        assertNull(toolFailureDiagnostic("private-command-secret"))
        assertNull(toolFailureDiagnostic("adapter_result_unconfirmed_io\nsecret"))
        assertNull(toolFailureDiagnostic("adapter_result_unconfirmed_private_class"))
    }
}
