package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
internal fun WorkProcessCard(work: WorkProcessUi, ownerKey: String, modifier: Modifier = Modifier, allowLiveTiming: Boolean = false, generationComplete: Boolean = !work.running, toolContent: @Composable (WorkStepUi) -> Unit = {}) {
    var expanded by rememberSaveable(ownerKey) { mutableStateOf(false) }
    val reasoningState = rememberSaveableStateHolder()
    val colors = conversationColors()
    val steps = work.segments.flatMap { it.steps }
    val stepCount = steps.size + work.segments.count { it.reasoning != null }
    val category = steps.map { it.category }.distinct().singleOrNull()
    val title = ToolCardPresentations.processTitleForSteps(steps)
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(colors.surface).drawWithContent {
        drawContent()
        drawLine(if (colors.dark) Color(0x05FFFFFF) else Color(0x40FFFFFF),
            Offset(0f, .5.dp.toPx()), Offset(size.width, .5.dp.toPx()), 1.dp.toPx())
    }.border(1.dp, colors.line, shape).testTag("work-process-surface")) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val summaryWidth = maxWidth * .38f
            val stacked = maxWidth < 300.dp || LocalDensity.current.fontScale >= 1.2f
            Row(Modifier.fillMaxWidth().testTag("work-process-header").heightIn(min = 44.dp).conversationClickable { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }
                .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(ToolOperationIcons.category(category),
                    null, Modifier.size(18.dp).testTag("work-process-icon"), tint = colors.accent)
                Column(Modifier.weight(1f)) {
                    Text(title, color = colors.text,
                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (stacked) Text("$stepCount 项 · ${work.summary}", color = colors.muted,
                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 17.sp))
                }
                if (!stacked) Text("$stepCount 项 · ${work.summary}", Modifier.widthIn(max = summaryWidth),
                    color = colors.muted, style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 17.sp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(if (expanded) DaiyuIcons.ChevronDown else DaiyuIcons.ChevronRight,
                    if (expanded) "收起$title" else "展开$title", Modifier.size(14.dp), tint = colors.muted)
            }
        }
        AnimatedVisibility(expanded) {
            Column {
                HorizontalDivider(thickness = 1.dp, color = colors.line)
                Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 3.dp, bottom = 4.dp)) {
                    work.segments.forEachIndexed { segmentIndex, segment ->
                        if (!segment.narration.isNullOrBlank()) {
                            val narrationConnector = work.segments.take(segmentIndex).any { it.steps.isNotEmpty() || it.reasoning != null } &&
                                work.segments.drop(segmentIndex).any { it.steps.isNotEmpty() || it.reasoning != null }
                            Text(segment.narration, Modifier.fillMaxWidth().drawBehind {
                                if (narrationConnector) drawLine(colors.line, Offset(5.dp.toPx(), 0f),
                                    Offset(5.dp.toPx(), size.height + 3.dp.toPx()), 1.dp.toPx())
                            }.padding(start = if (narrationConnector) 18.dp else 0.dp), color = colors.muted,
                                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 14.sp, lineHeight = 22.sp))
                        }
                        segment.reasoning?.let { reported ->
                            key(ownerKey, "reasoning", reported.itemId) {
                                reasoningState.SaveableStateProvider(reported.itemId) {
                                    ReasoningStepRow(ownerKey, reported,
                                        showConnector = segment.steps.isNotEmpty() || work.segments.drop(segmentIndex + 1)
                                            .any { it.steps.isNotEmpty() || it.reasoning != null },
                                        allowLiveTiming = allowLiveTiming, generationComplete = generationComplete)
                                }
                            }
                        }
                        segment.steps.forEachIndexed { index, step ->
                            key(ownerKey, step.owner, step.toolCallId) {
                                val laterStep = index < segment.steps.lastIndex ||
                                    work.segments.drop(segmentIndex + 1).any { it.steps.isNotEmpty() || it.reasoning != null }
                                WorkStepRow(step, showConnector = laterStep, toolContent = toolContent)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A single compact timeline row shared by work summaries and ordered mixed content. */
@Composable
internal fun WorkStepRow(step: WorkStepUi, showConnector: Boolean = false, toolContent: @Composable (WorkStepUi) -> Unit = {}) {
    val colors = conversationColors()
    var expanded by rememberSaveable(step.owner, step.toolCallId) { mutableStateOf(false) }
    val running = step.activity in setOf(ToolActivityUi.RUNNING, ToolActivityUi.PREPARING, ToolActivityUi.PAUSING, ToolActivityUi.PAUSED)
    val tint = when {
        step.state == WorkStepState.FAILED -> colors.error
        step.state == WorkStepState.OK -> colors.success
        running -> colors.accent
        step.activity in setOf(ToolActivityUi.WAITING, ToolActivityUi.UNKNOWN) -> colors.warning
        else -> colors.muted
    }
    val stateLabel = when (step.activity) {
        ToolActivityUi.STOPPING -> "所属运行停止中"
        ToolActivityUi.STOPPED -> "所属运行已停止"
        ToolActivityUi.INTERRUPTED -> "所属运行已中断"
        else -> step.activity.label
    }
    Row(Modifier.fillMaxWidth().testTag("tool-step:${step.toolCallId}").drawBehind {
        // Draw against the measured row, including its expanded body, rather than a fixed-height stub.
        if (showConnector) drawLine(colors.line,
            Offset(5.dp.toPx(), 27.dp.toPx()), Offset(5.dp.toPx(), size.height + 3.dp.toPx()), 1.dp.toPx())
    }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.padding(top = 17.dp).width(12.dp), contentAlignment = Alignment.TopStart) {
            val ring = running && step.state != WorkStepState.OK && step.state != WorkStepState.FAILED
            Box(Modifier.size(10.dp).clip(CircleShape).then(
                if (ring) Modifier.border(1.5.dp, tint, CircleShape) else Modifier.background(tint)),
                contentAlignment = Alignment.Center) {
                if (step.state == WorkStepState.OK) Icon(DaiyuIcons.Check, null, Modifier.size(8.dp), tint = Color.White)
                else if (step.state == WorkStepState.FAILED) Text("!", color = Color.White,
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 9.sp, lineHeight = 10.sp))
            }
        }
        Column(Modifier.weight(1f)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stacked = maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.2f
                Row(Modifier.fillMaxWidth().testTag("tool-step-header:${step.toolCallId}").heightIn(min = 44.dp).conversationClickable { expanded = !expanded }
                    .semantics { stateDescription = "$stateLabel · ${if (expanded) "已展开" else "已折叠"}" }
                    .padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(ToolOperationIcons.operation(step.operationGlyph), null, Modifier.size(18.dp).testTag("tool-operation-icon:${step.toolCallId}"), tint = colors.accent)
                    Column(Modifier.weight(1f)) {
                        Text(step.name, color = colors.text,
                            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (stacked) Text(stateLabel, Modifier.fillMaxWidth(), color = tint,
                            style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 17.sp))
                    }
                    if (!stacked) Text(stateLabel, Modifier.widthIn(max = 88.dp).width(IntrinsicSize.Max), color = tint,
                        style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 17.sp))
                    Icon(if (expanded) DaiyuIcons.ChevronDown else DaiyuIcons.ChevronRight,
                        if (expanded) "收起操作详情" else "展开操作详情", Modifier.size(14.dp), tint = colors.muted)
                }
            }
            if (step.activity in setOf(ToolActivityUi.STOPPING, ToolActivityUi.STOPPED, ToolActivityUi.INTERRUPTED) &&
                step.state in setOf(WorkStepState.PENDING, WorkStepState.UNKNOWN)) Text("工具回执未确认 · 不重放", color = colors.muted,
                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 18.sp))
            AnimatedVisibility(expanded) {
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
