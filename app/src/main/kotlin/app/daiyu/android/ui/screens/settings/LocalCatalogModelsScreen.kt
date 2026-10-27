package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.background
import app.daiyu.android.ui.haptics.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyListScope
import app.daiyu.android.ui.navigation.PageBackHandler as BackHandler
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.model.catalog.CatalogLocalEditResult
import app.daiyu.android.model.catalog.CatalogModelEdit
import app.daiyu.android.model.catalog.SavedCatalogModel
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.model.catalog.CatalogModelSelection
import app.daiyu.android.model.catalog.SavedModelCatalogPersistence
import app.daiyu.android.model.catalog.SavedModelCatalogSnapshot
import app.daiyu.android.model.catalog.selectionFor
import app.daiyu.android.ui.components.DaiyuConfirmDialog
import app.daiyu.android.ui.components.DaiyuTextInputDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.daiyu.android.ui.haptics.miuix.TextButton
import java.util.concurrent.atomic.AtomicBoolean

internal class LocalCatalogModelActions<T>(
    val currentModelId: String,
    val rename: (CatalogModelSelection<T>, String, () -> Boolean, (CatalogLocalEditResult) -> Unit) -> Unit,
    val delete: (CatalogModelSelection<T>, () -> Boolean, (CatalogLocalEditResult) -> Unit) -> Unit,
    val form: ((SavedCatalogModel<T>) -> CatalogModelEdit)? = null,
    val update: ((CatalogModelSelection<T>, CatalogModelEdit, () -> Boolean, (CatalogLocalEditResult) -> Unit) -> Unit)? = null,
    val add: ((SavedModelCatalogSnapshot<T>, CatalogModelEdit, () -> Boolean, (CatalogLocalEditResult) -> Unit) -> Unit)? = null,
)

