package app.daiyu.android.ui.components


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallSplit
import app.daiyu.android.ui.components.DaiyuAlertDialog as AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import app.daiyu.android.ui.haptics.material.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.daiyu.android.delegation.*

@Composable
internal fun SubAgentParallelLimitRow(profile: SubAgentProfile, config: SubAgentModelConfig?, enabled: Boolean = true) {
    val editor = LocalConversationSubAgentEditor.current
    val state = editor?.observe()
    val ownerConfig = (state as? SubAgentEditorState.Loaded)?.config
    val usable = enabled && editor?.enabled == true && ownerConfig != null && config != null && config.providerId.isNotBlank() && config.model.isNotBlank()
    val currentUsable by rememberUpdatedState(usable)
    key(editor, profile.id, profile.providerId, profile.modelId, profile.role, config?.providerId, config?.model, usable) {
        var open by remember { mutableStateOf(false) }
        var value by remember { mutableStateOf("") }
        var openedProfile by remember { mutableStateOf(profile) }
        var openedToken by remember { mutableStateOf<String?>(null) }
        var openedLimit by remember { mutableStateOf(1) }
        var error by remember { mutableStateOf<String?>(null) }
        val limit = if (config != null && ownerConfig != null && config.providerId.isNotBlank() && config.model.isNotBlank()) ownerConfig.parallelLimit(profile.id, SubAgentParallelModel(config.providerId, config.model)) else null
        SubAgentSettingRow("并行上限", when {
            state is SubAgentEditorState.Error -> "配置读取失败"
            ownerConfig == null -> "正在读取配置"
            config == null -> if (profile.modelId.isBlank()) "未选择模型" else "模型不可用"
            limit == 0 -> "不限"
            limit != null -> limit.toString()
            else -> "模型不可用"
        }, Icons.Rounded.CallSplit, "设置${profile.name}并行上限", enabled = usable, onClick = {
            if (currentUsable && config != null && limit != null && ownerConfig != null) {
                openedProfile = ownerConfig.profiles.singleOrNull { it.id == profile.id } ?: return@SubAgentSettingRow
                openedToken = ownerConfig.presetApplicationToken; openedLimit = limit
                value = limit.toString(); error = null; open = true
            }
        })
        if (state is SubAgentEditorState.Error) {
            Text("子代理配置错误：${state.reason}")
            TextButton(onClick = { editor?.retry() }) { Text("重试恢复配置") }
        }
        if (open && usable && config != null) {
            val number = value.trim().toIntOrNull()?.takeIf { it >= 0 }
            AlertDialog(onDismissRequest = { open = false }, title = { Text("${profile.name} · 并行上限") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${config.providerName} · ${config.modelDisplayName.ifBlank { config.model }}")
                    OutlinedTextField(value, { if (currentUsable) value = it }, label = { Text("0 为不限，或输入正整数") },
                        modifier = Modifier.managedInput().fillMaxWidth(), singleLine = true, isError = number == null, enabled = usable,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(cursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.primary), errorCursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.error)),
                    )
                    error?.let { Text(it) }
                } }, dismissButton = { TextButton(onClick = { open = false }) { Text("取消") } },
                confirmButton = { TextButton(enabled = usable && number != null, onClick = {
                    if (currentUsable && number != null) {
                        if (editor?.saveParallelLimit(openedProfile, config.model, number, openedToken, openedLimit)
                            is ConversationSubAgentPreferences.WriteResult.Saved) open = false
                        else error = "配置已变更，请重新打开"
                    }
                }) { Text("保存") } })
        }
    }
}
