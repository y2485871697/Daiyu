package app.daiyu.android.ui.screens.assistants

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.ExperimentalFoundationApi
import app.daiyu.android.ui.haptics.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.daiyu.android.R
import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.assistants.AssistantStore
import app.daiyu.android.assistants.AssistantAssets
import app.daiyu.android.assistants.duplicate
import app.daiyu.android.ui.components.AssistantAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text

/** Stateless list boundary; copy/delete are delegated to the owning store by the host. */
@Composable
fun AssistantPickerDialog(
    show: Boolean,
    assistants: List<AssistantProfile>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onDelete: (String) -> Unit,
    assets: AssistantAssets,
    onCreate: () -> Unit,
    editing: Boolean = false,
    onOutsideDismiss: () -> Unit = onDismiss,
) {
    var searchFocused by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var actionProfileId by remember { mutableStateOf<String?>(null) }
    var deleteProfile by remember { mutableStateOf<AssistantProfile?>(null) }
    val colors = assistantPreviewPalette()
    val s = assistantPreviewScale()
    LaunchedEffect(show) {
        if (!show) { searchFocused = false; searchQuery = ""; actionProfileId = null; deleteProfile = null }
    }
    if (!show || editing) return
    val filtered = remember(assistants, searchQuery) {
        val query = searchQuery.trim()
        assistants.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
    }
    val actionProfile = assistants.firstOrNull { it.id == actionProfileId }
    // Management is a view within the same glass sheet, not a stack of stock dialogs.
    AssistantPreviewDialog(show = actionProfile == null && deleteProfile == null,
        title = stringResource(R.string.assistant_picker_title), onDismiss = onDismiss,
        showCloseButton = false,
        onOutsideDismiss = onOutsideDismiss,
        footer = {
            AssistantPreviewButton(stringResource(R.string.assistant_new), onCreate,
                Modifier.weight(1f).testTag("assistant-new"), secondary = true, icon = AssistantPreviewIcons.Add)
            AssistantPreviewButton(stringResource(R.string.assistant_done), onDismiss,
                Modifier.weight(1f).testTag("assistant-done"), primary = true)
        }) {
        AssistantSearchField(searchQuery, { searchQuery = it }, stringResource(R.string.assistant_search),
            focused = searchFocused, onFocusChanged = { searchFocused = it },
            modifier = Modifier.testTag("assistant-search"),
            fieldModifier = Modifier.testTag("assistant-search-input"))
        Row(Modifier.fillMaxWidth().testTag("assistant-list-meta")
            .padding(horizontal = 4.dp * s, vertical = 8.dp * s),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.assistant_count, filtered.size), color = colors.muted,
                modifier = Modifier.alignByBaseline(), fontSize = (11f * s).sp, lineHeight = (16f * s).sp)
            Text(stringResource(R.string.assistant_list_hint), color = colors.muted,
                modifier = Modifier.alignByBaseline(), fontSize = (11f * s).sp, lineHeight = (16f * s).sp)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("assistant-list"),
            contentPadding = PaddingValues(start = 2.dp * s, end = 2.dp * s, top = 2.dp * s, bottom = 7.dp * s),
            verticalArrangement = Arrangement.spacedBy(5.dp * s)) {
            items(filtered, key = { it.id }) { profile ->
                AssistantProfileCard(profile, profile.id == selectedId,
                    onClick = { onSelect(profile.id); onDismiss() }, onLongClick = { actionProfileId = profile.id }, assets = assets,
                    modifier = Modifier.testTag("assistant-item-${profile.id}"))
            }
            if (filtered.isEmpty()) item {
                Text(stringResource(R.string.assistant_empty), color = colors.muted, fontSize = (13f * s).sp,
                    lineHeight = (20f * s).sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp * s, vertical = 22.dp * s))
            }
        }
    }
    actionProfile?.let { profile ->
        AssistantActionsDialog(profile, assets = assets, canDelete = assistants.size > 1,
            onCopy = { actionProfileId = null; onCopy(profile.id) },
            onEdit = { onEdit(profile.id) },
            onDelete = { actionProfileId = null; deleteProfile = profile }, onDismiss = { actionProfileId = null },
            onOutsideDismiss = onOutsideDismiss)
    }
    deleteProfile?.let { profile ->
        AssistantDeleteDialog(profile, assets = assets, canDelete = assistants.size > 1, onDismiss = { deleteProfile = null },
            onConfirm = { deleteProfile = null; onDelete(profile.id) }, onOutsideDismiss = onOutsideDismiss)
    }
}

