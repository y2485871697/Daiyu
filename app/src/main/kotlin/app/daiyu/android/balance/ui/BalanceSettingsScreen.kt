package app.daiyu.android.balance.ui

import androidx.compose.material3.SwitchDefaults

import app.daiyu.android.ui.app.DaiyuPalette

import app.daiyu.android.ui.components.imeCursorBrush
import app.daiyu.android.ui.components.materialImeCursorColor
import app.daiyu.android.ui.components.managedInput

import app.daiyu.android.ui.navigation.PageBackHandler as BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import app.daiyu.android.ui.haptics.material.Button
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import androidx.compose.material3.Icon
import app.daiyu.android.ui.haptics.material.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import app.daiyu.android.ui.haptics.material.Switch
import androidx.compose.material3.Text
import app.daiyu.android.ui.haptics.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.balance.BalanceLifecycleBinding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.daiyu.android.balance.BalanceConfiguration
import app.daiyu.android.balance.BalanceOption
import app.daiyu.android.balance.BalanceRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val BalancePresets = listOf(BalanceOption.PRESET_CUSTOM, BalanceOption.PRESET_NEW_API,
    BalanceOption.PRESET_SUB2API, BalanceOption.PRESET_DEEPSEEK)
private val BalancePresetNames = listOf("自定义", "New API", "Sub2API", "DeepSeek")

/**
 * Independent P05 destination. No activity/controller/model-settings dependency, no implicit HTTP.
 * Frozen option fields/preset resolution/password visibility and explicit test interaction are retained.
 * Draft credentials use remember, NEVER rememberSaveable or a saved-state bundle.
 */
