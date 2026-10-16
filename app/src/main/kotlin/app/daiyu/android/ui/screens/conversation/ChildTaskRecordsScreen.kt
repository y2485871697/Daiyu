package app.daiyu.android.ui.screens.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.daiyu.android.R
import app.daiyu.android.domain.delegation.ChildTaskControlReceipt
import app.daiyu.android.domain.delegation.ChildTaskPauseState
import app.daiyu.android.domain.delegation.ChildTaskRecord
import app.daiyu.android.domain.delegation.ChildTaskStatus
import app.daiyu.android.domain.delegation.ChildTaskStop
import app.daiyu.android.domain.delegation.RetainedChildTask
import app.daiyu.android.ui.components.MiuixScaffoldPage
import app.daiyu.android.ui.screens.settings.SettingsCard
import app.daiyu.android.ui.screens.settings.SettingsNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private class TaskRecordsPageState {
    var records by mutableStateOf<List<ChildTaskRecord>?>(null)
    var live by mutableStateOf<List<RetainedChildTask>>(emptyList())
    var failed by mutableStateOf(false)
    var liveFailed by mutableStateOf(false)
    var loading by mutableStateOf(true)
    var refresh by mutableStateOf(0)
    var feedback by mutableStateOf<Int?>(null)
    var active = true
}

/** A durable row alone is never permission to continue, even if it once ran in this process. */
internal fun controllableChildTask(record: ChildTaskRecord, live: List<RetainedChildTask>): RetainedChildTask? {
    if (record.status !in setOf(ChildTaskStatus.QUEUED, ChildTaskStatus.RUNNING)) return null
    return live.singleOrNull { it.record.owner == record.owner }?.takeIf {
        !it.settled && !it.stopRequested && !it.journalUnconfirmed &&
            it.record.status in setOf(ChildTaskStatus.QUEUED, ChildTaskStatus.RUNNING)
    }
}

internal fun childTaskControlMessage(receipt: ChildTaskControlReceipt, retractingPause: Boolean = false): Int =
    when (receipt) {
        ChildTaskControlReceipt.PENDING -> R.string.business_task_pause_requested
        ChildTaskControlReceipt.PAUSED -> R.string.business_task_pause_confirmed
        ChildTaskControlReceipt.RESUMED -> if (retractingPause) R.string.business_task_pause_retracted
            else R.string.business_task_resumed
        ChildTaskControlReceipt.STALE -> R.string.business_task_control_stale
    }

