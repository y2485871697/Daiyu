package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Slider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.daiyu.android.domain.pet.PetFood
import app.daiyu.android.pet.PetHost
import app.daiyu.android.ui.components.MiuixScaffoldPage
import kotlin.math.roundToInt

private enum class PetSettingsPage { HOME, PERMISSION, MORE, SCALE, FEED, MEMORY }

/** The application owns this host; navigating settings never creates or closes a runtime. */
@Composable
fun PetSettingsScreen(host: PetHost, modifier: Modifier = Modifier, onBack: () -> Unit = {},
    onOpenModel: () -> Unit = {}, overlayAllowed: () -> Boolean = { false },
    onOverlayAllowedChange: ((Boolean) -> Unit)? = null, onOpenOverlayPermission: () -> Unit = {}) {
    var snapshot by remember(host) { mutableStateOf(host.snapshot()) }
    DisposableEffect(host) {
        val observation = host.observe { snapshot = it }
        onDispose { observation.cancel() }
    }
    var page by remember(host) { mutableStateOf(PetSettingsPage.HOME) }
    var processAllowed by remember(host) { mutableStateOf(overlayAllowed()) }
    var notice by remember(host) { mutableStateOf<String?>(null) }
    val settings = snapshot.pet.state.settings
    val state = snapshot.pet.state
    val enabled = !snapshot.closed
    val back: () -> Unit = {
        page = when (page) {
            PetSettingsPage.HOME -> { onBack(); PetSettingsPage.HOME }
            PetSettingsPage.PERMISSION, PetSettingsPage.MORE -> PetSettingsPage.HOME
            else -> PetSettingsPage.MORE
        }
    }
    BackHandler(enabled = page != PetSettingsPage.HOME, onBack = back)
    MiuixScaffoldPage(title = when (page) {
        PetSettingsPage.HOME -> "鲸鱼女仆"
        PetSettingsPage.PERMISSION -> "悬浮显示权限"
        PetSettingsPage.MORE -> "更多设置"
        PetSettingsPage.SCALE -> "桌宠大小"
        PetSettingsPage.FEED -> "投喂"
        PetSettingsPage.MEMORY -> "记忆与用量"
    }, onBack = back, modifier = modifier.testTag("pet-settings-view")) {
        snapshot.pet.error?.let { item("pet-error") { SettingsNote(it, error = true) } }
        notice?.let { item("pet-notice") { SettingsNote(it) } }
        when (page) {
            PetSettingsPage.HOME -> {
                item("pet-controls") {
                    SettingsCard {
                        SettingsSwitchRow("鲸鱼女仆", settings.enabled, enabled, tag = "pet-enabled") { host.setEnabled(it) }
                        SettingsSwitchRow("任务结束后说话", settings.workReactions, enabled) {
                            host.updateSettings(host.snapshot().pet.state.settings.copy(workReactions = it))
                        }
                        SettingsSwitchRow("全局显示", settings.globalVisible, enabled,
                            summary = if (settings.globalVisible && !snapshot.canDrawOverlays) "请在悬浮显示权限中完成授权" else null) {
                            host.setGlobalVisible(it)
                        }
                        SettingsRow("说话模型", summary = if (settings.model.modelId.isBlank()) "尚未配置" else "已选择独立模型",
                            tag = "pet-model-configure", onClick = if (enabled) onOpenModel else null)
                    }
                }
                item("pet-settings-links") {
                    SettingsCard {
                        SettingsRow("悬浮显示权限", tag = "pet-overlay-permission", onClick = { page = PetSettingsPage.PERMISSION })
                        SettingsRow("更多设置", summary = "投喂、大小、语音与记忆", onClick = { page = PetSettingsPage.MORE })
                    }
                }
            }
            PetSettingsPage.PERMISSION -> item("pet-overlay-controls") {
                SettingsCard {
                    if (onOverlayAllowedChange != null) SettingsSwitchRow("允许本次运行悬浮显示", processAllowed, enabled) {
                        onOverlayAllowedChange(it)
                        processAllowed = overlayAllowed()
                    }
                    SettingsRow("系统悬浮窗权限", summary = "打开系统设置进行授权", onClick = onOpenOverlayPermission)
                    SettingsRow("刷新权限状态", summary = if (snapshot.canDrawOverlays) "已允许悬浮显示" else "尚未允许悬浮显示",
                        onClick = if (enabled) ({ host.refreshOverlayPermission() }) else null)
                }
                SettingsNote("本次运行许可和系统权限均允许后，才会在其他应用上显示。")
                snapshot.overlayError?.let { SettingsNote(it, error = true) }
            }
            PetSettingsPage.MORE -> item("pet-more") {
                SettingsCard {
                    SettingsSwitchRow("语音播报", settings.audioEnabled, enabled) {
                        host.updateSettings(host.snapshot().pet.state.settings.copy(audioEnabled = it))
                    }
                    SettingsRow("桌宠大小", summary = "${(settings.scale * 100).roundToInt()}%", onClick = { page = PetSettingsPage.SCALE })
                    SettingsRow("投喂", summary = "粮食 ${state.satiety}", onClick = { page = PetSettingsPage.FEED })
                    SettingsRow("记忆与用量", onClick = { page = PetSettingsPage.MEMORY })
                }
            }
            PetSettingsPage.SCALE -> item("pet-scale") {
                SettingsCard {
                    SettingsRow("当前大小", summary = "${(settings.scale * 100).roundToInt()}%")
                    Slider(value = settings.scale, onValueChange = { host.controller.setScale(it) }, enabled = enabled,
                        valueRange = .5f..1.8f, steps = 25, modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsRow("恢复默认大小", onClick = if (enabled) ({ host.controller.setScale(1f) }) else null)
                }
            }
            PetSettingsPage.FEED -> item("pet-foods") {
                SettingsCard {
                    PetFood.entries.forEach { food ->
                        val bitmap = remember(host, food) { host.resources.food(food.resourceKey)?.asImageBitmap() }
                        SettingsRow(food.displayName, summary = "粮食 +${food.satiety}",
                            leading = { if (bitmap != null) Image(bitmap, null, Modifier.size(40.dp)) },
                            onClick = if (enabled && settings.enabled && !state.thinking) ({ host.feed(food) }) else null)
                    }
                }
            }
            PetSettingsPage.MEMORY -> {
                item("pet-memory-summary") {
                    SettingsCard {
                        SettingsRow("清空记忆", summary = "同时取消当前反应", onClick = if (enabled) ({ host.controller.clearMemories() }) else null)
                        if (state.memories.isEmpty()) SettingsNote("暂无记忆")
                        else state.memories.forEach { SettingsNote(it.speech) }
                    }
                }
                item("pet-usage") {
                    val known = state.usage.filter { it.inputTokens != null && it.outputTokens != null }
                    SettingsCard {
                        SettingsRow("模型请求", summary = "${state.usage.size} 次 · ${known.size} 次报告完整用量")
                        SettingsRow("已报告 Token", summary = "输入 ${known.sumOf { it.inputTokens ?: 0 }} · 输出 ${known.sumOf { it.outputTokens ?: 0 }}")
                        SettingsNote("桌宠用量独立统计；未报告不代表免费。")
                    }
                }
            }
        }
    }
}
