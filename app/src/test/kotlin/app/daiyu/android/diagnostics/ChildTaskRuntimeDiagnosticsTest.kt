package app.daiyu.android.diagnostics

import app.daiyu.android.domain.delegation.*
import app.daiyu.android.domain.runtime.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ChildTaskRuntimeDiagnosticsTest {
    @Test fun requestIdentityExportClearAndDisabledPrivacy() {
        RuntimeDiagnostics.install(Files.createTempDirectory("child-trace").toFile())
        RuntimeDiagnostics.clear(); RuntimeDiagnostics.enabled = true
        val event = ChildTaskDiagnosticEvent(ChildRunOwner("conversation", "parent", "group", 1, "child", "message"),
            "assigned", ChildModelRole.REVIEW, ChildTaskDiagnosticPhase.REQUEST_STARTED,
            ChildRequestModel("provider", "actual-api-model"), RequestCheckpoint("request", "attempt", "owner", RequestOperation.NEXT), 2)
        try {
            RuntimeDiagnostics.childTask(event)
            val rows = RuntimeDiagnostics.exportChildTasks()
            assertEquals(1, rows.length())
            val row = rows.getJSONObject(0)
            assertEquals("child", row.getString("child_run_id")); assertEquals("actual-api-model", row.getString("model_id"))
            assertEquals("request", row.getString("request_id")); assertEquals(2, row.getInt("request_sequence"))
            assertTrue(row.getLong("time_ms") > 0)
            assertFalse(row.has("endpoint")); assertFalse(row.has("headers")); assertFalse(row.has("prompt")); assertFalse(row.has("arguments"))
            RuntimeDiagnostics.enabled = false
            RuntimeDiagnostics.childTask(event)
            assertEquals(1, RuntimeDiagnostics.exportChildTasks().length())
            RuntimeDiagnostics.clear(); assertEquals(0, RuntimeDiagnostics.exportChildTasks().length())
        } finally { RuntimeDiagnostics.enabled = false; RuntimeDiagnostics.clear() }
    }
}
