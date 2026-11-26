package app.daiyu.android.ui.screens.chat.conversation

import app.daiyu.android.ui.screens.settings.settingsDisclosureRotation
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun WorkProcessCard(work: WorkProcessUi, ownerKey: String, modifier: Modifier = Modifier, allowLiveTiming: Boolean = false, generationComplete: Boolean = !work.running, stopped: Boolean = false, revealResetEpoch: Long = 0L, onVisibleAdvance: () -> Unit = {}, handoff: ReasoningHandoff? = null, onDisclosureHandoffReady: (Boolean) -> Unit = {}, onExpandedChange: (Boolean) -> Unit = {}, answerPending: Boolean = false, workflowTextSettled: Boolean = true, content: (@Composable (Boolean) -> Unit)? = null, nodeMotions: WorkflowNodeMotions? = null, motionItems: List<MessageContentUi>? = null, toolContent: @Composable (WorkStepUi) -> Unit = {}) {
    val displayedWork = remember(work) { work.withResolvedTerminalCards() } ?: return
    val segments = displayedWork.segments
    val reasoning = segments.mapNotNull { it.reasoning }
    val visualScope = LocalWorkflowVisualScope.current
    val sequence = handoff ?: remember(ownerKey, visualScope) {
        visualScope?.handoff(ownerKey, reasoning) ?: ReasoningHandoff(reasoning)
    }
    val reasoningReady = stopped || sequence.ready(reasoning)
    val finishing = !work.running || answerPending
    val finished = finishing && reasoningReady && workflowTextSettled
    var expandedOverride by rememberSaveable(ownerKey, finishing, stopped) { mutableStateOf<Boolean?>(null) }
    var overrideFinished by rememberSaveable(ownerKey) { mutableStateOf(finishing) }
    var overrideStopped by rememberSaveable(ownerKey) { mutableStateOf(stopped) }
    val currentOverride = expandedOverride.takeIf { overrideFinished == finishing && overrideStopped == stopped }
    val expanded = currentOverride ?: (!stopped && !finished)
    val visibility = remember(ownerKey) { MutableTransitionState(expanded) }
    val detailExtent = remember(ownerKey) { WorkflowCardExtent(expanded) }
    visibility.targetState = detailExtent.keepsContent(expanded)
    val disclosure = rememberWorkflowDisclosureMotion(ownerKey, visibility, collapseUp = true,
        targetExpanded = expanded, animationIdle = detailExtent.idle(expanded),
        pendingCardHeight = remember(detailExtent) { detailExtent::pendingHeight })
    LaunchedEffect(expanded, if (expanded) detailExtent.fullHeight else 0) {
        detailExtent.animate(expanded, disclosure.closingDetailHeight, disclosure.upwards)
    }
    val fullyClosed = visibility.isIdle && !visibility.currentState && !visibility.targetState
    var contentMounted by remember(ownerKey) { mutableStateOf(false) }
    val paused = work.controlActivity == ToolActivityUi.PAUSED || work.controlActivity == ToolActivityUi.PAUSING
    val disclosureHandoffReady = reasoningReady && fullyClosed && !contentMounted && !(work.running && paused)
    androidx.compose.runtime.SideEffect { onDisclosureHandoffReady(disclosureHandoffReady); onExpandedChange(expanded) }
    val manuallyClosed = currentOverride == false && fullyClosed
    androidx.compose.runtime.SideEffect {
        if (stopped) reasoning.forEach(sequence::complete)
        else if (manuallyClosed) reasoning.filter { it.activeSinceNanos == null }.forEach(sequence::complete)
    }
    val reasoningState = rememberSaveableStateHolder()
    // Inner cards keep their disclosure in this holder only while the detail is shown. Once the whole
    // workflow has closed, reopening it starts every inner card from its default collapsed state.
    androidx.compose.runtime.SideEffect { if (fullyClosed && !contentMounted) reasoningState.removeState("ordered-content") }
    val items = motionItems ?: segments.flatMap { segment ->
        listOfNotNull(segment.reasoning?.let { MessageContentUi.Reasoning(it) }) + segment.steps.map { MessageContentUi.Tool(it) }
    }
    val motions = nodeMotions ?: remember(ownerKey) { WorkflowNodeMotions(items) }
    androidx.compose.runtime.SideEffect { if (!expanded || stopped) motions.suppress(items) }
    val colors = conversationColors()
    val headerHeight = remember(ownerKey) { androidx.compose.runtime.mutableIntStateOf(0) }
    val title = work.title
    val progressSummary = displayedWork.progressSummary
    CompositionLocalProvider(LocalWorkflowDisclosureParent provides disclosure,
        LocalWorkflowNodeMotions provides motions,
        LocalWorkflowNodeMotionEnabled provides (expanded && !stopped && work.controlActivity != ToolActivityUi.PAUSED && work.controlActivity != ToolActivityUi.PAUSING),
        LocalReasoningPaused provides (work.controlActivity == ToolActivityUi.PAUSED || work.controlActivity == ToolActivityUi.PAUSING)) {
    Column(modifier.fillMaxWidth().then(disclosure.containerModifier)
        .onGloballyPositioned {
            if (app.daiyu.android.diagnostics.ChatGeometryTrace.active) app.daiyu.android.diagnostics.ChatGeometryTrace.record("card",
                "bottom" to (it.positionInWindow().y + it.size.height), "height" to it.size.height)
        }
        .streamingWorkflowSurface(colors.surface, colors.line, 12.dp,
            if (colors.dark) Color(0x05FFFFFF) else Color(0x40FFFFFF), headerHeight = { headerHeight.intValue },
            diagnosticMotion = disclosure)
        .testTag("work-process-surface")) {
        BoxWithConstraints(Modifier.fillMaxWidth().onSizeChanged { headerHeight.intValue = it.height }) {
            val summaryWidth = maxWidth * .56f
            val stacked = maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.2f
            Row(Modifier.fillMaxWidth().testTag("work-process-header").workflowInkClearance().then(disclosure.headerModifier).heightIn(min = 44.dp).conversationClickable {
                disclosure.toggle(opening = !expanded)
                overrideFinished = finishing; overrideStopped = stopped; expandedOverride = !expanded
            }
                .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }
                .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(ToolOperationIcons.operation("chain"),
                    null, Modifier.size(18.dp).testTag("work-process-icon"), tint = colors.accent)
                Column(Modifier.weight(1f)) {
                    Text(title, Modifier.fillMaxWidth(), color = colors.text,
                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (stacked) Text(progressSummary, Modifier.fillMaxWidth(), color = colors.muted,
                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 17.sp))
                }
                if (!stacked) Text(progressSummary, Modifier.widthIn(max = summaryWidth),
                    color = colors.muted, style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 17.sp))
                Icon(DaiyuIcons.ChevronRight,
                    if (expanded) "收起$title" else "展开$title", Modifier.size(14.dp).settingsDisclosureRotation(expanded), tint = colors.muted)
            }
        }
        AnimatedVisibility(visibility, enter = fadeIn(), exit = ExitTransition.None) {
            DisposableEffect(ownerKey) {
                contentMounted = true
                onDispose { contentMounted = false }
            }
            Column(Modifier.clipToBounds().onGloballyPositioned { detailExtent.placed(it.size.height) }.layout { measurable, constraints ->
                val detail = measurable.measure(constraints)
                detailExtent.fullHeight = detail.height
                val height = detailExtent.measure(expanded, disclosure.closingDetailHeight)
                if (app.daiyu.android.diagnostics.ChatGeometryTrace.active) disclosure.trace("extent",
                    "full" to detail.height, "shown" to height, "offset" to detailExtent.contentOffset,
                    "expanded" to expanded, "closing_limit_px" to disclosure.closingDetailHeight)
                layout(detail.width, height) { detail.placeRelative(0, detailExtent.contentOffset) }
            }) {
                HorizontalDivider(thickness = 1.dp, color = colors.line)
                Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 3.dp, bottom = 4.dp)) {
                    if (content != null) reasoningState.SaveableStateProvider("ordered-content") { content(expanded) }
                    else {
                        var precedingReady = true
                        segments.forEachIndexed { segmentIndex, segment ->
                            if (!precedingReady) return@forEachIndexed
                            if (!segment.narration.isNullOrBlank()) {
                                val narrationConnector = segments.take(segmentIndex).any { it.steps.isNotEmpty() || it.reasoning != null } &&
                                    segments.drop(segmentIndex).any { it.steps.isNotEmpty() || it.reasoning != null }
                                Text(segment.narration, Modifier.fillMaxWidth().drawBehind {
                                    if (narrationConnector) drawLine(colors.line, Offset(5.dp.toPx(), 0f),
                                        Offset(5.dp.toPx(), size.height), 1.dp.toPx())
                                }.padding(start = if (narrationConnector) 18.dp else 0.dp), color = colors.muted,
                                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 14.sp, lineHeight = 22.sp))
                            }
                            segment.reasoning?.let { reported ->
                                key(ownerKey, "reasoning", reported.itemId) {
                                    reasoningState.SaveableStateProvider(reported.itemId) {
                                        WorkTimelineEntry(leadingConnector = segments.take(segmentIndex)
                                            .any { it.steps.isNotEmpty() || it.reasoning != null }) {
                                            ReasoningStepRow(ownerKey, reported,
                                                showConnector = segment.steps.isNotEmpty() || segments.drop(segmentIndex + 1)
                                                    .any { it.steps.isNotEmpty() || it.reasoning != null },
                                                allowLiveTiming = allowLiveTiming, generationComplete = generationComplete, stopped = stopped,
                                                revealResetEpoch = revealResetEpoch, parentExpanded = expanded,
                                                onVisibleAdvance = { if (expanded) onVisibleAdvance() },
                                                onHandoffReady = { if (it) sequence.complete(reported) })
                                        }
                                    }
                                }
                            }
                            if (!stopped && segment.reasoning?.let { !sequence.ready(it) } == true) precedingReady = false
                            if (precedingReady) segment.steps.forEachIndexed { index, step ->
                                key(ownerKey, step.renderKey) {
                                    val laterStep = index < segment.steps.lastIndex ||
                                        segments.drop(segmentIndex + 1).any { it.steps.isNotEmpty() || it.reasoning != null }
                                    WorkTimelineEntry(leadingConnector = index > 0 || segment.reasoning != null ||
                                        segments.take(segmentIndex).any { it.steps.isNotEmpty() || it.reasoning != null }) {
                                        WorkStepRow(step, showConnector = laterStep, toolContent = toolContent, flowRunning = work.running)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

/** A single compact timeline row shared by work summaries and ordered mixed content. */
internal val LocalToolCardPaintFeedback = staticCompositionLocalOf<((String, LayoutCoordinates) -> Boolean)?> { null }

private class ToolCardPaintFrame(var coordinates: LayoutCoordinates? = null)

@Composable
internal fun Modifier.toolCardPaintFeedback(step: WorkStepUi): Modifier {
    val scope = LocalWorkflowVisualScope.current ?: return this
    val report = LocalToolCardPaintFeedback.current ?: return this
    if (!scope.awaitsToolPaint(step.renderKey)) return this
    val frame = remember(scope, step.renderKey) { ToolCardPaintFrame() }
    return onPlaced { frame.coordinates = it }.drawWithContent {
        drawContent()
        if (scope.awaitsToolPaint(step.renderKey) && frame.coordinates?.let { report(step.toolCallId, it) } == true) {
            scope.toolPainted(step.renderKey)
        }
    }
}

@Composable
internal fun WorkStepRow(step: WorkStepUi, showConnector: Boolean = false, flowRunning: Boolean = false, toolContent: @Composable (WorkStepUi) -> Unit = {}) {
    if (!step.isVisibleInWorkflow) return
    val colors = conversationColors()
    val lineMotion = LocalWorkflowLineMotion.current
    val appearance = rememberWorkflowToolAppearance("tool:${step.renderKey}")
    var expanded by rememberSaveable(step.renderKey) { mutableStateOf(false) }
    val visibility = remember(step.renderKey) { MutableTransitionState(expanded) }
    visibility.targetState = expanded
    val disclosure = rememberWorkflowDisclosureMotion(step.renderKey, visibility)
    val activity = step.displayActivity
    val running = activity in setOf(ToolActivityUi.RUNNING, ToolActivityUi.PREPARING, ToolActivityUi.PAUSING, ToolActivityUi.PAUSED)
    val tint = when {
        step.state == WorkStepState.FAILED -> colors.error
        step.state == WorkStepState.OK -> colors.success
        running -> colors.accent
        activity in setOf(ToolActivityUi.WAITING, ToolActivityUi.UNKNOWN) -> colors.warning
        else -> colors.muted
    }
    val stateLabel = when (activity) {
        ToolActivityUi.STOPPING -> "所属运行停止中"
        ToolActivityUi.STOPPED -> "所属运行已停止"
        ToolActivityUi.INTERRUPTED -> "所属运行已中断"
        else -> activity.label
    }
    val disclosureLabel = if (expanded) "已展开" else "已折叠"
    Row(Modifier.fillMaxWidth().testTag("tool-step:${step.toolCallId}").then(disclosure.containerModifier).drawBehind {
        if (showConnector) drawLine(colors.line,
            Offset(5.dp.toPx(), 27.dp.toPx()), Offset(5.dp.toPx(), minOf(size.height,
                27.dp.toPx() + (size.height + lineMotion.spacingAfter.toPx() - 27.dp.toPx()) * (lineMotion.outgoing?.fraction() ?: 1f))), 1.dp.toPx())
    }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.padding(top = 17.dp).width(12.dp), contentAlignment = Alignment.TopStart) {
            val terminal = step.state == WorkStepState.OK || step.state == WorkStepState.FAILED
            val spinning = flowRunning && !terminal && LocalWorkflowNodeMotionEnabled.current && activity in setOf(ToolActivityUi.PENDING,
                ToolActivityUi.QUEUED, ToolActivityUi.PREPARING, ToolActivityUi.RUNNING, ToolActivityUi.WAITING,
                ToolActivityUi.PAUSING, ToolActivityUi.STOPPING)
            val ring = running && !terminal
            Box(Modifier.size(10.dp).workflowInkClearance().workflowCompletion("tool:${step.renderKey}", step.state == WorkStepState.OK,
                failed = step.state == WorkStepState.FAILED, appearance = appearance).clip(CircleShape).then(when {
                    spinning -> Modifier
                    ring -> Modifier.border(1.5.dp, tint, CircleShape)
                    else -> Modifier.background(tint)
                }), contentAlignment = Alignment.Center) {
                if (spinning) WorkflowWaitingIndicator("tool:${step.renderKey}", tint)
                else if (step.state == WorkStepState.OK) Icon(DaiyuIcons.Check, null, Modifier.size(8.dp), tint = Color.White)
                else if (step.state == WorkStepState.FAILED) Icon(DaiyuIcons.Close, "执行失败", Modifier.size(8.dp), tint = Color.White)
            }
        }
        Column(Modifier.weight(1f).testTag("tool-step-body:${step.toolCallId}").workflowToolAppearance(appearance)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stacked = maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.2f
                Row(Modifier.fillMaxWidth().testTag("tool-step-header:${step.toolCallId}").workflowInkClearance().then(disclosure.headerModifier).heightIn(min = 44.dp).conversationClickable {
                    disclosure.toggle(opening = !expanded); expanded = !expanded
                }
                    .semantics { stateDescription = if (stateLabel.isEmpty()) disclosureLabel else "$stateLabel · $disclosureLabel" }
                    .padding(vertical = 5.dp).toolCardPaintFeedback(step), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(ToolOperationIcons.operation(step.operationGlyph), null, Modifier.size(18.dp).testTag("tool-operation-icon:${step.toolCallId}"), tint = colors.accent)
                    Column(Modifier.weight(1f)) {
                        Text(toolHeaderText(step.name, step.category in setOf("terminal", "background_job")), color = colors.text,
                            inlineContent = toolHeaderInlineContent(colors.text),
                            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (stacked && stateLabel.isNotEmpty()) Text(stateLabel, Modifier.fillMaxWidth(), color = tint,
                            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 17.sp))
                    }
                    if (!stacked && stateLabel.isNotEmpty()) Text(stateLabel, Modifier.widthIn(max = 88.dp).width(IntrinsicSize.Max), color = tint,
                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 17.sp))
                    Icon(DaiyuIcons.ChevronRight,
                        if (expanded) "收起操作详情" else "展开操作详情", Modifier.size(14.dp).settingsDisclosureRotation(expanded), tint = colors.muted)
                }
            }
            WorkflowAnimatedDetail(visibility, disclosure, springMotion = true) {
                CompositionLocalProvider(LocalToolOutputContext provides ToolOutputContext(step.name, stateLabel)) {
                    Column(Modifier.padding(bottom = 4.dp).then(
                        if (step.categoryDetails != null) Modifier.testTag("tool-category:${step.category}") else Modifier
                    ), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        step.categoryDetails?.let { ToolCategoryContent(step.category, it) }
                        if (step.categoryDetails?.rich?.bodyRestricted != true) toolContent(step)
                        if (step.categoryDetails == null) {
                            step.sections.forEach { section -> ToolDetailSection(section, step.category in setOf("terminal", "background_job", "files")) }
                        }
                        if (step.categoryDetails == null && step.sections.isEmpty() && step.detail.isNotBlank()) ToolDetailSection(ToolDetailSectionUi("详情", step.detail), false)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolDetailSection(section: ToolDetailSectionUi, code: Boolean) =
    ToolDocument(ToolDisclosureUi(section.title, section.text, code))
