package app.daiyu.android.ui.screens.chat.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.daiyu.android.domain.ReportedReasoning
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Independent disclosure: collapse never hides or rewrites the answer, images or tool card. */
@Composable
internal fun ReportedReasoningCard(ownerKey: String, reported: List<ReportedReasoning>) {
    val visible = reported.filter { it.text.isNotBlank() }
    if (visible.isEmpty()) return
    var expanded by rememberSaveable(ownerKey) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("reported-reasoning:$ownerKey"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (expanded) "收起模型已报告的思考 / 摘要" else "展开模型已报告的思考 / 摘要",
            Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }.padding(vertical = 8.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.body2)
        if (expanded) {
            Text("仅包含供应商实际公开返回的内容；不代表隐藏内部推理。流中断时可能不完整。",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1)
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    visible.forEach { Text(it.text, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2) }
                }
            }
        }
    }
}