@Composable
fun BalanceSettingsScreen(runtime: BalanceRuntime, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by runtime.state.collectAsState()
    val projection = state.project()
    val scope = rememberCoroutineScope() // Local save/delete only; NEVER owns manual HTTP.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    var manualOwner by remember(runtime, lifecycle) { mutableStateOf<BalanceLifecycleBinding?>(null) }
    DisposableEffect(runtime, lifecycle, scope) {
        val binding = BalanceLifecycleBinding(lifecycle, scope, runtime, poll = false)
        manualOwner = binding
        onDispose { binding.close(); manualOwner = null }
    }
    var draft by remember(runtime) { mutableStateOf(emptyBalanceDraft()) }
    var loading by remember(runtime) { mutableStateOf(true) }
    var draftRevision by remember(runtime) { mutableStateOf<String?>(null) }
    var busy by remember(runtime) { mutableStateOf(false) }
    var dirty by remember(runtime) { mutableStateOf(false) }
    var notice by remember(runtime) { mutableStateOf<String?>(null) }
    var deleteConfirmation by remember(runtime) { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    // Keying by revision cancels a previous account's editor load before it can publish a late draft.
    LaunchedEffect(runtime, state.revision) {
        loading = true
        draft = withContext(Dispatchers.IO) { runtime.store.configurationForEditing() } ?: emptyBalanceDraft()
        dirty = false
        deleteConfirmation = false
        draftRevision = state.revision
        loading = false
    }
    fun edit(value: BalanceConfiguration) {
        draft = value
        dirty = true
        notice = null
        deleteConfirmation = false
    }
    if (loading || draftRevision != state.revision) {
        Column(modifier.padding(16.dp)) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("正在读取独立余额配置")
        }
        return
    }
    val enabled = !busy
    Column(modifier.testTag("page:独立余额设置").verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("独立余额设置", modifier = Modifier.testTag("balance-settings-title"), style = MaterialTheme.typography.titleLarge)
        }
        Text("独立账户接口返回的真实金额；不是 token 估算。密钥与金额仅加密保存，不进入备份。",
            style = MaterialTheme.typography.bodySmall)
        ProviderBalanceIndicator(state)
        Text(projection.statusText, style = MaterialTheme.typography.bodySmall)
        projection.checkedAtMillis?.let {
            Text("上次成功：${DateFormat.getDateTimeInstance().format(Date(it))}", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(draft.accountId, { edit(draft.copy(accountId = it)) }, label = { Text("独立余额账户标识") },
            singleLine = true, enabled = enabled, modifier = Modifier.managedInput().fillMaxWidth(),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(cursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.primary), errorCursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.error)),
        )
        OutlinedTextField(draft.endpoint, { edit(draft.copy(endpoint = it)) }, label = { Text("独立余额服务 Base URL（HTTPS）") },
            singleLine = true, enabled = enabled, modifier = Modifier.managedInput().fillMaxWidth(),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(cursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.primary), errorCursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.error)),
        )
        BalanceSecretField(draft.apiKey, { edit(draft.copy(apiKey = it)) }, "独立余额 API Key（可空；不读取聊天密钥）", enabled)
        OutlinedTextField(draft.currency, { edit(draft.copy(currency = it)) }, label = { Text("接口金额币种（CNY / USD 等，不做换算）") },
            singleLine = true, enabled = enabled, modifier = Modifier.managedInput().fillMaxWidth(),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(cursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.primary), errorCursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.error)),
        )
        val option = draft.option
        if (option == null) {
            Text("已恢复原型的精确 URL / RFC6901 路径；不会自动改写为点路径。")
            OutlinedTextField(draft.amountPointer, { edit(draft.copy(amountPointer = it)) }, label = { Text("RFC6901 金额路径") },
                singleLine = true, enabled = enabled, modifier = Modifier.managedInput().fillMaxWidth(),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(cursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.primary), errorCursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.error)),
            )
            TextButton(onClick = { edit(draft.copy(option = BalanceOption(enabled = true))) }, enabled = enabled) {
                Text("明确切换到保留的预设 / 表达式设置")
            }
        } else {
            ProviderBalanceOptionFields(option, { edit(draft.copy(option = it)) }, enabled)
            if (option.preset == BalanceOption.PRESET_NEW_API) {
                val userHeader = draft.customHeaders.entries.firstOrNull { it.key.equals("New-Api-User", true) }?.value.orEmpty()
                OutlinedTextField(userHeader, { value ->
                    val headers = draft.customHeaders.filterKeys { !it.equals("New-Api-User", true) }.toMutableMap()
                    if (value.isNotBlank()) headers["New-Api-User"] = value
                    edit(draft.copy(customHeaders = headers.toMap()))
                }, label = { Text("New-Api-User 请求头（仅服务要求时填写）") },
                    singleLine = true, enabled = enabled, modifier = Modifier.managedInput().fillMaxWidth(),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(cursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.primary), errorCursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.error)),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = enabled && dirty, onClick = {
                scope.launch {
                    busy = true
                    notice = null
                    try {
                        runtime.save(draft)
                        dirty = false
                        notice = "已加密保存；保存本身不发起请求。已启动的宿主轮询会使用新配置。"
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { notice = "保存未确认。请检查账户、HTTPS 地址、独立密钥、路径与币种后重新保存。" }
                    finally { busy = false }
                }
            }) { Text("保存配置") }
            TextButton(modifier = Modifier.testTag("balance-query-saved"), enabled = enabled && !dirty && projection.canRefresh &&
                lifecycleState.isAtLeast(Lifecycle.State.STARTED), onClick = {
                // ON_STOP cancels the child Job even if this composition remains mounted.
                manualOwner?.requestRefresh()
            }) { Text(if (state.refreshing) "查询中" else "测试已保存的余额查询") }
        }
        if (dirty) Text("修改尚未保存；测试只查询已明确保存的账户，避免误用旧账户凭据。", style = MaterialTheme.typography.bodySmall)
        if (state.configured) {
            TextButton(enabled = enabled, onClick = { deleteConfirmation = true }) { Text("删除独立配置与余额") }
            if (deleteConfirmation) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = enabled, onClick = {
                    scope.launch {
                        busy = true
                        try { runtime.clear(); notice = "已删除独立配置与缓存余额" }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { notice = "删除未确认，请重新操作" }
                        finally { busy = false; deleteConfirmation = false }
                    }
                }) { Text("确认删除") }
                TextButton(onClick = { deleteConfirmation = false }) { Text("取消") }
            }
        }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(16.dp))
    }
}

