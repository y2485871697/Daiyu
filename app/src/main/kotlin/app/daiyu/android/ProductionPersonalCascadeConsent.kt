package app.daiyu.android

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.voice.personal.PersonalVoiceCascadeBinding
import app.daiyu.android.voice.personal.PersonalVoiceCascadeLimits
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/** Memory-only proposal, NOT permission. Local reference resolution never initiates an upload. */
internal class PersonalCascadeOffer internal constructor(
    internal val hostToken: Long,
    internal val frozen: FrozenVoiceSpeech,
    internal val binding: PersonalVoiceCascadeBinding,
    val limits: PersonalVoiceCascadeLimits,
    val perRequestAudioMs: Long,
) {
    override fun toString() = "PersonalCascadeOffer(redacted)"
}

/** Distinct from a selected voice, a frozen profile, and the existing one-request read consent. */
internal class ExplicitPersonalCascadePermission private constructor(internal val offer: PersonalCascadeOffer) {
    internal val acceptedAtMs = System.nanoTime() / 1_000_000L
    override fun toString() = "ExplicitPersonalCascadePermission(redacted)"
    companion object {
        /** Only call in the positive action of the finite multi-upload disclosure. */
        fun acceptDisplayedLimits(offer: PersonalCascadeOffer) = ExplicitPersonalCascadePermission(offer)
    }
}

/**
 * Drop-in composer entry. Parent supplies its existing captured editor/lifecycle/owner admission.
 * Async preparation, confirm, dismiss and disposal are fenced against the ORIGINAL callback and
 * host epoch, not a rememberUpdatedState callback that could accidentally admit a newer editor.
 */
@Composable
internal fun ProductionPersonalCascadeConsent(
    host: ActivityCascadeVoiceHost,
    isCurrent: () -> Boolean,
    onFailure: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    var pending by remember(host) { mutableStateOf<PersonalCascadeOffer?>(null) }
    var originalAdmission by remember(host) { mutableStateOf<(() -> Boolean)?>(null) }
    var generation by remember(host) { mutableLongStateOf(0L) }
    var preparing by remember(host) { mutableStateOf(false) }
    fun discard() {
        generation++
        val prior = pending
        pending = null; originalAdmission = null; preparing = false
        if (prior != null) host.cancelPersonalConsent(prior)
    }
    DisposableEffect(host) {
        onDispose {
            generation++
            pending?.let(host::cancelPersonalConsent)
            pending = null
        }
    }
    LaunchedEffect(lifecycleState) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) discard()
    }
    TextButton(text = "听写并朗读", enabled = !preparing,
        modifier = Modifier.testTag("voice-cascade-arm"), onClick = {
            if (isCurrent() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                discard()
                val original = isCurrent
                val request = ++generation
                originalAdmission = original
                preparing = true
                scope.launch {
                    try {
                        val offer = host.preparePersonalConsent()
                        if (request != generation || !original() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            offer?.let(host::cancelPersonalConsent)
                            return@launch
                        }
                        if (offer == null) {
                            host.arm() // Nonpersonal/system retains the existing explicit arm path.
                            originalAdmission = null
                        } else pending = offer
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { if (request == generation) onFailure("级联未启用：检查独立会话语音配置、个人引用或账户状态") }
                    finally { if (request == generation) preparing = false }
                }
            }
        })
    val offer = pending
    if (offer != null) Dialog(onDismissRequest = { if (pending === offer) discard() }) {
        Column(Modifier.fillMaxWidth().padding(20.dp).testTag("personal-cascade-consent"),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val config = offer.frozen.configuration
            Text("明确授权：有限个人声音级联")
            Text("账户 / provider：${config.provider}\n服务：${if (config.engine == "DOUBAO") "openspeech.bytedance.com" else java.net.URI(config.endpoint).host}\n账户指纹：${offer.binding.accountFingerprint.take(12)}\n个人声音 / 引用：${config.voice}\n引用摘要：${offer.binding.referenceDigest.take(12)}")
            Text(if (config.engine == "MIMO") "下一条手动发送被受理的回答将按完整句段，多次上传回答文字及这份参考录音到上述账户。" else
                "下一条手动发送被受理的回答将按完整句段，多次上传回答文字及上述账户个人音色标识；不会上传本机参考录音。")
            Text("每次上传可能产生费用，包括失败或结果未知的请求；不会自动重试、换账户或回退音色。此许可不是单条朗读许可，也不会自动发送聊天消息。")
            Text("上限：${offer.limits.maxSentences} 次合成上传；${offer.limits.maxCharacters} 个 Unicode 字符（含空白标点）；累计音频预约 ${offer.limits.maxAudioDurationMs / 1000} 秒；每请求最多 ${offer.perRequestAudioMs / 1000} 秒。实际音轨过长或时长未知将拒绝播放，不按文字估算时长。确认后 ${offer.limits.validForMs / 1000} 秒内有效，仅绑定下一次原始发送 ticket 受理的同一会话 / run / 消息；取消、删除、配置或账户变化立即撤权。")
            TextButton(text = "取消（不上传）", modifier = Modifier.testTag("personal-cascade-cancel"), onClick = {
                if (pending === offer) discard()
            })
            TextButton(text = "同意上述账户、引用、多次上传、费用与有限额度", modifier = Modifier.testTag("personal-cascade-accept"), onClick = {
                if (pending === offer) {
                    val admit = originalAdmission
                    pending = null; originalAdmission = null; generation++
                    if (admit?.invoke() == true && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && host.consentCurrent(offer)) {
                        try { host.armPersonal(ExplicitPersonalCascadePermission.acceptDisplayedLimits(offer)) }
                        catch (_: Exception) { host.cancelPersonalConsent(offer); onFailure("个人级联授权已失效，未启动上传") }
                    } else host.cancelPersonalConsent(offer)
                }
            })
        }
    }
}
