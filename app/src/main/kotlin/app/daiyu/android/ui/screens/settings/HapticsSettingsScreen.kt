package app.daiyu.android.ui.screens.settings

import app.daiyu.android.ui.app.DaiyuPalette

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import app.daiyu.android.ui.haptics.toggleable
import top.yukonga.miuix.kmp.basic.Slider as IntensityPreviewSlider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Text
import app.daiyu.android.ui.screens.settings.SettingsSwitch as Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.screens.assistants.assistantPreviewPalette
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.daiyu.android.domain.haptics.HapticDelivery
import app.daiyu.android.domain.haptics.HapticIntensity
import app.daiyu.android.domain.haptics.HapticSettings
import app.daiyu.android.haptics.HapticsRuntime
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * The host owns runtime/session/lifecycle; opening settings never creates a second preference store.
 * Local haptic preferences are live (including while generating), not frozen model/run settings.
 * ownerCurrent guards a delayed callback from an outgoing screen; a revoked owner cannot save.
 */
@Composable
internal fun HapticsSettingsScreen(
    runtime: HapticsRuntime,
    session: HapticsRuntime.Session,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    ownerCurrent: () -> Boolean = { true },
) {
    var settings by remember(runtime) { mutableStateOf(runtime.store.read()) }
    var error by remember(runtime) { mutableStateOf<String?>(null) }
    var intensityDialog by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val currentOwner by rememberUpdatedState(ownerCurrent)
    val pageOwner = remember(runtime, session) { HapticsPageOwner() }
    val canAct = { pageOwner.active && currentOwner() }
    DisposableEffect(runtime, session) {
        pageOwner.active = true
        val handler = Handler(Looper.getMainLooper())
        var alive = true
        val observation = runtime.store.observe { value ->
            if (Looper.myLooper() == Looper.getMainLooper()) {
                if (alive) settings = value
            } else handler.post { if (alive) settings = value }
        }
        onDispose {
            alive = false; pageOwner.active = false
            observation.close(); handler.removeCallbacksAndMessages(null)
        }
    }

    fun save(transform: HapticSettings.() -> HapticSettings): Boolean {
        if (!canAct()) return false
        val old = runtime.store.read()
        val next = old.transform()
        if (!runtime.store.write(next)) {
            settings = runtime.store.read()
            error = "设置写入失败，未保存"
            return false
        }
        settings = next
        error = null
        if (!old.touch && next.touch) session.touch()
        return true
    }
    fun preview(intensity: HapticIntensity) {
        if (!canAct()) return
        error = when (session.preview(intensity)) {
            HapticDelivery.SYSTEM_DISABLED -> "系统触感已关闭"
            HapticDelivery.PERMISSION_DENIED -> "震动权限不可用"
            HapticDelivery.UNAVAILABLE -> "当前设备或页面无法提供震动"
            HapticDelivery.FAILED -> "震动播放失败"
            null -> "当前页面不可试听"
            HapticDelivery.PLAYED -> null
        }
    }
    val labels = listOf("默认", "低", "中", "高")
    val intensityAccent = assistantPreviewPalette().primary
    SettingsScaffoldPage(title = "触感反馈", onBack = onBack, modifier = modifier) {
        item(key = "haptics_switches") {
            SettingsSectionTitle("触感设置")
            SettingsCard {
                SettingsRow("触感反馈", tag = "settings-switch:触感反馈",
                    summary = labels[settings.intensity.ordinal],
                    onClick = { if (canAct()) intensityDialog = true }, trailing = {
                        HapticsSwitchSlot("触感反馈", expanded = intensityDialog) {
                            Switch(checked = settings.touch,
                                colors = SwitchDefaults.colors(checkedTrackColor = DaiyuPalette.switchTrack,
                                    checkedBorderColor = DaiyuPalette.switchTrack), onCheckedChange = { save { copy(touch = it) } })
                        }
                    })
                HapticsSwitchRow("消息生成震动", settings.generationOn, settings.touch) {
                    save { copy(generation = it) }
                }
                SettingsRow("后台震动", tag = "settings-switch:后台震动",
                    summary = when {
                        !settings.backgroundOn -> "未启用"
                        settings.backgroundReasoning -> "正文、工具与推理过程"
                        else -> "正文、工具与推理完成"
                    },
                    onClick = if (settings.generationOn) ({
                        if (canAct()) {
                            if (!settings.backgroundOn) { if (save { copy(background = true) }) expanded = true }
                            else { expanded = !expanded }
                        }
                    }) else null, trailing = {
                        HapticsSwitchSlot("后台震动", expanded = expanded.takeIf { settings.backgroundOn }) {
                            Switch(checked = settings.backgroundOn, enabled = settings.generationOn,
                                colors = SwitchDefaults.colors(checkedTrackColor = DaiyuPalette.switchTrack,
                                    checkedBorderColor = DaiyuPalette.switchTrack),
                                onCheckedChange = { if (save { copy(background = it) }) expanded = it })
                        }
                    })
                if (expanded && settings.backgroundOn) HapticsSwitchRow(
                    "后台推理过程震动", settings.backgroundReasoning, true,
                ) { save { copy(backgroundReasoning = it) } }
            }
        }
        error?.let { message -> item(key = "haptics_error") { SettingsNote(message, error = true) } }
    }
    top.yukonga.miuix.kmp.window.WindowDialog(show = intensityDialog, title = "震动幅度",
        onDismissRequest = { intensityDialog = false }) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                DaiyuIcon(DaiyuIcons.Vibration, contentDescription = null, size = 32.dp,
                    tint = intensityAccent, modifier = Modifier.padding(top = 6.dp))
                Text(labels[settings.intensity.ordinal], style = MiuixTheme.textStyles.title3,
                    modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
                IntensityPreviewSlider(value = settings.intensity.ordinal.toFloat(), valueRange = 0f..3f, steps = 2,
                    showKeyPoints = true, keyPoints = HapticIntensity.entries.indices.map { it.toFloat() },
                    magnetThreshold = 0.18f, hapticEffect = SliderDefaults.SliderHapticEffect.None,
                    colors = SliderDefaults.sliderColors(foregroundColor = intensityAccent,
                        keyPointColor = intensityAccent.copy(alpha = .30f), keyPointForegroundColor = intensityAccent),
                    modifier = Modifier.fillMaxWidth().testTag("haptics-intensity"),
                    onValueChange = { value ->
                        val next = HapticIntensity.entries[value.roundToInt().coerceIn(0, 3)]
                        if (canAct() && next != runtime.store.read().intensity) {
                            preview(next)
                            val saved = runtime.store.write(runtime.store.read().copy(intensity = next))
                            settings = runtime.store.read()
                            if (!saved) error = "设置写入失败，未保存"
                        }
                    })
            }
        }

}

