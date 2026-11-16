"""All independent chat disclosures share the per-turn direction and anchor policy."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation'


class WorkflowDisclosureCoverageTests(unittest.TestCase):
    def test_nested_disclosures_anchor_the_enclosing_workflow_until_the_requester_finishes(self):
        motion = (CHAT / 'WorkflowDisclosureMotion.kt').read_text()
        work = (CHAT / 'WorkProcessCard.kt').read_text()
        self.assertIn('LocalWorkflowDisclosureParent provides disclosure', work)
        self.assertIn('boundary.captureAnchor(this, anchorAtTop)', motion)
        self.assertIn('boundary.anchorRequester === this', motion)
        self.assertIn('force || boundary.animationIdle', motion)
        self.assertIn('onDispose { motion.dispose() }', motion)

    def test_bottom_reading_anchor_keeps_the_existing_physical_edge(self):
        motion = (CHAT / 'WorkflowDisclosureMotion.kt').read_text()
        capture = motion.split('private fun captureAnchor(', 1)[1].split('private fun releaseAnchor(', 1)[0]
        self.assertIn('anchorBottom = anchorTop + it.size.height', capture)
        self.assertNotIn('safe.bottom', capture)
        source = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/WorkflowDisclosureDirectionTest.kt').read_text()
        self.assertIn('testUpwardToolAndDownwardCollapseKeepTheLowerReadingBoundaryStill', source)
        self.assertIn('testBoundedUpwardToolAndDownwardCollapseKeepTheLowerReadingBoundaryStill', source)
        self.assertIn('readingAnchor, position("reading-anchor"), 1f', source)
        self.assertIn('footerAnchor, position("reading-footer"), 1f', source)

    def test_external_catchup_survives_unwrapping_until_the_same_owner_is_settled(self):
        source = (CHAT / 'ChatMessageList.kt').read_text()
        self.assertNotIn('if (!state.bounded || !protect) state.followCatchup = false', source)
        self.assertIn('if (!protect) state.followCatchup = false', source)
        self.assertIn('if (currentNestedTail.followCatchup) {', source)
        self.assertIn('currentTailKey != assistantOwnerKey ||', source)

    def test_total_workflow_reversals_are_capped_by_placed_closing_geometry(self):
        motion = (CHAT / 'WorkflowDisclosureMotion.kt').read_text()
        work = (CHAT / 'WorkProcessCard.kt').read_text()
        self.assertIn('closingDetailHeight: Int?', motion)
        self.assertIn('placedContainer.size.height - placedHeader.size.height', motion)
        self.assertIn('safe.bottom - placedHeader.positionInWindow().y', motion)
        self.assertIn('disclosure.closingDetailHeight', work)
        self.assertIn('minOf(height, measuredHeight, closingLimit ?: height)', motion)

    def test_supplemental_chat_disclosures_have_both_geometry_anchors_and_toggle(self):
        for name in ('QuestionCard.kt', 'ToolCategoryContent.kt', 'ChildTaskToolContent.kt', 'ChildTaskCardContent.kt'):
            with self.subTest(name=name):
                source = (CHAT / name).read_text()
                for token in ('rememberWorkflowDisclosureMotion(', '.then(disclosure.containerModifier)',
                              '.then(disclosure.headerModifier)', 'disclosure.toggle(opening =',
                              'WorkflowExpandableContent(visibility, disclosure)'):
                    self.assertIn(token, source)
                self.assertNotIn('SettingsExpandableContent(', source)

    def test_terminal_handoff_waits_for_geometric_offsets_not_catchup_flag(self):
        source = (CHAT / 'ChatMessageList.kt').read_text()
        migration = source.split('// Unwrap only after terminal', 1)[1].split('val requestedIndex', 1)[0]
        self.assertEqual(2, migration.count('abs(nestedTail.scrollOffset - nestedTail.viewportOffset) > 1f'))
        self.assertNotIn('|| nestedTail.followCatchup', migration)

    def test_disclosure_scroll_moves_the_window_not_only_its_clipped_contents(self):
        source = (CHAT / 'ChatMessageList.kt').read_text()
        anchor = source.split('correctAnchor = { itemKey, delta ->', 1)[1].split('fun canFollowBottom()', 1)[0]
        self.assertIn('listState.dispatchRawDelta(delta)', anchor)
        self.assertIn('it.size != viewport.contentHeight', anchor)
        self.assertNotIn('viewport.scrollOffset =', anchor)
        self.assertIn('disclosureViewport.active == null', source)

    def test_full_extent_is_a_parent_measure_dependency_even_when_the_viewport_is_fixed(self):
        source = (CHAT / 'ChatMessageList.kt').read_text()
        self.assertIn('HorizontalAlignmentLine(::maxOf)', source)
        self.assertIn('alignmentLines = mapOf(StreamingTailContentHeight to body.height)', source)
        self.assertIn('val contentHeight = viewport[StreamingTailContentHeight]', source)
        self.assertIn('layout(viewport.width, contentHeight)', source)
        self.assertNotIn('state.contentHeight = body.height', source)

    def test_transient_zero_consumption_does_not_move_the_saved_cycle_edge(self):
        source = (CHAT / 'WorkflowDisclosureMotion.kt').read_text()
        self.assertNotIn('anchorTop += delta - consumed', source)
        self.assertNotIn('anchorBottom += delta - consumed', source)

    def test_reentrant_anchor_layout_does_not_rebase_the_cycle_edge(self):
        source = (CHAT / 'WorkflowDisclosureMotion.kt').read_text()
        self.assertIn('anchorGesture == viewport.gesture && !correctingAnchor', source)
        start = source.index('correctingAnchor = true')
        request = source.index('viewport.correctAnchor(it.itemKey, delta)', start)
        release = source.index('finally { correctingAnchor = false }', request)
        self.assertLess(start, request)
        self.assertLess(request, release)

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
        self.assertIn('if (protect) clearance() else 0', source)
        self.assertIn('val actualTop = list.localPositionOf(viewport, Offset.Zero).y', source)
        self.assertIn('!state.followCatchup', source)
        self.assertIn('(state.bounded || protect) && list != null', source)
        self.assertIn('nestedTail.migrating || nestedTail.followCatchup', source)
        self.assertEqual(2, source.count('while (followBottom && (disclosureViewport.active != null ||'))
        self.assertIn('val protect = protectClearance()', source)
        self.assertIn('if (!protect) state.followCatchup = false', source)
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
        self.assertIn('node.size.height.toFloat(), identity.height', source)
        self.assertIn('kotlin.math.abs(compensation) <= 14f * density + 1f', source)
        self.assertIn('animateNested(header, opening = false, frames = 3)', source)
        self.assertIn('animateNested("work-process-header", opening = false)', source)


if __name__ == '__main__':
    unittest.main()
