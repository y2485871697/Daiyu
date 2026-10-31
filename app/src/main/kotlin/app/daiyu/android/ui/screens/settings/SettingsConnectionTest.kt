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
                original.set(true); request = null; status = connectionTestMessage(ModelConnectionResult.Failed(RemoteModelFailure.REQUEST),
                    app.daiyu.android.voice.providers.DoubaoSpeech.isOpenspeech(frozen.draft.endpoint))
            }
        }
    }, cancel = ::cancel)
}

internal fun connectionTestMessage(result: ModelConnectionResult, speechOnly: Boolean = false): String = when (result) {
    ModelConnectionResult.SpeechConnected -> "语音合成成功"
    is ModelConnectionResult.Connected -> "连接成功，返回 ${result.modelCount} 个模型"
    is ModelConnectionResult.Failed -> if (speechOnly) "语音合成失败" else "连接失败"
}