private class HapticsPageOwner { var active = false }

@Composable
private fun HapticsSwitchRow(title: String, checked: Boolean, enabled: Boolean,
                             onChange: (Boolean) -> Unit) {
    val colors = MiuixTheme.colorScheme
    SettingsRow(title, tag = "settings-switch:$title", modifier = Modifier.toggleable(
        value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange,
    ), trailing = {
        HapticsSwitchSlot(title) {
            Switch(checked = checked, enabled = enabled, onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedThumbColor = colors.onPrimary,
                    checkedTrackColor = DaiyuPalette.switchTrack, checkedBorderColor = DaiyuPalette.switchTrack,
                    uncheckedThumbColor = colors.onSurfaceVariantSummary,
                    uncheckedTrackColor = colors.surface, uncheckedBorderColor = colors.onSurfaceVariantSummary))
        }
    })
}

@Composable
private fun HapticsSwitchSlot(title: String, expanded: Boolean? = null, content: @Composable () -> Unit) {
    Row(Modifier.size(width = 72.dp, height = 48.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        if (expanded != null) DaiyuIcon(
            if (expanded) DaiyuIcons.ChevronDown else DaiyuIcons.ChevronRight,
            contentDescription = if (expanded) "收起$title" else "展开$title",
            size = 16.dp, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        ) else Box(Modifier.size(16.dp))
        Box(Modifier.size(48.dp).testTag("haptics-switch-slot:$title"), contentAlignment = Alignment.Center) {
            content()
        }
    }
}
