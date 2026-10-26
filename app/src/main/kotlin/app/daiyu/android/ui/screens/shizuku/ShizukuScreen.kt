package app.daiyu.android.ui.screens.shizuku

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.R
import app.daiyu.android.domain.tools.ExecutionReceipt
import app.daiyu.android.shizuku.*
import app.daiyu.android.ui.components.EtaFormTextField
import app.daiyu.android.ui.components.MiuixScaffoldPage
import app.daiyu.android.ui.screens.settings.SettingsCard
import app.daiyu.android.ui.screens.settings.SettingsNote
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import app.daiyu.android.ui.haptics.miuix.TextButton

/**
 * The host retains session with its reserved run, and accepts onReady only for that exact run.
 * Opening this page only refreshes service state. It never prompts, connects or executes by itself.
 * Back navigation does not own the run: the host must close abandoned/pending runs explicitly.
 */
@Composable
internal fun ShizukuScreen(
    authorization: ShizukuAuthorization,
    session: ShizukuRunSession?,
    onBack: () -> Unit,
    onReady: (ShizukuRunSession, RunShizukuCommands) -> Unit
) {
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var service by remember(authorization) { mutableStateOf(authorization.state) }
    var busy by remember(authorization, session) { mutableStateOf(false) }
    var error by remember(authorization, session) { mutableStateOf<Int?>(null) }
    var refresh by remember(authorization) { mutableIntStateOf(0) }
    val currentReady by rememberUpdatedState(onReady)
    val currentSession by rememberUpdatedState(session)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(authorization, session, refresh) {
        service = withContext(Dispatchers.IO) { authorization.refresh() }
        // Refresh never regrants a revoked approval or reconnects a run.
        val observedRun = session?.state
        if (observedRun != null &&
            observedRun.status in setOf(ShizukuApprovalStatus.OPENING, ShizukuApprovalStatus.READY) &&
            (service.availability != ShizukuAvailability.AUTHORIZED || service.uid != observedRun.selectedUid ||
                (observedRun.status == ShizukuApprovalStatus.READY && !authorization.state.connected))) {
            session?.revoke()
        }
    }
    val runState = if (session != null) session.states.collectAsState().value else null
    fun requestPermission() {
        if (busy || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        busy = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { authorization.requestPermission() }
                refresh++ // Dialog result is refreshed again on resume or explicit Refresh.
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = R.string.shizuku_failed }
            finally { busy = false }
        }
    }
    fun open() {
        val run = session ?: return
        if (busy || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        busy = true
        error = null
        scope.launch {
            try {
                val commands = withContext(Dispatchers.IO) { run.open(authorization) }
                if (currentSession === run && run.state.status == ShizukuApprovalStatus.READY) {
                    currentReady(run, commands)
                } else {
                    run.close() // A late connection must never be handed to a different run/page.
                }
                refresh++
            } catch (cancelled: CancellationException) {
                run.close()
                throw cancelled
            } catch (_: Exception) {
                run.close()
                error = R.string.shizuku_failed
            } finally { busy = false }
        }
    }
    MiuixScaffoldPage(
        title = stringResource(R.string.shizuku_title), onBack = onBack, compactTitle = session == null,
        actions = {
            TextButton(stringResource(R.string.shizuku_refresh), onClick = { refresh++ },
                enabled = !busy, modifier = Modifier.testTag("shizuku-refresh"))
        }
    ) {
        item("service") {
            SettingsCard(Modifier.padding(bottom = 12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(serviceText(service))
                    if (service.availability == ShizukuAvailability.PERMISSION_REQUIRED) {
                        TextButton(stringResource(R.string.shizuku_request_permission), onClick = ::requestPermission,
                            enabled = !busy, modifier = Modifier.testTag("shizuku-permission"))
                    }
                }
            }
        }
        error?.let { message -> item("error") { SettingsNote(stringResource(message), error = true) } }
        if (runState == null) {
            item("no-run") { SettingsNote(stringResource(R.string.shizuku_no_run)) }
        } else {
            item("approval") {
                ShizukuApprovalContent(runState, busy,
                    canOpen = service.availability == ShizukuAvailability.AUTHORIZED,
                    onEdit = { uid, script, timeout ->
                        try { session?.edit(uid, script, timeout); error = null }
                        catch (_: Exception) { error = R.string.shizuku_invalid_approval }
                    },
                    onApprove = {
                        try { session?.approve(runState); error = null }
                        catch (_: Exception) { error = R.string.shizuku_invalid_approval }
                    },
                    onOpen = ::open,
                    onRevoke = { session?.revoke() })
            }
        }
    }
}

@Composable
private fun serviceText(service: ShizukuAuthorizationState): String = when (service.availability) {
    ShizukuAvailability.UNAVAILABLE -> stringResource(R.string.shizuku_unavailable)
    ShizukuAvailability.UNSUPPORTED -> stringResource(R.string.shizuku_unsupported)
    ShizukuAvailability.PERMISSION_REQUIRED -> stringResource(R.string.shizuku_permission_required)
    ShizukuAvailability.AUTHORIZED -> stringResource(R.string.shizuku_authorized, service.uid ?: -1)
    ShizukuAvailability.CLOSED -> stringResource(R.string.shizuku_closed)
}

