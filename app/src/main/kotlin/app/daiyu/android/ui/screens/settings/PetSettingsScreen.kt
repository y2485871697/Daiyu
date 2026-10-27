package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.daiyu.android.assistants.AssistantProfile
import app.daiyu.android.pet.PetApplicationHost
import app.daiyu.android.pet.PetHost
import app.daiyu.android.storage.IndependentModelSettings
import app.daiyu.android.ui.app.DaiyuPalette
import app.daiyu.android.ui.components.DaiyuConfirmDialog
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    var expanded by rememberSaveable(host) { mutableStateOf(false) }
    var showPermission by remember(host) { mutableStateOf(false) }
    val settings = snapshot.pet.state.settings
    val enabled = !snapshot.closed
    val colors = MiuixTheme.colorScheme
    SettingsScaffoldPage(title = "鲸鱼女仆", onBack = onBack,
        modifier = modifier.testTag("pet-settings-view")) {
        item("pet-controls") {
            SettingsCard(Modifier.padding(top = 12.dp)) {
                SettingsRow("鲸鱼女仆", tag = "pet-options-toggle",
                    modifier = Modifier.semantics { stateDescription = if (expanded) "已展开" else "已收起" },
                    onClick = { expanded = !expanded }, trailing = {
                        DaiyuIcon(if (expanded) DaiyuIcons.ChevronDown else DaiyuIcons.ChevronRight,
                            contentDescription = if (expanded) "收起桌宠选项" else "展开桌宠选项",
                            modifier = Modifier.padding(end = 12.dp).size(20.dp).testTag("pet-options-chevron"))
                        SettingsSwitch(checked = settings.enabled, enabled = enabled,
                            modifier = Modifier.testTag("pet-enabled").semantics { contentDescription = "鲸鱼女仆开关" },
                            onCheckedChange = { host.setEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colors.onPrimary,
                                checkedTrackColor = DaiyuPalette.switchTrack,
                                checkedBorderColor = DaiyuPalette.switchTrack,
                                uncheckedThumbColor = colors.onSurfaceVariantSummary,
                                uncheckedTrackColor = colors.surface,
                                uncheckedBorderColor = colors.onSurfaceVariantSummary,
                            ))
                    })
                if (expanded) {
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
        }
    }
    if (showPermission) DaiyuConfirmDialog("允许悬浮显示", "需要在系统设置中允许悬浮窗权限。",
        confirmText = "去设置", destructive = false,
        onConfirm = { showPermission = false; onOpenOverlayPermission() },
        onDismiss = { showPermission = false })
}
