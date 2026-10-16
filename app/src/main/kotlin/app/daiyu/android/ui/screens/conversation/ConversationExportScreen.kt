package app.daiyu.android.ui.screens.conversation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.daiyu.android.R
import app.daiyu.android.ui.components.MiuixScaffoldPage
import app.daiyu.android.ui.screens.settings.SettingsCard
import app.daiyu.android.ui.screens.settings.SettingsNote
import app.daiyu.android.ui.screens.settings.SettingsRow
import top.yukonga.miuix.kmp.basic.TextButton

@Composable
internal fun ConversationExportScreen(
    conversationTitle: String,
    available: Boolean,
    pending: Boolean,
    busy: Boolean,
    onExport: () -> Unit,
    onBack: () -> Unit,
) {
    MiuixScaffoldPage(stringResource(R.string.business_export_title), onBack) {
        item(key = "target") {
            SettingsCard {
                SettingsRow(stringResource(R.string.business_export_conversation), summary = conversationTitle)
                SettingsNote(stringResource(R.string.business_export_scope))
                SettingsNote(stringResource(R.string.business_export_excluded))
            }
        }
        item(key = "export") {
            TextButton(
                text = stringResource(R.string.business_export_choose_file),
                onClick = onExport,
                enabled = available && !pending && !busy,
                modifier = Modifier.testTag("conversation-export-choose"),
            )
            when {
                busy -> SettingsNote(stringResource(R.string.business_export_writing))
                pending -> SettingsNote(stringResource(R.string.business_export_waiting))
                !available -> SettingsNote(stringResource(R.string.business_export_unavailable))
            }
        }
    }
}
