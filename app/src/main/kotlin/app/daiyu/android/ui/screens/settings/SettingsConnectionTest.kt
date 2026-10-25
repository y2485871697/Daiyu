package app.daiyu.android.ui.screens.settings

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.model.catalog.ModelConnectionResult
import app.daiyu.android.model.catalog.RemoteModelFailure
import java.util.concurrent.atomic.AtomicBoolean

internal typealias ModelConnectionTester =
    (ModelSettingsDraftUi, () -> Boolean, (ModelConnectionResult) -> Unit) -> Boolean

internal data class SettingsConnectionTestUi(val running: Boolean, val status: String?,
    val start: () -> Unit, val cancel: () -> Unit)

internal class SettingsConnectionDraftOwner(val draft: ModelSettingsDraftUi) {
    override fun equals(other: Any?): Boolean = other is SettingsConnectionDraftOwner && draft === other.draft
    override fun hashCode(): Int = System.identityHashCode(draft)
}

@Composable
internal fun rememberSettingsConnectionTest(draft: ModelSettingsDraftUi, enabled: Boolean,
    tester: ModelConnectionTester?): SettingsConnectionTestUi {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentTester by rememberUpdatedState(tester)
    val draftOwner = SettingsConnectionDraftOwner(draft)
    val currentDraftOwner by rememberUpdatedState(draftOwner)
    val currentEnabled by rememberUpdatedState(enabled)
    val visit = remember(lifecycle) { AtomicBoolean(true) }
    var request by remember { mutableStateOf<AtomicBoolean?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    fun cancel() { request?.set(true); request = null; status = null }
    DisposableEffect(lifecycle, visit) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) cancel() }
        lifecycle.addObserver(observer)
        onDispose { visit.set(false); cancel(); lifecycle.removeObserver(observer) }
    }
    DisposableEffect(draftOwner, enabled, tester != null) {
        onDispose { cancel() }
    }
    return SettingsConnectionTestUi(request != null, status, start = {
        val invoke = currentTester
        if (visit.get() && invoke != null && currentEnabled && request == null &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            val original = AtomicBoolean(false)
            val frozen = currentDraftOwner
            request = original; status = null
            val accepted = try {
                invoke(frozen.draft, original::get) { result ->
                    if (visit.get() && request === original && !original.get() && currentEnabled && currentDraftOwner == frozen &&
                        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        request = null; original.set(true); status = connectionTestMessage(result, app.daiyu.android.voice.providers.DoubaoSpeech.isOpenspeech(frozen.draft.endpoint))
                    }
                }
            } catch (_: Exception) { false }
            if (!accepted && request === original) {
                original.set(true); request = null; status = "当前任务不可测试连接，请稍后重试"
            }
        }
    }, cancel = ::cancel)
}

internal fun connectionTestMessage(result: ModelConnectionResult, speechOnly: Boolean = false): String = when (result) {
    ModelConnectionResult.SpeechConnected -> "语音合成连接成功；未播放音频或验证语音识别"
    is ModelConnectionResult.Connected -> "目录连接成功，返回 ${result.modelCount} 个模型；未验证推理、工具或图片能力"
    is ModelConnectionResult.Failed -> when (result.reason) {
        RemoteModelFailure.INVALID_ENDPOINT -> "服务地址无效，请检查 HTTPS 地址"
        RemoteModelFailure.AUTHENTICATION -> "鉴权失败，请检查密钥"
        RemoteModelFailure.PERMISSION -> "服务拒绝访问，请检查账号权限"
        RemoteModelFailure.ENDPOINT -> if (speechOnly) "语音接口不存在或不支持" else "模型目录接口不存在或不支持"
        RemoteModelFailure.RATE_LIMIT -> "服务限流，请稍后重试"
        RemoteModelFailure.SERVER -> "服务暂时不可用，请稍后重试"
        RemoteModelFailure.REDIRECT -> "服务要求重定向；未向新地址发送密钥"
        RemoteModelFailure.TIMEOUT -> "连接超时，请检查网络或服务地址"
        RemoteModelFailure.NETWORK -> "网络连接失败"
        RemoteModelFailure.TLS -> "安全连接失败，请检查服务证书"
        RemoteModelFailure.PROTOCOL -> if (speechOnly) "语音响应格式不受支持" else "模型目录响应格式不受支持"
        RemoteModelFailure.REQUEST -> if (speechOnly) "语音无法发起请求，请检查连接设置" else "无法发起目录请求，请检查连接设置"
        RemoteModelFailure.CANCELLED -> "连接测试已取消"
    }
}
