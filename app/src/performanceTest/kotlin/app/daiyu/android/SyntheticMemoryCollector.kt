package app.daiyu.android

import android.os.Debug
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Test-only process snapshots, not a peak-memory or stable-recovery verdict. */
internal class SyntheticMemoryCollector(
    private val clock: () -> Long = System::nanoTime,
    private val readMemory: () -> Metrics = ::readProcessMemory,
) {
    internal data class Metrics(val totalPssKib: Int, val totalPrivateDirtyKib: Int,
        val javaHeapUsedBytes: Long, val javaHeapCommittedBytes: Long,
        val javaHeapLimitBytes: Long, val nativeHeapAllocatedBytes: Long)

    private val samples = JSONArray()
    private var gcRequested = false

    fun markGcRequested() { gcRequested = true }

    fun sample(phase: String, ordinal: Int, switchStartedNs: Long? = null) {
        val start = clock()
        val memory = readMemory()
        val end = clock()
        samples.put(JSONObject().put("phase", phase).put("ordinal", ordinal)
            .put("sample_start_ns", start).put("sample_end_ns", end)
            .put("switch_started_ns", switchStartedNs ?: JSONObject.NULL)
            .put("total_pss_kib", memory.totalPssKib).put("total_private_dirty_kib", memory.totalPrivateDirtyKib)
            .put("java_heap_used_bytes", memory.javaHeapUsedBytes)
            .put("java_heap_committed_bytes", memory.javaHeapCommittedBytes)
            .put("java_heap_limit_bytes", memory.javaHeapLimitBytes)
            .put("native_heap_allocated_bytes", memory.nativeHeapAllocatedBytes))
    }

    private companion object {
        fun readProcessMemory(): Metrics {
            val memory = Debug.MemoryInfo()
            Debug.getMemoryInfo(memory)
            val runtime = Runtime.getRuntime()
            val committed = runtime.totalMemory()
            return Metrics(memory.totalPss, memory.totalPrivateDirty, committed - runtime.freeMemory(),
                committed, runtime.maxMemory(), Debug.getNativeHeapAllocatedSize())
        }
    }

    private fun count(phase: String) = (0 until samples.length()).count { samples.getJSONObject(it).getString("phase") == phase }
    val switchSamples get() = count("switch_completed")
    val postGcSamples get() = count("post_gc_observation")

    fun write(file: File, collectionId: String, diagnosticsMode: String,
        completed: Boolean = true, failureStage: String? = null) {
        val rows = (0 until samples.length()).map { samples.getJSONObject(it) }
        if (completed) {
            check(rows.count { it.getString("phase") == "switch_completed" } == 100)
            check(rows.filter { it.getString("phase") == "switch_completed" }.map { it.getInt("ordinal") } == (1..100).toList())
            check(rows.count { it.getString("phase") == "post_gc_observation" } == 10)
        }
        file.writeText(JSONObject().put("schema", "daiyu.synthetic.memory.raw.v1")
            .put("collection_id", collectionId).put("clock", "System.nanoTime")
            .put("diagnostics_mode", diagnosticsMode).put("switches_completed", switchSamples)
            .put("collection_completed", completed).put("failure_stage", failureStage ?: JSONObject.NULL)
            .put("gc_requested", gcRequested).put("post_gc_observations", postGcSamples)
            .put("stable_recovery_verified", false).put("peak_memory_measured", false)
            .put("business_acceptance", false).put("collection_authenticated", false)
            .put("samples", samples).toString())
    }
}
