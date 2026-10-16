package app.daiyu.android.ui.screens.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.daiyu.android.R
import app.daiyu.android.domain.delegation.ChildTaskRecord
import app.daiyu.android.domain.delegation.ChildTaskStatus
import app.daiyu.android.ui.components.MiuixScaffoldPage
import app.daiyu.android.ui.screens.settings.SettingsCard
import app.daiyu.android.ui.screens.settings.SettingsNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ChildTaskRecordsScreen(
    conversationId: String,
    load: suspend (String) -> List<ChildTaskRecord>,
    onBack: () -> Unit,
    onStop: ((ChildTaskRecord) -> app.daiyu.android.domain.delegation.ChildTaskStop)? = null,
) {
    var records by remember(conversationId) { mutableStateOf<List<ChildTaskRecord>?>(null) }
    var failed by remember(conversationId) { mutableStateOf(false) }
    var refresh by remember(conversationId) { mutableStateOf(0) }
    LaunchedEffect(conversationId, refresh) {
        records = null
        failed = false
        try {
            val loaded = withContext(Dispatchers.IO) { load(conversationId) }
            check(loaded.all { it.owner.conversationId == conversationId })
            records = loaded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    val visibleRecords = records
    MiuixScaffoldPage(
        title = stringResource(R.string.business_task_records),
        onBack = onBack,
        actions = {
            TextButton(stringResource(R.string.business_task_refresh), onClick = { refresh++ },
                enabled = records != null || failed, modifier = Modifier.testTag("child-tasks-refresh"))
        },
    ) {
        item(key = "scope") { SettingsNote(stringResource(R.string.business_task_scope)) }
        when {
            failed -> item(key = "error") {
                SettingsNote(stringResource(R.string.business_task_load_failed), error = true)
            }
            visibleRecords == null -> item(key = "loading") { SettingsNote(stringResource(R.string.business_task_loading)) }
            visibleRecords.isEmpty() -> item(key = "empty") { SettingsNote(stringResource(R.string.business_task_empty)) }
            else -> items(visibleRecords, key = { it.owner.childRunId }) { record ->
                TaskRecordCard(record, onStop?.let { stop -> { stop(record); refresh++ } })
            }
        }
    }
}

@Composable
private fun TaskRecordCard(record: ChildTaskRecord, onStop: (() -> Unit)?) {
    var expanded by remember(record.owner.childRunId) { mutableStateOf(false) }
    val status = when (record.status) {
        ChildTaskStatus.QUEUED -> R.string.business_task_queued
        ChildTaskStatus.RUNNING -> R.string.business_task_running
        ChildTaskStatus.COMPLETED -> R.string.business_task_completed
        ChildTaskStatus.NOT_EXECUTED -> R.string.business_task_not_executed
        ChildTaskStatus.UNKNOWN -> R.string.business_task_unknown
    }
    SettingsCard(Modifier.padding(bottom = 12.dp).testTag("child-task:${record.owner.childRunId}")) {
        Column(Modifier.padding(16.dp)) {
            Text(record.profileId, style = MiuixTheme.textStyles.body1)
            Text(stringResource(status), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(stringResource(R.string.business_task_parent, record.owner.parentRunId),
                style = MiuixTheme.textStyles.footnote1)
            SelectionContainer {
                Column {
                    Text(stringResource(R.string.business_task_prompt), modifier = Modifier.padding(top = 12.dp))
                    Text(record.prompt, maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                    if (record.text.isNotEmpty()) {
                        Text(stringResource(R.string.business_task_result), modifier = Modifier.padding(top = 12.dp))
                        Text(record.text, maxLines = if (expanded) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis)
                    }
                    record.detail?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
                }
            }
            if (record.status == ChildTaskStatus.UNKNOWN) {
                Text(stringResource(R.string.business_task_unknown_note), modifier = Modifier.padding(top = 8.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            if (onStop != null && record.status in setOf(ChildTaskStatus.QUEUED, ChildTaskStatus.RUNNING)) {
                TextButton(stringResource(R.string.business_task_stop), onClick = onStop,
                    modifier = Modifier.testTag("child-task-stop:${record.owner.childRunId}"))
            }
            TextButton(stringResource(if (expanded) R.string.business_task_collapse else R.string.business_task_expand),
                onClick = { expanded = !expanded }, modifier = Modifier.testTag("child-task-expand:${record.owner.childRunId}"))
        }
    }
}
