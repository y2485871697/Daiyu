package app.daiyu.android.ui.screens.assistants

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalConfiguration
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
import app.daiyu.android.ui.components.MiuixDialogActions
import app.daiyu.android.ui.haptics.TouchHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

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
    var searchQuery by remember { mutableStateOf("") }
    var actionProfile by remember { mutableStateOf<AssistantProfile?>(null) }
    var deleteProfile by remember { mutableStateOf<AssistantProfile?>(null) }
    val dialogBodyHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp.coerceAtLeast(280.dp)
    LaunchedEffect(show) {
        if (!show) { searchQuery = ""; actionProfile = null; deleteProfile = null }
    }
    val filtered = remember(assistants, searchQuery) {
        val query = searchQuery.trim()
        assistants.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
    }
    WindowDialog(show = show, title = stringResource(R.string.assistant_list_title),
        onDismissRequest = { if (actionProfile == null && deleteProfile == null) onDismiss() }) {
        Column(modifier = Modifier.fillMaxWidth().height(dialogBodyHeight)) {
            TextField(value = searchQuery, onValueChange = { searchQuery = it }, label = stringResource(R.string.assistant_search),
                useLabelAsPlaceholder = true, singleLine = true, modifier = Modifier.fillMaxWidth())
            Column(modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                filtered.forEach { profile ->
                    AssistantProfileCard(profile, profile.id == selectedId,
                        onClick = { onSelect(profile.id); onDismiss() }, onLongClick = { actionProfile = profile }, assets = assets)
                }
                if (filtered.isEmpty()) {
                    Text(text = stringResource(R.string.assistant_empty), style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp))
                }
            }
        }
    }
    actionProfile?.let { profile ->
        AssistantActionsDialog(profile, canDelete = assistants.size > 1,
            onCopy = { actionProfile = null; onCopy(profile.id) },
            onEdit = { actionProfile = null; onDismiss(); onEdit(profile.id) },
            onDelete = { actionProfile = null; deleteProfile = profile }, onDismiss = { actionProfile = null })
    }
    deleteProfile?.let { profile ->
        AssistantDeleteDialog(profile, canDelete = assistants.size > 1, onDismiss = { deleteProfile = null },
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
) {
    val profiles by store.assistants.collectAsState()
    val active by store.selectedId.collectAsState()
    val scope = rememberCoroutineScope()
    fun mutate(action: () -> Unit) {
        scope.launch { withContext(Dispatchers.IO) { action() } }
    }
    AssistantPickerDialog(show, profiles, selectedId ?: active, onSelect, onEdit, onDismiss,
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
    val bitmap = remember(profile.avatarFileName) { assets.avatarBitmap(profile.avatarFileName) }
    Card(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().combinedClickable(hapticFeedbackEnabled = false,
            onClick = { TouchHaptics.click(view); onClick() },
            onLongClick = { TouchHaptics.longPress(view); onLongClick() }).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            AssistantAvatar(bitmap = bitmap, size = 32.dp)
            Text(text = profile.name, style = MiuixTheme.textStyles.title3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 12.dp, end = 8.dp))
            if (selected) Text(text = stringResource(R.string.assistant_current), style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary, maxLines = 1)
        }
    }
}

@Composable
internal fun AssistantActionsDialog(profile: AssistantProfile, canDelete: Boolean, onCopy: () -> Unit,
    onEdit: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    WindowDialog(show = true, title = profile.name, onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(text = stringResource(R.string.ui_copy_4edd1d), onClick = onCopy, modifier = Modifier.fillMaxWidth())
            TextButton(text = stringResource(R.string.ui_edit_a7f814), onClick = onEdit, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary())
            TextButton(text = stringResource(R.string.action_delete), onClick = onDelete, enabled = canDelete,
                modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary(
                    color = MiuixTheme.colorScheme.error, textColor = MiuixTheme.colorScheme.onError))
        }
    }
}

@Composable
internal fun AssistantDeleteDialog(profile: AssistantProfile, canDelete: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    WindowDialog(show = true, title = stringResource(R.string.assistant_delete_title),
        summary = stringResource(R.string.assistant_delete_body, profile.name), onDismissRequest = onDismiss) {
        MiuixDialogActions(confirmText = stringResource(R.string.action_delete), destructive = true, confirmEnabled = canDelete,
            onCancel = onDismiss, onConfirm = onConfirm)
    }
}
