package app.daiyu.android.ui.screens.assistants

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import app.daiyu.android.ui.haptics.TouchHaptics
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
) {
    var searchFocused by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var actionProfile by remember { mutableStateOf<AssistantProfile?>(null) }
    var deleteProfile by remember { mutableStateOf<AssistantProfile?>(null) }
    val colors = assistantPreviewPalette()
    val s = assistantPreviewScale()
    LaunchedEffect(show) {
        if (!show) { searchQuery = ""; actionProfile = null; deleteProfile = null }
    }
    val filtered = remember(assistants, searchQuery) {
        val query = searchQuery.trim()
        assistants.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
    }
    // Management is a view within the same glass sheet, not a stack of stock dialogs.
    AssistantPreviewDialog(show = show && actionProfile == null && deleteProfile == null,
        title = stringResource(R.string.assistant_picker_title), onDismiss = onDismiss,
        showCloseButton = false,
        footer = {
            // Creation remains a visual placeholder until its editor flow is connected.
            AssistantPreviewButton(stringResource(R.string.assistant_new), {},
                Modifier.weight(1f).testTag("assistant-new"), icon = AssistantPreviewIcons.Add)
            AssistantPreviewButton(stringResource(R.string.assistant_done), onDismiss,
                Modifier.weight(1f).testTag("assistant-done"), primary = true)
        }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp * s).testTag("assistant-search")
            .clip(RoundedCornerShape(13.dp * s)).background(colors.soft)
            .border(1.dp, if (searchFocused) colors.accent else colors.line, RoundedCornerShape(13.dp * s))
            .padding(1.dp).padding(horizontal = 11.dp * s),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp * s)) {
            Icon(AssistantPreviewIcons.Search, null, Modifier.size(14.dp * s), tint = colors.muted)
            BasicTextField(searchQuery, { searchQuery = it }, singleLine = true,
                modifier = Modifier.weight(1f).heightIn(min = 34.dp * s)
                    .testTag("assistant-search-input").onFocusChanged { searchFocused = it.isFocused },
                cursorBrush = SolidColor(colors.accent),
                textStyle = TextStyle(color = colors.text, fontSize = (11f * s).sp, lineHeight = (16.5f * s).sp),
                decorationBox = { innerTextField ->
                    Box(Modifier.padding(horizontal = 2.dp * s, vertical = 1.dp * s), contentAlignment = Alignment.CenterStart) {
                        if (searchQuery.isEmpty()) Text(stringResource(R.string.assistant_search), color = colors.muted,
                            fontSize = (11f * s).sp, lineHeight = (16.5f * s).sp)
                        innerTextField()
                    }
                })
        }
        Row(Modifier.fillMaxWidth().testTag("assistant-list-meta")
            .padding(start = 2.dp * s, end = 2.dp * s, top = 10.dp * s, bottom = 7.dp * s),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.assistant_count, filtered.size), color = colors.muted,
                fontSize = (8f * s).sp, lineHeight = (12.8f * s).sp)
            Text(stringResource(R.string.assistant_list_hint), color = colors.muted,
                fontSize = (8f * s).sp, lineHeight = (12.8f * s).sp)
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("assistant-list"),
            contentPadding = PaddingValues(start = 2.dp * s, end = 2.dp * s, top = 2.dp * s, bottom = 7.dp * s),
            verticalArrangement = Arrangement.spacedBy(5.dp * s)) {
            items(filtered, key = { it.id }) { profile ->
                AssistantProfileCard(profile, profile.id == selectedId,
                    onClick = { onSelect(profile.id); onDismiss() }, onLongClick = { actionProfile = profile }, assets = assets,
                    modifier = Modifier.testTag("assistant-item-${profile.id}"))
            }
            if (filtered.isEmpty()) item {
                Text(stringResource(R.string.assistant_empty), color = colors.muted, fontSize = (10f * s).sp,
                    lineHeight = (18f * s).sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp * s, vertical = 22.dp * s))
            }
        }
    }
    actionProfile?.let { profile ->
        AssistantActionsDialog(profile, assets = assets, canDelete = assistants.size > 1,
            onCopy = { actionProfile = null; onCopy(profile.id) },
            onEdit = { actionProfile = null; onEdit(profile.id); onDismiss() },
            onDelete = { actionProfile = null; deleteProfile = profile }, onDismiss = { actionProfile = null })
    }
    deleteProfile?.let { profile ->
        AssistantDeleteDialog(profile, assets = assets, canDelete = assistants.size > 1, onDismiss = { deleteProfile = null },
            onConfirm = { deleteProfile = null; onDelete(profile.id) })
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
    selectedId: String? = null,
    useGlobalSelection: Boolean = true,
    canMutate: () -> Boolean = { true },
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
        } }, assets = assets)
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
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp * s).clip(shape)
        .background(if (selected) colors.soft else androidx.compose.ui.graphics.Color.Transparent)
        .border(1.dp, if (selected) colors.line else androidx.compose.ui.graphics.Color.Transparent, shape)
        .combinedClickable(hapticFeedbackEnabled = false, interactionSource = remember { MutableInteractionSource() }, indication = null,
            onClick = { TouchHaptics.click(view); onClick() },
            onLongClick = { TouchHaptics.longPress(view); onLongClick() }).padding(1.dp).padding(10.dp * s),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp * s)) {
        AssistantAvatar(bitmap = bitmap, size = 34.dp * s, modifier = Modifier.testTag("assistant-avatar-${profile.id}"))
        Column(Modifier.weight(1f)) {
            Text(profile.name, color = colors.text, fontSize = (11f * s).sp,
                fontWeight = FontWeight(550), lineHeight = (16.5f * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // There is no description field in the production profile; do not create a shadow model.
            Text(profile.prompt.lineSequence().firstOrNull { it.isNotBlank() } ?: "自定义助手",
                color = colors.muted, fontSize = (9f * s).sp, lineHeight = (14.4f * s).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp * s))
        }
        if (selected) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp * s)) {
            Icon(AssistantPreviewIcons.Check, null, Modifier.size(11.dp * s), tint = colors.accent)
            Text(stringResource(R.string.assistant_current), color = colors.accent, fontSize = (8f * s).sp, lineHeight = (11f * s).sp, maxLines = 1)
        }
    }
}

