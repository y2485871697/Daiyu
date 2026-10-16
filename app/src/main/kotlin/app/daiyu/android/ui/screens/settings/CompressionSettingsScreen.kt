package app.daiyu.android.ui.screens.settings

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import app.daiyu.android.context.CompressionSettingsEditor
import app.daiyu.android.context.SummaryModelBinding
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

@Composable
internal fun CompressionSettingsScreen(
    editor: CompressionSettingsEditor,
    onBack: () -> Unit,
    ownerCurrent: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val worker = remember(editor) { Executors.newSingleThreadExecutor() }
    val main = remember { Handler(Looper.getMainLooper()) }
    val binding = remember(editor) { SummaryModelBinding(UUID.randomUUID().toString(), UUID.randomUUID().toString()) }
    var draft by remember(editor) { mutableStateOf(CompressionSettingsDraftUi(binding)) }
    var busy by remember(editor) { mutableStateOf(true) }
    var notice by remember(editor) { mutableStateOf<String?>(null) }
    var error by remember(editor) { mutableStateOf(false) }
    val page = remember(editor) { CompressionPage() }
    val currentOwner by rememberUpdatedState(ownerCurrent)

    fun submit(work: CompressionSettingsEditor.Work) {
        busy = true
        try {
            worker.execute {
                val result = runCatching { work.runWorker() }
                main.post {
                    if (!page.active || !currentOwner()) return@post
                    result.onSuccess { completed ->
                        if (!editor.isCurrent(completed)) return@onSuccess
                        when (val outcome = completed.outcome) {
                            is CompressionSettingsEditor.Outcome.Loaded -> {
                                draft = outcome.snapshot.toDraft(binding)
                                error = outcome.snapshot.profileUnavailableReason != null
                                notice = if (error) "已选摘要配置不可用，请重新填写并保存；未读取聊天模型密钥。" else null
                            }
                            is CompressionSettingsEditor.Outcome.Saved -> {
                                draft = outcome.snapshot.toDraft(binding)
                                error = false
                                notice = "设置已保存并回读；没有发起摘要请求。"
                            }
                            is CompressionSettingsEditor.Outcome.Failed -> {
                                error = true
                                notice = if (outcome.writesMayHaveOccurred)
                                    "保存或回读未确认。请核对后明确重新保存；不会自动重试。"
                                else "当前设置无法读取或校验失败。请明确填写后保存；旧版消息数不会自动转成轮数。"
                            }
                            CompressionSettingsEditor.Outcome.Revoked -> return@onSuccess
                        }
                        busy = false
                    }.onFailure {
                        error = true
                        notice = "设置操作未确认，请核对后重新操作。"
                        busy = false
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            editor.revoke()
            page.active = false
            busy = false
            error = true
            notice = "设置页面已结束，请重新进入。"
        }
    }

    DisposableEffect(editor) {
        onDispose {
            editor.revoke()
            page.active = false
            // Admitted writes finish; queued work sees the permanently revoked owner.
            worker.shutdown()
        }
    }
    LaunchedEffect(editor) { editor.prepareLoad()?.let(::submit) }
    val leave: () -> Unit = {
        editor.revoke()
        page.active = false
        onBack()
    }
    BackHandler(onBack = leave)
    CompressionSettingsForm(draft = draft,
        onChange = { if (page.active && currentOwner() && !busy) { draft = it; notice = null; error = false } },
        onSave = { requested ->
            if (page.active && currentOwner() && !busy) {
                val request = runCatching { CompressionSettingsEditor.SaveRequest(
                    requested.compression(), requested.summary(), requested.protocolChangeAuthorized,
                    requested.requestOptionsReplacementAuthorized) }
                request.onSuccess { editor.prepareSave(it)?.let(::submit) }.onFailure {
                    error = true
                    notice = "请检查窗口、预算、保留轮数、端点和摘要配置；数值必须为合法整数。"
                }
            }
        }, onBack = leave, enabled = !busy, notice = notice, error = error, modifier = modifier)
}

private class CompressionPage { var active = true }

private fun CompressionSettingsEditor.Snapshot.toDraft(fallbackBinding: SummaryModelBinding): CompressionSettingsDraftUi {
    val value = CompressionSettingsDraftUi(settings.summaryModel ?: fallbackBinding,
        automatic = settings.automatic, window = settings.contextWindowTokens?.toString().orEmpty(),
        reserve = settings.reserveTokens.toString(), trigger = settings.triggerPercent.toString(),
        keepTurns = settings.keepTurns.toString(), configureSummary = settings.summaryModel != null)
    val saved = profile ?: return value
    return value.copy(provider = saved.provider, savedProtocol = saved.provider.protocol,
        summaryWindow = saved.budgets.contextWindowTokens.toString(), inputBudget = saved.budgets.inputBudgetTokens.toString(),
        outputBudget = saved.budgets.maxOutputTokens.toString(), reasoningBudget = saved.budgets.reasoningReserveTokens.toString(),
        overheadBudget = saved.budgets.providerOverheadTokens.toString(), deadlineMillis = saved.budgets.deadlineMillis.toString())
}
