package app.daiyu.android.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import app.daiyu.android.ui.components.DaiyuAlertDialog as AlertDialog
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
import androidx.compose.ui.unit.sp
import app.daiyu.android.delegation.*
import app.daiyu.android.ui.components.subagent.*
import app.daiyu.android.ui.haptics.TouchHaptics

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SubAgentProfileRow(profile: SubAgentProfile, providers: List<SubAgentProvider>, enabled: Boolean = true,
    settings: Boolean = false, draftAllowed: Boolean = enabled, draftRetry: (() -> Unit)? = null,
    authorization: (@Composable () -> Unit)? = null) {
    val editor = LocalConversationSubAgentEditor.current
    val usable = enabled && editor?.enabled == true
    val view = LocalView.current
    val currentUsable by rememberUpdatedState(usable)
    var modelPicker by remember(editor, profile.id) { mutableStateOf(false) }
    var thinkingPicker by remember(editor, profile.id) { mutableStateOf(false) }
    var rolePicker by remember(editor, profile.id) { mutableStateOf(false) }
    var tierPicker by remember(editor, profile.id, profile.role) { mutableStateOf(false) }
    var profileDraft by remember(editor, profile.id) { mutableStateOf<SubAgentProfileDraftSession?>(null) }
    var resolutionPicker by remember(editor, profile.id, profile.providerId, profile.modelId, profile.role) { mutableStateOf(false) }
    LaunchedEffect(usable, editor) {
        if (!usable) { modelPicker = false; thinkingPicker = false; rolePicker = false; tierPicker = false; resolutionPicker = false }
    }
    LaunchedEffect(profile.role) { rolePicker = false; tierPicker = false; thinkingPicker = false; modelPicker = false; resolutionPicker = false }
    val config = remember(profile.providerId, profile.modelId, profile.role, providers) {
        val provider = providers.firstOrNull { it.id == profile.providerId && it.isEnabled }
        val model = provider?.models?.firstOrNull { it.id == profile.modelId && it.isEnabled }
        if (provider == null || model == null || model.supportsSpeechSynthesis ||
            !profile.acceptsModel(model.supportsImageGeneration, model.supportsVideoGeneration)) null
        else runCatching { buildSubAgentModelConfig(provider, model) }.getOrNull()
    }
    val media = if (profile.isMedia && config != null) MediaReasoningSettings.resolve(config, profile.role) else null
    val canThink = config != null && (!profile.isMedia || media?.status == MediaReasoningSettings.Status.SUPPORTED)
    val thinking = config?.let { subAgentThinkingPreference(profile, it, LocalConversationAgentStyle.current) }
    val effective = thinking?.effort
    val efforts = thinking?.options.orEmpty()
    val thinkingLabel = when {
        config == null -> "未选择模型"
        profile.isMedia && !canThink -> media?.label ?: "当前接口未适配"
        profile.isMedia && effective !in efforts -> "原档位不可用，请重选"
        else -> effective?.displayName ?: "未启用"
    }
    LaunchedEffect(profile.providerId, profile.modelId) { thinkingPicker = false }
    Column(verticalArrangement = Arrangement.spacedBy(if (settings) 0.dp else 12.dp)) {
        if (settings) {
            SubAgentSettingRow("模型", config?.let { it.modelDisplayName.ifBlank { it.model } }
                ?: if (profile.modelId.isBlank()) "选择模型" else "模型不可用",
                Icons.Rounded.ViewInAr, "${profile.name}模型", enabled = usable, badge = config?.providerName,
                onClick = { if (currentUsable) { TouchHaptics.click(view); modelPicker = true } },
                onLongClick = { if (currentUsable && canThink) { TouchHaptics.longPress(view); thinkingPicker = true } },
                trailing = { SubAgentGptSpeedButton(profile, providers, usable) })
            Box(Modifier.fillMaxWidth()) {
                SubAgentSettingRow("职责", profile.roleLabel, Icons.Rounded.Assignment,
                    "选择${profile.name}职责", enabled = usable, dropdown = true,
                    onClick = { if (currentUsable) { TouchHaptics.click(view); rolePicker = !rolePicker } })
                Box(Modifier.align(Alignment.CenterEnd).size(40.dp)) {
                    if (usable) SubAgentDropdownMenu(rolePicker, { rolePicker = false }, Modifier.selectableGroup()) {
                        listOf("implementation" to "执行", "review" to "审查／总结", "image_generation" to "图片生成", "video_generation" to "视频生成").forEach { (role, label) ->
                            SubAgentSelectionItem(label, profile.role == role) {
                                if (currentUsable) editor?.updateProfile(profile.id) { it.withRole(role) }
                                rolePicker = false
                            }
                        }
                    }
                }
            }
            if (profile.supportsTaskTier) {
                Box(Modifier.fillMaxWidth()) {
                    SubAgentSettingRow("任务分工", profile.tier?.label ?: "未设置分工", Icons.Rounded.AccountTree,
                        "设置${profile.name}任务分工", enabled = usable, dropdown = true,
                        onClick = { if (currentUsable) { TouchHaptics.click(view); tierPicker = !tierPicker } })
                    Box(Modifier.align(Alignment.CenterEnd).size(40.dp)) {
                        if (usable) SubAgentDropdownMenu(tierPicker, { tierPicker = false }, Modifier.selectableGroup()) {
                            (listOf<SubAgentTaskTier?>(null) + SubAgentTaskTier.entries).forEach { tier ->
                                SubAgentSelectionItem(tier?.label ?: "未设置分工", profile.tier == tier) {
                                    if (currentUsable) editor?.updateProfile(profile.id) { latest -> if (latest.supportsTaskTier) latest.copy(tier = tier) else latest }
                                    tierPicker = false
                                }
                            }
                        }
                    }
                }
            }
            if (profile.role == "image_generation") {
                SubAgentSettingRow("默认分辨率", profile.imageResolution?.let(ImageResolutionTier::label) ?: "跟随接口",
                    Icons.Rounded.ViewInAr, "设置${profile.name}分辨率", enabled = usable && config != null,
                    onClick = { if (currentUsable && config != null) { TouchHaptics.click(view); resolutionPicker = true } })
            }
            SubAgentSettingRow("思考深度", thinkingLabel, Icons.Rounded.AutoAwesome, "调整${profile.name}思考深度", enabled = usable && canThink,
                onClick = { if (currentUsable && canThink) { TouchHaptics.click(view); thinkingPicker = true } })
            SubAgentParallelLimitRow(profile, config, usable)
        } else if (LocalConversationAgentStyle.current) {
            val colors = conversationAgentColors()
            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                color = androidx.compose.ui.graphics.Color(if (colors.dark) 0x03ffffff else 0x1cffffff),
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.line), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(profile.name, Modifier.weight(1f), fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.text)
                        authorization?.invoke()
                        app.daiyu.android.ui.components.subagent.ConversationAgentIconButton(
                            modifier = Modifier.size(27.dp, 29.dp), enabled = usable, onClick = {
                            if (currentUsable && editor != null) SubAgentProfileDraftSession.open(editor, profile)?.let { TouchHaptics.click(view); profileDraft = it }
                        }) { Icon(Icons.Rounded.MoreHoriz, "配置${profile.name}", Modifier.size(16.dp), tint = colors.accent) }
                    }
                    Row(Modifier.fillMaxWidth().semantics { contentDescription = "${profile.name}模型" }
                        .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                            role = Role.Button, enabled = usable, hapticFeedbackEnabled = false,
                            onClickLabel = "选择${profile.name}模型", onLongClickLabel = "调整${profile.name}思考深度",
                            onClick = { if (currentUsable) { TouchHaptics.click(view); modelPicker = true } },
                            onLongClick = { if (currentUsable && canThink) { TouchHaptics.longPress(view); thinkingPicker = true } })
                        .padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Icon(Icons.Rounded.ViewInAr, null, Modifier.size(12.dp), tint = colors.accent)
                        Text(config?.let { it.modelDisplayName.ifBlank { it.model } } ?: if (profile.modelId.isBlank()) "无" else "模型不可用",
                            Modifier.weight(1f), fontSize = 10.sp, color = if (config == null) colors.muted else colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Rounded.ExpandMore, null, Modifier.size(12.dp), tint = colors.accent)
                    }
                    HorizontalDivider(color = colors.line)
                    SubAgentGptSpeedButton(profile, providers, usable, Modifier.padding(top = 7.dp))
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().heightIn(min = 88.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).heightIn(min = 64.dp).semantics { contentDescription = "${profile.name}模型" }
                    .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                        role = Role.Button, enabled = usable, hapticFeedbackEnabled = false,
                        onClickLabel = "选择${profile.name}模型", onLongClickLabel = "调整${profile.name}思考深度",
                        onClick = { if (currentUsable) { TouchHaptics.click(view); modelPicker = true } },
                        onLongClick = { if (currentUsable && canThink) { TouchHaptics.longPress(view); thinkingPicker = true } }),
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
                    Text(profile.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(config?.let { it.modelDisplayName.ifBlank { it.model } } ?: if (profile.modelId.isBlank()) "无" else "模型不可用",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (config != null) Text(config.providerName + " · 思考 $thinkingLabel", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SubAgentGptSpeedButton(profile, providers, usable)
                IconButton(modifier = Modifier.size(48.dp), enabled = usable, onClick = {
                    if (currentUsable && editor != null) SubAgentProfileDraftSession.open(editor, profile)?.let { TouchHaptics.click(view); profileDraft = it }
                }) { Icon(Icons.Rounded.MoreVert, "配置${profile.name}", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface) }
            }
        }
    }
    profileDraft?.let { session ->
        SubAgentProfileConfigDialog(session, editor, providers, enabled = draftAllowed, onDismiss = {
            session.dismiss(); if (profileDraft === session) profileDraft = null
        }, onRetry = { if (draftRetry != null) draftRetry() else session.editor.retry() })
    }
    if (usable && resolutionPicker && config != null) {
        val verifiedGrok = config.grokImage
        AlertDialog(onDismissRequest = { resolutionPicker = false }, title = { Text("默认分辨率") }, text = { Column {
            Text("低、中、高、超高表示请求档位，不承诺固定像素。自然语言和本次子代理参数可覆盖此默认值。" +
                if (verifiedGrok) "当前 Grok 实测端点仅验证低、高；中、超高不可选。" else "实际支持取决于端点映射。")
            (listOf<String?>(null) + ImageResolutionTier.values).forEach { tier ->
                val allowed = !verifiedGrok || tier == null || tier in setOf("low", "high")
                TextButton(enabled = usable && allowed, onClick = {
                    if (currentUsable) editor?.updateProfile(profile.id) { latest ->
                        if (latest.providerId == profile.providerId && latest.modelId == profile.modelId && latest.role == "image_generation") latest.copy(imageResolution = tier) else latest
                    }
                    resolutionPicker = false
                }) { Text(tier?.let(ImageResolutionTier::label) ?: "跟随接口") }
            }
        } }, confirmButton = { TextButton(onClick = { resolutionPicker = false }) { Text("关闭") } })
    }
    if (usable && modelPicker) {
        val all = SubAgentModelPickerProjector.project(providers, profile.providerId, profile.modelId)
        val models = all.copy(providerGroups = all.providerGroups.map { group ->
            group.copy(models = group.models.filter { profile.acceptsModel(it.supportsImageGeneration, it.supportsVideoGeneration) })
        }.filter { it.models.isNotEmpty() })
        SubAgentModelPickerDialog(models, true, { modelPicker = false }, { provider, model ->
            if (currentUsable) editor?.saveModel(profile.id, ModelFeatureSelection(true, provider, model), expected = profile)
            modelPicker = false
        }, "选择${profile.name}模型", onClearSelection = {
            if (currentUsable) editor?.saveModel(profile.id, ModelFeatureSelection(true, "", ""), expected = profile)
            modelPicker = false
        }, highlightSelection = true)
    }
    if (usable && thinkingPicker && config != null && canThink) {
        val effort = requireNotNull(effective)
        val options = efforts
        SubAgentThinkingEffortPickerDialog(true, effort, options.ifEmpty { listOf(effort) }, { thinkingPicker = false }, { next ->
            if (currentUsable && next in options) editor?.updateProfile(profile.id) { latest ->
                if (latest.role == profile.role && latest.providerId == profile.providerId && latest.modelId == profile.modelId) latest.copy(reasoning = next) else latest
            }
        }, description = "${profile.name} · 仅影响此代理" + if (profile.isMedia) "；档位依据端点显式映射，不代表执行代理式思考或可见思考过程" else "")
    }
}
