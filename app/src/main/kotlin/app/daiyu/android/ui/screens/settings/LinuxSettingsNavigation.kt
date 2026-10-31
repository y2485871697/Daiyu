package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import app.daiyu.android.ui.haptics.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import app.daiyu.android.ui.layout.horizontalCutoutPadding
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import app.daiyu.android.terminal.*
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class LinuxSettingsTab { BASIC, FILES }
internal enum class LinuxFileLocation { WORKSPACE, ROOTFS, SHARED }

@Composable
internal fun LinuxSettingsTabs(selected: LinuxSettingsTab, enabled: Boolean = true, onSelect: (LinuxSettingsTab) -> Unit) {
    val colors = MiuixTheme.colorScheme
    Box(Modifier.fillMaxWidth().background(settingsPageBackground()).horizontalCutoutPadding()
        .testTag("linux-bottom-navigation"), contentAlignment = Alignment.Center) {
        Row(Modifier.widthIn(max = 480.dp).fillMaxWidth().selectableGroup()) {
            LinuxSettingsTab.entries.forEach { tab ->
                val active = selected == tab
                val tint = if (active) colors.onPrimary else colors.onPrimaryContainer
                Column(Modifier.weight(1f).testTag("linux-tab:${tab.name.lowercase()}")
                    .selectable(active, enabled = enabled, role = Role.Tab, onClick = { onSelect(tab) })
                    .heightIn(min = 72.dp).padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(width = 64.dp, height = 32.dp).clip(RoundedCornerShape(50))
                        .background(if (active) colors.primary else colors.primaryContainer)
                        .then(if (active) Modifier.testTag("linux-tab-indicator:${tab.name.lowercase()}") else Modifier),
                        contentAlignment = Alignment.Center) {
                        DaiyuIcon(if (tab == LinuxSettingsTab.BASIC) DaiyuIcons.Settings else DaiyuIcons.File,
                            null, tint = tint, size = 24.dp)
                    }
                    Text(if (tab == LinuxSettingsTab.BASIC) "基本" else "文件",
                        style = MiuixTheme.textStyles.body2.copy(fontSize = 13.sp, lineHeight = 16.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal),
                        color = if (active) colors.primary else colors.onPrimaryContainer)
                }
            }
        }
    }
}

@Composable
internal fun LinuxFileLocationTabs(selected: LinuxFileLocation, enabled: Boolean, onSelect: (LinuxFileLocation) -> Unit) {
    SettingsSegmentedTabs(
        labels = listOf("工作区", "Rootfs", "共享文件夹"),
        selectedIndex = selected.ordinal,
        enabled = enabled,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp).testTag("linux-file-location-tabs"),
        tabTag = { "linux-files:${LinuxFileLocation.entries[it].name.lowercase()}" },
        onSelect = { onSelect(LinuxFileLocation.entries[it]) },
    )
}

internal fun linuxBrowsableRootfs(state: LinuxSettingsState, distribution: String, mode: LinuxExecutionMode,
    selected: LinuxGenerationRef?): LinuxInstallation? {
    if (!state.listConfirmed || state.revoked || state.busy || state.pendingRemoval != null || state.pendingApproval != null) return null
    val matching = state.entries.filter { it.distribution.equals(distribution, true) && it.executionMode == mode }
    return if (selected == null) matching.singleOrNull()
        else matching.singleOrNull { LinuxGenerationRef(it.environmentId, it.generation) == selected }
}
