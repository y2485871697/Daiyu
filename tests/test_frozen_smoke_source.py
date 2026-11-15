"""Frozen APK smoke keeps source, workflow and validation-tool identities distinct."""
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import verify_smoke_source as source
spec = importlib.util.spec_from_file_location('frozen_tool_recorder', ROOT / 'scripts/acceptance/record_tool_tests.py')
recorder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recorder)


class FrozenSmokeSourceTests(unittest.TestCase):
    def fixture(self, variant):
        env = dict(SOURCE_SHA='a' * 40, SOURCE_RUN_ID='123', SOURCE_VARIANT=variant,
                   GITHUB_SHA='b' * 40, SMOKE_MODE='smoke', SMOKE_TEST_CLASSES='explicit.Test')
        record = dict(source_sha='a' * 40, run_id='123', attempt=1,
                      application_id='app.daiyu.android', test_application_id='app.daiyu.android.test',
                      variant='release' if variant == 'compile' else 'release-diagnostic',
                      debuggable=False, production_delivery=variant == 'compile',
                      profileable=variant == 'diagnostic', jank_diagnostics=variant == 'diagnostic',
                      validation_scope='compile-only', full_regression_passed=False,
                      unit_tests_run=False, instrumentation_run=False)
        run = dict(id=123, run_attempt=1, head_sha='a' * 40, head_branch='main',
                   workflow_id=397560064, status='completed', conclusion='success')
        return env, record, run, [dict(name='增量Release编译（不运行全量）', conclusion='success')]

    def test_frozen_checkout_is_explicit_and_old_call_remains_strict(self):
        for variant in ('compile', 'diagnostic'):
            env, record, run, jobs = self.fixture(variant)
            source.verify(env, record, run, jobs, [], checkout_sha='a' * 40)
            self.assertEqual('b' * 40, env['GITHUB_SHA'])
            with self.assertRaises(ValueError):
                source.verify(env, record, run, jobs, [])
            with self.assertRaises(ValueError):
                source.verify(dict(env, GITHUB_SHA='a' * 40), record, run, jobs, [], checkout_sha='c' * 40)

    def test_frozen_checkout_does_not_expand_application_allowlist(self):
        env, record, run, jobs = self.fixture('compile')
        for path in ('app/src/main/kotlin/Changed.kt', 'app/src/androidTest/kotlin/Changed.kt',
                     'app/build.gradle.kts', '.github/workflows/build.yml', 'tests/../app/file'):
            with self.subTest(path=path), self.assertRaises(ValueError):
                source.verify(env, record, run, jobs, [path], checkout_sha='a' * 40)

    def test_checkout_requires_complete_expected_sha_before_git(self):
        for value in ('', 'a' * 8, 'x' * 40):
            with patch.object(source.subprocess, 'check_output') as read, self.assertRaises(ValueError):
                source.verify_checkout(dict(EXPECTED_SHA=value, GITHUB_SHA='b' * 40))
            read.assert_not_called()

    def test_checkout_checks_actual_head_tool_head_and_ancestry(self):
        env = dict(EXPECTED_SHA='a' * 40, GITHUB_SHA='b' * 40)
        with patch.object(source.subprocess, 'check_output', side_effect=['a' * 40 + '\n', 'b' * 40 + '\n']), \
                patch.object(source.subprocess, 'run') as run:
            self.assertEqual('a' * 40, source.verify_checkout(env))
            run.assert_called_once_with(['git', 'merge-base', '--is-ancestor', 'a' * 40, 'b' * 40], check=True)
        for values in (['c' * 40 + '\n'], ['a' * 40 + '\n', 'c' * 40 + '\n']):
            with patch.object(source.subprocess, 'check_output', side_effect=values), \
                    patch.object(source.subprocess, 'run'), self.assertRaises(ValueError):
                source.verify_checkout(env)
        with patch.object(source.subprocess, 'check_output', return_value='a' * 40 + '\n'), \
                patch.object(source.subprocess, 'run', side_effect=subprocess.CalledProcessError(1, 'git')), \
                self.assertRaises(subprocess.CalledProcessError):
            source.verify_checkout(env)

    def test_recorder_binds_frozen_cwd_and_preserves_workflow_identity(self):
        env = dict(GITHUB_SHA='b' * 40, GITHUB_RUN_ID='43', GITHUB_RUN_ATTEMPT='1',
                   EXPECTED_SHA='a' * 40, SMOKE_MODE='smoke')
        with tempfile.TemporaryDirectory() as directory:
            old = os.getcwd()
            try:
                os.chdir(directory)
                console = SimpleNamespace(buffer=io.BytesIO())
                with patch.dict(os.environ, env, clear=True), \
                        patch.object(recorder.subprocess, 'check_output', return_value='a' * 40 + '\n'), \
                        patch.object(recorder.subprocess, 'call', return_value=7), \
                        patch.object(recorder.sys, 'stdout', console):
                    self.assertEqual(7, recorder.record('python3'))
                result = json.loads(Path('tool-evidence/receipt.json').read_text())
                self.assertEqual(dict(sha='a' * 40, run_id=43, attempt=1), result['identity'])
                self.assertEqual('b' * 40, result['workflow_sha'])
                self.assertEqual('b' * 40, result['validation_tools_sha'])
                self.assertEqual(7, result['exit_code'])
            finally:
                os.chdir(old)

    def test_workflow_keeps_frozen_source_and_tool_checkouts_separate(self):
        smoke = (ROOT / '.github/workflows/build.yml').read_text().split('  smoke:\n', 1)[1]
        self.assertIn("ref: ${{ inputs.mode == 'smoke' && inputs.expected_sha || github.sha }}", smoke)
        self.assertIn('ref: ${{ github.sha }}\n          path: .smoke-validation-tools', smoke)
        self.assertIn('[[ "$EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]]', smoke)
        self.assertIn('python3 .smoke-validation-tools/scripts/verify_smoke_source.py --verify-checkout-only', smoke)
        self.assertIn('python3 .smoke-validation-tools/scripts/acceptance/record_tool_tests.py --python python3', smoke)
        self.assertIn('run: python3 .smoke-validation-tools/scripts/verify_smoke_source.py', smoke)
        self.assertIn('smoke_script=scripts/smoke_boot.sh; if test "$SMOKE_MODE" = smoke && test "$SMOKE_TEST_CLASSES" = "app.daiyu.android.SearchHistoryDialogTest,app.daiyu.android.SearchHostActivityTest,app.daiyu.android.RetainedSearchDataTest,app.daiyu.android.SearchSQLiteCancellationTest"; then smoke_script=.smoke-validation-tools/scripts/smoke_boot.sh; fi', smoke)
        self.assertIn('test -s smoke/prelaunch-ready.txt && bash "$smoke_script"', smoke)
        self.assertIn('code=$?; printf "%s\\n" "$code" > smoke/exit-code.txt; exit "$code"', smoke)
        self.assertNotIn('GITHUB_SHA=', smoke)
        self.assertNotIn('assemble', smoke)
