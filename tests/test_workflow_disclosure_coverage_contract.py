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
        self.assertIn('boundary.captureAnchor(this, anchorAtTop, openingDownwards)', motion)
        self.assertIn('boundary.anchorRequester === this', motion)
        self.assertIn('force || boundary.animationIdle', motion)
        self.assertIn('onDispose { motion.dispose() }', motion)

    def test_top_anchor_limits_clearance_to_its_own_disclosure(self):
        source = (CHAT / 'WorkflowDisclosureMotion.kt').read_text()
        clearance = source.split('private fun anchorClearance()', 1)[1].split('private fun correctAnchor(', 1)[0]
        self.assertIn('if (!anchorAtTop) return turnClearance', clearance)
        self.assertIn('placed.positionInWindow().y + placed.size.height, safe.bottom', clearance)
        self.assertIn('minOf(turnClearance, disclosureBottom - safeBottom)', source)
        correction = source.split('private fun correctAnchor(', 1)[1].split('private fun chooseDirection()', 1)[0]
        self.assertEqual(3, correction.count('anchorClearance()'))
        self.assertNotIn('viewport.anchorClearance(context.itemKey)', correction)
        self.assertIn('val clearance = anchorClearance()', correction)
        self.assertIn('val delta = maxOf(anchor, clearance ?: anchor)', correction)

    def test_downward_opening_detaches_follow_and_ignores_composer_overflow(self):
        source = (CHAT / 'WorkflowDisclosureMotion.kt').read_text()
        toggle = source.split('fun toggle(opening: Boolean)', 1)[1].split('fun observeExpanded(', 1)[0]
        self.assertLess(toggle.index('cycleChosen = chooseDirection()'), toggle.index('val openingDownwards = opening && !upwards'))
        self.assertIn('if (openingDownwards) viewport.detachFollow()\n            else if (following) viewport.resumeFollow()', toggle)
        self.assertIn('if (detachedOpening) return null', source)
        self.assertIn('detachedOpening = detached', source)
        self.assertIn('if (!detachedOpening && expanded && anchorAtTop', source)
        chat = (CHAT / 'ChatMessageList.kt').read_text()
        self.assertIn('detachFollow = { followBottom = false; wasDragged = false }', chat)
        self.assertNotIn('protectEnclosingSurface', (CHAT / 'ReportedReasoningCard.kt').read_text())
        device = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/WorkflowDisclosureDirectionTest.kt').read_text()
        self.assertIn('testFollowingUpperHeaderExpandsDownBehindComposerWithoutMovingItsTop', device)
        self.assertIn('testCompletedTailReasoningExpandsBehindComposerWithoutMovingTheEnclosingCard', device)
        self.assertIn('bottom() > safeBottom + ChatTailClearance.value * density + 1f', device)
        self.assertIn('following && expected, supplementalFollowing', device)

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

    def test_total_card_animates_visible_pixels_and_waits_for_size_before_release(self):
        extent = (CHAT / 'WorkflowCardExtent.kt').read_text()
        work = (CHAT / 'WorkProcessCard.kt').read_text().split('internal fun WorkStepRow(', 1)[0]
        motion = (CHAT / 'WorkflowDisclosureMotion.kt').read_text()
        self.assertIn('Animatable(Offset.Zero, Offset.VectorConverter)', extent)
        self.assertIn('minOf(measuredHeight, closingLimit ?: measuredHeight)', extent)
        self.assertIn('velocity.x.coerceAtMost(0f)', extent)
        self.assertIn('geometry.animateTo(Offset.Zero, spec, initialVelocity = Offset(', extent)
        self.assertIn('placedGeometry == if (expanded)', extent)
        self.assertIn('finished != false && openingTarget > 0 && geometry.value.x >= openingTarget - 1f', extent)
        self.assertIn('!initialized && initiallyExpanded ||', extent)
        self.assertLess(extent.index('openingTarget = fullHeight\n            geometry.animateTo(Offset(fullHeight.toFloat(), 0f), spec)'), extent.index('finished = expanded'))
        self.assertIn('detailExtent.placed(it.size.height)', work)
        self.assertIn('visibility.targetState = detailExtent.keepsContent(expanded)', work)
        self.assertIn('targetExpanded = expanded, animationIdle = detailExtent.idle(expanded)', work)
        self.assertIn('visibility.isIdle && animationIdle', motion)
        self.assertIn('detailExtent.fullHeight = detail.height', work)
        self.assertNotIn('shrinkVertically(', work)
        device = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/WorkflowDisclosureDirectionTest.kt').read_text()
        self.assertIn('testLongWorkflowManualAndAutomaticCollapseHasNoHiddenHeightPlateau', device)
        self.assertIn('samples[3] < start - 1f', device)
        self.assertIn('fullDetail > start * 2f', device)

    def test_long_card_geometry_uses_matching_unclipped_semantic_bounds(self):
        device = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/WorkflowDisclosureDirectionTest.kt').read_text()
        long_card = device.split('fun testLongWorkflowManualAndAutomaticCollapseHasNoHiddenHeightPlateau()', 1)[1]
        self.assertNotIn('layoutInfo.coordinates', long_card)
        self.assertIn('header.positionInWindow.y + header.size.height', long_card)
        self.assertIn('surface.positionInWindow.y + surface.size.height', long_card)
        self.assertIn('fixedTop, header.positionInRoot.y, 1f', long_card)
        self.assertIn('0f, samples.last(), 1f', long_card)
        self.assertIn('assertDoesNotExist()', long_card)

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
        anchor = source.split('correctAnchor = { itemKey, remaining ->', 1)[1].split('fun canFollowBottom()', 1)[0]
        self.assertIn('listState.dispatchRawDelta(delta)', anchor)
        self.assertLess(anchor.index('forceRemeasure()'), anchor.index('val delta = remaining()'))
        self.assertNotIn('viewport.scrollOffset =', anchor)
        self.assertIn('disclosureViewport.active == null', source)

    def test_full_extent_is_a_parent_measure_dependency_even_when_the_viewport_is_fixed(self):
        source = (CHAT / 'ChatMessageList.kt').read_text()
        self.assertIn('var contentHeight by androidx.compose.runtime.mutableIntStateOf(0)', source)
        self.assertIn('state.contentHeight = body.height', source)
        self.assertIn('val contentHeight = state.contentHeight', source)
        self.assertIn('layout(viewport.width, contentHeight)', source)
        self.assertNotIn('StreamingTailContentHeight', source)
        self.assertNotIn('HorizontalAlignmentLine', source)
        inner = source.index('state.contentHeight = body.height')
        outer_measure = source.index('val viewport = measurables.single().measure', inner)
        self.assertLess(outer_measure, source.index('val contentHeight = state.contentHeight', outer_measure))
        tests = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/ChatMessageListMeasurePhaseTest.kt').read_text()
        self.assertIn('testBoundedContentGrowthUpdatesLazyItemExtentWithoutChangingViewportHeight', tests)
        self.assertIn('testPrefetchedStreamingTailMeasuresWithoutPlacingItsContent', tests)
        self.assertIn('slot.premeasure(0,', tests)

    def test_transient_zero_consumption_does_not_move_the_saved_cycle_edge(self):
        source = (CHAT / 'WorkflowDisclosureMotion.kt').read_text()
        self.assertNotIn('anchorTop += delta - consumed', source)
        self.assertNotIn('anchorBottom += delta - consumed', source)

    def test_reentrant_anchor_layout_does_not_rebase_the_cycle_edge(self):
        source = (CHAT / 'WorkflowDisclosureMotion.kt').read_text()
        self.assertIn('anchorGesture == viewport.gesture && !correctingAnchor', source)
        start = source.index('correctingAnchor = true')
        request = source.index('viewport.correctAnchor(context.itemKey)', start)
        release = source.index('finally { correctingAnchor = false }', request)
        self.assertLess(start, request)
        self.assertLess(request, release)

    def test_press_retains_the_live_anchor_until_a_disclosure_claims_the_click(self):
        motion = (CHAT / 'WorkflowDisclosureMotion.kt').read_text()
        down = motion.split('fun pointerDown(', 1)[1].split('fun claimGesture()', 1)[0]
        self.assertIn('active?.retainAnchor(gesture)', down)
        self.assertNotIn('active = null', down)
        self.assertIn('if (!disclosureClaimed) active = null', motion)
        self.assertIn('context?.viewport?.claimGesture()', motion)
        source = (CHAT / 'ChatMessageList.kt').read_text()
        press = source.split('disclosureViewport.pointerDown(followBottom)', 1)[1].split('contentPadding =', 1)[0]
        self.assertIn('awaitPointerEvent(PointerEventPass.Final)', press)
        self.assertIn('disclosureViewport.pointerUp()', press)

    def test_transition_idle_settles_the_physical_anchor_before_release(self):
        source = (CHAT / 'WorkflowDisclosureMotion.kt').read_text()
        finish = source.split('private fun finishIfSettled()', 1)[1].split('fun dispose()', 1)[0]
        self.assertIn('if (!finishPending || !animationIdle) return', finish)
        output_handoff, settled = finish.split('val boundary = borrowedBoundary ?: this', 1)
        self.assertIn('expanded && anchorAtTop && borrowedBoundary == null && outputChanged()', output_handoff)
        self.assertIn('releaseAnchor(force = false)', output_handoff)
        self.assertLess(settled.index('boundary.correctAnchor(settling = true)'), settled.index('releaseAnchor(force = false)'))
        self.assertIn('(anchorRequester ?: this).finishIfSettled()', source)
        self.assertIn('return abs(anchorDelta()) <= .5f', source)
        self.assertIn('if (anchorRequester != null) return', source)
        device = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/WorkflowDisclosureDirectionTest.kt').read_text()
        self.assertIn('A settled disclosure must release the outer follower', device)

    def test_idle_settlement_leaves_lazy_subcomposition_before_forcing_measure(self):
        source = (CHAT / 'WorkflowDisclosureMotion.kt').read_text()
        effect = source.split('LaunchedEffect(motion, idle, closed) {', 1)[1].split('DisposableEffect(motion)', 1)[0]
        self.assertIn('if (idle)', effect)
        self.assertLess(effect.index('withFrameNanos { }'), effect.index('motion.finish(closed)'))
        self.assertNotIn('SideEffect', source)
        self.assertIn('onDispose { motion.dispose() }', source)
        self.assertIn('(anchorRequester ?: this).finishIfSettled()', source)

    def test_tool_spring_reversal_reuses_the_placed_extent_guard(self):
        source = (CHAT / 'WorkProcessCard.kt').read_text().split('internal fun WorkStepRow(', 1)[1]
        self.assertIn('val detailExtent = remember(step.renderKey) { WorkflowCollapseExtent() }', source)
        self.assertIn('detailExtent.measure(detail.height, expanded, disclosure.closingDetailHeight)', source)
        self.assertIn('shrinkTowards = disclosure.alignment', source)

    def test_chat_helper_preserves_exit_input_and_focus_guards(self):
        source = (CHAT / 'WorkflowExpandableContent.kt').read_text()
        for token in ('visibleState = visibility', 'expandFrom = motion.alignment', 'shrinkTowards = motion.alignment',
                      'focusManager.clearFocus(force = true)', 'clearAndSetSemantics {}',
                      'if (!expanded) cancelFocusChange()', 'it.consume()'):
            self.assertIn(token, source)

    def test_workflow_total_card_alone_has_fixed_top_collapse(self):
        source = (CHAT / 'WorkProcessCard.kt').read_text()
        self.assertEqual(1, source.count('collapseUp = true'))
        self.assertIn('detailExtent.contentOffset', source)
        self.assertIn('shrinkTowards = disclosure.alignment', source)
        motion = (CHAT / 'WorkflowDisclosureMotion.kt').read_text()
        self.assertIn('if (collapseUp) Alignment.Top else alignment', motion)
        self.assertIn('!value && expanded && !manualChange && collapseUp', motion)
        self.assertIn('anchorAtTop = !upwards || collapseUp && !opening', motion)
        self.assertIn('val visibility = remember(ownerKey) { MutableTransitionState(expanded) }', source)
        self.assertNotIn('remember(ownerKey, stopped) { MutableTransitionState', source)

    def test_following_gap_is_single_source_and_catchup_is_not_a_global_scroll_override(self):
        source = (CHAT / 'ChatMessageList.kt').read_text()
        self.assertIn('internal val ChatTailClearance = 8.dp', source)
        self.assertIn('bottom = composerBottomInset + tailClearance', source)
        self.assertIn('if (protect) drawClearance() else 0', source)
        self.assertIn('val actualTop = list.localPositionOf(viewport, Offset.Zero).y', source)
        self.assertIn('!state.followCatchup', source)
        self.assertIn('(state.bounded || protect) && list != null', source)
        correction = source.split('fun correctFollowClearance() {', 1)[1].split('val requestedIndex', 1)[0]
        early_guard = correction.split('val remeasurement =', 1)[0]
        self.assertNotIn('followCatchup', early_guard)
        for token in ('if (nestedTail.migrating ||', '!canFollowBottom()',
                      'val shortBoundedTail = nestedTail.bounded && currentTailKey == assistantOwnerKey &&',
                      'nestedTail.contentHeight > 0 && nestedTail.contentHeight <= layout.viewportSize.height -',
                      'val boundedTerminalReveal = nestedTail.bounded && currentTailKey == assistantOwnerKey &&',
                      'if (!protectMeasuredTail && (activeTail?.status == RunStatus.RUNNING || nestedTail.bounded)) return',
                      'val protectMeasuredTail = shortBoundedTail || boundedTerminalReveal || viewportCorrection',
                      'if (nestedTail.followCatchup && !protectMeasuredTail) return',
                      'if (!protectMeasuredTail && resumeFollowPending',
                      'activeTail.status != RunStatus.RUNNING && !activeTail.isStreaming',
                      'readyTurns[activeTail.listKey] == activeTail',
                      'revealedTurns[activeTail.listKey] != activeTail'):
            self.assertIn(token, correction)
        follow = source.split('fun canFollowBottom(): Boolean =', 1)[1].split('SideEffect {', 1)[0]
        self.assertIn('followBottom && !currentDragging', follow)
        self.assertIn('!currentNestedTail.migrating && disclosureViewport.active == null', follow)
        self.assertEqual(2, source.count('while (followBottom && (disclosureViewport.active != null ||'))
        self.assertIn('val protect = protectClearance()', source)
        self.assertIn('if (!protect) state.followCatchup = false', source)
        self.assertNotIn('16.dp.roundToPx()', source)
        self.assertNotIn('24.dp.roundToPx()', source)

    def test_device_regression_covers_each_supplemental_path_and_real_nested_clipping(self):
        source = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/WorkflowDisclosureDirectionTest.kt').read_text()
        self.assertIn('listOf("question", "category", "child-live", "child-receipt")', source)
        for name in ('testAllSupplementalDisclosuresFollowTheUpperHalfWhileStreaming',
                     'testAllSupplementalDisclosuresFollowingInLowerHalfExpandUp',
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

    def test_settled_upper_reading_anchor_yields_only_to_output_or_interaction(self):
        motion = (CHAT / 'WorkflowDisclosureMotion.kt').read_text()
        for token in ('anchoredOutput = viewport.output(context.itemKey)',
                      'expanded && anchorAtTop && borrowedBoundary == null && !outputChanged()',
                      'context.viewport.outputPending(context.itemKey)',
                      'context.viewport.output(context.itemKey) != anchoredOutput',
                      'fun releaseForOutput()', 'releaseAnchor(force = false)'):
            self.assertIn(token, motion)
        chat = (CHAT / 'ChatMessageList.kt').read_text()
        for token in ('turn.content.filterIsInstance<MessageContentUi.Text>(), turn.finalContent, turn.leadingContent',
                      'nestedStates[itemKey]?.textClearance?.typingPending == true',
                      'disclosureViewport.active?.let { it to it.outputChanged() }',
                      'state.first.releaseForOutput()'):
            self.assertIn(token, chat)



class WorkflowSurfaceLiveClipContractTest(unittest.TestCase):
    def test_surface_clips_to_live_geometry_not_cached_placement(self):
        src = (Path(__file__).resolve().parents[1] /
            'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation/WorkflowTimelineLayout.kt').read_text()
        surface = src.split('internal fun Modifier.streamingWorkflowSurface(', 1)[1].split('internal fun Modifier.workflowInkClearance(', 1)[0]
        draw = surface.split('return tracked.drawWithContent {', 1)[1]
        # A cached placement height predates the same-frame correction and lifts the bottom border for a frame.
        self.assertIn('else clearance.availableHeight(frame.coordinates)', draw)
        self.assertNotIn('frame.availableHeight', draw)


if __name__ == '__main__':
    unittest.main()
