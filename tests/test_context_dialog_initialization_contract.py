"""The compression sheet never exposes unloaded preferences as a saved default."""
from pathlib import Path
import importlib.util
import itertools
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/context/ContextManagementPanel.kt'
ANDROID_TEST = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ContextManagementPanelTest.kt'


class ContextDialogInitializationContractTest(unittest.TestCase):
    def test_only_a_completed_snapshot_can_initialize_a_new_draft(self):
        source = SOURCE.read_text()
        self.assertIn('val configurationReady = state?.snapshot?.savedConfiguration != null && state.operation == null && !state.requiresRefresh', source)
        initializer = source.split('val initial = remember(', 1)[1].split('val draft =', 1)[0]
        self.assertLess(initializer.index('if (!configurationReady) return@remember null'),
                        initializer.index('CompressionDialogDraft('))
        self.assertIn('val draft = presentation.draft ?: initial', source)
        gate = source.split('if (draft == null) {', 1)[1].split('if (presentation.choosingModel)', 1)[0]
        self.assertIn('return', gate)
        self.assertNotIn('CompressionConversationDialog(', gate)

    def test_pending_and_failure_paths_preserve_close_and_explicit_resume(self):
        source = SOURCE.read_text()
        gate = source.split('if (draft == null) {', 1)[1].split('if (presentation.choosingModel)', 1)[0]
        for token in ('BackHandler(onBack = dismiss)', 'if (isCurrent())',
                      'if (adapter == null) onResume() else adapter.refresh()',
                      'Modifier.testTag("context-close")'):
            self.assertIn(token, gate)
        self.assertNotIn('ProductionCompressionSettingsStore', source)
        self.assertNotIn('submitCompression(', gate)

    def test_delayed_saved_enabled_disabled_and_failed_states_have_route_regressions(self):
        tests = ANDROID_TEST.read_text()
        for name in ('testOpeningWhileRefreshIsPendingNeverShowsAnUnloadedConfigurationAndBackCloses',
                     'testDelayedSavedCustomModelAndProtocolAppearTogetherInTheFirstConfiguration',
                     'testDelayedDisabledConfigurationDoesNotInferCustomModelFromAStaleLabel',
                     'testInitialLoadFailureKeepsRetryAndCloseWithoutShowingFalseDefaults',
                     'testPausedBeforeFirstConfigurationHasExplicitResumeAndCloseWithoutFalseDefaults',
                     'testExplicitRefreshInitializesOnlyFromCompletedFreshSnapshot',
                     'testAdapterReplacementKeepsUnsavedExactTargetAndOnlyRefreshes'):
            self.assertIn('fun ' + name + '()', tests)


class CompressionDialogSmokeScopeTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('compression_dialog_tool_tests',
            ROOT / 'scripts/acceptance/record_tool_tests.py')
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)

    def test_exact_four_classes_and_reverse_order_use_only_related_contracts(self):
        classes = tuple('app.daiyu.android.' + name for name in (
            'ContextManagementPanelTest', 'CompressionConversationDialogTest',
            'ContextManagementEntryActivityTest', 'ControllerContextManagementEntryTest'))
        expected_modules = ('test_context_dialog_initialization_contract',
            'test_compression_presentation_resume_contract', 'test_context_route_lifecycle_contract',
            'test_compression_protocol_help_contract', 'test_summary_protocol_preference_contract',
            'test_record_tool_tests_scope', 'test_acceptance_tool_receipt',
            'test_smoke_boot.SourceTests.test_compile_source_requires_same_sha_and_explicit_targeted_scope')
        self.assertEqual(classes, self.module.COMPRESSION_DIALOG_PLATFORM_CLASSES)
        self.assertEqual(expected_modules, self.module.COMPRESSION_DIALOG_MODULES)
        for selected in itertools.permutations(classes):
            for python in ('python', 'python3'):
                command, scope = self.module.command_for(python, dict(SMOKE_MODE='smoke',
                    SMOKE_TEST_CLASSES=','.join(selected)))
                self.assertEqual('compression-dialog-targeted', scope)
                self.assertEqual([python, '-B', '-m', 'unittest', *expected_modules, '-v'], command)
                self.assertNotIn('discover', command)

    def test_partial_or_extra_class_does_not_claim_the_complete_targeted_scope(self):
        classes = self.module.COMPRESSION_DIALOG_PLATFORM_CLASSES
        for selected in (classes[:-1], (*classes, classes[0]), (*classes, 'app.daiyu.android.UnrelatedTest')):
            _, scope = self.module.command_for('python3', dict(SMOKE_MODE='smoke',
                SMOKE_TEST_CLASSES=','.join(selected)))
            self.assertEqual('full', scope)

    def test_the_same_classes_do_not_change_non_smoke_checks(self):
        _, scope = self.module.command_for('python3', dict(SMOKE_MODE='checks',
            SMOKE_TEST_CLASSES=','.join(self.module.COMPRESSION_DIALOG_PLATFORM_CLASSES)))
        self.assertEqual('full', scope)


if __name__ == '__main__':
    unittest.main()
