package app.daiyu.android.ui.screens.settings

import android.os.Handler
import android.os.Looper
import app.daiyu.android.ui.navigation.PageBackHandler as BackHandler
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
    source: SettingsModelSource,
) {
    val worker = remember(editor) { Executors.newSingleThreadExecutor() }
    val main = remember { Handler(Looper.getMainLooper()) }
    val binding = remember(editor) { SummaryModelBinding(UUID.randomUUID().toString(), UUID.randomUUID().toString()) }
    var draft by remember(editor) { mutableStateOf(CompressionSettingsDraftUi(binding)) }
    var retainedSummaryBinding by remember(editor) { mutableStateOf<SummaryModelBinding?>(null) }
    var busy by remember(editor) { mutableStateOf(true) }
    var saving by remember(editor) { mutableStateOf(false) }
    var notice by remember(editor) { mutableStateOf<String?>(null) }
    var error by remember(editor) { mutableStateOf(false) }
    var choosingModel by remember(editor) { mutableStateOf(false) }
    var enableSummarySelection by remember(editor) { mutableStateOf(false) }
    var modelLabel by remember(editor) { mutableStateOf(source.label("compression")) }
    var selectedKey by remember(editor) { mutableStateOf(source.key("compression")) }
    val page = remember(editor) { CompressionPage() }
    val currentOwner by rememberUpdatedState(ownerCurrent)

    fun submit(work: CompressionSettingsEditor.Work, save: Boolean, selectedBinding: SummaryModelBinding? = null) {
        busy = true
        saving = save
        try {
            worker.execute {
                val result = runCatching { work.runWorker() }
                main.post {
                    if (!page.active || !currentOwner()) return@post
                    result.onSuccess { completed ->
                        if (!editor.isCurrent(completed)) return@onSuccess
                        when (val outcome = completed.outcome) {
                            is CompressionSettingsEditor.Outcome.Loaded -> {
                                outcome.snapshot.settings.summaryModel?.let { retainedSummaryBinding = it }
                                draft = outcome.snapshot.toDraft(retainedSummaryBinding ?: binding).let {
                                    it.copy(provider = it.provider.copy(protocol = source.protocol("compression") ?: it.provider.protocol))
                                }
                                error = outcome.snapshot.profileUnavailableReason != null
                                notice = if (error) "已选压缩模型暂不可用。" else null
                            }
                            is CompressionSettingsEditor.Outcome.Saved -> {
                                (outcome.snapshot.settings.summaryModel ?: selectedBinding)?.let { retainedSummaryBinding = it }
                                draft = outcome.snapshot.toDraft(retainedSummaryBinding ?: binding).let {
                                    it.copy(provider = it.provider.copy(protocol = source.protocol("compression") ?: it.provider.protocol))
                                }
                                modelLabel = source.label("compression")
                                selectedKey = source.key("compression")
                                error = outcome.snapshot.profileUnavailableReason != null
                                notice = if (error) "所选模型暂不可用于压缩。" else null
                            }
                            is CompressionSettingsEditor.Outcome.Failed -> {
                                error = true
                                notice = if (outcome.writesMayHaveOccurred)
                                    "保存或回读未确认。请核对后明确重新保存；不会自动重试。"
                                else "设置读取或保存失败，请重新操作。"
                            }
                            CompressionSettingsEditor.Outcome.Revoked -> return@onSuccess
                        }
                        busy = false
                        saving = false
                    }.onFailure {
                        error = true
                        notice = "设置操作未确认，请核对后重新操作。"
                        busy = false
                        saving = false
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            editor.revoke()
            page.active = false
            busy = false
            saving = false
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
    LaunchedEffect(editor) { editor.prepareLoad()?.let { submit(it, save = false) } }
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
                    requested.compression()) }
                request.onSuccess { editor.prepareSave(it)?.let { work -> submit(work, save = true) } }.onFailure {
                    error = true
                    notice = "设置保存失败，请重新操作。"
                }
            }
        }, onBack = leave, enabled = !busy || saving, saving = busy && saving,
        notice = notice, error = error, modifier = modifier,
        modelLabel = modelLabel, hasSummarySelection = retainedSummaryBinding != null, onChooseModel = {
            if (!busy && page.active && currentOwner()) {
                enableSummarySelection = false
                choosingModel = true
            }
        }, onEnableSummary = {
            if (!busy && page.active && currentOwner()) {
                enableSummarySelection = true
                choosingModel = true
            }
        })
    if (choosingModel) SettingsModelPicker("压缩模型", source, selectedKey,
        protocols = setOf(app.daiyu.android.model.ProviderProtocol.RESPONSES, app.daiyu.android.model.ProviderProtocol.MESSAGES, app.daiyu.android.model.ProviderProtocol.CHAT_COMPLETIONS),
        onSelect = { choice ->
            if (!busy && page.active && currentOwner()) {
                val selectedBinding = app.daiyu.android.context.CatalogSummaryBindings.encode(
                    app.daiyu.android.storage.ProviderCatalogRunTarget(choice.provider,
                        choice.selection.providerIdentity, choice.selection.expectedRevision,
                        choice.selection.record.settings.localIdentity, choice.selection.record.id, choice.protocol))
                val settings = draft.copy(binding = selectedBinding, configureSummary = draft.configureSummary || enableSummarySelection).compression()
                editor.prepareSelection(settings) {
                    source.withModel(choice) { /* Validate the explicitly selected provider on the admitted worker. */ }
                    source.remember("compression", choice)
                }?.let { submit(it, save = true, selectedBinding = selectedBinding) }
            }
        }, onDismiss = { choosingModel = false })
}

private class CompressionPage { var active = true }

internal fun CompressionSettingsEditor.Snapshot.toDraft(fallbackBinding: SummaryModelBinding): CompressionSettingsDraftUi {
    val value = CompressionSettingsDraftUi(settings.summaryModel ?: fallbackBinding,
        automatic = settings.automatic, summaryProtocol = settings.summaryProtocol,
        window = settings.contextWindowTokens?.toString().orEmpty(),
        reserve = settings.reserveTokens.toString(), trigger = settings.triggerPercent.toString(),
        configureSummary = settings.summaryModel != null)
    val saved = profile ?: return value
    return value.copy(provider = app.daiyu.android.storage.ModelSettings("", saved.provider.model, "", false,
            responsesPath = saved.provider.responsesPath, protocol = saved.provider.protocol), savedProtocol = saved.provider.protocol,
        summaryWindow = saved.budgets.contextWindowTokens.toString(), inputBudget = saved.budgets.inputBudgetTokens.toString(),
        outputBudget = saved.budgets.maxOutputTokens.toString(), reasoningBudget = saved.budgets.reasoningReserveTokens.toString(),
        overheadBudget = saved.budgets.providerOverheadTokens.toString(), deadlineMillis = saved.budgets.deadlineMillis.toString())
}
