package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.daiyu.android.balance.BalanceOption
import top.yukonga.miuix.kmp.basic.TextButton

/** Frontend draft only; provider persistence and balance queries are intentionally not connected. */
@Composable
internal fun ProviderBalanceFields(draft: ModelSettingsDraftUi, enabled: Boolean,
    onChange: (ModelSettingsDraftUi) -> Unit) {
    val options = draft.balanceOption
    val presets = listOf(BalanceOption.PRESET_CUSTOM, BalanceOption.PRESET_NEW_API,
        BalanceOption.PRESET_SUB2API, BalanceOption.PRESET_DEEPSEEK)
    val names = listOf("自定义", "NewAPI", "Sub2API", "DeepSeek")
    fun change(value: BalanceOption) { if (enabled) onChange(draft.copy(balanceOption = value)) }
    SettingsSectionTitle("余额")
    SettingsCard {
        SettingsSwitchRow("余额", options.enabled, enabled = enabled, summary = "从提供商获取账户余额") {
            change(options.copy(enabled = it))
        }
        if (options.enabled) {
            ProviderChoiceRow("预设模板", names.getOrElse(presets.indexOf(options.preset)) { "自定义" },
                "provider-balance-preset", enabled, names, presets.map { "provider-balance-preset:$it" },
                presets.indexOf(options.preset), summary = when (options.preset) {
                    BalanceOption.PRESET_NEW_API -> "请求 /api/user/self，并用 data.quota / 500000 换算额度"
                    BalanceOption.PRESET_SUB2API -> "填入 /usage 和 remaining，可以再改"
                    BalanceOption.PRESET_DEEPSEEK -> "填入 /user/balance 和 balance_infos[0].total_balance，可以再改"
                    else -> "自行填写接口路径和 JSON 结果键"
                }) { change(BalanceOption.applyPreset(presets[it], options)) }
            if (options.preset == BalanceOption.PRESET_NEW_API) {
                SettingsTextField("访问令牌", options.accessToken, { change(options.copy(accessToken = it)) },
                    password = true, enabled = enabled, compact = true, tag = "provider-balance-token")
                SettingsNote("来自 NewAPI 个人安全设置。留空则使用提供商 API Key")
            } else {
                SettingsTextField("余额 API 路径", options.apiPath, { change(options.copy(apiPath = it)) },
                    enabled = enabled, compact = true, tag = "provider-balance-path")
                SettingsTextField("结果 JSON 键", options.resultPath, { change(options.copy(resultPath = it)) },
                    enabled = enabled, compact = true, tag = "provider-balance-result")
            }
            TextButton(text = "测试余额查询", enabled = enabled, onClick = {},
                modifier = Modifier.testTag("provider-balance-test").fillMaxWidth().padding(12.dp))
        }
    }
}
