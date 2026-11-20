"""Task-scroll-only tooling and inventory selection; no emulator or compilation."""
import io
from itertools import permutations
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
    'app.daiyu.android.ui.screens.chat.conversation.ChatStreamingFollowTest',
    'app.daiyu.android.ui.screens.chat.conversation.ChatResumeScrollTest',
    'app.daiyu.android.ui.screens.chat.conversation.ChatStreamingOcclusionTest',
    'app.daiyu.android.ui.screens.chat.conversation.ChatMessageSpacingTest',
    'app.daiyu.android.ui.screens.chat.conversation.ReasoningWorkflowTest',
    'app.daiyu.android.ui.screens.chat.conversation.ReportedReasoningTypingTest',
    'app.daiyu.android.ui.screens.chat.conversation.MarkdownRevealTraversalTest',
    'app.daiyu.android.ui.screens.chat.conversation.ChatMessageListMeasurePhaseTest',
)


COMPLEMENT = (
    'app.daiyu.android.ui.screens.chat.conversation.ChatMessageSpacingTest',
    'app.daiyu.android.ui.screens.chat.conversation.ReportedReasoningTypingTest',
    'app.daiyu.android.ui.screens.chat.conversation.MarkdownRevealTraversalTest',
)

class TaskScrollSmokeScopeTests(unittest.TestCase):
    def test_exact_task_scroll_scope_runs_explicit_contracts_and_tool_guards(self):
        self.assertEqual(CLASSES, recorder.TASK_SCROLL_PLATFORM_CLASSES)
        for classes in (CLASSES, tuple(reversed(CLASSES))):
            command, scope = recorder.command_for('python3', dict(
                SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=','.join(classes)))
            self.assertEqual('task-scroll-targeted', scope)
            self.assertEqual(['python3', '-B', '-m', 'unittest', *recorder.TASK_SCROLL_MODULES, '-v'], command)
        required = {
            'test_streaming_follow_contract',
            'test_streaming_occlusion_contract',
            'test_reasoning_stream_cost_contract',
            'test_workflow_render_isolation_contract',
            'test_markdown_typing_contract',
            'test_typewriter_haptics_contract',
            'test_r77_candidate_repairs_contract.R77CandidateRepairsContract.test_spacing_keeps_original_wait_and_geometry_oracle_with_last_sample_diagnostics',
            'test_chat_resume_scroll_contract',
            'test_diagnostic_smoke_source',
            'test_record_tool_tests_scope',
            'test_acceptance_tool_receipt',
            'test_task_scroll_smoke_scope',
            'test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope',
        }
        self.assertEqual(required, set(recorder.TASK_SCROLL_MODULES))
        self.assertEqual(len(required), len(recorder.TASK_SCROLL_MODULES))
        self.assertNotIn('discover', command)

    def test_exact_paint_diagnostics_keep_task_scroll_checks(self):
        pair = (CLASSES[3], CLASSES[4])
        for classes in (pair, (*pair, CLASSES[2])):
            for ordering in permutations(classes):
                command, scope = recorder.command_for('python3', dict(
                    SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=','.join(ordering)))
                self.assertEqual('task-scroll-targeted', scope)
                self.assertEqual(['python3', '-B', '-m', 'unittest', *recorder.TASK_SCROLL_MODULES, '-v'], command)
            raw = ','.join(classes)
            for invalid in (raw + ',app.daiyu.android.UnknownTest', raw + ',' + classes[0],
                            ' ' + raw, raw + ' ', raw + ',', ',' + raw):
                self.assertEqual('full', recorder.command_for('python3', dict(
                    SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=invalid))[1])
            for mode in ('', 'compile', 'candidate', 'daily', 'release'):
                self.assertEqual('full', recorder.command_for('python3', dict(
                    SMOKE_MODE=mode, SMOKE_TEST_CLASSES=raw))[1])

    def test_workflow_task_scroll_check_runs_streaming_follow_contract(self):
        root = Path(__file__).resolve().parents[1]
        workflow = (root / '.github/workflows/chat-workflow-checks.yml').read_text()
        scope = workflow.split("if os.environ['WORKFLOW_CHECK_SCOPE'] == 'task-scroll':", 1)[1]
        scope = scope.split("if os.environ['WORKFLOW_CHECK_SCOPE'] == 'tool-receipts':", 1)[0]
        self.assertIn("'test_streaming_follow_contract.py'", scope)
        self.assertIn("'test_streaming_occlusion_contract.py'", scope)
        selected = workflow.split('if [[ "$WORKFLOW_CHECK_SCOPE" == task-scroll ]]; then', 1)[1].split('fi', 1)[0]
        self.assertIn('MarkdownAdmissionCompositionTest', selected)
        expected = workflow.rsplit("if os.environ['WORKFLOW_CHECK_SCOPE'] == 'task-scroll':", 1)[1]
        expected = expected.split("if os.environ['WORKFLOW_CHECK_SCOPE'] == 'page-performance':", 1)[0]
        self.assertIn("'MarkdownAdmissionCompositionTest'", expected)

    def test_task_scroll_runs_and_records_protected_frontier_domain_tests(self):
        root = Path(__file__).resolve().parents[1]
        workflow = (root / '.github/workflows/chat-workflow-checks.yml').read_text()
        self.assertIn('domain_tasks=(:core-domain:test --tests app.daiyu.android.domain.BottomFollowMotionTest)', workflow)
        self.assertIn('"${domain_tasks[@]}" :app:compileDebugKotlin :app:testDebugUnitTest "${filters[@]}"', workflow)
        self.assertIn("if record['check_scope'] == 'task-scroll':", workflow)
        self.assertIn("{'app.daiyu.android.domain.BottomFollowMotionTest'}", workflow)
        self.assertIn("record['domain_checks']", workflow)
        self.assertIn("assert ':core-domain:test' in graph", workflow)
        self.assertIn("domain_task.group(1) not in ('NO-SOURCE', 'SKIPPED')", workflow)
        self.assertIn('core-domain/build/test-results/test/', workflow)

    def test_partial_extended_duplicate_and_other_modes_keep_full_selection(self):
        classes = ','.join(CLASSES)
        for raw in ('', ','.join(CLASSES[:-1]), classes + ',app.daiyu.android.UnknownTest',
                    classes + ',' + CLASSES[0], ' ' + classes):
            self.assertEqual('full', recorder.command_for('python3', dict(
                SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=raw))[1])
        for mode in ('', 'compile', 'candidate', 'daily', 'release'):
            self.assertEqual('full', recorder.command_for('python3', dict(
                SMOKE_MODE=mode, SMOKE_TEST_CLASSES=classes))[1])

    def test_task_scroll_receipt_preserves_real_failure_identity_and_command(self):
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
                    self.assertEqual('task-scroll-targeted', receipt['scope'])
                    self.assertEqual(9, receipt['exit_code'])
                    self.assertEqual(dict(sha='c' * 40, run_id=14, attempt=1), receipt['identity'])
                    self.assertEqual(call.call_args.args[0], receipt['command'])
                    tools_root = Path(recorder.__file__).resolve().parents[2]
                    self.assertEqual(tools_root, call.call_args.kwargs['cwd'])
                    self.assertEqual(str(tools_root / 'tests'), call.call_args.kwargs['env']['PYTHONPATH'].split(os.pathsep)[0])
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

    def test_exact_task_scroll_inventory_covers_requested_classes_once_across_shards(self):
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

    def test_unapproved_task_scroll_scopes_keep_full_inventory_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            classes = ','.join(CLASSES)
            for raw in (','.join(CLASSES[:-1]), classes + ',app.daiyu.android.UnknownTest',
                        classes + ',' + CLASSES[0]):
                result = helper.select(root, classes=raw)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('Ambiguous instrumentation test declaration', result.stderr)

    def test_requested_task_scroll_source_ambiguity_and_missing_files_stay_fail_closed(self):
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

