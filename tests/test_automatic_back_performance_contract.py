"""Ordinary-build, back-only entry wiring. Runtime semantics live in focused JVM tests."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/kotlin/app/daiyu/android'


class AutomaticBackPerformanceContractTest(unittest.TestCase):
    def test_actual_claim_predictive_and_accepted_commit_entries(self):
        nav = (APP / 'ui/navigation/PageBackGestures.kt').read_text()
        claim = nav.index('claimed = true')
        start = nav.index('state.startDiagnostic(BackPerformanceRecorder.Kind.CUSTOM_SWIPE)', claim)
        self.assertLess(start, nav.index('claimSwipe()', claim))
        self.assertLess(start, nav.index('state.update(owner, distance / gestureWidth', claim))
        predictive = nav.split('override fun handleOnBackStarted', 1)[1].split('override fun handleOnBackProgressed', 1)[0]
        self.assertLess(predictive.index('if (!activeState.alive'), predictive.index('activeState.startDiagnostic'))
        self.assertLess(predictive.index('activeState.startDiagnostic'), predictive.index('activeState.update'))
        commit = nav.split('fun commitBack(back:', 1)[1].split('private class SwipeCommit', 1)[0]
        self.assertLess(commit.index('if (!alive'), commit.index('Kind.BACK_PRESS'))
        self.assertLess(commit.index('BackPerformance.commit(diagnosticOwner)'), commit.index('synchronousTrace'))
        self.assertIn('remember(diagnosticFrameHost) { diagnosticFrameHost?.backOwner() ?: Any() }', nav)
        self.assertIn('BackPerformance.recorder.route(diagnosticOwner)', nav)
        self.assertIn('diagnosticToken == BackPerformance.recorder.token()', nav)
        self.assertIn('finally {\n                    handleOnBackCancelled()', nav)

    def test_ordinary_release_uses_one_existing_worker_and_only_active_back_frames(self):
        window = (APP / 'diagnostics/WindowFramePerformance.kt').read_text()
        self.assertEqual(1, window.count('addOnFrameMetricsAvailableListener(session.listener, target)'))
        self.assertEqual(1, window.count('HandlerThread("daiyu-frame-metrics")'))
        self.assertIn('private var enabled = true', window)
        self.assertIn('host.enabled = true', window)
        self.assertIn('kind == "main" && BackPerformance.recorder.observing(this@WindowFramePerformance)', window)
        self.assertIn('if (epoch == null && !diagnostic && !automaticBack) return', window)
        self.assertIn('if (epoch != null) batch.add(epoch, page, frame)', window)
        self.assertIn('if (diagnostic) incident.add(page, frame)', window)
        self.assertIn('host::detach', window)
        for reason in ('PAUSED', 'DESTROYED'):
            self.assertIn(f'endHost(host, BackPerformanceRecorder.End.{reason})', window)
        self.assertIn('removeOnFrameMetricsAvailableListener(session.listener)', window)
        self.assertIn('postDelayed({ work() }, delay)', window)
        recorder = (APP / 'diagnostics/BackPerformanceRecorder.kt').read_text()
        self.assertNotRegex(recorder, r'BuildConfig|JANK_DIAGNOSTICS|Choreographer|Thread\(|JSONObject|File\(')
        self.assertIn('vsync > window.deadline', recorder)
        self.assertIn('val overlapsStart = vsync < window.start', recorder)
        self.assertIn('duration <= window.start - vsync', recorder)
        self.assertIn('inFlightMaxObserved >= SLOW_PHASE_NS', recorder)
        self.assertIn('arrayOfNulls<Sample>(8)', recorder)
        self.assertIn('Array(Phase.entries.size)', recorder)
        self.assertIn('MAX_WORST = 4', recorder)
        self.assertIn('HARD_NS = 4_000_000_000L', recorder)
        self.assertIn('if (!window.commit)', recorder)
        self.assertIn('window.slow == 0 && window.overlapSlow == 0', recorder)

    def test_typed_back_channel_serializes_only_on_existing_bounded_writer(self):
        runtime = (APP / 'diagnostics/RuntimeDiagnostics.kt').read_text()
        entry = runtime.split('internal fun backIncident(', 1)[1].split('private val active', 1)[0]
        self.assertIn('incident: BackPerformanceRecorder.Incident', entry)
        self.assertNotIn('enabled', entry)
        self.assertNotRegex(entry, r'Stage\b|code: Code|heap|stackTrace|failure|message|revision|thread_id')
        self.assertLess(entry.index('executor.execute'), entry.index('JSONObject()'))
        self.assertLess(entry.index('executor.execute'), entry.index('lock.withLock'))
        self.assertGreaterEqual(entry.count('incident.epoch != backGeneration.get()'), 3)
        self.assertIn('ArrayBlockingQueue(128)', runtime)
        self.assertIn('require(bytes.size <= 16 * 1024)', runtime)
        self.assertIn('rows.size > 256', runtime)
        self.assertIn('FILE_BYTES = 512 * 1024', runtime)
        self.assertIn('backGeneration.incrementAndGet()', runtime.split('fun clear()', 1)[1])
        toggle = runtime.split('@Volatile var enabled = false', 1)[1].split('private var reserve', 1)[0]
        self.assertNotIn('backGeneration', toggle)
        export = runtime.split('fun export(): JSONArray {', 1)[1].split('private fun exportLocked', 1)[0]
        self.assertLess(export.index('awaitWrites()'), export.index('exportLocked()'))
        self.assertNotIn('withLock', export)

    def test_io_source_token_trace_semantics_and_privacy_whitelist(self):
        main = (APP / 'MainActivity.kt').read_text()
        setter = main.split('private var retainedPage: String', 1)[1].split('private ', 1)[0]
        self.assertIn('synchronousTrace("daiyu.back.route-change")', setter)
        self.assertNotIn('Trace.beginSection', setter)
        reload = main.split('private fun reloadConfirmedModelLabel(', 1)[1].split('private fun refreshAutomaticCompression', 1)[0]
        self.assertLess(reload.index('captureBackPerformanceToken()'), reload.index('lifecycleScope.launch'))
        for phase in ('commit', 'publish'):
            self.assertIn(f'synchronousTrace("daiyu.chat.model-refresh-{phase}", diagnosticToken)', reload)
        helper = (APP / 'diagnostics/SynchronousTrace.kt').read_text()
        self.assertEqual(1, helper.count('block()'))
        self.assertIn('sourceToken: BackPerformanceRecorder.Token?', helper)
        self.assertIn('catch (_: Throwable)', helper)
        recorder = (APP / 'diagnostics/BackPerformanceRecorder.kt').read_text()
        names = re.findall(r'\("(daiyu\.[^"]+)"\)', recorder)
        self.assertEqual(17, len(names))
        self.assertEqual({
            'daiyu.back.custom-up-dispatch', 'daiyu.back.callback-commit', 'daiyu.back.prepare',
            'daiyu.back.business-handler', 'daiyu.back.reset', 'daiyu.back.route-change',
            'daiyu.retained.lifecycle-set', 'daiyu.retained.lifecycle-close',
            'daiyu.chat.model-refresh-commit', 'daiyu.chat.model-refresh-publish',
            'daiyu.chat.position-seed', 'daiyu.chat.restore-lifecycle',
            'daiyu.chat.restore-clearance', 'daiyu.chat.retained-compose-entry',
            'daiyu.chat.refresh', 'daiyu.chat.publish', 'daiyu.chat.action-revision',
        }, set(names))
        self.assertIn('INCLUSIVE', recorder)
        nav = (APP / 'ui/navigation/PageBackGestures.kt').read_text()
        self.assertIn('synchronousTrace("daiyu.chat.retained-compose-entry") {}', nav)
        self.assertNotIn('try { retainedRootContent', nav)

    def test_incremental_jvm_android_and_workflow_are_selected_without_apk_jobs(self):
        flow = (ROOT / '.github/workflows/screen-observation-jank-checks.yml').read_text()
        for name in ('test_automatic_back_performance_contract.py', 'test_jank_diagnostics_contract.py'):
            page_scope = flow.split("if os.environ['SCREEN_JANK_CHECK_SCOPE'] == 'page-back':", 1)[1].split('if os.environ', 1)[0]
            self.assertIn(name, page_scope)
        for name in ('BackPerformanceRecorderTest', 'BackPerformanceRuntimeTest'):
            self.assertGreaterEqual(flow.count(name), 2)
        android = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/navigation/PageBackGesturesActivityTest.kt').read_text()
        self.assertIn('fun testOrdinaryBuildAutomaticallyRecordsClaimedBackWithBroadDiagnosticsOff()', android)
        self.assertIn('assertFalse(app.daiyu.android.BuildConfig.JANK_DIAGNOSTICS)', android)
        self.assertIn('runtime.enabled = false', android)
        self.assertIn('Deterministic synthetic sample', android)
        self.assertIn('checks_only.init.gradle', flow)
        self.assertIn('assert not packages, packages', flow)


if __name__ == '__main__':
    unittest.main()