private fun emptyBalanceDraft() = BalanceConfiguration("", "", "", "", "CNY", BalanceOption())

/** Preserved expansion, enabled switch, preset ordering and fixed New API vs editable fill templates. */
@Composable
internal fun ProviderBalanceOptionFields(option: BalanceOption, onChange: (BalanceOption) -> Unit, enabled: Boolean) {
    var expanded by remember { mutableStateOf(option.enabled) }
    val resolved = option.resolved()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicComponent(title = "余额信息", summary = "独立余额接口、预设与结果表达式", onClick = { expanded = !expanded },
            endActions = {
                Switch(checked = option.enabled, enabled = enabled,
                    colors = SwitchDefaults.colors(checkedTrackColor = DaiyuPalette.switchTrack,
                        checkedBorderColor = DaiyuPalette.switchTrack), onCheckedChange = {
                    onChange(option.copy(enabled = it))
                    if (it) expanded = true
                })
            })
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val presetIndex = BalancePresets.indexOf(resolved.preset).coerceAtLeast(0)
                WindowSpinnerPreference(
                    items = BalancePresetNames.map { DropdownItem(text = it) },
                    selectedIndex = presetIndex, title = "余额预设",
                    summary = when (resolved.preset) {
                        BalanceOption.PRESET_NEW_API -> "固定用户额度接口与 quota / 500000 单位换算"
                        BalanceOption.PRESET_SUB2API -> "填充 /usage 与 remaining，允许手动修改"
                        BalanceOption.PRESET_DEEPSEEK -> "填充 /user/balance 与 balance_infos[0].total_balance，允许手动修改"
                        else -> "自定义 API 路径与结果表达式"
                    },
                    onSelectedIndexChange = { index ->
                        if (enabled) onChange(BalanceOption.applyPreset(BalancePresets.getOrElse(index) { BalanceOption.PRESET_CUSTOM }, option))
                    },
                )
                if (resolved.preset == BalanceOption.PRESET_NEW_API) {
                    Text("New API 固定：${resolved.apiPath}；${resolved.resultPath}。此前自定义路径保留，切回自定义可恢复。")
                    BalanceSecretField(option.accessToken, { onChange(option.copy(accessToken = it)) }, "New API 独立访问令牌（可覆盖上方余额 Key）", enabled)
                } else {
                    if (option.accessToken.isNotBlank()) BalanceSecretField(option.accessToken,
                        { onChange(option.copy(accessToken = it)) }, "当前仍使用余额访问令牌覆盖；清空后使用上方余额 Key", enabled)
                    MiuixTextField(value = option.apiPath,
                        onValueChange = { if (enabled) onChange(option.copy(apiPath = it)) },
                        label = "余额 API 路径 / 完整 HTTPS URL", singleLine = true, modifier = Modifier.managedInput().fillMaxWidth(),
                        cursorBrush = imeCursorBrush(top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.primary),
                    )
                    MiuixTextField(value = option.resultPath,
                        onValueChange = { if (enabled) onChange(option.copy(resultPath = it)) },
                        label = "结果路径 / 表达式", singleLine = true, modifier = Modifier.managedInput().fillMaxWidth(),
                        cursorBrush = imeCursorBrush(top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.primary),
                    )
                    Text("支持 balance_infos[0].total_balance、点号索引与带空格的 + - * /。Sub2API / DeepSeek 路径留空回到模板。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun BalanceSecretField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, enabled = enabled,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (visible) "隐藏密钥" else "显示密钥")
            }
        }, modifier = Modifier.managedInput().fillMaxWidth(),
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(cursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.primary), errorCursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.error)),
    )
}
