package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.icons.daiyuIconColor
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun WorkProcessCard(work: WorkProcessUi, ownerKey: String, onCopy: ((WorkStepUi) -> Unit)? = null, modifier: Modifier = Modifier, toolContent: @Composable (WorkStepUi) -> Unit = {}) {
    var expanded by rememberSaveable(ownerKey) { mutableStateOf(false) }
    val colors = MiuixTheme.colorScheme
    val stepCount = work.segments.sumOf { it.steps.size }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .border(0.5.dp, colors.onSurfaceVariantSummary.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
        .background(colors.surfaceVariant.copy(alpha = 0.35f))) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button) { expanded = !expanded }
            .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(ToolOperationIcons.category(work.segments.flatMap { it.steps }.map { it.category }.distinct().singleOrNull()),
                null, Modifier.size(16.dp), tint = colors.onSurfaceVariantSummary)
            Text("工作过程", color = colors.onSurface, style = MiuixTheme.textStyles.footnote1, maxLines = 1)
            Text("$stepCount 步 · ${work.summary}", Modifier.weight(1f),
                color = colors.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(if (expanded) DaiyuIcons.ChevronDown else DaiyuIcons.ChevronRight,
                if (expanded) "收起工作过程" else "展开工作过程", Modifier.size(16.dp), tint = colors.onSurfaceVariantSummary)
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                work.segments.forEach { segment ->
                    if (!segment.narration.isNullOrBlank()) {
                        Text(segment.narration, color = colors.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1)
                    }
                    segment.steps.forEachIndexed { index, step ->
                        key(ownerKey, step.owner, step.toolCallId) {
                            WorkStepRow(step, onCopy, showConnector = index < segment.steps.lastIndex, toolContent = toolContent)
                        }
                    }
                }
            }
        }
    }
}

/** A single compact timeline row shared by work summaries and ordered mixed content. */
@Composable
internal fun WorkStepRow(step: WorkStepUi, onCopy: ((WorkStepUi) -> Unit)? = null, showConnector: Boolean = false, toolContent: @Composable (WorkStepUi) -> Unit = {}) {
    val colors = MiuixTheme.colorScheme
    var expanded by rememberSaveable(step.owner, step.toolCallId) { mutableStateOf(false) }
    var technical by rememberSaveable(step.owner, step.toolCallId) { mutableStateOf(false) }
    val tint = when (step.state) {
        WorkStepState.FAILED -> colors.error
        WorkStepState.OK -> daiyuIconColor(DaiyuIcons.Chart)
        else -> when (step.activity) {
            ToolActivityUi.RUNNING, ToolActivityUi.PREPARING, ToolActivityUi.PAUSING, ToolActivityUi.PAUSED -> colors.primary
            ToolActivityUi.WAITING, ToolActivityUi.UNKNOWN -> daiyuIconColor(DaiyuIcons.Folder)
            else -> colors.onSurfaceVariantSummary
        }
    }
    val stateLabel = when (step.activity) {
        ToolActivityUi.STOPPING -> "所属运行停止中"
        ToolActivityUi.STOPPED -> "所属运行已停止"
        ToolActivityUi.INTERRUPTED -> "所属运行已中断"
        else -> step.activity.label
    }
    Row(Modifier.fillMaxWidth().testTag("tool-step:${step.toolCallId}"), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Column(Modifier.padding(top = 9.dp).width(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(tint))
            if (showConnector) Box(Modifier.padding(top = 4.dp).width(1.dp).heightIn(min = 22.dp)
                .background(colors.onSurfaceVariantSummary.copy(alpha = 0.16f)))
        }
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = "$stateLabel · ${if (expanded) "已展开" else "已折叠"}" }
                .padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(ToolOperationIcons.operation(step.operationGlyph), null, Modifier.size(14.dp), tint = colors.onSurfaceVariantSummary)
                Text(step.name, Modifier.weight(1f), color = colors.onSurface,
                    style = MiuixTheme.textStyles.footnote1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stateLabel, color = tint, style = MiuixTheme.textStyles.footnote1, maxLines = 1)
                Icon(if (expanded) DaiyuIcons.ChevronDown else DaiyuIcons.ChevronRight,
                    if (expanded) "收起操作详情" else "展开操作详情", Modifier.size(14.dp), tint = colors.onSurfaceVariantSummary)
            }
            if (step.activity in setOf(ToolActivityUi.STOPPING, ToolActivityUi.STOPPED, ToolActivityUi.INTERRUPTED) &&
                step.state in setOf(WorkStepState.PENDING, WorkStepState.UNKNOWN)) Text("工具回执未确认 · 不重放", color = colors.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1)
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 6.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    step.categoryDetails?.let { ToolCategoryContent(step.category, it) }
                    toolContent(step)
                    if (step.categoryDetails == null) {
                        step.sections.forEach { section -> ToolDetailSection(section, step.category in setOf("terminal", "background_job", "files")) }
                    }
                    val inspect = step.categoryDetails?.rich?.technical.orEmpty()
                    if (inspect.isNotEmpty()) {
                        Text(if (technical) "收起技术详情" else "技术详情", Modifier.heightIn(min = 44.dp)
                            .clickable(role = Role.Button) { technical = !technical }.padding(vertical = 6.dp),
                            style = MiuixTheme.textStyles.footnote1, color = colors.primary)
                        if (technical) inspect.forEach { ToolDocument(it) }
                    }
                    onCopy?.let { copy ->
                        ChatIconButton(DaiyuIcons.Copy, "复制安全摘要", { copy(step) })
                    }
                    if (step.sections.isEmpty() && step.detail.isNotBlank()) ToolDetailSection(ToolDetailSectionUi("详情", step.detail), false)
                }
            }
        }
    }
}

@Composable
private fun ToolDetailSection(section: ToolDetailSectionUi, code: Boolean) =
    ToolDocument(ToolDisclosureUi(section.title, section.text, code))
