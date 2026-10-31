package app.daiyu.android

import app.daiyu.android.ui.navigation.PageBackStack
import android.net.Uri
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Toast
import app.daiyu.android.ui.navigation.PageBackHandler as BackHandler
import app.daiyu.android.ui.components.DaiyuConfirmDialog
import app.daiyu.android.ui.components.OptionSelectionSheet
import app.daiyu.android.ui.components.OptionSelectionItem
import app.daiyu.android.ui.screens.settings.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.voice.account.*
import app.daiyu.android.voice.providers.DoubaoPersonalVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import app.daiyu.android.ui.haptics.miuix.IconButton
import app.daiyu.android.ui.icons.ChatPreviewIcons
import app.daiyu.android.ui.icons.DaiyuIcon
import app.daiyu.android.ui.icons.DaiyuIcons
import app.daiyu.android.ui.screens.chat.conversation.ChatSelectionSheet
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Keeps a swallowed lease-stop failure fail-closed for the lifetime of this mounted port. */
internal class AccountLeafPreviewGuard(port: VoiceAccountPreviewPort) {
    private var stopFailed = false
    val stopUnconfirmed get() = stopFailed
    val preview = PersonalVoicePreview(VoiceAccountPreviewPort { request, listener ->
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!stopFailed) { "试听停止未确认，请退出账户页面后重试" }
        val lease = port.play(request, listener)
        VoiceAccountPreviewPort.Lease {
            try { lease.stop() }
            catch (failure: Throwable) { stopFailed = true; throw failure }
        }
    })

    suspend fun execute(
        current: () -> Boolean,
        stopPreviewStrictly: () -> Boolean = { false },
        block: suspend () -> Unit,
    ) {
        withContext(Dispatchers.Main.immediate) {
            check(current())
            // stop() is synchronous, including preparation. Do not wait for another play event.
            // PersonalVoicePreview absorbs stop exceptions; the wrapped lease latches them here.
            preview.stop()
            check(!stopFailed) { "试听停止未确认，已拒绝本次操作" }
            // The host must also prove strict output shutdown, including failures it swallowed itself.
            try { check(stopPreviewStrictly()) { "宿主未确认试听已严格停止，已拒绝本次操作" } }
            catch (failure: Throwable) { stopFailed = true; throw failure }
            check(current())
            block()
        }
    }
}

private data class AccountConfirmation(
    val label: String, val failureNotice: String?, val visit: Long,
    val action: suspend ((String) -> Unit) -> Unit,
)

/**
 * Independently mounted leaf. frozenConfiguration MUST be a saved runtime snapshot (not a draft).
 * Selection is only a callback; the parent decides whether/how to save a new configuration.
 * No network on render/load/select/picker return. Cloud catalog queries follow explicit taps; training requires confirmation;
 * demo playback only follows an explicit preview action.
 */
