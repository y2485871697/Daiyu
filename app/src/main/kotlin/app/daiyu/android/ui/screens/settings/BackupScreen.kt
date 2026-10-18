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
            if (busy) SettingsNote("处理中")
            if (!statusText.isNullOrBlank()) SettingsNote(statusText)
            if (!errorText.isNullOrBlank()) SettingsNote(errorText, error = true)
        }
        item(key = "backup_actions") {
            SettingsCard {
                SettingsRow("导出备份", tag = "backup-export", onClick = if (busy) null else onExportComplete,
                    leading = { SettingsIcon(DaiyuIcons.Export) })
                SettingsRow("导入备份", tag = "backup-import", onClick = if (busy) null else onImport,
                    leading = { SettingsIcon(DaiyuIcons.Import) })
            }
        }
    }
}
