package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun WorkProcessCard(work: WorkProcessUi, onRetryStep: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val colors = MiuixTheme.colorScheme
    val stepCount = work.segments.sumOf { it.steps.size }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainer)
        .border(1.dp, colors.outline, RoundedCornerShape(16.dp))) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }
            .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape)
                .background(if (work.running) colors.primary else colors.onSurfaceVariantSummary))
            Text("工作过程", color = colors.onSurface, style = MiuixTheme.textStyles.body2,
                maxLines = 1)
            Text("${work.segments.size} 段 · $stepCount 步 · ${work.summary}", Modifier.weight(1f),
                color = colors.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(if (expanded) DaiyuIcons.ChevronDown else DaiyuIcons.ChevronRight,
                if (expanded) "收起工作过程" else "展开工作过程", Modifier.size(18.dp), tint = colors.onSurfaceVariantSummary)
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                work.segments.forEach { segment ->
                    if (!segment.narration.isNullOrBlank()) {
                        Text(segment.narration, color = colors.onSurfaceVariantSummary, style = MiuixTheme.textStyles.body2)
                    }
                    segment.steps.forEach { step -> WorkStepRow(step, onRetryStep) }
                }
            }
        }
    }
}

@Composable
internal fun WorkStepRow(step: WorkStepUi, onRetry: (String) -> Unit) {
    val colors = MiuixTheme.colorScheme
    val tint = when (step.state) {
        WorkStepState.FAILED -> colors.error
        WorkStepState.OK -> colors.primary
        WorkStepState.PENDING -> colors.onSurfaceVariantSummary
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(when (step.state) {
                WorkStepState.OK -> DaiyuIcons.Check
                WorkStepState.FAILED -> DaiyuIcons.Error
                WorkStepState.PENDING -> DaiyuIcons.Tool
            }, when (step.state) {
                WorkStepState.OK -> "完成"
                WorkStepState.FAILED -> "失败"
                WorkStepState.PENDING -> "进行中或待定"
            }, Modifier.size(18.dp), tint = tint)
            Box(Modifier.padding(top = 4.dp).width(1.dp).heightIn(min = 24.dp).background(colors.onSurfaceVariantSummary.copy(alpha = 0.2f)))
        }
        Column(Modifier.weight(1f)) {
            Text(step.name, color = tint, style = MiuixTheme.textStyles.body2)
            if (step.detail.isNotBlank()) Text(step.detail, color = colors.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1, maxLines = 8, overflow = TextOverflow.Ellipsis)
        }
        if (step.state == WorkStepState.FAILED) {
            ChatIconButton(DaiyuIcons.Retry, "重试 ${step.name}", { onRetry(step.id) }, tint = colors.error)
        }
    }
}
