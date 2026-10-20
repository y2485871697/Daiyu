package app.daiyu.android.ui.screens.chat.context

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.ui.components.UsageBody as Text
import app.daiyu.android.ui.components.UsageGlassDialog
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.screens.assistants.AssistantPreviewButton
import app.daiyu.android.ui.screens.assistants.AssistantPreviewSwitch
import app.daiyu.android.ui.screens.assistants.assistantPreviewPalette

internal data class CompressionDialogDraft(
    val customModel: Boolean,
    val modelLabel: String?,
    val modelKey: String?,
    val protocol: ProviderProtocol,
)

@Composable
internal fun CompressionConversationDialog(
    draft: CompressionDialogDraft,
    onChange: (CompressionDialogDraft) -> Unit,
    onChooseModel: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
    notice: String? = null,
    onManage: (() -> Unit)? = null,
) {
    val colors = assistantPreviewPalette()
    var showHelp by remember { mutableStateOf(false) }
    UsageGlassDialog(title = "压缩会话", onDismiss = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 430.dp)
            .testTag("context-management"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("custom-model") {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("自定义压缩模型", fontSize = 14.sp)
                    AssistantPreviewSwitch(draft.customModel, enabled, "",
                        Modifier.testTag("compression-dialog-custom").semantics { contentDescription = "自定义压缩模型" },
                        onToggle = { onChange(draft.copy(customModel = it)) })
                }
            }
            if (draft.customModel) item("model") {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("compression-dialog-model")
                    .clip(RoundedCornerShape(12.dp)).background(colors.fieldBackground)
                    .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClick = onChooseModel).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(draft.modelLabel ?: "选择模型", Modifier.weight(1f), fontSize = 14.sp,
                        maxLines = 2)
                    Icon(DaiyuIcons.ChevronRight, null, Modifier.size(18.dp), tint = colors.muted)
                }
            }
            item("protocol-title") {
                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("请求接口", fontSize = 14.sp)
                    Text("仅用于摘要，不影响正常对话。", color = colors.muted, fontSize = 12.sp)
                }
            }
            item("protocols") {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.soft)) {
                    listOf(ProviderProtocol.CHAT_COMPLETIONS to "Chat Completions API",
                        ProviderProtocol.RESPONSES to "Responses API").forEach { (protocol, label) ->
                        val selected = draft.protocol == protocol
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .testTag("compression-dialog-api:${protocol.wireName}")
                            .background(if (selected) colors.fieldBackground else androidx.compose.ui.graphics.Color.Transparent)
                            .selectable(selected, enabled = enabled, role = Role.RadioButton,
                                interactionSource = remember { MutableInteractionSource() }, indication = null,
                                onClick = { onChange(draft.copy(protocol = protocol)) })
                            .padding(start = 14.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.weight(1f), fontSize = 14.sp)
                            RadioButton(selected, onClick = null, colors = RadioButtonDefaults.colors(
                                selectedColor = colors.primary, unselectedColor = colors.muted))
                        }
                    }
                }
            }
            if (notice != null) item("notice") {
                Text(notice, Modifier.testTag("context-notice"), color = colors.muted, fontSize = 12.sp)
            }
            if (onManage != null) item("local-context") {
                AssistantPreviewButton("查看本机上下文", onManage,
                    Modifier.fillMaxWidth().testTag("context-view"))
            }
            item("help") {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("compression-dialog-help")
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        showHelp = !showHelp
                    }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("接口怎么选", Modifier.weight(1f), color = colors.muted, fontSize = 12.sp)
                    Icon(if (showHelp) DaiyuIcons.ChevronDown else DaiyuIcons.ChevronRight, null,
                        Modifier.size(18.dp), tint = colors.muted)
                }
                if (showHelp) Text("仅用于摘要。默认 Chat Completions；模型需要时选 Responses。Anthropic / Codex 不受此项影响。",
                    Modifier.testTag("compression-dialog-help-text").padding(bottom = 4.dp),
                    color = colors.muted, fontSize = 12.sp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistantPreviewButton("取消", onDismiss, Modifier.weight(1f).testTag("context-close"))
            AssistantPreviewButton("确定", { if (enabled) onConfirm() }, Modifier.weight(1f).testTag("context-compress"), primary = true)
        }
    }
}
