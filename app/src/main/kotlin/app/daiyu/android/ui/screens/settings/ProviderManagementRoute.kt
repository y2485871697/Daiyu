package app.daiyu.android.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.ChatController
import app.daiyu.android.ModelSettingsSaveOwner
import app.daiyu.android.model.catalog.CatalogLocalEditResult
import app.daiyu.android.model.catalog.CatalogModelParameters
import app.daiyu.android.model.catalog.SavedModelCatalogPersistence
import app.daiyu.android.storage.*
import app.daiyu.android.ui.components.DaiyuConfirmDialog
import app.daiyu.android.ui.components.DaiyuTextInputDialog
import app.daiyu.android.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import top.yukonga.miuix.kmp.basic.TextButton

internal val RetainedProviderConnectionTest =
    SemanticsPropertyKey<Pair<ModelSettingsDraftUi, ModelConnectionTester>>("RetainedProviderConnectionTest")

private class ProviderPagePermit {
    val live = AtomicBoolean(true)
    val save = ModelSettingsSaveOwner()
    fun revoke() { live.set(false); save.revoke() }
}
private class ProviderDetail(
    val editor: ProviderEditorSlot, val name: String, val editing: EditingSettings,
    val configurationRevision: Long,
    val persistence: SavedModelCatalogPersistence<CatalogModelParameters>, val catalogIdentity: String?,
) {
    val live = AtomicBoolean(true)
    var draft by mutableStateOf(editing.settings.providerDraft())
}
private class PausedProviderDraft(
    val editor: ProviderEditorSlot, val editing: EditingSettings, val configurationRevision: Long,
    val draft: ModelSettingsDraftUi,
)
private class ProviderPrompt(val registration: RegisteredProvider?)
private class ProviderConfirm(val title: String, val message: String, val destructive: Boolean, val action: () -> Unit)
private fun ModelSettings.providerDraft() = ModelSettingsDraftUi(protocol = protocol, endpoint = endpoint,
    responsesPath = responsesPath, apiKey = apiKey, model = model, batteryEnabled = batteryEnabled,
    deviceStatusEnabled = deviceStatusEnabled, customBody = requestOptions.customBody,
    speedMode = requestOptions.speedMode, codex = requestOptions.codex,
    anthropicVersion = requestOptions.anthropicVersion.orEmpty(),
    openAiWebSearchEnabled = requestOptions.openAiWebSearchEnabled,
    customHeaders = if (customHeaders.isEmpty) "" else customHeaders.encode())
private fun ModelSettingsDraftUi.providerSettings(base: ModelSettings) = base.copy(endpoint = endpoint.trim(),
    responsesPath = responsesPath.trim(), model = model.trim(), apiKey = apiKey, protocol = protocol,
    batteryEnabled = batteryEnabled, deviceStatusEnabled = deviceStatusEnabled,
    customHeaders = app.daiyu.android.model.ProviderHeaders.parse(customHeaders),
    requestOptions = base.requestOptions.copy(customBody = customBody, speedMode = speedMode, codex = codex,
        anthropicVersion = anthropicVersion.takeIf { it.isNotEmpty() }, openAiWebSearchEnabled = openAiWebSearchEnabled))

