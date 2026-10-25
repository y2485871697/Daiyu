package app.daiyu.android.ui.screens.settings

import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics

import app.daiyu.android.ui.haptics.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.HorizontalDivider
import app.daiyu.android.ui.haptics.material.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.model.ProviderHeaders
import app.daiyu.android.model.ProviderRequestOptions
import app.daiyu.android.model.RequestBodyFields
import app.daiyu.android.model.RequestBodyValueType
import app.daiyu.android.model.sessiongateway.SessionGatewayRule
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import app.daiyu.android.ui.haptics.miuix.TextButton

internal val RetainedProviderFieldAdd = SemanticsPropertyKey<() -> Unit>("RetainedProviderFieldAdd")
internal val RetainedProviderGatewayChange =
    SemanticsPropertyKey<Pair<SessionGatewayRule, (SessionGatewayRule) -> Unit>>("RetainedProviderGatewayChange")


internal data class ProviderFieldRow(val id: Long, val name: String, val value: String) {
    override fun toString() = "ProviderFieldRow(<redacted>)"
}

internal fun ModelSettingsDraftUi.inlineFieldsSaveError(): String? = listOfNotNull(
    if (!headerFieldsValid) headerFieldsError ?: "请修正自定义请求头。"
    else if (runCatching { ProviderHeaders.parse(customHeaders) }.isFailure)
        "自定义请求头配置无效，请修正或清除后保存。" else null,
    if (!bodyFieldsValid) bodyFieldsError ?: "请修正自定义请求体。"
    else if (runCatching { ProviderRequestOptions(customBody = customBody).validate(protocol) }.isFailure)
        "自定义请求体配置无效，请修正或清除后保存。" else null,
    if (runCatching { sessionGateway.validate() }.isFailure) "请检查网关字段。" else null,
).joinToString("\n").takeIf { it.isNotBlank() }

