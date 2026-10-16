package app.daiyu.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.daiyu.android.delegation.ConversationSubAgentPreferences
import app.daiyu.android.delegation.SubAgentPreset
import app.daiyu.android.ui.haptics.TouchHaptics
import app.daiyu.android.ui.icons.daiyuIconColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

internal class SubAgentPresetDirectory(private val repository: ConversationSubAgentPreferences) {
    var entries by mutableStateOf<List<SubAgentPreset>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(true)
        private set
    private var retryVersion by mutableIntStateOf(0)
    private fun fail(failure: Exception) {
        if (failure is CancellationException) throw failure
        error = failure.message ?: failure.javaClass.simpleName; loading = false
    }
    @Composable fun observe() {
        val version = retryVersion
        LaunchedEffect(this, version) {
            if (error != null) return@LaunchedEffect
            try {
                entries = repository.presets(); loading = false
                repository.presetsFlow().collect { if (error == null) entries = it }
            } catch (failure: Exception) { fail(failure) }
        }
    }
    fun change(action: () -> Unit): Boolean {
        if (loading || error != null) return false
        return try { action(); entries = repository.presets(); true } catch (failure: Exception) { fail(failure); false }
    }
    fun retry() {
        try {
            check(repository.recoverDurability()) { "子代理组恢复失败；请重试" }
            error = null; loading = true; retryVersion++
        } catch (failure: Exception) { fail(failure) }
    }
}
@Composable
internal fun rememberSubAgentPresetDirectory(repository: ConversationSubAgentPreferences): SubAgentPresetDirectory =
    remember(repository) { SubAgentPresetDirectory(repository) }.also { it.observe() }
@Composable
internal fun SubAgentPresetDirectoryStatus(directory: SubAgentPresetDirectory) {
    val view = LocalView.current
    when {
        directory.error != null -> {
            Text("子代理组读取或保存失败：${directory.error}", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { TouchHaptics.click(view); directory.retry() }) { Text("重试读取子代理组") }
        }
        directory.loading -> Text("正在读取子代理组…")
        directory.entries.isEmpty() -> Text("还没有子代理组，可在设置中添加。")
    }
}
@Composable
internal fun SubAgentPresetCard(preset: SubAgentPreset, enabled: Boolean, actionLabel: String,
    onClick: () -> Unit, selected: Boolean = false, trailing: @Composable (() -> Unit)? = null) {
    val view = LocalView.current
    Surface(shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = 96.dp).semantics { contentDescription = actionLabel }
                .clickable(enabled = enabled, role = Role.Button, interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    TouchHaptics.click(view); onClick()
                }.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Rounded.AccountTree, null, tint = daiyuIconColor(Icons.Rounded.AccountTree))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(preset.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${preset.config.profiles.size} 个子代理 · ${if (preset.config.enabled) "自动委派已开启" else "自动委派已关闭"}", style = MaterialTheme.typography.bodySmall)
                    if (selected) Text("当前使用", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(20.dp))
            }
            trailing?.invoke()
        }
    }
}
