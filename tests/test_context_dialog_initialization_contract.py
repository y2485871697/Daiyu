"""The compression sheet never exposes unloaded preferences as a saved default."""
from pathlib import Path
import importlib.util
import itertools
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/context/ContextManagementPanel.kt'
ANDROID_TEST = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ContextManagementPanelTest.kt'


class ContextDialogInitializationContractTest(unittest.TestCase):
    def test_preloaded_draft_is_editable_but_only_current_snapshot_can_submit(self):
        source = SOURCE.read_text()
        self.assertIn('val configurationReady = state != null && state.operation == null && !state.requiresRefresh', source)
        self.assertIn('if (configurationReady && saved != null) CompressionDialogSeed.from(saved, source) else null', source)
        self.assertIn('val startingDraft = initial ?: seed?.takeIf { allowSeed && pendingInitialRead }', source)
        self.assertIn('val draft = presentation.draft ?: startingDraft?.draft', source)
        self.assertIn('presentation.retain(initial.draft, initial.binding)', source)
        self.assertIn('startingDraft?.binding else presentation.binding', source)
        self.assertIn('if (!isCurrent() || !canEdit || adapter == null || draft == null) return@confirm', source)
        self.assertIn('val canSubmit = configurationReady && state?.snapshot != null', source)
        self.assertIn('operation == null || operation == ContextManagementOperation.REFRESH', source)
        self.assertLess(source.index('if (!canSubmit)'), source.index('adapter.submitCompression(selection)'))

    def test_prefetch_reuses_existing_local_read_and_never_replaces_visit_snapshot(self):
        activity = (SOURCE.parents[4] / 'MainActivity.kt').read_text()
        loader = activity.split('private fun refreshAutomaticCompression()', 1)[1].split('private fun toggleAutomaticCompression()', 1)[0]
        self.assertIn('withContext(Dispatchers.IO)', loader)
        self.assertIn('CompressionDialogSeed.from(saved.coordinatorSettings(), source)', loader)
        self.assertIn('compressionSeedRevision == revision', loader)
        self.assertIn('seed = compressionDialogSeed', activity)
        self.assertIn('onMore = { if (currentChat()) refreshAutomaticCompression() }', activity)
        self.assertIn('LaunchedEffect(adapter) { adapter?.refresh() }', SOURCE.read_text())

    def test_pending_cold_path_has_no_sheet_but_failure_can_be_retried(self):
        source = SOURCE.read_text()
        dialog = SOURCE.with_name('CompressionConversationDialog.kt').read_text()
        self.assertEqual(1, source.count('CompressionConversationDialog('))
        self.assertNotIn('正在读取会话配置', source)
        self.assertIn('if (draft == null && pendingInitialRead) {\n        BackHandler(onBack = dismiss)\n        return', source)
        self.assertIn('if (adapter == null) onResume() else', source)
        self.assertIn('val dismiss = { if (isCurrent()) { adapter?.close(); onClose() } }', source)
        self.assertIn('Modifier.weight(1f).testTag("context-close")', dialog)
        self.assertIn('if (draft != null) AssistantPreviewButton("确定"', dialog)
        self.assertNotIn('ProductionCompressionSettingsStore', source)

    def test_delayed_saved_enabled_disabled_and_failed_states_have_route_regressions(self):
        tests = ANDROID_TEST.read_text()
        for name in ('testOpeningWhileRefreshIsPendingNeverShowsAnUnloadedConfigurationAndBackCloses',
                     'testPreloadedConfigurationOpensDirectlyWithoutAuthorizingCompression',
                     'testEditingPreloadedDraftKeepsTheSelectedModelWithoutSubmitting',
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
