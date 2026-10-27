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
import app.daiyu.android.ui.components.DaiyuConfirmDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

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
    var saveOwner = ModelSettingsSaveOwner()
        private set
    var error by mutableStateOf<String?>(null)
    var pageUi = ProviderPageUiState()
    var balanceTestJob: kotlinx.coroutines.Job? = null
    fun revoke() { live.set(false); saveOwner.revoke(); balanceTestJob?.cancel(); balanceTestJob = null }
    fun resume() { saveOwner = ModelSettingsSaveOwner(); live.set(true) }
    var draft by mutableStateOf(editing.settings.providerDraft().copy(providerName = name, providerEnabled = providerEnabled,
        balanceConfiguration = balanceConfiguration,
        balanceOption = balanceConfiguration?.option ?: app.daiyu.android.balance.BalanceOption(enabled = balanceConfiguration != null)))
}
private class PausedProviderDraft(
    val editor: ProviderEditorSlot, val editing: EditingSettings, val configurationRevision: Long,
    val draft: ModelSettingsDraftUi, val balanceConfiguration: app.daiyu.android.balance.BalanceConfiguration?,
    val pageUi: ProviderPageUiState,
)
private class NewProviderDraft(protocol: ProviderProtocol, val speechOnly: Boolean = false, oauth: app.daiyu.android.model.oauth.CodexAuth? = null) {
    val pageUi = ProviderPageUiState()
    var error by mutableStateOf<String?>(null)
    var draft by mutableStateOf((if (speechOnly) ModelSettingsDraftUi(
        providerName = "豆包语音", endpoint = app.daiyu.android.voice.providers.DoubaoSpeech.BASE_URL,
        model = "seed-tts-2.0", providerEnabled = true,
    ) else if (oauth != null) ModelSettingsDraftUi(providerName = "OpenAI Codex", endpoint = app.daiyu.android.model.oauth.CodexOAuthProtocol.BASE_URL,
        responsesPath = "/responses", oauth = oauth) else ModelSettingsDraftUi(providerName = "")).selectProtocol(protocol))
    var balanceTestJob: kotlinx.coroutines.Job? = null
    val registration = ProviderRegistrationOwnership { oauth?.let(app.daiyu.android.model.oauth.CodexOAuthRuntime::discard) }
    fun revoke() { balanceTestJob?.cancel(); balanceTestJob = null; registration.revoke() }
}
private class ProviderConfirm(val title: String, val message: String, val destructive: Boolean, val deletionAppearance: Boolean = false, val action: () -> Unit)
private fun ModelSettings.providerDraft() = ModelSettingsDraftUi(oauth = oauth, protocol = protocol, endpoint = endpoint,
    responsesPath = responsesPath, apiKey = apiKey, model = model, batteryEnabled = batteryEnabled,
    deviceStatusEnabled = deviceStatusEnabled, customBody = requestOptions.customBody,
    speedMode = requestOptions.speedMode, codex = requestOptions.codex,
    anthropicVersion = requestOptions.anthropicVersion.orEmpty(),
    openAiWebSearchEnabled = requestOptions.openAiWebSearchEnabled,
    sessionGateway = requestOptions.sessionGateway,
    customHeaders = if (customHeaders.isEmpty) "" else customHeaders.encode())
