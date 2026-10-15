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
            SettingsNote("备份含聊天隐私，请选择可信位置。备份不包含 API 密钥、Cookie、授权令牌或系统授权。")
            if (busy) SettingsNote("正在处理数据，请稍候…")
            if (!statusText.isNullOrBlank()) SettingsNote(statusText)
            if (!errorText.isNullOrBlank()) SettingsNote(errorText, error = true)
        }
        item(key = "backup_export") {
            SettingsSectionTitle("导出备份")
            SettingsCard {
                SettingsRow("导出精简备份", onClick = if (busy) null else onExportCompact,
                    summary = "纯文本兼容格式 v1 · 上限 16 MiB", leading = {
                        DaiyuIcon(DaiyuIcons.Export, contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary, size = 24.dp)
                    })
                SettingsRow("导出完整备份", onClick = if (busy) null else onExportComplete,
                    summary = "完整中立格式 v1 · 推荐 · 上限 112 MiB", leading = {
                        DaiyuIcon(DaiyuIcons.Backup, contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary, size = 24.dp)
                    })
            }
            SettingsNote("精简备份包含会话、文本、工具结果、协议历史及草稿，不含附件、压缩原文归档、分支、用量或模型配置，不能替代完整备份。完整备份还包含这些数据及非秘密配置。")
        }
        item(key = "backup_import") {
            SettingsSectionTitle("导入备份")
            SettingsCard {
                SettingsRow("导入精简备份", onClick = if (busy) null else onImportCompact, leading = {
                    DaiyuIcon(DaiyuIcons.Import, contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary, size = 24.dp)
                })
                SettingsRow("导入完整备份", onClick = if (busy) null else onImportComplete, leading = {
                    DaiyuIcon(DaiyuIcons.Folder, contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary, size = 24.dp)
                })
            }
            SettingsNote("导入仅追加，不覆盖已有会话；身份冲突将整包回滚。不会自动执行历史模型请求或工具。完整备份中的非秘密配置仅保存为元数据，不会自动替换当前模型设置、密钥或系统授权。")
        }
        item(key = "backup_restore_preview") {
            SettingsSectionTitle("恢复配置预览")
            SettingsCard {
                SettingsRow("预览已导入的模型配置", onClick = if (busy) null else onPreviewRestore, leading = {
                    DaiyuIcon(DaiyuIcons.Settings, contentDescription = null,
                        tint = MiuixTheme.colorScheme.primary, size = 24.dp)
                })
                SettingsNote(restorePreviewText.ifBlank { "请先预览已导入的非秘密配置。" })
                if (canRestoreConfiguration) {
                    SettingsRow("确认恢复配置", onClick = if (busy) null else onRestoreConfiguration, leading = {
                        DaiyuIcon(DaiyuIcons.Reconnect, contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary, size = 24.dp)
                    })
                }
            }
            SettingsNote("恢复需要明确确认，不读取或展示密钥，不启用工具。协议、地址、路径或模型变化后，必须重新输入并保存密钥。")
        }
    }
}
