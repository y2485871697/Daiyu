package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.balance.BalanceConfiguration
import app.daiyu.android.balance.BalanceOption
import app.daiyu.android.balance.BalanceResult
import app.daiyu.android.balance.BalanceState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.TextButton

internal typealias ProviderBalanceTester = (ModelSettingsDraftUi, () -> Boolean, (BalanceResult) -> Unit) -> Boolean

/** Independent balance credentials; test receipts never replace the saved configuration. */
@Composable
internal fun ProviderBalanceFields(draft: ModelSettingsDraftUi, enabled: Boolean,
    onChange: (ModelSettingsDraftUi) -> Unit, testBalance: ProviderBalanceTester? = null,
    testUi: SettingsConnectionTestUi? = null) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val visit = remember(lifecycle) { AtomicBoolean(true) }
    DisposableEffect(visit) { onDispose { visit.set(false) } }
    val latestDraft by rememberUpdatedState(draft)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestChange by rememberUpdatedState(onChange)
    val configuration = draft.balanceConfiguration ?: BalanceConfiguration(
        "balance", "", "", "/balance", "USD", draft.balanceOption)
    val legacy = draft.balanceConfiguration != null && configuration.option == null
    val options = configuration.option ?: draft.balanceOption.copy(enabled = true)
    val test = testUi ?: rememberProviderBalanceTest(draft, enabled, testBalance)
    val presets = listOf(BalanceOption.PRESET_CUSTOM, BalanceOption.PRESET_NEW_API,
        BalanceOption.PRESET_SUB2API, BalanceOption.PRESET_DEEPSEEK)
    val names = listOf("自定义", "NewAPI", "Sub2API", "DeepSeek")
    fun update(value: BalanceConfiguration) {
        if (visit.get() && latestEnabled && latestDraft === draft && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            test.cancel()
            val configuration = value.takeUnless { !it.queryEnabled && runCatching { it.validate() }.isFailure }
            latestChange(draft.copy(balanceConfiguration = configuration, balanceOption = value.option ?: draft.balanceOption))
        }
    }
    fun change(value: BalanceOption) = update(configuration.copy(option = value))
    SettingsSectionTitle("余额")
    SettingsCard {
        SettingsSwitchRow("余额", options.enabled, enabled = enabled, summary = "从提供商获取账户余额") {
            change(options.copy(enabled = it))
        }
        if (options.enabled) {
            SettingsTextField("余额账户标识", configuration.accountId, { update(configuration.copy(accountId = it)) },
                enabled = enabled, compact = true, tag = "provider-balance-account")
            SettingsTextField(if (legacy) "独立余额完整 URL" else "独立余额 Base URL", configuration.endpoint,
                { update(configuration.copy(endpoint = it)) }, enabled = enabled, compact = true, tag = "provider-balance-endpoint")
            SettingsTextField("独立余额 API Key", configuration.apiKey, { update(configuration.copy(apiKey = it)) },
                password = true, enabled = enabled, compact = true, tag = "provider-balance-key")
            SettingsTextField("余额币种", configuration.currency, { update(configuration.copy(currency = it)) },
                enabled = enabled, compact = true, tag = "provider-balance-currency")
            BalanceHeaderFields(configuration, enabled, ::update)
            if (legacy) {
                SettingsTextField("金额 JSON Pointer（RFC6901）", configuration.amountPointer,
                    { update(configuration.copy(amountPointer = it)) }, enabled = enabled, compact = true, tag = "provider-balance-pointer")
                SettingsNote("当前使用 RFC6901；更改余额开关或选择模板后改用模板结果键。")
            }
            ProviderChoiceRow("预设模板", if (legacy) "旧版 RFC6901" else names.getOrElse(presets.indexOf(options.preset)) { "自定义" },
                "provider-balance-preset", enabled, names, presets.map { "provider-balance-preset:$it" },
                if (legacy) -1 else presets.indexOf(options.preset), summary = when (options.preset) {
                    BalanceOption.PRESET_NEW_API -> "请求 /api/user/self，并用 data.quota / 500000 换算额度"
                    BalanceOption.PRESET_SUB2API -> "填入 /usage 和 remaining，可以再改"
                    BalanceOption.PRESET_DEEPSEEK -> "填入 /user/balance 和 balance_infos[0].total_balance，可以再改"
                    else -> "自行填写接口路径和 JSON 结果键"
                }) { change(BalanceOption.applyPreset(presets[it], options)) }
            if (!legacy && options.preset == BalanceOption.PRESET_NEW_API) {
                SettingsTextField("访问令牌", options.accessToken, { change(options.copy(accessToken = it)) },
                    password = true, enabled = enabled, compact = true, tag = "provider-balance-token")
                SettingsNote("留空仅使用上方独立余额 API Key，不使用聊天密钥。")
            } else if (!legacy) {
                SettingsTextField("余额 API 路径", options.apiPath, { change(options.copy(apiPath = it)) },
                    enabled = enabled, compact = true, tag = "provider-balance-path")
                SettingsTextField("结果 JSON 键", options.resultPath, { change(options.copy(resultPath = it)) },
                    enabled = enabled, compact = true, tag = "provider-balance-result")
            }
            if (!legacy && options.accessToken.isNotEmpty()) {
                TextButton(text = "清除余额访问令牌", enabled = enabled,
                    modifier = Modifier.testTag("provider-balance-token-clear"), onClick = { change(options.copy(accessToken = "")) })
                if (options.preset != BalanceOption.PRESET_NEW_API) SettingsNote("仍使用先前的余额访问令牌；清除后使用独立余额 API Key。")
            }
            val valid = runCatching { configuration.validate() }.isSuccess
            if (!valid) SettingsNote("请检查独立 HTTPS 地址、账户标识、币种、结果路径及请求头。", error = true)
            TextButton(text = if (test.running) "正在测试余额查询" else "测试余额查询",
                enabled = enabled && testBalance != null && valid && !test.running, onClick = test.start,
                modifier = Modifier.testTag("provider-balance-test").fillMaxWidth().padding(12.dp))
            test.status?.let { SettingsNote(it) }
        }
    }
}

