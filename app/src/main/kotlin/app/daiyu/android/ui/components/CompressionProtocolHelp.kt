package app.daiyu.android.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.ui.components.UsageBody as Text
import app.daiyu.android.ui.haptics.clickable
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.screens.assistants.assistantPreviewPalette
import app.daiyu.android.ui.screens.settings.SettingsExpandableContent
import app.daiyu.android.ui.screens.settings.settingsDisclosureRotation

@Composable
internal fun CompressionProtocolHelp(
    expanded: Boolean,
    onToggle: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
) {
    val colors = assistantPreviewPalette()
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag(tag)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClick = onToggle).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("接口怎么选", Modifier.weight(1f), color = colors.muted, fontSize = 12.sp)
            Icon(DaiyuIcons.ChevronRight, null,
                Modifier.size(18.dp).settingsDisclosureRotation(expanded), tint = colors.muted)
        }
        SettingsExpandableContent(expanded = expanded) {
            Text("仅用于摘要。默认 Chat Completions；模型需要时选 Responses。Anthropic / Codex 不受此项影响。",
                Modifier.testTag("$tag-text").padding(bottom = 4.dp), color = colors.muted, fontSize = 12.sp)
        }
    }
}
