package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.domain.ReportedReasoning
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Independent disclosure: collapse never hides or rewrites the answer, images or tool card. */
@Composable
internal fun ReportedReasoningCard(ownerKey: String, reported: List<ReportedReasoning>) {
    val visible = reported.filter { it.text.isNotBlank() }
    if (visible.isEmpty()) return
    val colors = conversationColors()
    val shape = RoundedCornerShape(14.dp)
    var expanded by rememberSaveable(ownerKey) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("reported-reasoning:$ownerKey")
        .clip(shape).border(1.dp, colors.line, shape).background(colors.surface)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 35.dp)
            .conversationClickable { expanded = !expanded }
            .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }
            .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(Icons.Rounded.Psychology, null, Modifier.size(14.dp), tint = colors.accent)
            Text("思考摘要", Modifier.weight(1f), color = colors.accent,
                style = MiuixTheme.textStyles.body2.copy(fontSize = 13.sp, lineHeight = 18.sp))
            // ReportedReasoning does not supply elapsed time; do not manufacture a duration.
            Text("模型已报告", color = colors.muted,
                style = MiuixTheme.textStyles.footnote1.copy(fontSize = 11.sp, lineHeight = 16.sp))
            Icon(if (expanded) DaiyuIcons.ChevronDown else DaiyuIcons.ChevronRight,
                if (expanded) "收起模型已报告的思考 / 摘要" else "展开模型已报告的思考 / 摘要",
                Modifier.size(10.dp), tint = colors.muted)
        }
        if (expanded) {
            HorizontalDivider(thickness = 1.dp, color = colors.line)
            Column(Modifier.padding(start = 11.dp, end = 11.dp, top = 8.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("仅包含供应商实际公开返回的内容；不代表隐藏内部推理。流中断时可能不完整。",
                    color = colors.muted,
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp, lineHeight = 18.sp))
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        visible.forEach { Text(it.text, color = colors.muted,
                            style = MiuixTheme.textStyles.body2.copy(fontSize = 16.sp, lineHeight = 26.sp)) }
                    }
                }
            }
        }
    }
}