@Composable
private fun BalanceHeaderFields(configuration: BalanceConfiguration, enabled: Boolean,
    onChange: (BalanceConfiguration) -> Unit) {
    var duplicate by remember(configuration.customHeaders) { mutableStateOf(false) }
    val rows = configuration.customHeaders.entries.toList()
    fun replace(index: Int, name: String, value: String) {
        if (rows.withIndex().any { it.index != index && it.value.key.equals(name, true) }) { duplicate = true; return }
        onChange(configuration.copy(customHeaders = rows.mapIndexed { i, entry ->
            if (i == index) name to value else entry.key to entry.value
        }.toMap()))
    }
    rows.forEachIndexed { index, entry ->
        SettingsTextField("余额请求头名称", entry.key, { replace(index, it, entry.value) },
            enabled = enabled, compact = true, tag = "provider-balance-header-name:$index")
        SettingsTextField("余额请求头值", entry.value, { replace(index, entry.key, it) },
            password = true, enabled = enabled, compact = true, tag = "provider-balance-header-value:$index")
        TextButton(text = "删除请求头", enabled = enabled, modifier = Modifier.testTag("provider-balance-header-delete:$index"),
            onClick = { onChange(configuration.copy(customHeaders = configuration.customHeaders - entry.key)) })
    }
    if (duplicate) SettingsNote("余额请求头名称不能重复。", error = true)
    TextButton(text = "添加余额请求头", enabled = enabled && rows.size < 16,
        modifier = Modifier.testTag("provider-balance-header-add"), onClick = {
            var name = "X-Balance-${rows.size + 1}"
            while (rows.any { it.key.equals(name, true) }) name += "-1"
            onChange(configuration.copy(customHeaders = configuration.customHeaders + (name to "")))
        })
}

@Composable
internal fun rememberProviderBalanceTest(draft: ModelSettingsDraftUi, enabled: Boolean,
    tester: ProviderBalanceTester?): SettingsConnectionTestUi {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val currentTester by rememberUpdatedState(tester)
    val owner = SettingsConnectionDraftOwner(draft)
    val currentOwner by rememberUpdatedState(owner)
    val currentEnabled by rememberUpdatedState(enabled)
    val visit = remember(lifecycle) { AtomicBoolean(true) }
    var request by remember { mutableStateOf<AtomicBoolean?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    fun cancel() { request?.set(true); request = null; status = null }
    DisposableEffect(lifecycle, visit) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) cancel() }
        lifecycle.addObserver(observer)
        onDispose { visit.set(false); cancel(); lifecycle.removeObserver(observer) }
    }
    DisposableEffect(owner, enabled, tester != null) { onDispose { cancel() } }
    return SettingsConnectionTestUi(request != null, status, start = {
        val invoke = currentTester
        if (visit.get() && invoke != null && currentEnabled && currentOwner == owner && request == null &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            val cfg = owner.draft.balanceConfiguration
            if (cfg == null || !cfg.queryEnabled || runCatching { cfg.validate() }.isFailure) {
                status = "请先完善独立余额配置"
            } else {
                val flag = AtomicBoolean(false)
                request = flag; status = null
                val accepted = try {
                    invoke(owner.draft, flag::get) { result ->
                        scope.launch {
                            if (visit.get() && !flag.get() && request === flag && currentEnabled && currentOwner == owner &&
                                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                flag.set(true); request = null
                                status = when (result) {
                                    is BalanceResult.Available -> "余额测试成功：${result.money.display()}"
                                    else -> BalanceState(configured = true, result = result).project().statusText
                                } + "（仅本次测试，未保存）"
                            }
                        }
                    }
                } catch (_: Exception) { false }
                if (!accepted && request === flag) { flag.set(true); request = null; status = "当前不可测试余额，请稍后重试" }
            }
        }
    }, cancel = ::cancel)
}