@Composable
internal fun ProviderManagementRoute(controller: ChatController, ownerCurrent: () -> Boolean,
    onBack: () -> Unit, onDetailBack: () -> Unit = onBack, engineeringUi: Boolean = false,
    initialSlot: String? = null, pageTitleOverride: String? = null,
) {
    val repo = controller.providers
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ownerCheck by rememberUpdatedState(ownerCurrent)
    val visit = remember { AtomicBoolean(true) }
    var permit by remember { mutableStateOf(ProviderPagePermit()) }
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var registry by remember { mutableStateOf<ProviderRegistrySnapshot?>(null) }
    var detail by remember { mutableStateOf<ProviderDetail?>(null) }
    var pausedDraft by remember { mutableStateOf<PausedProviderDraft?>(null) }
    var pendingOpen by remember { mutableStateOf(true) }
    var serial by remember { mutableStateOf(0L) }
    var busy by remember { mutableStateOf<Any?>(null) }
    var loading by remember { mutableStateOf(false) }
    var checkingRegistry by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf<ProviderPrompt?>(null) }
    var confirmation by remember { mutableStateOf<ProviderConfirm?>(null) }
    var version by remember { mutableStateOf(0L) }
    fun current(p: ProviderPagePermit) = visit.get() && p.live.get() && permit === p && resumed &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && ownerCheck()
    fun clearPrompts() { prompt = null; confirmation = null }
    fun closeDetail() { serial++; loading = false; detail?.live?.set(false); detail = null; clearPrompts() }
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
                value to detailCurrent
            } }
            if (!current(p) || serial != request) return@launch
            checkingRegistry = false
            result.fold(onSuccess = { (value, detailCurrent) ->
                registry = value
                if (!detailCurrent && detail === previousDetail) {
                    closeDetail(); notice = "提供商配置已变化，请重新打开详情。"
                }
            }, onFailure = { closeDetail(); notice = "提供商列表读取失败；未创建替代配置。" })
        }
    }
    fun open(slot: String, restore: PausedProviderDraft? = null) {
        val p = permit
        if (!current(p) || busy != null) return
        if (restore == null) pausedDraft = null
        closeDetail(); checkingRegistry = false; loading = true; notice = null
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
                ProviderDetail(editor, registration.name, editing, configurationRevision, repo.catalogPersistence(editor), identity)
            } }
            if (!current(p) || serial != request) return@launch
            loading = false
            result.fold(onSuccess = { loaded ->
                if (restore != null && restore.editor == loaded.editor && restore.editing == loaded.editing &&
                    restore.configurationRevision == loaded.configurationRevision) {
                    loaded.draft = restore.draft.copy(protocolChangeAuthorized = false, requestOptionsReplacementAuthorized = false, headersReplacementAuthorized = false)
                }
                pausedDraft = null
                detail = loaded
            }, onFailure = { pausedDraft = null; notice = "详情读取失败或已变化，请刷新后重新打开。" })
        }
    }
    fun mutate(expected: ProviderRegistrySnapshot, p: ProviderPagePermit,
        action: (ProviderRepository, Long, () -> Unit) -> ProviderRegistrySnapshot,
    ) {
        if (!current(p) || registry !== expected || detail != null || loading || busy != null) return
        clearPrompts()
        val ticket = Any(); busy = ticket
        try {
            controller.mutateProviders(expected.revision, p.save, action) { result ->
                if (busy === ticket) busy = null
                if (current(p)) {
                    if (result != null) { registry = result; notice = "提供商列表已更新" }
                    else { notice = "操作未确认，请刷新核对；不会自动重试。"; refresh() }
                }
            }
        } catch (_: Exception) { if (busy === ticket) busy = null; if (current(p)) notice = "请等待当前运行或数据操作结束。" }
    }
    DisposableEffect(controller) {
        val listener: () -> Unit = { version++; if (busy == null && !loading) refresh() }
        controller.observe(listener)
        onDispose { controller.unobserve(listener) }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                detail?.let {
                    pausedDraft = PausedProviderDraft(it.editor, it.editing, it.configurationRevision, it.draft)
                }
                resumed = false; permit.revoke(); closeDetail(); checkingRegistry = false
            } else if (event == Lifecycle.Event.ON_RESUME) {
                permit = ProviderPagePermit(); resumed = true
                pendingOpen = true
            }
        }
        lifecycle.addObserver(observer)
        onDispose { pausedDraft = null; visit.set(false); permit.revoke(); detail?.live?.set(false); lifecycle.removeObserver(observer) }
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
        pausedDraft = null; pendingOpen = false
        if (detail != null || loading) {
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
    BackHandler { back() }
    val p = permit
    val enabled = version.let { current(p) && controller.ready && controller.active == null &&
        !controller.dataBusy && !controller.hasLiveChildTasks() && busy == null && !loading && !checkingRegistry }
    val d = detail
    if (d != null) key(d) {
        val live = { current(p) && detail === d && d.live.get() }
        val cancelled = { !p.live.get() || !d.live.get() }
        val draft = d.draft
        val saved = d.editing.settings
        val detailTitle = pageTitleOverride ?: if (d.editor.slotId == ProviderRegistry.PRIMARY) "模型设置" else d.name
        val save: (ModelSettingsDraftUi) -> Unit = { submitted ->
            if (live() && enabled && d.draft === submitted) {
                val value = runCatching {
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
                    submitted.providerSettings(saved).also { it.validate() }
                }
                if (value.isFailure) {
                    notice = "未保存，请检查输入和必要确认。"
                } else {
                    val ticket = Any(); busy = ticket
                    try {
                        controller.saveProviderSettings(d.editor, value.getOrThrow(), d.editing,
                            submitted.protocolChangeAuthorized, submitted.requestOptionsReplacementAuthorized, p.save,
                            headersReplacementAuthorized = submitted.headersReplacementAuthorized) { success ->
                            if (busy === ticket) busy = null
                            if (live()) {
                                if (success && d.editor.slotId == ProviderRegistry.PRIMARY) {
                                    p.revoke(); onDetailBack()
                                } else if (success) open(d.editor.slotId)
                                else { closeDetail(); notice = "未保存，请重新打开详情核对。"; refresh() }
                            }
                        }
                    } catch (_: Exception) { if (busy === ticket) busy = null; if (live()) notice = "当前不可保存；未重试。" }
                }
            }
        }
        val change: (ModelSettingsDraftUi) -> Unit = { if (live() && enabled) d.draft = it }
        fun connectionSettings(value: ModelSettingsDraftUi): ModelSettings? = try { value.providerSettings(saved) }
            catch (_: IllegalArgumentException) { notice = "自定义请求头无效，请检查后重试。"; null }
        val connection: ModelConnectionTester = { value, requestCancelled, deliver ->
            if (!live() || !enabled || requestCancelled() || d.draft !== value) false
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
            }, select = { selection, requestCancelled, complete ->
                if (live() && enabled && !requestCancelled()) {
                    val ticket = Any(); busy = ticket
                    try {
                        controller.selectProviderCatalogModel(d.editor, saved, selection,
                            { cancelled() || requestCancelled() }) { success ->
                            if (busy === ticket) busy = null
                            if (live() && !requestCancelled()) {
                                complete(success)
                                if (success) open(d.editor.slotId)
                                else { closeDetail(); notice = "模型未切换，请重新打开详情核对。"; refresh() }
                            }
                        }
                    } catch (_: Exception) { if (busy === ticket) busy = null; complete(false) }
                } else complete(false)
            })
        if (d.catalogIdentity == null) ModelSettingsScreen(draft, change, save, {}, { back() },
            modifier = connectionModifier,
            pageTitle = detailTitle, enabled = enabled, engineeringUi = engineeringUi, testConnection = connection,
            credentialUnavailable = d.editing.credentialUnavailable, requestOptionsUnavailable = d.editing.requestOptionsUnavailable,
            headersUnavailable = d.editing.headersUnavailable,
            catalogEnabled = false, errorText = notice)
        else ProviderCatalogSettingsScreen(draft, change, save, { back() }, d.catalogIdentity, d.persistence,
            createModel = { CatalogModelParameters.newModel(it) }, fetchCatalog = { value, requestCancelled, deliver ->
                if (!live() || !enabled || requestCancelled() || d.draft !== value) false
                else connectionSettings(value)?.let { settings ->
                    controller.fetchModelCatalog(settings,
                        { cancelled() || requestCancelled() || d.draft !== value }) {
                        if (live() && !requestCancelled() && d.draft === value) deliver(it)
                    }
                } ?: false
            }, modifier = connectionModifier, pageTitle = detailTitle,
            // A normal list visit uses the configuration/model split for every provider.
            twoTabs = initialSlot != ProviderRegistry.PRIMARY, ownerCurrent = live,
            enabled = enabled, engineeringUi = engineeringUi, errorText = notice,
            credentialUnavailable = d.editing.credentialUnavailable, requestOptionsUnavailable = d.editing.requestOptionsUnavailable,
            headersUnavailable = d.editing.headersUnavailable,
            testConnection = connection, localModelActions = actions)
    } else {
        MiuixScaffoldPage("提供商", onBack = { back() }, modifier = Modifier.testTag("provider-management")) {
            item {
                notice?.let { SettingsNote(it, error = true) }
                if (loading) SettingsNote("正在读取提供商详情")
                TextButton(text = "添加提供商", enabled = enabled && registry != null,
                    modifier = Modifier.testTag("provider-add"), onClick = { if (enabled) prompt = ProviderPrompt(null) })
                TextButton(text = "刷新", enabled = !loading && busy == null,
                    modifier = Modifier.testTag("provider-refresh"), onClick = { if (current(p)) refresh() })
            }
            val expected = registry
            expected?.providers?.forEach { registration -> item(key = registration.slotId) {
                SettingsSectionTitle(registration.name)
                SettingsCard {
                    SettingsRow("配置与模型", summary = if (expected.activeSlotId == registration.slotId) "当前提供商" else "",
                        tag = "provider-open-${registration.slotId}", onClick = { if (enabled) open(registration.slotId) })
                    SettingsSwitchRow("启用", registration.enabled, enabled = enabled) { value ->
                        mutate(expected, p) { repo, revision, admit -> repo.setEnabled(revision, registration.slotId, value, admit) }
                    }
                    SettingsRow("设为当前提供商", tag = "provider-activate-${registration.slotId}",
                        onClick = if (enabled && registration.enabled && expected.activeSlotId != registration.slotId) ({
                            confirmation = ProviderConfirm("切换提供商", "后续新运行将使用此提供商的已保存配置。", false) {
                                mutate(expected, p) { repo, revision, admit -> repo.activate(revision, registration.slotId, admit) }
                            }
                        }) else null)
                    SettingsRow("重命名", tag = "provider-rename-${registration.slotId}",
                        onClick = if (enabled) ({ prompt = ProviderPrompt(registration) }) else null)
                    SettingsRow("删除提供商", tag = "provider-delete-${registration.slotId}",
                        onClick = if (enabled) ({ confirmation = ProviderConfirm("删除提供商", "将移除注册并撤销旧编辑入口；若为当前提供商则清空选择，不自动切换。不会安全擦除原加密配置。", true) {
                            mutate(expected, p) { repo, revision, admit -> repo.delete(revision, registration.slotId, admit) }
                        } }) else null)
                }
            } }
            if (expected?.providers?.isEmpty() == true) item { SettingsNote("尚无提供商，请添加并保存配置、启用后设为当前。") }
        }
    }
    val input = prompt
    val expected = registry
    if (input != null && expected != null) DaiyuTextInputDialog(
        title = if (input.registration == null) "添加提供商" else "重命名提供商", initial = input.registration?.name.orEmpty(),
        hint = "名称", confirmText = "保存", onConfirm = { raw ->
            if (prompt === input && current(p) && enabled) {
                val name = raw.trim()
                if (runCatching { ProviderRegistry.requireValidName(name) }.isSuccess) {
                    mutate(expected, p) { repo, revision, admit ->
                        input.registration?.let { repo.rename(revision, it.slotId, name, admit) } ?: repo.register(revision, name, admit)
                    }
                } else notice = "名称须为1至200字，且不能包含控制字符。"
            }
        }, onDismiss = { if (prompt === input) prompt = null })
    val confirm = confirmation
    if (confirm != null) DaiyuConfirmDialog(confirm.title, confirm.message, "确认", confirm.destructive,
        onConfirm = { if (confirmation === confirm && current(p) && enabled) { confirmation = null; confirm.action() } },
        onDismiss = { if (confirmation === confirm) confirmation = null })
}
