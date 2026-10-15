package app.daiyu.android.ui.screens.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.FeatureCatalog
import app.daiyu.android.ui.FeatureEntry
import app.daiyu.android.ui.FeatureGroup
import app.daiyu.android.ui.FeatureRoute
import app.daiyu.android.ui.components.MiuixScaffoldPage
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Stable permission IDs: accessibility, root, notification_access, usage_access. */
internal data class PermissionStateUi(
    val id: String,
    val statusText: String = "未检查",
    val granted: Boolean = false,
)

private val permissionTitles = linkedMapOf(
    "accessibility" to "无障碍",
    "root" to "Root",
    "notification_access" to "通知访问",
    "usage_access" to "使用情况访问",
)

/** Explicit mapping: adding a catalog entry must also choose its settings icon. */
internal val settingsEntryIcons = mapOf(
    "P01" to DaiyuIcons.Assistant,
    "P07" to DaiyuIcons.Menu,
    "P22" to DaiyuIcons.Folder,
    "P27" to DaiyuIcons.Search,
    "P28" to DaiyuIcons.Search,
    "P28.folders" to DaiyuIcons.Folder,
    "R03" to DaiyuIcons.Model,
    "P02" to DaiyuIcons.Lightning,
    "P03" to DaiyuIcons.Gateway,
    "P04" to DaiyuIcons.Context,
    "P05" to DaiyuIcons.Balance,
    "P06" to DaiyuIcons.Provider,
    "P08" to DaiyuIcons.Title,
    "P25" to DaiyuIcons.Vision,
    "P26" to DaiyuIcons.Reconnect,
    "P11" to DaiyuIcons.Send,
    "P12" to DaiyuIcons.Pause,
    "P13" to DaiyuIcons.Stop,
    "P14" to DaiyuIcons.Steer,
    "P17" to DaiyuIcons.Image,
    "P18" to DaiyuIcons.Video,
    "P19" to DaiyuIcons.Paint,
    "P20" to DaiyuIcons.Movie,
    "P09" to DaiyuIcons.Compress,
    "P10" to DaiyuIcons.Context,
    "P21" to DaiyuIcons.Chart,
    "P39" to DaiyuIcons.Shield,
    "P42" to DaiyuIcons.Compress,
    "P43" to DaiyuIcons.Info,
    "P15" to DaiyuIcons.SubAgent,
    "P16" to DaiyuIcons.SubAgent,
    "P23" to DaiyuIcons.Interactive,
    "P32" to DaiyuIcons.Voice,
    "P32.dictation" to DaiyuIcons.Voice,
    "P38" to DaiyuIcons.Voice,
    "P38.files" to DaiyuIcons.File,
    "P33" to DaiyuIcons.Skills,
    "P34" to DaiyuIcons.Browser,
    "R06" to DaiyuIcons.Device,
    "R07" to DaiyuIcons.Accessibility,
    "R08" to DaiyuIcons.VirtualScreen,
    "R09" to DaiyuIcons.Terminal,
    "R12" to DaiyuIcons.Backup,
    "P24" to DaiyuIcons.Pet,
    "P29" to DaiyuIcons.AppIcon,
    "P30" to DaiyuIcons.Palette,
    "P31" to DaiyuIcons.Vibration,
    "P35" to DaiyuIcons.Timestamp,
    "P36" to DaiyuIcons.Refresh,
    "P37" to DaiyuIcons.Tool,
    "P40" to DaiyuIcons.Refresh,
    "P41" to DaiyuIcons.Locate,
)

internal fun filterSettingsGroups(query: String, groups: List<FeatureGroup> = FeatureCatalog.groups): List<FeatureGroup> {
    val term = query.trim()
    if (term.isEmpty()) return groups
    return groups.mapNotNull { group ->
        val entries = group.entries.filter { it.title.contains(term, ignoreCase = true) }
        group.copy(entries = entries).takeIf { entries.isNotEmpty() }
    }
}

@Composable
internal fun DaiyuSettingsScreen(
    onBack: () -> Unit,
    onOpenEntry: (FeatureEntry) -> Unit,
    onRoute: (FeatureRoute) -> Unit,
    modifier: Modifier = Modifier,
    permissionStates: List<PermissionStateUi> = emptyList(),
    onPermissionClick: (String) -> Unit = {},
    onOpenSubAgents: (() -> Unit)? = null,
    onOpenAssistants: (() -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    val groups = remember(query) { filterSettingsGroups(query) }
    val permissions = permissionStates.associateBy { it.id }
    MiuixScaffoldPage(title = "设置", onBack = onBack, modifier = modifier) {
        item(key = "settings_search") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                DaiyuIcon(DaiyuIcons.Search, contentDescription = null,
                    modifier = Modifier.padding(horizontal = 12.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, size = 24.dp)
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "搜索设置",
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item(key = "settings_permissions") {
            SettingsSectionTitle("能力与权限")
            SettingsCard {
                permissionTitles.forEach { (id, title) ->
                    val state = permissions[id] ?: PermissionStateUi(id)
                    val icon = when (id) {
                        "accessibility" -> DaiyuIcons.Accessibility
                        "root" -> DaiyuIcons.Shield
                        "notification_access" -> DaiyuIcons.Bell
                        else -> DaiyuIcons.Chart
                    }
                    SettingsRow(title = title, onClick = { onPermissionClick(id) }, leading = {
                        DaiyuIcon(icon, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, size = 24.dp)
                    }, trailing = {
                        Text(state.statusText, style = MiuixTheme.textStyles.body2,
                            color = if (state.granted) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        DaiyuIcon(DaiyuIcons.ChevronRight, contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, size = 20.dp)
                    })
                }
            }
        }
        if (groups.isEmpty()) {
            item(key = "settings_empty") { SettingsNote("没有匹配的设置") }
        }
        groups.forEach { group ->
            item(key = "settings_group_${group.id}") {
                SettingsSectionTitle(group.title)
                SettingsCard {
                    group.entries.forEach { entry ->
                        SettingsRow(title = entry.title, onClick = {
                            val route = entry.route
                            when {
                                entry.id == "P01" && onOpenAssistants != null -> onOpenAssistants()
                                (entry.id == "P15" || entry.id == "P16") && onOpenSubAgents != null -> onOpenSubAgents()
                                route != null -> onRoute(route)
                                else -> onOpenEntry(entry)
                            }
                        }, leading = {
                            DaiyuIcon(settingsEntryIcons.getValue(entry.id), contentDescription = null,
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, size = 24.dp)
                        })
                    }
                }
            }
        }
    }
}