@Composable
internal fun <T> LocalCatalogModelsScreen(
    providerIdentity: String,
    persistence: SavedModelCatalogPersistence<T>,
    actions: LocalCatalogModelActions<T>,
    enabled: Boolean,
    onBack: () -> Unit,
    pageTitle: String = "本地模型",
    headerContent: LazyListScope.() -> Unit = {},
    managementEndpoint: String? = null,
    fetchingModels: Boolean = false,
    onFetchModels: (() -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    queryState: MutableState<String> = remember { mutableStateOf("") },
    scrollBehavior: ScrollBehavior = MiuixScrollBehavior(),
    backPreviewEnabled: Boolean = true,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val currentEnabled by rememberUpdatedState(enabled)
    val visit = remember(providerIdentity, persistence) { AtomicBoolean(true) }
    var snapshot by remember(providerIdentity, persistence) { mutableStateOf<SavedModelCatalogSnapshot<T>?>(null) }
    var request by remember { mutableStateOf<AtomicBoolean?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<CatalogModelSelection<T>?>(null, referentialEqualityPolicy()) }
    var query by queryState
    val loadingListState = rememberLazyListState()
    val loadingScrollBehavior = MiuixScrollBehavior()
    var adding by remember { mutableStateOf<Any?>(null) }
    var addingBase by remember { mutableStateOf<SavedModelCatalogSnapshot<T>?>(null) }
    var editorStale by remember { mutableStateOf(false) }
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var deleting by remember { mutableStateOf<CatalogModelSelection<T>?>(null) }
    fun current() = visit.get() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    fun cancel() {
        request?.set(true); request = null; snapshot = null; loaded = false; editing = null; deleting = null; adding = null
    }
    fun pause() {
        request?.set(true); request = null; deleting = null
        // New identities fence pre-pause callbacks without unmounting the form or its input.
        editing = editing?.copy()
        adding = adding?.let { Any() }
    }
    fun reload() {
        if (!current() || request != null) return
        deleting = null; loaded = false
        val token = AtomicBoolean(false); request = token
        scope.launch {
            try {
                val value = withContext(Dispatchers.IO) { persistence.read(providerIdentity) }
                if (current() && request === token && !token.get()) {
                    val selection = editing
                    val addBase = addingBase.takeIf { adding != null }
                    if ((selection != null && (value == null || value.providerIdentity != selection.providerIdentity ||
                            value.revision != selection.expectedRevision ||
                            value.models.singleOrNull { it.id == selection.record.id } != selection.record)) ||
                        (addBase != null && (value == null || value.providerIdentity != addBase.providerIdentity || value.revision != addBase.revision))) {
                        editorStale = true
                        notice = "目录已变化，草稿已保留；请取消后重新打开。"
                    }
                    snapshot = value; loaded = true
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (current() && request === token && !token.get()) {
                    loaded = true; editorStale = true; notice = "本地模型读取失败，草稿已保留；请刷新"
                }
            } finally { if (request === token) request = null }
        }
    }
    fun matches(selection: CatalogModelSelection<T>) = snapshot?.let { value ->
        value.providerIdentity == selection.providerIdentity && value.revision == selection.expectedRevision &&
            value.models.singleOrNull { it.id == selection.record.id } == selection.record
    } == true
    fun submit(selection: CatalogModelSelection<T>, name: String?) {
        if (!current() || !currentEnabled || request != null || !matches(selection)) return
        editing = null; deleting = null
        val token = AtomicBoolean(false); request = token
        val cancelled = { token.get() || !visit.get() }
        val completed: (CatalogLocalEditResult) -> Unit = { result ->
            if (current() && request === token && !token.get()) {
                request = null
                notice = when (result) {
                    CatalogLocalEditResult.APPLIED -> if (name == null) "本地模型已删除" else "模型名称已保存"
                    CatalogLocalEditResult.STALE -> "目录已变化，请重新选择模型"
                    CatalogLocalEditResult.CANCELLED -> "修改已取消"
                    CatalogLocalEditResult.CURRENT_MODEL -> "请先切换当前模型，再删除此模型"
                    CatalogLocalEditResult.FAILED -> "修改未保存，请刷新后重试"
                }
                reload()
            }
        }
        try {
            if (name == null) actions.delete(selection, cancelled, completed)
            else actions.rename(selection, name, cancelled, completed)
        } catch (_: Exception) { completed(CatalogLocalEditResult.FAILED) }
    }
    fun saveForm(value: CatalogModelEdit, selection: CatalogModelSelection<T>?) {
        val base = snapshot ?: return
        if (!current() || !currentEnabled || editorStale || request != null || (selection != null && !matches(selection))) return
        if (runCatching { value.validate() }.isFailure) return
        if (selection == null && base.models.any { it.id == value.id }) { notice = "Model ID 已存在"; return }
        val token = AtomicBoolean(false); request = token
        val cancelled = { token.get() || !visit.get() }
        val complete: (CatalogLocalEditResult) -> Unit = { result ->
            if (current() && request === token && !token.get()) {
                request = null
                if (result == CatalogLocalEditResult.APPLIED) {
                    editing = null; adding = null; notice = "模型已保存"; reload()
                } else notice = "模型未保存，请刷新后重新打开。"
            }
        }
        try {
            if (selection == null) actions.add?.invoke(base, value, cancelled, complete)
                ?: complete(CatalogLocalEditResult.FAILED)
            else actions.update?.invoke(selection, value, cancelled, complete)
                ?: complete(CatalogLocalEditResult.FAILED)
        } catch (_: Exception) { complete(CatalogLocalEditResult.FAILED) }
    }
    DisposableEffect(lifecycle, visit) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { resumed = false; pause() }
            if (event == Lifecycle.Event.ON_RESUME) { resumed = true; reload() }
        }
        lifecycle.addObserver(observer)
        onDispose { visit.set(false); cancel(); lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(visit) { reload() }
    val back = { if (current()) { visit.set(false); cancel(); onBack() } }
    BackHandler(previewEnabled = backPreviewEnabled) { back() }
    val editorEnabled = enabled && resumed && request == null
    val addVisit = adding
    if (addVisit != null) CatalogModelEditorDialog(CatalogModelEdit("", "自定义模型"), isNew = true,
        enabled = editorEnabled, canSave = !editorStale, errorText = notice,
        onSave = { if (adding === addVisit) saveForm(it, null) }, onDismiss = { if (request == null && adding === addVisit) adding = null })
    editing?.let { selection ->
        val form = actions.form
        if (form != null) CatalogModelEditorDialog(form(selection.record), isNew = false,
            enabled = editorEnabled, canSave = !editorStale, errorText = notice,
            canDelete = !editorStale && selection.record.id != actions.currentModelId,
            onDelete = { if (editing === selection && current() && currentEnabled && request == null && matches(selection)) { editing = null; deleting = selection } },
            onSave = { if (editing === selection) saveForm(it, selection) },
            onDismiss = { if (request == null && editing === selection) editing = null })
        else DaiyuTextInputDialog("编辑模型名称", selection.record.displayName, "仅更改显示名称，API模型ID保持不变", "保存",
            onConfirm = { name ->
                if (editing === selection && current() && matches(selection)) {
                    val value = name.trim()
                    if (value.isEmpty() || value.length > 200 || value.any { it.code < 0x20 || it.code == 0x7f })
                        notice = "请输入1至200字符的单行名称"
                    else submit(selection, value)
                }
            }, onDismiss = { if (editing === selection) editing = null })
    }
    deleting?.let { selection ->
        DaiyuConfirmDialog("删除本地模型", "删除 ${selection.record.displayName} 的本地记录，不影响远端模型或其他配置。", "删除", true, deletionAppearance = true,
            onConfirm = { if (deleting === selection && current() && matches(selection)) submit(selection, null) },
            onDismiss = { if (deleting === selection) deleting = null })
    }
    SettingsScaffoldPage(pageTitle, onBack = back,
        // The short loading list must not clamp the retained model position to its first rows.
        listState = if (snapshot == null) loadingListState else listState,
        scrollBehavior = if (snapshot == null) loadingScrollBehavior else scrollBehavior) {
        headerContent()
        item("catalog-state") {
            val add = if (actions.add != null && enabled && request == null && loaded && snapshot != null)
                ({ notice = null; editorStale = false; addingBase = snapshot; adding = Any() }) else null
            if (managementEndpoint != null) ProviderModelManagementCard(managementEndpoint, fetchingModels, onFetchModels, add)
            else if (actions.add != null) SettingsCard {
                SettingsRow("添加自定义模型", summary = "手动填写展示名称与 Model ID", tag = "local-model-add",
                    leading = { DaiyuIcon(DaiyuIcons.Add, null) }, showChevron = true, onClick = add)
            }
            notice?.let { SettingsNote(it) }
            if (!loaded) SettingsNote("正在读取本地模型")
            if (loaded && snapshot == null) TextButton("重试", enabled = request == null,
                onClick = { notice = null; reload() }, modifier = Modifier.testTag("local-model-refresh"))
            SettingsSectionTitle("模型列表（共 ${snapshot?.models?.size ?: 0} 个）")
            SettingsSearch(query, { query = it }, "搜索模型", "local-model-search")
            if (loaded && snapshot?.models.isNullOrEmpty()) SettingsNote("还没有模型，请从远端拉取或添加自定义模型")
        }
        snapshot?.models?.filter { query.isBlank() || it.id.contains(query.trim(), true) || it.displayName.contains(query.trim(), true) }?.forEach { model ->
            item("local-model:${model.id}") {
                val selection = snapshot?.selectionFor(model.id)
                SettingsCard {
                    val form = actions.form?.invoke(model)
                    val selected = model.id == actions.currentModelId
                    Column {
                        SettingsRow(model.displayName, summary = model.id, tag = "local-model:${model.id}",
                            trailing = {
                                if (selected) DaiyuIcon(DaiyuIcons.Check, "当前模型")
                                Box(Modifier.size(40.dp).testTag("local-model-rename:${model.id}")
                                    .clickable(enabled = enabled && request == null) {
                                        if (current() && currentEnabled && selection != null && matches(selection)) {
                                            notice = null; editorStale = false; editing = selection
                                        }
                                    }, contentAlignment = Alignment.Center) { DaiyuIcon(Icons.Rounded.Tune, "模型设置") }
                            })
                        FlowRow(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val context = form?.contextWindow?.toIntOrNull()
                            val labels = listOfNotNull(
                                if (context == null) "上下文未知" else if (context % 1000 == 0) "${context / 1000}K 上下文" else "$context 上下文",
                                "支持推理".takeIf { form?.reasoning == true }, "支持视觉".takeIf { form?.vision == true },
                                "当前".takeIf { selected })
                            labels.forEach { label ->
                                Text(label, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onPrimary,
                                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                        .background(MiuixTheme.colorScheme.primary).padding(horizontal = 7.dp, vertical = 2.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
