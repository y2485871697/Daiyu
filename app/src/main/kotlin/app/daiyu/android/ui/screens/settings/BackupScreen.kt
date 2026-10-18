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
    onOpenLegacyLibrary: () -> Unit,
    restorePreviewText: String,
    onPreviewRestore: () -> Unit,
    onRestoreConfiguration: () -> Unit,
    modifier: Modifier = Modifier,
    canRestoreConfiguration: Boolean = false,
    busy: Boolean = false,
    statusText: String? = null,
    errorText: String? = null,
    engineeringUi: Boolean = false,
    onImport: () -> Unit = onImportComplete,
) {
    MiuixScaffoldPage(title = "备份与导入", onBack = onBack, modifier = modifier) {
        item(key = "backup_intro") {
            SettingsSectionTitle("备份与恢复")
            SettingsCard {
                SettingsNote("导入备份前请确认文件来源可信；恢复操作可能更改本机数据。")
                if (busy) SettingsNote("处理中")
                if (!statusText.isNullOrBlank()) SettingsNote(statusText)
                if (!errorText.isNullOrBlank()) SettingsNote(errorText, error = true)
            }
        }
        item(key = "backup_actions_title") { SettingsSectionTitle("操作") }
        item(key = "backup_actions") {
            SettingsCard {
                SettingsRow("导出备份", summary = "保存当前数据备份", tag = "backup-export", onClick = if (busy) null else onExportComplete,
                    leading = { SettingsIcon(DaiyuIcons.Export) })
                SettingsRow("导入备份", summary = "从备份文件恢复数据", tag = "backup-import", onClick = if (busy) null else onImport,
                    leading = { SettingsIcon(DaiyuIcons.Import) })
            }
        }
    }
}