@Composable
internal fun VoiceAccountPanel(
    frozenConfiguration: VoiceConfiguration,
    resources: VoiceAccountResources,
    previewPort: VoiceAccountPreviewPort,
    isCurrent: (VoiceConfiguration) -> Boolean,
    onSelect: (VoiceConfiguration, DoubaoPersonalVoice) -> Unit,
    onBack: () -> Unit,
    // Synchronous main-thread host proof; false/throw must include any prior strict-stop failure.
    stopPreviewStrictly: () -> Boolean = { false },
    onAccountKeyChange: (suspend (String) -> Unit)? = null,
    onPreviewRequest: () -> Unit = {},
    onPageChanged: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val latestCurrent by rememberUpdatedState(isCurrent)
    val latestStrictStop by rememberUpdatedState(stopPreviewStrictly)
    val latestPageChanged by rememberUpdatedState(onPageChanged)
    val speech = remember(frozenConfiguration.revision) { runCatching { frozenConfiguration.speech() }.getOrNull() }
    val account = remember(speech) { speech?.let { runCatching { VoiceAccountIdentity.from(it) }.getOrNull() } }
    val allVoices by resources.voices.state.collectAsState()
    val voices = allVoices.filter { it.account == account?.fingerprint }
    val visibleVoices = voices.filter { it.showInMyVoices }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val previewGuard = remember(previewPort) { AccountLeafPreviewGuard(previewPort) }
    val preview = previewGuard.preview
    val previewState by preview.state.collectAsState()
    var loaded by remember(resources, frozenConfiguration.revision) { mutableStateOf(false) }
    var feedback by remember(frozenConfiguration.revision) { mutableStateOf(AccountPageFeedback()) }
    val notice = feedback.text
    fun clearPageFeedback() { preview.stop(); feedback = feedback.nextVisit(); latestPageChanged() }
    var busy by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var confirmation by remember(frozenConfiguration.revision) { mutableStateOf<AccountConfirmation?>(null) }
    var ak by remember(frozenConfiguration.revision) { mutableStateOf("") }
    var sk by remember(frozenConfiguration.revision) { mutableStateOf("") }
    var project by remember(frozenConfiguration.revision) { mutableStateOf("default") }
    var name by remember(frozenConfiguration.revision) { mutableStateOf("") }
    var existingId by remember(frozenConfiguration.revision) { mutableStateOf("") }
    var slotId by remember(frozenConfiguration.revision) { mutableStateOf("") }
    var manualSlotId by remember(frozenConfiguration.revision) { mutableStateOf("") }
    var postpaid by remember(frozenConfiguration.revision) { mutableStateOf(false) }
    var sampleUri by remember(frozenConfiguration.revision) { mutableStateOf<Uri?>(null) }
    var sampleName by remember(frozenConfiguration.revision) { mutableStateOf("") }
    val samplePicker = remember(resources) { AccountSamplePicker<Uri>() }
    var pickerPending by remember { mutableStateOf(false) }
    var pickerResult by remember { mutableIntStateOf(0) }
    var slotPicker by remember(frozenConfiguration.revision) { mutableStateOf(false) }
    var importSlotPicker by remember(frozenConfiguration.revision) { mutableStateOf(false) }
    var importCandidates by remember(frozenConfiguration.revision) { mutableStateOf(emptyList<PersonalVoices.Voice>()) }
    var projects by remember(frozenConfiguration.revision) { mutableStateOf(emptyList<VoiceProjectCatalog.Project>()) }
    fun current() = latestCurrent(frozenConfiguration) && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    val enabled = loaded && !busy && account != null && current() && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    fun execute(failureNotice: String? = null, block: suspend ((String) -> Unit) -> Unit) {
        if (!current() || busy) return
        feedback = feedback.nextVisit()
        val origin = feedback.visit
        fun operationCurrent() = current() && feedback.visit == origin
        confirmation = null; busy = true
        operation = scope.launch {
            val operationContext = currentCoroutineContext()
            fun report(message: String) { if (operationContext.isActive && current()) feedback = feedback.report(origin, message) }
            try {
                previewGuard.execute(::operationCurrent, { latestStrictStop() }) { block(::report) }
                currentCoroutineContext().ensureActive()
                if (operationCurrent() && feedback.text.isBlank()) report("操作完成")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (currentCoroutineContext().isActive && operationCurrent()) report(if (previewGuard.stopUnconfirmed)
                    "试听停止未确认，已拒绝本次操作。请退出账户页面并核对语音输出状态。"
                else voiceAccountFailure(failure, failureNotice))
            } finally { busy = false }
        }
    }
    fun ask(label: String, failureNotice: String? = null, action: suspend ((String) -> Unit) -> Unit) {
        if (enabled) confirmation = AccountConfirmation(label, failureNotice, feedback.visit, action)
    }
    DisposableEffect(lifecycle, frozenConfiguration.revision, preview) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                slotPicker = false; importSlotPicker = false; confirmation = null; operation?.cancel(); preview.stop()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer); operation?.cancel(); preview.stop()
            samplePicker.invalidate()
        }
    }
    LaunchedEffect(resources, frozenConfiguration.revision) {
        val origin = feedback.visit
        preview.stop(); confirmation = null; sampleUri = null
        try {
            withContext(Dispatchers.IO) { resources.voices.load() }
            loaded = true
            val credentials = account?.let { withContext(Dispatchers.IO) { resources.preferences.load(it.fingerprint) } }
            if (credentials != null) { ak = credentials.keyId; sk = credentials.secret; project = credentials.project }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { feedback = feedback.report(origin, "本机账户资料或控制面凭据不可读；请重新填写 AK 和 SK 后保存。") }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        samplePicker.deliver(uri); pickerResult++
    }
    LaunchedEffect(pickerResult, lifecycleState, frozenConfiguration.revision) {
        val uri = samplePicker.take(frozenConfiguration.revision,
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), latestCurrent(frozenConfiguration))
        pickerPending = samplePicker.pending
        if (uri != null) { sampleUri = uri; sampleName = "已选择录音"; confirmation = null }
    }
    LaunchedEffect(sampleUri, lifecycleState, frozenConfiguration.revision) {
        val uri = sampleUri ?: return@LaunchedEffect
        if (!current()) return@LaunchedEffect
        val label = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0)?.take(160) else null
                }
            }.getOrNull().orEmpty().ifBlank { "已选择录音" }
        }
        if (current() && sampleUri == uri) sampleName = label
    }
    var page by remember { mutableStateOf("LIST") }
    var parentPage by remember { mutableStateOf("LIST") }
    var step by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var manualId by remember { mutableStateOf(false) }
    var advancedCreation by remember { mutableStateOf(false) }
    var agreed by remember { mutableStateOf(false) }
    var projectPicker by remember { mutableStateOf(false) }
    var voiceMenu by remember { mutableStateOf<PersonalVoices.Voice?>(null) }
    var accountKey by remember(frozenConfiguration.revision) { mutableStateOf(frozenConfiguration.key) }
    val creationSlots = personalVoiceSlots(voices, creating = true, postpaid = false)
        .filter { it.id.matches(Regex("S_[A-Za-z0-9_-]+")) }
    val creationSlotId = manualSlotId.trim().ifBlank { slotId }
    val importSlots = importCandidates.filter { it.canImport }.sortedBy { it.name }
        .filter { it.id.matches(Regex("S_[A-Za-z0-9_-]+|etaClone[a-fA-F0-9]{32}")) }
    fun navigate(target: String) {
        if (!current() || busy) return
        clearPageFeedback()
        slotPicker = false; importSlotPicker = false; confirmation = null; menu = false; addMenu = false
        if (target == "SYNC" || target == "ACCOUNT") parentPage = page
        page = target
    }
    suspend fun refreshProjects(a: String, s: String, preferred: String): String {
        val origin = feedback.visit
        projects = emptyList(); projectPicker = false
        val fetched = resources.projects.list(a, s)
        currentCoroutineContext().ensureActive()
        check(current() && page == "SYNC" && ak == a && sk == s && feedback.visit == origin)
        val selected = VoiceProjectCatalog.selectProject(fetched, preferred)
        projects = fetched; project = selected.name
        return selected.name
    }
    suspend fun syncName(voice: PersonalVoices.Voice) {
        val saved = withContext(Dispatchers.IO) { resources.preferences.load(voice.account) }
        resources.voices.syncName(voice, speech!!, saved?.keyId.orEmpty(), saved?.secret.orEmpty(), saved?.project.orEmpty())
    }
    suspend fun trySyncName(voice: PersonalVoices.Voice): Boolean = try {
        syncName(voice); true
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }
    fun beginFlow(target: String) {
        if (!current() || busy) return
        name = ""; existingId = ""; slotId = ""; manualSlotId = ""; postpaid = false; sampleUri = null
        manualId = false; advancedCreation = false; agreed = false; step = 0
        samplePicker.invalidate(); pickerPending = false
        navigate(target)
    }
    fun back() {
        if (busy) { operation?.cancel(); feedback = feedback.report(feedback.visit, "正在停止等待，请稍候。"); return }
        clearPageFeedback()
        samplePicker.invalidate()
        slotPicker = false; importSlotPicker = false; confirmation = null; menu = false; addMenu = false; voiceMenu = null
        when {
            page == "SYNC" || page == "ACCOUNT" -> {
                page = parentPage
            }
            page == "CREATE" && step > 0 -> { step--; agreed = false }
            page != "LIST" -> page = "LIST"
            else -> { operation?.cancel(); onBack() }
        }
    }
    PageBackStack(when (page) {
        "LIST" -> null
        "CREATE" -> "CREATE:$step"
        else -> page
    }, when {
        page == "SYNC" || page == "ACCOUNT" -> when (parentPage) {
            "LIST" -> null
            "CREATE" -> "CREATE:$step"
            else -> parentPage
        }
        page == "CREATE" && step > 0 -> "CREATE:${step - 1}"
        else -> null
    }) {
        BackHandler { back() }
        val title = when (page) {
            "SYNC" -> "同步音色名额"; "ACCOUNT" -> "声音复刻账户"
            "IMPORT" -> "导入声音"; "CREATE" -> "制作声音"; else -> "我的声音"
        }
        VoicePersonalScaffold(title, ::back, enabled = !busy && current(),
            onAdd = if (page == "LIST") ({ if (current()) addMenu = true }) else null,
            onSettings = if (page == "LIST") ({ if (current()) menu = true }) else null) {
            if (page == "LIST") {
                if (visibleVoices.isEmpty()) item { SettingsNote("还没有声音。点击 + 制作或导入声音。") }
                items(visibleVoices.size, key = { visibleVoices[it].id }) { index ->
                    val voice = visibleVoices[index]
                    VoiceSettingsCard {
                        val playing = previewState.matches(voice.account, voice.id)
                        SettingsRow(voice.name, summary = voiceStatus(voice) + if (voice.nameSyncPending) " · 名称待同步" else "", showChevron = false,
                            onClick = if (!enabled || !voice.tts || !voice.accepted) null else ({
                                if (current() && !busy) {
                                    val selectedVoice = runCatching { resources.voices.selected(voice.id, speech!!) }.getOrNull()
                                    if (selectedVoice != null) { preview.stop(); onSelect(frozenConfiguration, selectedVoice) }
                                    else feedback = feedback.report(feedback.visit, "音色状态已变化，请查看状态后重试。")
                                }
                            }), trailing = {
                            IconButton(enabled = enabled && voice.tts && voice.demo.isNotBlank(),
                                modifier = Modifier.size(48.dp).testTag("voice-preview:${voice.id}"),
                                minHeight = 48.dp, minWidth = 48.dp, backgroundColor = Color.Transparent, onClick = {
                                    if (current() && !busy) {
                                        if (previewState.matches(voice.account, voice.id)) preview.stop()
                                        else { onPreviewRequest(); execute { check(current()); preview.toggle(speech!!, voice) } }
                                    }
                                }) {
                                DaiyuIcon(if (playing) DaiyuIcons.Stop else ChatPreviewIcons.Play,
                                    if (playing) { if (previewState.loading) "正在准备，点击停止" else "停止试听" } else "播放试听")
                            }
                            IconButton(enabled = enabled, modifier = Modifier.size(48.dp).testTag("voice-menu:${voice.id}"),
                                minHeight = 48.dp, minWidth = 48.dp, backgroundColor = Color.Transparent,
                                onClick = { if (current() && !busy) voiceMenu = voice }) {
                                DaiyuIcon(ChatPreviewIcons.More, "音色菜单", modifier = Modifier.rotate(90f))
                            }
                        })
                        if (voice.tts && !voice.accepted) SettingsRow("确认音色，允许正式合成", summary = "可能产生音色费用",
                            showChevron = true, onClick = if (!enabled) null else ({ ask("我有权使用此声音，并同意正式合成可能产生音色费用") {
                                withContext(Dispatchers.IO) { resources.voices.accept(voice) }
                            } }))
                    }
                }
            }
            if (page == "ACCOUNT") item {
                VoiceSettingsCard {
                    SettingsTextField("声音复刻 API Key", accountKey, { if (!busy) { accountKey = it; confirmation = null } },
                        password = true, enabled = !busy, compact = true)
                    VoiceAccountSaveButton("保存账户", "voice-account-save",
                        enabled = !busy && current() && accountKey.isNotBlank() && onAccountKeyChange != null, onClick = {
                            val key = accountKey
                            execute { onAccountKeyChange?.invoke(key) }
                        })
                }
                SettingsNote("声音复刻账户独立保存；API Key 与音色名额须属于同一项目。")
                VoiceSettingsCard { SettingsRow("打开豆包控制台", onClick = { if (current()) uriHandler.openUri("https://console.volcengine.com/speech/app") }) }
            }
            if (page == "SYNC") {
                item { SettingsCard(Modifier.testTag("voice-sync-credentials-card")) {
                    SettingsTextField("AK", ak, { if (!busy && current()) { ak = it; sk = ""; project = "default"; projects = emptyList(); projectPicker = false; confirmation = null; feedback = feedback.clear() } },
                        password = true, enabled = !busy, compact = true)
                    SettingsTextField("SK", sk, { if (!busy && current()) { sk = it; project = "default"; projects = emptyList(); projectPicker = false; confirmation = null; feedback = feedback.clear() } },
                        password = true, enabled = !busy, compact = true)
                } }
                item { SettingsCard(Modifier.testTag("voice-sync-project-card")) {
                    SettingsRow("当前项目", summary = project, showChevron = true, onClick = if (!enabled || ak.isBlank() || sk.isBlank()) null else ({
                        val a = ak; val s = sk; val p = project
                        execute("项目获取失败或没有可访问的项目，请核对 AK／SK 与项目权限后重试。") { report ->
                            refreshProjects(a, s, p)
                            projectPicker = true
                            report("请选择与语音 API Key 相同的项目。")
                        }
                    }))
                } }
                item { SettingsCard(Modifier.testTag("voice-sync-read-card")) {
                    SettingsRow("读取我的名额", showChevron = true, onClick = if (!enabled || ak.isBlank() || sk.isBlank() || project.isBlank()) null else ({
                        val a = ak; val s = sk; val p = project
                        ask("查询项目 $p 的槽位；已制作槽位将用当前语音 Key 校验，不制作、不合成",
                            "名额读取失败，请手动重试；若持续失败，请检查网络或本机存储。") { report ->
                            val count = resources.voices.importPurchased(speech!!, a, s, p)
                            report(if (count == 0) "本项目未找到名额；请核对项目与语音 API Key。" else "已同步 $count 个名额；未使用和已复刻的音色均可在制作声音时选择。")
                        }
                    }))
                } }
                item { SettingsCard(Modifier.testTag("voice-sync-storage-card")) {
                    VoiceAccountSaveButton("保存账户信息", "voice-sync-save",
                        enabled = enabled && ak.isNotBlank() && sk.isNotBlank(), onClick = {
                        val a = ak; val s = sk; val preferred = project
                        execute("账户保存未确认，请核对 AK／SK、项目访问权限及本机存储后重试。") { report ->
                            val p = refreshProjects(a, s, preferred)
                            withContext(Dispatchers.IO) {
                                currentCoroutineContext().ensureActive()
                                resources.preferences.save(account!!.fingerprint, a, s, p)
                            }
                            report("账户信息已加密保存在本机")
                        }
                    })
                } }
                item { SettingsCard(Modifier.testTag("voice-sync-console-card")) {
                    SettingsRow("打开火山访问密钥管理", onClick = { if (current()) uriHandler.openUri("https://console.volcengine.com/iam/keymanage/") })
                } }
            }
            if (page == "CREATE") item {
                SettingsSectionTitle("${step + 1} / 3")
                androidx.compose.material3.LinearProgressIndicator(progress = { (step + 1) / 3f },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            }
            if (page == "IMPORT") item {
                VoiceSettingsCard {
                    AccountField("备注名称（可空）", name, busy) { name = it.take(80); confirmation = null }
                    SettingsRow("选取我的名额", summary = importSlots.withIndex().firstOrNull { it.value.id == existingId }
                        ?.let { personalVoiceSlotTitle(it.value, it.index) + " · " + it.value.id } ?: existingId.takeIf { it.isNotBlank() },
                        showChevron = true, onClick = if (!enabled) null else ({
                            if (current() && !busy) {
                                importCandidates = emptyList(); importSlotPicker = true
                                if (ak.isNotBlank() && sk.isNotBlank() && project.isNotBlank()) {
                                    val a = ak; val s = sk; val p = project
                                    execute("已复刻名额查询失败，请检查账户与项目后重试。") { report ->
                                        val origin = feedback.visit
                                        val fetched = resources.voices.listImportable(speech!!, a, s, p)
                                        currentCoroutineContext().ensureActive()
                                        check(current() && page == "IMPORT" && importSlotPicker && feedback.visit == origin &&
                                            ak == a && sk == s && project == p)
                                        importCandidates = fetched
                                        report(if (fetched.isEmpty()) "云端暂无可导入的已复刻声音。" else "请选择已复刻的声音。")
                                    }
                                }
                            }
                        }))
                    SettingsRow("备用方式：从控制台复制 ID", onClick = if (busy) null else ({ manualId = !manualId }), trailing = {
                        DaiyuIcon(DaiyuIcons.ChevronRight, if (manualId) "收起" else "展开",
                            modifier = Modifier.settingsDisclosureRotation(manualId),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, size = 20.dp)
                    })
                    SettingsExpandableContent(manualId) {
                        SettingsNote("在豆包控制台进入声音复刻的“已复刻”列表，复制已复刻成功的音色 ID。API Key 与音色须属于同一项目。")
                        SettingsRow("打开豆包控制台", onClick = { if (current()) uriHandler.openUri("https://console.volcengine.com/speech/app") })
                        AccountField("已有音色 ID", existingId, busy) { existingId = it; confirmation = null }
                    }
                    SettingsRow("导入声音", showChevron = true, onClick = if (!enabled || !existingId.matches(Regex("(?:S_|etaClone)[A-Za-z0-9_-]+"))) null else ({
                        val id = existingId; val n = name
                        ask("用当前语音账户查询已有音色 $id；不上传、不制作") {
                            val origin = feedback.visit
                            resources.voices.importExisting(speech!!, id, n)
                            currentCoroutineContext().ensureActive()
                            check(current() && feedback.visit == origin)
                            clearPageFeedback(); page = "LIST"
                            feedback = feedback.report(feedback.visit, "声音已导入；没有重新制作。")
                        }
                    }))
                }
                SettingsNote("仅查询已有声音，不上传录音、不重新制作。备注名称最多 80 字。")
            }
            if (page == "CREATE" && step == 0) {
                item { SettingsSectionTitle("1. 选择名额") }
                item { SettingsCard(Modifier.testTag("voice-create-name-card")) {
                    AccountField("声音名称", name, busy) { name = it.take(80); confirmation = null }
                } }
                if (manualSlotId.isBlank()) item { SettingsCard(Modifier.testTag("voice-create-advanced-card")) {
                    SettingsRow("高级：新建后付费音色", onClick = if (!enabled) null else ({ advancedCreation = !advancedCreation }), trailing = {
                        DaiyuIcon(DaiyuIcons.ChevronRight, if (advancedCreation) "收起" else "展开",
                            modifier = Modifier.settingsDisclosureRotation(advancedCreation),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, size = 20.dp)
                    })
                    SettingsExpandableContent(advancedCreation) {
                        SettingsSwitchRow("新建后付费音色", postpaid, enabled = enabled) {
                            postpaid = it
                            if (it) { slotId = ""; manualSlotId = ""; manualId = false }
                            confirmation = null
                        }
                    }
                } }
                if (!postpaid && manualSlotId.isBlank()) item { SettingsCard(Modifier.testTag("voice-create-sync-card")) {
                    SettingsRow("选择音色名额", summary = creationSlots
                        .withIndex().firstOrNull { it.value.id == slotId }
                        ?.let { personalVoiceSlotTitle(it.value, it.index) + " · " + it.value.id } ?: slotId.takeIf { it.isNotBlank() },
                        showChevron = true, onClick = if (!enabled) null else ({
                            if (current() && !busy) {
                                if (resources.voices.state.value.any { it.account == account?.fingerprint &&
                                    it.id.matches(Regex("S_[A-Za-z0-9_-]+")) }) slotPicker = true
                                else navigate("SYNC")
                            }
                        }))
                } }
                if (!postpaid && slotId.isBlank()) item { SettingsCard(Modifier.testTag("voice-create-manual-card")) {
                    SettingsRow("备用方式：从控制台复制 ID", onClick = if (busy) null else ({ manualId = !manualId }), trailing = {
                        DaiyuIcon(DaiyuIcons.ChevronRight, if (manualId) "收起" else "展开",
                            modifier = Modifier.settingsDisclosureRotation(manualId),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, size = 20.dp)
                    })
                    SettingsExpandableContent(manualId) {
                        SettingsRow("打开豆包控制台", onClick = { if (current()) uriHandler.openUri("https://console.volcengine.com/speech/app") })
                        AccountField("名额 ID", manualSlotId, busy) { manualSlotId = it; slotId = ""; postpaid = false; confirmation = null }
                    }
                } }
                item { SettingsCard(Modifier.testTag("voice-create-next-sample-card")) {
                    SettingsRow("下一步：选择录音", showChevron = true, onClick = if (!enabled || name.isBlank() || (!postpaid && (!creationSlotId.matches(Regex("S_[A-Za-z0-9_-]+")) || voices.firstOrNull { it.id == creationSlotId }?.canTrain == false))) null else ({
                        if (postpaid) { clearPageFeedback(); step = 1; agreed = false }
                        else {
                            val id = creationSlotId; val a = ak; val s = sk; val p = project
                            execute("名额校验失败，请检查账户与项目后重试。") { report ->
                                val origin = feedback.visit
                                val remote = resources.voices.findTrainingSlot(speech!!, id, a, s, p)
                                currentCoroutineContext().ensureActive()
                                check(current() && page == "CREATE" && step == 0 && !postpaid &&
                                    creationSlotId == id && ak == a && sk == s && project == p && feedback.visit == origin)
                                val message = when {
                                    remote == null -> "没有该名额 ID"
                                    !remote.canTrain -> "该名额正在制作、已锁定或次数已用完"
                                    else -> null
                                }
                                if (message != null) {
                                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    report(message)
                                } else { clearPageFeedback(); step = 1; agreed = false }
                            }
                        }
                    }))
                } }
            }
            if (page == "CREATE" && step == 1) {
                item { SettingsSectionTitle("2. 选择录音") }
                item { SettingsCard(Modifier.testTag("voice-create-sample-card")) {
                    SettingsRow(if (sampleUri == null) "选择录音" else "重新选择录音", tag = "voice-training-sample",
                        summary = if (sampleUri == null) null else sampleName, showChevron = true, onClick = if (!enabled || pickerPending) null else ({
                            if (samplePicker.begin(frozenConfiguration.revision)) {
                                pickerPending = true
                                try { picker.launch("audio/*") }
                                catch (_: Exception) { samplePicker.deliver(null); pickerPending = samplePicker.pending; pickerResult++; feedback = feedback.report(feedback.visit, "未能打开录音选择器") }
                            }
                        }))
                } }
                item { SettingsCard(Modifier.testTag("voice-create-next-confirm-card")) {
                    SettingsRow("下一步：确认制作", showChevron = true, onClick = if (!enabled || sampleUri == null) null else ({ clearPageFeedback(); step = 2; agreed = false }))
                } }
            }
            if (page == "CREATE" && step == 2) {
                item { SettingsSectionTitle("3. 确认制作") }
                item { SettingsCard(Modifier.testTag("voice-create-summary-card")) {
                    SettingsRow("名称", summary = name)
                    SettingsRow(if (postpaid) "新建后付费音色" else "已有名额", summary = if (postpaid) null else creationSlotId)
                } }
                item { SettingsCard(Modifier.testTag("voice-create-consent-card")) {
                    SettingsSwitchRow("我有权使用此录音并同意本次上传制作", agreed, enabled = enabled) { agreed = it }
                } }
                item { SettingsCard(Modifier.testTag("voice-create-submit-card")) {
                    SettingsRow("确认上传并制作", tag = "voice-training-submit", showChevron = true, onClick = if (!enabled || !agreed || sampleUri == null) null else ({
                        val uri = sampleUri!!; val n = name; val slot = if (postpaid) null else creationSlotId
                        ask("上传所选录音并进行一次${if (slot == null) "后付费自定义" else "名额 $slot"}制作，可能消耗次数及费用。${if (slot != null && voices.any { it.id == slot && !it.unused }) "将消耗此名额的剩余复刻次数并覆盖已有声音，不占用新名额。" else ""}结果未知时只查询原音色，不重复提交。${if (slot == null) "\n\n须在同一项目另行开通后付费音色服务。首次正式合成可能产生音色费用，未正式使用的音色可能七天后删除。请核对费用与有效期后再制作。" else ""}", "制作结果未确认，请在原音色菜单查询状态，不要重复提交。") { report ->
                            val origin = feedback.visit
                            val sample = try { PersonalVoices.readSample(context, uri) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) {
                                report("录音读取失败，请检查文件格式、大小与访问权限；本次未提交制作。")
                                return@ask
                            }
                            try {
                                currentCoroutineContext().ensureActive()
                                if (!current() || feedback.visit != origin) throw CancellationException("account_page_changed")
                                val id = resources.voices.create(speech!!, sample, n, slot, confirmed = true)
                                val created = resources.voices.find(id, speech!!)
                                val nameSynced = created?.takeIf { it.nameSyncPending && !it.uncertain }?.let { trySyncName(it) }
                                currentCoroutineContext().ensureActive()
                                check(current() && feedback.visit == origin)
                                clearPageFeedback(); page = "LIST"; step = 0
                                feedback = feedback.report(feedback.visit, when (nameSynced) {
                                    true -> "声音已提交，名称已同步到云端；请在原音色菜单查询制作状态。"
                                    false -> "声音已提交，名称同步失败；可在原音色菜单重试名称同步，不要重新制作。"
                                    null -> "已提交制作，请在原音色菜单查询状态，不要重复提交。"
                                })
                            } finally { sample.clear(); sampleUri = null; agreed = false }
                        }
                    }))
                } }
            }
            if (!latestCurrent(frozenConfiguration)) item { SettingsNote("配置已变化，请退出后重新打开。", error = true) }
            if (notice.isNotBlank()) item { SettingsNote(notice) }
            previewState.error?.let { value -> item { SettingsNote(value, error = true) } }
        }
        if (menu) VoiceAccountSettingsSheet(
            onSelect = { navigate(it) }, onDismiss = { menu = false }, isEnabled = { !busy && current() })
        if (addMenu) VoiceAddSheet(
            onSelect = { beginFlow(it) }, onDismiss = { addMenu = false }, isEnabled = { !busy && current() })
        if (slotPicker && page == "CREATE" && step == 0) OptionSelectionSheet(
            "选择音色名额", onDismiss = { slotPicker = false }, modifier = Modifier.testTag("voice-create-slot-picker"), itemSpacing = 8.dp) {
            creationSlots.forEachIndexed { index, voice ->
                OptionSelectionItem(personalVoiceSlotTitle(voice, index) + "\n" + voice.id + " · " + voiceSlotSummary(voice),
                    selected = !postpaid && slotId == voice.id,
                    enabled = enabled && voice.canTrain, cardStyle = true,
                    modifier = Modifier.testTag("voice-create-slot:${voice.id}")) {
                    if (enabled && !busy && current() && page == "CREATE" && step == 0 &&
                        resources.voices.state.value.any { it.account == account?.fingerprint && it.id == voice.id && it.canTrain }) {
                        slotId = voice.id; manualSlotId = ""; postpaid = false; manualId = false; confirmation = null; slotPicker = false
                    }
                }
            }
        }
        if (importSlotPicker && page == "IMPORT") OptionSelectionSheet(
            "选取我的名额", onDismiss = { importSlotPicker = false; operation?.cancel() },
            modifier = Modifier.testTag("voice-import-slot-picker"), itemSpacing = 8.dp) {
            when {
                busy -> SettingsNote("正在查询云端已复刻声音…")
                ak.isBlank() || sk.isBlank() || project.isBlank() -> {
                    SettingsNote("请先保存同步账户信息，以查询云端已复刻的名额。")
                    SettingsRow("同步音色名额", showChevron = true, onClick = if (!enabled) null else ({ navigate("SYNC") }))
                }
                importSlots.isEmpty() -> SettingsNote(notice.ifBlank { "云端暂无可导入的已复刻声音。" })
            }
            importSlots.forEachIndexed { index, voice ->
                OptionSelectionItem(personalVoiceSlotTitle(voice, index) + "\n" + voice.id + " · " + (if (voice.status == 4) "已激活" else "复刻成功"),
                    selected = existingId == voice.id, enabled = enabled, cardStyle = true,
                    modifier = Modifier.testTag("voice-import-slot:${voice.id}")) {
                    if (enabled && !busy && current() && page == "IMPORT" &&
                        importCandidates.any { it.account == account?.fingerprint && it.id == voice.id && it.canImport }) {
                        existingId = voice.id; confirmation = null; importSlotPicker = false
                    }
                }
            }
        }
        if (projectPicker) ChatSelectionSheet("选择项目", onDismiss = { projectPicker = false },
            showCloseButton = false, titleFontSize = 20.sp) {
            val finishSelection = app.daiyu.android.ui.components.rememberBottomSheetAction()
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                projects.forEach { item -> SettingsRadioRow(item.displayName, project == item.name,
                    enabled = item.allowed && !busy, summary = listOfNotNull(
                        item.name.takeIf { it != item.displayName },
                        "无此项目访问权限".takeIf { !item.allowed },
                    ).joinToString(" · ").ifBlank { null }) {
                    finishSelection {
                        if (current() && !busy && page == "SYNC" && projects.any { it.name == item.name && it.allowed }) {
                            project = item.name; projectPicker = false; confirmation = null; feedback = feedback.clear()
                        }
                    }
                } }
            }
        }
        voiceMenu?.let { pending ->
            voices.firstOrNull { it.account == pending.account && it.id == pending.id }
        }?.let { voice ->
            VoiceChoiceDialog(voice.name, listOf("QUERY" to if (voice.nameSyncPending && !voice.uncertain && voice.tts) "重试名称同步" else "查看状态", "REMOVE" to "移除本机记录"), "",
                onSelect = { action ->
                    voiceMenu = null
                    val latestVoice = resources.voices.state.value.firstOrNull { it.account == voice.account && it.id == voice.id }
                    if (enabled && current() && latestVoice != null) when (action) {
                        "QUERY" -> ask(if (latestVoice.nameSyncPending && !latestVoice.uncertain && latestVoice.tts)
                            "仅同步原音色 ${latestVoice.id} 的名称，不上传、不重新制作"
                            else "查询原音色 ${latestVoice.id}，有待同步名称时一并同步；不重复制作") { report ->
                            if (!latestVoice.nameSyncPending || latestVoice.uncertain || !latestVoice.tts) resources.voices.refresh(latestVoice, speech!!)
                            resources.voices.find(latestVoice.id, speech!!)?.takeIf { it.nameSyncPending && !it.uncertain }?.let {
                                report(if (trySyncName(it)) "名称已同步到云端；没有重新制作。"
                                    else "名称同步失败；请检查已保存的 AK／SK 与项目后重试，不要重新制作。")
                            }
                        }
                        "REMOVE" -> if (!latestVoice.uncertain) ask("仅移除本机记录，不删除云端音色或任务，不退款、不释放名额") {
                            preview.stop(); withContext(Dispatchers.IO) { resources.voices.removeLocal(latestVoice) }
                        }
                    }
                }, onDismiss = { voiceMenu = null },
                isEnabled = { action -> enabled && (action != "REMOVE" || !voice.uncertain) })
        }
        confirmation?.let { pending ->
            DaiyuConfirmDialog("确认本次操作", pending.label, "确认", false,
                onConfirm = { if (confirmation === pending && enabled && feedback.visit == pending.visit) execute(pending.failureNotice, block = pending.action) }, onDismiss = { confirmation = null })
        }
    }
}