/** Store-backed convenience boundary, retains copy-memory and delete-memory behavior. */
@Composable
fun AssistantPickerDialog(
    show: Boolean,
    store: AssistantStore,
    assets: AssistantAssets,
    onSelect: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
    selectedId: String? = null,
    useGlobalSelection: Boolean = true,
    canMutate: () -> Boolean = { true },
    editing: Boolean = false,
    onOutsideDismiss: () -> Unit = onDismiss,
) {
    val profiles by store.assistants.collectAsState()
    val active by store.selectedId.collectAsState()
    val scope = rememberCoroutineScope()
    fun mutate(action: () -> Unit) {
        scope.launch {
            // Admit on the UI thread before handing an accepted write to the worker.
            if (!canMutate()) return@launch
            withContext(Dispatchers.IO) { action() }
        }
    }
    AssistantPickerDialog(show, profiles, if(useGlobalSelection) selectedId ?: active else selectedId, onSelect, onEdit, onDismiss,
        onCreate = { if (canMutate()) onCreate() },
        onCopy = { id -> mutate {
            val source = requireNotNull(store.read(id)) { "助手不存在" }
            val copy = store.duplicate(id)
            assets.copyMemory(id, copy.id)
            source.avatarFileName?.let { fileName ->
                store.save(copy.copy(avatarFileName = assets.copyAvatar(fileName, copy.id)))
            }
        } },
        onDelete = { id -> mutate {
            val avatar = store.read(id)?.avatarFileName
            store.delete(id)
            assets.deleteMemory(id)
            assets.deleteAvatar(avatar)
        } }, assets = assets, editing = editing, onOutsideDismiss = onOutsideDismiss)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AssistantProfileCard(
    profile: AssistantProfile,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    assets: AssistantAssets,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val bitmap = remember(assets, profile.avatarFileName) { assets.avatarBitmap(profile.avatarFileName) }
    val colors = assistantPreviewPalette()
    val s = assistantPreviewScale()
    val shape = RoundedCornerShape(15.dp * s)
    Row(modifier.fillMaxWidth().heightIn(min = 76.dp * s).clip(shape)
        .background(if (selected) colors.soft else androidx.compose.ui.graphics.Color.Transparent)
        .border(1.dp, if (selected) colors.line else androidx.compose.ui.graphics.Color.Transparent, shape)
        .combinedClickable( interactionSource = remember { MutableInteractionSource() }, indication = null,
            onClick = { onClick() },
            onLongClick = { onLongClick() }).padding(1.dp).padding(10.dp * s),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp * s)) {
        AssistantAvatar(bitmap = bitmap, size = 40.dp * s, modifier = Modifier.testTag("assistant-avatar-${profile.id}"))
        Column(Modifier.weight(1f)) {
            Text(profile.name, color = colors.text, fontSize = (15f * s).sp,
                fontWeight = FontWeight(550), lineHeight = (21f * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(profile.description.ifBlank { profile.prompt.lineSequence().firstOrNull { it.isNotBlank() } ?: "自定义助手" },
                color = colors.muted, fontSize = (12f * s).sp, lineHeight = (18f * s).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp * s))
        }
        if (selected) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp * s)) {
            Icon(AssistantPreviewIcons.Check, null, Modifier.size(11.dp * s), tint = colors.accent)
            Text(stringResource(R.string.assistant_current), color = colors.accent, fontSize = (11f * s).sp, lineHeight = (16f * s).sp, maxLines = 1)
        }
    }
}

