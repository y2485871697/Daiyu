package app.daiyu.android

import android.net.Uri
import android.os.Looper
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
import top.yukonga.miuix.kmp.basic.TextButton

internal const val ACCOUNT_PREVIEW_CONFIRMATION =
    "使用当前账户在线新合成固定文本：你好，这是当前账户音色的试听。" +
        "本次试听可能收费或消耗额度；取消也未必免计费。确认仅授权本次请求。"

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
        confirmation = null; busy = true
        operation = scope.launch {
            try {
                previewGuard.execute(::current, { latestStrictStop() }, block)
                if (current()) notice = "操作完成；未自动选择、查询、上传或开始训练下一次请求。"
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("voice-account"),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(text = "返回", onClick = { confirmation = null; operation?.cancel(); preview.stop(); onBack() })
        Text("豆包账户音色管理")
        Text("仅使用已保存语音配置的实际认证账户。导入已有音色、读取购买槽位、项目查询和状态查询均需确认后联网；不会自动查询或上传。")
        Text(if (account == null) "请先保存有效豆包语音配置" else "当前语音账户：${account.fingerprint.take(12)}…")
        if (!latestCurrent(frozenConfiguration)) Text("配置已变化，请退出后重新打开。")
        if (notice.isNotBlank()) Text(notice)
        previewState.error?.let { Text(it) }
        confirmation?.let { pending ->
            Text("确认本次请求：${pending.first}")
            TextButton(text = "确认本次操作", enabled = enabled, onClick = { execute(pending.second) })
            TextButton(text = "取消", onClick = { confirmation = null })
        }
        AccountField("控制面 AK（不是语音 API Key）", ak, busy, true) { ak = it; confirmation = null }
        AccountField("控制面 SK", sk, busy, true) { sk = it; confirmation = null }
        AccountField("项目名称", project, busy) { project = it; confirmation = null }
        Text("AK/SK/项目仅加密保存于当前语音账户下，不发送给合成接口。项目权限标记仅作提示，不能证明有复刻服务权限。")
        TextButton(text = "仅本机加密保存控制面凭据", enabled = account != null && !busy && current(), onClick = {
            val savedAk = ak; val savedSk = sk; val savedProject = project
            execute { withContext(Dispatchers.IO) { resources.preferences.save(account!!.fingerprint, savedAk, savedSk, savedProject) } }
        })
        TextButton(text = "清除本机控制面凭据", enabled = account != null && !busy && current(), onClick = {
            execute { withContext(Dispatchers.IO) { resources.preferences.clear(account!!.fingerprint) }; ak = ""; sk = ""; projects = emptyList() }
        })
        TextButton(text = "查询项目列表…", enabled = enabled, onClick = {
            val a = ak; val s = sk
            ask("向火山 IAM 发送 AK 签名请求，仅查询项目，不发送语音 Key") {
                projects = resources.projects.list(a, s)
            }
        })
        projects.forEach { item ->
            TextButton(text = "项目 ${item.displayName} (${item.name}) · ${if (item.allowed) "有项目权限" else "权限未确认"}", enabled = enabled,
                onClick = { project = item.name; confirmation = null })
        }
        TextButton(text = "导入购买的槽位…", enabled = enabled, onClick = {
            val a = ak; val s = sk; val p = project
            ask("查询项目 $p 的槽位；已训练槽位将用当前语音 Key 校验，不训练、不合成") {
                resources.voices.importPurchased(speech!!, a, s, p)
            }
        })
        AccountField("音色名称", name, busy) { name = it; confirmation = null }
        AccountField("已有音色 ID（S_ 或 etaClone）", existingId, busy) { existingId = it; confirmation = null }
        TextButton(text = "导入已有音色…", enabled = enabled, onClick = {
            val id = existingId; val n = name
            ask("用当前语音账户查询已有音色 $id；不上传、不训练") { resources.voices.importExisting(speech!!, id, n) }
        })
        Text("训练样本应为本人或已获授权的声音。一次上传可能消耗额度/费用；结果未知时仅查询原音色，不自动重训。")
        AccountField("已有训练槽位 S_ ID", slotId, busy) { slotId = it; confirmation = null }
        TextButton(text = if (postpaid) "后付费自定义 ID：已明确选择（点此改用已有槽位）" else "使用已有槽位（点此明确选择后付费自定义 ID）", enabled = enabled,
            onClick = { postpaid = !postpaid; confirmation = null })
        TextButton(text = if (sampleUri == null) "选择本机训练样本（不会上传）" else "已选样本；重新选择", enabled = enabled && !pickerPending,
            onClick = {
                if (samplePicker.begin(frozenConfiguration.revision)) { pickerPending = true; picker.launch("audio/*") }
            })
        TextButton(text = "提交一次训练…", enabled = enabled && sampleUri != null && name.isNotBlank(), onClick = {
            val uri = sampleUri!!; val n = name; val slot = if (postpaid) null else slotId
            ask("我拥有此录音的使用权，同意上传并进行一次${if (slot == null) "后付费自定义" else "槽位 $slot"}训练，可能消耗额度和费用") {
                val sample = PersonalVoices.readSample(context, uri)
                try {
                    check(current())
                    resources.voices.create(speech!!, sample, n, slot, confirmed = true)
                } finally { sample.clear(); sampleUri = null }
            }
        })
        Text("槽位 / 我的音色（空闲槽位可选训练，但不代表能朗读）")
        voices.forEach { voice ->
            Text("${voice.name} · ${voice.id}\n状态 ${voice.status} / ${voice.catalogState} · 模型 ${voice.models.sorted()} · 剩余 ${if (voice.remaining < 0) "未知" else voice.remaining.toString()} · ${if (voice.uncertain) "提交未知，只能查询" else if (voice.unused) "空闲槽位" else "音色"}")
            if (voice.error.isNotBlank()) Text(voice.error)
            TextButton(text = "查询状态…", enabled = enabled, onClick = {
                ask("查询原音色 ${voice.id}；绝不重复训练") { resources.voices.refresh(voice, speech!!) }
            })
            TextButton(text = "选为训练槽位（不上传）", enabled = enabled && voice.canTrain && voice.id.startsWith("S_"),
                onClick = { slotId = voice.id; postpaid = false; confirmation = null })
            if (voice.tts && !voice.accepted) TextButton(text = "首次使用确认：我有权使用此声音", enabled = enabled, onClick = {
                execute { withContext(Dispatchers.IO) { resources.voices.accept(voice) } }
            })
            TextButton(text = "选择此音色（不联网）", enabled = enabled && voice.tts && voice.accepted,
                onClick = { if (current()) { preview.stop(); onSelect(frozenConfiguration, resources.voices.selected(voice.id, speech!!)!!) } })
            TextButton(text = if (previewState.matches(voice.account, voice.id)) "停止试听" else "试听…", enabled = enabled && voice.tts && voice.accepted && voice.demo.isNotBlank(),
                onClick = {
                    if (previewState.matches(voice.account, voice.id)) preview.stop()
                    else ask(ACCOUNT_PREVIEW_CONFIRMATION) { check(current()); preview.toggle(speech!!, voice) }
                })
            TextButton(text = "仅移除本机记录（不删除云端、不退款、不释放槽位；未知须先查询）", enabled = enabled && !voice.uncertain, onClick = {
                execute { preview.stop(); withContext(Dispatchers.IO) { resources.voices.removeLocal(voice) } }
            })
        }
    }
}

@Composable
private fun AccountField(label: String, value: String, readOnly: Boolean, secret: Boolean = false, onChange: (String) -> Unit) {
    Text(label)
    BasicTextField(value = value, onValueChange = onChange, readOnly = readOnly, singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(8.dp))
}
