from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation'
DOMAIN = ROOT / 'core-domain/src/main/kotlin/app/daiyu/android/domain'

class ReasoningWorkflowContractTest(unittest.TestCase):
    def test_workflow_waiting_rotates_and_failure_uses_animated_red_cross(self):
        work = (CHAT / 'WorkProcessCard.kt').read_text()
        thought = (CHAT / 'ReportedReasoningCard.kt').read_text()
        motion = (CHAT / 'WorkflowNodeMotion.kt').read_text()
        self.assertIn('val spinning = flowRunning && !terminal', work)
        self.assertIn('expanded && !stopped && work.controlActivity != ToolActivityUi.PAUSED', work)
        self.assertIn('fresh && enabled', motion)
        self.assertIn('ToolActivityUi.WAITING', work)
        self.assertIn('if (spinning) WorkflowWaitingIndicator', work)
        self.assertIn('!complete && allowLiveTiming && !generationComplete && !stopped', thought)
        self.assertIn('if (spinning) WorkflowWaitingIndicator', thought)
        self.assertIn('CircularProgressIndicator(Modifier.size(10.dp)', motion)
        self.assertIn('strokeWidth = 1.5.dp', motion)
        self.assertIn('failed = step.state == WorkStepState.FAILED', work)
        self.assertIn('Icon(DaiyuIcons.Close, "执行失败"', work)
        self.assertNotIn('Text("!"', work)
        self.assertIn('step.state == WorkStepState.FAILED -> colors.error', work)
        self.assertIn('fun fail(key: String) = failed.add(key)', motion)
        self.assertIn('failed.add(item.workflowKey(index))', motion)

    def test_terminal_glyph_waits_for_real_incoming_connector_before_its_spring(self):
        motion = (CHAT / 'WorkflowNodeMotion.kt').read_text()
        completion = motion[motion.index('internal fun Modifier.workflowCompletion'):motion.index('internal fun WorkflowWaitingIndicator')]
        self.assertIn('rememberUpdatedState(LocalWorkflowLineMotion.current.incoming)', completion)
        wait = 'snapshotFlow { incoming.value?.fraction() ?: 1f }.first { it >= 1f }'
        self.assertIn(wait, completion)
        self.assertLess(completion.index(wait), completion.index('progress.animateTo(1f, spring('))
        self.assertNotIn('delay(', completion)
        self.assertIn('if (animate && sharedAppearance == null) {', completion)
        regression = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/WorkflowNodeMotionTest.kt').read_text()
        for name in ('testAppendedSuccessWaitsForIncomingConnector', 'testAppendedFailureWaitsForIncomingConnector',
                     'testReasoningCompletionWaitsForActualArrivalThenPopsIn'):
            self.assertIn('fun ' + name, regression)

    def test_tool_body_and_glyph_share_incoming_arrival_without_moving_the_line_or_text(self):
        motion = (CHAT / 'WorkflowNodeMotion.kt').read_text()
        work = (CHAT / 'WorkProcessCard.kt').read_text()
        self.assertIn('fun appear(key: String) = appeared.add(key)', motion)
        self.assertIn('appeared.add(item.workflowKey(index))', motion)
        appearance = motion[motion.index('internal fun rememberWorkflowToolAppearance'):motion.index('internal fun Modifier.workflowToolAppearance')]
        self.assertIn('remember(owner, key)', appearance)
        self.assertIn('WorkflowToolAppearance(owner.appear(key) && enabled)', appearance)
        self.assertIn('snapshotFlow { incoming.value?.fraction() ?: 1f }.first { it >= 1f }', appearance)
        self.assertIn('appearance.progress.animateTo(1f, spring(', appearance)
        self.assertIn('else appearance.progress.snapTo(1f)', appearance)
        self.assertNotIn('delay(', appearance)
        self.assertIn('appearance?.takeUnless { it.settled }', motion)
        self.assertIn('sharedAppearance?.progress?.value ?: progress.value', motion)
        self.assertIn('translationY = 4.dp.toPx() * (1f - fraction)', motion)
        self.assertIn('val appearance = rememberWorkflowToolAppearance("tool:${step.renderKey}")', work)
        self.assertIn('failed = step.state == WorkStepState.FAILED, appearance = appearance', work)
        self.assertIn('testTag("tool-step-body:${step.toolCallId}").workflowToolAppearance(appearance)', work)
        row = work[work.index('internal fun WorkStepRow'):]
        self.assertLess(row.index('.drawBehind {'), row.index('.workflowToolAppearance(appearance)'))
        content = (CHAT / 'MessageContent.kt').read_text()
        self.assertNotIn('workflowToolAppearance', content)
        self.assertIn('val timelineIndices = visibleIndices.filter', content)
        narration = (CHAT / 'WorkflowNarration.kt').read_text()
        self.assertIn('entry.precedingText.all { currentReveal.ready(it.id, it.text, it.window) }', narration)
        regression = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/WorkflowNodeMotionTest.kt').read_text()
        for name in ('testNewPendingToolAndFastCompletionShareActualArrival',
                     'testExistingToolReceiptUpdateDoesNotReplayItsBody',
                     'testNarrationWaitDoesNotDrawADanglingLineOrExposeTheNextTool'):
            self.assertIn('fun ' + name, regression)

    def test_reasoning_arrival_keeps_header_and_typewriter_behind_the_node(self):
        card = (CHAT / 'ReportedReasoningCard.kt').read_text()
        motion = (CHAT / 'WorkflowNodeMotion.kt').read_text()
        row = card[card.index('internal fun ReasoningStepRow('):]
        identity = chr(34) + 'reasoning:${reported.itemId}' + chr(34)
        self.assertIn('rememberWorkflowToolAppearance(' + identity + ')', row)
        self.assertIn('.workflowCompletion(' + identity + ', complete, appearance = appearance)', row)
        self.assertIn('onHandoffReady = onHandoffReady, appearance = appearance', row)
        header = card[card.index('Row(Modifier.fillMaxWidth().heightIn'):card.index('AnimatedVisibility(')]
        self.assertIn('.workflowToolAppearance(appearance)', header)
        self.assertIn('val arrived = appearance?.arrived != false', card)
        self.assertIn('val revealEnabled = arrived && revealGate && hasReportedReasoningText(text)', card)
        self.assertIn('val reveal = key(revealGate)', card)
        self.assertNotIn('key(revealEnabled)', card)
        gate = card[card.index('val revealGate ='):card.index('val revealEnabled =')]
        self.assertNotIn('hasReportedReasoningText', gate)
        for token in ('live = isThinking', 'paceTerminal = queuedForArrival',
                      'initialBaseline = if (queuedForArrival) emptyList() else null',
                      'onHandoffReady(appearance?.settled != false && handoffReady)'):
            self.assertIn(token, card)
        body = card[card.index('AnimatedVisibility('):card.index('internal fun rememberReasoningElapsed(')]
        self.assertIn('enter = if (manualExpansion)', body)
        self.assertIn('else EnterTransition.None', body)
        self.assertNotIn('workflowToolAppearance', body)
        appearance = motion[motion.index('internal fun rememberWorkflowToolAppearance'):motion.index('internal fun Modifier.workflowToolAppearance')]
        wait = 'snapshotFlow { incoming.value?.fraction() ?: 1f }.first { it >= 1f }'
        self.assertIn('var arrived by mutableStateOf(!animate)', motion)
        self.assertLess(appearance.index(wait), appearance.index('appearance.arrived = true'))
        self.assertLess(appearance.index('appearance.arrived = true'), appearance.index('appearance.progress.animateTo(1f, spring('))
        self.assertLess(appearance.index('appearance.progress.animateTo(1f, spring('), appearance.index('appearance.settled = true'))
        typing = (CHAT / 'MarkdownTyping.kt').read_text()
        self.assertIn('if (baseline.isEmpty()) 0 else markdownTypingBaselineLength(', typing)
        regression = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/WorkflowNodeMotionTest.kt').read_text()
        for name in ('testNewActiveReasoningWaitsForActualArrival',
                     'testMixedLiveToolReasoningToolReasoningWaitsForEachFrontier',
                     'testLegacyLiveToolReasoningToolReasoningWaitsForEachFrontier'):
            self.assertIn('fun ' + name, regression)

    def test_successive_edges_wait_for_their_upstream_incoming(self):
        motion = (CHAT / 'WorkflowNodeMotion.kt').read_text()
        lines = motion[motion.index('internal fun rememberWorkflowLineMotions'):motion.index('internal fun WorkflowLineScope')]
        self.assertIn('val upstream = rememberUpdatedState(result[from]?.incoming)', lines)
        wait = 'snapshotFlow { upstream.value?.fraction() ?: 1f }.first { it >= 1f }'
        self.assertLess(lines.index(wait), lines.index('progress.animateTo(1f, tween('))
        self.assertIn('(result[from] ?: WorkflowLineMotion()).copy(outgoing =', lines)
        self.assertIn('(result[to] ?: WorkflowLineMotion()).copy(incoming =', lines)
        regression = (ROOT / 'app/src/androidTest/kotlin/app/daiyu/android/ui/screens/chat/conversation/WorkflowNodeMotionTest.kt').read_text()
        self.assertIn('fun testNewEdgesWaitForUpstreamIncoming', regression)

    def test_motion_check_keeps_an_exact_contract_and_jvm_scope(self):
        workflow = (ROOT / '.github/workflows/chat-workflow-checks.yml').read_text()
        self.assertIn('options: [workflow-node-motion,', workflow)
        self.assertIn("if os.environ['WORKFLOW_CHECK_SCOPE'] == 'workflow-node-motion':\n              patterns = ('test_reasoning_workflow_contract.py', 'test_work_timeline_connector_contract.py',", workflow)
        self.assertIn('if [[ "$WORKFLOW_CHECK_SCOPE" == workflow-node-motion ]]; then\n            classes=(WorkflowConnectorsTest WorkflowNarrationCompositionTest MarkdownRevealTest ReasoningHandoffTest)\n            filters=()', workflow)
        self.assertIn("if os.environ['WORKFLOW_CHECK_SCOPE'] == 'workflow-node-motion':\n              expected = {'WorkflowConnectorsTest', 'WorkflowNarrationCompositionTest', 'MarkdownRevealTest', 'ReasoningHandoffTest'}", workflow)

    def test_captured_position_is_display_metadata_not_protocol(self):
        source = (DOMAIN / 'Conversation.kt').read_text()
        self.assertIn('val blockOffset: Int? = null', source)
        runner = (DOMAIN / 'ChatRunner.kt').read_text()
        self.assertEqual(2, runner.count('blockOffset = current.blocks.size'))
        history = (DOMAIN / 'RunnerHistorySegments.kt').read_text()
        self.assertIn('if (previous == null) blockOffset else previous.blockOffset', history)
        self.assertIn('blocks.take(offset - start).count(::keep)', history)
        self.assertIn('it.protocolReasoning = protocolReasoning', history)

    def test_codec_preserves_legacy_shape_and_validates_new_metadata(self):
        source = (ROOT / 'app/src/main/kotlin/app/daiyu/android/storage/ReportedReasoningCodec.kt').read_text()
        self.assertIn('value.blockOffset?.let', source)
        self.assertIn('raw is Int || raw is Long', source)
        self.assertIn('0..Int.MAX_VALUE.toLong()', source)
        self.assertIn('blockOffset = offset', source)

    def test_both_mapper_paths_share_real_boundary_positions(self):
        source = (CHAT / 'ChatTurnMapper.kt').read_text()
        self.assertIn('MessageContentUi.Reasoning(it)', source)
        self.assertIn('WorkSegmentUi(reasoning = it)', source)
        self.assertIn('.groupBy { it.blockOffset ?: 0 }', source)
        self.assertIn('val hasWorkflow = hasTools || message.reportedReasoning.any { '
                      'hasReportedReasoningText(it.text) || it.activeSinceNanos != null }', source)
        self.assertIn('workProcess = if (hasWorkflow) WorkProcessUi(segments, summary, running) else null', source)
        self.assertIn('integrateReasoning = hasWorkflow', source)
        self.assertIn('val display = mutableListOf<DisplayMessage>()', source)
        self.assertNotIn('val orderedTail', source)

    def test_integrated_card_reuses_disclosure_and_preserves_dedicated_tool_route(self):
        source = (CHAT / 'ReportedReasoningCard.kt').read_text()
        self.assertIn('embedded: Boolean = false', source)
        self.assertIn('embedded = true', source)
        self.assertIn('rememberMarkdownProjection(listOf(text), streaming = streamingProjection, windows = listOf(sourceWindow))', source)
        self.assertIn('generationComplete && projection != null && displayed == summary.text', source)
        self.assertIn('ReasoningDisclosureDurationMillis = 220', source)
        content = (CHAT / 'MessageContent.kt').read_text()
        self.assertIn('reasoningBody(block.reported,', content)
        self.assertEqual(1, content.count('dedicatedTool(block.step)'))
        self.assertIn('key(block.step.renderKey)', content)
        self.assertIn('val dedicated = dedicatedTool(block.step)', content)
        self.assertIn('if (visible && !dedicated) SpacedMessageEntry(index, content, (connectors[index] ?: WorkflowConnector(false, false, false)), entryBody, fillWidth, lastVisibleIndex = visibleIndices.lastOrNull() ?: -1, lineMotion = lineMotions[index]) {', content)
        self.assertEqual(1, content.count('WorkStepRow('))
        self.assertLess(content.index('val dedicated = dedicatedTool(block.step)'),
                        content.index('if (visible && !dedicated) SpacedMessageEntry'))

    def test_top_card_is_not_duplicated_and_timing_has_exact_owner_gate(self):
        source = (CHAT / 'ChatMessageList.kt').read_text()
        self.assertNotIn('ReportedReasoningCard(', source)
        self.assertEqual(1, source.count('WorkProcessCard('))
        self.assertIn('val ordered = workflowContent ?: rememberAssistantWorkflowContent(turn)', source)
        self.assertIn('val ordered = remember(turn) { turn.workflowContent() }', (CHAT / 'AssistantWorkflowContent.kt').read_text())
        self.assertIn('MessageContent(steps,', source)
        self.assertIn('ReasoningStepRow(ownerKey, reported, connector,', source)
        self.assertIn('turn.isStreaming && turn.status == RunStatus.RUNNING', source)
        work = (CHAT / 'WorkProcessCard.kt').read_text()
        self.assertIn('key(ownerKey, "reasoning", reported.itemId)', work)
        self.assertNotIn('key(ownerKey, "reasoning", reported.itemId, expanded)', work)
        self.assertIn('reasoningState.SaveableStateProvider(reported.itemId)', work)
        self.assertIn('val progressSummary = work.progressSummary', work)
        self.assertIn('reasoningState.SaveableStateProvider("ordered-content")', work)
        self.assertIn('key(block.workflowKey(index))', (CHAT / 'MessageContent.kt').read_text())

    def test_outer_workflow_uses_one_title_icon_and_completed_progress(self):
        model = (CHAT / 'ChatScreenModels.kt').read_text()
        self.assertIn('val title: String get() = "工作流程"', model)
        self.assertIn('segment.steps.count { it.state == WorkStepState.OK }', model)
        self.assertIn('it.activeSinceNanos == null && hasReportedReasoningText(it.text)', model)
        work = (CHAT / 'WorkProcessCard.kt').read_text()
        self.assertIn('val title = work.title', work)
        self.assertIn('ToolOperationIcons.operation("chain")', work)
        self.assertEqual(2, work.count('Text(progressSummary'))
        self.assertNotIn('processTitleForSteps', work)
        self.assertNotIn('$stepCount 项', work)
        presentation = (CHAT / 'ToolCardPresentation.kt').read_text()
        self.assertNotIn('categoryTitles', presentation)
        self.assertNotIn('fun processTitle', presentation)
        icons = (CHAT / 'ToolOperationIcons.kt').read_text()
        self.assertNotIn('fun category(', icons)
        self.assertIn('ToolCategoryContent(step.category, it)', work)

    def test_runtime_controls_use_the_exact_owner_even_after_tools_finish(self):
        mapper = (CHAT / 'ChatTurnMapper.kt').read_text()
        self.assertIn('phase == ExecutionPhase.PAUSED -> ToolActivityUi.PAUSED', mapper)
        self.assertIn('stopRequested -> ToolActivityUi.STOPPING', mapper)
        self.assertIn('controlActivity.takeIf { index == last }', mapper)
        host = (ROOT / 'app/src/main/kotlin/app/daiyu/android/MainActivity.kt').read_text()
        self.assertIn('controlOwner, phase = state?.phase ?: ExecutionPhase.IDLE, pauseRequested = state?.pauseRequested == true', host)
        self.assertIn('stopRequested = controlOwner?.let(controller::runStopping) == true', host)

    def test_failed_and_unconfirmed_counts_are_independent_and_do_not_replace_controls(self):
        model = (CHAT / 'ChatScreenModels.kt').read_text()
        self.assertIn('val failedStepCount: Int get()', model)
        self.assertIn('val unconfirmedStepCount: Int get()', model)
        self.assertIn('segment.steps.count { it.state == WorkStepState.FAILED }', model)
        self.assertIn('segment.steps.count { it.state == WorkStepState.UNKNOWN && !(running && it.activity.inFlight) }', model)
        self.assertIn('if (failed > 0) add("${failed}步失败")', model)
        self.assertIn('if (unconfirmed > 0) add("${unconfirmed}步待确认")', model)
        self.assertIn('val status = controlActivity?.label ?: summary', model)
        mapper = (CHAT / 'ChatTurnMapper.kt').read_text()
        self.assertIn('message.status == RunStatus.FAILED -> "流程失败"', mapper)
        self.assertLess(mapper.index('message.status == RunStatus.STOPPED -> "已停止"'),
                        mapper.index('segments.any { segment -> segment.steps.any { it.state == WorkStepState.FAILED } } -> "失败"'))
        regression = (ROOT / 'app/src/test/kotlin/app/daiyu/android/ui/screens/chat/conversation/WorkProcessProgressTest.kt').read_text()
        for name in ('screenshotShapeCountsOnlyThreeClosedReasoningSteps',
                     'failedAndUnknownReceiptsRemainVisibleDuringAllControlStates',
                     'failedRunWithoutFailedToolIsNotInventedAsToolFailure',
                     'resultCountsAccumulateAcrossSegmentsRatherThanBecomingFlags'):
            self.assertIn('fun ' + name, regression)

    def test_handoff_waits_for_reveal_and_exit_before_releasing_successors(self):
        card = ' '.join((CHAT / 'ReportedReasoningCard.kt').read_text().split())
        self.assertIn('val handoffReady = stopped || !observedLive || !isThinking && settled && '
                      '(manualExpansion || visibility.isIdle && !visibility.currentState && !visibility.targetState)', card)
        self.assertIn('SideEffect { onHandoffReady(appearance?.settled != false && handoffReady) }', card)
        handoff = (CHAT / 'ReasoningHandoff.kt').read_text()
        self.assertIn('initial.filter { it.activeSinceNanos == null }.forEach { put(it.itemId, true) }', handoff)
        self.assertIn('fun ready(reported: ReportedReasoning): Boolean = completed[reported.itemId] == true', handoff)
        content = (CHAT / 'MessageContent.kt').read_text()
        self.assertIn('val visible = precedingReady', content)
        self.assertIn('if (visible && block is MessageContentUi.Reasoning) precedingReady = reasoningReady(block.reported)', content)
        work = ' '.join((CHAT / 'WorkProcessCard.kt').read_text().split())
        self.assertIn('val fullyClosed = visibility.isIdle && !visibility.currentState && !visibility.targetState', work)
        self.assertIn('val disclosureHandoffReady = reasoningReady && '
                      'fullyClosed', work)
        self.assertIn('val manuallyClosed = currentOverride == false && fullyClosed', work)
        host = (CHAT / 'ChatMessageList.kt').read_text()
        self.assertIn('reasoningReady = { stopped || handoff.ready(it) }', host)
        self.assertIn('onHandoffReady = { if (it) handoff.complete(reported) }', host)
        self.assertIn('onAnswerReleased = { if (reasoningReady && answerPending) onAnswerReleased() }', host)
        self.assertIn('ordered.body.isNotEmpty() && bodyVisible', host)

    def test_single_thought_host_and_entrances_keep_order_and_do_not_replay(self):
        ordered = (CHAT / 'AssistantWorkflowContent.kt').read_text()
        for token in ['content.indexOfLast { it is MessageContentUi.Reasoning || it is MessageContentUi.Tool }',
                      'val split = lastStep + 1',
                      'val leading = content.take(firstStep)', 'val steps = content.subList(firstStep, split)', 'content.drop(split)',
                      'val work = workProcess ?: reportedReasoning.filter',
                      'WorkProcessUi(reasoning.map { WorkSegmentUi(reasoning = it) }',
                      'if (item is MessageContentUi.Reasoning && !handoff.ready(item.reported)) break',
                      'is MessageContentUi.Reasoning -> "reasoning:${reported.itemId}"',
                      'is MessageContentUi.Tool -> "tool:${step.renderKey}"']:
            self.assertIn(token, ordered)
        entry = ' '.join((CHAT / 'WorkflowEntry.kt').read_text().split())
        for token in ['fun enter(key: String, animate: Boolean): Boolean = shown.add(key) && animate',
                      'fun suppress(keys: Collection<String>) { shown.addAll(keys) }',
                      'val enter = remember(key) { entrances.enter(key, animate && !stopped) }',
                      'val visibility = remember(key, stopped)', 'MutableTransitionState(!enter || stopped)',
                      'expandVertically(tween(220, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) + fadeIn(tween(220))',
                      'exit = ExitTransition.None']:
            self.assertIn(token, entry)
        host = (CHAT / 'ChatMessageList.kt').read_text()
        self.assertIn('val entrances = remember(turn.listKey) { WorkflowEntrances(visibleWorkflowKeys(ordered.steps, handoff)) }', host)
        self.assertLess(host.index('val entrances = remember(turn.listKey)'), host.index('work?.let { AssistantWorkflow'))
        self.assertIn('entrances.suppress(steps.mapIndexed { index, item -> item.workflowKey(index) })', host)
        self.assertIn('WorkflowEntry(key, entrances, animate = false,', host)
        self.assertIn('entryBody = { key, body -> WorkflowEntry(key, bodyEntrances, observedLive,', host)

if __name__ == '__main__':
    unittest.main()