@Composable
internal fun AssistantActionsDialog(profile: AssistantProfile, assets: AssistantAssets, canDelete: Boolean, onCopy: () -> Unit,
    onEdit: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit,
    onOutsideDismiss: () -> Unit = onDismiss) {
    AssistantPreviewDialog(show = true, title = stringResource(R.string.assistant_actions_title), onDismiss = onDismiss, onBack = onDismiss,
        showCloseButton = false,
        onOutsideDismiss = onOutsideDismiss,
        footer = { AssistantPreviewButton(stringResource(R.string.assistant_back_to_list), onDismiss, Modifier.weight(1f)) }) {
        AssistantDialogIdentity(profile, assets)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistantPreviewButton(stringResource(R.string.ui_copy_assistant), onCopy, Modifier.fillMaxWidth().testTag("assistant-action-copy"),
                icon = AssistantPreviewIcons.Copy, actionRow = true)
            AssistantPreviewButton(stringResource(R.string.ui_edit_assistant), onEdit, Modifier.fillMaxWidth().testTag("assistant-action-edit"),
                icon = AssistantPreviewIcons.Edit, actionRow = true)
            AssistantPreviewButton(stringResource(R.string.ui_delete_assistant), onDelete, Modifier.fillMaxWidth().testTag("assistant-action-delete"),
                danger = true, enabled = canDelete, icon = AssistantPreviewIcons.Delete, actionRow = true)
        }
    }
}

@Composable
internal fun AssistantDeleteDialog(profile: AssistantProfile, assets: AssistantAssets, canDelete: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit,
    onOutsideDismiss: () -> Unit = onDismiss) {
    val s = assistantPreviewScale()
    AssistantPreviewDialog(show = true, title = stringResource(R.string.assistant_delete_title),
        onDismiss = onDismiss, onBack = onDismiss, onOutsideDismiss = onOutsideDismiss, footer = {
            AssistantPreviewButton(stringResource(R.string.action_cancel), onDismiss, Modifier.weight(1f))
            AssistantPreviewButton(stringResource(R.string.action_delete), onConfirm, Modifier.weight(1f), danger = true, deletionAppearance = true, enabled = canDelete)
        }) {
        AssistantDialogIdentity(profile, assets)
        Text(stringResource(R.string.assistant_delete_body, profile.name), color = assistantPreviewPalette().text,
            fontSize = (14f * s).sp, lineHeight = (22f * s).sp, modifier = Modifier.padding(start = 2.dp * s, end = 2.dp * s, top = 3.dp * s, bottom = 10.dp * s)
                .verticalScroll(rememberScrollState()))
    }
}

@Composable
private fun AssistantDialogIdentity(profile: AssistantProfile, assets: AssistantAssets) {
    val s = assistantPreviewScale()
    val colors = assistantPreviewPalette()
    val bitmap = remember(assets, profile.avatarFileName) { assets.avatarBitmap(profile.avatarFileName) }
    Row(Modifier.fillMaxWidth().padding(start = 2.dp * s, end = 2.dp * s, top = 7.dp * s, bottom = 16.dp * s),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp * s)) {
        AssistantAvatar(bitmap = bitmap, size = 44.dp * s)
        Column(Modifier.weight(1f)) {
            Text(profile.name, color = colors.text, fontSize = (15f * s).sp, lineHeight = (22f * s).sp,
                fontWeight = FontWeight(550))
            Text(profile.description.ifBlank { profile.prompt.lineSequence().firstOrNull { it.isNotBlank() } ?: "自定义助手" },
                color = colors.muted, fontSize = (12f * s).sp, lineHeight = (18f * s).sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp * s))
        }
    }
}
