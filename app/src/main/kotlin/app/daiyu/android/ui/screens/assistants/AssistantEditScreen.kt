package app.daiyu.android.ui.screens.assistants

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DesignServices
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.daiyu.android.R
import app.daiyu.android.assistants.AssistantAssets
import app.daiyu.android.assistants.AssistantMemoryDraft
import app.daiyu.android.assistants.AssistantEditSession
import app.daiyu.android.assistants.AssistantSkill
import app.daiyu.android.assistants.AssistantStore
import app.daiyu.android.ui.components.AssistantAvatar
import app.daiyu.android.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Eta's personality editor. Runtime skill discovery is supplied by the host, not copied. */
@Composable
fun AssistantEditScreen(
    assistantId: String,
    onBack: () -> Unit,
    store: AssistantStore,
    assets: AssistantAssets,
    installedSkills: List<AssistantSkill> = emptyList(),
    onSaved: (String) -> Unit = {},
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
    var prompt by remember(session) { mutableStateOf(original.prompt) }
    var avatarFileName by remember(session) { mutableStateOf(original.avatarFileName) }
    var memoryEnabled by remember(session) { mutableStateOf(original.memoryEnabled) }
    var persistedMemoryEnabled by remember(session) { mutableStateOf(original.memoryEnabled) }
    var enabledSkillIds by remember(session) { mutableStateOf(original.enabledSkillIds.toSet()) }
    var persistedSkillIds by remember(session) { mutableStateOf(original.enabledSkillIds.toSet()) }
    var memoryDraft by remember(session) { mutableStateOf("") }
    var memoryRevision by remember(session) { mutableStateOf("") }
    var memorySaved by remember(session) { mutableStateOf("") }
    var cropBitmap by remember(session) { mutableStateOf<Bitmap?>(null) }
    var saving by remember(session) { mutableStateOf(false) }
    val avatarBitmap = remember(assistantId, avatarFileName) { assets.avatarBitmap(avatarFileName) }
    val memoryBytes = remember(memoryDraft) { memoryDraft.toByteArray(Charsets.UTF_8).size }
    val memoryOverLimit = memoryBytes > AssistantAssets.MAX_MEMORY_BYTES
    val dirty = name.trim() != original.name || prompt != original.prompt || avatarFileName != original.avatarFileName ||
        memoryEnabled != persistedMemoryEnabled || enabledSkillIds != persistedSkillIds || memoryDraft != memorySaved

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
    fun persistAssistantToggles(nextMemory: Boolean = memoryEnabled, nextSkills: Set<String> = enabledSkillIds) {
        scope.launch {
            val result = runCatching { session.toggles(nextMemory, nextSkills) }
            if (!session.isActive) return@launch
            if (result.isSuccess) {
                persistedMemoryEnabled = nextMemory
                persistedSkillIds = nextSkills
                onSaved(assistantId)
            } else android.widget.Toast.makeText(context, result.exceptionOrNull()?.message ?: "技能目录发布失败，请重试", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    fun save() {
        if (!session.isActive || !dirty || saving || memoryOverLimit || memoryRevision.isBlank()) return
        val targetMemory = memoryDraft
        val baseRevision = memoryRevision
        val targetName = name
        val targetPrompt = prompt
        val targetMemoryEnabled = memoryEnabled
        val targetSkills = enabledSkillIds.toSet()
        val memoryChanged = targetMemory != memorySaved
        saving = true
        scope.launch {
            val result = runCatching {
                session.save(targetName, targetPrompt, targetMemoryEnabled, targetSkills) {
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
    MiuixScaffoldPage(title = stringResource(R.string.assistant_edit_title), onBack = { session.close(); onBack() },
        actions = {
            Text(text = stringResource(R.string.action_save),
                color = if (dirty && !saving && !memoryOverLimit) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                modifier = Modifier.padding(end = 12.dp).clickable(enabled = dirty && !saving && !memoryOverLimit, onClick = ::save))
        }) {
        item(key = "preview_title") { SmallTitle(stringResource(R.string.assistant_preview)) }
        item(key = "preview") {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AssistantAvatar(bitmap = avatarBitmap, size = 56.dp, modifier = Modifier.clickable(enabled = !saving) {
                        if (session.isActive && pickerOwner == null) {
                            pickerOwner = session
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    })
                    Text(text = name.trim().ifBlank { stringResource(R.string.assistant_unnamed) }, style = MiuixTheme.textStyles.title3)
                }
            }
        }
        item(key = "identity_title") { SmallTitle(stringResource(R.string.assistant_identity)) }
        item(key = "identity") {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextField(value = name, onValueChange = { if (session.isActive && !saving) name = it }, label = stringResource(R.string.assistant_name), enabled = !saving,
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            }
        }
        item(key = "prompt_title") { SmallTitle(stringResource(R.string.assistant_prompt_title)) }
        item(key = "prompt") {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                TextField(value = prompt, onValueChange = { if (session.isActive && !saving) prompt = it }, label = "", enabled = !saving,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).height(240.dp), singleLine = false)
            }
        }
        item(key = "memory_title") { SmallTitle(stringResource(R.string.ui_memory_b55ff5)) }
        item(key = "memory") {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                SwitchPreference(title = stringResource(R.string.ui_enable_memory_4b69b7),
                    summary = stringResource(R.string.assistant_memory_summary), checked = memoryEnabled,
                    onCheckedChange = { if (session.isActive && !saving) { memoryEnabled = it; persistAssistantToggles(nextMemory = it) } })
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    TextField(value = memoryDraft, onValueChange = {
                        if (session.isActive && !saving) { memoryEditor.edit(it); memoryDraft = it }
                    }, label = "MEMORY.md", enabled = !saving,
                        useLabelAsPlaceholder = true, singleLine = false, minLines = 6, maxLines = 12,
                        textStyle = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
                    if (memoryOverLimit) Text(text = stringResource(R.string.memory_over_limit), color = MiuixTheme.colorScheme.error,
                        style = MiuixTheme.textStyles.footnote1, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        item(key = "skills_title") { SmallTitle(stringResource(R.string.route_skills)) }
        item(key = "skills") {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                Text(text = stringResource(R.string.assistant_skills_summary), style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                if (installedSkills.isEmpty()) {
                    Text(text = stringResource(R.string.ui_no_skills_installed_yet_4e960f), style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
                } else installedSkills.forEach { skill ->
                    AssistantSkillSwitchRow(skill, enabledSkillIds.contains(skill.id), !saving) { enabled ->
                        if (session.isActive && !saving) {
                            val next = if (enabled) enabledSkillIds + skill.id else enabledSkillIds - skill.id
                            enabledSkillIds = next
                            persistAssistantToggles(nextSkills = next)
                        }
                    }
                }
            }
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

/** The no-menu branch of the original SkillSwitchRow, with the original icons and metrics. */
@Composable
private fun AssistantSkillSwitchRow(skill: AssistantSkill, checked: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val description = skill.description.ifBlank { stringResource(R.string.skills_no_description) }
    BasicComponent(startAction = {
        Icon(imageVector = when (skill.id) {
            "self-improving-agent" -> Icons.Rounded.Refresh
            "skill-creator" -> Icons.Rounded.DesignServices
            else -> Icons.Rounded.Extension
        }, contentDescription = null, modifier = Modifier.padding(end = 6.dp).size(24.dp),
            tint = if (enabled) MiuixTheme.colorScheme.onBackground else MiuixTheme.colorScheme.disabledOnSurface)
    }, endActions = {
        Switch(checked = checked, onCheckedChange = onToggle, enabled = enabled, modifier = Modifier.align(Alignment.CenterVertically))
    }) {
        Text(text = skill.name, style = MiuixTheme.textStyles.headline1, fontWeight = FontWeight.Medium,
            color = if (enabled) MiuixTheme.colorScheme.onBackground else MiuixTheme.colorScheme.disabledOnSurface)
        Text(text = description, style = MiuixTheme.textStyles.body2,
            color = if (enabled) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.disabledOnSurface,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}
