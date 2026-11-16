import importlib.util
import itertools
import io
import json
import os
from types import SimpleNamespace
import tempfile
from unittest.mock import patch
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/kotlin/app/daiyu/android'
SPEC = importlib.util.spec_from_file_location('browser_tool_recorder', ROOT / 'scripts/acceptance/record_tool_tests.py')
recorder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(recorder)


class BrowserNavigationDefaultsContractTest(unittest.TestCase):
    def test_actual_ordinary_leaf_carries_navigation_defaults_through_validation_effect_and_execution(self):
        source = (MAIN / 'integration/tools/RunOrdinaryBrowserTools.kt').read_text()
        for expression in (
            'RunBrowserTools.definition(write, permission.actions, navigationDefaults = true)',
            'RunBrowserTools.validate(arguments.canonicalJson(), write, navigationDefaults = true)',
            'RunBrowserTools.writeEffect(grant, arguments.canonicalJson(), navigationDefaults = true)',
            'RunBrowserTools.executeBound(host.session, invocation, host.screenshotStore, write, navigationDefaults = true)',
        ):
            self.assertIn(expression, source)
        self.assertIn('"navigate" in permission.actions', source)
        admission = (MAIN / 'browser/BrowserRunPreparation.kt').read_text()
        self.assertIn('navigationDefaults = preview.defaultPermission != null', admission)
        self.assertIn('action != "new_tab" || !json.has("url") || "navigate" in preview.defaultPermission?.actions.orEmpty()', admission)

    def test_strict_target_contract_stays_default_and_session_preflights_before_allocation(self):
        facade = (MAIN / 'browser/host/BrowserToolSession.kt').read_text()
        self.assertIn('navigationDefaults: Boolean = false', facade)
        self.assertIn('check(slots.isEmpty()) { "BROWSER_TAB_BUSY" }', facade)
        self.assertLess(facade.index('validateNavigation(initial)'), facade.index('val (selected, allocation)'))
        self.assertIn('selected ?: createTab(checkNotNull(allocation))', facade)
        catalog = (MAIN / 'integration/tools/RunBrowserTools.kt').read_text()
        self.assertIn('confirmation?.token?.preview?.defaultPermission != null', catalog)
        self.assertIn('!(navigationDefaults && action == "navigate")', catalog)
        self.assertIn('session.execute(command.parameters, command.navigationDefaults)', catalog)
        self.assertIn("package app.daiyu.android.integration.tools", catalog)
        self.assertIn("package app.daiyu.android.browser.host", facade)

    def test_main_and_child_tab_limits_stay_separate(self):
        registry = (MAIN / 'browser/session/BrowserSessionRegistry.kt').read_text()
        self.assertIn('if (owner.kind == BrowserOwnerKind.CHILD) 0..0 else 0..2', registry)
        self.assertIn('each child task has one independent temporary tab',
                      (MAIN / 'integration/tools/RunBrowserTools.kt').read_text())

    def test_browser_platform_selection_is_exact_and_unrelated_filters_keep_full_fallback(self):
        classes = recorder.BROWSER_PLATFORM_CLASSES
        self.assertEqual(len(classes), 4)
        for selected in itertools.permutations(classes):
            command, scope = recorder.command_for('python3', {
                'SMOKE_MODE': 'smoke', 'SMOKE_TEST_CLASSES': ','.join(selected)})
            self.assertEqual(scope, 'browser-targeted')
            self.assertEqual(command, ['python3', '-B', '-m', 'unittest', *recorder.BROWSER_MODULES, '-v'])
        for selected in (classes[:-1], (*classes, classes[0]), (*classes, 'app.daiyu.android.OtherTest')):
            self.assertEqual('full', recorder.command_for('python3', {
                'SMOKE_MODE': 'smoke', 'SMOKE_TEST_CLASSES': ','.join(selected)})[1])
        for mode in ('compile', 'checks', 'daily', 'release', ''):
            self.assertEqual('full', recorder.command_for('python3', {
                'SMOKE_MODE': mode, 'SMOKE_TEST_CLASSES': ','.join(classes)})[1])

    def test_browser_frozen_tools_preserve_both_revisions_and_actual_failure(self):
        old = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                env = dict(GITHUB_SHA='b' * 40, EXPECTED_SHA='a' * 40, GITHUB_RUN_ID='42',
                           GITHUB_RUN_ATTEMPT='1', SMOKE_MODE='smoke',
                           SMOKE_TEST_CLASSES=','.join(recorder.BROWSER_PLATFORM_CLASSES))
                with patch.dict(os.environ, env, clear=True), \
                        patch.object(recorder.subprocess, 'check_output', return_value='a' * 40 + '\n'), \
                        patch.object(recorder.subprocess, 'call', return_value=9) as call, \
                        patch.object(recorder.sys, 'stdout', SimpleNamespace(buffer=io.BytesIO())):
                    self.assertEqual(9, recorder.record('python3'))
                receipt = json.loads(Path('tool-evidence/receipt.json').read_text())
                self.assertEqual(9, receipt['exit_code'])
                self.assertEqual('a' * 40, receipt['identity']['sha'])
                self.assertEqual('b' * 40, receipt['validation_tools_sha'])
                self.assertEqual('browser-targeted', receipt['scope'])
                self.assertEqual(ROOT, call.call_args.kwargs['cwd'])
                self.assertEqual(str(ROOT / 'tests'), call.call_args.kwargs['env']['PYTHONPATH'].split(os.pathsep)[0])
            finally:
                os.chdir(old)

    def test_cloud_browser_workflow_checks_without_creating_another_application_package(self):
        workflow = (ROOT / '.github/workflows/browser-checks.yml').read_text()
        self.assertIn('ref: ${{ inputs.expected_sha }}', workflow)
        self.assertIn('checks_only.init.gradle', workflow)
        self.assertIn(':app:testDebugUnitTest', workflow)
        self.assertIn('app.daiyu.android.integration.tools.*Browser*', workflow)
        self.assertIn("'app.daiyu.android.integration.tools.RunOrdinaryBrowserPreparationTest'", workflow)
        self.assertNotIn(':app:assemble', workflow)
        self.assertIn('source_sha=os.environ', workflow)
        self.assertIn('apk_built=False', workflow)


if __name__ == '__main__':
    unittest.main()
