package app.daiyu.android.voice.control

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Stateless surface. Selection/settings do not grant consent, request permission or open audio. */
@Composable
fun VoiceControlPanel(
    state: VoiceControlState,
    profiles: List<VoiceControlProfile>,
    selectedProfile: VoiceControlProfile?,
    limits: VoiceControlLimits,
    onSelectProfile: (VoiceControlProfile) -> Unit,
    onLimitsChange: (VoiceControlLimits) -> Unit,
    onConfigureProfile: (VoiceControlProfile?) -> Unit,
    onStart: (VoiceControlProfile, VoiceControlLimits) -> Unit,
    onStop: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("持续语音 · UNIVERSAL", style = MaterialTheme.typography.titleMedium)
        Text("一次开始仅授权本次有限对话：最终识别将自动发送，回答正文按句播放；音频真正播放完成并释放后才再次收听。云端识别及按句合成可能收费。")
        Text("普通听写仍只填草稿，不会自动发送；豆包双工是独立入口。")
        Text("状态：${phaseLabel(state.phase)} · ${state.turn}/${state.maxTurns} 轮")
        state.reason?.let { Text("已结束：${reasonLabel(it)}") }
        Text("配置：${selectedProfile?.label ?: "尚未选择"}")
        profiles.forEach { profile ->
            OutlinedButton(onClick = { onSelectProfile(profile) }, enabled = !state.active) {
                Text((if (selectedProfile == profile) "✓ " else "") + profile.label)
            }
        }
        OutlinedButton(onClick = { onConfigureProfile(selectedProfile) }, enabled = !state.active) {
            Text("配置识别 / 正文播放 profile")
        }
        Text("本次上限：${limits.maxTurns} 轮 / ${limits.durationMillis / 1000} 秒（先到即停）")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onLimitsChange(limits.copy(maxTurns = 1, durationMillis = 60_000)) }, enabled = !state.active) { Text("1轮 / 60秒") }
            OutlinedButton(onClick = { onLimitsChange(limits.copy(maxTurns = 3, durationMillis = 180_000)) }, enabled = !state.active) { Text("3轮 / 180秒") }
        }
        if (state.transcript.isNotEmpty()) Text("本轮识别：${state.transcript}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { selectedProfile?.let { onStart(it, limits) } },
                enabled = !state.active && selectedProfile != null && selectedProfile in profiles && state.reason != VoiceControlStopReason.RELEASE_FAILED) {
                Text("开始本次有限持续语音")
            }
            OutlinedButton(onClick = { state.permitId?.let(onStop) }, enabled = state.active && state.permitId != null) { Text("停止本次") }
        }
        Text("离开前台、切换会话/配置、权限撤销、等待问题确认或任何失败都会结束许可；不会自动重试或重发。")
    }
}

private fun phaseLabel(phase: VoiceControlPhase): String = when (phase) {
    VoiceControlPhase.OFF -> "未开始"
    VoiceControlPhase.PERMISSION_REQUIRED -> "等待本次麦克风授权（尚未打开音频）"
    VoiceControlPhase.CONNECTING -> "准备收听"
    VoiceControlPhase.LISTENING -> "正在收听最终识别"
    VoiceControlPhase.WAITING_FOR_FINAL -> "收音已结束，等待最终识别"
    VoiceControlPhase.SUBMITTING -> "等待确切发送收据（不重发）"
    VoiceControlPhase.WAITING_FOR_BODY -> "已绑定本次 assistant，等待正文"
    VoiceControlPhase.SPEAKING -> "正文播放中（可能等待完整句子/音频）"
    VoiceControlPhase.DRAINING -> "正文已结束，等待音频播放完成并释放"
    VoiceControlPhase.STOPPED -> "许可已结束"
}
private fun reasonLabel(reason: VoiceControlStopReason): String = when (reason) {
    VoiceControlStopReason.USER -> "用户停止"
    VoiceControlStopReason.REVOKED -> "页面、会话或生命周期许可已撤销"
    VoiceControlStopReason.DISABLED -> "持续语音未启用"
    VoiceControlStopReason.CONFIGURATION_CHANGED -> "配置无效或已更改"
    VoiceControlStopReason.PERMISSION_DENIED -> "没有有效麦克风权限"
    VoiceControlStopReason.QUESTION_WAITING -> "等待用户回答/确认，请手动处理"
    VoiceControlStopReason.EMPTY_FINAL -> "最终识别为空或超出限制"
    VoiceControlStopReason.RECOGNITION_FAILED -> "识别失败"
    VoiceControlStopReason.REJECTED -> "发送被拒绝；未重试"
    VoiceControlStopReason.UNKNOWN -> "发送或运行结果不确定；保留原收据/草稿，不重发"
    VoiceControlStopReason.INVALID_RECEIPT -> "发送收据身份不匹配"
    VoiceControlStopReason.RESPONSE_FAILED -> "本次回答停止、失败或正文无法安全播放"
    VoiceControlStopReason.PLAYBACK_FAILED -> "正文播放失败或被抢占"
    VoiceControlStopReason.RELEASE_FAILED -> "资源释放未确认，禁止再次开始；请重启后检查"
    VoiceControlStopReason.TIMEOUT -> "本阶段超时；未重试"
    VoiceControlStopReason.EXPIRED -> "本次时长上限已到"
    VoiceControlStopReason.TURN_LIMIT -> "本次轮数上限已到"
    VoiceControlStopReason.CLOSED -> "页面已关闭"
}
