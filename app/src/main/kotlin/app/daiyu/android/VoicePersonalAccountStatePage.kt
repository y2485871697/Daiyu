package app.daiyu.android

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import app.daiyu.android.ui.screens.settings.SettingsCard
import app.daiyu.android.ui.screens.settings.SettingsRow

@Composable
internal fun VoicePersonalAccountStatePage(
    state: ControllerVoiceRuntime.LoadState,
    enabled: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val failed = state == ControllerVoiceRuntime.LoadState.FAILED || state == ControllerVoiceRuntime.LoadState.CANCELLED
    VoicePersonalScaffold("我的声音", onBack, enabled = enabled) {
        item {
            SettingsCard(Modifier.testTag("voice-account-load-state")) {
                SettingsRow(if (failed) "账户读取失败" else "正在读取已保存账户…",
                    summary = if (failed) "原配置已保留，请重试读取。" else null)
                if (failed) SettingsRow("重新读取", tag = "voice-account-retry",
                    onClick = if (enabled) onRetry else null)
            }
        }
    }
}
