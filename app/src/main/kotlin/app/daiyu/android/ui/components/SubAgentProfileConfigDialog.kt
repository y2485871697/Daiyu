package app.daiyu.android.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.daiyu.android.delegation.*
import app.daiyu.android.ui.components.subagent.*
import app.daiyu.android.ui.haptics.TouchHaptics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.window.WindowDialog

/** One opening owns one editor, application, expected profile and parallel-pool snapshot. */
internal class SubAgentProfileDraftSession private constructor(
    val editor: ConversationSubAgentEditor,
    val expectedProfile: SubAgentProfile?,
    val expectedApplicationToken: String?,
    private val openedConfig: ConversationSubAgentConfig,
    initialDraft: SubAgentProfile,
) {
    val owner = editor.owner
    val openToken = Any()
    private var active = true
    var draft by mutableStateOf(initialDraft)
        private set
    var name by mutableStateOf(initialDraft.name)
    var parallelValue by mutableStateOf("")
        private set
    private var parallelEdited by mutableStateOf(false)
    private var bindingChanged = false
    private var parallelModel by mutableStateOf<SubAgentParallelModel?>(null)
    var submitting by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    fun dismiss() { active = false }
    fun isCurrent(currentEditor: ConversationSubAgentEditor?, allowed: Boolean): Boolean {
        if (!active || !allowed || editor.isDisposed || currentEditor !== editor || currentEditor?.owner != owner) return false
        val current = (editor.state as? SubAgentEditorState.Loaded)?.config ?: return true
        return editor.canEdit() && current.presetApplicationToken == expectedApplicationToken
    }
    fun canCommit(currentEditor: ConversationSubAgentEditor?, allowed: Boolean, ticket: Any = openToken): Boolean =
        ticket === openToken && isCurrent(currentEditor, allowed) && editor.enabled
    fun edit(change: (SubAgentProfile) -> SubAgentProfile) {
        val old = draft; draft = change(old)
        if (old.providerId != draft.providerId || old.modelId != draft.modelId) { bindingChanged = true; resetParallel() }
    }
    fun selectModel(selection: ModelFeatureSelection) { edit { editor.changeProfileModel(it, selection) } }
    private fun resetParallel() {
        parallelEdited = false; parallelModel = null
        parallelValue = editor.rememberedParallelLimit(draft)?.toString().orEmpty()
    }
    fun bindParallel(config: SubAgentModelConfig?) {
        val next = config?.takeIf { it.providerId.isNotBlank() && it.model.isNotBlank() }?.let { SubAgentParallelModel(it.providerId, it.model) }
        if (next != parallelModel) {
            parallelModel = next
            val originalBinding = expectedProfile != null && !bindingChanged && draft.providerId == expectedProfile.providerId && draft.modelId == expectedProfile.modelId
            val restored = if (next != null && !originalBinding) editor.rememberedParallelLimit(draft) else null
            parallelEdited = restored != null
            parallelValue = if (next == null) "" else (restored ?: openedConfig.parallelLimit(next)).toString()
        }
    }
    fun editParallel(value: String) { parallelValue = value; parallelEdited = true }
    fun parallelChange(): SubAgentParallelLimitChange? {
        if (!parallelEdited) return null
        val model = parallelModel ?: return null
        val number = parallelValue.trim().toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return SubAgentParallelLimitChange(model, number, openedConfig.parallelLimit(model))
    }
    fun parallelBoundTo(config: SubAgentModelConfig?): Boolean = config == null || parallelModel == SubAgentParallelModel(config.providerId, config.model)
    fun parallelIsValid(): Boolean = !parallelEdited || (parallelModel != null && parallelValue.trim().toIntOrNull()?.let { it >= 0 } == true)
    companion object {
        fun open(editor: ConversationSubAgentEditor, expectedProfile: SubAgentProfile? = null): SubAgentProfileDraftSession? {
            if (!editor.enabled) return null
            val config = (editor.state as? SubAgentEditorState.Loaded)?.config ?: return null
            if (expectedProfile != null && config.profiles.singleOrNull { it.id == expectedProfile.id } != expectedProfile) return null
            return SubAgentProfileDraftSession(editor, expectedProfile, config.presetApplicationToken, config.detached(), expectedProfile ?: editor.newProfileDraft())
        }
    }
}
internal fun resolveSubAgentProfileConfig(profile: SubAgentProfile, providers: List<SubAgentProvider>): SubAgentModelConfig? {
    val provider = providers.singleOrNull { it.id == profile.providerId && it.isEnabled } ?: return null
    val model = provider.models.singleOrNull { it.id == profile.modelId && it.isEnabled } ?: return null
    if (model.supportsSpeechSynthesis || !profile.acceptsModel(model.supportsImageGeneration, model.supportsVideoGeneration)) return null
    return runCatching { buildSubAgentModelConfig(provider, model) }.getOrNull()
}
private class SubAgentResolvedDraftBinding(val providerId: String, val modelId: String, val role: String, val config: SubAgentModelConfig?)
private fun Modifier.profileDialogScrollableBody(): Modifier = layout { measurable, constraints ->
    val maximum = if (constraints.hasBoundedHeight) (constraints.maxHeight - 144.dp.roundToPx()).coerceAtLeast(1).coerceAtMost(520.dp.roundToPx()) else 520.dp.roundToPx()
    val placeable = measurable.measure(constraints.copy(minHeight = constraints.minHeight.coerceAtMost(maximum), maxHeight = maximum))
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubAgentProfileConfigDialog(session: SubAgentProfileDraftSession, currentEditor: ConversationSubAgentEditor?,
    providers: List<SubAgentProvider>, enabled: Boolean = true, onDismiss: () -> Unit, onRetry: () -> Unit = { session.editor.retry() }) {
    val latestEditor by rememberUpdatedState(currentEditor)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestDismiss by rememberUpdatedState(onDismiss)
    val current = session.isCurrent(currentEditor, enabled)
    DisposableEffect(session) { onDispose { session.dismiss() } }
    LaunchedEffect(session, current) { if (!current) { session.dismiss(); latestDismiss() } }
    if (!current) return
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val draft = session.draft
    val usable = session.canCommit(currentEditor, enabled) && !session.submitting
    var modelPicker by remember(session) { mutableStateOf(false) }
    var thinkingPicker by remember(session) { mutableStateOf(false) }
    var choices by remember(session) { mutableStateOf<String?>(null) }
    val resolved by produceState<SubAgentResolvedDraftBinding?>(null, draft.providerId, draft.modelId, draft.role, providers) {
        value = SubAgentResolvedDraftBinding(draft.providerId, draft.modelId, draft.role, resolveSubAgentProfileConfig(draft, providers))
    }
    val boundConfig = resolved?.takeIf { it.providerId == draft.providerId && it.modelId == draft.modelId && it.role == draft.role }?.config
    val resolutionPending = draft.providerId.isNotBlank() && draft.modelId.isNotBlank() &&
        (resolved == null || resolved?.providerId != draft.providerId || resolved?.modelId != draft.modelId || resolved?.role != draft.role)
    LaunchedEffect(session, draft.providerId, draft.modelId, draft.role, resolutionPending, boundConfig?.providerId, boundConfig?.model) {
        if (!resolutionPending) session.bindParallel(boundConfig)
    }
    LaunchedEffect(usable) { if (!usable) { modelPicker = false; thinkingPicker = false; choices = null } }
    val speedProvider = providers.singleOrNull { it.id == draft.providerId && it.isEnabled }
    val speedModel = speedProvider?.models?.singleOrNull { it.id == draft.modelId && it.isEnabled }
    val canEditSpeed = boundConfig != null && supportsGptSpeedBinding(speedProvider, speedModel)
    val media = if (draft.isMedia && boundConfig != null) MediaReasoningSettings.resolve(boundConfig, draft.role) else null
    val canThink = boundConfig != null && (!draft.isMedia || media?.status == MediaReasoningSettings.Status.SUPPORTED)
    val effective = boundConfig?.let { SubAgentPreferences.applyReasoning(draft, it).effectiveReasoningEffort }
    val efforts = if (draft.isMedia) media?.efforts.orEmpty() else boundConfig?.reasoningCapabilities?.selectableEfforts.orEmpty()
    val thinkingLabel = when {
        boundConfig == null -> "未选择有效模型"
        !canThink -> media?.label ?: "当前接口未适配"
        draft.isMedia && effective !in efforts -> "原档位不可用，请重选"
        else -> effective?.displayName ?: "未启用"
    }
    val verifiedGrok = boundConfig?.grokImage ?: false
    val ready = usable && !resolutionPending && session.parallelBoundTo(boundConfig) && session.name.trim().isNotBlank() && session.parallelIsValid() && (session.expectedProfile != null || boundConfig != null)
    val interact: (() -> Unit) -> Unit = { action -> if (session.canCommit(latestEditor, latestEnabled) && !session.submitting) { TouchHaptics.click(view); action() } }
    val dismiss = { session.dismiss(); latestDismiss() }
    val userDismiss = { if (!session.submitting) dismiss() }
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        WithoutPressRipple {
            SubAgentConfigurationWindow(title = if (session.expectedProfile == null) "添加子代理" else "子代理配置", onDismiss = userDismiss) {
                Column(Modifier.fillMaxWidth().profileDialogScrollableBody().verticalScroll(rememberScrollState())) {
                    if (LocalConversationAgentStyle.current) ConversationAgentField("名称", session.name, { if (usable) session.name = it.take(80) }, usable)
                    else EtaFormTextField(session.name, { if (usable) session.name = it.take(80) }, hint = "名称", singleLine = true, enabled = usable, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("启用", Modifier.weight(1f))
                        if (LocalConversationAgentStyle.current) ConversationAgentSwitch(draft.enabled, usable) { active -> interact { session.edit { it.copy(enabled = active) } } }
                        else Switch(draft.enabled, enabled = usable, onCheckedChange = { active -> interact { session.edit { it.copy(enabled = active) } } })
                    }
                    SubAgentSettingRow("模型", boundConfig?.let { it.modelDisplayName.ifBlank { it.model } } ?: if (draft.modelId.isBlank()) "选择模型" else "模型不可用",
                        Icons.Rounded.ViewInAr, "草稿模型", usable, badge = boundConfig?.providerName,
                        onClick = { interact { modelPicker = true } }, onLongClick = { if (usable && canThink) { TouchHaptics.longPress(view); thinkingPicker = true } })
                    if (canEditSpeed) SubAgentSettingRow("GPT 速度", subAgentGptSpeedDisplayName(draft.gptSpeedForModel()), Icons.Rounded.Speed, "草稿GPT速度", usable, onClick = { interact {
                        session.edit { profile ->
                            if (profile.providerId != draft.providerId || profile.modelId != draft.modelId || profile.role != draft.role) profile
                            else profile.copy(gptSpeedByModel = profile.gptSpeedByModel + (SubAgentProfile.modelReasoningKey(profile.providerId, profile.modelId) to profile.gptSpeedForModel().next()))
                        }
                    } })
                    SubAgentSettingRow("职责", draft.roleLabel, Icons.Rounded.Assignment, "草稿职责", usable, onClick = { interact { choices = "role" } })
                    if (draft.supportsTaskTier) SubAgentSettingRow("任务分工", draft.tier?.label ?: "未设置分工", Icons.Rounded.AccountTree, "草稿任务分工", usable, onClick = { interact { choices = "tier" } })
                    SubAgentSettingRow("思考深度", thinkingLabel, Icons.Rounded.AutoAwesome, "草稿思考深度", usable && canThink, onClick = { interact { thinkingPicker = true } })
                    if (LocalConversationAgentStyle.current) ConversationAgentField("并行上限（0 为不限）", session.parallelValue,
                        { if (usable) session.editParallel(it) }, usable && boundConfig != null, KeyboardOptions(keyboardType = KeyboardType.Number))
                    else EtaFormTextField(session.parallelValue, { if (usable) session.editParallel(it) }, hint = "并行上限（0 为不限）", singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = usable && boundConfig != null, modifier = Modifier.fillMaxWidth())
                    if (!session.parallelIsValid()) Text("请输入 0 或正整数", color = MaterialTheme.colorScheme.error)
                    Text("本配置内相同提供商/API 模型共用此上限；仅确认时保存，调低不会取消正在执行的任务。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                    if (draft.role == "image_generation") SubAgentSettingRow("默认分辨率", draft.imageResolution?.let(ImageResolutionTier::label) ?: "跟随接口",
                        Icons.Rounded.Image, "草稿分辨率", usable && boundConfig != null, onClick = { interact { choices = "resolution" } })
                    session.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                    val editorState = session.editor.state
                    if (editorState is SubAgentEditorState.Error) {
                        Text("配置保存或读取失败：${editorState.reason}", color = MaterialTheme.colorScheme.error)
                        TextButton(enabled = !session.submitting, onClick = onRetry) { Text("重试恢复配置（保留草稿）") }
                    }
                }
                SubAgentDraftDialogActions(ready, !session.submitting, userDismiss, onConfirm = {
                    if (ready && !session.submitting && session.canCommit(latestEditor, latestEnabled)) {
                        val capturedDraft = session.draft.copy(name = session.name.trim()); val capturedTicket = session.openToken
                        val capturedExpected = session.expectedProfile; val capturedApplication = session.expectedApplicationToken; val capturedParallel = session.parallelChange()
                        session.submitting = true; session.error = null
                        scope.launch {
                            try {
                                val result = session.editor.commitProfileDraft(capturedDraft, capturedExpected, capturedApplication, capturedParallel,
                                    canCommit = { session.canCommit(latestEditor, latestEnabled, capturedTicket) })
                                if (result is ConversationSubAgentPreferences.WriteResult.Saved && session.isCurrent(latestEditor, latestEnabled)) { TouchHaptics.click(view); dismiss() }
                                else if (session.isCurrent(latestEditor, latestEnabled)) session.error = "未保存：配置或模型已变更，请检查后重试；草稿已保留。"
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { session.error = "保存失败，草稿已保留，请重试。" }
                            finally { session.submitting = false }
                        }
                    }
                })
            }
            if (usable && modelPicker) {
                val all = SubAgentModelPickerProjector.project(providers, draft.providerId, draft.modelId)
                val models = all.copy(providerGroups = all.providerGroups.map { group -> group.copy(models = group.models.filter { draft.acceptsModel(it.supportsImageGeneration, it.supportsVideoGeneration) }) }.filter { it.models.isNotEmpty() })
                SubAgentModelPickerDialog(models, true, { modelPicker = false }, { provider, model ->
                    if (session.canCommit(latestEditor, latestEnabled)) session.selectModel(ModelFeatureSelection(true, provider, model)); modelPicker = false
                }, "选择${draft.name}模型", onClearSelection = {
                    if (session.canCommit(latestEditor, latestEnabled)) session.selectModel(ModelFeatureSelection(true, "", "")); modelPicker = false
                }, highlightSelection = true)
            }
            if (usable && thinkingPicker && canThink && effective != null) {
                val binding = draft
                SubAgentThinkingEffortPickerDialog(true, effective, efforts.ifEmpty { listOf(effective) }, { thinkingPicker = false }, { next ->
                    if (session.canCommit(latestEditor, latestEnabled) && next in efforts && session.draft.providerId == binding.providerId && session.draft.modelId == binding.modelId && session.draft.role == binding.role)
                        session.edit { it.copy(reasoning = next) }
                }, description = "仅修改当前草稿，确认后保存" + if (draft.isMedia) "；档位依据端点显式映射" else "")
            }
            if (usable) choices?.let { field ->
                val options: List<Pair<String?, String>> = when (field) {
                    "role" -> listOf("implementation" to "执行", "review" to "审查／总结", "image_generation" to "图片生成", "video_generation" to "视频生成")
                    "tier" -> listOf(null to "未设置分工") + SubAgentTaskTier.entries.map { it.wireValue to it.label }
                    else -> listOf(null to "跟随接口") + ImageResolutionTier.values.filter { !verifiedGrok || it in setOf("low", "high") }.map { it to ImageResolutionTier.label(it) }
                }
                SubAgentChoiceWindow(title = when (field) { "role" -> "职责"; "tier" -> "任务分工"; else -> "默认分辨率" }, onDismiss = { choices = null }) {
                    Column(Modifier.fillMaxWidth().profileDialogScrollableBody().verticalScroll(rememberScrollState())) {
                        options.forEach { (value, label) ->
                            val select = { interact {
                                session.edit { profile -> when (field) {
                                    "role" -> profile.withRole(requireNotNull(value))
                                    "tier" -> if (profile.supportsTaskTier) profile.copy(tier = SubAgentTaskTier.fromWireValue(value.orEmpty())) else profile
                                    else -> if (profile.role == "image_generation") profile.copy(imageResolution = value) else profile
                                } }; choices = null
                            } }
                            if (LocalConversationAgentStyle.current) ConversationAgentChoice(label, when (field) {
                                "role" -> draft.role == value
                                "tier" -> draft.tier?.wireValue == value
                                else -> draft.imageResolution == value
                            }, select)
                            else TextButton(onClick = select, modifier = Modifier.fillMaxWidth()) { Text(label) }
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun SubAgentDraftDialogActions(confirmEnabled: Boolean, cancelEnabled: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val view = LocalView.current
    if (LocalConversationAgentStyle.current) {
        HorizontalDivider(Modifier.padding(top = 10.dp), color = conversationAgentColors().line)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConversationAgentButton("取消", cancelEnabled, modifier = Modifier.weight(1f)) { TouchHaptics.click(view); onCancel() }
            ConversationAgentButton("确认", confirmEnabled, primary = true, modifier = Modifier.weight(1f), onClick = onConfirm)
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MiuixTextButton(text = "取消", modifier = Modifier.weight(1f), enabled = cancelEnabled, onClick = { TouchHaptics.click(view); onCancel() })
        MiuixTextButton(text = "确认", modifier = Modifier.weight(1f), enabled = confirmEnabled, colors = MiuixButtonDefaults.textButtonColorsPrimary(), onClick = onConfirm)
    }
}
