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


class PersistentColdStartContract(unittest.TestCase):
    def test_cold_start_uses_default_controller_and_external_monotonic_start(self):
        source = (PERFORMANCE / 'PersistentColdStartPerformanceTest.kt').read_text()
        sample = source.split('fun testCollectOneColdStart()', 1)[1]
        for fragment in ('daiyuColdStartElapsedNs', 'SystemClock.elapsedRealtimeNanos()',
                         'android.os.Process.getStartElapsedRealtime()', 'fresh_process_required',
                         'awaitReady(app.controller)', 'includes_instrumentation_startup',
                         'hasSetTextAction()', 'nextElapsedDraw(', 'verify(app, owner)'):
            self.assertIn(fragment, sample)
        self.assertLess(sample.index('val inputReady ='), sample.index('verify(app, owner)'))
        self.assertNotIn('application.controller =', source)
        self.assertNotIn('PerformanceFixture(', source)
        self.assertNotIn('recreate()', source)
        self.assertNotIn('deleteDatabase', source)
        self.assertNotIn('clear()', source)

    def test_preparation_requires_fresh_owned_synthetic_data_and_preserves_dataset(self):
        source = (PERFORMANCE / 'PersistentColdStartPerformanceTest.kt').read_text()
        for fragment in ('existing_synthetic_data_retained', 'existing_cold_start_owner_retained',
                         'existing_synthetic_selection_retained', 'daiyuColdStartOptIn',
                         'FullPerformanceDataset.buildPackage(', 'FullPerformanceDataset.snapshot(',
                         'AtomicFile(marker(app))', 'atomic.finishWrite(stream)',
                         '"state", "preparing"', '"state", "prepared"',
                         '"dataset_sha256"', '"production_delivery", false'):
            self.assertIn(fragment, source)
        self.assertIn('check(!output.exists())', source)

    def test_owner_recovery_and_sample_publication_are_paired(self):
        source = (PERFORMANCE / 'PersistentColdStartPerformanceTest.kt').read_text()
        self.assertIn('AtomicFile(marker(app)).openRead()', source)
        self.assertNotIn('marker(app).readText()', source)
        self.assertIn('stream.fd.sync()', source)
        self.assertLess(source.index('stream.fd.sync()'), source.index('pending.renameTo(output)'))
        self.assertIn('publishSample(output, sample)', source)
        self.assertNotIn('output.writeText(sample', source)
        self.assertIn('"dataset_deletion_attempted", false', source)
        self.assertIn('"dataset_integrity_verified", completed', source)
        self.assertIn('"prepared_process_start_ns"', source)
        sample = source.split('fun testCollectOneColdStart()', 1)[1]
        self.assertLess(sample.index('ui.onNodeWithTag("chat-turn:p-a-1003")'), sample.index('val inputReady ='))


class LongEntryContract(unittest.TestCase):
    def test_full_scale_long_entry_requires_exact_tail_action_and_20_rounds(self):
        source = (PERFORMANCE / 'ReleaseSyntheticPerformanceTest.kt').read_text()
        entry = source.split('private fun collectLongConversationEntries(', 1)[1].split('private fun collect(fullScale:', 1)[0]
        for fragment in ('repeat(20)', 'ConversationId("p-c-1")', 'ConversationId("p-c-0")',
                         '"chat-turn:p-a-1003"', 'assertDoesNotExist()', 'activity.select(short)',
                         'activity.select(long)', 'hasAnyAncestor(hasTestTag(tail))',
                         'copy.assertIsDisplayed().assertIsEnabled().assertHasClickAction()',
                         '"tail_operable_verified_ns"', '"instrumented_upper_bound"',
                         '.put("present_latency", false)'):
            self.assertIn(fragment, entry)
        self.assertLess(entry.index('val start = System.nanoTime()'), entry.index('activity.select(long)'))
        self.assertLess(entry.index('assertHasClickAction()'), entry.index('val draw = nextDrawNs(activity)'))
        call = source.index('collectLongConversationEntries(launched, activeFixture.database, inputs)')
        self.assertIn('stage = "long_conversation_entry"', source[:call].rsplit('if (fullScale)', 1)[1])
        self.assertLess(source.index('val captured = SyntheticFrameCollector'), call)


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


class SystemImeContract(unittest.TestCase):
    def test_manual_system_ime_is_opt_in_and_preserves_test_inventory(self):
        source = (PERFORMANCE / 'ReleaseSyntheticPerformanceTest.kt').read_text()
        self.assertEqual(source.count('fun test'), 2)
        self.assertIn('systemImeOption in setOf("false", "true")', source)
        self.assertIn('require(!systemImeEnabled || fullScale)', source)
        self.assertIn('daiyuExpectedIme', source)
        call = source.index('collectSystemImeWindow(launched, checkNotNull(expectedIme), captured, inputs)')
        self.assertIn('if (systemImeEnabled)', source[call - 120:call])
        self.assertLess(call, source.index('captured.close()', call))

    def test_real_selected_ime_has_no_injection_or_write_fallback(self):
        source = (PERFORMANCE / 'ReleaseSyntheticPerformanceTest.kt').read_text()
        helper = source.split('private fun collectSystemImeWindow(', 1)[1].split('private fun collect(fullScale:', 1)[0]
        loop = helper.split('while (System.nanoTime() - start', 1)[1].split('} finally', 1)[0]
        self.assertIn('DEFAULT_INPUT_METHOD', helper)
        self.assertIn('WindowInsetsCompat.Type.ime()', helper)
        self.assertIn('TimeUnit.SECONDS.toNanos(35)', loop)
        self.assertIn('changes >= 20 && previous.isNotBlank()', loop)
        for forbidden in ('performTextReplacement', 'commitText', 'onCreateInputConnection', 'controller.send',
                          'putString', 'executeShellCommand', 'sendStatus', 'INJECT_EVENTS'):
            self.assertNotIn(forbidden, loop)
        self.assertIn('text.length <= 4096', loop)
        self.assertIn('"text_utf16_length", text.length', loop)
        self.assertNotIn('.put("text", text)', helper)
        for field in ('polling_overhead_included', 'target_window_only'):
            self.assertIn('.put("' + field + '", true)', helper)
        for field in ('hardware_input_latency', 'ime_commit_latency', 'first_visible_feedback_measured', 'present_latency'):
            self.assertIn('.put("' + field + '", false)', helper)
        self.assertIn('.put("completed", completed)', helper)
        self.assertIn('manual-nihao-v1', helper)
        self.assertIn('.put("system_ime_change_observed", identityChanged)', helper)
        self.assertIn('primaryFailure?.addSuppressed(cleanup) ?: throw cleanup', helper)
        self.assertIn('system_ime_cleanup_not_hidden', helper)
        self.assertIn('.hide(androidx.core.view.WindowInsetsCompat.Type.ime())', helper)


if __name__ == '__main__':
    unittest.main()
