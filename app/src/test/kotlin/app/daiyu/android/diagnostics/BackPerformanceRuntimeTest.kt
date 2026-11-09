package app.daiyu.android.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.locks.ReentrantLock

class BackPerformanceRuntimeTest {
    @Test fun automaticOnlyChannelDefaultOffToggleClearQueuePrivacyAndSharedBounds() {
        val root = Files.createTempDirectory("back-runtime-test").toFile()
        RuntimeDiagnostics.install(root)
        val directoryField = RuntimeDiagnostics::class.java.getDeclaredField("directory").apply { isAccessible = true }
        val installedRoot = directoryField.get(RuntimeDiagnostics) as java.io.File
        try {
            RuntimeDiagnostics.enabled = false; RuntimeDiagnostics.clear()
            var now = 1_000_000_000L
            val recorder = BackPerformanceRecorder({ now }, RuntimeDiagnostics::backEpoch, RuntimeDiagnostics::backIncident)
            val owner = Any()
            fun incident() {
                recorder.start(owner, BackPerformanceRecorder.Kind.BACK_PRESS)
                recorder.commit(owner)
                recorder.frame(now, 40_000_000L, 16_666_667L, 60f, LongArray(9))
                recorder.end(BackPerformanceRecorder.End.TAIL)
                now += 100_000_000L
            }
            incident()
            RuntimeDiagnostics.event(RuntimeDiagnostics.Stage.MODEL_OPEN, RuntimeDiagnostics.Code.BEGIN)
            var rows = RuntimeDiagnostics.export()
            assertEquals(1, rows.length())
            val row = rows.getJSONObject(0)
            assertEquals("BACK_PERFORMANCE", row.getString("stage"))
            assertEquals(setOf("stage", "code", "time_ms", "kind", "end_reason", "commit_attempted",
                "route_changed", "elapsed_ns", "frame_count", "slow_count", "max_ns", "min_budget_ns",
                "max_budget_ns", "min_refresh_hz", "max_refresh_hz", "start_overlap_count", "start_overlap_slow_count", "phase_semantics", "phases", "worst_frames"),
                row.keys().asSequence().toSet())
            assertEquals("INCLUSIVE_NOT_ADDITIVE", row.getString("phase_semantics"))
            recorder.start(owner, BackPerformanceRecorder.Kind.BACK_PRESS)
            recorder.begin("daiyu.back.business-handler")
            recorder.frame(now - 10_000_000L, 40_000_000L, 16_666_667L, 60f, LongArray(9))
            now += BackPerformanceRecorder.HARD_NS
            recorder.checkpoint()
            val boundary = RuntimeDiagnostics.export().getJSONObject(1)
            assertEquals(0, boundary.getInt("frame_count"))
            assertEquals(1, boundary.getInt("start_overlap_slow_count"))
            assertTrue(boundary.getJSONArray("worst_frames").getJSONObject(0).getBoolean("overlaps_start_unattributed"))
            val phase = boundary.getJSONArray("phases").getJSONObject(0)
            assertEquals(0, phase.getInt("count")); assertEquals(1, phase.getInt("in_flight_count"))
            assertEquals(BackPerformanceRecorder.HARD_NS, phase.getLong("in_flight_max_observed_ns"))
            RuntimeDiagnostics.clear(); incident()
            val epoch = RuntimeDiagnostics.backEpoch()
            RuntimeDiagnostics.enabled = true; RuntimeDiagnostics.enabled = false
            assertEquals(epoch, RuntimeDiagnostics.backEpoch())
            incident(); assertEquals(2, RuntimeDiagnostics.export().length())

            // Holding the real file lock must NOT prevent enqueue on the calling/UI thread.
            // It also makes queued-old-epoch and full-queue behavior deterministic without sleeps.
            val field = RuntimeDiagnostics::class.java.getDeclaredField("lock").apply { isAccessible = true }
            val fileLock = field.get(RuntimeDiagnostics) as ReentrantLock
            fileLock.lock()
            try {
                repeat(300) { incident() }
                RuntimeDiagnostics.clear()
            } finally { fileLock.unlock() }
            assertEquals(0, RuntimeDiagnostics.export().length())
            recorder.start(owner, BackPerformanceRecorder.Kind.PREDICTIVE)
            recorder.frame(now, 40_000_000L, 16_666_667L, 60f, LongArray(9))
            RuntimeDiagnostics.clear()
            recorder.end(BackPerformanceRecorder.End.PAUSED)
            assertEquals(0, RuntimeDiagnostics.export().length())
            repeat(300) { incident() }
            rows = RuntimeDiagnostics.export()
            assertTrue(rows.length() in 1..256)
            assertTrue(installedRoot.listFiles().orEmpty().size <= 3)
            installedRoot.listFiles().orEmpty().forEach { file ->
                assertTrue(file.length() <= RuntimeDiagnostics.FILE_BYTES)
                file.forEachLine { assertTrue(it.toByteArray().size <= 16 * 1024) }
            }
        } finally { RuntimeDiagnostics.clear(); root.deleteRecursively() }
    }
}
