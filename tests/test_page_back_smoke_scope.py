"""Return-only tooling and inventory selection; no emulator or compilation."""
import io
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from test_record_tool_tests_scope import recorder
import test_speech_platform_discovery as discovery


CLASSES = (
    'app.daiyu.android.ui.navigation.PageBackGesturesActivityTest',
    'app.daiyu.android.ui.navigation.PageBackStackActivityTest',
    'app.daiyu.android.ui.navigation.PageEnterActivityTest',
    'app.daiyu.android.ui.navigation.PageRetainedContentActivityTest',
    'app.daiyu.android.ui.screens.chat.conversation.ChatMessageListMeasurePhaseTest',
)


class PageBackSmokeScopeTests(unittest.TestCase):
    def test_exact_return_test_refresh_has_its_own_non_cancelling_queue(self):
        workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/test-refresh-regression.yml').read_text()
        group = next(line for line in workflow.splitlines() if line.startswith('  group: daiyu-test-refresh-'))
        self.assertIn("inputs.smoke_classes == '" + ','.join(CLASSES) + "' && '-page-back' || ''", group)
        self.assertIn('  cancel-in-progress: false', workflow)

    def test_exact_return_build_smoke_has_its_own_non_cancelling_queue(self):
        workflow = (Path(__file__).resolve().parents[1] /
                    '.github/workflows/build.yml').read_text()
        concurrency = workflow.split('\nconcurrency:\n', 1)[1].split('\njobs:\n', 1)[0]
        group = next(line for line in concurrency.splitlines()
                     if line.startswith('  group:'))
        self.assertTrue(group.startswith('  group: daiyu-${{ inputs.mode }}-${{ github.ref }}'))
        suffix = (
            "${{ inputs.mode == 'smoke' && inputs.smoke_classes == '"
            + ','.join(CLASSES) + "' && '-page-back' || '' }}"
        )
        self.assertIn(suffix, group)
        self.assertIn('  cancel-in-progress: false', concurrency.splitlines())

    def test_exact_return_scope_runs_explicit_contracts_and_tool_guards(self):
        self.assertEqual(CLASSES, recorder.PAGE_BACK_PLATFORM_CLASSES)
        for classes in (CLASSES, tuple(reversed(CLASSES))):
            command, scope = recorder.command_for('python3', dict(
                SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=','.join(classes)))
            self.assertEqual('page-back-targeted', scope)
            self.assertEqual(['python3', '-B', '-m', 'unittest', *recorder.PAGE_BACK_MODULES, '-v'], command)
        required = {'test_page_back_gesture_contract', 'test_page_back_repeat_contract',
                    'test_page_enter_contract', 'test_chat_return_refresh_contract',
                    'test_chat_timeline_contract', 'test_reasoning_flow_stability_contract',
                    'test_diagnostic_smoke_source', 'test_record_tool_tests_scope',
                    'test_acceptance_tool_receipt', 'test_page_back_smoke_scope',
                    'test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope'}
        self.assertEqual(required, set(recorder.PAGE_BACK_MODULES))
        self.assertEqual(len(required), len(recorder.PAGE_BACK_MODULES))
        self.assertNotIn('discover', command)

    def test_exact_gesture_retained_pair_keeps_all_return_contracts(self):
        pair = (CLASSES[0], CLASSES[3])
        self.assertEqual(pair, recorder.PAGE_BACK_GESTURE_RETAINED_CLASSES)
        for classes in (pair, tuple(reversed(pair))):
            command, scope = recorder.command_for('python3', dict(
                SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=','.join(classes)))
            self.assertEqual('page-back-targeted', scope)
            self.assertEqual(['python3', '-B', '-m', 'unittest', *recorder.PAGE_BACK_MODULES, '-v'], command)
            self.assertNotIn('discover', command)

    def test_inexact_gesture_retained_pair_and_other_modes_keep_full_fallback(self):
        pair = (CLASSES[0], CLASSES[3])
        raw = ','.join(pair)
        for selected in ('', *pair, raw + ',' + pair[0], raw + ',app.daiyu.android.UnknownTest',
                         raw.replace('PageBackGesturesActivityTest', 'PageBackGesturesActivityTestExtra'),
                         ' ' + raw, raw + ' ', raw.replace(',', ', '), raw + ','):
            self.assertEqual('full', recorder.command_for('python3', dict(
                SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=selected))[1])
        for mode in ('', 'compile', 'candidate', 'daily', 'release'):
            self.assertEqual('full', recorder.command_for('python3', dict(
                SMOKE_MODE=mode, SMOKE_TEST_CLASSES=raw))[1])

    def test_gesture_retained_pair_receipt_keeps_current_tools_and_frozen_identity(self):
        for selected in ((CLASSES[0], CLASSES[3]), CLASSES,
                         (*CLASSES, 'app.daiyu.android.MessageActionsActivityTest')):
            old = Path.cwd()
            try:
                with tempfile.TemporaryDirectory() as directory:
                    os.chdir(directory)
                    env = dict(GITHUB_SHA='b' * 40, EXPECTED_SHA='a' * 40, GITHUB_RUN_ID='14',
                               GITHUB_RUN_ATTEMPT='1', SMOKE_MODE='smoke',
                               SMOKE_TEST_CLASSES=','.join(selected))
                    with patch.dict(os.environ, env, clear=True), patch.object(
                            recorder.subprocess, 'check_output', return_value='a' * 40 + '\n'), patch.object(
                            recorder.subprocess, 'call', return_value=9) as call, patch.object(
                            recorder.sys, 'stdout', SimpleNamespace(buffer=io.BytesIO())):
                        self.assertEqual(9, recorder.record('python3'))
                        receipt = json.loads(Path('tool-evidence/receipt.json').read_text())
                        tools = Path(recorder.__file__).resolve().parents[2]
                        self.assertEqual(tools, call.call_args.kwargs['cwd'])
                        self.assertEqual(str(tools / 'tests'), call.call_args.kwargs['env']['PYTHONPATH'].split(os.pathsep)[0])
                        self.assertEqual(dict(sha='a' * 40, run_id=14, attempt=1), receipt['identity'])
                        self.assertEqual('b' * 40, receipt['workflow_sha'])
                        self.assertEqual('b' * 40, receipt['validation_tools_sha'])
                        self.assertEqual('page-back-targeted', receipt['scope'])
                        self.assertEqual(9, receipt['exit_code'])
                        self.assertEqual(call.call_args.args[0], receipt['command'])
            finally:
                os.chdir(old)

    def test_return_with_message_actions_keeps_exact_targeted_command_and_inventory(self):
        extra = 'app.daiyu.android.MessageActionsActivityTest'
        extended = (*CLASSES, extra)
        for classes in (extended, tuple(reversed(extended))):
            command, scope = recorder.command_for('python3', dict(
                SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=','.join(classes)))
            self.assertEqual('page-back-targeted', scope)
            self.assertEqual(['python3', '-B', '-m', 'unittest', *recorder.PAGE_BACK_MODULES, '-v'], command)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            path = root / 'app/src/androidTest/kotlin' / (extra.replace('.', '/') + '.kt')
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('package app.daiyu.android\nclass MessageActionsActivityTest : TestCase() {\n fun testOne() {}\n}\n')
            selected = []
            for shard in range(4):
                result = helper.select(root, shard, classes=','.join(reversed(extended)))
                self.assertEqual(0, result.returncode, result.stderr)
                selection = json.loads((root / 'smoke/selection.json').read_text())
                inventory = json.loads((root / 'smoke/instrumentation-shards.json').read_text())
                self.assertEqual('targeted', inventory['discovery_scope'])
                self.assertEqual(set(extended), set(inventory['all_classes']))
                selected.extend(selection['selected_classes'])
            self.assertCountEqual(extended, selected)
            for raw in (','.join(extended) + ',' + extra, ','.join(extended) + ',app.daiyu.android.UnknownTest',
                        ','.join(extended[1:])):
                self.assertEqual('full', recorder.command_for('python3', dict(
                    SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=raw))[1])
                result = helper.select(root, classes=raw)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('Ambiguous instrumentation test declaration', result.stderr)

    def test_partial_extended_duplicate_and_other_modes_keep_full_selection(self):
        classes = ','.join(CLASSES)
        for raw in ('', ','.join(name.rsplit('.', 1)[-1] for name in CLASSES),
                    ','.join(CLASSES[:-1]), classes + ',app.daiyu.android.UnknownTest',
                    classes + ',' + CLASSES[0], ' ' + classes):
            self.assertEqual('full', recorder.command_for('python3', dict(
                SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=raw))[1])
        for mode in ('', 'compile', 'candidate', 'daily', 'release'):
            self.assertEqual('full', recorder.command_for('python3', dict(
                SMOKE_MODE=mode, SMOKE_TEST_CLASSES=classes))[1])

    def test_return_receipt_preserves_real_failure_identity_and_command(self):
        old = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                env = dict(GITHUB_SHA='c' * 40, GITHUB_RUN_ID='14', GITHUB_RUN_ATTEMPT='1',
                           SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=','.join(CLASSES))
                with patch.dict(os.environ, env, clear=True), patch.object(
                        recorder.subprocess, 'call', return_value=9) as call, patch.object(
                        recorder.sys, 'stdout', SimpleNamespace(buffer=io.BytesIO())):
                    self.assertEqual(9, recorder.record('python3'))
                    receipt = json.loads(Path('tool-evidence/receipt.json').read_text())
                    self.assertEqual('page-back-targeted', receipt['scope'])
                    self.assertEqual(9, receipt['exit_code'])
                    self.assertEqual(dict(sha='c' * 40, run_id=14, attempt=1), receipt['identity'])
                    self.assertEqual(call.call_args.args[0], receipt['command'])
                    tools = Path(recorder.__file__).resolve().parents[2]
                    self.assertEqual(tools, call.call_args.kwargs['cwd'])
                    self.assertEqual(str(tools / 'tests'), call.call_args.kwargs['env']['PYTHONPATH'].split(os.pathsep)[0])
            finally:
                os.chdir(old)

    def fixture(self, root):
        helper = discovery.SpeechPlatformDiscoveryTest()
        helper.fixture(root)
        for name in CLASSES:
            path = root / 'app/src/androidTest/kotlin' / (name.replace('.', '/') + '.kt')
            path.parent.mkdir(parents=True, exist_ok=True)
            package, _, short = name.rpartition('.')
            path.write_text(chr(10).join(('package ' + package, 'class ' + short,
                                         ' : TestCase() {', ' fun testOne() {}', '}', '')))
        return helper

    def test_exact_return_inventory_covers_requested_classes_once_across_shards(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            selected = []
            for shard in range(4):
                result = helper.select(root, shard, classes=','.join(reversed(CLASSES)))
                self.assertEqual(0, result.returncode, result.stderr)
                selection = json.loads((root / 'smoke/selection.json').read_text())
                inventory = json.loads((root / 'smoke/instrumentation-shards.json').read_text())
                self.assertEqual('targeted', inventory['discovery_scope'])
                self.assertEqual(set(CLASSES), set(inventory['all_classes']))
                selected.extend(selection['selected_classes'])
            self.assertCountEqual(CLASSES, selected)

    def test_unapproved_return_scopes_keep_full_inventory_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            classes = ','.join(CLASSES)
            for raw in (','.join(CLASSES[:-1]), classes + ',app.daiyu.android.UnknownTest',
                        classes + ',' + CLASSES[0]):
                result = helper.select(root, classes=raw)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('Ambiguous instrumentation test declaration', result.stderr)

    def test_requested_return_source_ambiguity_and_missing_files_stay_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            path = root / 'app/src/androidTest/kotlin' / (CLASSES[0].replace('.', '/') + '.kt')
            with path.open('a') as output:
                output.write('class OtherTest : TestCase() {}' + chr(10))
            result = helper.select(root, classes=','.join(CLASSES))
            self.assertNotEqual(0, result.returncode)
            self.assertIn('Ambiguous instrumentation test declaration', result.stderr)
            path.unlink()
            result = helper.select(root, classes=','.join(CLASSES))
            self.assertNotEqual(0, result.returncode)
            self.assertIn('Unknown instrumentation test class', result.stderr)
