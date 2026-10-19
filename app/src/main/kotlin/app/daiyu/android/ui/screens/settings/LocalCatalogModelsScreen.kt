package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyListScope
import androidx.activity.compose.BackHandler
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
import app.daiyu.android.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.TextButton
import java.util.concurrent.atomic.AtomicBoolean

internal class LocalCatalogModelActions<T>(
    val currentModelId: String,
    val rename: (CatalogModelSelection<T>, String, () -> Boolean, (CatalogLocalEditResult) -> Unit) -> Unit,
    val delete: (CatalogModelSelection<T>, () -> Boolean, (CatalogLocalEditResult) -> Unit) -> Unit,
    val select: ((CatalogModelSelection<T>, () -> Boolean, (Boolean) -> Unit) -> Unit)? = null,
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
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val currentEnabled by rememberUpdatedState(enabled)
    val visit = remember(providerIdentity, persistence) { AtomicBoolean(true) }
    var snapshot by remember(providerIdentity, persistence) { mutableStateOf<SavedModelCatalogSnapshot<T>?>(null) }
    var request by remember { mutableStateOf<AtomicBoolean?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<CatalogModelSelection<T>?>(null) }
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf<Any?>(null) }
    var deleting by remember { mutableStateOf<CatalogModelSelection<T>?>(null) }
    fun current() = visit.get() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    fun cancel() {
        request?.set(true); request = null; snapshot = null; loaded = false; editing = null; deleting = null; adding = null
    }
    fun reload() {
        if (!current() || request != null) return
        editing = null; deleting = null; snapshot = null; loaded = false
        val token = AtomicBoolean(false); request = token
        scope.launch {
            try {
                val value = withContext(Dispatchers.IO) { persistence.read(providerIdentity) }
                if (current() && request === token && !token.get()) { snapshot = value; loaded = true }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (current() && request === token && !token.get()) { loaded = true; notice = "本地模型读取失败，请刷新" }
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
        if (!current() || !currentEnabled || request != null || (selection != null && !matches(selection))) return
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
    fun choose(selection: CatalogModelSelection<T>?) {
        if (!current() || !currentEnabled || request != null || selection == null || !matches(selection) ||
            selection.record.id == actions.currentModelId) return
        val token = AtomicBoolean(false); request = token
        val complete: (Boolean) -> Unit = { success ->
            if (current() && request === token && !token.get()) {
                request = null; notice = if (success) "当前模型已切换" else "模型未切换，请重新选择"; reload()
            }
        }
        try { actions.select?.invoke(selection, { token.get() || !visit.get() }, complete) ?: complete(false) }
        catch (_: Exception) { complete(false) }
    }
    DisposableEffect(lifecycle, visit) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) cancel()
            if (event == Lifecycle.Event.ON_RESUME) reload()
        }
        lifecycle.addObserver(observer)
        onDispose { visit.set(false); cancel(); lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(visit) { reload() }
    val back = { if (current()) { visit.set(false); cancel(); onBack() } }
    BackHandler { back() }
    val addVisit = adding
    if (addVisit != null) CatalogModelEditorDialog(CatalogModelEdit("", ""), isNew = true,
        enabled = enabled && request == null, errorText = notice,
        onSave = { if (adding === addVisit) saveForm(it, null) }, onDismiss = { if (request == null && adding === addVisit) adding = null })
    editing?.let { selection ->
        val form = actions.form
        if (form != null) CatalogModelEditorDialog(form(selection.record), isNew = false,
            enabled = enabled && request == null, errorText = notice,
            canDelete = selection.record.id != actions.currentModelId,
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
        DaiyuConfirmDialog("删除本地模型", "删除 ${selection.record.displayName} 的本地记录，不影响远端模型或其他配置。", "删除", true,
            onConfirm = { if (deleting === selection && current() && matches(selection)) submit(selection, null) },
            onDismiss = { if (deleting === selection) deleting = null })
    }
    MiuixScaffoldPage(pageTitle, onBack = back) {
        headerContent()
        item("catalog-state") {
            if (actions.add != null) SettingsCard {
                SettingsRow("添加自定义模型", summary = "手动填写展示名称与 Model ID", tag = "local-model-add",
                    onClick = if (enabled && request == null && loaded && snapshot != null) ({ notice = null; adding = Any() }) else null)
            }
            SettingsTextField("搜索模型", query, { query = it }, tag = "local-model-search")
            TextButton("刷新", enabled = request == null, onClick = { notice = null; reload() }, modifier = Modifier.testTag("local-model-refresh"))
            notice?.let { SettingsNote(it) }
            if (!loaded) SettingsNote("正在读取本地模型")
            SettingsSectionTitle("模型列表（共 ${snapshot?.models?.size ?: 0} 个）")
            if (loaded && snapshot?.models.isNullOrEmpty()) SettingsNote("还没有模型，请从远端拉取或添加自定义模型")
        }
        snapshot?.models?.filter { query.isBlank() || it.id.contains(query.trim(), true) || it.displayName.contains(query.trim(), true) }?.forEach { model ->
            item("local-model:${model.id}") {
                val selection = snapshot?.selectionFor(model.id)
                SettingsCard {
                    val form = actions.form?.invoke(model)
                    val details = listOfNotNull(model.id, form?.let { if (it.contextWindow.isBlank()) "上下文：自动（未知）" else "${it.contextWindow} 上下文（手动）" },
                        form?.let { "推理：" + when (it.reasoning) { true -> "支持（手动）"; false -> "不支持（手动）"; null -> "自动（未知）" } },
                        form?.let { "视觉：" + when (it.vision) { true -> "支持（手动）"; false -> "不支持（手动）"; null -> "自动（未知）" } },
                        if (model.id == actions.currentModelId) "当前" else null).joinToString(" · ")
                    SettingsRow(model.displayName, summary = details, tag = "local-model:${model.id}",
                        onClick = if (enabled && request == null && actions.select != null) ({ choose(selection) }) else null,
                        trailing = {
                            if (model.id == actions.currentModelId) DaiyuIcon(DaiyuIcons.Check, "当前模型")
                            TextButton("设置", enabled = enabled && request == null,
                                modifier = Modifier.testTag("local-model-rename:${model.id}"),
                                onClick = { if (current() && currentEnabled && selection != null && matches(selection)) { notice = null; editing = selection } })
                        })
                }
            }
        }
    }
}
