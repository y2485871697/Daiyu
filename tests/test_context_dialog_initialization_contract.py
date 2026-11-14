"""The compression sheet never exposes unloaded preferences as a saved default."""
from pathlib import Path
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


if __name__ == '__main__':
    unittest.main()
