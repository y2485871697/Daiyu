package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun WorkProcessCard(work: WorkProcessUi, ownerKey: String, modifier: Modifier = Modifier, toolContent: @Composable (WorkStepUi) -> Unit = {}) {
    var expanded by rememberSaveable(ownerKey) { mutableStateOf(false) }
    val colors = conversationColors()
    val steps = work.segments.flatMap { it.steps }
    val stepCount = steps.size
    val category = steps.map { it.category }.distinct().singleOrNull()
    val title = ToolCardPresentations.processTitle(steps.map { it.category })
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(colors.surface).drawWithContent {
        drawContent()
        drawLine(if (colors.dark) Color(0x05FFFFFF) else Color(0x40FFFFFF),
            Offset(0f, .5.dp.toPx()), Offset(size.width, .5.dp.toPx()), 1.dp.toPx())
    }.border(1.dp, colors.line, shape).testTag("work-process-surface")) {
        Row(Modifier.fillMaxWidth().testTag("work-process-header").heightIn(min = 48.dp).conversationClickable { expanded = !expanded }
            .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }
            .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(ToolOperationIcons.category(category),
                null, Modifier.size(22.dp).testTag("work-process-icon"), tint = colors.accent)
            Text(title, Modifier.weight(1f), color = colors.text,
                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("$stepCount 项 · ${work.summary}", Modifier.widthIn(max = 140.dp),
                color = colors.muted, style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 18.sp),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(if (expanded) DaiyuIcons.ChevronDown else DaiyuIcons.ChevronRight,
                if (expanded) "收起$title" else "展开$title", Modifier.size(16.dp), tint = colors.muted)
        }
        AnimatedVisibility(expanded) {
            Column {
                HorizontalDivider(thickness = 1.dp, color = colors.line)
                Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 5.dp, bottom = 6.dp)) {
                    work.segments.forEachIndexed { segmentIndex, segment ->
                        if (!segment.narration.isNullOrBlank()) {
                            val narrationConnector = work.segments.take(segmentIndex).any { it.steps.isNotEmpty() } &&
                                work.segments.drop(segmentIndex).any { it.steps.isNotEmpty() }
                            Text(segment.narration, Modifier.fillMaxWidth().drawBehind {
                                if (narrationConnector) drawLine(colors.line, Offset(6.dp.toPx(), 0f),
                                    Offset(6.dp.toPx(), size.height + 5.dp.toPx()), 1.dp.toPx())
                            }.padding(start = if (narrationConnector) 22.dp else 0.dp), color = colors.muted,
                                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 14.sp, lineHeight = 22.sp))
                        }
                        segment.steps.forEachIndexed { index, step ->
                            key(ownerKey, step.owner, step.toolCallId) {
                                val laterStep = index < segment.steps.lastIndex ||
                                    work.segments.drop(segmentIndex + 1).any { it.steps.isNotEmpty() }
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
    var technical by rememberSaveable(step.owner, step.toolCallId) { mutableStateOf(false) }
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
            Offset(6.dp.toPx(), 30.dp.toPx()), Offset(6.dp.toPx(), size.height + 5.dp.toPx()), 1.dp.toPx())
    }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.padding(top = 18.dp).width(14.dp), contentAlignment = Alignment.TopStart) {
            val ring = running && step.state != WorkStepState.OK && step.state != WorkStepState.FAILED
            Box(Modifier.size(12.dp).clip(CircleShape).then(
                if (ring) Modifier.border(2.dp, tint, CircleShape) else Modifier.background(tint)),
                contentAlignment = Alignment.Center) {
                if (step.state == WorkStepState.OK) Icon(DaiyuIcons.Check, null, Modifier.size(10.dp), tint = Color.White)
                else if (step.state == WorkStepState.FAILED) Text("!", color = Color.White,
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 10.sp, lineHeight = 12.sp))
            }
        }
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth().testTag("tool-step-header:${step.toolCallId}").heightIn(min = 48.dp).conversationClickable { expanded = !expanded }
                .semantics { stateDescription = "$stateLabel · ${if (expanded) "已展开" else "已折叠"}" }
                .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(ToolOperationIcons.operation(step.operationGlyph), null, Modifier.size(20.dp).testTag("tool-operation-icon:${step.toolCallId}"), tint = colors.accent)
                Text(step.name, Modifier.weight(1f), color = colors.text,
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stateLabel, Modifier.widthIn(max = 88.dp), color = tint,
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 18.sp))
                Icon(if (expanded) DaiyuIcons.ChevronDown else DaiyuIcons.ChevronRight,
                    if (expanded) "收起操作详情" else "展开操作详情", Modifier.size(16.dp), tint = colors.muted)
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
                        val inspect = step.categoryDetails?.rich?.technical.orEmpty()
                        if (inspect.isNotEmpty()) {
                            Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                                HorizontalDivider(thickness = 1.dp, color = colors.line)
                                ToolDisclosureHeader("技术详情", technical,
                                    Modifier.testTag("tool-step-technical:${step.toolCallId}"), compact = true) { technical = !technical }
                                if (technical) Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    inspect.forEach { ToolDocument(it) }
                                }
                            }
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
