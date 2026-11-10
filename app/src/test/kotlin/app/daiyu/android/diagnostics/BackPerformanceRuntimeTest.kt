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
                val refresh = recorder.begin("daiyu.chat.refresh")
                val publish = recorder.begin("daiyu.chat.publish")
                recorder.complete(publish); recorder.complete(refresh)
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
                "max_budget_ns", "min_refresh_hz", "max_refresh_hz", "start_overlap_count", "start_overlap_slow_count", "phase_semantics", "phases", "worst_frames", "timeline", "timeline_dropped",
                "capture_timings", "capture_timings_dropped", "capture_timing_semantics",
                "compose_observed", "compose_limited", "compose_timing_semantics", "compositions"),
                row.keys().asSequence().toSet())
            assertEquals("INCLUSIVE_NOT_ADDITIVE", row.getString("phase_semantics"))
            val chatPhases = row.getJSONArray("phases")
            assertEquals(2, chatPhases.length())
            assertEquals(setOf("CHAT_REFRESH", "CHAT_PUBLISH"),
                (0 until chatPhases.length()).map { chatPhases.getJSONObject(it).getString("phase") }.toSet())
            for (index in 0 until chatPhases.length()) {
                val phase = chatPhases.getJSONObject(index)
                assertEquals(setOf("phase", "count", "inclusive_total_ns", "max_ns", "in_flight_count",
                    "in_flight_max_observed_ns"), phase.keys().asSequence().toSet())
                assertEquals(1, phase.getInt("count"))
                assertEquals(0, phase.getInt("in_flight_count"))
            }
            recorder.start(owner, BackPerformanceRecorder.Kind.BACK_PRESS)
            recorder.begin("daiyu.back.business-handler")
            recorder.frame(now - 10_000_000L, 40_000_000L, 16_666_667L, 60f, LongArray(9))
            now += BackPerformanceRecorder.HARD_NS
            recorder.checkpoint()
            val boundary = RuntimeDiagnostics.export().getJSONObject(1)
            assertEquals(0, boundary.getInt("frame_count"))
            assertEquals(1, boundary.getInt("start_overlap_slow_count"))
            assertTrue(boundary.getJSONArray("worst_frames").getJSONObject(0).getBoolean("overlaps_start_unattributed"))
            assertEquals(-10_000_000L, boundary.getJSONArray("worst_frames").getJSONObject(0).getLong("vsync_offset_ns"))
            val phase = boundary.getJSONArray("phases").getJSONObject(0)
            assertEquals(0, phase.getInt("count")); assertEquals(1, phase.getInt("in_flight_count"))
            assertEquals(BackPerformanceRecorder.HARD_NS, phase.getLong("in_flight_max_observed_ns"))
            // The fixed whitelist still fits the unchanged per-line/file budgets at full phase
            // cardinality, including the cold-only revision phase and four bounded worst frames.
            RuntimeDiagnostics.clear()
            recorder.start(owner, BackPerformanceRecorder.Kind.BACK_PRESS)
            val allSamples = BackPerformanceRecorder.Phase.entries.map { recorder.begin(it.trace) }
            assertTrue(allSamples.all { it != null })
            now += 20_000_000L
            allSamples.forEach(recorder::complete)
            repeat(50) { recorder.mark(BackPerformanceRecorder.Event.MOTION_START, recorder.token(), it) }
            repeat(30) {
                val capture = recorder.begin("daiyu.back.capture-wall", recorder.token())
                now += 1000L
                recorder.completeCapture(capture, BackPerformanceRecorder.CaptureResult.DISCARDED)
            }
            val composeToken = recorder.token()!!
            recorder.compositionObserved(composeToken); recorder.compositionLimited(composeToken)
            repeat(4) { recorder.composition(composeToken, "组合".repeat(200), now, 20_000_000L, 20_000_000L) }
            repeat(4) { recorder.frame(now, 40_000_000L + it, 16_666_667L, 60f, LongArray(9)) }
            recorder.end(BackPerformanceRecorder.End.TAIL)
            val full = RuntimeDiagnostics.export().getJSONObject(0)
            assertEquals(22, full.getJSONArray("phases").length())
            assertEquals(4, full.getJSONArray("worst_frames").length())
            assertEquals(32, full.getJSONArray("timeline").length())
            assertEquals(18, full.getInt("timeline_dropped"))
            assertEquals(16, full.getJSONArray("capture_timings").length())
            assertEquals(16, full.getInt("capture_timings_dropped"))
            assertEquals("ELAPSED_INCLUDING_SUSPENSION_NOT_CPU", full.getString("capture_timing_semantics"))
            assertEquals(setOf("event", "offset_ns", "count"), full.getJSONArray("timeline").getJSONObject(0).keys().asSequence().toSet())
            assertEquals(setOf("phase", "start_offset_ns", "elapsed_ns", "result"), full.getJSONArray("capture_timings").getJSONObject(0).keys().asSequence().toSet())
            assertTrue(full.getJSONArray("worst_frames").getJSONObject(0).has("vsync_offset_ns"))
            assertTrue(full.getBoolean("compose_observed")); assertTrue(full.getBoolean("compose_limited"))
            assertEquals("NESTED_ELAPSED_NOT_CPU", full.getString("compose_timing_semantics"))
            assertEquals(4, full.getJSONArray("compositions").length())
            val composition = full.getJSONArray("compositions").getJSONObject(0)
            assertEquals(setOf("function", "start_offset_ns", "inclusive_ns", "self_ns"), composition.keys().asSequence().toSet())
            assertEquals(127, composition.getString("function").length)
            assertTrue(full.toString().toByteArray(Charsets.UTF_8).size + 1 <= 16 * 1024)
            val revisionPhase = (0 until full.getJSONArray("phases").length())
                .map { full.getJSONArray("phases").getJSONObject(it) }
                .single { it.getString("phase") == "MESSAGE_ACTION_REVISION" }
            assertEquals(1, revisionPhase.getInt("count"))
            assertEquals(20_000_000L, revisionPhase.getLong("inclusive_total_ns"))
            RuntimeDiagnostics.clear()
            val onlyComposition = recorder.start(owner, BackPerformanceRecorder.Kind.BACK_PRESS)
            recorder.compositionObserved(onlyComposition)
            val values = listOf(3L, 20L, 2L, 5L, 8L, 4L)
            values.forEachIndexed { index, value ->
                recorder.composition(onlyComposition, "app.daiyu.android.StaticFunction$index (Fixture.kt:1)",
                    now + index * 1_000_000L, (value + 2) * 1_000_000L, value * 1_000_000L)
            }
            now += 30_000_000L; recorder.end(BackPerformanceRecorder.End.PAUSED)
            val compositionOnly = RuntimeDiagnostics.export().getJSONObject(0)
            assertEquals(0, compositionOnly.getInt("frame_count"))
            assertEquals(0, compositionOnly.getJSONArray("phases").length())
            val ranked = compositionOnly.getJSONArray("compositions")
            assertEquals(4, ranked.length())
            listOf(1, 4, 3, 5).forEachIndexed { rank, index ->
                val sample = ranked.getJSONObject(rank)
                assertEquals("app.daiyu.android.StaticFunction$index (Fixture.kt:1)", sample.getString("function"))
                assertEquals(index * 1_000_000L, sample.getLong("start_offset_ns"))
                assertEquals((values[index] + 2) * 1_000_000L, sample.getLong("inclusive_ns"))
                assertEquals(values[index] * 1_000_000L, sample.getLong("self_ns"))
            }
            assertFalse(RuntimeDiagnostics.enabled)
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
