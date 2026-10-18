package app.daiyu.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.daiyu.android.delegation.*
import app.daiyu.android.ui.haptics.TouchHaptics

internal class OwnerBoundPopup<Owner>(val owner: Owner) {
    private var active: Any? = null
    fun open(): Any = Any().also { active = it }
    fun dismiss() { active = null }
    fun isCurrent(ticket: Any?, current: OwnerBoundPopup<Owner>): Boolean = ticket != null && current === this && active === ticket
    fun dispatch(ticket: Any?, current: OwnerBoundPopup<Owner>, action: () -> Unit): Boolean {
        if (!isCurrent(ticket, current)) return false
        action(); return true
    }
}

/** Entry wiring only; the original collaboration panel below is unchanged. */
@Composable
internal fun ConversationCollaborationDialog(
    show: Boolean,
    conversationId: String?,
    onDismiss: () -> Unit,
    enabled: Boolean = true,
    onEnabledChange: (Boolean) -> Unit = {},
    taskRunning: Boolean = false,
    ownerMatches: () -> Boolean = { true },
    repositoryFactory: (() -> ConversationSubAgentPreferences)? = null,
    modelSource: SubAgentModelSource = SubAgentModelSource.Empty,
    onAuthorizeSavedModel: (SubAgentProfile, SubAgentProvider, SubAgentModel) -> Unit = { _, _, _ -> },
) {
    if (!show) return
    val context = LocalContext.current.applicationContext
    val repository = remember { repositoryFactory?.invoke() ?: ConversationSubAgentPreferences(context.getSharedPreferences("subagent_configuration", 0)) }
    val latestRunning by rememberUpdatedState(taskRunning)
    val latestMatches by rememberUpdatedState(ownerMatches)
    val latestId by rememberUpdatedState(conversationId)
    val latestShow by rememberUpdatedState(show)
    val owner = remember(conversationId) { conversationId?.takeIf { it.isNotBlank() }?.let { SubAgentConfigKey.Conversation(it) } }
    val editor = remember(owner, repository, modelSource) { owner?.let { captured ->
        ConversationSubAgentEditor(captured, repository, modelSource) {
            latestShow && latestId == captured.value && !latestRunning && latestMatches()
        }
    } }
    DisposableEffect(editor) { onDispose { editor?.dispose() } }
    key(editor) {
        CompositionLocalProvider(LocalConversationSubAgentEditor provides editor) {
            BoundConversationCollaborationDialog(show, enabled, onEnabledChange, onDismiss, taskRunning, ownerMatches, modelSource, onAuthorizeSavedModel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoundConversationCollaborationDialog(show: Boolean, enabled: Boolean, onEnabledChange: (Boolean) -> Unit,
    onDismiss: () -> Unit, taskRunning: Boolean, ownerMatches: () -> Boolean, modelSource: SubAgentModelSource,
    onAuthorizeSavedModel: (SubAgentProfile, SubAgentProvider, SubAgentModel) -> Unit) {
    if (!show || !ownerMatches()) return
    val editor = LocalConversationSubAgentEditor.current?.takeIf { ownerMatches() }
    val state = editor?.observe()
    var lastLoadedConfig by remember(editor) { mutableStateOf<ConversationSubAgentConfig?>(null) }
    val loadedConfig = (state as? SubAgentEditorState.Loaded)?.config
    SideEffect { if (loadedConfig != null) lastLoadedConfig = loadedConfig }
    val current = loadedConfig ?: lastLoadedConfig
    val canChange = editor?.enabled == true && !taskRunning && ownerMatches()
    var chooserOverride by remember(editor) { mutableStateOf<Boolean?>(null) }
    val choosing = chooserOverride ?: (current?.appliedPresetId == null)
    var applicationError by remember(editor) { mutableStateOf<String?>(null) }
    val latestOwnerMatches by rememberUpdatedState(ownerMatches)
    val latestTaskRunning by rememberUpdatedState(taskRunning)
    val latestChoosing by rememberUpdatedState(choosing)
    val latestCanChange by rememberUpdatedState(canChange)
    val directory = editor?.let { rememberSubAgentPresetDirectory(it.repository) }
    val providers by modelSource.providers.collectAsState()
    val maximumHeight = (LocalConfiguration.current.screenHeightDp - 48).coerceAtLeast(160).dp
    val view = LocalView.current
    val chooserPopup = if (choosing && directory != null && editor != null) {
        val popup = remember(editor) { OwnerBoundPopup(editor.owner) }
        val ticket = remember(popup) { popup.open() }
        DisposableEffect(popup) { onDispose { popup.dismiss() } }
        popup to ticket
    } else null
    val panelEditor = if (!choosing && current != null && editor != null) {
        key(editor, current.presetApplicationToken) {
            val scoped = remember(editor, current.presetApplicationToken) {
                editor.scoped(current.presetApplicationToken) { latestOwnerMatches() && !latestTaskRunning && !latestChoosing }
            }
            DisposableEffect(scoped) { onDispose { scoped.dispose() } }; scoped
        }
    } else null
    val panelState = panelEditor?.observe()
    val panelConfig = (panelState as? SubAgentEditorState.Loaded)?.config
    val panelEnabled = canChange && panelEditor?.enabled == true && panelConfig != null
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        WithoutPressRipple {
            Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)
                    .heightIn(max = maximumHeight).testTag("subagent-collaboration-card"), shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)) {
                        Text("本会话协作", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
                        Column(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("subagent-collaboration-body")
                            .verticalScroll(key(editor, choosing, current?.presetApplicationToken) { rememberScrollState() })
                            .padding(bottom = 8.dp).alpha(if (canChange) 1f else 0.38f)) {
                            when (state) {
                                is SubAgentEditorState.Error -> {
                                    Text("子代理配置读取或保存失败：${state.reason}")
                                    TextButton(onClick = { editor?.retry() }) { Text("重试恢复配置") }
                                }
                                SubAgentEditorState.Loading -> Text("正在读取本会话子代理配置…")
                                else -> if (editor == null) Text("请先选择会话；未选择会话时不可编辑。")
                                    else if (!canChange) Text("主代理尚未停止，暂不可编辑本会话配置。")
                            }
                            if (choosing && directory != null && editor != null && chooserPopup != null) {
                                val (popup, ticket) = chooserPopup
                                Text("选择子代理组", style = MaterialTheme.typography.titleMedium)
                                Text("应用时复制到本会话；本会话修改不会反写预设。切换会替换当前会话配置。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
                                SubAgentPresetDirectoryStatus(directory)
                                directory.entries.forEach { preset ->
                                    key(editor, preset.id) {
                                        SubAgentPresetCard(preset, canChange && directory.error == null && !directory.loading,
                                            "应用子代理组${preset.name}", selected = current?.appliedPresetId == preset.id,
                                            onClick = {
                                                if (popup.isCurrent(ticket, popup) && latestOwnerMatches() && !latestTaskRunning && latestCanChange && latestChoosing) {
                                                    val result = editor.applyPreset(preset.id) { popup.isCurrent(ticket, popup) && latestOwnerMatches() && !latestTaskRunning && latestChoosing }
                                                    if (result is ConversationSubAgentPreferences.WriteResult.Saved) { applicationError = null; chooserOverride = false }
                                                    else applicationError = "子代理组未应用，当前会话配置已保留；请重试。"
                                                }
                                            })
                                        Spacer(Modifier.height(12.dp))
                                    }
                                }
                                applicationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            } else if (current != null && editor != null && panelEditor != null) {
                                key(panelEditor) {
                                    var addedDraft by remember(panelEditor) { mutableStateOf<SubAgentProfileDraftSession?>(null) }
                                    CompositionLocalProvider(LocalConversationSubAgentEditor provides panelEditor) {
                                        when (panelState) {
                                            is SubAgentEditorState.Error -> {
                                                Text("本会话配置保存或读取失败：${panelState.reason}", color = MaterialTheme.colorScheme.error)
                                                TextButton(enabled = latestOwnerMatches() && !latestTaskRunning, onClick = {
                                                    if (latestOwnerMatches() && !latestTaskRunning && !latestChoosing) { TouchHaptics.click(view); editor.retry(); panelEditor.retry() }
                                                }) { Text("重试本会话配置") }
                                            }
                                            SubAgentEditorState.Loading -> Text("正在读取本会话子代理配置…")
                                            else -> Unit
                                        }
                                        (panelConfig ?: current).appliedPresetName?.let { Text("已应用：$it", style = MaterialTheme.typography.bodySmall) }
                                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                            .toggleable(value = panelConfig?.enabled ?: current.enabled, enabled = panelEnabled, role = Role.Switch,
                                                onValueChange = { if (panelEnabled && panelEditor.enabled) { TouchHaptics.click(view); panelEditor.setEnabled(it) } }),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text("自动委派", style = MaterialTheme.typography.bodyLarge)
                                                Text("按职责自动分配任务", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                                            }
                                            Switch(checked = panelConfig?.enabled ?: current.enabled, enabled = panelEnabled, onCheckedChange = null)
                                        }
                                        HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                                        (panelConfig ?: current).profiles.forEach { profile ->
                                            key(panelEditor, profile.id) {
                                                SubAgentProfileRow(profile, providers, enabled = panelEnabled,
                                                    draftAllowed = latestOwnerMatches() && !latestTaskRunning && !latestChoosing, draftRetry = { editor.retry(); panelEditor.retry() })
                                                val selectedProvider = providers.singleOrNull { it.id == profile.providerId && it.isEnabled }
                                                val selectedModel = selectedProvider?.models?.singleOrNull { it.id == profile.modelId && it.isEnabled }
                                                TextButton(modifier = Modifier.testTag("subagent-authorize-${profile.id}"),
                                                    enabled = panelEnabled && profile.enabled && selectedProvider != null && selectedModel != null,
                                                    onClick = {
                                                        if (panelEnabled && panelEditor.enabled && latestOwnerMatches() && !latestTaskRunning &&
                                                            !latestChoosing && selectedProvider != null && selectedModel != null)
                                                            onAuthorizeSavedModel(profile, selectedProvider, selectedModel)
                                                    }) { Text("授权此已保存模型") }
                                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                                            }
                                        }
                                        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text("点按模型切换 · 长按调整思考", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                                            IconButton(modifier = Modifier.size(48.dp), enabled = panelEnabled, onClick = {
                                                if (panelEnabled && panelEditor.enabled) SubAgentProfileDraftSession.open(panelEditor)?.let { TouchHaptics.click(view); addedDraft = it }
                                            }) { Icon(Icons.Rounded.Add, "添加子代理") }
                                        }
                                        addedDraft?.let { session ->
                                            SubAgentProfileConfigDialog(session, panelEditor, providers, enabled = latestOwnerMatches() && !latestTaskRunning && !latestChoosing,
                                                onDismiss = { session.dismiss(); if (addedDraft === session) addedDraft = null }, onRetry = { editor.retry(); panelEditor.retry() })
                                        }
                                    }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(min = 48.dp).testTag("subagent-collaboration-footer"),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (choosing && current != null && editor != null && chooserPopup != null) {
                                val (popup, ticket) = chooserPopup
                                TextButton(enabled = canChange, modifier = Modifier.semantics { onClick {
                                    if (popup.isCurrent(ticket, popup) && latestOwnerMatches() && !latestTaskRunning && latestCanChange && latestChoosing && editor.enabled) { TouchHaptics.click(view); chooserOverride = false }
                                    true
                                } }, onClick = {
                                    if (popup.isCurrent(ticket, popup) && latestOwnerMatches() && !latestTaskRunning && latestCanChange && latestChoosing && editor.enabled) { TouchHaptics.click(view); chooserOverride = false }
                                }) { Text("使用当前配置") }
                            } else if (!choosing && panelEditor != null) {
                                TextButton(enabled = panelEnabled, modifier = Modifier.semantics { onClick {
                                    if (panelEnabled && panelEditor.enabled && latestOwnerMatches() && !latestTaskRunning && !latestChoosing) { TouchHaptics.click(view); chooserOverride = true }
                                    true
                                } }, onClick = {
                                    if (panelEnabled && panelEditor.enabled && latestOwnerMatches() && !latestTaskRunning && !latestChoosing) { TouchHaptics.click(view); chooserOverride = true }
                                }) { Text("切换子代理组") }
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(enabled = !taskRunning, onClick = {
                                if (latestOwnerMatches() && !latestTaskRunning) { TouchHaptics.click(view); onDismiss() }
                            }) { Text("完成") }
                        }
                    }
                }
            }
        }
    }
}
