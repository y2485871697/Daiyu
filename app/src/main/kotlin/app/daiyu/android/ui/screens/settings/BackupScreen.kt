package app.daiyu.android.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.daiyu.android.ui.icons.DaiyuIcons

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
    SettingsScaffoldPage(title = "备份与导入", onBack = onBack, modifier = modifier) {
        item(key = "backup_intro") {
            SettingsNote("备份文件可能包含个人数据，请妥善保管。导入前请确认来源可信。")
            if (busy) SettingsNote("处理中")
            if (!statusText.isNullOrBlank()) SettingsNote(statusText)
            if (!errorText.isNullOrBlank()) SettingsNote(errorText, error = true)
        }
        item(key = "backup_actions") {
            SettingsCard {
                SettingsRow("导出数据", summary = if (busy) "处理中，请稍候" else "保存当前数据备份", tag = "backup-export", onClick = if (busy) null else onExportComplete,
                    leading = { SettingsIcon(DaiyuIcons.Export) })
                SettingsRow("导入数据", summary = if (busy) "处理中，请稍候" else "从备份文件恢复数据", tag = "backup-import", onClick = if (busy) null else onImport,
                    leading = { SettingsIcon(DaiyuIcons.Import) })
            }
        }
    }
}
