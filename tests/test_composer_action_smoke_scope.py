"""Keep composer action motion checks incremental without changing fallback coverage."""
import io
import itertools
import json
import os
import re
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from test_record_tool_tests_scope import recorder


class ComposerActionScopeTest(unittest.TestCase):
    def test_only_exact_three_class_permutations_use_targeted_contracts(self):
        for classes in itertools.permutations(recorder.COMPOSER_ACTION_PLATFORM_CLASSES):
            command, scope = recorder.command_for('python3', dict(
                SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=','.join(classes)))
            self.assertEqual('composer-action-targeted', scope)
            self.assertEqual(['python3', '-B', '-m', 'unittest', *recorder.COMPOSER_ACTION_MODULES, '-v'], command)
        required = {'test_chat_submission_preview_contract', 'test_composer_surface_color_contract',
                    'test_compression_send_gate_contract', 'test_delegation_batch_and_send_contract',
                    'test_run_controls_contract', 'test_composer_action_smoke_scope',
                    'test_record_tool_tests_scope', 'test_acceptance_tool_receipt',
                    'test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope'}
        self.assertEqual(required, set(recorder.COMPOSER_ACTION_MODULES))
        self.assertEqual(len(required), len(recorder.COMPOSER_ACTION_MODULES))

    def test_near_matches_duplicates_and_other_modes_keep_full_fallback(self):
        target = recorder.COMPOSER_ACTION_PLATFORM_CLASSES
        for classes in (target[:-1], target + (target[0],), target + ('unknown',), ()):
            self.assertEqual('full', recorder.command_for('python3', dict(
                SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=','.join(classes)))[1])
        for mode in ('compile', 'build', 'candidate', 'daily', 'release', ''):
            self.assertEqual('full', recorder.command_for('python3', dict(
                SMOKE_MODE=mode, SMOKE_TEST_CLASSES=','.join(target)))[1])

    def test_motion_lane_suffix_requires_exact_ordered_smoke_classes(self):
        root = Path(recorder.__file__).resolve().parents[2]
        workflow = (root / '.github/workflows/build.yml').read_text(encoding='utf-8')
        suffix = '-composer-action-motion'
        groups = [line.strip() for line in workflow.splitlines()
                  if line.lstrip().startswith('group:') and suffix in line]
        self.assertEqual(1, len(groups))
        classes = ','.join(recorder.COMPOSER_ACTION_PLATFORM_CLASSES)
        expected = ("${{ inputs.mode == 'smoke' && inputs.smoke_classes == '"
                    + classes + "' && '-composer-action-motion' || '' }}")
        expressions = re.findall(r"\$\{\{.*?\}\}", groups[0])
        self.assertEqual([expected], [expr for expr in expressions if suffix in expr])
        self.assertEqual(1, sum(classes in expr for expr in expressions))
        self.assertEqual(1, workflow.count(suffix))
        self.assertIn('  cancel-in-progress: false', workflow)

    def test_frozen_tools_cwd_failure_code_and_source_identity_are_preserved(self):
        old = Path.cwd()
        tools = Path(recorder.__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory() as folder:
            try:
                os.chdir(folder)
                env = dict(GITHUB_SHA='b' * 40, EXPECTED_SHA='a' * 40,
                           GITHUB_RUN_ID='29', GITHUB_RUN_ATTEMPT='2', SMOKE_MODE='smoke',
                           SMOKE_TEST_CLASSES=','.join(recorder.COMPOSER_ACTION_PLATFORM_CLASSES))
                with patch.dict(os.environ, env, clear=True), \
                        patch.object(recorder.subprocess, 'check_output', return_value='a' * 40 + '\n'), \
                        patch.object(recorder.subprocess, 'call', return_value=7) as call, \
                        patch.object(recorder.sys, 'stdout', SimpleNamespace(buffer=io.BytesIO())):
                    self.assertEqual(7, recorder.record('python3'))
                    receipt = json.loads(Path('tool-evidence/receipt.json').read_text())
                self.assertEqual('composer-action-targeted', receipt['scope'])
                self.assertEqual(7, receipt['exit_code'])
                self.assertEqual(dict(sha='a' * 40, run_id=29, attempt=2), receipt['identity'])
                self.assertEqual('b' * 40, receipt['workflow_sha'])
                self.assertEqual('b' * 40, receipt['validation_tools_sha'])
                self.assertEqual(call.call_args.args[0], receipt['command'])
                self.assertEqual(tools, call.call_args.kwargs['cwd'])
                self.assertEqual(str(tools / 'tests'), call.call_args.kwargs['env']['PYTHONPATH'].split(os.pathsep)[0])
            finally:
                os.chdir(old)