private fun ModelSettingsDraftUi.providerSettings(base: ModelSettings) = base.copy(endpoint = endpoint.trim(),
    responsesPath = responsesPath.trim(), model = model.trim(), apiKey = apiKey, oauth = oauth, protocol = protocol,
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
    val context = androidx.compose.ui.platform.LocalContext.current
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
    var selectedSlots by remember { mutableStateOf<Set<String>?>(null) }
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
    var oauthAttempt by remember { mutableStateOf<String?>(null) }
    var oauthResult by remember { mutableStateOf<app.daiyu.android.model.oauth.CodexAuth?>(null) }
    var oauthReturned by remember { mutableStateOf(false) }
    var oauthRegistryRevision by remember { mutableStateOf<Long?>(null) }
    fun discardOAuth(auth: app.daiyu.android.model.oauth.CodexAuth?) {
        if (auth != null) app.daiyu.android.model.oauth.CodexOAuthRuntime.discard(auth)
    }
    val oauthLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val auth = data?.getStringExtra("oauth-reference")?.let { runCatching { app.daiyu.android.model.oauth.CodexAuth(it) }.getOrNull() }
        if (oauthAttempt != null && (data == null || data.getStringExtra("attempt") == oauthAttempt) && ownerCheck() && visit.get()) {
            oauthResult = auth; oauthReturned = true
        } else { discardOAuth(auth); oauthReturned = true }
    }
    fun current(p: ProviderPagePermit) = visit.get() && p.live.get() && permit === p && resumed &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && ownerCheck()
    fun clearTransientPrompts() { confirmation = null; selectedSlots = null }
    fun clearPrompts() { creating?.revoke(); creating = null; clearTransientPrompts() }
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
                if (registry?.revision != value.revision) selectedSlots = null
                registry = value
                summaries = cards
                if (!detailCurrent && detail === previousDetail) {
                    closeDetail(); notice = "提供商配置已变化，请重新打开详情。"
                }
            }, onFailure = {
                if (detail != null) closeDetail()
                registry = null; notice = "提供商列表读取失败；未创建替代配置。"
            })
        }
    }
    fun open(slot: String, restore: PausedProviderDraft? = null, newProtocol: ProviderProtocol? = null) {
        val p = permit
        if (!current(p) || busy != null) return
        val retained = detail?.takeIf { restore != null && it.editor == restore.editor }
        if (restore == null) pausedDraft = null
        if (retained == null) closeDetail()
        openingSlot = slot; checkingRegistry = false; loading = true; notice = null
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
                val sameConfiguration = restore != null && restore.editor == loaded.editor && restore.editing == loaded.editing &&
                    restore.configurationRevision == loaded.configurationRevision && restore.balanceConfiguration == loaded.balanceConfiguration
                val ready = if (sameConfiguration && retained != null) retained.also { it.resume() } else loaded
                if (sameConfiguration) {
                    ready.draft = checkNotNull(restore).draft.copy(protocolChangeAuthorized = false, requestOptionsReplacementAuthorized = false, headersReplacementAuthorized = false)
                    ready.pageUi = checkNotNull(restore).pageUi
                }
                if (newProtocol != null) ready.draft = ready.draft.selectProtocol(newProtocol)
                authentication = false
                pausedDraft = null
                registry = ready.registry
                detail = ready
                openingSlot = null
            }, onFailure = { openingSlot = null;
                pausedDraft = null; notice = "详情读取失败或已变化，请刷新后重新打开。"
                retained?.error = notice
            })
        }
    }
    fun mutate(expected: ProviderRegistrySnapshot, p: ProviderPagePermit,
        completed: ((ProviderRegistrySnapshot) -> Unit)? = null,
        reportFailure: (String) -> Unit = { notice = it },
        action: (ProviderRepository, Long, () -> Unit) -> ProviderRegistrySnapshot,
    ) {
        if (!current(p) || registry !== expected || detail != null || loading || busy != null) return
        confirmation = null
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
                    else { reportFailure("操作未确认，请刷新核对；不会自动重试。"); refresh() }
                }
            }
        } catch (_: Exception) { if (busy === ticket) busy = null; if (current(p)) reportFailure("请等待当前运行或数据操作结束。") }
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
                // Pause revokes execution authority, not the user's page or in-memory draft.
                serial++
                resumed = false; permit.revoke(); detail?.revoke(); creating?.balanceTestJob?.cancel()
                creating?.let { it.draft = it.draft.copy(protocolChangeAuthorized = false,
                    requestOptionsReplacementAuthorized = false, headersReplacementAuthorized = false) }
                clearTransientPrompts(); checkingRegistry = false
            } else if (event == Lifecycle.Event.ON_RESUME) {
                permit = ProviderPagePermit(); resumed = true
                pendingOpen = true
            }
        }
        lifecycle.addObserver(observer)
        onDispose { oauthAttempt = null; discardOAuth(oauthResult); oauthResult = null; pausedDraft = null; visit.set(false); permit.revoke(); detail?.revoke(); creating?.revoke(); lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumed, permit, oauthReturned) {
        if (resumed && current(permit) && oauthReturned) {
            val auth = oauthResult
            val sameRegistry = withContext(Dispatchers.IO) { runCatching { repo.snapshot().revision == oauthRegistryRevision }.getOrDefault(false) }
            if (auth != null && oauthAttempt != null && sameRegistry && current(permit)) {
                authentication = true; creating = NewProviderDraft(ProviderProtocol.RESPONSES, oauth = auth)
                pendingOpen = false
            } else { discardOAuth(auth); notice = "OAuth 登录已取消或页面已变化，请重试。" }
            // Clear the effect key only after the final suspension and credential hand-off.
            oauthResult = null; oauthReturned = false; oauthAttempt = null; oauthRegistryRevision = null
        }
    }
    LaunchedEffect(resumed, permit, busy, initialSlot, pendingOpen, oauthAttempt, oauthReturned) {
        if (current(permit) && busy == null && oauthAttempt == null && !oauthReturned) {
            val registered = creating?.registration?.registeredSlot
            if (registered != null) {
                creating?.revoke(); creating = null; authentication = false; pendingOpen = false
                open(registered)
                return@LaunchedEffect
            }
            if (!pendingOpen) return@LaunchedEffect
            val restore = pausedDraft
            pendingOpen = false
            if (restore != null) open(restore.editor.slotId, restore)
            else if (openingSlot != null) open(checkNotNull(openingSlot))
            else if (initialSlot == null) refresh() else open(initialSlot)
        }
    }
    fun back() {
        if (selectedSlots != null) { clearTransientPrompts(); return }
        oauthAttempt = null; oauthRegistryRevision = null; oauthReturned = false
        discardOAuth(oauthResult); oauthResult = null
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
        BackHandler(previewEnabled = selectedSlots == null && (!loading || initialSlot == ProviderRegistry.PRIMARY)) { back() }
        val p = permit
        val enabled = version.let { current(p) && controller.ready && controller.active == null &&
            !controller.dataBusy && !controller.hasLiveChildTasks() && busy == null && !loading && !checkingRegistry &&
            (detail == null || detail?.live?.get() == true) }
        val modelScrollOwner = detail?.pageUi ?: pausedDraft?.pageUi
        val modelScrollBehavior = key(modelScrollOwner) { MiuixScrollBehavior() }
        val d = detail
        val newProvider = creating
        if (newProvider != null) key(newProvider) {
            val draft = newProvider.draft
            var modelTab by newProvider.pageUi.modelTab
            val tabs: LazyListScope.() -> Unit = {
                item("provider-tabs") { ProviderSettingsTabs(modelTab, enabled) {
                    if (current(p) && enabled && creating === newProvider) modelTab = it
                } }
            }
            if (modelTab) ProviderEmptyModelsPage("添加提供商", { back() }, tabs,
                "请先在配置页保存提供商配置，再添加或拉取模型。",
                listState = newProvider.pageUi.modelListState, queryState = newProvider.pageUi.modelQuery)
            else ModelSettingsScreen(draft, { if (current(p) && enabled && creating === newProvider && newProvider.draft === draft) {
                newProvider.balanceTestJob?.cancel(); newProvider.balanceTestJob = null; newProvider.error = null; newProvider.draft = it
            } },
                onSave = { submitted ->
                    val expected = registry
                    if (current(p) && enabled && creating === newProvider && newProvider.draft === submitted && expected != null) {
                        val value = runCatching {
                            ProviderRegistry.requireValidName(submitted.providerName.orEmpty().trim())
                            require(!newProvider.speechOnly || app.daiyu.android.voice.providers.DoubaoSpeech.isOpenspeech(submitted.endpoint))
                            submitted.providerBalanceConfiguration()?.validateProviderBinding()
                            require(!submitted.balanceOption.enabled || submitted.providerBalanceConfiguration() != null)
                            require(submitted.headerFieldsValid && submitted.bodyFieldsValid)
                            submitted.providerSettings(ModelSettings("", "", "", false)).also { it.validateConnection() }
                        }
                        if (value.isFailure) newProvider.error = "未保存，请检查名称、连接配置及密钥。"
                        else mutate(expected, p, completed = { result ->
                            val added = result.providers.single { candidate -> expected.providers.none { it.slotId == candidate.slotId } }
                            open(added.slotId)
                        }, reportFailure = { message ->
                            if (creating === newProvider) newProvider.error = message
                        }) { repo, revision, admit ->
                            newProvider.registration.beginSave()
                            val created = try {
                                repo.registerConfigured(revision, submitted.providerName.orEmpty().trim(), value.getOrThrow(),
                                    submitted.protocol != submitted.savedProtocol, enabled = submitted.providerEnabled, admit = admit)
                                    .also { result ->
                                        val added = result.providers.single { candidate -> expected.providers.none { it.slotId == candidate.slotId } }
                                        newProvider.registration.registered(added.slotId)
                                    }
                            } finally { newProvider.registration.finishSave() }
                            submitted.providerBalanceConfiguration()?.let {
                                val added = created.providers.single { candidate -> expected.providers.none { it.slotId == candidate.slotId } }
                                val runtime = kotlinx.coroutines.runBlocking { controller.providerBalances.get(added.slotId) }
                                repo.withEditor(repo.editor(added.slotId)) { admit(); kotlinx.coroutines.runBlocking { runtime.save(checkNotNull(submitted.providerBalanceConfiguration(added.slotId))) } }
                            }
                            created
                        }
                    }
                }, onFetchCatalog = {}, onBack = { back() }, pageTitle = "添加提供商", providerName = "",
                headerContent = tabs, showCatalog = false, enabled = enabled, errorText = newProvider.error,
                listState = newProvider.pageUi.configurationListState, speechOnly = newProvider.speechOnly,
                testBalance = { value, requestCancelled, deliver ->
                    val configuration = value.providerBalanceConfiguration()
                    if (!current(p) || !enabled || creating !== newProvider || newProvider.draft !== value ||
                        requestCancelled() || configuration == null || !configuration.queryEnabled ||
                        runCatching { configuration.validateProviderBinding() }.isFailure) false
                    else {
                        newProvider.balanceTestJob?.cancel()
                        newProvider.balanceTestJob = scope.launch {
                            val result = try { controller.providerBalances.queryDraft(configuration) }
                            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) { app.daiyu.android.balance.BalanceResult.Failed(app.daiyu.android.balance.BalanceFailure.NETWORK) }
                            if (current(p) && creating === newProvider && newProvider.draft === value && !requestCancelled()) deliver(result)
                        }
                        true
                    }
                }, testConnection = { value, cancelled, deliver ->
                    if (!current(p) || !enabled || creating !== newProvider || newProvider.draft !== value || cancelled()) false
                    else runCatching { value.providerSettings(ModelSettings("", "", "", false)) }.getOrNull()?.let { settings ->
                        controller.testModelConnection(settings,
                            { !current(p) || creating !== newProvider || newProvider.draft !== value || cancelled() }) {
                            if (current(p) && creating === newProvider && newProvider.draft === value && !cancelled()) deliver(it)
                        }
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
                        require(!app.daiyu.android.voice.providers.DoubaoSpeech.isOpenspeech(saved.endpoint) ||
                            app.daiyu.android.voice.providers.DoubaoSpeech.isOpenspeech(submitted.endpoint)) { "请使用豆包语音服务地址" }
                        require(submitted.headerFieldsValid && submitted.bodyFieldsValid) { "请先修正请求头或请求体字段。" }
                        require(submitted.savedProtocol == saved.protocol) { "编辑基线已变化，请重新打开详情。" }
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
                        d.error = "未保存，请检查输入和必要确认。"
                    } else {
                        d.error = null
                        val ticket = Any(); busy = ticket
                        try {
                            controller.saveProviderSettings(d.editor, value.getOrThrow(), d.editing,
                                submitted.protocol != saved.protocol, submitted.requestOptionsReplacementAuthorized, d.saveOwner,
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
                        } catch (_: Exception) { if (busy === ticket) busy = null; if (live()) d.error = "当前不可保存；未重试。" }
                    }
                }
            }
            val change: (ModelSettingsDraftUi) -> Unit = { if (live() && enabled && d.draft === draft) {
                d.balanceTestJob?.cancel(); d.balanceTestJob = null; d.error = null; d.draft = it
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
                catch (_: IllegalArgumentException) { d.error = "自定义请求头无效，请检查后重试。"; null }
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
                    confirmation = ProviderConfirm("删除提供商", "确定删除“${d.name}”？若为当前提供商，将清空当前选择，不会自动切换。", true, deletionAppearance = true) {
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
                    catalogEnabled = false, errorText = d.error?.takeIf { d.live.get() }, testBalance = balanceTest, onDelete = delete,
                    listState = d.pageUi.configurationListState,
                    speechOnly = app.daiyu.android.voice.providers.DoubaoSpeech.isOpenspeech(d.editing.settings.endpoint))
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
                enabled = enabled, engineeringUi = engineeringUi, errorText = d.error?.takeIf { d.live.get() },
                credentialUnavailable = d.editing.credentialUnavailable, requestOptionsUnavailable = d.editing.requestOptionsUnavailable,
                headersUnavailable = d.editing.headersUnavailable,
                testConnection = connection, localModelActions = actions, testBalance = balanceTest,
                onDelete = delete, pageUi = d.pageUi, modelScrollBehavior = modelScrollBehavior,
                speechOnly = app.daiyu.android.voice.providers.DoubaoSpeech.isOpenspeech(d.editing.settings.endpoint))
        } else if (loading) {
            SettingsScaffoldPage("模型提供商", { back() }) { item { SettingsNote("正在读取提供商详情") } }
        } else if (authentication) {
            ProviderAuthenticationPage(enabled && oauthAttempt == null, notice, onBack = { back() }, onApiKey = {
                if (current(p) && enabled) creating = NewProviderDraft(ProviderProtocol.RESPONSES)
            }, onOAuth = {
                if (enabled && current(p) && oauthAttempt == null) {
                    oauthRegistryRevision = registry?.revision
                    oauthAttempt = java.util.UUID.randomUUID().toString()
                    try { oauthLauncher.launch(android.content.Intent(context, app.daiyu.android.model.oauth.CodexLoginActivity::class.java)
                        .putExtra("attempt", oauthAttempt)) }
                    catch (_: Exception) { oauthAttempt = null; oauthRegistryRevision = null; notice = "无法打开 OAuth 登录" }
                }
            })
        } else {
            val displayedRegistry = registry
            val selection = selectedSlots
            ProviderDirectoryPage(registry, summaries, query, { query = it }, enabled && registry != null,
                notice, onBack = { back() },
                onAddOpenAi = { if (current(p) && enabled) authentication = true },
                onAddAnthropic = { if (current(p) && enabled) creating = NewProviderDraft(ProviderProtocol.MESSAGES) },
                onAddSpeech = { if (current(p) && enabled) creating = NewProviderDraft(ProviderProtocol.CHAT_COMPLETIONS, speechOnly = true) },
                onOpen = { if (current(p) && enabled) open(it.slotId) },
                onSelect = { if (current(p) && enabled && registry === displayedRegistry && selectedSlots == null) selectedSlots = setOf(it.slotId) },
                selectedSlots = selectedSlots,
                onSelectionChange = { if (current(p) && enabled && selection != null && selectedSlots === selection) selectedSlots = it },
                onCancelSelection = { if (current(p) && enabled && selectedSlots === selection) clearTransientPrompts() },
                onDeleteSelected = {
                    val expected = displayedRegistry
                    val selected = selection
                    if (current(p) && enabled && registry === expected && selectedSlots === selected && expected != null && !selected.isNullOrEmpty()) {
                        confirmation = ProviderConfirm("删除提供商", "确定删除选中的 ${selected.size} 个提供商？若包含当前提供商，将清空当前选择，不会自动切换。", true, deletionAppearance = true) {
                            if (selectedSlots === selected) mutate(expected, p, completed = {
                                selectedSlots = null; refresh()
                            }) { repo, revision, admit -> repo.deleteSelected(revision, selected, admit) }
                        }
                    }
                }, modifier = Modifier.testTag("provider-management"), listState = directoryListState)
        }
        if (d != null && !d.live.get()) d.error?.let { message ->
            val dismissError: () -> Unit = {
                if (detail === d && d.error == message) d.error = null
            }
            DaiyuConfirmDialog("提供商设置", message, "知道了", false,
                onConfirm = dismissError, onDismiss = dismissError)
        }
        val confirm = confirmation
        if (confirm != null) DaiyuConfirmDialog(confirm.title, confirm.message, "确认", confirm.destructive, deletionAppearance = confirm.deletionAppearance,
            onConfirm = { if (confirmation === confirm && current(p) && enabled) { confirmation = null; confirm.action() } },
            onDismiss = { if (confirmation === confirm) confirmation = null })
    }
}
