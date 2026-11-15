"""All independent chat disclosures share the per-turn direction and anchor policy."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation'


class WorkflowDisclosureCoverageTests(unittest.TestCase):
    def test_supplemental_chat_disclosures_have_both_geometry_anchors_and_toggle(self):
        for name in ('QuestionCard.kt', 'ToolCategoryContent.kt', 'ChildTaskToolContent.kt', 'ChildTaskCardContent.kt'):
            with self.subTest(name=name):
                source = (CHAT / name).read_text()
                for token in ('rememberWorkflowDisclosureMotion(', '.then(disclosure.containerModifier)',
                              '.then(disclosure.headerModifier)', 'disclosure.toggle(opening =',
                              'WorkflowExpandableContent(visibility, disclosure)'):
                    self.assertIn(token, source)
                self.assertNotIn('SettingsExpandableContent(', source)

    def test_disclosure_scroll_moves_the_window_not_only_its_clipped_contents(self):
        source = (CHAT / 'ChatMessageList.kt').read_text()
        anchor = source.split('correctAnchor = { itemKey, delta ->', 1)[1].split('fun canFollowBottom()', 1)[0]
        self.assertIn('listState.dispatchRawDelta(delta)', anchor)
        self.assertIn('it.size != viewport.contentHeight', anchor)
        self.assertNotIn('viewport.scrollOffset =', anchor)
        self.assertIn('disclosureViewport.active == null', source)

    def test_chat_helper_preserves_exit_input_and_focus_guards(self):
        source = (CHAT / 'WorkflowExpandableContent.kt').read_text()
        for token in ('visibleState = visibility', 'expandFrom = motion.alignment', 'shrinkTowards = motion.alignment',
                      'focusManager.clearFocus(force = true)', 'clearAndSetSemantics {}',
                      'if (!expanded) cancelFocusChange()', 'it.consume()'):
            self.assertIn(token, source)

    def test_workflow_total_card_alone_has_fixed_top_collapse(self):
        source = (CHAT / 'WorkProcessCard.kt').read_text()
        self.assertEqual(1, source.count('collapseUp = true'))
        self.assertIn('shrinkTowards = disclosure.collapseAlignment', source)
        self.assertIn('shrinkTowards = disclosure.alignment', source)
        motion = (CHAT / 'WorkflowDisclosureMotion.kt').read_text()
        self.assertIn('if (collapseUp) Alignment.Top else alignment', motion)
        self.assertIn('!value && expanded && !manualChange && collapseUp', motion)
        self.assertIn('anchorAtTop = collapseUp && !opening', motion)
        self.assertIn('val visibility = remember(ownerKey) { MutableTransitionState(expanded) }', source)
        self.assertNotIn('remember(ownerKey, stopped) { MutableTransitionState', source)

    def test_following_gap_is_single_source_and_catchup_is_not_a_global_scroll_override(self):
        source = (CHAT / 'ChatMessageList.kt').read_text()
        self.assertIn('internal val ChatTailClearance = 35.dp', source)
        self.assertIn('bottom = composerBottomInset + ChatTailClearance', source)
        self.assertIn('if (protectClearance()) clearance() else 0', source)
        self.assertIn('!state.followCatchup', source)
        self.assertIn('(state.bounded || protectClearance()) && list != null', source)
        self.assertIn('nestedTail.migrating || nestedTail.followCatchup', source)
        self.assertEqual(2, source.count('while (followBottom && (disclosureViewport.active != null || nestedTail.followCatchup ||'))
        self.assertIn('if (!protectClearance()) state.followCatchup = false', source)
        self.assertNotIn('16.dp.roundToPx()', source)
        self.assertNotIn('24.dp.roundToPx()', source)

    def test_device_regression_covers_each_supplemental_path_and_real_nested_clipping(self):
        source = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/WorkflowDisclosureDirectionTest.kt').read_text()
        self.assertIn('listOf("question", "category", "child-live", "child-receipt")', source)
        for name in ('testAllSupplementalDisclosuresUseActiveStreamingFollowPriority',
                     'testAllSupplementalDisclosuresAtIdleBottomUseTheUpperHalf',
                     'testAllSupplementalDisclosuresDetachedInUpperHalfExpandDown',
                     'testAllSupplementalDisclosuresDetachedInLowerHalfExpandUp',
                     'testNestedWorkflowAndToolMoveTheirReadingWindowInsteadOfClippingTheIdentity'):
            self.assertIn('fun ' + name, source)
        self.assertIn('identity.top >= window - 1f', source)
        self.assertIn('captureToImage().toPixelMap()', source)


if __name__ == '__main__':
    unittest.main()
