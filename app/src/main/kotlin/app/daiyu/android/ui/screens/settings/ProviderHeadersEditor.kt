package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.model.ProviderHeaders
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

private data class HeaderDraft(val name: String, val value: String)

@Composable
internal fun ProviderHeadersEditor(
    draft: ModelSettingsDraftUi,
    enabled: Boolean,
    onDraftChange: (ModelSettingsDraftUi) -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var owner by remember { mutableStateOf<RequestBodyFieldEditorOwner?>(null) }
    var rows by remember { mutableStateOf<List<HeaderDraft>>(emptyList()) }
    var notice by remember { mutableStateOf<String?>(null) }
    val latestDraft by rememberUpdatedState(draft)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestChange by rememberUpdatedState(onDraftChange)
    val latestOwner by rememberUpdatedState(owner)
    fun close() {
        owner?.revoke()
        owner = null
        rows = emptyList()
        notice = null
    }
    fun current(expected: RequestBodyFieldEditorOwner) = latestOwner === expected &&
        expected.current(latestDraft, latestEnabled) &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) close() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); owner?.revoke() }
    }
    SettingsRow("自定义请求头", summary = if (draft.customHeaders.isBlank()) "未设置" else "已设置",
        tag = "model-custom-headers", onClick = if (enabled) ({
            close()
            runCatching {
                ProviderHeaders.parse(draft.customHeaders)
                val json = JSONObject(draft.customHeaders.ifEmpty { "{}" })
                json.keys().asSequence().map { HeaderDraft(it, json.getString(it)) }.toList()
            }.onSuccess {
                rows = it
                owner = RequestBodyFieldEditorOwner(draft)
            }.onFailure { notice = "原请求头无效；请重新输入或清除后保存。原文未改变。" }
        }) else null)
    val active = owner
    if (active == null) {
        notice?.let { SettingsNote(it, error = true) }
        return
    }
    if (!active.current(draft, enabled)) return
    WindowDialog(show = true, title = "自定义请求头", onDismissRequest = { close() }) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).testTag("model-headers-list")) {
            itemsIndexed(rows) { index, row ->
                Column {
                    SettingsTextField("请求头名称 ${index + 1}", row.name, { value ->
                        if (current(active)) rows = rows.toMutableList().also { it[index] = row.copy(name = value) }
                    }, enabled = enabled)
                    SettingsTextField("请求头值 ${index + 1}", row.value, { value ->
                        if (current(active)) rows = rows.toMutableList().also { it[index] = row.copy(value = value) }
                    }, enabled = enabled, password = true)
                    TextButton(text = "删除请求头", modifier = Modifier.testTag("model-header-delete:$index"),
                        enabled = enabled, onClick = {
                            if (current(active)) rows = rows.filterIndexed { position, _ -> position != index }
                        })
                }
            }
            item {
                TextButton(text = "添加请求头", modifier = Modifier.testTag("model-header-add"),
                    enabled = enabled && rows.size < 32, onClick = {
                        if (current(active) && rows.size < 32) rows = rows + HeaderDraft("", "")
                    })
                TextButton(text = "应用到草稿", modifier = Modifier.testTag("model-custom-headers-apply"),
                    enabled = enabled, onClick = {
                        if (current(active)) {
                            runCatching {
                                require(rows.map { it.name.lowercase(java.util.Locale.ROOT) }.distinct().size == rows.size)
                                val json = JSONObject()
                                rows.forEach { json.put(it.name, it.value) }
                                ProviderHeaders.parse(json.toString()).encode()
                            }.onSuccess { headers ->
                                if (current(active)) {
                                    close()
                                    latestChange(active.draft.copy(customHeaders = headers))
                                }
                            }.onFailure { notice = "请求头名称或值无效；不能重复或覆盖鉴权、会话字段。" }
                        }
                    })
                notice?.let { SettingsNote(it, error = true) }
                TextButton(text = "取消", onClick = { close() })
            }
        }
    }
}