class TaskScrollComplementScopeTests(unittest.TestCase):
    fixture = TaskScrollSmokeScopeTests.fixture

    def test_exact_complement_retains_tool_guards(self):
        self.assertEqual(COMPLEMENT, recorder.TASK_SCROLL_COMPLEMENT_CLASSES)
        for classes in (COMPLEMENT, tuple(reversed(COMPLEMENT))):
            command, scope = recorder.command_for('python3', dict(SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=','.join(classes)))
            self.assertEqual('task-scroll-targeted', scope)
            self.assertEqual(['python3', '-B', '-m', 'unittest', *recorder.TASK_SCROLL_MODULES, '-v'], command)

    def test_unapproved_complement_keeps_full_checks(self):
        raw = ','.join(COMPLEMENT)
        for value in (','.join(COMPLEMENT[:-1]), raw + ',' + CLASSES[0], raw + ',' + COMPLEMENT[0], raw + ',', ',' + raw, ' ' + raw, raw + ' '):
            self.assertEqual('full', recorder.command_for('python3', dict(SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=value))[1])
        for mode in ('', 'compile', 'candidate', 'daily', 'release'):
            self.assertEqual('full', recorder.command_for('python3', dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=raw))[1])

    def test_complement_inventory_exact_once_with_one_empty_shard(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            selected, empty = [], 0
            for shard in range(4):
                result = helper.select(root, shard, classes=','.join(reversed(COMPLEMENT)))
                self.assertEqual(0, result.returncode, result.stderr)
                selection = json.loads((root / 'smoke/selection.json').read_text())
                inventory = json.loads((root / 'smoke/instrumentation-shards.json').read_text())
                self.assertEqual('targeted', inventory['discovery_scope'])
                self.assertEqual(set(COMPLEMENT), set(inventory['all_classes']))
                self.assertEqual(3, inventory['total_tests'])
                selected.extend(selection['selected_classes'])
                if not selection['selected_classes']:
                    empty += 1
                    self.assertEqual('filtered-empty-shard', selection['status'])
                    self.assertEqual(0, selection['expected_tests'])
            self.assertCountEqual(COMPLEMENT, selected)
            self.assertEqual(1, empty)

    def test_requested_complement_missing_or_ambiguous_stays_closed(self):
        for missing in (False, True):
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                helper = self.fixture(root)
                path = root / 'app/src/androidTest/kotlin' / (COMPLEMENT[0].replace('.', '/') + '.kt')
                if missing:
                    path.unlink()
                else:
                    with path.open('a') as output:
                        output.write('class OtherTest : TestCase() {}' + chr(10))
                result = helper.select(root, classes=','.join(COMPLEMENT))
                self.assertNotEqual(0, result.returncode)
                self.assertIn('Unknown instrumentation test class' if missing else 'Ambiguous instrumentation test declaration', result.stderr)

    def test_complement_failure_receipt_keeps_frozen_cwd(self):
        old = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                env = dict(GITHUB_SHA='d' * 40, GITHUB_RUN_ID='15', GITHUB_RUN_ATTEMPT='2', SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=','.join(COMPLEMENT))
                with patch.dict(os.environ, env, clear=True), patch.object(recorder.subprocess, 'call', return_value=7) as call, patch.object(recorder.sys, 'stdout', SimpleNamespace(buffer=io.BytesIO())):
                    self.assertEqual(7, recorder.record('python3'))
                    receipt = json.loads(Path('tool-evidence/receipt.json').read_text())
                    self.assertEqual('task-scroll-targeted', receipt['scope'])
                    self.assertEqual(7, receipt['exit_code'])
                    self.assertEqual(dict(sha='d' * 40, run_id=15, attempt=2), receipt['identity'])
                    tools_root = Path(recorder.__file__).resolve().parents[2]
                    self.assertEqual(tools_root, call.call_args.kwargs['cwd'])
                    self.assertEqual(str(tools_root / 'tests'), call.call_args.kwargs['env']['PYTHONPATH'].split(os.pathsep)[0])
            finally:
                os.chdir(old)

    def test_complement_uses_frozen_smoke_script_only_for_canonical_scope(self):
        root = Path(__file__).resolve().parents[1]
        workflow = (root / '.github/workflows/build.yml').read_text()
        canonical = ','.join(COMPLEMENT)
        self.assertIn('if test "$SMOKE_MODE" = smoke && test "$SMOKE_TEST_CLASSES" = "' + canonical + '"; then smoke_script=.smoke-validation-tools/scripts/smoke_boot.sh; fi;', workflow)


    def test_unapproved_complement_keeps_full_inventory_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            raw = ','.join(COMPLEMENT)
            for value in (','.join(COMPLEMENT[:-1]), raw + ',' + CLASSES[0], raw + ',' + COMPLEMENT[0]):
                with self.subTest(classes=value):
                    result = helper.select(root, classes=value)
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn('Ambiguous instrumentation test declaration', result.stderr)

    def test_malformed_complement_is_rejected_before_inventory_discovery(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            raw = ','.join(COMPLEMENT)
            for value in (raw + ',', ',' + raw, ' ' + raw, raw + ' '):
                with self.subTest(classes=value):
                    result = helper.select(root, classes=value)
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn('Expected comma-separated complete instrumentation class names', result.stderr)
                    self.assertNotIn('Ambiguous instrumentation test declaration', result.stderr)


class StreamingOcclusionScopeTests(unittest.TestCase):
    target = 'app.daiyu.android.ui.screens.chat.conversation.ChatStreamingOcclusionTest'

    def test_exact_single_class_keeps_all_task_scroll_contracts(self):
        command, scope = recorder.command_for('python3', dict(SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=self.target))
        self.assertEqual('task-scroll-targeted', scope)
        self.assertEqual(['python3', '-B', '-m', 'unittest', *recorder.TASK_SCROLL_MODULES, '-v'], command)
        self.assertNotIn('discover', command)

    def test_only_exact_single_class_is_newly_approved(self):
        c = self.target
        for raw in ('', ' ', c + ',' + c, c + ',', ',' + c, c + ',,', ' ' + c, c + ' ',
                    c + chr(9), chr(10) + c, c + ',app.daiyu.android.UnknownTest',
                    'app.daiyu.android.UnknownTest,' + c, 'ChatStreamingOcclusionTest', c + '#method'):
            with self.subTest(raw=raw):
                self.assertEqual('full', recorder.command_for('python3', dict(SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=raw))[1])
        for mode in ('', 'compile', 'candidate', 'daily', 'release'):
            self.assertEqual('full', recorder.command_for('python3', dict(SMOKE_MODE=mode, SMOKE_TEST_CLASSES=c))[1])

    def test_single_class_receipt_preserves_failure_and_tools_cwd(self):
        old = Path.cwd()
        with tempfile.TemporaryDirectory() as directory:
            try:
                os.chdir(directory)
                env = dict(GITHUB_SHA='d' * 40, GITHUB_RUN_ID='15', GITHUB_RUN_ATTEMPT='1',
                           SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=self.target)
                with patch.dict(os.environ, env, clear=True), patch.object(
                        recorder.subprocess, 'call', return_value=9) as call, patch.object(
                        recorder.sys, 'stdout', SimpleNamespace(buffer=io.BytesIO())):
                    self.assertEqual(9, recorder.record('python3'))
                    receipt = json.loads(Path('tool-evidence/receipt.json').read_text())
                    self.assertEqual('task-scroll-targeted', receipt['scope'])
                    self.assertEqual(9, receipt['exit_code'])
                    self.assertEqual(dict(sha='d' * 40, run_id=15, attempt=1), receipt['identity'])
                    self.assertEqual(call.call_args.args[0], receipt['command'])
                    root = Path(recorder.__file__).resolve().parents[2]
                    self.assertEqual(root, call.call_args.kwargs['cwd'])
                    self.assertEqual(str(root / 'tests'), call.call_args.kwargs['env']['PYTHONPATH'].split(os.pathsep)[0])
            finally:
                os.chdir(old)

class TaskScrollNativeLayoutScopeTests(unittest.TestCase):
    CLASSES = CLASSES + ('app.daiyu.android.ui.screens.chat.conversation.MessageMarkdownTest',)

    def test_exact_native_layout_scope_retains_the_existing_contract_guards(self):
        self.assertEqual(self.CLASSES, recorder.TASK_SCROLL_NATIVE_LAYOUT_CLASSES)
        self.assertEqual(CLASSES, recorder.TASK_SCROLL_PLATFORM_CLASSES)
        self.assertEqual(COMPLEMENT, recorder.TASK_SCROLL_COMPLEMENT_CLASSES)
        for classes in (self.CLASSES, tuple(reversed(self.CLASSES))):
            command, scope = recorder.command_for('python3', dict(
                SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=','.join(classes)))
            self.assertEqual('task-scroll-targeted', scope)
            self.assertEqual(['python3', '-B', '-m', 'unittest', *recorder.TASK_SCROLL_MODULES, '-v'], command)
            self.assertNotIn('discover', command)

    def test_unapproved_native_layout_sets_and_other_modes_keep_full_selection(self):
        raw = ','.join(self.CLASSES)
        invalid = (','.join(self.CLASSES[1:]), ','.join(self.CLASSES[:-2] + self.CLASSES[-1:]),
                   raw.replace('MessageMarkdownTest', 'MessageMarkdwonTest'),
                   raw + ',app.daiyu.android.UnknownTest', raw + ',' + self.CLASSES[-1],
                   raw + ',', ',' + raw, ' ' + raw, raw + ' ')
        for classes in invalid:
            with self.subTest(classes=classes):
                self.assertEqual('full', recorder.command_for('python3', dict(
                    SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=classes))[1])
        for mode in ('', 'compile', 'candidate', 'daily', 'release'):
            self.assertEqual('full', recorder.command_for('python3', dict(
                SMOKE_MODE=mode, SMOKE_TEST_CLASSES=raw))[1])

    def fixture(self, root):
        helper = TaskScrollSmokeScopeTests().fixture(root)
        name = self.CLASSES[-1]
        path = root / 'app/src/androidTest/kotlin' / (name.replace('.', '/') + '.kt')
        path.parent.mkdir(parents=True, exist_ok=True)
        package, _, short = name.rpartition('.')
        path.write_text(chr(10).join(('package ' + package, 'class ' + short,
                                     ' : TestCase() {', ' fun testOne() {}', '}', '')))
        return helper

    def test_native_layout_inventory_covers_exactly_nine_classes_once_across_four_shards(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            selected = []
            expected = 0
            for shard in range(4):
                result = helper.select(root, shard, classes=','.join(reversed(self.CLASSES)))
                self.assertEqual(0, result.returncode, result.stderr)
                selection = json.loads((root / 'smoke/selection.json').read_text())
                inventory = json.loads((root / 'smoke/instrumentation-shards.json').read_text())
                self.assertEqual('targeted', inventory['discovery_scope'])
                self.assertEqual(set(self.CLASSES), set(inventory['all_classes']))
                selected.extend(selection['selected_classes'])
                expected += selection['expected_tests']
            self.assertCountEqual(self.CLASSES, selected)
            self.assertEqual(len(self.CLASSES), len(set(selected)))
            self.assertEqual(9, expected)

    def test_missing_and_ambiguous_native_layout_source_stay_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            path = root / 'app/src/androidTest/kotlin' / (self.CLASSES[-1].replace('.', '/') + '.kt')
            with path.open('a') as output:
                output.write('class OtherTest : TestCase() {}' + chr(10))
            result = helper.select(root, classes=','.join(self.CLASSES))
            self.assertNotEqual(0, result.returncode)
            self.assertIn('Ambiguous instrumentation test declaration', result.stderr)
            path.unlink()
            result = helper.select(root, classes=','.join(self.CLASSES))
            self.assertNotEqual(0, result.returncode)
            self.assertIn('Unknown instrumentation test class', result.stderr)

    def test_unapproved_native_layout_scopes_cannot_bypass_full_inventory_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            raw = ','.join(self.CLASSES)
            for classes in (','.join(self.CLASSES[1:]),
                            raw.replace('MessageMarkdownTest', 'MessageMarkdwonTest'),
                            raw + ',app.daiyu.android.UnknownTest',
                            raw + ',' + self.CLASSES[-1]):
                result = helper.select(root, classes=classes)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('Ambiguous instrumentation test declaration', result.stderr)


class ResumeScrollScopeTests(unittest.TestCase):
    target = 'app.daiyu.android.ui.screens.chat.conversation.ChatResumeScrollTest'
    fixture = TaskScrollSmokeScopeTests.fixture

    def test_exact_resume_class_keeps_existing_task_scroll_checks(self):
        command, scope = recorder.command_for('python3', dict(
            SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=self.target))
        self.assertEqual('task-scroll-targeted', scope)
        self.assertEqual(['python3', '-B', '-m', 'unittest', *recorder.TASK_SCROLL_MODULES, '-v'], command)
        self.assertNotIn('discover', command)

    def test_only_exact_resume_class_in_smoke_is_newly_approved(self):
        target = self.target
        for raw in ('', ' ', target + ',' + target, target + ',', ',' + target,
                    ' ' + target, target + ' ', target + chr(9), chr(10) + target,
                    target + ',app.daiyu.android.UnknownTest',
                    target + ',app.daiyu.android.ui.screens.chat.conversation.ReasoningWorkflowTest',
                    'ChatResumeScrollTest', target + '#testPartialReplyKeepsItsFrontierAndPacesOffscreenSuffixAfterLazyDisposal'):
            with self.subTest(raw=raw):
                self.assertEqual('full', recorder.command_for('python3', dict(
                    SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=raw))[1])
        for mode in ('', 'compile', 'candidate', 'daily', 'release'):
            self.assertEqual('full', recorder.command_for('python3', dict(
                SMOKE_MODE=mode, SMOKE_TEST_CLASSES=target))[1])

    def test_resume_inventory_runs_class_once_and_preserves_three_empty_shards(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            helper = self.fixture(root)
            selected, empty = [], 0
            for shard in range(4):
                result = helper.select(root, shard, classes=self.target)
                self.assertEqual(0, result.returncode, result.stderr)
                selection = json.loads((root / 'smoke/selection.json').read_text())
                inventory = json.loads((root / 'smoke/instrumentation-shards.json').read_text())
                self.assertEqual('targeted', inventory['discovery_scope'])
                self.assertEqual({self.target: 1}, inventory['all_classes'])
                self.assertEqual(1, inventory['total_tests'])
                selected.extend(selection['selected_classes'])
                if not selection['selected_classes']:
                    empty += 1
                    self.assertEqual('filtered-empty-shard', selection['status'])
                    self.assertEqual(0, selection['expected_tests'])
                else:
                    self.assertEqual(1, selection['expected_tests'])
            self.assertEqual([self.target], selected)
            self.assertEqual(3, empty)

    def test_requested_resume_missing_or_ambiguous_stays_fail_closed(self):
        for missing in (False, True):
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                helper = self.fixture(root)
                path = root / 'app/src/androidTest/kotlin' / (self.target.replace('.', '/') + '.kt')
                if missing:
                    path.unlink()
                else:
                    with path.open('a') as output:
                        output.write('class OtherTest : TestCase() {}' + chr(10))
                result = helper.select(root, classes=self.target)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('No instrumentation tests found' if missing else
                              'Ambiguous instrumentation test declaration', result.stderr)
