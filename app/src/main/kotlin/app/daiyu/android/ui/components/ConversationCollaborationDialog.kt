package app.daiyu.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import app.daiyu.android.ui.haptics.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.*

import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.components.DaiyuDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import app.daiyu.android.delegation.*
import app.daiyu.android.ui.components.subagent.*
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle

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

/** Conversation-bound editor wiring; preview styling is scoped to this chat entry only. */
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
        CompositionLocalProvider(LocalConversationSubAgentEditor provides editor,
            app.daiyu.android.ui.components.subagent.LocalConversationAgentStyle provides true) {
            BoundConversationCollaborationDialog(show, enabled, onEnabledChange, onDismiss, taskRunning, ownerMatches, modelSource)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoundConversationCollaborationDialog(show: Boolean, enabled: Boolean, onEnabledChange: (Boolean) -> Unit,
    onDismiss: () -> Unit, taskRunning: Boolean, ownerMatches: () -> Boolean, modelSource: SubAgentModelSource) {
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
    var addedDraft by remember(panelEditor) { mutableStateOf<SubAgentProfileDraftSession?>(null) }
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        WithoutPressRipple {
            ConversationAgentTypography {
                ConversationAgentSheet(onDismiss, useDefaultHeight = true, modifier = Modifier.testTag("subagent-collaboration-card")) {
                    Column(Modifier.fillMaxSize()
                        .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp)) {
                        ConversationAgentHandle()
                        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).heightIn(min = 29.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("本会话协作", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        }
                        key(editor, choosing, current?.presetApplicationToken) {
                            Column(Modifier.fillMaxWidth().weight(1f).testTag("subagent-collaboration-body")
                                .verticalScroll(rememberScrollState())
                                .padding(bottom = if (panelEditor != null) 0.dp else 8.dp)) {
                                when (state) {
                                    is SubAgentEditorState.Error -> {
                                        Text("子代理配置读取或保存失败：${state.reason}")
                                        ConversationAgentButton("重试恢复配置", onClick = { editor?.retry() })
                                    }
                                    SubAgentEditorState.Loading -> Text("正在读取本会话子代理配置…")
                                    else -> if (editor == null) CollaborationEmptyState(
                                        "尚未选择会话", "开始或选择一个会话后，即可配置本会话协作。", "subagent-no-conversation")
                                        else if (!canChange) Text("主代理尚未停止，暂不可编辑本会话配置。")
                                }
                                if (choosing && directory != null && editor != null && chooserPopup != null) {
                                    val (popup, ticket) = chooserPopup
                                    if (!directory.loading && directory.error == null && directory.entries.isEmpty()) {
                                        CollaborationEmptyState("还没有子代理组",
                                            "可在设置中添加子代理组，或使用当前配置。", "subagent-no-presets")
                                    } else {
                                        if (directory.entries.isNotEmpty()) {
                                            Text("选择子代理组", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = conversationAgentColors().accent, modifier = Modifier.padding(horizontal = 2.dp, vertical = 8.dp))
                                            Text("应用后仅修改本会话配置，不影响原子代理组。", style = MaterialTheme.typography.bodySmall, color = conversationAgentColors().muted, modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 14.dp))
                                        }
                                        SubAgentPresetDirectoryStatus(directory)
                                    }
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
                                            Spacer(Modifier.height(8.dp))
                                        }
                                    }
                                    applicationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                } else if (current != null && editor != null && panelEditor != null) {
                                    key(panelEditor) {
                                        CompositionLocalProvider(LocalConversationSubAgentEditor provides panelEditor) {
                                            when (panelState) {
                                                is SubAgentEditorState.Error -> {
                                                    Text("本会话配置保存或读取失败：${panelState.reason}", color = MaterialTheme.colorScheme.error)
                                                    ConversationAgentButton("重试本会话配置", enabled = latestOwnerMatches() && !latestTaskRunning, onClick = {
                                                        if (latestOwnerMatches() && !latestTaskRunning && !latestChoosing) { editor.retry(); panelEditor.retry() }
                                                    })
                                                }
                                                SubAgentEditorState.Loading -> Text("正在读取本会话子代理配置…")
                                                else -> Unit
                                            }
                                            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)
                                                .toggleable(value = panelConfig?.enabled ?: current.enabled, enabled = panelEnabled, role = Role.Switch,
                                                    onValueChange = { if (panelEnabled && panelEditor.enabled) { panelEditor.setEnabled(it) } }),
                                                verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text("自动委派", style = MaterialTheme.typography.bodyLarge)
                                                    Text("按职责自动分配任务", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                                                }
                                                ConversationAgentSwitch(checked = panelConfig?.enabled ?: current.enabled, enabled = panelEnabled)
                                            }
                                            HorizontalDivider(Modifier.padding(bottom = 8.dp), color = conversationAgentColors().line)
                                            (panelConfig ?: current).profiles.forEach { profile ->
                                                key(panelEditor, profile.id) {
                                                    SubAgentProfileRow(profile, providers, enabled = panelEnabled,
                                                        draftAllowed = latestOwnerMatches() && !latestTaskRunning && !latestChoosing,
                                                        draftRetry = { editor.retry(); panelEditor.retry() })
                                                    Spacer(Modifier.height(8.dp))
                                                }
                                            }
                                            addedDraft?.let { session ->
                                                SubAgentProfileConfigDialog(session, panelEditor, providers, enabled = latestOwnerMatches() && !latestTaskRunning && !latestChoosing,
                                                    onDismiss = { session.dismiss(); if (addedDraft === session) addedDraft = null }, onRetry = { editor.retry(); panelEditor.retry() })
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (current != null && editor != null && panelEditor != null) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("subagent-collaboration-add-row"),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("点按模型切换 · 长按调整思考", style = MaterialTheme.typography.labelSmall.copy(
                                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f).testTag("subagent-collaboration-hint"))
                                ConversationAgentIconButton(modifier = Modifier.size(48.dp).testTag("subagent-collaboration-add"), enabled = panelEnabled, onClick = {
                                    if (panelEnabled && panelEditor.enabled) SubAgentProfileDraftSession.open(panelEditor)?.let { addedDraft = it }
                                }) { Icon(Icons.Rounded.Add, "添加子代理", Modifier.size(22.dp)) }
                            }
                        }
                        HorizontalDivider(Modifier.padding(top = if (panelEditor != null) 8.dp else 10.dp).testTag("subagent-collaboration-footer-divider"), color = conversationAgentColors().line)
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 10.dp).testTag("subagent-collaboration-footer"),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (choosing && current != null && editor != null && chooserPopup != null) {
                                val (popup, ticket) = chooserPopup
                                ConversationAgentButton("使用当前配置", enabled = canChange, modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    if (popup.isCurrent(ticket, popup) && latestOwnerMatches() && !latestTaskRunning && latestCanChange && latestChoosing && editor.enabled) { chooserOverride = false }
                                }
                            } else if (!choosing && panelEditor != null) {
                                ConversationAgentButton("切换子代理组", enabled = panelEnabled, modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    if (panelEnabled && panelEditor.enabled && latestOwnerMatches() && !latestTaskRunning && !latestChoosing) { chooserOverride = true }
                                }
                            }
                            ConversationAgentButton(if (editor == null) "关闭" else "完成", enabled = !taskRunning, primary = true,
                                modifier = Modifier.weight(1f).fillMaxHeight().testTag("subagent-collaboration-done")) {
                                if (latestOwnerMatches() && !latestTaskRunning) { onDismiss() }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollaborationEmptyState(title: String, description: String, tag: String) {
    val colors = conversationAgentColors()
    Column(Modifier.fillMaxWidth().testTag(tag).padding(horizontal = 2.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = colors.text)
        Text(description, fontSize = 13.sp, lineHeight = 20.sp, color = colors.muted)
    }
}
