package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.ui.components.WithoutPressRipple
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

internal val SettingsModelProtocols = setOf(ProviderProtocol.RESPONSES, ProviderProtocol.MESSAGES,
    ProviderProtocol.CHAT_COMPLETIONS, ProviderProtocol.GEMINI)

/** Original settings palette and type styles; shared by purpose-specific model selectors. */
@Composable
internal fun SettingsModelPicker(title: String, source: SettingsModelSource, selectedKey: String?,
    onSelect: (SettingsModelChoice) -> Unit, onDismiss: () -> Unit,
    protocols: Set<ProviderProtocol> = SettingsModelProtocols) {
    var choices by remember(source) { mutableStateOf<List<SettingsModelChoice>?>(null) }
    var failed by remember(source) { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(selectedKey?.substringBefore('/')) }
    LaunchedEffect(source, protocols) {
        try { choices = withContext(Dispatchers.IO) { source.choices(protocols) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    WithoutPressRipple {
        WindowDialog(show = true, title = title, onDismissRequest = onDismiss) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp).testTag("settings-model-picker")) {
                when {
                    failed -> item { SettingsNote("模型列表读取失败，请重新打开。", error = true) }
                    choices == null -> item { SettingsNote("正在读取模型…") }
                    choices!!.isEmpty() -> item { SettingsNote("暂无可选模型，请先在供应商中添加模型。") }
                    else -> choices!!.groupBy { it.provider.slotId }.forEach { (providerId, models) ->
                        item("provider:$providerId") {
                            Text(models.first().providerName, style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                    .testTag("settings-model-provider:$providerId")
                                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                        expanded = if (expanded == providerId) null else providerId
                                    }.padding(horizontal = 12.dp, vertical = 16.dp))
                            HorizontalDivider()
                        }
                        if (expanded == providerId) items(models, key = { it.key }) { choice ->
                            SettingsRow(choice.selection.record.displayName,
                                modifier = Modifier.padding(start = 16.dp),
                                tag = "settings-model-choice:${choice.key}", onClick = { onSelect(choice) },
                                trailing = { if (choice.key == selectedKey) DaiyuIcon(DaiyuIcons.Check,
                                    contentDescription = "已选择", modifier = Modifier.size(20.dp)) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
