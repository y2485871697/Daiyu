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
import androidx.compose.ui.text.style.TextAlign
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
internal fun LinuxSettingsTabs(selected: LinuxSettingsTab, onSelect: (LinuxSettingsTab) -> Unit) {
    val colors = MiuixTheme.colorScheme
    Box(Modifier.fillMaxWidth().background(settingsPageBackground()).horizontalCutoutPadding().testTag("linux-bottom-navigation"),
        contentAlignment = Alignment.Center) {
        Row(Modifier.widthIn(max = 360.dp).fillMaxWidth().selectableGroup()) {
            LinuxSettingsTab.entries.forEach { tab ->
                val active = selected == tab
                val tint = if (active) colors.primary else colors.onSurfaceVariantSummary
                Column(Modifier.weight(1f)
                    .testTag("linux-tab:${tab.name.lowercase()}")
                    .selectable(active, role = Role.Tab, onClick = { onSelect(tab) })
                    .heightIn(min = 64.dp).padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DaiyuIcon(if (tab == LinuxSettingsTab.BASIC) DaiyuIcons.Settings else DaiyuIcons.Folder,
                        null, tint = tint, size = 22.dp)
                    Text(if (tab == LinuxSettingsTab.BASIC) "基本" else "文件",
                        style = MiuixTheme.textStyles.body2.copy(fontSize = 13.sp, lineHeight = 16.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal), color = tint)
                    Box(Modifier.size(width = 20.dp, height = 3.dp).clip(RoundedCornerShape(2.dp))
                        .then(if (active) Modifier.background(colors.primary)
                            .testTag("linux-tab-indicator:${tab.name.lowercase()}") else Modifier))
                }
            }
        }
    }
}

@Composable
internal fun LinuxFileLocationTabs(selected: LinuxFileLocation, enabled: Boolean, onSelect: (LinuxFileLocation) -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().selectableGroup()
        .testTag("linux-file-location-tabs")) {
        LinuxFileLocation.entries.forEach { location ->
            val active = selected == location
            Box(Modifier.weight(1f)
                .testTag("linux-files:${location.name.lowercase()}")
                .selectable(active, enabled = enabled, role = Role.Tab, onClick = { onSelect(location) })
                .heightIn(min = 52.dp), contentAlignment = Alignment.Center) {
                Text(when (location) { LinuxFileLocation.WORKSPACE -> "工作区"; LinuxFileLocation.ROOTFS -> "Rootfs"; LinuxFileLocation.SHARED -> "共享文件夹" },
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 14.dp),
                    style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp, lineHeight = 20.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal),
                    maxLines = 2, textAlign = TextAlign.Center,
                    color = (if (active) colors.primary else colors.onSurfaceVariantSummary)
                        .copy(alpha = if (enabled) 1f else .45f))
                if (active) Box(Modifier.align(Alignment.BottomCenter).size(width = 24.dp, height = 3.dp)
                    .clip(RoundedCornerShape(2.dp)).background(colors.primary)
                    .testTag("linux-files-indicator:${location.name.lowercase()}"))
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
