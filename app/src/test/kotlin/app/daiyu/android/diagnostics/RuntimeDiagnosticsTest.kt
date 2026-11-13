package app.daiyu.android.diagnostics

import app.daiyu.android.domain.*
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
    @Test fun pixelTraceRetainsOnlyDimensionsRegionAndExactChangedTileBounds() {
        RuntimeDiagnostics.install(Files.createTempDirectory("runtime-pixel-trace").toFile())
        RuntimeDiagnostics.awaitWrites(); RuntimeDiagnostics.clear(); RuntimeDiagnostics.enabled = true
        try {
            val region = app.daiyu.android.gui.GuiBounds(992, 2432, 1216, 2640)
            val tile = app.daiyu.android.gui.GuiBounds(1184, 2624, 1216, 2640)
            RuntimeDiagnostics.pixels(app.daiyu.android.gui.GuiVisualPixels.Check(RuntimeDiagnostics.Code.LOCAL_PIXELS_CHANGED,
                1216, 2640, 1216, 2640, 49, 1, region, listOf(tile), false))
            RuntimeDiagnostics.awaitWrites()
            val row = RuntimeDiagnostics.export().getJSONObject(0)
            assertEquals("GUI_VALIDATE", row.getString("stage"))
            assertEquals("LOCAL_PIXELS_CHANGED", row.getString("code"))
            assertTrue(row.getBoolean("admitted")); assertFalse(row.getBoolean("target_tile_changed"))
            assertEquals("DIAGNOSTIC_ONLY", row.getString("pixel_policy"))
            assertEquals(49, row.getInt("bytes_or_size")); assertEquals(1, row.getInt("revision_or_count"))
            assertEquals("[1216,2640,1216,2640]", row.getJSONArray("dimensions").toString())
            assertEquals("[992,2432,1216,2640]", row.getJSONArray("compared_region").toString())
            assertEquals("[[1184,2624,1216,2640]]", row.getJSONArray("changed_tile_bounds").toString())
            assertFalse(row.has("data_base64")); assertFalse(row.has("png")); assertFalse(row.has("text"))
        } finally { RuntimeDiagnostics.enabled = false; RuntimeDiagnostics.clear() }
    }

    @Test fun phaseMilestonesKeepTheirSpanAndIgnoreClosedOrClearedSpans() {
        RuntimeDiagnostics.install(Files.createTempDirectory("runtime-phase-trace").toFile())
        RuntimeDiagnostics.awaitWrites(); RuntimeDiagnostics.clear(); RuntimeDiagnostics.enabled = true
        try {
            val span = RuntimeDiagnostics.begin(RuntimeDiagnostics.Stage.SUMMARY_REQUEST)
            span.mark(RuntimeDiagnostics.Code.FIRST_EVENT)
            span.mark(RuntimeDiagnostics.Code.FIRST_TEXT, size = 12, count = 3)
            span.end(); span.mark(RuntimeDiagnostics.Code.PROTOCOL_TERMINAL)
            RuntimeDiagnostics.awaitWrites()
            val rows = RuntimeDiagnostics.export()
            assertEquals(4, rows.length())
            assertEquals(listOf("BEGIN", "FIRST_EVENT", "FIRST_TEXT", "END"),
                (0 until rows.length()).map { rows.getJSONObject(it).getString("code") })
            assertTrue((0 until rows.length()).all { rows.getJSONObject(it).getLong("span") == span.id })
            val stale = RuntimeDiagnostics.begin(RuntimeDiagnostics.Stage.GUI_CAPTURE)
            RuntimeDiagnostics.clear(); stale.mark(RuntimeDiagnostics.Code.CAPTURE_CALLBACK); stale.end()
            RuntimeDiagnostics.awaitWrites(); assertEquals(0, RuntimeDiagnostics.export().length())
        } finally { RuntimeDiagnostics.enabled = false; RuntimeDiagnostics.clear() }
    }

    @Test fun spanRegistrationNeverWaitsForDiagnosticDiskLock() {
        RuntimeDiagnostics.install(Files.createTempDirectory("runtime-lock").toFile())
        RuntimeDiagnostics.awaitWrites()
        RuntimeDiagnostics.clear()
        RuntimeDiagnostics.enabled = true
        val field = RuntimeDiagnostics::class.java.getDeclaredField("lock").apply { isAccessible = true }
        val lock = field.get(RuntimeDiagnostics) as java.util.concurrent.locks.ReentrantLock
        val done = java.util.concurrent.CountDownLatch(1)
        val worker = Thread {
            try { RuntimeDiagnostics.begin(RuntimeDiagnostics.Stage.UI_PUBLISH).end() }
            finally { done.countDown() }
        }
        lock.lock()
        try {
            worker.start()
            assertTrue("diagnostic registration waited for file I/O", done.await(1, java.util.concurrent.TimeUnit.SECONDS))
        } finally { lock.unlock(); worker.join(1000) }
        RuntimeDiagnostics.awaitWrites()
        assertEquals(0, RuntimeDiagnostics.export().length())
        RuntimeDiagnostics.enabled = false
    }

    @Test fun fatalWriteCannotRestoreClearedOrDisabledGeneration() {
        RuntimeDiagnostics.install(Files.createTempDirectory("runtime-fatal").toFile())
        RuntimeDiagnostics.awaitWrites()
        RuntimeDiagnostics.clear()
        RuntimeDiagnostics.enabled = true
        val field = RuntimeDiagnostics::class.java.getDeclaredField("generation").apply { isAccessible = true }
        val generation = field.get(RuntimeDiagnostics) as java.util.concurrent.atomic.AtomicLong
        val write = RuntimeDiagnostics::class.java.getDeclaredMethod("writeFatalAtEpoch",
            Throwable::class.java, Thread::class.java, java.lang.Long.TYPE).apply { isAccessible = true }
        val oldEpoch = generation.get()
        RuntimeDiagnostics.clear()
        write.invoke(RuntimeDiagnostics, IllegalStateException("private-fatal-canary"), Thread.currentThread(), oldEpoch)
        assertEquals(0, RuntimeDiagnostics.export().length())
        val currentEpoch = generation.get()
        RuntimeDiagnostics.enabled = false
        write.invoke(RuntimeDiagnostics, OutOfMemoryError("private-fatal-canary"), Thread.currentThread(), currentEpoch)
        assertEquals(0, RuntimeDiagnostics.export().length())
    }

    @Test fun modelFailureExportsOnlyClosedTransportObservationsAndRespectsDisable() {
        RuntimeDiagnostics.install(Files.createTempDirectory("runtime-model-transport").toFile())
        RuntimeDiagnostics.awaitWrites(); RuntimeDiagnostics.clear(); RuntimeDiagnostics.enabled = true
        try {
            val diagnostic = ModelRequestDiagnostic(transport = ModelTransportDiagnostic(
                ModelTransportPhase.READING_STREAM, ModelTransportCause.PARTIAL_SSE_FRAME, ModelRequestKind.RESUME,
                210, 190, 3, 50, true, false, eofSeen = true, partialFrame = true, localDisconnectRequested = true,
                socketDetail = ModelSocketDetail.RESET))
            RuntimeDiagnostics.begin(RuntimeDiagnostics.Stage.MODEL_REQUEST).end(ModelRequestException(ModelFailure.NETWORK, diagnostic))
            RuntimeDiagnostics.awaitWrites()
            val rows = RuntimeDiagnostics.export()
            val row = rows.getJSONObject(rows.length() - 1)
            assertEquals("READING_STREAM", row.getString("transport_phase"))
            assertEquals("PARTIAL_SSE_FRAME", row.getString("transport_cause"))
            assertEquals("RESUME", row.getString("request_kind"))
            assertEquals(210L, row.getLong("request_elapsed_ms"))
            assertEquals(3L, row.getLong("sse_frames"))
            assertTrue(row.getBoolean("eof_seen")); assertTrue(row.getBoolean("partial_sse_frame"))
            assertTrue(row.getBoolean("local_disconnect_requested"))
            assertEquals("RESET", row.getString("socket_detail"))
            assertFalse(row.getBoolean("completion_received"))
            for (forbidden in listOf("url", "headers", "body", "message", "cause", "suppressed")) assertFalse(row.has(forbidden))
            RuntimeDiagnostics.enabled = false
            RuntimeDiagnostics.begin(RuntimeDiagnostics.Stage.MODEL_REQUEST).end(ModelRequestException(ModelFailure.NETWORK, diagnostic))
            RuntimeDiagnostics.awaitWrites()
            assertEquals(rows.length(), RuntimeDiagnostics.export().length())
        } finally { RuntimeDiagnostics.enabled = false; RuntimeDiagnostics.clear() }
    }

}
