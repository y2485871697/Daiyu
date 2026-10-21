package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.daiyu.android.terminal.*
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class LinuxSettingsTab { BASIC, FILES }
internal enum class LinuxFileLocation { WORKSPACE, ROOTFS, SHARED }

@Composable
internal fun LinuxSettingsTabs(selected: LinuxSettingsTab, onSelect: (LinuxSettingsTab) -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth().background(settingsPageBackground()).navigationBarsPadding()
        .padding(horizontal = 16.dp, vertical = 8.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LinuxSettingsTab.entries.forEach { tab ->
            Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                .background(if (selected == tab) colors.primary.copy(alpha = .12f) else colors.surface)
                .testTag("linux-tab:${tab.name.lowercase()}")
                .selectable(selected == tab, role = Role.Tab, onClick = { onSelect(tab) })
                .heightIn(min = 56.dp).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DaiyuIcon(if (tab == LinuxSettingsTab.BASIC) DaiyuIcons.Settings else DaiyuIcons.Folder,
                    null, tint = if (selected == tab) colors.primary else colors.onSurfaceVariantSummary, size = 21.dp)
                Text(if (tab == LinuxSettingsTab.BASIC) "基本" else "文件", style = MiuixTheme.textStyles.body2,
                    color = if (selected == tab) colors.primary else colors.onSurfaceVariantSummary)
            }
        }
    }
}

@Composable
internal fun LinuxFileLocationTabs(selected: LinuxFileLocation, enabled: Boolean, onSelect: (LinuxFileLocation) -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(colors.surface).selectableGroup()) {
        LinuxFileLocation.entries.forEach { location ->
            Box(Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                .background(if (selected == location) colors.primary.copy(alpha = .12f) else colors.surface)
                .testTag("linux-files:${location.name.lowercase()}")
                .selectable(selected == location, enabled = enabled, role = Role.Tab, onClick = { onSelect(location) })
                .heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                Text(when (location) { LinuxFileLocation.WORKSPACE -> "工作区"; LinuxFileLocation.ROOTFS -> "Rootfs"; LinuxFileLocation.SHARED -> "共享文件夹" },
                    style = MiuixTheme.textStyles.body2, color = if (selected == location) colors.primary else colors.onSurfaceVariantSummary)
            }
        }
    }
}

internal fun linuxBrowsableRootfs(state: LinuxSettingsState, distribution: String, mode: LinuxExecutionMode,
    selected: LinuxGenerationRef?): LinuxInstallation? {
    if (!state.listConfirmed || state.revoked || state.busy || state.pendingRemoval != null || state.pendingApproval != null) return null
    val matching = state.entries.filter { it.distribution.equals(distribution, true) && it.executionMode == mode }
    return if (selected == null) matching.singleOrNull()
        else matching.singleOrNull { LinuxGenerationRef(it.environmentId, it.generation) == selected }
}
