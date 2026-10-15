package app.daiyu.android.ui.components

import androidx.compose.ui.graphics.Color
import app.daiyu.android.model.GptSpeedMode

internal const val SubAgentGptSpeedAnimationDurationMillis = 450
private const val SubAgentGptSpeedRotationDegreesPerCycle = 360f
private const val SubAgentGptSpeedScaleStart = 1f
private const val SubAgentGptSpeedScalePeak = 1.12f
private val SubAgentGptSpeedFastColor = Color(0xFFF2A7BD)
private val SubAgentGptSpeedUltraFastColor = Color(0xFF800020)
internal fun subAgentGptSpeedChipColor(mode: GptSpeedMode, normalColor: Color): Color = when (mode) {
    GptSpeedMode.FAST -> SubAgentGptSpeedFastColor
    GptSpeedMode.ULTRA_FAST -> SubAgentGptSpeedUltraFastColor
    else -> normalColor
}
internal fun subAgentGptSpeedRotationDegrees(progress: Float): Float = SubAgentGptSpeedRotationDegreesPerCycle * progress.coerceIn(0f, 1f)
internal fun subAgentGptSpeedScale(progress: Float): Float {
    val clamped = progress.coerceIn(0f, 1f)
    val peakWeight = 1f - kotlin.math.abs(clamped * 2f - 1f)
    return SubAgentGptSpeedScaleStart + (SubAgentGptSpeedScalePeak - SubAgentGptSpeedScaleStart) * peakWeight
}
internal class SubAgentGptSpeedAnimationGate {
    private var current: Int = 0
    private var issued: Int = 0
    fun request(): Int { if (current != 0) return 0; issued += 1; current = issued; return current }
    fun finish(token: Int) { if (token != 0 && token == current) current = 0 }
}
internal fun subAgentGptSpeedDisplayName(mode: GptSpeedMode): String = when (mode) {
    GptSpeedMode.FAST -> "快速"
    GptSpeedMode.ULTRA_FAST -> "极速"
    GptSpeedMode.NORMAL -> "标准"
}
internal fun subAgentGptSpeedToastMessage(mode: GptSpeedMode): String = "已切换为${subAgentGptSpeedDisplayName(mode)}速度，可能增加费用"
