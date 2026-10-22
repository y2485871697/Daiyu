package app.daiyu.android

import android.net.Uri
import android.os.Looper
import app.daiyu.android.ui.navigation.PageBackHandler as BackHandler
import app.daiyu.android.ui.components.MiuixScaffoldPage
import app.daiyu.android.ui.components.DaiyuConfirmDialog
import app.daiyu.android.ui.screens.settings.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.voice.account.*
import app.daiyu.android.voice.providers.DoubaoPersonalVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import app.daiyu.android.ui.haptics.miuix.TextButton

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

/**
 * Independently mounted leaf. frozenConfiguration MUST be a saved runtime snapshot (not a draft).
 * Selection is only a callback; the parent decides whether/how to save a new configuration.
 * No network on render/load/select/picker return. Every network operation has a second confirmation.
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
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val latestCurrent by rememberUpdatedState(isCurrent)
    val latestStrictStop by rememberUpdatedState(stopPreviewStrictly)
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
    var notice by remember(frozenConfiguration.revision) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var confirmation by remember(frozenConfiguration.revision) { mutableStateOf<Pair<String, suspend () -> Unit>?>(null) }
    var ak by remember(frozenConfiguration.revision) { mutableStateOf("") }
    var sk by remember(frozenConfiguration.revision) { mutableStateOf("") }
    var project by remember(frozenConfiguration.revision) { mutableStateOf("default") }
    var name by remember(frozenConfiguration.revision) { mutableStateOf("") }
    var existingId by remember(frozenConfiguration.revision) { mutableStateOf("") }
    var slotId by remember(frozenConfiguration.revision) { mutableStateOf("") }
    var postpaid by remember(frozenConfiguration.revision) { mutableStateOf(false) }
    var sampleUri by remember(frozenConfiguration.revision) { mutableStateOf<Uri?>(null) }
    val samplePicker = remember(resources) { AccountSamplePicker<Uri>() }
    var pickerPending by remember { mutableStateOf(false) }
    var pickerResult by remember { mutableIntStateOf(0) }
    var projects by remember(frozenConfiguration.revision) { mutableStateOf(emptyList<VoiceProjectCatalog.Project>()) }
    fun current() = latestCurrent(frozenConfiguration) && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    val enabled = loaded && !busy && account != null && current() && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    fun execute(block: suspend () -> Unit) {
        if (!current() || busy) return
        confirmation = null; notice = ""; busy = true
        operation = scope.launch {
            try {
                previewGuard.execute(::current, { latestStrictStop() }, block = block)
                if (current() && notice.isBlank()) notice = "操作完成"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (current()) notice = if (previewGuard.stopUnconfirmed)
                    "试听停止未确认，已拒绝本次操作。请退出账户页面并核对语音输出状态。"
                else "操作未确认。请核对账户、槽位和权限；若已提交训练，只查询原音色，不要重复训练。"
            } finally { busy = false }
        }
    }
    fun ask(label: String, action: suspend () -> Unit) { if (enabled) confirmation = label to action }
    DisposableEffect(lifecycle, frozenConfiguration.revision, preview) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                confirmation = null; operation?.cancel(); preview.stop()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer); operation?.cancel(); preview.stop()
            samplePicker.invalidate()
        }
    }
    LaunchedEffect(resources, frozenConfiguration.revision) {
        preview.stop(); confirmation = null; sampleUri = null
        try {
            withContext(Dispatchers.IO) { resources.voices.load() }
            loaded = true
            val credentials = account?.let { withContext(Dispatchers.IO) { resources.preferences.load(it.fingerprint) } }
            if (credentials != null) { ak = credentials.keyId; sk = credentials.secret; project = credentials.project }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { notice = "本机账户资料或控制面凭据不可读；不会静默覆盖，凭据可明确重新保存或清除。" }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        samplePicker.deliver(uri); pickerResult++
    }
    LaunchedEffect(pickerResult, lifecycleState, frozenConfiguration.revision) {
        val uri = samplePicker.take(frozenConfiguration.revision,
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), latestCurrent(frozenConfiguration))
        pickerPending = samplePicker.pending
        if (uri != null) { sampleUri = uri; confirmation = null }
    }
    var page by remember { mutableStateOf("LIST") }
    var parentPage by remember { mutableStateOf("LIST") }
    var step by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var manualId by remember { mutableStateOf(false) }
    var projectManual by remember { mutableStateOf(false) }
    var advancedCreation by remember { mutableStateOf(false) }
    var agreed by remember { mutableStateOf(false) }
    var projectPicker by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<String?>(null) }
    var voiceMenu by remember { mutableStateOf<PersonalVoices.Voice?>(null) }
    var accountKey by remember(frozenConfiguration.revision) { mutableStateOf(frozenConfiguration.key) }
    fun navigate(target: String) {
        if (!current() || busy) return
        confirmation = null; preview.stop(); menu = false; addMenu = false
        if (target == "SYNC" || target == "ACCOUNT") parentPage = page
        page = target
    }
    fun beginFlow(target: String) {
        if (!current() || busy) return
        name = ""; existingId = ""; slotId = ""; postpaid = false; sampleUri = null
        manualId = false; advancedCreation = false; agreed = false; step = 0
        samplePicker.invalidate(); pickerPending = false
        navigate(target)
    }
    fun back() {
        if (busy) { operation?.cancel(); notice = "正在停止等待。若已提交制作，请查询原音色，不要重复提交。"; return }
        samplePicker.invalidate()
        confirmation = null; preview.stop(); menu = false; addMenu = false; voiceMenu = null
        when {
            page == "SYNC" || page == "ACCOUNT" -> page = parentPage
            page == "CREATE" && step > 0 -> { step--; agreed = false }
            page != "LIST" -> page = "LIST"
            else -> { operation?.cancel(); onBack() }
        }
    }
    BackHandler { back() }
    val title = when (page) {
        "SYNC" -> "同步音色名额"; "ACCOUNT" -> "声音复刻账户"
        "IMPORT" -> "导入声音"; "CREATE" -> "制作声音"; else -> "我的声音"
    }
    MiuixScaffoldPage(title, ::back, modifier = Modifier.testTag("voice-account"), actions = {
        if (page == "LIST") {
            TextButton("+", enabled = !busy, modifier = Modifier.testTag("voice-account-add"), onClick = { if (current()) addMenu = true })
            TextButton("更多", enabled = !busy, modifier = Modifier.testTag("voice-account-menu"), onClick = { if (current()) menu = true })
        }
    }) {
        if (page == "LIST") {
            if (visibleVoices.isEmpty()) item { SettingsNote("还没有声音。点击 + 制作或导入声音。") }
            items(visibleVoices.size, key = { visibleVoices[it].id }) { index ->
                val voice = visibleVoices[index]
                VoiceSettingsCard {
                    SettingsRow(voice.name, summary = voiceStatus(voice), trailing = {
                        TextButton(if (previewState.matches(voice.account, voice.id)) { if (previewState.loading) "正在准备" else "停止" } else "试听",
                            enabled = enabled && voice.tts && voice.demo.isNotBlank(), onClick = {
                                if (previewState.matches(voice.account, voice.id)) preview.stop()
                                else { onPreviewRequest(); execute { check(current()); preview.toggle(speech!!, voice) } }
                            })
                        TextButton("更多", enabled = !busy, onClick = { if (current()) voiceMenu = voice })
                    })
                    if (voice.tts && !voice.accepted) SettingsRow("确认音色，允许正式合成", summary = "可能产生音色费用",
                        onClick = if (!enabled) null else ({ ask("我有权使用此声音，并同意正式合成可能产生音色费用") {
                            withContext(Dispatchers.IO) { resources.voices.accept(voice) }
                        } }))
                }
            }
        }
        if (page == "ACCOUNT") item {
            VoiceSettingsCard {
                SettingsTextField("声音复刻 API Key", accountKey, { if (!busy) { accountKey = it; confirmation = null } }, password = true, enabled = !busy)
                SettingsRow("保存账户", onClick = if (busy || accountKey.isBlank() || onAccountKeyChange == null) null else ({
                    val key = accountKey
                    execute { onAccountKeyChange?.invoke(key) }
                }))
            }
            SettingsNote("声音复刻账户独立保存；API Key 与音色名额须属于同一项目。")
            VoiceSettingsCard { SettingsRow("打开豆包控制台", onClick = { if (current()) uriHandler.openUri("https://console.volcengine.com/speech/app") }) }
        }
        if (page == "SYNC") item {
            SettingsNote("AK / SK 与语音 API Key 不同。此处只读取名额，不购买、不训练。项目列表不能判断语音 Key 的归属，请选择同一项目。")
            VoiceSettingsCard {
                AccountField("AK", ak, busy, true) { ak = it; sk = ""; projects = emptyList(); confirmation = null }
                AccountField("SK", sk, busy, true) { sk = it; projects = emptyList(); confirmation = null }
                SettingsRow("当前项目", summary = project, onClick = if (busy || projects.isEmpty()) null else ({ projectPicker = true }))
                SettingsRow(if (projects.isEmpty()) "获取项目列表" else "刷新项目列表", onClick = if (!enabled || ak.isBlank() || sk.isBlank()) null else ({
                    val a = ak; val s = sk
                    ask("向火山 IAM 发送 AK 签名请求，仅查询项目，不发送语音 Key") { projects = resources.projects.list(a, s); projectPicker = projects.isNotEmpty(); notice = if (projects.isEmpty()) "没有可选项目，可手动填写项目名称。" else "请选择与语音 API Key 相同的项目。" }
                }))
                SettingsRow("手动填写项目（备用）", onClick = if (busy) null else ({ projectManual = !projectManual }))
                if (projectManual) AccountField("项目名称", project, busy) { project = it; confirmation = null }
                SettingsRow("读取我的名额", onClick = if (!enabled || ak.isBlank() || sk.isBlank() || project.isBlank()) null else ({
                    val a = ak; val s = sk; val p = project
                    ask("查询项目 $p 的槽位；已制作槽位将用当前语音 Key 校验，不制作、不合成") {
                        val count = resources.voices.importPurchased(speech!!, a, s, p)
                        notice = if (count == 0) "本项目未找到名额；请核对项目与语音 API Key。" else "已同步 $count 个名额；未使用的名额在制作声音时选择。"
                    }
                }))
                SettingsRow("保存账户信息", onClick = if (account == null || busy || !current() || ak.isBlank() || sk.isBlank() || project.isBlank()) null else ({
                    val a = ak; val s = sk; val p = project
                    execute { withContext(Dispatchers.IO) { resources.preferences.save(account!!.fingerprint, a, s, p) }; notice = "账户信息已加密保存在本机" }
                }))
                SettingsRow("清除已记住的信息", onClick = if (account == null || busy || !current()) null else ({
                    ask("清除本机保存的 AK / SK 与项目，不改动云端账户") {
                        withContext(Dispatchers.IO) { resources.preferences.clear(account!!.fingerprint) }
                        ak = ""; sk = ""; project = "default"; projects = emptyList(); notice = "已清除本机账户信息"
                    }
                }))
            }
            SettingsNote("修改后请点击保存账户信息；返回其它子页会保留本次输入。AK / SK 须配对，凭据按语音账户加密保存。")
            VoiceSettingsCard { SettingsRow("打开火山访问密钥管理", onClick = { if (current()) uriHandler.openUri("https://console.volcengine.com/iam/keymanage/") }) }
        }
        if (page == "CREATE") item {
            SettingsSectionTitle("${step + 1} / 3")
            androidx.compose.material3.LinearProgressIndicator(progress = { (step + 1) / 3f },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        }
        if (page == "IMPORT" || (page == "CREATE" && step == 0)) item {
            if (page == "CREATE") SettingsSectionTitle("1. 选择名额")
            VoiceSettingsCard {
                AccountField(if (page == "IMPORT") "备注名称（可空）" else "声音名称", name, busy) { name = it.take(80); confirmation = null }
                if (page == "CREATE") {
                    SettingsRow(if (advancedCreation) "收起高级设置" else "高级：新建后付费音色", onClick = { advancedCreation = !advancedCreation })
                    if (advancedCreation) SettingsSwitchRow("新建后付费音色", postpaid, enabled = enabled) { postpaid = it; confirmation = null }
                }
                voices.filter { if (page == "CREATE") !postpaid && it.id.startsWith("S_") else !it.unused }.forEach { voice ->
                    val selected = if (page == "CREATE") slotId == voice.id else existingId == voice.id
                    SettingsRadioRow(voice.name, selected, enabled = enabled && (page != "CREATE" || voice.canTrain), summary = if (page == "CREATE") voiceSlotSummary(voice) else voiceStatus(voice)) {
                        if (current()) { if (page == "CREATE") { slotId = voice.id; postpaid = false } else existingId = voice.id; confirmation = null }
                    }
                }
                SettingsRow("查找我的音色名额", onClick = if (!enabled) null else ({ navigate("SYNC") }))
                SettingsRow("备用方式：从控制台复制 ID", onClick = if (busy) null else ({ manualId = !manualId }))
                if (manualId) {
                    if (page == "CREATE") AccountField("名额 ID", slotId, busy) { slotId = it; confirmation = null }
                    else AccountField("已有音色 ID", existingId, busy) { existingId = it; confirmation = null }
                }
                if (page == "CREATE") SettingsRow("下一步：选择录音", onClick = if (!enabled || name.isBlank() || (!postpaid && (!slotId.matches(Regex("S_[A-Za-z0-9_-]+")) || voices.firstOrNull { it.id == slotId }?.canTrain == false))) null else ({ step = 1; agreed = false }))
                else SettingsRow("导入声音", onClick = if (!enabled || !existingId.matches(Regex("(?:S_|etaClone)[A-Za-z0-9_-]+"))) null else ({
                    val id = existingId; val n = name
                    ask("用当前语音账户查询已有音色 $id；不上传、不制作") { resources.voices.importExisting(speech!!, id, n); page = "LIST"; notice = "声音已导入；没有重新制作。" }
                }))
            }
            SettingsNote(if (page == "IMPORT") "仅查询已有声音，不上传录音、不重新制作。备注名称最多 80 字。"
                else if (postpaid) "后付费音色可能收费，需先在控制台开通。请核对费用与有效期后再制作。"
                else "优先使用已有或免费名额；列表为空不代表免费名额已用完。可同步名额，或从豆包控制台复制音色 ID。")
        }
        if (page == "CREATE" && step == 1) item {
            SettingsSectionTitle("2. 选择录音")
            VoiceSettingsCard {
                SettingsRow(if (sampleUri == null) "选择录音" else "重新选择录音", tag = "voice-training-sample",
                    summary = if (sampleUri == null) "选择文件不会上传" else "录音已选择", onClick = if (!enabled || pickerPending) null else ({
                        if (samplePicker.begin(frozenConfiguration.revision)) {
                            pickerPending = true
                            try { picker.launch("audio/*") }
                            catch (_: Exception) { samplePicker.deliver(null); pickerPending = samplePicker.pending; pickerResult++; notice = "未能打开录音选择器" }
                        }
                    }))
                SettingsRow("下一步：确认制作", onClick = if (!enabled || sampleUri == null) null else ({ step = 2; agreed = false }))
            }
            SettingsNote("支持 WAV / MP3 / OGG / M4A / AAC，最大 10 MiB。请选择清晰的单人录音，并确认你拥有此声音及录音的使用权。")
        }
        if (page == "CREATE" && step == 2) item {
            SettingsSectionTitle("3. 确认制作")
            VoiceSettingsCard {
                SettingsRow("名称", summary = name)
                SettingsRow(if (postpaid) "新建后付费音色" else "已有名额", summary = if (postpaid) null else slotId)
                SettingsSwitchRow("我有权使用此录音并同意本次上传制作", agreed, enabled = enabled) { agreed = it }
                SettingsRow("确认上传并制作", tag = "voice-training-submit", onClick = if (!enabled || !agreed || sampleUri == null) null else ({
                    val uri = sampleUri!!; val n = name; val slot = if (postpaid) null else slotId
                    ask("上传所选录音并进行一次${if (slot == null) "后付费自定义" else "名额 $slot"}制作，可能消耗次数及费用。结果未知时只查询原音色，不重复提交。") {
                        val sample = PersonalVoices.readSample(context, uri)
                        try { check(current()); resources.voices.create(speech!!, sample, n, slot, confirmed = true); page = "LIST"; step = 0; notice = "已提交制作，请在原音色菜单查询状态，不要重复提交。" }
                        finally { sample.clear(); sampleUri = null; agreed = false }
                    }
                }))
            }
            SettingsNote("本次会上传录音并消耗训练次数，可能覆盖已有音色或产生费用。")
        }
        if (!latestCurrent(frozenConfiguration)) item { SettingsNote("配置已变化，请退出后重新打开。", error = true) }
        if (notice.isNotBlank()) item { SettingsNote(notice) }
        previewState.error?.let { value -> item { SettingsNote(value, error = true) } }
    }
    if (menu) VoiceChoiceDialog("声音设置", listOf("ACCOUNT" to "声音复刻账户", "SYNC" to "同步音色名额"), "",
        onSelect = { navigate(it) }, onDismiss = { menu = false })
    if (addMenu) VoiceChoiceDialog("添加声音", listOf("CREATE" to "用录音制作声音", "IMPORT" to "导入已有声音"), "",
        onSelect = { beginFlow(it) }, onDismiss = { addMenu = false })
    if (projectPicker) top.yukonga.miuix.kmp.window.WindowDialog(show = true, title = "选择项目", onDismissRequest = { projectPicker = false }) {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            projects.forEach { item -> SettingsRadioRow(item.displayName, project == item.name,
                enabled = item.allowed && !busy, summary = if (item.allowed) null else "无此项目访问权限") {
                if (current()) { project = item.name; projectPicker = false; confirmation = null; notice = "" }
            } }
            TextButton("关闭", onClick = { projectPicker = false })
        }
    }
    voiceMenu?.let { voice ->
        VoiceChoiceDialog(voice.name, buildList {
            add("QUERY" to "查询状态")
            if (voice.error.isNotBlank()) add("ERROR" to "错误详情")
            if (voice.tts && voice.accepted) add("SELECT" to "用于朗读")
            if (!voice.uncertain) add("REMOVE" to "移除本机记录")
        }, "", onSelect = { action ->
            voiceMenu = null
            if (enabled) when (action) {
                "QUERY" -> ask("查询原音色 ${voice.id}，不重复制作") { resources.voices.refresh(voice, speech!!) }
                "ERROR" -> detail = voice.error
                "SELECT" -> { preview.stop(); resources.voices.selected(voice.id, speech!!)?.let { onSelect(frozenConfiguration, it) } }
                "REMOVE" -> ask("仅移除本机记录，不删除云端音色或任务，不退款、不释放名额") {
                    preview.stop(); withContext(Dispatchers.IO) { resources.voices.removeLocal(voice) }
                }
            }
        }, onDismiss = { voiceMenu = null })
    }
    detail?.let { message ->
        DaiyuConfirmDialog("错误详情", message, "关闭", false, onConfirm = { detail = null }, onDismiss = { detail = null })
    }
    confirmation?.let { pending ->
        DaiyuConfirmDialog("确认本次操作", pending.first, "确认", false,
            onConfirm = { if (confirmation === pending && enabled) execute(pending.second) }, onDismiss = { confirmation = null })
    }
}

@Composable
private fun AccountField(label: String, value: String, readOnly: Boolean, secret: Boolean = false, onChange: (String) -> Unit) {
    SettingsTextField(label, value, onChange, password = secret, enabled = !readOnly)
}

private fun voiceStatus(voice: PersonalVoices.Voice): String = when {
    voice.uncertain -> "提交结果未知，请查询原音色，不要重新制作"
    voice.catalogState == "Expired" -> "已到期"
    voice.catalogState == "Reclaimed" -> "已回收"
    voice.status == 1 || voice.catalogState == "Training" -> "制作中"
    voice.status == 3 || voice.error.isNotBlank() -> "制作失败"
    voice.accepted && voice.tts -> "已正式使用"
    voice.tts -> "已就绪 · 试听后确认使用"
    voice.status == 4 || voice.catalogState == "Active" -> "已锁定"
    voice.remaining == 0 -> "制作次数已用完"
    voice.unused -> "未使用名额" + if (voice.remaining >= 0) " · 剩余 ${voice.remaining} 次" else ""
    voice.status == 0 -> "未找到音色"
    else -> "待查询"
}

private fun voiceSlotSummary(voice: PersonalVoices.Voice): String =
    voiceStatus(voice) + (if (voice.remaining >= 0) " · 剩余 ${voice.remaining} 次" else " · 次数待查询") +
        if (voice.showInMyVoices && voice.canTrain) " · 再次制作可能覆盖已有声音" else ""