@Composable
internal fun ProviderInlineFields(draft: ModelSettingsDraftUi, enabled: Boolean, headers: Boolean,
    onDraftChange: (ModelSettingsDraftUi) -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val visit = remember(headers, draft.protocol) { AtomicBoolean(true) }
    var epoch by remember { mutableStateOf(0L) }
    val callbackEpoch = epoch
    val raw = if (headers) draft.customHeaders else draft.customBody
    val prefix = if (headers) "model-header" else "model-body-field"
    fun read(value: String): List<ProviderFieldRow> = if (headers) {
        ProviderHeaders.parse(value)
        val json = JSONObject(value.ifBlank { "{}" })
        json.keys().asSequence().mapIndexed { index, name -> ProviderFieldRow(index.toLong(), name, json.getString(name)) }.toList()
    } else RequestBodyFields.read(value).mapIndexed { index, field ->
        ProviderFieldRow(index.toLong(), field.name,
            if (field.type == RequestBodyValueType.STRING) JSONObject.quote(field.text) else field.text)
    }
    val parsed = runCatching { read(raw) }
    val rows = (if (headers) draft.headerFieldRows else draft.bodyFieldRows) ?: parsed.getOrDefault(emptyList())
    val nextId = (rows.maxOfOrNull { it.id } ?: -1L) + 1L
    var expanded by rememberSaveable(headers, draft.protocol) { mutableStateOf(false) }
    val latestDraft by rememberUpdatedState(draft)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestChange by rememberUpdatedState(onDraftChange)
    DisposableEffect(lifecycle, visit) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) { expanded = false; epoch++ } }
        lifecycle.addObserver(observer)
        onDispose { visit.set(false); lifecycle.removeObserver(observer) }
    }
    fun current() = visit.get() && epoch == callbackEpoch && latestEnabled && latestDraft === draft && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    fun update(value: List<ProviderFieldRow>) {
        if (!current()) return
        val problem = value.mapIndexedNotNull { index, row -> when {
            row.name.isBlank() -> "第 ${index + 1} 项${if (headers) "请求头名称" else "请求体字段名"}不能为空"
            value.take(index).any { if (headers) it.name.equals(row.name, true) else it.name == row.name } -> "第 ${index + 1} 项名称重复"
            else -> null
        } }.firstOrNull()
        val encoded = if (problem == null) runCatching {
            if (headers) {
                val json = JSONObject()
                value.forEach { json.put(it.name, it.value) }
                ProviderHeaders.parse(json.toString()).encode()
            } else {
                val body = value.joinToString(",", "{", "}") { "${JSONObject.quote(it.name)}:${it.value}" }
                RequestBodyFields.read(body)
                ProviderRequestOptions(customBody = body).validate(draft.protocol)
                body
            }
        } else null
        val error = problem ?: if (encoded?.isFailure == true)
            if (headers) "请求头名称或值无效；认证与传输请求头由系统管理。" else "字段值须为有效 JSON，且不能覆盖系统字段。"
            else null
        val result = encoded?.getOrNull()
        latestChange(if (headers) draft.copy(customHeaders = result ?: draft.customHeaders, headerFieldsValid = error == null, headerFieldRows = value, headerFieldsError = error)
            else draft.copy(customBody = result ?: draft.customBody, bodyFieldsValid = error == null, bodyFieldRows = value, bodyFieldsError = error))
    }
    SettingsRow(if (rows.isEmpty()) "未设置" else "已设置 ${rows.size} 项",
        summary = if (headers) "可覆盖 User-Agent；认证与传输请求头由系统管理。" else "值使用 JSON 格式，字符串请加双引号；不是请求头。",
        tag = if (headers) "model-custom-headers" else "model-body-fields",
        onClick = if (enabled) ({ expanded = !expanded }) else null,
        trailing = { DaiyuIcon(DaiyuIcons.ChevronDown, null, modifier = Modifier.rotate(if (expanded) 180f else 0f)) })
    if (expanded) {
        rows.forEachIndexed { index, row -> key(row.id) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)
                .testTag("$prefix-row:$index"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsTextField(if (headers) "名称" else "字段名", row.name, { value ->
                        update(rows.map { if (it.id == row.id) it.copy(name = value) else it })
                    }, enabled = enabled, compact = true, embedded = true, tag = "$prefix-name:$index")
                    SettingsTextField(if (headers) "值" else "值（JSON）", row.value, { value ->
                        update(rows.map { if (it.id == row.id) it.copy(value = value) else it })
                    }, password = headers, multiline = !headers, enabled = enabled, compact = true, embedded = true,
                        tag = "$prefix-value:$index", secretLabel = "值")
                }
                IconButton(enabled = enabled, modifier = Modifier.size(48.dp).testTag("$prefix-delete:$index"), onClick = {
                    update(rows.filter { it.id != row.id })
                }) {
                    DaiyuIcon(DaiyuIcons.Delete, if (headers) "删除请求头" else "删除请求体字段",
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, size = 20.dp)
                }
            }
        } }
        if (parsed.isFailure) TextButton("清除无效配置", enabled = enabled, onClick = { update(emptyList()) })
        val add: () -> Unit = {
            if (current() && rows.size < 32) update(rows + ProviderFieldRow(nextId, "", ""))
        }
        HorizontalDivider(color = MiuixTheme.colorScheme.onSurface.copy(alpha = .10f))
        Row(Modifier.fillMaxWidth().testTag("$prefix-add").semantics { this[RetainedProviderFieldAdd] = add }
            .clickable(enabled = enabled && rows.size < 32, role = Role.Button, onClick = add)
            .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = if (enabled && rows.size < 32) 1f else .38f)
            DaiyuIcon(DaiyuIcons.Add, null, tint = color, size = 20.dp)
            Text(if (headers) "添加请求头" else "添加请求体字段", style = MiuixTheme.textStyles.body1, color = color)
        }
    }
}

@Composable
internal fun ProviderGatewayEditor(draft: SessionGatewayRule, enabled: Boolean,
    onDraftChange: (SessionGatewayRule) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    SettingsRow("网关", summary = "${draft.modelPattern} · ${draft.keyField}", tag = "provider-session-gateway",
        onClick = if (enabled) ({ expanded = !expanded }) else null,
        modifier = Modifier.semantics { this[RetainedProviderGatewayChange] = draft to onDraftChange },
        trailing = { DaiyuIcon(DaiyuIcons.ChevronDown, null, modifier = Modifier.rotate(if (expanded) 180f else 0f)) })
    if (expanded) SessionGatewayFields(draft, enabled, onDraftChange)
}

@Composable
internal fun SessionGatewayFields(draft: SessionGatewayRule, enabled: Boolean,
    onDraftChange: (SessionGatewayRule) -> Unit,
) {
    SettingsTextField("模型正则（每一行一个）", draft.modelPattern,
        { onDraftChange(draft.copy(modelPattern = it)) }, multiline = true, enabled = enabled)
    SettingsTextField("路径正则（每一行一个）", draft.pathPattern,
        { onDraftChange(draft.copy(pathPattern = it)) }, multiline = true, enabled = enabled)
    SettingsTextField("会话保持", draft.retention,
        { onDraftChange(draft.copy(retention = it)) }, enabled = enabled)
    Row {
        Column(Modifier.weight(1f)) {
            SettingsTextField("Key 来源", draft.keySource, { onDraftChange(draft.copy(keySource = it)) }, enabled = enabled)
        }
        Column(Modifier.weight(1f)) {
            SettingsTextField("字段", draft.keyField, { onDraftChange(draft.copy(keyField = it)) }, enabled = enabled)
        }
    }
}
