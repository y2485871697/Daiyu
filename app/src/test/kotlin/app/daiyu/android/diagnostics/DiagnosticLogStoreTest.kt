package app.daiyu.android.diagnostics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class DiagnosticLogStoreTest {
    private fun fixture(work: (File, DiagnosticLogStore) -> Unit) {
        val root = Files.createTempDirectory("diagnostics-test").toFile()
        try { val file = File(root, "diagnostics/state.json"); work(file, DiagnosticLogStore(file, clock = { 123L })) }
        finally { root.deleteRecursively() }
    }
    @Test fun defaultsOffAndDisabledEventsDoNotCreateAFile() = fixture { file, store ->
        assertEquals(DiagnosticLogState(loaded = true), store.load())
        store.record(DiagnosticEvent.RUN_COMPLETED)
        assertFalse(file.exists())
        assertEquals(0, JSONObject(String(store.exportSnapshot(), Charsets.UTF_8)).getJSONArray("events").length())
    }
    @Test fun enablingAndEventsSurviveReopenButDisablingRetainsHistory() = fixture { file, store ->
        store.setEnabled(true); store.record(DiagnosticEvent.DATA_STARTED)
        val reopened = DiagnosticLogStore(file)
        assertEquals(DiagnosticLogState(true, true, 1), reopened.load())
        reopened.setEnabled(false); reopened.record(DiagnosticEvent.RUN_FAILED)
        assertEquals(DiagnosticLogState(true, false, 1), DiagnosticLogStore(file).load())
    }
    @Test fun clearKeepsEnablementAndDoesNotClearAnyOtherFile() = fixture { file, store ->
        val unrelated = File(file.parentFile.parentFile, "operation-journal.json").apply { writeText("UNKNOWN private-canary") }
        store.setEnabled(true); store.record(DiagnosticEvent.RUN_FAILED)
        store.clear()
        assertEquals(DiagnosticLogState(true, true, 0), DiagnosticLogStore(file).load())
        assertEquals("UNKNOWN private-canary", unrelated.readText())
        assertFalse(String(store.exportSnapshot(), Charsets.UTF_8).contains("private-canary"))
    }
    @Test fun boundedEntriesEvictOldestAndSnapshotNeverChangesAfterClear() = fixture { file, _ ->
        var tick = 0L
        val store = DiagnosticLogStore(file, clock = { tick++ })
        store.setEnabled(true)
        repeat(DiagnosticLogStore.MAX_ENTRIES + 2) { store.record(DiagnosticEvent.DATA_COMPLETED) }
        assertEquals(DiagnosticLogStore.MAX_ENTRIES, store.state.value.count)
        assertTrue(file.length() <= DiagnosticLogStore.MAX_BYTES)
        val snapshot = store.exportSnapshot()
        val rows = JSONObject(String(snapshot, Charsets.UTF_8)).getJSONArray("events")
        assertEquals(2L, rows.getJSONObject(0).getLong("time_ms"))
        assertEquals((DiagnosticLogStore.MAX_ENTRIES + 1).toLong(), rows.getJSONObject(rows.length() - 1).getLong("time_ms"))
        store.clear()
        assertEquals(DiagnosticLogStore.MAX_ENTRIES, JSONObject(String(snapshot, Charsets.UTF_8)).getJSONArray("events").length())
        assertEquals(0, store.state.value.count)
    }
    @Test fun malformedUnknownOversizedAndInvalidUtf8DoNotExportAndExplicitClearRecovers() = fixture { file, _ ->
        file.parentFile.mkdirs()
        val bad = listOf("not-json".toByteArray(), "{\"version\":2,\"enabled\":true,\"events\":[]}".toByteArray(),
            ByteArray(DiagnosticLogStore.MAX_BYTES + 1) { 32 }, byteArrayOf(0xc3.toByte(), 0x28))
        for (bytes in bad) {
            file.writeBytes(bytes)
            val store = DiagnosticLogStore(file)
            assertTrue(store.load().unavailable)
            assertThrows(IllegalStateException::class.java) { store.exportSnapshot() }
            assertArrayEquals(bytes, file.readBytes())
            store.clear()
            assertEquals(DiagnosticLogState(true, false, 0), DiagnosticLogStore(file).load())
        }
    }
    @Test fun failedWriteDoesNotReportEnabledOrChangeDurableBytes() = fixture { file, store ->
        store.setEnabled(false)
        val before = file.readBytes()
        val failing = DiagnosticLogStore(file, write = { _, _ -> error("private-failure-canary") })
        assertThrows(IllegalStateException::class.java) { failing.setEnabled(true) }
        assertFalse(failing.state.value.enabled)
        assertTrue(failing.state.value.unavailable)
        assertArrayEquals(before, file.readBytes())
        assertFalse(failing.state.value.toString().contains("private-failure-canary"))
    }
    @Test fun strictSchemaRejectsUnknownEventsKeysTypesAndTrailingValues() = fixture { file, _ ->
        file.parentFile.mkdirs()
        val valid = "{\"version\":1,\"enabled\":true,\"events\":[{\"time_ms\":123,\"event\":\"RUN_COMPLETED\"}]}"
        val invalid = listOf(valid.replace("RUN_COMPLETED", "UNKNOWN"),
            valid.replace("123", "-1"), valid.replace("123", "1.5"),
            valid.replace("true", "\"true\""), valid.replace("123", "\"123\""),
            valid.replace("\"version\":1", "\"extra\":0,\"version\":1"),
            valid.replace("\"time_ms\":123", "\"extra\":0,\"time_ms\":123"), valid + " {}")
        for (text in invalid) {
            file.writeText(text)
            val store = DiagnosticLogStore(file)
            assertTrue(text, store.load().unavailable)
            assertThrows(IllegalStateException::class.java) { store.exportSnapshot() }
            assertEquals(text, file.readText())
        }
    }
    @Test fun recordWriteFailureDoesNotThrowOrChangeTheBusinessOwnedBytesAndClearCanRecover() = fixture { file, store ->
        store.setEnabled(true); store.record(DiagnosticEvent.RUN_COMPLETED)
        val before = file.readBytes()
        var fail = true
        val recovering = DiagnosticLogStore(file, write = { target, bytes ->
            if (fail) error("private-error-canary") else target.writeBytes(bytes)
        })
        recovering.record(DiagnosticEvent.RUN_FAILED)
        assertTrue(recovering.state.value.unavailable)
        assertArrayEquals(before, file.readBytes())
        assertThrows(IllegalStateException::class.java) { recovering.clear() }
        assertArrayEquals(before, file.readBytes())
        fail = false; recovering.clear()
        assertEquals(DiagnosticLogState(true, false, 0), DiagnosticLogStore(file).load())
    }
    @Test fun exportUsesOnlyTheFixedMetadataContract() = fixture { _, store ->
        store.setEnabled(true); store.record(DiagnosticEvent.RUN_WAITING_USER)
        val json = JSONObject(String(store.exportSnapshot(), Charsets.UTF_8))
        assertEquals(setOf("format", "version", "enabled", "events", "privacy", "frame_performance", "runtime_trace"), json.keys().asSequence().toSet())
        assertEquals("daiyu-diagnostics", json.getString("format")); assertEquals(2, json.getInt("version"))
        assertTrue(json.getJSONArray("runtime_trace").length() <= 256)
        val row = json.getJSONArray("events").getJSONObject(0)
        assertEquals(setOf("time_ms", "event"), row.keys().asSequence().toSet())
        assertEquals(123L, row.getLong("time_ms")); assertEquals("RUN_WAITING_USER", row.getString("event"))
    }
    @Test fun clearReplacesAnAbandonedPendingFileWithoutTouchingOtherFiles() = fixture { file, store ->
        store.setEnabled(true); store.record(DiagnosticEvent.RUN_FAILED)
        val pending = File(file.parentFile, "${file.name}.pending").apply { writeText("abandoned diagnostic metadata") }
        val unrelated = File(file.parentFile, "unrelated.pending").apply { writeText("unrelated canary") }
        DiagnosticLogStore(file).clear()
        assertFalse(pending.exists())
        assertEquals("unrelated canary", unrelated.readText())
        assertEquals(DiagnosticLogState(true, true, 0), DiagnosticLogStore(file).load())
    }
    @Test fun concurrentRecordClearAndExportKeepValidBoundedState() = fixture { file, store ->
        store.setEnabled(true)
        val start = CountDownLatch(1); val error = AtomicReference<Throwable?>()
        val workers = (0..2).map { worker -> Thread {
            try {
                start.await()
                repeat(12) {
                    when (worker) { 0 -> store.record(DiagnosticEvent.RUN_COMPLETED); 1 -> store.clear(); else -> store.exportSnapshot() }
                }
            } catch (failure: Throwable) { error.set(failure) }
        }.apply { start() } }
        start.countDown(); workers.forEach { it.join(10_000); assertFalse(it.isAlive) }
        error.get()?.let { throw it }
        val reopened = DiagnosticLogStore(file).load()
        assertFalse(reopened.unavailable); assertTrue(reopened.enabled)
        assertEquals(store.state.value, reopened)
    }
    @Test fun asyncSpeechIsQueuedWithoutIoAndPreservesOccurrenceTimesAndOrder() = fixture { file, _ ->
        val pending = mutableListOf<Runnable>()
        var time = 10L
        val store = DiagnosticLogStore(file, clock = { time }, executor = java.util.concurrent.Executor { pending += it })
        store.setEnabled(true)
        val before = file.readBytes()
        store.recordAsync(DiagnosticEvent.SPEECH_HTTP_STARTED)
        time = 25L
        store.recordAsync(DiagnosticEvent.SPEECH_HTTP_COMPLETED)
        assertArrayEquals(before, file.readBytes())
        assertEquals(2, pending.size)
        time = 99L
        pending.forEach { it.run() }
        val rows = JSONObject(file.readText()).getJSONArray("events")
        assertEquals("SPEECH_HTTP_STARTED", rows.getJSONObject(0).getString("event"))
        assertEquals(10L, rows.getJSONObject(0).getLong("time_ms"))
        assertEquals("SPEECH_HTTP_COMPLETED", rows.getJSONObject(1).getString("event"))
        assertEquals(25L, rows.getJSONObject(1).getLong("time_ms"))
        assertEquals(2, DiagnosticLogStore(file).load().count)
    }
    @Test fun asyncSpeechRespectsDisabledStateAndDoesNotCreateAFile() = fixture { file, _ ->
        val pending = mutableListOf<Runnable>()
        val store = DiagnosticLogStore(file, executor = java.util.concurrent.Executor { pending += it })
        store.recordAsync(DiagnosticEvent.SPEECH_REQUEST_ACCEPTED)
        pending.forEach { it.run() }
        assertFalse(file.exists())
        assertEquals(0, store.load().count)
    }
    @Test fun clearInvalidatesAlreadyQueuedSpeechEvents() = fixture { file, _ ->
        val pending = mutableListOf<Runnable>()
        val store = DiagnosticLogStore(file, executor = java.util.concurrent.Executor { pending += it })
        store.setEnabled(true)
        store.recordAsync(DiagnosticEvent.SPEECH_PLAYER_QUEUED)
        store.clear()
        pending.forEach { it.run() }
        assertEquals(DiagnosticLogState(true, true, 0), DiagnosticLogStore(file).load())
    }
    @Test fun disableAndReenableDoNotReplayAnOldQueuedStage() = fixture { file, _ ->
        val pending = mutableListOf<Runnable>()
        val store = DiagnosticLogStore(file, executor = java.util.concurrent.Executor { pending += it })
        store.setEnabled(true)
        store.recordAsync(DiagnosticEvent.SPEECH_HTTP_STARTED)
        store.setEnabled(false)
        store.setEnabled(true)
        store.recordAsync(DiagnosticEvent.SPEECH_REQUEST_ACCEPTED)
        pending.forEach { it.run() }
        val rows = JSONObject(file.readText()).getJSONArray("events")
        assertEquals(1, rows.length())
        assertEquals("SPEECH_REQUEST_ACCEPTED", rows.getJSONObject(0).getString("event"))
    }
    @Test fun rejectedAsyncSubmissionIsNonfatalAndMarksTheNextAcceptedEvent() = fixture { file, _ ->
        var reject = true
        val pending = mutableListOf<Runnable>()
        val store = DiagnosticLogStore(file, executor = java.util.concurrent.Executor {
            if (reject) throw java.util.concurrent.RejectedExecutionException("private-rejection-canary")
            pending += it
        })
        store.setEnabled(true)
        store.recordAsync(DiagnosticEvent.SPEECH_HTTP_STARTED)
        reject = false
        store.recordAsync(DiagnosticEvent.SPEECH_HTTP_COMPLETED)
        pending.single().run()
        val rows = JSONObject(file.readText()).getJSONArray("events")
        assertEquals(2, rows.length())
        assertEquals("DIAGNOSTIC_EVENTS_DROPPED", rows.getJSONObject(0).getString("event"))
        assertEquals("SPEECH_HTTP_COMPLETED", rows.getJSONObject(1).getString("event"))
        assertFalse(file.readText().contains("private-rejection-canary"))
    }
    @Test fun asyncWriteFailureDoesNotEscapeOrReplaceDurableHistory() = fixture { file, store ->
        store.setEnabled(true)
        store.record(DiagnosticEvent.RUN_COMPLETED)
        val before = file.readBytes()
        val failing = DiagnosticLogStore(file, write = { _, _ -> error("private-canary") },
            executor = java.util.concurrent.Executor { it.run() })
        failing.recordAsync(DiagnosticEvent.SPEECH_PLAYER_QUEUED)
        assertTrue(failing.state.value.unavailable)
        assertArrayEquals(before, file.readBytes())
    }
    @Test fun speechEventsKeepTheExistingFixedMetadataSchemaAndOldHistory() = fixture { file, store ->
        store.setEnabled(true)
        store.record(DiagnosticEvent.DATA_COMPLETED)
        val reopened = DiagnosticLogStore(file, executor = java.util.concurrent.Executor { it.run() })
        reopened.recordAsync(DiagnosticEvent.SPEECH_NATIVE_STARTED)
        val json = JSONObject(file.readText())
        assertEquals(setOf("version", "enabled", "events"), json.keys().asSequence().toSet())
        assertEquals(1, json.getInt("version"))
        val rows = json.getJSONArray("events")
        assertEquals("DATA_COMPLETED", rows.getJSONObject(0).getString("event"))
        assertEquals("SPEECH_NATIVE_STARTED", rows.getJSONObject(1).getString("event"))
        for (index in 0 until rows.length()) {
            assertEquals(setOf("time_ms", "event"), rows.getJSONObject(index).keys().asSequence().toSet())
        }
        assertEquals(2, DiagnosticLogStore(file).load().count)
    }

    @Test fun productionAsyncWriterRunsOffCallerThreadWithoutBlockingTheCaller() = fixture { file, _ ->
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val writerThread = AtomicReference<Thread?>()
        var initializing = true
        val store = DiagnosticLogStore(file, write = { target, bytes ->
            if (!initializing) {
                writerThread.set(Thread.currentThread())
                started.countDown()
                assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            target.parentFile.mkdirs()
            target.writeBytes(bytes)
            if (!initializing) finished.countDown()
        })
        store.setEnabled(true)
        initializing = false
        try {
            store.recordAsync(DiagnosticEvent.SPEECH_NATIVE_PREPARING)
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertNotSame(Thread.currentThread(), writerThread.get())
        } finally { release.countDown() }
        assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, DiagnosticLogStore(file).load().count)
    }

    @Test fun clearAlsoInvalidatesDroppedMarkersFromTheOldGeneration() = fixture { file, _ ->
        var reject = true
        val pending = mutableListOf<Runnable>()
        val store = DiagnosticLogStore(file, executor = java.util.concurrent.Executor {
            if (reject) throw java.util.concurrent.RejectedExecutionException()
            pending += it
        })
        store.setEnabled(true)
        store.recordAsync(DiagnosticEvent.SPEECH_HTTP_STARTED)
        store.clear()
        reject = false
        store.recordAsync(DiagnosticEvent.SPEECH_REQUEST_ACCEPTED)
        pending.single().run()
        val rows = JSONObject(file.readText()).getJSONArray("events")
        assertEquals(1, rows.length())
        assertEquals("SPEECH_REQUEST_ACCEPTED", rows.getJSONObject(0).getString("event"))
    }
    @Test fun staleWorkerCannotConsumeTheNewGenerationsDroppedMarker() = fixture { file, _ ->
        var reject = false
        val pending = mutableListOf<Runnable>()
        val store = DiagnosticLogStore(file, executor = java.util.concurrent.Executor {
            if (reject) throw java.util.concurrent.RejectedExecutionException()
            pending += it
        })
        store.setEnabled(true)
        store.recordAsync(DiagnosticEvent.SPEECH_HTTP_STARTED)
        store.clear()
        reject = true
        store.recordAsync(DiagnosticEvent.SPEECH_HTTP_STARTED)
        reject = false
        store.recordAsync(DiagnosticEvent.SPEECH_HTTP_COMPLETED)
        pending.forEach { it.run() }
        val rows = JSONObject(file.readText()).getJSONArray("events")
        assertEquals(2, rows.length())
        assertEquals("DIAGNOSTIC_EVENTS_DROPPED", rows.getJSONObject(0).getString("event"))
        assertEquals("SPEECH_HTTP_COMPLETED", rows.getJSONObject(1).getString("event"))
    }

    @Test fun diagnosticOutOfMemoryCannotBlockCallerWorkOrReplaceItsFailure() = fixture { file, _ ->
        val failure = OutOfMemoryError("private-clock-canary")
        val store = DiagnosticLogStore(file, clock = { throw failure })
        store.record(DiagnosticEvent.RUN_SLICE_STARTED)
        var work = 0
        val original = IllegalStateException("business-canary")
        val result = runCatching { work++; throw original }
        store.record(DiagnosticEvent.RUN_FAILED)
        store.recordAsync(DiagnosticEvent.RUN_FAILED)
        assertEquals(1, work)
        assertSame(original, result.exceptionOrNull())
        assertFalse(file.exists())
    }

}
