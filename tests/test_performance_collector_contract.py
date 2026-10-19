"""Source wiring contracts; device collection still requires the isolated Release APK."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
PERFORMANCE = ROOT / 'app/src/performanceTest/kotlin/app/daiyu/android'


class MemoryCollectorContract(unittest.TestCase):
    def test_full_scale_uses_real_selection_and_separate_memory_report(self):
        source = (PERFORMANCE / 'ReleaseSyntheticPerformanceTest.kt').read_text()
        memory = source.split('stage = "conversation_switch_memory"', 1)[1].split('stage = "backend_migration"', 1)[0]
        self.assertIn('repeat(100)', memory)
        self.assertIn('main { launched.select(selected) }', memory)
        self.assertLess(memory.index('ui.waitForIdle()'), memory.index('memory.sample("switch_completed"'))
        self.assertIn('preferences.getString("selected", null)', memory)
        self.assertIn('repeat(10)', memory)
        self.assertIn('Thread.sleep(1_000)', memory)
        self.assertIn('memory.write(report, id, diagnosticsMode)', memory)
        self.assertIn('"memory_report", memoryReport?.name ?: JSONObject.NULL', source)

    def test_diagnostics_is_injected_and_read_back_not_just_labeled(self):
        source = (PERFORMANCE / 'ReleaseSyntheticPerformanceTest.kt').read_text()
        self.assertIn('diagnosticsMode in setOf("off", "on")', source)
        self.assertIn('diagnosticsEnabled = diagnosticsMode == "on"', source)
        self.assertIn('diagnostics.setEnabled(diagnosticsEnabled)', source)
        self.assertIn('taskSurfaceStore = surfaces, diagnostics = diagnostics', source)
        self.assertIn('diagnosticState.enabled == (diagnosticsMode == "on")', source)
        self.assertIn('!diagnosticState.unavailable', source)

    def test_partial_failure_preserves_samples_without_completeness_claim(self):
        source = (PERFORMANCE / 'ReleaseSyntheticPerformanceTest.kt').read_text()
        self.assertIn('completed = false, failureStage = stage', source)
        self.assertIn('"memory_switch_samples", memoryCollector?.switchSamples ?: 0', source)
        self.assertIn('"memory_post_gc_samples", memoryCollector?.postGcSamples ?: 0', source)
        memory = (PERFORMANCE / 'SyntheticMemoryCollector.kt').read_text()
        self.assertIn('if (completed)', memory)
        self.assertIn('.put("collection_completed", completed)', memory)

    def test_gc_and_diagnostic_evidence_record_executed_state(self):
        source = (PERFORMANCE / 'ReleaseSyntheticPerformanceTest.kt').read_text()
        self.assertIn('System.gc(); memory.markGcRequested(); System.runFinalization()', source)
        self.assertIn('var diagnosticsStateVerified = false', source)
        self.assertLess(source.index('check(diagnosticState.loaded'), source.index('diagnosticsStateVerified = true'))
        self.assertNotIn('.put("diagnostics_state_verified", true)', source)
        self.assertIn('.put("diagnostics_state_verified", diagnosticsStateVerified)', source)
        memory = (PERFORMANCE / 'SyntheticMemoryCollector.kt').read_text()
        self.assertIn('.put("gc_requested", gcRequested)', memory)
        self.assertNotIn('postGcSamples > 0', memory)

    def test_memory_is_raw_observation_not_acceptance_or_peak_claim(self):
        source = (PERFORMANCE / 'SyntheticMemoryCollector.kt').read_text()
        for field in ('total_pss_kib', 'total_private_dirty_kib', 'java_heap_used_bytes',
                      'java_heap_committed_bytes', 'java_heap_limit_bytes', 'native_heap_allocated_bytes'):
            self.assertIn('"' + field + '"', source)
        for field in ('stable_recovery_verified', 'peak_memory_measured', 'business_acceptance', 'collection_authenticated'):
            self.assertIn('.put("' + field + '", false)', source)
        self.assertIn('== (1..100).toList()', source)
        self.assertIn('== 10', source)


class ControlledWorkloadContract(unittest.TestCase):
    def test_controlled_transport_has_no_network_fallback_and_is_full_scale_only(self):
        source = (PERFORMANCE / 'ReleaseSyntheticPerformanceTest.kt').read_text()
        self.assertIn('if (fullScale) ControlledPerformanceModel() else null', source)
        self.assertIn('controlledModel?.port ?: error("external_model_request_forbidden")', source)
        call = source.index('collectControlledWorkloads(launched, activeFixture.controller')
        self.assertIn('if (fullScale)', source[call - 120:call])
        model = (PERFORMANCE / 'ControlledPerformanceModel.kt').read_text()
        self.assertIn('checkNotNull(queued.pollFirst())', model)
        self.assertIn('ModelEvent.TextDelta("body"', model)
        self.assertIn('finally { drained.countDown() }', model)
        for forbidden in ('OkHttp', 'HttpURLConnection', 'ModelFactory'):
            self.assertNotIn(forbidden, model)

    def test_input_connection_never_falls_back_to_semantics(self):
        source = (PERFORMANCE / 'ReleaseSyntheticPerformanceTest.kt').read_text()
        start = source.index('val connection = main')
        end = source.index('captured.scenario("synthetic_stop_upper_bound")')
        window = source[start:end]
        self.assertIn('editor.onCheckIsTextEditor()', window)
        self.assertIn('editor.onCreateInputConnection(', window)
        self.assertIn('connection.commitText("字", 1)', window)
        self.assertIn('connection.closeConnection()', window)
        self.assertIn('TimeUnit.SECONDS.toNanos(30)', window)
        self.assertIn('"字".repeat(commits)', window)
        self.assertNotIn('performTextReplacement', window[:window.index('finally')])
        self.assertIn('.put("ime_service_round_trip_measured", false)', window)

    def test_stop_keeps_owner_persistence_and_upper_bound_endpoint(self):
        source = (PERFORMANCE / 'ReleaseSyntheticPerformanceTest.kt').read_text()
        stop = source.split('captured.scenario("synthetic_stop_upper_bound")', 1)[1].split('private fun collect(', 1)[0]
        self.assertIn('repeat(20)', stop)
        self.assertLess(stop.index('requestNs = System.nanoTime()'), stop.index('controller.stop(owner)'))
        self.assertIn('session.drained.await', stop)
        self.assertIn('saved.id == owner.id && saved.runId == owner.runId && saved.status == RunStatus.STOPPED', stop)
        self.assertIn('.put("latency_kind", "instrumented_upper_bound")', stop)
        self.assertIn('.put("first_visible_feedback_measured", false)', stop)
        self.assertNotIn('.put("present_latency", true)', source)

    def test_windows_use_vsync_not_callback_labels_and_alias_cleanup_is_scoped(self):
        source = (PERFORMANCE / 'SyntheticFrameCollector.kt').read_text()
        counter = source.split('fun framesBetween(', 1)[1].split('fun scenario(', 1)[0]
        self.assertIn('"intended_vsync_timestamp_ns"', counter)
        self.assertNotIn('.label', counter)
        fixture = (PERFORMANCE / 'ReleaseSyntheticPerformanceTest.kt').read_text()
        self.assertIn('it == keyAlias || it.startsWith("$keyAlias.")', fixture)
        self.assertIn('cleanup { controlledModel?.close() }', fixture)
        self.assertLess(fixture.index('cleanup { fixture?.stopController() }'), fixture.index('cleanup { controlledModel?.close() }'))
        self.assertIn('window.put("frame_count_by_intended_vsync", count)', fixture)


if __name__ == '__main__':
    unittest.main()
