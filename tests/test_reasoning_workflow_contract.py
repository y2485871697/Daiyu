from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CHAT = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/screens/chat/conversation'
DOMAIN = ROOT / 'core-domain/src/main/kotlin/app/daiyu/android/domain'

class ReasoningWorkflowContractTest(unittest.TestCase):
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
        self.assertIn('rememberMarkdownProjection(listOf(text), streaming = streamingProjection)', source)
        self.assertIn('generationComplete && projection != null && displayed == summary.text', source)
        self.assertIn('ReasoningDisclosureDurationMillis = 220', source)
        content = (CHAT / 'MessageContent.kt').read_text()
        self.assertIn('reasoningBody(block.reported,', content)
        self.assertEqual(1, content.count('dedicatedTool(block.step)'))
        self.assertIn('key(block.step.owner, block.step.toolCallId)', content)
        self.assertIn('val dedicated = dedicatedTool(block.step)', content)
        self.assertIn('if (visible && !dedicated) entryBody(block.workflowKey(index)) {', content)
        self.assertEqual(1, content.count('WorkStepRow('))
        self.assertLess(content.index('val dedicated = dedicatedTool(block.step)'),
                        content.index('if (visible && !dedicated) entryBody'))

    def test_top_card_is_not_duplicated_and_timing_has_exact_owner_gate(self):
        source = (CHAT / 'ChatMessageList.kt').read_text()
        self.assertNotIn('ReportedReasoningCard(', source)
        self.assertEqual(1, source.count('WorkProcessCard('))
        self.assertIn('val ordered = rememberAssistantWorkflowContent(turn)', source)
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
        self.assertIn('SideEffect { onHandoffReady(handoffReady) }', card)
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
        self.assertIn('onAnswerReleased = { if (reasoningReady && answerPending) answerReleased = true }', host)
        self.assertIn('ordered.body.isNotEmpty() && (answerReleased || canReleaseWithoutWorkflow)', host)

    def test_single_thought_host_and_entrances_keep_order_and_do_not_replay(self):
        ordered = (CHAT / 'AssistantWorkflowContent.kt').read_text()
        for token in ['content.indexOfLast { it is MessageContentUi.Reasoning || it is MessageContentUi.Tool }',
                      'val steps = content.take(lastStep + 1)', 'content.drop(lastStep + 1)',
                      'val work = workProcess ?: reportedReasoning.filter',
                      'WorkProcessUi(reasoning.map { WorkSegmentUi(reasoning = it) }',
                      'if (item is MessageContentUi.Reasoning && !handoff.ready(item.reported)) break',
                      'is MessageContentUi.Reasoning -> "reasoning:${reported.itemId}"',
                      'is MessageContentUi.Tool -> "tool:${step.toolCallId}"']:
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
        self.assertIn('if (!expanded) entrances.suppress(steps.mapIndexed { index, item -> item.workflowKey(index) })', host)
        self.assertIn('WorkflowEntry(key, entrances, animate = expanded && work.running,', host)
        self.assertIn('entryBody = { key, body -> WorkflowEntry(key, bodyEntrances, observedLive,', host)

if __name__ == '__main__':
    unittest.main()