@Composable
internal fun ChildTaskRecordsScreen(
    conversationId: String,
    load: suspend (String) -> List<ChildTaskRecord>,
    onBack: () -> Unit,
    onStop: ((ChildTaskRecord) -> ChildTaskStop)? = null,
    loadLive: (suspend (String) -> List<RetainedChildTask>)? = null,
    onPause: ((ChildTaskRecord) -> ChildTaskControlReceipt)? = null,
    onResume: ((ChildTaskRecord) -> ChildTaskControlReceipt)? = null,
) {
    // Each visit has separate storage. Cancelled loads / retained old click callbacks must not
    // publish to (or operate on behalf of) a different conversation or a later visit.
    val page = remember(conversationId) { TaskRecordsPageState() }
    val currentPage by rememberUpdatedState(page)
    val currentLoad by rememberUpdatedState(load)
    val currentLoadLive by rememberUpdatedState(loadLive)
    DisposableEffect(page) { onDispose { page.active = false } }
    LaunchedEffect(page, page.refresh) {
        val revision = page.refresh
        val recordsLoader = currentLoad
        val liveLoader = currentLoadLive
        page.loading = true
        page.failed = false
        page.liveFailed = false
        page.live = emptyList()
        try {
            val loaded = withContext(Dispatchers.IO) { recordsLoader(conversationId) }
            check(loaded.all { it.owner.conversationId == conversationId })
            var liveFailed = false
            val snapshots = try {
                if (liveLoader == null) emptyList() else withContext(Dispatchers.IO) {
                    liveLoader(conversationId).also { snapshots ->
                        check(snapshots.all { it.record.owner.conversationId == conversationId })
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                liveFailed = true
                emptyList()
            }
            if (page.active && currentPage === page && page.refresh == revision) {
                page.records = loaded
                page.live = snapshots
                page.liveFailed = liveFailed
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (page.active && currentPage === page && page.refresh == revision) page.failed = true
        } finally {
            if (page.active && currentPage === page && page.refresh == revision) page.loading = false
        }
    }
    val control: (() -> Int) -> Unit = { action ->
        if (page.active && currentPage === page && !page.loading) {
            val message = try { action() } catch (_: Exception) { R.string.business_task_control_failed }
            if (page.active && currentPage === page) {
                page.feedback = message
                // Re-read observations, never turn a receipt into a persisted status or dispatch.
                page.live = emptyList()
                page.loading = true
                page.refresh++
            }
        }
    }
    val visibleRecords = page.records
    MiuixScaffoldPage(
        title = stringResource(R.string.business_task_records),
        onBack = onBack,
        actions = {
            TextButton(stringResource(R.string.business_task_refresh), onClick = {
                if (page.active && currentPage === page && !page.loading) {
                    page.loading = true
                    page.live = emptyList()
                    page.refresh++
                }
            }, enabled = !page.loading, modifier = Modifier.testTag("child-tasks-refresh"))
        },
    ) {
        item(key = "scope") { SettingsNote(stringResource(R.string.business_task_scope)) }
        if (loadLive != null) item(key = "live-scope") { SettingsNote(stringResource(R.string.business_task_live_scope)) }
        page.feedback?.let { message -> item(key = "control-feedback") {
            SettingsNote(stringResource(message))
        } }
        if (page.liveFailed) item(key = "live-error") {
            SettingsNote(stringResource(R.string.business_task_live_load_failed), error = true)
        }
        when {
            page.failed -> item(key = "error") {
                SettingsNote(stringResource(R.string.business_task_load_failed), error = true)
            }
            visibleRecords == null -> item(key = "loading") { SettingsNote(stringResource(R.string.business_task_loading)) }
            visibleRecords.isEmpty() -> item(key = "empty") { SettingsNote(stringResource(R.string.business_task_empty)) }
            else -> items(visibleRecords, key = { it.owner.toString() }) { record ->
                val live = controllableChildTask(record, page.live)
                TaskRecordCard(record, live, !page.loading,
                    onStop = onStop?.let { stop -> { control {
                        when (stop(record)) {
                            ChildTaskStop.REQUESTED -> R.string.business_task_stop_requested
                            ChildTaskStop.ALREADY_SETTLED, ChildTaskStop.NOT_FOUND -> R.string.business_task_control_stale
                        }
                    } } },
                    onPause = onPause?.let { pause -> { control { childTaskControlMessage(pause(record)) } } },
                    onResume = onResume?.let { resume -> { control {
                        childTaskControlMessage(resume(record), live?.pauseState == ChildTaskPauseState.PENDING)
                    } } },
                )
            }
        }
    }
}

@Composable
private fun TaskRecordCard(record: ChildTaskRecord, live: RetainedChildTask?, enabled: Boolean,
    onStop: (() -> Unit)?, onPause: (() -> Unit)?, onResume: (() -> Unit)?) {
    var expanded by remember(record.owner) { mutableStateOf(false) }
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
            when (live?.pauseState) {
                ChildTaskPauseState.PENDING -> Text(stringResource(R.string.business_task_pause_pending))
                ChildTaskPauseState.PAUSED -> Text(stringResource(R.string.business_task_paused))
                else -> Unit
            }
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
            if (live != null) {
                if (onPause != null) TextButton(stringResource(R.string.business_task_pause), onClick = onPause,
                    enabled = enabled && live.pauseState == ChildTaskPauseState.ACTIVE,
                    modifier = Modifier.testTag("child-task-pause:${record.owner.childRunId}"))
                if (onResume != null) TextButton(stringResource(if (live.pauseState == ChildTaskPauseState.PENDING)
                    R.string.business_task_retract_pause else R.string.business_task_resume), onClick = onResume,
                    enabled = enabled && live.pauseState != ChildTaskPauseState.ACTIVE,
                    modifier = Modifier.testTag("child-task-resume:${record.owner.childRunId}"))
            }
            if (onStop != null && record.status in setOf(ChildTaskStatus.QUEUED, ChildTaskStatus.RUNNING)) {
                TextButton(stringResource(R.string.business_task_stop), onClick = onStop, enabled = enabled,
                    modifier = Modifier.testTag("child-task-stop:${record.owner.childRunId}"))
            }
            TextButton(stringResource(if (expanded) R.string.business_task_collapse else R.string.business_task_expand),
                onClick = { expanded = !expanded }, modifier = Modifier.testTag("child-task-expand:${record.owner.childRunId}"))
        }
    }
}