@Composable
internal fun AssistantActionsDialog(profile: AssistantProfile, assets: AssistantAssets, canDelete: Boolean, onCopy: () -> Unit,
    onEdit: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AssistantPreviewDialog(show = true, title = stringResource(R.string.assistant_actions_title), onDismiss = onDismiss, onBack = onDismiss,
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
internal fun AssistantDeleteDialog(profile: AssistantProfile, assets: AssistantAssets, canDelete: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val s = assistantPreviewScale()
    AssistantPreviewDialog(show = true, title = stringResource(R.string.assistant_delete_title),
        onDismiss = onDismiss, onBack = onDismiss, footer = {
            AssistantPreviewButton(stringResource(R.string.action_cancel), onDismiss, Modifier.weight(1f))
            AssistantPreviewButton(stringResource(R.string.action_delete), onConfirm, Modifier.weight(1f), danger = true, enabled = canDelete)
        }) {
        AssistantDialogIdentity(profile, assets)
        Text(stringResource(R.string.assistant_delete_body, profile.name), color = assistantPreviewPalette().text,
            fontSize = (11f * s).sp, lineHeight = (19.8f * s).sp, modifier = Modifier.padding(start = 2.dp * s, end = 2.dp * s, top = 3.dp * s, bottom = 10.dp * s)
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
            Text(profile.name, color = colors.text, fontSize = (12f * s).sp, lineHeight = (19.2f * s).sp,
                fontWeight = FontWeight(550))
            Text(profile.prompt.lineSequence().firstOrNull { it.isNotBlank() } ?: "自定义助手",
                color = colors.muted, fontSize = (9f * s).sp, lineHeight = (15.3f * s).sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp * s))
        }
    }
}