/** Pure rendering seam; no SDK, journal or command I/O during composition. */
@Composable
internal fun ShizukuApprovalContent(
    state: ShizukuRunState,
    busy: Boolean,
    canOpen: Boolean,
    onEdit: (Int?, String, Long) -> Unit,
    onApprove: () -> Unit,
    onOpen: () -> Unit,
    onRevoke: () -> Unit
) {
    val editable = !busy && state.status in setOf(ShizukuApprovalStatus.DRAFT, ShizukuApprovalStatus.APPROVED)
    var timeout by remember(state.sessionId, state.runId) { mutableStateOf(state.timeoutMillis.toString()) }
    LaunchedEffect(state.timeoutMillis) {
        if ((timeout.toLongOrNull() ?: 0) != state.timeoutMillis) timeout = state.timeoutMillis.toString()
    }
    SettingsCard(Modifier.padding(bottom = 12.dp).testTag("shizuku-approval")) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.shizuku_run_scope, state.sessionId, state.runId))
            Text(stringResource(R.string.shizuku_identity_required), Modifier.padding(top = 12.dp))
            TextButton(stringResource(R.string.shizuku_shell),
                onClick = { onEdit(2000, state.script, timeout.toLongOrNull() ?: 0) }, enabled = editable,
                modifier = Modifier.testTag("shizuku-select-shell"))
            TextButton(stringResource(R.string.shizuku_root),
                onClick = { onEdit(0, state.script, timeout.toLongOrNull() ?: 0) }, enabled = editable,
                modifier = Modifier.testTag("shizuku-select-root"))
            state.selectedUid?.let { Text(stringResource(R.string.shizuku_selected, it)) }
            EtaFormTextField(state.script,
                onValueChange = { onEdit(state.selectedUid, it, timeout.toLongOrNull() ?: 0) },
                hint = stringResource(R.string.shizuku_script), enabled = editable, minLines = 3,
                modifier = Modifier.padding(top = 12.dp).testTag("shizuku-script"))
            EtaFormTextField(timeout, onValueChange = {
                timeout = it
                // Even an invalid/empty edit revokes the old approval immediately.
                onEdit(state.selectedUid, state.script, it.toLongOrNull() ?: 0)
            }, hint = stringResource(R.string.shizuku_timeout), enabled = editable, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.padding(top = 8.dp).testTag("shizuku-timeout"))
            Text(stringResource(R.string.shizuku_scope_warning), Modifier.padding(top = 12.dp))
            Text(stringResource(when (state.status) {
                ShizukuApprovalStatus.DRAFT -> R.string.shizuku_draft
                ShizukuApprovalStatus.APPROVED -> R.string.shizuku_approved
                ShizukuApprovalStatus.OPENING -> R.string.shizuku_opening
                ShizukuApprovalStatus.READY -> R.string.shizuku_ready
                ShizukuApprovalStatus.REVOKED -> R.string.shizuku_revoked
                ShizukuApprovalStatus.CLOSED -> R.string.shizuku_run_closed
            }), Modifier.padding(top = 12.dp).testTag("shizuku-approval-status"))
            if (editable) TextButton(stringResource(R.string.shizuku_approve), onClick = onApprove,
                enabled = state.selectedUid != null && state.script.isNotBlank() && state.timeoutMillis in 1..120_000 &&
                    timeout.toLongOrNull() == state.timeoutMillis,
                modifier = Modifier.testTag("shizuku-approve"))
            if (state.status == ShizukuApprovalStatus.APPROVED) {
                TextButton(stringResource(R.string.shizuku_open), onClick = onOpen, enabled = !busy && canOpen,
                    modifier = Modifier.testTag("shizuku-open"))
            }
            if (state.status !in setOf(ShizukuApprovalStatus.REVOKED, ShizukuApprovalStatus.CLOSED)) {
                // Keep revoke enabled while a worker is connecting.
                TextButton(stringResource(R.string.shizuku_revoke), onClick = onRevoke,
                    modifier = Modifier.testTag("shizuku-revoke"))
            }
            state.identity?.let { Text(stringResource(R.string.shizuku_observed, it.kind, it.uid)) }
            when (val receipt = state.receipt) {
                is ExecutionReceipt.NotExecuted -> Text(stringResource(R.string.shizuku_not_executed, receipt.code))
                is ExecutionReceipt.Unknown -> Text(stringResource(R.string.shizuku_unknown, receipt.code))
                is ExecutionReceipt.Confirmed -> Text(stringResource(R.string.shizuku_confirmed))
                null -> Unit
            }
            when (val cleanup = state.cleanup) {
                ShizukuCloseResult.Drained -> Text(stringResource(R.string.shizuku_cleanup_drained))
                is ShizukuCloseResult.Unknown -> Text(stringResource(R.string.shizuku_cleanup_unknown, cleanup.reason))
                null -> if (state.status in setOf(ShizukuApprovalStatus.REVOKED, ShizukuApprovalStatus.CLOSED)) {
                    Text(stringResource(R.string.shizuku_cleanup_pending))
                }
            }
        }
    }
}
