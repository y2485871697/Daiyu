package app.daiyu.android

import junit.framework.TestCase
import org.json.JSONObject
import java.io.File

class SyntheticMemoryCollectorTest : TestCase() {
    private val metrics = SyntheticMemoryCollector.Metrics(11, 12, 13L, 14L, 15L, 16L)

    private fun report(collector: SyntheticMemoryCollector, completed: Boolean = false): JSONObject {
        val file = File.createTempFile("synthetic-memory-", ".json")
        try {
            collector.write(file, "test-collection", "off", completed, if (completed) null else "test_failure")
            return JSONObject(file.readText())
        } finally { check(file.delete()) }
    }

    fun testSampleIntervalEnclosesAllMetricReads() {
        val calls = ArrayList<String>()
        var now = 100L
        val collector = SyntheticMemoryCollector(clock = { calls.add("clock"); now++ },
            readMemory = { calls.add("metrics"); metrics })
        collector.sample("before_switches", 0)
        assertEquals(listOf("clock", "metrics", "clock"), calls)
        val row = report(collector).getJSONArray("samples").getJSONObject(0)
        assertEquals(100L, row.getLong("sample_start_ns"))
        assertEquals(101L, row.getLong("sample_end_ns"))
        assertEquals(11, row.getInt("total_pss_kib"))
        assertEquals(12, row.getInt("total_private_dirty_kib"))
        assertEquals(13L, row.getLong("java_heap_used_bytes"))
        assertEquals(14L, row.getLong("java_heap_committed_bytes"))
        assertEquals(15L, row.getLong("java_heap_limit_bytes"))
        assertEquals(16L, row.getLong("native_heap_allocated_bytes"))
    }

    fun testGcRequestSurvivesFailureBeforeFirstPostGcSample() {
        val collector = SyntheticMemoryCollector(readMemory = { metrics })
        collector.sample("switch_completed", 1)
        assertFalse(report(collector).getBoolean("gc_requested"))
        collector.markGcRequested()
        val failed = report(collector)
        assertTrue(failed.getBoolean("gc_requested"))
        assertEquals(0, failed.getInt("post_gc_observations"))
        assertEquals(1, failed.getInt("switches_completed"))
        assertFalse(failed.getBoolean("collection_completed"))
        assertEquals("test_failure", failed.getString("failure_stage"))
    }

    fun testCompletedReportKeepsRawEvidenceAndExplicitGcState() {
        val collector = SyntheticMemoryCollector(readMemory = { metrics })
        repeat(100) { collector.sample("switch_completed", it + 1) }
        collector.markGcRequested()
        repeat(10) { collector.sample("post_gc_observation", it + 1) }
        val completed = report(collector, completed = true)
        assertTrue(completed.getBoolean("collection_completed"))
        assertTrue(completed.getBoolean("gc_requested"))
        assertEquals(100, completed.getInt("switches_completed"))
        assertEquals(10, completed.getInt("post_gc_observations"))
        for (field in listOf("stable_recovery_verified", "peak_memory_measured", "business_acceptance", "collection_authenticated")) {
            assertFalse(completed.getBoolean(field))
        }
    }
}
