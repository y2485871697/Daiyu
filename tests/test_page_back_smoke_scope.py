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
    def test_exact_return_scope_runs_explicit_contracts_and_tool_guards(self):
        self.assertEqual(CLASSES, recorder.PAGE_BACK_PLATFORM_CLASSES)
        for classes in (CLASSES, tuple(reversed(CLASSES))):
            command, scope = recorder.command_for('python3', dict(
                SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=','.join(classes)))
            self.assertEqual('page-back-targeted', scope)
            self.assertEqual(['python3', '-B', '-m', 'unittest', *recorder.PAGE_BACK_MODULES, '-v'], command)
        required = {'test_page_back_gesture_contract', 'test_page_back_repeat_contract',
                    'test_chat_timeline_contract', 'test_reasoning_flow_stability_contract',
                    'test_diagnostic_smoke_source', 'test_record_tool_tests_scope',
                    'test_acceptance_tool_receipt', 'test_page_back_smoke_scope',
                    'test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope'}
        self.assertEqual(required, set(recorder.PAGE_BACK_MODULES))
        self.assertEqual(len(required), len(recorder.PAGE_BACK_MODULES))
        self.assertNotIn('discover', command)

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
                    self.assertTrue(call.call_args.kwargs['env']['PYTHONPATH'].startswith(str(Path('tests').resolve())))
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