@Composable
private fun AccountField(label: String, value: String, readOnly: Boolean, secret: Boolean = false, onChange: (String) -> Unit) {
    SettingsTextField(label, value, onChange, password = secret, enabled = !readOnly)
}

internal fun voiceStatus(voice: PersonalVoices.Voice): String = when {
    voice.uncertain -> "提交结果未知，请查询原音色，不要重新制作"
    voice.catalogState == "Expired" -> "已到期"
    voice.catalogState == "Reclaimed" -> "已回收"
    voice.status == 1 || voice.catalogState == "Training" -> "训练中"
    voice.status == 3 -> "训练失败"
    voice.status == 4 || voice.catalogState == "Active" -> "已激活，无法再次制作"
    voice.status == 2 || voice.catalogState == "Success" -> "训练成功"
    voice.remaining == 0 -> "制作次数已用完"
    voice.unused -> "未使用名额"
    voice.status == 0 -> "未找到音色"
    voice.error.isNotBlank() -> "状态查询失败，请重试"
    else -> "待查询"
}

internal fun voiceSlotSummary(voice: PersonalVoices.Voice): String =
    voiceStatus(voice) + (if (voice.remaining >= 0) " · 剩余 ${voice.remaining} 次" else " · 次数待查询") +
        if (voice.showInMyVoices && voice.canTrain) " · 再次制作消耗本名额次数并覆盖已有声音" else ""

internal fun personalVoiceSlots(voices: List<PersonalVoices.Voice>, creating: Boolean, postpaid: Boolean): List<PersonalVoices.Voice> =
    voices.filter { if (creating) !postpaid && it.id.startsWith("S_") else !it.unused }
        .sortedWith(compareBy<PersonalVoices.Voice> { it.unused }.thenBy { it.name })

internal fun personalVoiceSlotTitle(voice: PersonalVoices.Voice, index: Int): String =
    if (voice.name == voice.id) "音色名额 ${index + 1}" else voice.name
