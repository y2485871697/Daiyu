package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.ui.components.WithoutPressRipple
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ProviderSettingsTabs(modelTab: Boolean, enabled: Boolean, onSelect: (Boolean) -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("配置", "模型").forEachIndexed { index, title ->
            val selected = modelTab == (index == 1)
            val shape = RoundedCornerShape(14.dp)
            Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(shape)
                .then(if (selected) Modifier.background(colors.surfaceVariant)
                    else Modifier.border(1.dp, colors.onSurfaceVariantSummary, shape))
                .testTag("settings-radio:$title")
                .selectable(selected = selected, enabled = enabled, role = Role.Tab,
                    onClick = { onSelect(index == 1) }).padding(horizontal = 12.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center) {
                Text(title, style = MiuixTheme.textStyles.body1, color = colors.onSurface)
            }
        }
    }
}

@Composable
internal fun ProviderProtocolFields(draft: ModelSettingsDraftUi, enabled: Boolean,
    onChange: (ModelSettingsDraftUi) -> Unit) {
    if (draft.savedProtocol == ProviderProtocol.MESSAGES) return
    val protocols = listOf(ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.RESPONSES)
    val modes = listOf("Chat Completions API", "Responses API")
    val tags = listOf("settings-radio:OpenAI Chat Completions", "settings-radio:OpenAI Responses")
    val index = protocols.indexOf(draft.protocol)
    ProviderChoiceRow("Endpoint 模式", modes.getOrNull(index) ?: "请选择", "provider-endpoint-select", enabled, modes,
        tags, index,
        summary = when (draft.protocol) {
            ProviderProtocol.RESPONSES -> "使用 Responses API、typed items 与语义化流式事件"
            ProviderProtocol.CHAT_COMPLETIONS -> "使用 Chat Completions API"
            else -> "请选择请求接口"
        }) { choice -> onChange(draft.selectProtocol(protocols[choice])) }

}

@Composable
internal fun ProviderChoiceRow(title: String, value: String, tag: String, enabled: Boolean,
    choices: List<String>, optionTags: List<String>, selectedIndex: Int, summary: String? = null, onSelect: (Int) -> Unit) {
    var expanded by remember(value) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val colors = MiuixTheme.colorScheme
    val currentEnabled by rememberUpdatedState(enabled)
    DisposableEffect(lifecycle, value, enabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) expanded = false
        }
        lifecycle.addObserver(observer)
        onDispose { expanded = false; lifecycle.removeObserver(observer) }
    }
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.testTag(tag).fillMaxWidth().heightIn(min = 64.dp)
            .clickable(enabled = enabled) { expanded = true }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MiuixTheme.textStyles.body1)
                summary?.let { Text(it, style = MiuixTheme.textStyles.body2, color = colors.onSurfaceVariantSummary) }
            }
            Text(value, style = MiuixTheme.textStyles.body2, color = colors.onSurfaceVariantSummary,
                modifier = Modifier.widthIn(max = 164.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Column {
                DaiyuIcon(DaiyuIcons.ChevronDown, null, Modifier.size(12.dp).rotate(180f))
                DaiyuIcon(DaiyuIcons.ChevronDown, null, Modifier.size(12.dp))
            }
        }
        WithoutPressRipple {
            DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(max = 320.dp).testTag("$tag-menu"),
                shape = RoundedCornerShape(22.dp), containerColor = colors.surface) {
                choices.forEachIndexed { index, choice ->
                    DropdownMenuItem(
                        text = { Text(choice, style = MiuixTheme.textStyles.body1) },
                        modifier = Modifier.testTag(optionTags[index]).semantics { selected = index == selectedIndex },
                        trailingIcon = { if (index == selectedIndex) DaiyuIcon(DaiyuIcons.Check, "已选择", size = 22.dp) },
                        onClick = {
                            if (expanded && currentEnabled && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                expanded = false
                                onSelect(index)
                            }
                        },
                    )
                }
            }
        }
    }
}
