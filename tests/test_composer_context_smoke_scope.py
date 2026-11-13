"""Exact, incremental composer/context regression selection and real tool receipts."""
import io
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from test_record_tool_tests_scope import recorder


class ComposerContextScopeTest(unittest.TestCase):
    def test_exact_set_is_order_independent_but_near_matches_are_not_targeted(self):
        target = recorder.COMPOSER_CONTEXT_PLATFORM_CLASSES
        for classes in (target, tuple(reversed(target))):
            command, scope = recorder.command_for('python3', dict(SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=','.join(classes)))
            self.assertEqual('composer-context-targeted', scope)
            self.assertEqual(['python3', '-B', '-m', 'unittest', *recorder.COMPOSER_CONTEXT_MODULES, '-v'], command)
        for classes in (target[:-1], target + (target[0],), target + ('unknown',)):
            self.assertEqual('full', recorder.command_for('python3', dict(SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=','.join(classes)))[1])
        for mode in ('compile', 'candidate', 'daily'):
            self.assertEqual('full', recorder.command_for('python3', dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=','.join(target)))[1])

    def test_failure_receipt_keeps_exact_command_and_identity(self):
        old = Path.cwd()
        with tempfile.TemporaryDirectory() as folder:
            try:
                os.chdir(folder)
                env = dict(GITHUB_SHA='e' * 40, GITHUB_RUN_ID='29', GITHUB_RUN_ATTEMPT='1', SMOKE_MODE='smoke',
                           SMOKE_TEST_CLASSES=','.join(recorder.COMPOSER_CONTEXT_PLATFORM_CLASSES))
                with patch.dict(os.environ, env, clear=True), patch.object(recorder.subprocess, 'call', return_value=7) as call, patch.object(recorder.sys, 'stdout', SimpleNamespace(buffer=io.BytesIO())):
                    self.assertEqual(7, recorder.record('python3'))
                    receipt = json.loads(Path('tool-evidence/receipt.json').read_text())
                    self.assertEqual('composer-context-targeted', receipt['scope'])
                    self.assertEqual(7, receipt['exit_code'])
                    self.assertEqual(dict(sha='e' * 40, run_id=29, attempt=1), receipt['identity'])
                    self.assertEqual(call.call_args.args[0], receipt['command'])
            finally:
                os.chdir(old)

    def fixture(self, root):
        from test_speech_platform_discovery import SpeechPlatformDiscoveryTest
        helper = SpeechPlatformDiscoveryTest()
        helper.fixture(root)
        paths = []
        for name in recorder.COMPOSER_CONTEXT_PLATFORM_CLASSES:
            path = root / 'app/src/androidTest/kotlin' / (name.replace('.', '/') + '.kt')
            path.parent.mkdir(parents=True, exist_ok=True)
            package, cls = name.rsplit('.', 1)
            path.write_text(f'package {package}\nclass {cls} : TestCase() {{\n fun testOne() {{}}\n}}\n')
            paths.append(path)
        return helper, paths

    def test_four_shards_select_only_four_sources_and_track_added_methods(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            helper, paths = self.fixture(root)
            paths[0].write_text(paths[0].read_text().replace(' fun testOne()', ' fun testAdded() {}\n fun testOne()'))
            selected = []
            for shard in range(4):
                result = helper.select(root, shard, classes=','.join(recorder.COMPOSER_CONTEXT_PLATFORM_CLASSES), count=4)
                self.assertEqual(0, result.returncode, result.stderr)
                inventory = json.loads((root / 'smoke/instrumentation-shards.json').read_text())
                self.assertEqual('targeted', inventory['discovery_scope'])
                self.assertEqual(5, inventory['total_tests'])
                self.assertEqual(set(recorder.COMPOSER_CONTEXT_PLATFORM_CLASSES), set(inventory['all_classes']))
                selection = json.loads((root / 'smoke/selection.json').read_text())
                selected.extend(selection['selected_classes'])
            self.assertEqual(sorted(recorder.COMPOSER_CONTEXT_PLATFORM_CLASSES), sorted(selected))

    def test_missing_or_ambiguous_selected_source_fails_closed(self):
        for ambiguous in (False, True):
            with tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                helper, paths = self.fixture(root)
                if ambiguous:
                    paths[0].write_text(paths[0].read_text() + 'class AnotherTest : TestCase() {}\n')
                else:
                    paths[0].unlink()
                result = helper.select(root, 0, classes=','.join(recorder.COMPOSER_CONTEXT_PLATFORM_CLASSES), count=4)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((root / 'smoke/selection.json').exists())
