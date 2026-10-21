package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.model.RequestBodyField
import app.daiyu.android.model.RequestBodyFields
import app.daiyu.android.model.RequestBodyValueType
import app.daiyu.android.ui.haptics.miuix.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

internal val RetainedRequestBodyFieldApply = SemanticsPropertyKey<() -> Unit>("RetainedRequestBodyFieldApply")

/** A visit-local capability, not a saved draft or an authorization to persist it. */
internal class RequestBodyFieldEditorOwner(val draft: ModelSettingsDraftUi) {
    private var live = true
    fun current(value: ModelSettingsDraftUi, enabled: Boolean): Boolean {
        if (!enabled || value !== draft) live = false
        return live
    }
    fun revoke() { live = false }
    override fun toString() = "RequestBodyFieldEditorOwner(<redacted>)"
}

@Composable
internal fun RequestBodyFieldsEditor(draft: ModelSettingsDraftUi, enabled: Boolean, engineeringUi: Boolean = false,
    onDraftChange: (ModelSettingsDraftUi) -> Unit,
) {
    if (!engineeringUi) {
        ProviderInlineFields(draft, enabled, headers = false, onDraftChange = onDraftChange)
        return
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var owner by remember { mutableStateOf<RequestBodyFieldEditorOwner?>(null) }
    var selection by remember { mutableStateOf<RequestBodyField?>(null) }
    var adding by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val latestDraft by rememberUpdatedState(draft)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestOwner by rememberUpdatedState(owner)
    val latestChange by rememberUpdatedState(onDraftChange)
    fun close(expected: RequestBodyFieldEditorOwner? = owner) {
        if (owner !== expected) return
        owner?.revoke(); owner = null; selection = null; adding = false; notice = null
    }
    fun current(expected: RequestBodyFieldEditorOwner): Boolean = latestOwner === expected &&
        expected.current(latestDraft, latestEnabled) && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) close() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); owner?.revoke() }
    }
    DisposableEffect(owner) {
        val previous = owner
        onDispose { previous?.revoke() }
    }
    SettingsRow("自定义请求体", tag = "model-body-fields", summary = "逐字段编辑",
        onClick = if (enabled) ({
            close()
            owner = RequestBodyFieldEditorOwner(draft)
        }) else null)
    val active = owner ?: return
    if (!active.current(draft, enabled)) return
    val fields = remember(active) { runCatching { RequestBodyFields.read(active.draft.customBody) } }
    if (adding || selection != null) {
        val original = selection
        var field by remember(active, original) { mutableStateOf(original ?: RequestBodyField("", RequestBodyValueType.STRING, "")) }
        WindowDialog(show = true, title = if (original == null) "添加字段" else "编辑字段", onDismissRequest = { close(active) }) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).testTag("model-body-field-editor-list")) {
                item {
                    SettingsTextField("字段名", field.name, { field = field.copy(name = it) }, enabled = enabled)
                }
                itemsIndexed(RequestBodyValueType.entries) { _, type ->
                    SettingsRadioRow(type.label, field.type == type, enabled = enabled) {
                        if (current(active)) field = field.copy(type = type,
                            text = if (type == RequestBodyValueType.NULL) "null" else field.text)
                    }
                }
                if (field.type != RequestBodyValueType.NULL) item {
                    SettingsTextField("字段值", field.text, { field = field.copy(text = it) }, multiline = true, enabled = enabled)
                }
                item {
                    notice?.let { SettingsNote(it, error = true) }
                    val apply: () -> Unit = {
                        if (current(active)) {
                            val result = runCatching { RequestBodyFields.put(active.draft.customBody, original?.name, field, active.draft.protocol) }
                            result.fold(onSuccess = { body ->
                                if (current(active)) { close(active); latestChange(active.draft.copy(customBody = body)) }
                            }, onFailure = { notice = "字段无效；请检查名称、类型、值及协议限制。" })
                        }
                    }
                    val retained = if (engineeringUi) Modifier.semantics {
                        this[RetainedRequestBodyFieldApply] = apply
                    } else Modifier
                    TextButton(text = "应用到草稿", modifier = Modifier.testTag("model-body-field-apply").then(retained),
                        enabled = enabled, onClick = apply)
                    TextButton(text = "取消", onClick = { close(active) })
                }
            }
        }
    } else WindowDialog(show = true, title = "请求体字段", onDismissRequest = { close(active) }) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).testTag("model-body-fields-list")) {
            fields.fold(onSuccess = { rows ->
                itemsIndexed(rows) { index, field ->
                    Column {
                        SettingsRow(field.name, summary = field.type.label, tag = "model-body-field:$index",
                            onClick = { if (current(active)) { selection = field; notice = null } })
                        TextButton(text = "删除字段", modifier = Modifier.testTag("model-body-field-delete:$index"),
                            enabled = enabled, onClick = {
                                if (current(active)) {
                                    val result = runCatching { RequestBodyFields.remove(active.draft.customBody, field.name, active.draft.protocol) }
                                    result.fold(onSuccess = { body ->
                                        if (current(active)) { close(active); latestChange(active.draft.copy(customBody = body)) }
                                    }, onFailure = { notice = "未删除；请先修复原 JSON 或协议配置。" })
                                }
                            })
                    }
                }
                item {
                    TextButton(text = "添加字段", modifier = Modifier.testTag("model-body-field-add"), enabled = enabled,
                        onClick = { if (current(active)) { adding = true; notice = null } })
                }
            }, onFailure = {
                item { SettingsNote("原请求体无效，请先在 JSON 编辑器修复；原文未改变。", error = true) }
            })
            item {
                notice?.let { SettingsNote(it, error = true) }
                TextButton(text = "关闭", onClick = { close(active) })
            }
        }
    }
}
