package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.balance.BalanceMoney
import app.daiyu.android.balance.BalanceOption
import app.daiyu.android.balance.BalanceResult
import app.daiyu.android.balance.BalanceState
import app.daiyu.android.balance.formatBalanceDisplay
import app.daiyu.android.balance.ui.ProviderBalanceAmount
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal typealias ProviderBalanceTester = (ModelSettingsDraftUi, () -> Boolean, (BalanceResult) -> Unit) -> Boolean

internal data class ProviderBalanceTestUi(val running: Boolean, val status: String?,
    val money: BalanceMoney?, val start: () -> Unit, val cancel: () -> Unit)

/** Provider-bound balance options; test receipts never replace saved configuration. */
@Composable
internal fun ProviderBalanceFields(draft: ModelSettingsDraftUi, enabled: Boolean,
    onChange: (ModelSettingsDraftUi) -> Unit, testBalance: ProviderBalanceTester? = null,
    testUi: ProviderBalanceTestUi? = null) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val visit = remember(lifecycle) { AtomicBoolean(true) }
    DisposableEffect(visit) { onDispose { visit.set(false) } }
    val latestDraft by rememberUpdatedState(draft)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestChange by rememberUpdatedState(onChange)
    val configuration = draft.providerBalanceConfiguration()
    val options = draft.balanceOption
    val test = testUi ?: rememberProviderBalanceTest(draft, enabled, testBalance)
    val presets = listOf(BalanceOption.PRESET_CUSTOM, BalanceOption.PRESET_NEW_API,
        BalanceOption.PRESET_SUB2API, BalanceOption.PRESET_DEEPSEEK)
    val names = listOf("自定义", "NewAPI", "Sub2API", "DeepSeek")
    fun change(value: BalanceOption) {
        if (visit.get() && latestEnabled && latestDraft === draft && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            test.cancel()
            latestChange(draft.copy(balanceOption = value))
        }
    }
    SettingsSectionTitle("余额")
    SettingsCard {
        SettingsSwitchRow("余额", options.enabled, enabled = enabled, summary = "从提供商获取账户余额") {
            change(options.copy(enabled = it))
        }
        if (options.enabled) {
            ProviderChoiceRow("预设模板", names.getOrElse(presets.indexOf(options.preset)) { "自定义" },
                "provider-balance-preset", enabled, names, presets.map { "provider-balance-preset:$it" },
                presets.indexOf(options.preset)) { change(BalanceOption.applyPreset(presets[it], options)) }
            if (options.preset == BalanceOption.PRESET_NEW_API) {
                SettingsTextField("访问令牌", options.accessToken, { change(options.copy(accessToken = it)) },
                    password = true, enabled = enabled, compact = true, tag = "provider-balance-token")
            } else {
                SettingsTextField("余额 API 路径", options.apiPath, { change(options.copy(apiPath = it)) },
                    enabled = enabled, compact = true, tag = "provider-balance-path")
                SettingsTextField("结果 JSON 键", options.resultPath, { change(options.copy(resultPath = it)) },
                    enabled = enabled, compact = true, tag = "provider-balance-result")
            }
            val valid = runCatching { requireNotNull(configuration).validateProviderBinding() }.isSuccess
            ProviderActionButton(text = if (test.running) "正在测试余额查询" else "测试余额查询",
                enabled = enabled && testBalance != null && valid && !test.running, onClick = test.start,
                modifier = Modifier.testTag("provider-balance-test").padding(horizontal = 12.dp, vertical = 8.dp))
            test.money?.let { money ->
                Box(Modifier.fillMaxWidth().testTag("provider-balance-test-result")
                    .padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                    ProviderBalanceAmount(formatBalanceDisplay(money.amount.toPlainString()),
                        modifier = Modifier.testTag("provider-balance-test-amount"), iconSize = 16.dp,
                        textStyle = MiuixTheme.textStyles.footnote1.copy(fontSize = 14.sp, lineHeight = 28.sp))
                }
            }
            test.status?.let { SettingsNote(it) }
        }
    }
}

@Composable
internal fun rememberProviderBalanceTest(draft: ModelSettingsDraftUi, enabled: Boolean,
    tester: ProviderBalanceTester?): ProviderBalanceTestUi {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val currentTester by rememberUpdatedState(tester)
    val owner = SettingsConnectionDraftOwner(draft)
    val currentOwner by rememberUpdatedState(owner)
    val currentEnabled by rememberUpdatedState(enabled)
    val visit = remember(lifecycle) { AtomicBoolean(true) }
    var request by remember { mutableStateOf<AtomicBoolean?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var money by remember { mutableStateOf<BalanceMoney?>(null) }
    fun cancel() { request?.set(true); request = null; status = null; money = null }
    DisposableEffect(lifecycle, visit) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) cancel() }
        lifecycle.addObserver(observer)
        onDispose { visit.set(false); cancel(); lifecycle.removeObserver(observer) }
    }
    DisposableEffect(owner, enabled, tester != null) { onDispose { cancel() } }
    return ProviderBalanceTestUi(request != null, status, money, start = {
        val invoke = currentTester
        if (visit.get() && invoke != null && currentEnabled && currentOwner == owner && request == null &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            val cfg = owner.draft.providerBalanceConfiguration()
            if (cfg == null || !cfg.queryEnabled || runCatching { cfg.validateProviderBinding() }.isFailure) {
                status = "请先完善提供商连接与余额查询设置"
            } else {
                val flag = AtomicBoolean(false)
                request = flag; status = null; money = null
                val accepted = try {
                    invoke(owner.draft, flag::get) { result ->
                        scope.launch {
                            if (visit.get() && !flag.get() && request === flag && currentEnabled && currentOwner == owner &&
                                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                flag.set(true); request = null
                                money = (result as? BalanceResult.Available)?.money
                                status = when (result) {
                                    is BalanceResult.Available -> null
                                    else -> BalanceState(configured = true, result = result).project().statusText + "（仅本次测试，未保存）"
                                }
                            }
                        }
                    }
                } catch (_: Exception) { false }
                if (!accepted && request === flag) { flag.set(true); request = null; status = "当前不可测试余额，请稍后重试" }
            }
        }
    }, cancel = ::cancel)
}
