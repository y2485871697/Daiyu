package app.daiyu.android.ui.screens.settings

import app.daiyu.android.ui.navigation.PageBackStack
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import app.daiyu.android.ui.navigation.PageBackHandler as BackHandler
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.ChatController
import app.daiyu.android.ModelSettingsSaveOwner
import app.daiyu.android.model.catalog.CatalogLocalEditResult
import app.daiyu.android.model.catalog.CatalogModelParameters
import app.daiyu.android.model.catalog.SavedModelCatalogPersistence
import app.daiyu.android.storage.*
import app.daiyu.android.ui.components.DaiyuChoiceSheet
import app.daiyu.android.ui.components.DaiyuConfirmDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import app.daiyu.android.ui.haptics.miuix.TextButton

internal val RetainedProviderConnectionTest =
    SemanticsPropertyKey<Pair<ModelSettingsDraftUi, ModelConnectionTester>>("RetainedProviderConnectionTest")

private class ProviderPagePermit {
    val live = AtomicBoolean(true)
    val save = ModelSettingsSaveOwner()
    fun revoke() { live.set(false); save.revoke() }
}
private class ProviderDetail(
    val editor: ProviderEditorSlot, val name: String, val providerEnabled: Boolean, val editing: EditingSettings,
    val configurationRevision: Long,
    val persistence: SavedModelCatalogPersistence<CatalogModelParameters>, val catalogIdentity: String?,
    val balanceConfiguration: app.daiyu.android.balance.BalanceConfiguration?,
    val registry: ProviderRegistrySnapshot,
) {
    val live = AtomicBoolean(true)
    val saveOwner = ModelSettingsSaveOwner()
    var pageUi = ProviderPageUiState()
    var balanceTestJob: kotlinx.coroutines.Job? = null
    fun revoke() { live.set(false); saveOwner.revoke(); balanceTestJob?.cancel(); balanceTestJob = null }
    var draft by mutableStateOf(editing.settings.providerDraft().copy(providerName = name, providerEnabled = providerEnabled,
        balanceConfiguration = balanceConfiguration,
        balanceOption = balanceConfiguration?.option ?: app.daiyu.android.balance.BalanceOption(enabled = balanceConfiguration != null)))
}
private class PausedProviderDraft(
    val editor: ProviderEditorSlot, val editing: EditingSettings, val configurationRevision: Long,
    val draft: ModelSettingsDraftUi, val balanceConfiguration: app.daiyu.android.balance.BalanceConfiguration?,
    val pageUi: ProviderPageUiState,
)
private class NewProviderDraft(protocol: ProviderProtocol) {
    var draft by mutableStateOf(ModelSettingsDraftUi(providerName = "").selectProtocol(protocol))
    var balanceTestJob: kotlinx.coroutines.Job? = null
    fun revoke() { balanceTestJob?.cancel(); balanceTestJob = null }
}
private class ProviderConfirm(val title: String, val message: String, val destructive: Boolean, val action: () -> Unit)
private fun ModelSettings.providerDraft() = ModelSettingsDraftUi(protocol = protocol, endpoint = endpoint,
    responsesPath = responsesPath, apiKey = apiKey, model = model, batteryEnabled = batteryEnabled,
    deviceStatusEnabled = deviceStatusEnabled, customBody = requestOptions.customBody,
    speedMode = requestOptions.speedMode, codex = requestOptions.codex,
    anthropicVersion = requestOptions.anthropicVersion.orEmpty(),
    openAiWebSearchEnabled = requestOptions.openAiWebSearchEnabled,
    sessionGateway = requestOptions.sessionGateway,
    customHeaders = if (customHeaders.isEmpty) "" else customHeaders.encode())
private fun ModelSettingsDraftUi.providerSettings(base: ModelSettings) = base.copy(endpoint = endpoint.trim(),
    responsesPath = responsesPath.trim(), model = model.trim(), apiKey = apiKey, protocol = protocol,
    batteryEnabled = batteryEnabled, deviceStatusEnabled = deviceStatusEnabled,
    customHeaders = app.daiyu.android.model.ProviderHeaders.parse(customHeaders),
    requestOptions = base.requestOptions.copy(sessionGateway = sessionGateway, customBody = customBody, speedMode = speedMode, codex = codex,
        anthropicVersion = anthropicVersion.takeIf { it.isNotEmpty() }, openAiWebSearchEnabled = openAiWebSearchEnabled))

