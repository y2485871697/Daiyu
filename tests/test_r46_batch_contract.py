"""Keep the R45 device-failure repairs wired without replacing device acceptance."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'app/src/main/kotlin/app/daiyu/android'
TEST = ROOT / 'app/src/androidTest/kotlin/app/daiyu/android'

class R46BatchContract(unittest.TestCase):
    def test_editor_replacement_retires_video_visit_without_detaching_route(self):
        text = (MAIN / 'MainActivity.kt').read_text()
        select = text.split('internal fun select(conversation:', 1)[1].split('private fun sendDraft', 1)[0]
        self.assertIn('fenceVideoRoute(detachHost = false)', select)
        self.assertIn('videoHost.enter(conversation?.id?.value)', select)
        self.assertIn('if (detachHost && controller.ownsVideoUi(videoUi)) videoHost.detach()', text)
        self.assertIn('private fun fenceVideoRoute(detachHost: Boolean = true)', text)

    def test_input_window_retains_dialog_token_and_view_tree_owner(self):
        text = (MAIN / 'ui/components/ManagedInput.kt').read_text()
        self.assertIn('if (ancestor is DialogWindowProvider) return ancestor.window', text)
        self.assertIn('(findViewTreeLifecycleOwner() as? Activity) ?: context.inputActivity()', text)
        self.assertIn('isAttachedToWindow && it.decorView.windowToken == windowToken', text)
        self.assertIn('entry.view.findViewTreeLifecycleOwner()?.lifecycle', text)

    def test_pause_uses_real_framework_cycle_and_positive_compose_root(self):
        text = (TEST / 'ContextManagementEntryActivityTest.kt').read_text().split('fun testPauseWithoutStop', 1)[1].split('fun testSelectionAndPage', 1)[0]
        self.assertIn('realPauseResume(activity, atPause = {', text)
        self.assertNotIn('callActivityOnPause', text)
        self.assertIn('awaitUniqueTag("chat-draft")', text)
        self.assertIn('assertFalse(old.state.value.confirmingCompression)', text)

    def test_mcp_rejection_compares_persisted_values_and_generation(self):
        text = (TEST / 'McpSettingsScreenTest.kt').read_text()
        self.assertNotIn('assertEquals(beforeRevocation, store.snapshot())', text)
        for value in ['revision', 'stamp', 'servers.size']:
            self.assertIn('beforeRevocation.' + value, text)
        for value in ['id', 'label', 'endpoint', 'bearer', 'enabled', 'allowedTools', 'catalog']:
            self.assertIn('before.' + value, text)
        self.assertIn('old.sameMetadata(fresh)', text)
        self.assertIn('store.save(emptyList(), owner, beforeRevocation)', text)

    def test_entry_waits_for_real_animation_not_a_timer(self):
        text = (MAIN / 'ui/components/DialogMotion.kt').read_text()
        self.assertIn('this[DialogEnterSettled] = progress.value == 1f && !progress.isRunning', text)
        helper = (TEST / 'ComposeActivityTestCase.kt').read_text().split('protected fun awaitDialogEnter', 1)[1].split('protected fun awaitDialogImeFrame', 1)[0]
        self.assertIn('signals.isNotEmpty()', helper)
        self.assertIn('signals.all { it }', helper)
        footer = (TEST / 'SubAgentCollaborationLayoutTest.kt').read_text().split('fun testProfileFormKeepsEqualFooter', 1)[1]
        self.assertLess(footer.index('awaitDialogEnter(actions)'), footer.index('val deleteNode'))
        self.assertIn('assertEquals(geometry, actionsBefore.top, after.top, 1f)', footer)

    def test_touch_bounds_are_distinct_from_compact_fill(self):
        # R63: the approved provider design now paints its complete, scalable target.
        text = (TEST / 'ProviderSettingsLayoutActivityTest.kt').read_text()
        for token in ['.assertHeightIsAtLeast(48.dp)',
                      'touch.height >= kotlin.math.round(48f * density)',
                      'assertEquals("touch and fill top", bounds.top, touch.top, 1f)',
                      'assertEquals("touch and fill bottom", bounds.bottom, touch.bottom, 1f)',
                      'host.performTouchInput { click(topPoint - hostBounds.topLeft) }',
                      'host.performTouchInput { click(bottomPoint - hostBounds.topLeft) }',
                      'bounds.height, paintedRows.size.toFloat(), 2f',
                      'assertTrue("large font grows the fill: $fontDiagnostic", bounds.height > 48f * density)',
                      'touch targets do not overlap', 'clicks.all { it == 3 }']:
            self.assertIn(token, text)

    def test_caret_comparison_and_dialog_window_evidence_are_preserved(self):
        text = (TEST / 'ManagedInputActivityTest.kt').read_text()
        for token in ['.systemBarsPadding().imePadding()', 'node(tag).scrollToVisible()',
                      'hidden.contentEquals(unfocused)', 'Visible IME must show the caret to this test',
                      'awaitDialogImeFrame(node(tag), visible)', 'awaitImeFrame(node(tag), visible)']:
            self.assertIn(token, text)
        helper = (TEST / 'ComposeActivityTestCase.kt').read_text()
        self.assertIn('AccessibilityWindowInfo.TYPE_INPUT_METHOD', helper)
        self.assertIn('flags = oldFlags', helper)

if __name__ == '__main__':
    unittest.main()
