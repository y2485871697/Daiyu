package app.daiyu.android.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.daiyu.android.ui.components.MiuixScaffoldPage
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The host owns document pickers, import confirmation, restore authorization and operation state. */
@Composable
internal fun BackupScreen(
    onBack: () -> Unit,
    onExportCompact: () -> Unit,
    onExportComplete: () -> Unit,
    onImportCompact: () -> Unit,
    onImportComplete: () -> Unit,
    restorePreviewText: String,
    onPreviewRestore: () -> Unit,
    onRestoreConfiguration: () -> Unit,
    modifier: Modifier = Modifier,
    canRestoreConfiguration: Boolean = false,
    busy: Boolean = false,
    statusText: String? = null,
    errorText: String? = null,
) {
    MiuixScaffoldPage(title = "备份与导入", onBack = onBack, modifier = modifier) {
        item(key = "backup_intro") {
            if (busy) SettingsNote("处理中")
            if (!statusText.isNullOrBlank()) SettingsNote(statusText)
            if (!errorText.isNullOrBlank()) SettingsNote(errorText, error = true)
        }
        item(key = "backup_export") {
            SettingsSectionTitle("导出备份")
            SettingsCard {
                SettingsRow("导出精简备份", onClick = if (busy) null else onExportCompact, leading = {
                        DaiyuIcon(DaiyuIcons.Export, contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, size = 24.dp)
                    })
                SettingsRow("导出完整备份", onClick = if (busy) null else onExportComplete, leading = {
                        DaiyuIcon(DaiyuIcons.Backup, contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, size = 24.dp)
                    })
            }
        }
        item(key = "backup_import") {
            SettingsSectionTitle("导入备份")
            SettingsCard {
                SettingsRow("导入精简备份", onClick = if (busy) null else onImportCompact, leading = {
                    DaiyuIcon(DaiyuIcons.Import, contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, size = 24.dp)
                })
                SettingsRow("导入完整备份", onClick = if (busy) null else onImportComplete, leading = {
                    DaiyuIcon(DaiyuIcons.Folder, contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, size = 24.dp)
                })
            }
        }
        item(key = "backup_restore_preview") {
            SettingsSectionTitle("恢复配置")
            SettingsCard {
                SettingsRow("预览模型配置", onClick = if (busy) null else onPreviewRestore, leading = {
                    DaiyuIcon(DaiyuIcons.Settings, contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, size = 24.dp)
                })
                SettingsNote(restorePreviewText.ifBlank { "暂无配置" })
                if (canRestoreConfiguration) {
                    SettingsRow("恢复配置", onClick = if (busy) null else onRestoreConfiguration, leading = {
                        DaiyuIcon(DaiyuIcons.Reconnect, contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, size = 24.dp)
                    })
                }
            }
        }
    }
}
