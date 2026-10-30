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
        assertEquals(setOf("format", "version", "enabled", "events", "privacy", "frame_performance"), json.keys().asSequence().toSet())
        assertEquals("daiyu-diagnostics", json.getString("format")); assertEquals(2, json.getInt("version"))
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
}
