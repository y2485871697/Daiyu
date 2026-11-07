package app.daiyu.android.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class RuntimeDiagnosticsTest {
    @Test fun traceIsBoundedPairsFailuresAndNeverRecordsExceptionOrBusinessText() {
        RuntimeDiagnostics.install(Files.createTempDirectory("runtime-trace").toFile())
        RuntimeDiagnostics.awaitWrites()
        RuntimeDiagnostics.clear()
        RuntimeDiagnostics.enabled = true
        val failure = OutOfMemoryError("private-chat-screenshot-password-canary")
        assertSame(failure, assertThrows(OutOfMemoryError::class.java) {
            RuntimeDiagnostics.measure(RuntimeDiagnostics.Stage.CHECKPOINT_ENCODE, 27, 2_000_000) { throw failure }
        })
        RuntimeDiagnostics.event(RuntimeDiagnostics.Stage.GUI_VALIDATE, RuntimeDiagnostics.Code.LOCAL_PIXELS_CHANGED, 81, 1)
        RuntimeDiagnostics.awaitWrites()
        val initial = RuntimeDiagnostics.export()
        val text = initial.toString()
        assertTrue(text.contains("CHECKPOINT_ENCODE"))
        assertTrue(text.contains("OOM"))
        assertTrue(text.contains("heap_used"))
        assertTrue(text.contains("frames"))
        assertFalse(text.contains("private-chat"))
        RuntimeDiagnostics.enabled = false
        RuntimeDiagnostics.measure(RuntimeDiagnostics.Stage.MODEL_REQUEST) { 1 }
        RuntimeDiagnostics.awaitWrites()
        assertEquals(initial.length(), RuntimeDiagnostics.export().length())
        RuntimeDiagnostics.enabled = true
        repeat(400) { RuntimeDiagnostics.event(RuntimeDiagnostics.Stage.UI_PUBLISH, RuntimeDiagnostics.Code.END, count = it.toLong()) }
        RuntimeDiagnostics.awaitWrites()
        assertTrue(RuntimeDiagnostics.export().length() <= 256)
        val oldSpan = RuntimeDiagnostics.begin(RuntimeDiagnostics.Stage.UI_PUBLISH)
        RuntimeDiagnostics.clear()
        oldSpan.end(failure)
        RuntimeDiagnostics.awaitWrites()
        assertEquals(0, RuntimeDiagnostics.export().length())
        RuntimeDiagnostics.enabled = false
    }
}
