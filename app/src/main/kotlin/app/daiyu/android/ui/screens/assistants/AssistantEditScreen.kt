package app.daiyu.android.ui.screens.assistants

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import app.daiyu.android.ui.navigation.PageBackHandler as BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import app.daiyu.android.ui.haptics.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.daiyu.android.R
import app.daiyu.android.assistants.AssistantAssets
import app.daiyu.android.assistants.AssistantMemoryDraft
import app.daiyu.android.assistants.AssistantEditSession
import app.daiyu.android.assistants.AssistantSkill
import app.daiyu.android.assistants.AssistantStore
import app.daiyu.android.ui.components.AssistantAvatar
import app.daiyu.android.ui.components.EditorScrollIsolation
import app.daiyu.android.ui.components.editorPageGestures
import app.daiyu.android.ui.components.isolateEditorScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text

/** Assistant editor; skill discovery and persistence remain owned by the host. */
@Composable
fun AssistantEditScreen(
    assistantId: String,
    onBack: () -> Unit,
    store: AssistantStore,
    assets: AssistantAssets,
    installedSkills: List<AssistantSkill> = emptyList(),
    onSaved: (String) -> Unit = {},
    onOutsideDismiss: () -> Unit = onBack,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val original = remember(assistantId, store) { store.read(assistantId) }
    if (original == null) {
        LaunchedEffect(assistantId) { onBack() }
        return
    }
    val session = remember(assistantId, store, assets) { AssistantEditSession(store, original) }
    DisposableEffect(session) { onDispose { session.close() } }
    val memoryEditor = remember(session) { AssistantMemoryDraft() }
    val initialReadTicket = remember(session) { memoryEditor.beginRead() }
    var name by remember(session) { mutableStateOf(original.name) }
    var description by remember(session) { mutableStateOf(original.description) }
    var prompt by remember(session) { mutableStateOf(original.prompt) }
    var avatarFileName by remember(session) { mutableStateOf(original.avatarFileName) }
    var memoryEnabled by remember(session) { mutableStateOf(original.memoryEnabled) }
    var enabledSkillIds by remember(session) { mutableStateOf(original.enabledSkillIds.toSet()) }
    var memoryDraft by remember(session) { mutableStateOf("") }
    var memoryRevision by remember(session) { mutableStateOf("") }
    var memorySaved by remember(session) { mutableStateOf("") }
    var cropBitmap by remember(session) { mutableStateOf<Bitmap?>(null) }
    var saving by remember(session) { mutableStateOf(false) }
    val avatarBitmap = remember(assistantId, avatarFileName) { assets.avatarBitmap(avatarFileName) }
    val memoryBytes = remember(memoryDraft) { memoryDraft.toByteArray(Charsets.UTF_8).size }
    val memoryOverLimit = memoryBytes > AssistantAssets.MAX_MEMORY_BYTES
    val dirty = description != original.description || name.trim() != original.name || prompt != original.prompt || avatarFileName != original.avatarFileName ||
        memoryEnabled != original.memoryEnabled || enabledSkillIds != original.enabledSkillIds.toSet() || memoryDraft != memorySaved

    LaunchedEffect(session) {
        val result = withContext(Dispatchers.IO) { runCatching { assets.memory(assistantId) } }
        if (session.isActive) result.onSuccess { snapshot ->
            memoryEditor.completeRead(initialReadTicket, snapshot)
            memoryDraft = memoryEditor.content
            memorySaved = memoryEditor.saved
            memoryRevision = memoryEditor.revision.orEmpty()
        }.onFailure {
            android.widget.Toast.makeText(context, "记忆读取失败，已输入草稿保留；请重新打开后合并", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    // The launcher survives recomposition; a result still belongs to the page that launched it.
    var pickerOwner by remember { mutableStateOf<AssistantEditSession?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        val owner = pickerOwner
        pickerOwner = null
        if (uri != null && owner === session && session.isActive && !saving) cropBitmap = decodeAvatarBitmap(context, uri)
    }
    fun save() {
        if (!session.isActive || !dirty || saving || memoryOverLimit || memoryRevision.isBlank()) return
        val targetMemory = memoryDraft
        val baseRevision = memoryRevision
        val targetName = name
        val targetDescription = description
        val targetPrompt = prompt
        val targetMemoryEnabled = memoryEnabled
        val targetSkills = enabledSkillIds.toSet()
        val memoryChanged = targetMemory != memorySaved
        saving = true
        scope.launch {
            val result = runCatching {
                session.save(targetName, targetPrompt, targetMemoryEnabled, targetSkills, targetDescription) {
                    if (memoryChanged) assets.saveMemory(assistantId, targetMemory, baseRevision) else null
                }
            }
            if (!session.isActive) return@launch
            result.getOrNull()?.memory?.let { memoryRevision = it.revision; memorySaved = it.content }
            saving = false
            if (result.isSuccess) { onSaved(assistantId); session.close(); onBack() }
            else android.widget.Toast.makeText(context, result.exceptionOrNull()?.message ?: "保存失败，草稿已保留", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val colors = assistantPreviewPalette()
    val s = assistantPreviewScale()
    val scrollIsolation = remember(session) { EditorScrollIsolation() }
    val close = { session.close(); onBack() }
    BackHandler(onBack = close)
    AssistantPreviewDialog(show = true, title = stringResource(R.string.assistant_editor_title), onDismiss = close, onBack = close,
        showCloseButton = false,
        onOutsideDismiss = { session.close(); onOutsideDismiss() },
        footer = {
            AssistantPreviewButton(stringResource(R.string.action_cancel), close, Modifier.weight(1f).testTag("assistant-edit-cancel"))
            AssistantPreviewButton(stringResource(R.string.assistant_save_changes), ::save,
                Modifier.weight(1f).testTag("assistant-edit-save"), primary = true,
                enabled = dirty && !saving && !memoryOverLimit && memoryRevision.isNotBlank())
        }) {
        Column(Modifier.fillMaxWidth().weight(1f).testTag("assistant-editor")
            .editorPageGestures(scrollIsolation)
            .verticalScroll(rememberScrollState()).padding(horizontal = 2.dp * s, vertical = 1.dp * s)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp * s)
                .testTag("assistant-edit-avatar").clip(RoundedCornerShape(16.dp * s)).background(colors.soft)
                .clickable(enabled = !saving, interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    if (session.isActive && pickerOwner == null) {
                        pickerOwner = session
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }.padding(14.dp * s), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp * s)) {
                AssistantAvatar(bitmap = avatarBitmap, size = 64.dp * s, modifier = Modifier.testTag("assistant-avatar-image"))
                Text(name.ifBlank { stringResource(R.string.assistant_editor_name) }, color = colors.text,
                    fontSize = (18f * s).sp, lineHeight = (26f * s).sp, fontWeight = FontWeight(550),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("assistant-avatar-name"))
            }
            AssistantPreviewField(name, { if (session.isActive && !saving) name = it },
                stringResource(R.string.assistant_editor_name), enabled = !saving,
                fieldModifier = Modifier.testTag("assistant-edit-name"))
            AssistantPreviewField(description, { if (session.isActive && !saving) description = it },
                stringResource(R.string.assistant_description), enabled = !saving,
                fieldModifier = Modifier.testTag("assistant-edit-description"))
            AssistantPreviewField(prompt, { if (session.isActive && !saving) prompt = it },
                stringResource(R.string.assistant_system_prompt), enabled = !saving, singleLine = false,
                minHeight = 110.dp, maxHeight = 250.dp, fieldModifier = Modifier.testTag("assistant-edit-prompt").isolateEditorScroll(scrollIsolation))
            AssistantPreviewSection(stringResource(R.string.route_skills), Modifier.testTag("assistant-skills")) {
                if (installedSkills.isEmpty()) {
                    Text(stringResource(R.string.assistant_skills_none), color = colors.muted,
                        fontSize = (13f * s).sp, lineHeight = (20f * s).sp, maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().testTag("assistant-skills-empty"))
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp * s)) {
                        Text(stringResource(R.string.assistant_skills_caption), color = colors.muted,
                            fontSize = (12f * s).sp, lineHeight = (18f * s).sp, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.assistant_independent_switches), color = colors.muted,
                            fontSize = (11f * s).sp, lineHeight = (16f * s).sp)
                    }
                    Spacer(Modifier.height(7.dp * s))
                    installedSkills.forEachIndexed { index, skill ->
                        if (index > 0) {
                            Spacer(Modifier.height(3.dp * s))
                            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
                        }
                        AssistantSkillSwitchRow(skill, enabledSkillIds.contains(skill.id), !saving) { enabled ->
                            if (session.isActive && !saving) {
                                val next = if (enabled) enabledSkillIds + skill.id else enabledSkillIds - skill.id
                                enabledSkillIds = next
                            }
                        }
                    }
                }
            }
            AssistantPreviewSection(modifier = Modifier.testTag("assistant-memory")) {
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp * s), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp * s)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ui_memory_b55ff5), color = colors.text,
                            fontSize = (14f * s).sp, lineHeight = (21f * s).sp, fontWeight = FontWeight(550))
                        Text(stringResource(R.string.assistant_memory_caption), color = colors.muted,
                            fontSize = (11f * s).sp, lineHeight = (16f * s).sp, modifier = Modifier.padding(top = 4.dp * s))
                    }
                    AssistantPreviewSwitch(memoryEnabled, !saving, stringResource(R.string.assistant_enable),
                        Modifier.testTag("assistant-memory-switch")) {
                        if (session.isActive && !saving) { memoryEnabled = it }
                    }
                }
                AssistantPreviewField(memoryDraft, {
                    if (session.isActive && !saving) { memoryEditor.edit(it); memoryDraft = it }
                }, stringResource(R.string.assistant_memory_content), enabled = !saving, singleLine = false,
                    minHeight = 90.dp, maxHeight = 180.dp, bottomSpacing = 0.dp,
                    fieldModifier = Modifier.testTag("assistant-edit-memory").isolateEditorScroll(scrollIsolation))
                Text(stringResource(if (memoryEnabled) R.string.assistant_enabled else R.string.assistant_disabled),
                    color = colors.muted, fontSize = (11f * s).sp, lineHeight = (17f * s).sp,
                    modifier = Modifier.padding(start = 2.dp * s, end = 2.dp * s, top = 7.dp * s))
            }
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 15.dp * s).padding(horizontal = 2.dp * s)
            .testTag("assistant-edit-error")) {
            if (memoryOverLimit) Text(stringResource(R.string.memory_over_limit), color = colors.danger,
                fontSize = (12f * s).sp, lineHeight = (18f * s).sp)
        }
    }
    cropBitmap?.let { bitmap ->
        AvatarCropDialog(bitmap = bitmap, onCancel = { cropBitmap = null }, onConfirm = { cropped ->
            cropBitmap = null
            val accepted = session.isActive && !saving
            scope.launch {
                try {
                    if (!accepted) return@launch
                    // Publish a new immutable file name; a failed profile CAS must not
                    // overwrite an avatar file already referenced by a newer profile.
                    val result = runCatching { session.avatar { assets.saveAvatar(java.util.UUID.randomUUID().toString(), cropped) } }
                    if (session.isActive) result.onSuccess {
                        avatarFileName = it.avatarFileName
                        onSaved(assistantId)
                    }.onFailure {
                        android.widget.Toast.makeText(context, it.message ?: "保存失败，草稿已保留", android.widget.Toast.LENGTH_LONG).show()
                    }
                } finally {
                    if (cropped !== bitmap) cropped.recycle()
                    bitmap.recycle()
                }
            }
        })
    }
}

/** .ap-skill-row: text and an independent switch, without the settings preference chrome. */
@Composable
private fun AssistantSkillSwitchRow(skill: AssistantSkill, checked: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val colors = assistantPreviewPalette()
    val s = assistantPreviewScale()
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp * s), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp * s)) {
        Column(Modifier.weight(1f)) {
            Text(skill.name, color = colors.text, fontSize = (13f * s).sp, lineHeight = (20f * s).sp)
            Text(skill.description.ifBlank { stringResource(R.string.skills_no_description) }, color = colors.muted,
                fontSize = (11f * s).sp, lineHeight = (16f * s).sp, modifier = Modifier.padding(top = 3.dp * s))
        }
        AssistantPreviewSwitch(checked, enabled, stringResource(R.string.assistant_enable),
            Modifier.testTag("assistant-skill-${skill.id}"), onToggle)
    }
}
