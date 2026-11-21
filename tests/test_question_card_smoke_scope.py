"""Question-card verification must not discover unrelated device or tool tests."""
import importlib.util
import itertools
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
TARGETS = (
    'app.daiyu.android.ui.screens.chat.conversation.QuestionCardReadabilityTest',
    'app.daiyu.android.question.QuestionCardBindingTest',
    'app.daiyu.android.question.QuestionTest',
)
MODULES = ('test_question_card_readability_contract', 'test_stream_question_projection_contract',
           'test_question_card_smoke_scope')
SPEC = importlib.util.spec_from_file_location('question_scope_recorder', ROOT / 'scripts/acceptance/record_tool_tests.py')
recorder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(recorder)


class QuestionCardSmokeScopeTest(unittest.TestCase):
    def test_recorder_uses_exact_closed_modules_for_every_class_order(self):
        for python in ('python', 'python3'):
            for order in itertools.permutations(TARGETS):
                command, scope = recorder.command_for(python, dict(SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=','.join(order)))
                self.assertEqual('question-card-targeted', scope)
                self.assertEqual([python, '-B', '-m', 'unittest', *MODULES, '-v'], command)
                self.assertNotIn('discover', command)

    def test_nonexact_or_other_mode_input_keeps_full_fallback(self):
        valid = ','.join(TARGETS)
        invalid = ('', ','.join(TARGETS[:-1]), valid + ',' + TARGETS[0], valid + ',app.daiyu.android.OtherTest',
                   valid + ',', ' ' + valid, valid + ' ', valid.replace(',', ', ', 1))
        for selected in invalid:
            self.assertEqual('full', recorder.command_for('python3', dict(SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=selected))[1])
        for mode in ('', 'compile', 'candidate', 'daily', 'release'):
            self.assertEqual('full', recorder.command_for('python3', dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=valid))[1])

    def selector(self, root, classes, shard=0, api='26'):
        source = (ROOT / 'scripts/smoke_boot.sh').read_text().split("<<'PYCOUNT'\n", 1)[1].split('\nPYCOUNT', 1)[0]
        guard = "from unittest.mock import patch\nfrom pathlib import Path\n"
        guard += "with patch.object(Path, 'rglob', side_effect=AssertionError('full androidTest scan')):\n"
        guard += '    exec(' + repr(source) + ')\n'
        env = dict(os.environ, SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=','.join(classes),
                   SMOKE_SHARD_COUNT='4', SMOKE_SHARD_INDEX=str(shard), SMOKE_API=api,
                   SMOKE_SCRIPT_DIR=str(ROOT / 'scripts'))
        return subprocess.run([sys.executable, '-X', 'utf8', '-'], input=guard, encoding='utf-8', cwd=root, env=env, text=True, capture_output=True)

    def fixture(self, root):
        (root / 'smoke').mkdir()
        paths = []
        for count, target in enumerate(TARGETS, 1):
            package, name = target.rsplit('.', 1)
            path = root / 'app/src/androidTest/kotlin' / (target.replace('.', '/') + '.kt')
            path.parent.mkdir(parents=True, exist_ok=True)
            methods = ''.join(' fun testCase%d() {}\n' % n for n in range(count))
            path.write_text('package %s\nclass %s : TestCase() {\n%s}\n' % (package, name, methods))
            paths.append(path)
        return paths

    def test_four_shards_on_both_apis_read_only_three_explicit_sources(self):
        counts = dict(zip(TARGETS, (1, 2, 3)))
        for api in ('26', '37.2'):
            for order in (TARGETS, tuple(reversed(TARGETS))):
                with tempfile.TemporaryDirectory() as temporary:
                    root = Path(temporary)
                    self.fixture(root)
                    selected = []
                    for shard in range(4):
                        result = self.selector(root, order, shard, api)
                        self.assertEqual(0, result.returncode, result.stderr)
                        manifest = json.loads((root / 'smoke/instrumentation-shards.json').read_text())
                        selection = json.loads((root / 'smoke/selection.json').read_text())
                        self.assertEqual('targeted', manifest['discovery_scope'])
                        self.assertEqual(counts, manifest['all_classes'])
                        self.assertEqual(sorted(TARGETS), selection['requested_classes'])
                        actual = selection['selected_classes']
                        selected.extend(actual)
                        self.assertEqual(sum(counts[name] for name in actual), selection['expected_tests'])
                        self.assertEqual('planned' if actual else 'filtered-empty-shard', selection['status'])
                    self.assertEqual(sorted(TARGETS), sorted(selected))

    def test_missing_empty_or_ambiguous_target_source_fails_without_fallback(self):
        for fault in ('missing', 'empty', 'ambiguous'):
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                paths = self.fixture(root)
                if fault == 'missing':
                    paths[0].unlink()
                elif fault == 'empty':
                    paths[0].write_text('package app.daiyu.android.ui.screens.chat.conversation\nclass QuestionCardReadabilityTest {}\n')
                else:
                    with paths[0].open('a') as stream:
                        stream.write('class SecondTest : TestCase() { fun testExtra() {} }\n')
                result = self.selector(root, TARGETS)
                self.assertNotEqual(0, result.returncode, fault)
