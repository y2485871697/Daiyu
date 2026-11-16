from pathlib import Path
import importlib.util
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/kotlin/app/daiyu/android'
SPEC = importlib.util.spec_from_file_location('browser_pause_recorder', ROOT / 'scripts/acceptance/record_tool_tests.py')
recorder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(recorder)


class BrowserPauseLifecycleContractTest(unittest.TestCase):
    def test_opening_task_projection_never_acquires_control(self):
        source = (MAIN / 'browser/ui/BrowserUiPool.kt').read_text()
        opening = source.split('fun ensureTabForUI()', 1)[1].split('fun newTabFromUI()', 1)[0]
        self.assertIn('if (visit.taskOwned) { refresh(); return }', opening)
        self.assertLess(opening.index('if (visit.taskOwned)'), opening.index('takeControl()'))
        self.assertNotIn('visit.newTab()', opening)
        self.assertNotIn('visit::takeover', opening)
        explicit = source.split('fun takeControl()', 1)[1].split('private fun download(', 1)[0]
        self.assertIn('state.waiting', explicit)
        self.assertIn('visit.newTab()', explicit)
        self.assertIn('visit::takeover', explicit)

    def test_leaving_returns_original_token_and_only_resumes_its_browser_pause(self):
        source = (MAIN / 'ControllerBrowserVisual.kt').read_text()
        leave = source.split('private fun leave(visit: Visit)', 1)[1].split('fun revoke()', 1)[0]
        for required in ('visit.active.getAndSet(false)', 'pending.compareAndSet(it, null)',
                         'currentVisit.compareAndSet(visit, null)', 'token !== held',
                         'unknown || revoked.get() || !ownerCurrent()',
                         'binding.session.releaseTakeover(held)'):
            self.assertIn(required, leave)
        self.assertLess(leave.index('original?.let(::detach)'), leave.index('binding.session.releaseTakeover(held)'))
        self.assertLess(leave.index('binding.session.releaseTakeover(held)'), leave.index('token = null'))
        self.assertIn('resumeBrowserPause()', leave)
        resume = source.split('private fun resumeBrowserPause()', 1)[1].split('private fun closeTab(', 1)[0]
        self.assertIn('pauseEpoch.also { pauseEpoch = null } ?: return', resume)
        self.assertIn('resumeOriginal(epoch)', resume)
        for forbidden in ('newTab(', '.navigate(', 'session.execute(', 'unknown = false'):
            self.assertNotIn(forbidden, leave)

    def test_failure_diagnostics_keep_unknown_and_never_record_user_values(self):
        source = (MAIN / 'browser/host/BrowserToolSession.kt').read_text()
        self.assertIn('BrowserOutcome.Unknown', source)
        self.assertIn('"session:$stage:$kind"', source)
        self.assertNotIn('Log.w("DaiyuBrowser", e.', source)
        self.assertNotIn('Log.w("DaiyuBrowser", "session:$stage:$kind",', source)

    def test_lifecycle_selection_is_exact_without_changing_existing_browser_scope(self):
        classes = recorder.BROWSER_LIFECYCLE_PLATFORM_CLASSES
        self.assertEqual(6, len(classes))
        self.assertEqual(4, len(recorder.BROWSER_PLATFORM_CLASSES))
        self.assertIn('app.daiyu.android.ControllerBrowserVisualTest', classes)
        self.assertIn('app.daiyu.android.BrowserVisualLayoutTest', classes)
        for selected in (classes, tuple(reversed(classes))):
            command, scope = recorder.command_for('python3', {
                'SMOKE_MODE': 'smoke', 'SMOKE_TEST_CLASSES': ','.join(selected)})
            self.assertEqual('browser-targeted', scope)
            self.assertIn('test_browser_pause_lifecycle_contract', command)
        for selected in (classes[:-1], (*classes, classes[0]), (*classes, 'app.daiyu.android.OtherTest')):
            self.assertEqual('full', recorder.command_for('python3', {
                'SMOKE_MODE': 'smoke', 'SMOKE_TEST_CLASSES': ','.join(selected)})[1])
        self.assertEqual('full', recorder.command_for('python3', {
            'SMOKE_MODE': 'compile', 'SMOKE_TEST_CLASSES': ','.join(classes)})[1])

    def test_regression_uses_actual_ordinary_scope_and_distinct_run_identity(self):
        tests = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/browser/CatalogBrowserBindingAndroidTest.kt').read_text()
        regression = tests.split('fun testOrdinaryEmptyInspectionCloseThenFreshRunNavigatesWithoutOpeningPanel()', 1)[1].split('private fun owner(', 1)[0]
        for required in ('RunOrdinaryBrowserTools(context, executionOwner', 'confirmed.preview.grant',
                         'CatalogBrowserBinding.attach(host, assistant, confirmed)', 'dispatchOwned(authorization',
                         'leaf.observeClose', 'run("old")', 'run("fresh")',
                         'pool.ensureTabForUI()', 'assertEquals(1, requests.get())'):
            self.assertIn(required, regression)
        self.assertNotIn('call_00_', regression)


    def test_lifecycle_workflow_has_an_independent_queue(self):
        workflow = (ROOT / '.github/workflows/build.yml').read_text()
        concurrency = workflow.split('\nconcurrency:\n', 1)[1].split('\njobs:\n', 1)[0]
        selector = ','.join(recorder.BROWSER_LIFECYCLE_PLATFORM_CLASSES)
        condition = "inputs.mode == 'smoke' && inputs.smoke_classes == '" + selector + "' && '-browser-lifecycle'"
        self.assertIn(condition, concurrency)
        self.assertIn('cancel-in-progress: false', concurrency)
        self.assertEqual(1, concurrency.count("'-browser-lifecycle'"))


if __name__ == '__main__':
    unittest.main()
