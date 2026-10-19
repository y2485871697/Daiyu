package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Both timeline and mixed content render this same inert, typed presentation. */
@Composable
internal fun ToolCategoryContent(category: String, details: ToolCategoryDetailsUi) {
    val colors = MiuixTheme.colorScheme
    Column(Modifier.fillMaxWidth().testTag("tool-category:$category"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(details.heading, style = MiuixTheme.textStyles.body2, color = colors.onSurface)
        when (category) {
            "terminal", "background_job" -> RuntimeDetails(details)
            "delegation", "child_control" -> TaskDetails(details)
            "memory", "skills", "files", "workspace", "personal_search", "apps" -> ResourceDetails(details)
            "browser", "gui" -> ObservationDetails(details)
            "image_read", "generation", "speech" -> MediaDetails(details)
            "device_read", "system_action", "context" -> MetricDetails(details.fields)
            else -> ResourceDetails(details)
        }
        Text(details.notice, style = MiuixTheme.textStyles.footnote1, color = colors.onSurfaceVariantSummary)
    }
}

@Composable
private fun MetricDetails(fields: List<ToolFieldUi>) {
    val colors = MiuixTheme.colorScheme
    if (fields.isEmpty()) Text("未提供可展示的结构化字段", style = MiuixTheme.textStyles.footnote1, color = colors.onSurfaceVariantSummary)
    fields.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            pair.forEach { field ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(colors.surfaceVariant.copy(alpha = .4f))
                    .padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(field.label, style = MiuixTheme.textStyles.footnote1, color = colors.onSurfaceVariantSummary)
                    Text(field.value, style = MiuixTheme.textStyles.body2, color = colors.onSurface)
                }
            }
        }
    }
}

@Composable
private fun ResourceDetails(details: ToolCategoryDetailsUi) {
    val colors = MiuixTheme.colorScheme
    Column(Modifier.fillMaxWidth().border(.5.dp, colors.outline, RoundedCornerShape(8.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (details.fields.isEmpty()) Text("当前记录没有可公开的条目元数据", style = MiuixTheme.textStyles.footnote1, color = colors.onSurfaceVariantSummary)
        details.fields.forEach { FieldLine(it) }
    }
}

@Composable
private fun RuntimeDetails(details: ToolCategoryDetailsUi) {
    val colors = MiuixTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.surfaceVariant.copy(alpha = .6f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (details.fields.isEmpty()) Text("尚无运行元数据", color = colors.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1)
        details.fields.forEach { field ->
            Text("${field.label}：${field.value}", color = colors.onSurface,
                style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace))
        }
    }
}

@Composable
private fun TaskDetails(details: ToolCategoryDetailsUi) {
    val colors = MiuixTheme.colorScheme
    MetricDetails(details.fields)
    details.items.forEach { task ->
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.surfaceVariant.copy(alpha = .35f))
            .padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(DaiyuIcons.SubAgent, null, Modifier.size(16.dp), tint = colors.primary)
                Text(task.title, style = MiuixTheme.textStyles.body2, color = colors.onSurface)
            }
            task.fields.forEach { FieldLine(it) }
        }
    }
}

@Composable
private fun ObservationDetails(details: ToolCategoryDetailsUi) {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.surfaceVariant.copy(alpha = .3f)).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(DaiyuIcons.Accessibility, null, Modifier.size(20.dp), tint = colors.primary)
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (details.fields.isEmpty()) Text("没有可展示的观察元数据", style = MiuixTheme.textStyles.footnote1, color = colors.onSurfaceVariantSummary)
            details.fields.forEach { FieldLine(it) }
        }
    }
}

@Composable
private fun MediaDetails(details: ToolCategoryDetailsUi) {
    MetricDetails(details.fields)
    // Actual images, video and speech controls remain owned by their existing trusted hosts.
}

@Composable
private fun FieldLine(field: ToolFieldUi) {
    Text("${field.label}：${field.value}", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}
