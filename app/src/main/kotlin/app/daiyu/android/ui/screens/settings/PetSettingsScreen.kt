package app.daiyu.android.ui.screens.settings

import app.daiyu.android.ui.navigation.PageBackHandler as BackHandler
import app.daiyu.android.ui.haptics.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import app.daiyu.android.ui.haptics.material.Slider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.daiyu.android.domain.pet.PetFood
import app.daiyu.android.pet.PetHost
import app.daiyu.android.pet.PetApplicationHost
import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.storage.IndependentModelSettings
import app.daiyu.android.ui.components.DaiyuChoiceSheet
import app.daiyu.android.ui.components.DaiyuConfirmDialog
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import kotlin.math.roundToInt

private enum class PetSettingsPage { HOME, MORE, SCALE, FEED, MEMORY }

/** The application owns this host; navigating settings never creates or closes a runtime. */
@Composable
internal fun PetSettingsScreen(host: PetHost, models: IndependentModelSettings,
    source: SettingsModelSource, assistants: List<AssistantProfile>, pets: PetApplicationHost,
    modifier: Modifier = Modifier, onBack: () -> Unit = {},
    onOverlayAllowedChange: (Boolean) -> Unit, onOpenOverlayPermission: () -> Unit) {
    IndependentModelSelection(models, IndependentModelSettings.Slot.PET, assistants, pets, source) { model ->
        PetSettingsContent(host, model, modifier, onBack, onOverlayAllowedChange, onOpenOverlayPermission)
    }
}

@Composable
private fun PetSettingsContent(host: PetHost, model: IndependentModelControls, modifier: Modifier,
    onBack: () -> Unit, onOverlayAllowedChange: (Boolean) -> Unit, onOpenOverlayPermission: () -> Unit) {
    var snapshot by remember(host) { mutableStateOf(host.snapshot()) }
    DisposableEffect(host) {
        val observation = host.observe { snapshot = it }
        onDispose { observation.cancel() }
    }
    var page by remember(host) { mutableStateOf(PetSettingsPage.HOME) }
    var showMenu by remember(host) { mutableStateOf(false) }
    var showPermission by remember(host) { mutableStateOf(false) }
    val settings = snapshot.pet.state.settings
    val state = snapshot.pet.state
    val enabled = !snapshot.closed
    val back: () -> Unit = {
        page = when (page) {
            PetSettingsPage.HOME -> { onBack(); PetSettingsPage.HOME }
            PetSettingsPage.MORE -> PetSettingsPage.HOME
            else -> PetSettingsPage.MORE
        }
    }
    BackHandler(enabled = page != PetSettingsPage.HOME, onBack = back)
    SettingsScaffoldPage(title = when (page) {
        PetSettingsPage.HOME -> "鲸鱼女仆"
        PetSettingsPage.MORE -> "更多设置"
        PetSettingsPage.SCALE -> "桌宠大小"
        PetSettingsPage.FEED -> "投喂"
        PetSettingsPage.MEMORY -> "记忆与用量"
    }, onBack = back, modifier = modifier.testTag("pet-settings-view")) {
        when (page) {
            PetSettingsPage.HOME -> item("pet-controls") {
                SettingsCard(Modifier.padding(top = 12.dp)) {
                    SettingsSwitchRow("鲸鱼女仆", settings.enabled, enabled, tag = "pet-enabled",
                        accessory = {
                            DaiyuIcon(DaiyuIcons.ChevronDown, contentDescription = "桌宠选项",
                                modifier = Modifier.padding(end = 12.dp).size(20.dp)
                                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showMenu = true })
                        }) { host.setEnabled(it) }
                    SettingsSwitchRow("任务结束后说话", settings.workReactions, enabled,
                        tag = "pet-work-reactions") {
                        host.updateSettings(host.snapshot().pet.state.settings.copy(workReactions = it))
                    }
                    SettingsSwitchRow("全局显示", settings.globalVisible, enabled,
                        tag = "pet-global-visible") { requested ->
                        if (!requested) {
                            host.setGlobalVisible(false)
                            onOverlayAllowedChange(false)
                        } else {
                            onOverlayAllowedChange(true)
                            host.setGlobalVisible(true)
                            if (!host.snapshot().canDrawOverlays) showPermission = true
                        }
                    }
                    SettingsSwitchRow("指定说话模型", model.enabled, enabled && (!model.busy || model.saving),
                        busy = model.saving, tag = "pet-custom-model", onCheckedChange = model.onEnabledChange)
                    SettingsRow("说话模型", summary = model.label ?: "选择模型",
                        tag = "pet-model-configure", showChevron = true, onClick = if (enabled && !model.busy) model.onChoose else null)
                }
            }
            PetSettingsPage.MORE -> item("pet-more") {
                SettingsCard {
                    SettingsSwitchRow("语音播报", settings.audioEnabled, enabled) {
                        host.updateSettings(host.snapshot().pet.state.settings.copy(audioEnabled = it))
                    }
                    SettingsRow("桌宠大小", summary = "${(settings.scale * 100).roundToInt()}%", onClick = { page = PetSettingsPage.SCALE })
                    SettingsRow("投喂", summary = "粮食 ${state.satiety}", onClick = { page = PetSettingsPage.FEED })
                    SettingsRow("记忆与用量", onClick = { page = PetSettingsPage.MEMORY })
                    SettingsRow("系统悬浮窗权限", onClick = onOpenOverlayPermission)
                    SettingsRow("刷新权限状态", showChevron = true, onClick = if (enabled) ({ host.refreshOverlayPermission() }) else null)
                }
            }
            PetSettingsPage.SCALE -> item("pet-scale") {
                SettingsCard {
                    SettingsRow("当前大小", summary = "${(settings.scale * 100).roundToInt()}%")
                    Slider(value = settings.scale, onValueChange = { host.controller.setScale(it) }, enabled = enabled,
                        valueRange = .5f..1.8f, steps = 25, modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsRow("恢复默认大小", showChevron = true, onClick = if (enabled) ({ host.controller.setScale(1f) }) else null)
                }
            }
            PetSettingsPage.FEED -> item("pet-foods") {
                SettingsCard {
                    PetFood.entries.forEach { food ->
                        val bitmap = remember(host, food) { host.resources.food(food.resourceKey)?.asImageBitmap() }
                        SettingsRow(food.displayName, summary = "粮食 +${food.satiety}",
                            leading = { if (bitmap != null) Image(bitmap, null, Modifier.size(40.dp)) },
                            showChevron = true, onClick = if (enabled && settings.enabled && !state.thinking) ({ host.feed(food) }) else null)
                    }
                }
            }
            PetSettingsPage.MEMORY -> {
                item("pet-memory-summary") {
                    SettingsCard {
                        SettingsRow("清空记忆", summary = "同时取消当前反应", showChevron = true, onClick = if (enabled) ({ host.controller.clearMemories() }) else null)
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
    if (showMenu) DaiyuChoiceSheet("鲸鱼女仆", listOf("鲸鱼女仆", "更多设置"),
        onSelect = { index -> showMenu = false; if (index == 1) page = PetSettingsPage.MORE },
        onDismiss = { showMenu = false })
    if (showPermission) DaiyuConfirmDialog("允许悬浮显示", "需要在系统设置中允许悬浮窗权限。",
        confirmText = "去设置", destructive = false,
        onConfirm = { showPermission = false; onOpenOverlayPermission() },
        onDismiss = { showPermission = false })

}
