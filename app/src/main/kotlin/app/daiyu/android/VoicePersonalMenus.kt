package app.daiyu.android

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.ui.components.usagePalette
import app.daiyu.android.ui.haptics.clickable
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.screens.chat.conversation.ChatSelectionSheet
import top.yukonga.miuix.kmp.basic.Text

private data class VoiceActionItem(val id: String, val label: String, val icon: ImageVector)

@Composable
internal fun VoiceAddSheet(onSelect: (String) -> Unit, onDismiss: () -> Unit,
    isEnabled: () -> Boolean = { true }) {
    VoiceActionSheet("添加声音", listOf(
        VoiceActionItem("CREATE", "用录音制作声音", DaiyuIcons.Voice),
        VoiceActionItem("IMPORT", "导入已有声音", DaiyuIcons.File),
    ), onSelect, onDismiss, isEnabled = { isEnabled() })
}

@Composable
internal fun VoiceAccountSettingsSheet(onSelect: (String) -> Unit, onDismiss: () -> Unit,
    isEnabled: (String) -> Boolean = { true }) {
    VoiceActionSheet("声音设置", listOf(
        VoiceActionItem("ACCOUNT", "声音复刻账户", DaiyuIcons.Key),
        VoiceActionItem("SYNC", "同步音色名额", DaiyuIcons.Refresh),
    ), onSelect, onDismiss, isEnabled)
}

@Composable
private fun VoiceActionSheet(title: String, items: List<VoiceActionItem>,
    onSelect: (String) -> Unit, onDismiss: () -> Unit, isEnabled: (String) -> Boolean) {
    val colors = usagePalette()
    ChatSelectionSheet(title, onDismiss = onDismiss, showCloseButton = false, titleFontSize = 20.sp) {
        val finishSelection = app.daiyu.android.ui.components.rememberBottomSheetAction()
        Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())
            .testTag("voice-action-sheet")) {
            items.forEach { item ->
                val enabled = isEnabled(item.id)
                val tint = when (item.id) {
                    "CREATE", "SYNC" -> if (colors.dark) Color(0xFF80B3B1) else Color(0xFF568E90)
                    "IMPORT" -> if (colors.dark) Color(0xFFC5A778) else Color(0xFFB08D59)
                    else -> colors.accent
                }
                Row(Modifier.fillMaxWidth().testTag("voice-personal-action:${item.id}")
                    .heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
                    .alpha(if (enabled) 1f else .38f)
                    .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() },
                        indication = null, role = Role.Button) { finishSelection { if (isEnabled(item.id)) onSelect(item.id) } }
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(item.icon, null, Modifier.size(24.dp).testTag("voice-personal-icon:${item.id}"), tint = tint)
                    Text(item.label, Modifier.weight(1f), fontSize = 16.sp, lineHeight = 24.sp, color = colors.text)
                    Icon(DaiyuIcons.ChevronRight, null, Modifier.size(18.dp)
                        .testTag("voice-personal-chevron:${item.id}").alpha(.4f), tint = colors.muted)
                }
            }
        }
    }
}
