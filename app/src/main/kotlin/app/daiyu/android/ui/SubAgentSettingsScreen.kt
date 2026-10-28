package app.daiyu.android.ui

import app.daiyu.android.ui.navigation.PageBackStack
import app.daiyu.android.ui.app.DaiyuPalette
import app.daiyu.android.ui.app.daiyuSwitchColors

import app.daiyu.android.ui.navigation.PageBackHandler as BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import app.daiyu.android.ui.components.DaiyuAlertDialog as AlertDialog
import androidx.compose.material3.*
import app.daiyu.android.ui.haptics.material.TextButton
import app.daiyu.android.ui.haptics.material.FilledTonalButton
import app.daiyu.android.ui.haptics.material.IconButton
import app.daiyu.android.ui.haptics.material.Switch
import app.daiyu.android.ui.haptics.material.DropdownMenuItem
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.daiyu.android.delegation.*
import app.daiyu.android.ui.components.*
import app.daiyu.android.ui.screens.settings.settingsPageBackground
import app.daiyu.android.ui.layout.horizontalCutoutPadding

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubAgentSettingsScreen(
    onBack: () -> Unit,
    repositoryFactory: (() -> ConversationSubAgentPreferences)? = null,
    isCurrentRoute: Boolean = true,
    modelSource: SubAgentModelSource = SubAgentModelSource.Empty,
) {
    val context = LocalContext.current.applicationContext
    val repository = remember { repositoryFactory?.invoke() ?: ConversationSubAgentPreferences(context.getSharedPreferences("subagent_configuration", 0)) }
    val directory = rememberSubAgentPresetDirectory(repository)
    var selectedId by remember { mutableStateOf<String?>(null) }
    val group = directory.entries.firstOrNull { it.id == selectedId }
    LaunchedEffect(directory.entries, directory.loading, directory.error) {
        if (!directory.loading && directory.error == null && selectedId != null && group == null) selectedId = null
    }
    PageBackStack(group?.id) {
        if (group != null) {
            key(group.id) {
                val owner = remember(group.id) { SubAgentConfigKey.Preset(group.id) }
                val editor = remember(owner, modelSource) {
                    ConversationSubAgentEditor(owner, repository, modelSource) {
                        selectedId == owner.value && try { repository.presetExists(owner.value) } catch (_: Exception) { false }
                    }
                }
                DisposableEffect(editor) { onDispose { editor.dispose() } }
                val detailBack = { editor.dispose(); selectedId = null }
                BackHandler(enabled = isCurrentRoute, onBack = detailBack)
                CompositionLocalProvider(LocalConversationSubAgentEditor provides editor) {
                    SubAgentPresetDetail(editor, group.name, detailBack, isCurrentRoute, modelSource)
                }
            }
            return@PageBackStack
        }
        var add by remember { mutableStateOf(false) }
        var rename by remember { mutableStateOf<SubAgentPreset?>(null) }
        var delete by remember { mutableStateOf<SubAgentPreset?>(null) }
        var actionsPreset by remember { mutableStateOf<SubAgentPreset?>(null) }
        var name by remember { mutableStateOf("") }
        val view = LocalView.current
        val editable = !directory.loading && directory.error == null
        CompositionLocalProvider(LocalRippleConfiguration provides null) {
            WithoutPressRipple {
                Scaffold(containerColor = settingsPageBackground(),
                    topBar = { TopAppBar(title = { Text("子代理组") }, navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                    }, colors = TopAppBarDefaults.topAppBarColors(containerColor = settingsPageBackground())) },
                    floatingActionButtonPosition = FabPosition.Center,
                    floatingActionButton = {
                        FilledTonalButton(enabled = editable, onClick = {
                            name = ""; add = true
                        }, modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 168.dp), shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                            elevation = ButtonDefaults.filledTonalButtonElevation(defaultElevation = 3.dp)) {
                            Text("添加子代理组", style = MaterialTheme.typography.bodyLarge)
                        }
                    }) { padding ->
                    LazyColumn(Modifier.fillMaxSize().padding(padding).horizontalCutoutPadding(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            SubAgentPresetDirectoryStatus(directory)
                        }
                        items(directory.entries, key = { it.id }) { preset ->
                            SubAgentPresetCard(preset, editable, "编辑子代理组${preset.name}", settingsStyle = true,
                                onClick = { if (editable) directory.change {
                                    check(repository.presetExists(preset.id)) { "子代理组不存在，请重试" }
                                    selectedId = preset.id
                                } },
                                trailing = {
                                    IconButton(modifier = Modifier.padding(end = 8.dp).size(48.dp), enabled = editable, onClick = {
                                        if (editable) { actionsPreset = preset }
                                    }) { Icon(Icons.Rounded.MoreVert, "${preset.name}组更多操作") }
                                })
                        }
                    }
                }
                actionsPreset?.takeIf { editable }?.let { preset ->
                    SubAgentPresetActionsSheet(preset, onDismiss = { actionsPreset = null },
                        onRename = { if (editable) { name = preset.name; rename = preset }; actionsPreset = null },
                        onDelete = { if (editable) delete = preset; actionsPreset = null })
                }
                if (add || rename != null) SubAgentPresetNameDialog(
                    title = if (add) "添加子代理组" else "重命名子代理组", name = name,
                    onNameChange = { name = it }, saveEnabled = editable && name.trim().isNotBlank(),
                    onDismiss = { add = false; rename = null }, onSave = {
                        if (directory.change {
                            if (add) repository.addPreset(name.trim())
                            else check(repository.renamePreset(requireNotNull(rename).id, name.trim())) { "子代理组不存在，请重试" }
                        }) { add = false; rename = null }
                    })
                delete?.let { preset ->
                    AlertDialog(onDismissRequest = { delete = null }, title = { Text("删除子代理组？") },
                        text = { Text("删除“${preset.name}”不会改变已应用此组的会话配置。") },
                        dismissButton = { TextButton(onClick = { delete = null }) { Text("取消") } },
                        confirmButton = { TextButton(enabled = editable,
                            colors = ButtonDefaults.textButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer), onClick = {
                            if (directory.change { check(repository.removePreset(preset.id)) { "子代理组不存在，请重试" } }) delete = null
                        }) { Text("删除") } })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubAgentPresetDetail(editor: ConversationSubAgentEditor, groupName: String, onBack: () -> Unit,
    isCurrentRoute: Boolean, modelSource: SubAgentModelSource) {
    val state = editor.observe()
    val lastLoadedConfig = remember(editor) { arrayOfNulls<ConversationSubAgentConfig>(1) }
    val loadedConfig = (state as? SubAgentEditorState.Loaded)?.config
    if (loadedConfig != null) lastLoadedConfig[0] = loadedConfig
    val config = loadedConfig ?: lastLoadedConfig[0]
    val profiles = config?.profiles.orEmpty()
    val editable = editor.enabled
    val providers by modelSource.providers.collectAsState()
    var addedDraft by remember(editor) { mutableStateOf<SubAgentProfileDraftSession?>(null) }
    var rename by remember(editor) { mutableStateOf<SubAgentProfile?>(null) }
    var delete by remember(editor) { mutableStateOf<SubAgentProfile?>(null) }
    var name by remember(editor) { mutableStateOf("") }
    val view = LocalView.current
    LaunchedEffect(editor, editable) { if (!editable) { rename = null; delete = null } }
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
    WithoutPressRipple {
        Scaffold(containerColor = settingsPageBackground(),
            topBar = { TopAppBar(title = { Text(groupName) }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = settingsPageBackground())) },
            floatingActionButtonPosition = FabPosition.Center,
            floatingActionButton = {
                FilledTonalButton(onClick = {
                    if (editor.enabled) SubAgentProfileDraftSession.open(editor)?.let { addedDraft = it }
                }, enabled = editable, modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 168.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                    elevation = ButtonDefaults.filledTonalButtonElevation(defaultElevation = 3.dp)) {
                    Text("添加子代理", style = MaterialTheme.typography.bodyLarge)
                }
            }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).horizontalCutoutPadding(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp)) {
                    item {
                        Text(when (state) {
                            is SubAgentEditorState.Error -> "子代理配置读取或保存失败：${state.reason}"
                            SubAgentEditorState.Loading -> "正在读取子代理组配置…"
                            else -> if (!editable) "当前子代理组暂不可修改。"
                                else "配置属于此预设组；会话应用时复制，后续修改互不影响。"
                        }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 4.dp))
                        if (state is SubAgentEditorState.Error) TextButton(onClick = { editor.retry() }) { Text("重试恢复配置") }
                    }
                    if (config != null) {
                        items(profiles, key = { it.id }) { profile ->
                            Column(Modifier.fillMaxWidth().padding(top = 12.dp).alpha(if (editable) 1f else 0.38f)
                                .clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(usagePalette().sheet))
                                .padding(16.dp)) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(when (profile.role) {
                                            "review" -> Icons.Rounded.FactCheck
                                            "image_generation" -> Icons.Rounded.Image
                                            "video_generation" -> Icons.Rounded.Videocam
                                            else -> Icons.Rounded.AccountTree
                                        }, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
                                        Text(profile.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Box(Modifier.requiredSize(36.dp, 22.dp).wrapContentSize(unbounded = true)
                                            .semantics { contentDescription = "启用${profile.name}" }, contentAlignment = Alignment.Center) {
                                            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                                                Switch(profile.enabled, enabled = editable, onCheckedChange = { active ->
                                                    if (editor.enabled) { editor.updateProfile(profile.id) { it.copy(enabled = active) } }
                                                }, colors = daiyuSwitchColors(SwitchDefaults.colors(checkedTrackColor = DaiyuPalette.switchTrack,
                                                    checkedBorderColor = DaiyuPalette.switchTrack)), modifier = Modifier.scale(0.7f))
                                            }
                                        }
                                        var expanded by remember(editor, profile.id) { mutableStateOf(false) }
                                        LaunchedEffect(editable) { if (!editable) expanded = false }
                                        Box {
                                            IconButton(enabled = editable, onClick = { if (editor.enabled) { expanded = true } }) {
                                                Icon(Icons.Rounded.MoreVert, "${profile.name}更多操作")
                                            }
                                            if (editable) SubAgentDropdownMenu(expanded, { expanded = false }) {
                                                DropdownMenuItem(text = { Text("配置") }, leadingIcon = { Icon(Icons.Rounded.Tune, null) },
                                                    onClick = {
                                                        if (editor.enabled) SubAgentProfileDraftSession.open(editor, profile)?.let { addedDraft = it }
                                                        expanded = false
                                                    })
                                                DropdownMenuItem(text = { Text("重命名") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                                                    onClick = { if (editor.enabled) { name = profile.name; rename = profile }; expanded = false })
                                                DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                                                    onClick = { if (editor.enabled) delete = profile; expanded = false })
                                            }
                                        }
                                    }
                                    SubAgentProfileRow(profile, providers, enabled = editable, settings = true, draftAllowed = true)
                                }
                            }
                        }
                    }
                }
            }
        }
        addedDraft?.let { session ->
            SubAgentProfileConfigDialog(session, editor, providers, enabled = isCurrentRoute, onDismiss = {
                session.dismiss(); if (addedDraft === session) addedDraft = null
            })
        }
        rename?.let { profile ->
            if (editable) SubAgentPresetNameDialog(title = "重命名代理", fieldHint = "名称", name = name,
                onNameChange = { if (editor.enabled) name = it }, saveEnabled = name.trim().isNotBlank(),
                onDismiss = { rename = null }, onSave = {
                    if (editor.enabled && editor.updateProfile(profile.id) { it.copy(name = name.trim()) } is ConversationSubAgentPreferences.WriteResult.Saved) rename = null
                })
        }
        delete?.let { profile ->
            if (editable) AlertDialog(onDismissRequest = { delete = null }, title = { Text("删除代理？") },
                text = { Text("将删除“${profile.name}”的配置，不会删除提供商或模型。") },
                dismissButton = { TextButton(onClick = { delete = null }) { Text("取消") } },
                confirmButton = { TextButton(enabled = editable,
                    colors = ButtonDefaults.textButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer), onClick = {
                    if (editor.enabled && editor.remove(profile.id) is ConversationSubAgentPreferences.WriteResult.Saved) delete = null
                }) { Text("删除") } })
        }
    }
    }
}