@Composable
internal fun ProviderManagementRoute(controller: ChatController, ownerCurrent: () -> Boolean,
    onBack: () -> Unit, onDetailBack: () -> Unit = onBack, engineeringUi: Boolean = false,
    initialSlot: String? = null, pageTitleOverride: String? = null,
) = ProviderSettingsTheme {
    val repo = controller.providers
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ownerCheck by rememberUpdatedState(ownerCurrent)
    val visit = remember { AtomicBoolean(true) }
    var permit by remember { mutableStateOf(ProviderPagePermit()) }
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var registry by remember { mutableStateOf<ProviderRegistrySnapshot?>(null, referentialEqualityPolicy()) }
    var summaries by remember { mutableStateOf<Map<String, ProviderCardSummary>>(emptyMap()) }
    var query by remember { mutableStateOf("") }
    val directoryListState = rememberLazyListState()
    var authentication by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf<RegisteredProvider?>(null) }
    var detail by remember { mutableStateOf<ProviderDetail?>(null) }
    var openingSlot by remember { mutableStateOf<String?>(null) }
    var pausedDraft by remember { mutableStateOf<PausedProviderDraft?>(null) }
    var pendingOpen by remember { mutableStateOf(true) }
    var serial by remember { mutableStateOf(0L) }
    var busy by remember { mutableStateOf<Any?>(null) }
    var loading by remember { mutableStateOf(false) }
    var checkingRegistry by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf<NewProviderDraft?>(null) }
    var confirmation by remember { mutableStateOf<ProviderConfirm?>(null) }
    var version by remember { mutableStateOf(0L) }
    fun current(p: ProviderPagePermit) = visit.get() && p.live.get() && permit === p && resumed &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && ownerCheck()
    fun clearPrompts() { creating?.revoke(); creating = null; confirmation = null; managing = null }
    fun closeDetail() { serial++; loading = false; openingSlot = null; detail?.revoke(); detail = null; clearPrompts() }
    fun refresh() {
        val p = permit
        if (!current(p)) return
        val request = ++serial
        val previousDetail = detail
        checkingRegistry = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val value = repo.snapshot()
                val detailCurrent = previousDetail == null ||
                    (previousDetail.editor.registryRevision == value.revision && repo.editing(previousDetail.editor) == previousDetail.editing)
                val cards = value.providers.associate { registration ->
                    val editor = ProviderEditorSlot(registration.slotId, value.revision)
                    registration.slotId to repo.withEditor(editor) { store ->
                        val fields = store.nonSecretConfiguration()
                        val endpoint = runCatching {
                            val uri = java.net.URI(fields["endpoint"] as? String ?: "")
                            if (uri.host == null) "" else java.net.URI(uri.scheme, null, uri.host, uri.port,
                                uri.path, null, null).toString()
                        }.getOrDefault("")
                        ProviderCardSummary(endpoint,
                            ProviderProtocol.entries.firstOrNull { it.wireName == fields["protocol"] }
                                ?: if (fields["protocol"] == null) ProviderProtocol.RESPONSES else ProviderProtocol.UNKNOWN,
                            runCatching { store.localModelCatalogSnapshot()?.models?.size }.getOrNull())
                    }
                }
                Triple(value, detailCurrent, cards)
            } }
            if (!current(p) || serial != request) return@launch
            checkingRegistry = false
            result.fold(onSuccess = { (value, detailCurrent, cards) ->
                registry = value
                summaries = cards
                if (!detailCurrent && detail === previousDetail) {
                    closeDetail(); notice = "提供商配置已变化，请重新打开详情。"
                }
            }, onFailure = { closeDetail(); notice = "提供商列表读取失败；未创建替代配置。" })
        }
    }
    fun open(slot: String, restore: PausedProviderDraft? = null, newProtocol: ProviderProtocol? = null) {
        val p = permit
        if (!current(p) || busy != null) return
        if (restore == null) pausedDraft = null
        closeDetail(); openingSlot = slot; checkingRegistry = false; loading = true; notice = null
        val request = ++serial
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val snapshot = repo.snapshot()
                val registration = checkNotNull(snapshot.providers.singleOrNull { it.slotId == slot })
                val editor = repo.editor(slot)
                check(editor.registryRevision == snapshot.revision)
                val (configurationRevision, editing) = repo.withEditor(editor) { store ->
                    store.modelCatalog.readConfiguration { revision -> revision to store.loadForEditing() }
                }
                val identity = repo.withEditor(editor) { it.localModelCatalogSnapshot()?.providerIdentity }
                val balance = controller.providerBalances.get(slot).store.configurationForEditing()
                repo.withEditor(editor) { }
                ProviderDetail(editor, registration.name, registration.enabled, editing, configurationRevision,
                    repo.catalogPersistence(editor), identity, balance, snapshot)
            } }
            if (!current(p) || serial != request) return@launch
            loading = false
            result.fold(onSuccess = { loaded ->
                if (restore != null && restore.editor == loaded.editor && restore.editing == loaded.editing &&
                    restore.configurationRevision == loaded.configurationRevision && restore.balanceConfiguration == loaded.balanceConfiguration) {
                    loaded.draft = restore.draft.copy(protocolChangeAuthorized = false, requestOptionsReplacementAuthorized = false, headersReplacementAuthorized = false)
                    loaded.pageUi = restore.pageUi
                }
                if (newProtocol != null) loaded.draft = loaded.draft.selectProtocol(newProtocol)
                authentication = false
                pausedDraft = null
                registry = loaded.registry
                detail = loaded
                openingSlot = null
            }, onFailure = { openingSlot = null; pausedDraft = null; notice = "详情读取失败或已变化，请刷新后重新打开。" })
        }
    }
    fun mutate(expected: ProviderRegistrySnapshot, p: ProviderPagePermit,
        completed: ((ProviderRegistrySnapshot) -> Unit)? = null,
        action: (ProviderRepository, Long, () -> Unit) -> ProviderRegistrySnapshot,
    ) {
        if (!current(p) || registry !== expected || detail != null || loading || busy != null) return
        confirmation = null; managing = null
        val navigation = serial
        val ticket = Any(); busy = ticket
        try {
            controller.mutateProviders(expected.revision, p.save, action) { result ->
                if (busy === ticket) busy = null
                if (current(p)) {
                    if (result != null) {
                        registry = result; notice = null
                        if (completed != null && serial == navigation) completed(result) else refresh()
                    }
                    else { notice = "操作未确认，请刷新核对；不会自动重试。"; refresh() }
                }
            }
        } catch (_: Exception) { if (busy === ticket) busy = null; if (current(p)) notice = "请等待当前运行或数据操作结束。" }
    }
    DisposableEffect(controller) {
        val listener: () -> Unit = { version++; if (busy == null && !loading && detail == null && creating == null && !checkingRegistry) refresh() }
        controller.observe(listener)
        onDispose { controller.unobserve(listener) }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                detail?.let {
                    it.pageUi.checkpointModelPositionForResume()
                    pausedDraft = PausedProviderDraft(it.editor, it.editing, it.configurationRevision, it.draft, it.balanceConfiguration, it.pageUi)
                }
                authentication = false
                resumed = false; permit.revoke(); closeDetail(); checkingRegistry = false
            } else if (event == Lifecycle.Event.ON_RESUME) {
                permit = ProviderPagePermit(); resumed = true
                pendingOpen = true
            }
        }
        lifecycle.addObserver(observer)
        onDispose { pausedDraft = null; visit.set(false); permit.revoke(); detail?.revoke(); creating?.revoke(); lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumed, permit, busy, initialSlot, pendingOpen) {
        if (pendingOpen && current(permit) && busy == null) {
            val restore = pausedDraft
            pendingOpen = false
            if (restore != null) open(restore.editor.slotId, restore)
            else if (initialSlot == null) refresh() else open(initialSlot)
        }
    }
    fun back() {
        if (creating != null) { serial++; clearPrompts(); refresh(); return }
        if (authentication && !loading) { serial++; authentication = false; clearPrompts(); refresh(); return }
        detail?.revoke()
        pausedDraft = null; pendingOpen = false
        if (detail != null || loading) {
            authentication = false
            // The primary provider is entered as the app's model-settings page, not as a
            // separate provider-list visit. Returning from its detail must leave the route;
            // otherwise the next Back press only reveals the provider list.
            if (initialSlot == ProviderRegistry.PRIMARY) {
                permit.revoke(); onDetailBack()
            } else {
                closeDetail(); refresh()
            }
        } else {
            permit.revoke(); onBack()
        }
    }
    PageBackStack(when {
        creating != null -> "create-provider"
        (detail != null || openingSlot != null) && initialSlot != ProviderRegistry.PRIMARY ->
            "provider:${detail?.editor?.slotId ?: openingSlot}"
        authentication -> "provider-authentication"
        else -> null
    }, if (creating != null && authentication) "provider-authentication" else null) {
        BackHandler(previewEnabled = !loading || initialSlot == ProviderRegistry.PRIMARY) { back() }
        val p = permit
        val enabled = version.let { current(p) && controller.ready && controller.active == null &&
            !controller.dataBusy && !controller.hasLiveChildTasks() && busy == null && !loading && !checkingRegistry }
        val modelScrollOwner = detail?.pageUi ?: pausedDraft?.pageUi
        val modelScrollBehavior = key(modelScrollOwner) { MiuixScrollBehavior() }
        val d = detail
        val newProvider = creating
        if (newProvider != null) key(newProvider) {
            val draft = newProvider.draft
            var modelTab by remember { mutableStateOf(false) }
            val tabs: LazyListScope.() -> Unit = {
                item("provider-tabs") { ProviderSettingsTabs(modelTab, enabled) { modelTab = it } }
            }
            if (modelTab) ProviderEmptyModelsPage("添加提供商", { back() }, tabs,
                "请先在配置页保存名称、Base URL 和 API Key，再添加或拉取模型。")
            else ModelSettingsScreen(draft, { if (enabled && creating === newProvider) {
                newProvider.revoke(); newProvider.draft = it
            } },
                onSave = { submitted ->
                    val expected = registry
                    if (enabled && creating === newProvider && newProvider.draft === submitted && expected != null) {
                        val value = runCatching {
                            ProviderRegistry.requireValidName(submitted.providerName.orEmpty().trim())
                            submitted.providerBalanceConfiguration()?.validateProviderBinding()
                            require(!submitted.balanceOption.enabled || submitted.providerBalanceConfiguration() != null)
                            require(submitted.headerFieldsValid && submitted.bodyFieldsValid)
                            require(submitted.protocol == submitted.savedProtocol || submitted.protocolChangeAuthorized)
                            submitted.providerSettings(ModelSettings("", "", "", false)).also { it.validateConnection() }
                        }
                        if (value.isFailure) notice = "未保存，请检查名称、连接配置及密钥。"
                        else mutate(expected, p, completed = { result ->
                            val added = result.providers.single { candidate -> expected.providers.none { it.slotId == candidate.slotId } }
                            open(added.slotId)
                        }) { repo, revision, admit ->
                            val created = repo.registerConfigured(revision, submitted.providerName.orEmpty().trim(), value.getOrThrow(),
                                submitted.protocolChangeAuthorized, enabled = submitted.providerEnabled, admit = admit)
                            submitted.providerBalanceConfiguration()?.let {
                                val added = created.providers.single { candidate -> expected.providers.none { it.slotId == candidate.slotId } }
                                val runtime = kotlinx.coroutines.runBlocking { controller.providerBalances.get(added.slotId) }
                                repo.withEditor(repo.editor(added.slotId)) { admit(); kotlinx.coroutines.runBlocking { runtime.save(checkNotNull(submitted.providerBalanceConfiguration(added.slotId))) } }
                            }
                            created
                        }
                    }
                }, onFetchCatalog = {}, onBack = { back() }, pageTitle = "添加提供商", providerName = "",
                headerContent = tabs, showCatalog = false, enabled = enabled, errorText = notice,
                testBalance = { value, requestCancelled, deliver ->
                    val configuration = value.providerBalanceConfiguration()
                    if (!current(p) || !enabled || creating !== newProvider || newProvider.draft !== value ||
                        requestCancelled() || configuration == null || !configuration.queryEnabled ||
                        runCatching { configuration.validateProviderBinding() }.isFailure) false
                    else {
                        newProvider.revoke()
                        newProvider.balanceTestJob = scope.launch {
                            val result = try { controller.providerBalances.queryDraft(configuration) }
                            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) { app.daiyu.android.balance.BalanceResult.Failed(app.daiyu.android.balance.BalanceFailure.NETWORK) }
                            if (current(p) && creating === newProvider && newProvider.draft === value && !requestCancelled()) deliver(result)
                        }
                        true
                    }
                }, testConnection = { value, cancelled, deliver ->
                    if (!enabled || creating !== newProvider || newProvider.draft !== value || cancelled()) false
                    else runCatching { value.providerSettings(ModelSettings("", "", "", false)) }.getOrNull()?.let { settings ->
                        controller.testModelConnection(settings,
                            { !current(p) || creating !== newProvider || newProvider.draft !== value || cancelled() }, deliver)
                    } ?: false
                })
        } else if (d != null) key(d) {
            val live = { current(p) && detail === d && d.live.get() }
            val cancelled = { !p.live.get() || !d.live.get() }
            val draft = d.draft
            val saved = d.editing.settings
            val detailTitle = pageTitleOverride ?: d.name
            val save: (ModelSettingsDraftUi) -> Unit = { submitted ->
                if (live() && enabled && d.draft === submitted) {
                    val value = runCatching {
                        require(submitted.headerFieldsValid && submitted.bodyFieldsValid) { "请先修正请求头或请求体字段。" }
                        require(submitted.savedProtocol == saved.protocol) { "编辑基线已变化，请重新打开详情。" }
                        require(submitted.protocol == saved.protocol || submitted.protocolChangeAuthorized) {
                            "切换协议须重新输入密钥并明确确认"
                        }
                        require(!d.editing.requestOptionsUnavailable || submitted.requestOptionsReplacementAuthorized) {
                            "请求策略已损坏；替换或重置需要明确确认"
                        }
                        require(!d.editing.headersUnavailable || submitted.headersReplacementAuthorized) {
                            "原请求头不可用；替换或清除需要明确确认"
                        }
                        ProviderRegistry.requireValidName(submitted.providerName.orEmpty().trim())
                        submitted.providerBalanceConfiguration()?.validateProviderBinding()
                        require(!submitted.balanceOption.enabled || submitted.providerBalanceConfiguration() != null) { "请完善余额查询设置" }
                        submitted.providerSettings(saved).also { it.validateConnection() }
                    }
                    if (value.isFailure) {
                        notice = "未保存，请检查输入和必要确认。"
                    } else {
                        val ticket = Any(); busy = ticket
                        try {
                            controller.saveProviderSettings(d.editor, value.getOrThrow(), d.editing,
                                submitted.protocolChangeAuthorized, submitted.requestOptionsReplacementAuthorized, d.saveOwner,
                                headersReplacementAuthorized = submitted.headersReplacementAuthorized,
                                providerName = submitted.providerName.orEmpty().trim(),
                                providerEnabled = submitted.providerEnabled,
                                replaceBalanceConfiguration = submitted.providerBalanceConfiguration(d.editor.slotId) != d.balanceConfiguration,
                                balanceConfiguration = submitted.providerBalanceConfiguration(d.editor.slotId), expectedBalanceConfiguration = d.balanceConfiguration) { success ->
                                if (busy === ticket) busy = null
                                if (live()) {
                                    if (success && initialSlot == ProviderRegistry.PRIMARY && d.editor.slotId == ProviderRegistry.PRIMARY) {
                                        d.revoke(); p.revoke(); onDetailBack()
                                    } else if (success) open(d.editor.slotId)
                                    else { closeDetail(); notice = "保存未完整确认，请重新打开详情核对名称与配置。"; refresh() }
                                }
                            }
                        } catch (_: Exception) { if (busy === ticket) busy = null; if (live()) notice = "当前不可保存；未重试。" }
                    }
                }
            }
            val change: (ModelSettingsDraftUi) -> Unit = { if (live() && enabled && d.draft === draft) {
                d.balanceTestJob?.cancel(); d.balanceTestJob = null; d.draft = it
            } }
            val balanceTest: ProviderBalanceTester = { value, requestCancelled, deliver ->
                val configuration = value.providerBalanceConfiguration(d.editor.slotId)?.takeIf { runCatching { it.validateProviderBinding(); it.queryEnabled }.getOrDefault(false) }
                if (!live() || !enabled || d.draft !== value || requestCancelled() || configuration == null) false
                else {
                    d.balanceTestJob?.cancel()
                    d.balanceTestJob = scope.launch {
                        val result = try {
                            withContext(Dispatchers.IO) {
                                repo.withEditor(d.editor) { store -> store.modelCatalog.readConfiguration { revision ->
                                    check(revision == d.configurationRevision && !cancelled() && !requestCancelled())
                                } }
                            }
                            controller.providerBalances.queryDraft(configuration)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { app.daiyu.android.balance.BalanceResult.Failed(app.daiyu.android.balance.BalanceFailure.NETWORK) }
                        val stillCurrent = withContext(Dispatchers.IO) { runCatching {
                            repo.withEditor(d.editor) { store -> store.modelCatalog.readConfiguration { it == d.configurationRevision } }
                        }.getOrDefault(false) }
                        if (live() && !requestCancelled() && d.draft === value) {
                            if (stillCurrent) deliver(result)
                            else { closeDetail(); notice = "提供商配置已变化，请重新打开详情。"; refresh() }
                        }
                    }
                    true
                }
            }
            fun connectionSettings(value: ModelSettingsDraftUi): ModelSettings? = try { value.providerSettings(saved) }
                catch (_: IllegalArgumentException) { notice = "自定义请求头无效，请检查后重试。"; null }
            val connection: ModelConnectionTester = { value, requestCancelled, deliver ->
                if (!live() || !enabled || !value.headerFieldsValid || !value.bodyFieldsValid || requestCancelled() || d.draft !== value) false
                else connectionSettings(value)?.let { settings ->
                    controller.testModelConnection(settings,
                        { cancelled() || requestCancelled() || d.draft !== value }) {
                        if (live() && !requestCancelled() && d.draft === value) deliver(it)
                    }
                } ?: false
            }
            val connectionModifier = if (engineeringUi) Modifier.semantics {
                this[RetainedProviderConnectionTest] = draft to connection
            } else Modifier
            val actions = LocalCatalogModelActions<CatalogModelParameters>(saved.model,
                rename = { selection, name, requestCancelled, complete ->
                    if (live() && !requestCancelled()) controller.renameProviderCatalogModel(d.editor, selection, name,
                        { cancelled() || requestCancelled() }) { if (live() && !requestCancelled()) complete(it) }
                    else complete(CatalogLocalEditResult.CANCELLED)
                }, delete = { selection, requestCancelled, complete ->
                    if (live() && !requestCancelled()) controller.deleteProviderCatalogModel(d.editor, selection,
                        { cancelled() || requestCancelled() }) { if (live() && !requestCancelled()) complete(it) }
                    else complete(CatalogLocalEditResult.CANCELLED)
                }, form = { app.daiyu.android.model.catalog.CatalogModelEdit.from(it) },
                update = { selection, value, requestCancelled, complete ->
                    if (live() && enabled && !requestCancelled()) controller.updateProviderCatalogModel(d.editor, selection, value,
                        { cancelled() || requestCancelled() }) { if (live() && !requestCancelled()) complete(it) }
                    else complete(CatalogLocalEditResult.CANCELLED)
                }, add = { snapshot, value, requestCancelled, complete ->
                    if (live() && enabled && !requestCancelled()) controller.addProviderCatalogModel(d.editor,
                        snapshot.providerIdentity, snapshot.revision, value, { cancelled() || requestCancelled() }) {
                        if (live() && !requestCancelled()) complete(it)
                    } else complete(CatalogLocalEditResult.CANCELLED)
                })
            val delete: () -> Unit = {
                if (live() && enabled && registry === d.registry) {
                    confirmation = ProviderConfirm("删除提供商", "确定删除“${d.name}”？若为当前提供商，将清空当前选择，不会自动切换。", true) {
                        if (live() && enabled && registry === d.registry) {
                            closeDetail()
                            mutate(d.registry, p, completed = {
                                if (initialSlot == ProviderRegistry.PRIMARY && d.editor.slotId == ProviderRegistry.PRIMARY) { p.revoke(); onDetailBack() }
                                else refresh()
                            }) { repo, revision, admit -> repo.delete(revision, d.editor.slotId, admit) }
                        }
                    }
                }
            }
            if (d.catalogIdentity == null) {
                var modelTab by d.pageUi.modelTab
                val tabs: LazyListScope.() -> Unit = {
                    item(key = "provider-tabs") {
                        ProviderSettingsTabs(modelTab, enabled) { selected ->
                            if (live()) modelTab = selected
                        }
                    }
                }
                if (modelTab) {
                    ProviderEmptyModelsPage(detailTitle, { back() }, tabs,
                        "请先在配置页保存 Base URL 和 API Key，再添加或拉取模型。",
                        listState = d.pageUi.modelListState, queryState = d.pageUi.modelQuery)
                } else ModelSettingsScreen(draft, change, save, {}, { back() },
                    modifier = connectionModifier, headerContent = tabs, showCatalog = false,
                    pageTitle = detailTitle, providerName = d.name, enabled = enabled, engineeringUi = engineeringUi, testConnection = connection,
                    credentialUnavailable = d.editing.credentialUnavailable, requestOptionsUnavailable = d.editing.requestOptionsUnavailable,
                    headersUnavailable = d.editing.headersUnavailable,
                    catalogEnabled = false, errorText = notice, testBalance = balanceTest, onDelete = delete,
                    listState = d.pageUi.configurationListState)
            } else ProviderCatalogSettingsScreen(draft, change, save, { back() }, d.catalogIdentity, d.persistence,
                createModel = { CatalogModelParameters.newModel(it) }, fetchCatalog = { value, requestCancelled, deliver ->
                    if (!live() || !enabled || !value.headerFieldsValid || !value.bodyFieldsValid || requestCancelled() || d.draft !== value) false
                    else connectionSettings(value)?.let { settings ->
                        controller.fetchModelCatalog(settings,
                            { cancelled() || requestCancelled() || d.draft !== value }) {
                            if (live() && !requestCancelled() && d.draft === value) deliver(it)
                        }
                    } ?: false
                }, modifier = connectionModifier, pageTitle = detailTitle, providerName = d.name,
                // A normal list visit uses the configuration/model split for every provider.
                twoTabs = true, ownerCurrent = live,
                enabled = enabled, engineeringUi = engineeringUi, errorText = notice,
                credentialUnavailable = d.editing.credentialUnavailable, requestOptionsUnavailable = d.editing.requestOptionsUnavailable,
                headersUnavailable = d.editing.headersUnavailable,
                testConnection = connection, localModelActions = actions, testBalance = balanceTest,
                onDelete = delete, pageUi = d.pageUi, modelScrollBehavior = modelScrollBehavior)
        } else if (loading) {
            SettingsScaffoldPage("模型提供商", { back() }) { item { SettingsNote("正在读取提供商详情") } }
        } else if (authentication) {
            ProviderAuthenticationPage(enabled, notice, onBack = { back() }, onApiKey = {
                if (enabled) creating = NewProviderDraft(ProviderProtocol.RESPONSES)
            })
        } else {
            ProviderDirectoryPage(registry, summaries, query, { query = it }, enabled && registry != null,
                notice, onBack = { back() },
                onAddOpenAi = { if (enabled) authentication = true },
                onAddAnthropic = { if (enabled) creating = NewProviderDraft(ProviderProtocol.MESSAGES) },
                onOpen = { if (enabled) open(it.slotId) },
                onManage = { if (enabled) managing = it }, modifier = Modifier.testTag("provider-management"),
                listState = directoryListState)
        }
        val managed = managing
        val managedRegistry = registry
        if (managed != null && managedRegistry != null) DaiyuChoiceSheet(managed.name,
            listOf("配置与模型", if (managed.enabled) "禁用" else "启用", "设为当前提供商", "删除提供商"),
            onSelect = { index ->
                if (current(p) && enabled && managing === managed) {
                    managing = null
                    when (index) {
                        0 -> open(managed.slotId)
                        1 -> mutate(managedRegistry, p) { repo, revision, admit ->
                            repo.setEnabled(revision, managed.slotId, !managed.enabled, admit)
                        }
                        2 -> if (managed.enabled && managedRegistry.activeSlotId != managed.slotId) {
                            confirmation = ProviderConfirm("切换提供商", "后续新运行将使用此提供商的已保存配置。", false) {
                                mutate(managedRegistry, p) { repo, revision, admit -> repo.activate(revision, managed.slotId, admit) }
                            }
                        }
                        3 -> confirmation = ProviderConfirm("删除提供商", "将移除注册并撤销旧编辑入口；若为当前提供商则清空选择，不自动切换。不会安全擦除原加密配置。", true) {
                            mutate(managedRegistry, p) { repo, revision, admit -> repo.delete(revision, managed.slotId, admit) }
                        }
                    }
                }
            }, onDismiss = { managing = null })
        val confirm = confirmation
        if (confirm != null) DaiyuConfirmDialog(confirm.title, confirm.message, "确认", confirm.destructive,
            onConfirm = { if (confirmation === confirm && current(p) && enabled) { confirmation = null; confirm.action() } },
            onDismiss = { if (confirmation === confirm) confirmation = null })
    }
}
