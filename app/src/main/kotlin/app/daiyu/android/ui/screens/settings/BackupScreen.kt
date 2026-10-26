package app.daiyu.android.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
    var includeFullLinuxEnvironment by rememberSaveable { mutableStateOf(false) }
    SettingsScaffoldPage(title = "备份与导入", onBack = onBack, modifier = modifier) {
        item(key = "backup_intro") {
            if (busy) SettingsNote("处理中")
            if (!statusText.isNullOrBlank()) SettingsNote(statusText)
            if (!errorText.isNullOrBlank()) SettingsNote(errorText, error = true)
        }
        item(key = "backup_actions") {
            SettingsCard {
                SettingsSwitchRow("包含完整 Linux 环境", checked = includeFullLinuxEnvironment, enabled = !busy,
                    tag = "backup-include-full-linux") { includeFullLinuxEnvironment = it }
                SettingsRow("导出数据", summary = if (busy) "处理中，请稍候" else "保存当前数据备份", tag = "backup-export", showChevron = true, onClick = if (busy) null else onExportComplete,
                    leading = { SettingsIcon(DaiyuIcons.Export) })
                SettingsRow("导入数据", summary = if (busy) "处理中，请稍候" else "从备份文件恢复数据", tag = "backup-import", showChevron = true, onClick = if (busy) null else onImport,
                    leading = { SettingsIcon(DaiyuIcons.Import) })
            }
        }
    }
}
