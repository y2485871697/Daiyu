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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.icons.daiyuIconColor
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun WorkProcessCard(work: WorkProcessUi, ownerKey: String, onCopy: ((WorkStepUi) -> Unit)? = null, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(ownerKey) { mutableStateOf(false) }
    val colors = MiuixTheme.colorScheme
    val stepCount = work.segments.sumOf { it.steps.size }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .border(0.5.dp, colors.onSurfaceVariantSummary.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
        .background(colors.surfaceVariant.copy(alpha = 0.35f))) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }
            .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(6.dp).clip(CircleShape)
                .background(if (work.running) colors.primary else colors.onSurfaceVariantSummary))
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
                            WorkStepRow(step, onCopy, showConnector = index < segment.steps.lastIndex)
                        }
                    }
                }
            }
        }
    }
}

/** A single compact timeline row shared by work summaries and ordered mixed content. */
@Composable
internal fun WorkStepRow(step: WorkStepUi, onCopy: ((WorkStepUi) -> Unit)? = null, showConnector: Boolean = false) {
    val colors = MiuixTheme.colorScheme
    var expanded by rememberSaveable(step.owner, step.toolCallId) { mutableStateOf(false) }
    val tint = when (step.state) {
        WorkStepState.FAILED -> colors.error
        WorkStepState.OK -> daiyuIconColor(DaiyuIcons.Chart)
        else -> colors.onSurfaceVariantSummary
    }
    val stateLabel = when (step.state) {
        WorkStepState.OK -> "成功"
        WorkStepState.FAILED -> "失败"
        WorkStepState.PENDING -> "待定"
        WorkStepState.UNKNOWN -> "结果未确认"
        WorkStepState.NOT_EXECUTED -> "未执行"
    }
    Row(Modifier.fillMaxWidth().testTag("tool-step:${step.toolCallId}"), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Column(Modifier.padding(top = 9.dp).width(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(tint))
            if (showConnector) Box(Modifier.padding(top = 4.dp).width(1.dp).heightIn(min = 22.dp)
                .background(colors.onSurfaceVariantSummary.copy(alpha = 0.16f)))
        }
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }
                .padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(toolCategoryIcon(step.category), null, Modifier.size(14.dp), tint = colors.onSurfaceVariantSummary)
                Text(step.name, Modifier.weight(1f), color = colors.onSurface,
                    style = MiuixTheme.textStyles.footnote1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stateLabel, color = tint, style = MiuixTheme.textStyles.footnote1, maxLines = 1)
                Icon(if (expanded) DaiyuIcons.ChevronDown else DaiyuIcons.ChevronRight,
                    if (expanded) "收起操作详情" else "展开操作详情", Modifier.size(14.dp), tint = colors.onSurfaceVariantSummary)
            }
            if (step.detail.isNotBlank()) Text(step.detail, color = colors.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 6.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    step.sections.forEach { section -> ToolDetailSection(section, step.category in setOf("terminal", "background_job", "files")) }
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
private fun ToolDetailSection(section: ToolDetailSectionUi, code: Boolean) {
    val colors = MiuixTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(section.title, color = colors.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1)
        Text(section.text, Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp))
            .background(colors.surfaceVariant.copy(alpha = 0.45f)).padding(6.dp),
            color = colors.onSurfaceVariantSummary,
            style = if (code) MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace) else MiuixTheme.textStyles.footnote1,
            maxLines = 12, overflow = TextOverflow.Ellipsis)
    }
}

private fun toolCategoryIcon(category: String) = when (category) {
    "memory", "context" -> DaiyuIcons.Context
    "skills" -> DaiyuIcons.Skills
    "delegation", "child_control" -> DaiyuIcons.SubAgent
    "workspace", "files" -> DaiyuIcons.Folder
    "terminal", "background_job" -> DaiyuIcons.Terminal
    "image_read", "generation" -> DaiyuIcons.Image
    "browser", "mcp" -> DaiyuIcons.Browser
    "gui" -> DaiyuIcons.Accessibility
    "apps", "device_read", "system_action" -> DaiyuIcons.Device
    "personal_search" -> DaiyuIcons.Search
    "speech" -> DaiyuIcons.Voice
    "user_question" -> DaiyuIcons.Interactive
    else -> DaiyuIcons.Tool
}
