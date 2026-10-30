package app.daiyu.android.diagnostics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FramePerformanceLogTest {
    private fun fixture(work: (File, FramePerformanceLog) -> Unit) {
        val directory = Files.createTempDirectory("page-frame-test").toFile()
        try { work(directory, FramePerformanceLog(directory, 2048)) } finally { directory.deleteRecursively() }
    }
    private fun row(n: Int) = JSONObject().put("page", "chat").put("sample", n)
    @Test fun defaultOffCreatesNoLogsAndExportRemainsJson() = fixture { dir, log ->
        assertNull(log.captureEpoch()); log.append(0, row(1))
        assertEquals(0, log.export().getJSONArray("windows").length())
        assertTrue(dir.listFiles()!!.isEmpty())
    }
    @Test fun clearRejectsOldCaptureAndRetainsNewCapture() = fixture { _, log ->
        log.setEnabled(true); val old = log.captureEpoch()!!
        log.append(old, row(1)); log.export(); log.clear(); log.append(old, row(2))
        log.append(log.captureEpoch()!!, row(3))
        val rows = log.export().getJSONArray("windows")
        assertEquals(1, rows.length()); assertEquals(3, rows.getJSONObject(0).getInt("sample"))
    }
    @Test fun disableRetainsHistoryAndRejectsLateCallback() = fixture { _, log ->
        log.setEnabled(true); val old = log.captureEpoch()!!
        log.append(old, row(1)); log.setEnabled(false); log.append(old, row(2))
        assertNull(log.captureEpoch()); assertEquals(1, log.export().getJSONArray("windows").length())
    }
    @Test fun flushFailureCannotKeepRecordingEnabled() = fixture { _, log ->
        log.setEnabled(true); log.flushCapture = { error("private-canary") }
        log.setEnabled(false); assertNull(log.captureEpoch())
        log.flushCapture = null
        assertEquals(1, log.export().getLong("write_failures"))
        assertFalse(log.export().toString().contains("private-canary"))
    }
    @Test fun rotationHasThreeFilesAndSizeBound() = fixture { dir, log ->
        log.setEnabled(true)
        repeat(100) { log.append(log.captureEpoch()!!, row(it).put("fixed", "x".repeat(300))); log.export() }
        assertTrue(dir.listFiles()!!.size <= 3)
        assertTrue(dir.listFiles()!!.all { it.length() <= 2048 })
        val rows = log.export().getJSONArray("windows")
        assertEquals(99, rows.getJSONObject(rows.length() - 1).getInt("sample"))
        assertTrue(rows.getJSONObject(0).getInt("sample") > 0)
    }
    @Test fun truncatedRecordDoesNotDestroyValidEventExport() = fixture { dir, log ->
        File(dir, "frames-0.jsonl").writeText("{\"page\":\"chat\"}\n{\"unfinished\"")
        val snapshot = log.export()
        assertEquals(1, snapshot.getJSONArray("windows").length())
        assertEquals(1, snapshot.getLong("corrupt_rows_skipped"))
    }
    @Test fun rejectedFilesystemWriteIsCountedAndDoesNotThrowIntoCapture() = fixture { dir, _ ->
        val blockingFile = File(dir, "not-a-directory").apply { writeText("private-canary") }
        val log = FramePerformanceLog(blockingFile)
        log.setEnabled(true); log.append(log.captureEpoch()!!, row(1))
        val snapshot = log.export()
        assertEquals(1, snapshot.getLong("write_failures"))
        assertFalse(snapshot.toString().contains("private-canary"))
    }
    @Test fun snapshotFlushesActiveWindowBeforeWriterBarrier() = fixture { _, log ->
        log.setEnabled(true)
        log.flushCapture = { log.append(log.captureEpoch()!!, row(7)) }
        assertEquals(7, log.export().getJSONArray("windows").getJSONObject(0).getInt("sample"))
    }
    @Test fun fullWriterQueueDropsWithoutBlockingCaptureOrEnqueue() = fixture { dir, _ ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val log = FramePerformanceLog(dir, 2048) { file, bytes ->
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); file.appendBytes(bytes)
        }
        log.setEnabled(true); val epoch = log.captureEpoch()!!
        try {
            log.append(epoch, row(0)); assertTrue(entered.await(2, TimeUnit.SECONDS))
            val submitted = CountDownLatch(1)
            Thread {
                assertEquals(epoch, log.captureEpoch())
                repeat(9) { log.append(epoch, row(it + 1)) }; submitted.countDown()
            }.start()
            assertTrue("capture/enqueue must not wait for disk", submitted.await(1, TimeUnit.SECONDS))
        } finally { release.countDown() }
        val snapshot = log.export()
        assertEquals(1, snapshot.getLong("queue_dropped_windows"))
        assertEquals(9, snapshot.getJSONArray("windows").length())
    }
    @Test fun partialWriteRollsBackAndDoesNotPoisonLaterWrites() = fixture { dir, _ ->
        var fail = true
        val log = FramePerformanceLog(dir, 2048) { file, bytes ->
            if (fail) { file.appendBytes(bytes.copyOf(5)); error("write-failed") } else file.appendBytes(bytes)
        }
        log.setEnabled(true); log.append(log.captureEpoch()!!, row(1)); log.export()
        fail = false; log.append(log.captureEpoch()!!, row(2))
        val snapshot = log.export()
        assertEquals(1, snapshot.getLong("write_failures"))
        assertEquals(2, snapshot.getJSONArray("windows").getJSONObject(0).getInt("sample"))
    }
    @Test fun processInterruptedTailCannotSwallowTheNextSuccessfulWindow() = fixture { dir, log ->
        File(dir, "frames-0.jsonl").writeText("{\"unfinished\"")
        log.setEnabled(true); log.append(log.captureEpoch()!!, row(3))
        val snapshot = log.export()
        assertEquals(1, snapshot.getLong("corrupt_rows_skipped"))
        assertEquals(3, snapshot.getJSONArray("windows").getJSONObject(0).getInt("sample"))
    }
    @Test fun diagnosticSwitchExportAndClearShareTheSinglePersistedPreference() = fixture { dir, _ ->
        val store = DiagnosticLogStore(File(dir, "events.json"))
        store.setEnabled(true); assertNotNull(store.frames.captureEpoch())
        store.frames.append(store.frames.captureEpoch()!!, row(8))
        val snapshot = JSONObject(String(store.exportSnapshot(), Charsets.UTF_8))
        assertEquals("daiyu-diagnostics", snapshot.getString("format"))
        assertEquals(8, snapshot.getJSONObject("frame_performance").getJSONArray("windows").getJSONObject(0).getInt("sample"))
        store.clear(); assertNotNull(store.frames.captureEpoch())
        assertEquals(0, JSONObject(String(store.exportSnapshot(), Charsets.UTF_8)).getJSONObject("frame_performance").getJSONArray("windows").length())
        store.setEnabled(false); assertNull(store.frames.captureEpoch())
        assertFalse(DiagnosticLogStore(File(dir, "events.json")).load().enabled)
    }
    @Test fun multipleActivityCapturesFlushAndReleaseIndependently() = fixture { _, log ->
        log.setEnabled(true)
        val main = Any(); val login = Any()
        log.registerCapture(main) { log.append(log.captureEpoch()!!, row(1)) }
        log.registerCapture(login) { log.append(log.captureEpoch()!!, row(2)) }
        assertEquals(2, log.export().getJSONArray("windows").length())
        log.unregisterCapture(login)
        assertEquals(3, log.export().getJSONArray("windows").length())
        log.unregisterCapture(main)
        assertEquals(3, log.export().getJSONArray("windows").length())
    }
    @Test fun routeNormalizationNeverLeaksDynamicIdentity() {
        assertEquals("provider-detail", PerformanceRoute.part("provider:secret-provider"))
        assertEquals("detail", PerformanceRoute.part("detail:secret-task"))
        assertEquals("detail", PerformanceRoute.part("https://private.invalid"))
        assertEquals("context-management", PerformanceRoute.part("context-management"))
        assertEquals("detail", PerformanceScreenNames.label("private-assistant-name"))
    }
    @Test fun intendedVsyncUsesPreviousRouteForDelayedCallbacks() {
        val timeline = PerformanceRouteTimeline()
        timeline.change(100, "chat"); timeline.change(200, "settings")
        assertEquals("chat", timeline.pageAt(199)); assertEquals("settings", timeline.pageAt(200))
        assertEquals("transition", timeline.pageAt(99))
        repeat(100) { timeline.change(201L + it, "page-$it") }
        assertEquals("transition", timeline.pageAt(199))
    }
    private fun frame(time: Long, duration: Long = 10_000_000, first: Boolean = false, budget: Long = 16_666_666) =
        PerformanceFrame(time, duration, budget, 60.0, longArrayOf(-1, 2, 3, 4, 5, 6, 7, 8, -1), 2, first)
    @Test fun summaryRetainsRawStagesPercentilesAndHonestCallbackRate() {
        var json: JSONObject? = null
        val batch = PerformanceFrameBatch({ _, row -> json = row }, { 123L })
        batch.add(1, "chat", frame(1_000_000_000, 10_000_000))
        batch.add(1, "chat", frame(1_016_666_666, 20_000_000))
        batch.add(1, "chat", frame(1_033_333_332, 800_000_000, first = true))
        batch.flush("export")
        val row = json!!
        assertEquals(3, row.getInt("frame_count")); assertEquals(2, row.getInt("judged_frames"))
        assertEquals(1, row.getInt("over_budget_frames")); assertEquals(.5, row.getDouble("over_budget_ratio"), .0001)
        assertEquals(1, row.getInt("over_700ms")); assertEquals(800_000.0, row.getDouble("p99_us"), .001)
        assertEquals(60.0, row.getDouble("callback_fps"), .001)
        assertEquals(6, row.getInt("callback_drops")); assertEquals(-1, row.getJSONArray("frames").getJSONArray(0).getLong(3))
    }
    @Test fun routeEpochRefreshAndFrameCountSplitWindows() {
        val rows = mutableListOf<Pair<Long, JSONObject>>()
        val batch = PerformanceFrameBatch({ epoch, row -> rows.add(epoch to row) })
        batch.add(1, "chat", frame(1)); batch.add(1, "settings", frame(2))
        batch.add(2, "settings", frame(3)); batch.flush("pause")
        assertEquals(3, rows.size); assertEquals("chat", rows[0].second.getString("page"))
        assertEquals(1L, rows[1].first); assertEquals(2L, rows[2].first)
        assertTrue(rows[2].second.isNull("callback_fps"))
    }
    @Test fun unavailableBudgetIsNotReportedAsSmooth() {
        var result: JSONObject? = null
        val batch = PerformanceFrameBatch({ _, row -> result = row })
        batch.add(1, "chat", frame(1, budget = -1)); batch.flush("export")
        val row = result!!
        assertEquals(0, row.getInt("judged_frames")); assertTrue(row.isNull("over_budget_ratio"))
        assertEquals(-1, row.getJSONArray("frames").getJSONArray(0).getLong(2))
    }
}
