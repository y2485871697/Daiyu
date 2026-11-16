"""The follow-first disclosure regression must not silently run the full suite."""
import unittest
import inspect
from test_record_tool_tests_scope import recorder


CLASSES = tuple('app.daiyu.android.ui.screens.chat.conversation.' + name for name in (
    'WorkflowDisclosureDirectionTest', 'ReasoningWorkflowTest', 'ChatStreamingOcclusionTest',
    'ChatStreamingFollowTest', 'ChatMessageListMeasurePhaseTest', 'ChatResumeScrollTest',
))


class WorkflowDisclosureScopeTests(unittest.TestCase):
    def test_exact_order_independent_scope_has_an_explicit_contract_command(self):
        self.assertEqual(CLASSES, recorder.WORKFLOW_DISCLOSURE_PLATFORM_CLASSES)
        for names in (CLASSES, tuple(reversed(CLASSES))):
            command, scope = recorder.command_for('python3', dict(SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=','.join(names)))
            self.assertEqual('workflow-disclosure-targeted', scope)
            self.assertEqual(['python3', '-B', '-m', 'unittest', *recorder.WORKFLOW_DISCLOSURE_MODULES, '-v'], command)
            self.assertNotIn('discover', command)
            self.assertIn('test_streaming_occlusion_contract', command)
            self.assertIn('test_streaming_follow_contract', command)
            self.assertIn('test_workflow_disclosure_coverage_contract', command)
            self.assertIn('test_disclosure_consistency_contract', command)

    def test_near_matches_do_not_claim_the_targeted_scope(self):
        value = ','.join(CLASSES)
        for malformed in (','.join(CLASSES[:-1]), value + ',' + CLASSES[0], value + ',unknown.Test', value + ',', ' ' + value, ''):
            self.assertNotEqual('workflow-disclosure-targeted', recorder.command_for('python3', dict(
                SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=malformed))[1])
        self.assertNotEqual('workflow-disclosure-targeted', recorder.command_for('python3', dict(
            SMOKE_MODE='release', SMOKE_TEST_CLASSES=value))[1])

    def test_collapse_direction_and_measure_pair_never_discovers_the_full_suite(self):
        names = tuple('app.daiyu.android.ui.screens.chat.conversation.' + name for name in (
            'WorkflowDisclosureDirectionTest', 'ChatMessageListMeasurePhaseTest'))
        self.assertEqual(names, recorder.WORKFLOW_COLLAPSE_PLATFORM_CLASSES)
        for ordered in (names, tuple(reversed(names))):
            command, scope = recorder.command_for('python3', dict(SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=','.join(ordered)))
            self.assertEqual('workflow-collapse-targeted', scope)
            self.assertEqual(['python3', '-B', '-m', 'unittest', *recorder.WORKFLOW_DISCLOSURE_MODULES, '-v'], command)
            self.assertNotIn('discover', command)
        value = ','.join(names)
        for malformed in (names[0], value + ',' + names[0], value + ',unknown.Test', value + ',', ' ' + value, ''):
            self.assertNotEqual('workflow-collapse-targeted', recorder.command_for('python3', dict(
                SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=malformed))[1])
        self.assertNotEqual('workflow-collapse-targeted', recorder.command_for('python3', dict(
            SMOKE_MODE='release', SMOKE_TEST_CLASSES=value))[1])

    def test_collapse_receipt_contracts_follow_the_frozen_validation_tools(self):
        source = inspect.getsource(recorder.record)
        routed = source.split('if scope in (', 1)[1].split('validation_cwd = Path', 1)[0]
        self.assertIn('"workflow-collapse-targeted"', routed)
        self.assertIn('str(validation_cwd / "tests")', source)

    def test_original_workflow_body_scope_stays_independent(self):
        self.assertEqual('workflow-body-targeted', recorder.command_for('python3', dict(
            SMOKE_MODE='smoke', SMOKE_TEST_CLASSES=','.join(recorder.WORKFLOW_BODY_PLATFORM_CLASSES)))[1])
