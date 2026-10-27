package app.daiyu.android

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.ui.components.DaiyuConfirmDialog
import app.daiyu.android.ui.screens.settings.SettingsCard
import app.daiyu.android.ui.screens.settings.SettingsRow
import app.daiyu.android.voice.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

internal fun stopVoicePreview(host: VoiceHost, gate: AudioOwnerGate): Boolean =
    host.stop(host.owner) && gate.releaseHealthy()

/** Uses the same audio gate and players as chat, and only starts after an explicit click. */
@Composable
internal fun VoiceSettingsPreview(runtime: ControllerVoiceRuntime, profile: VoiceProfile,
    enabled: Boolean, isCurrent: () -> Boolean, installRevoke: (() -> Unit) -> (() -> Unit),
    sampleText: String = "你好，这是代鱼的语音试听。", configuration: VoiceConfiguration? = null,
    onInitialize: (() -> Unit)? = null, personalPreview: FrozenVoiceSpeech? = null, embedded: Boolean = false) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val current by rememberUpdatedState(isCurrent)
    val allowed by rememberUpdatedState(enabled)
    var prepared by remember { mutableStateOf<FrozenVoiceSpeech?>(null) }
    var initialize by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var preparing by remember { mutableStateOf(false) }
    var request by remember { mutableLongStateOf(0L) }
    var preparation by remember { mutableStateOf<Job?>(null) }
    val host = remember(runtime, profile) {
        VoiceHost(scope, ActivityVoiceAudio.gate) {
            val frozen = checkNotNull(prepared)
            check(current() && allowed && runtime.current(frozen))
            if (frozen.configuration.engine == "SYSTEM") AndroidVoiceHostPlayer(AndroidSpeechPlayer(context))
            else RemoteVoiceHostPlayer(context, scope, runtime, frozen)
        }
    }
    val state by host.state.collectAsState()
    fun stop(): Boolean {
        request++; preparation?.cancel(); preparation = null; preparing = false; initialize = false
        val stopped = stopVoicePreview(host, ActivityVoiceAudio.gate)
        prepared = null
        if (!stopped) error = "音频停止未确认，请退出本页检查语音输出。"
        return stopped
    }
    fun play(frozen: FrozenVoiceSpeech) {
        if (!current() || !allowed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !runtime.current(frozen)) return
        if (preparing) return
        val token = ++request
        preparing = true
        preparation = scope.launch {
            try {
                val ready = if (frozen.configuration.voice.startsWith("mimo-local-")) runtime.authorizeReference(frozen) else frozen
                if (request == token && current() && allowed && runtime.current(ready) && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    prepared = ready
                    if (!host.speak(host.owner, "settings-preview", sampleText)) error = "试听未启动，请检查语音配置。"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (request == token) error = "试听未启动，请检查语音配置。" }
            finally { if (request == token) { preparing = false; preparation = null } }
        }
    }
    DisposableEffect(host, lifecycle) {
        host.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val revoke = installRevoke { stop() }
        val unobserve = runtime.observeInvalidation { changed ->
            if (changed == null || changed == profile) releaseOnMain(Handler(Looper.getMainLooper())) { check(stop()) { "settings_preview_release_unconfirmed" } }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) stop()
            host.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        lifecycle.addObserver(observer)
        onDispose { stop(); revoke(); unobserve(); lifecycle.removeObserver(observer); host.close() }
    }
    LaunchedEffect(enabled, configuration) { if (!enabled) stop() }
    val active = preparing || voiceHostIsActive(state)
    val previewRow: @Composable () -> Unit = {
        SettingsRow(if (active) "停止试听" else "试听", tag = "voice-preview",
            summary = when {
                error.isNotBlank() -> error
                state.phase == SpeechPhase.ERROR || state.phase == SpeechPhase.UNAVAILABLE -> "试听失败，请检查语音配置。"
                preparing || state.phase == SpeechPhase.INITIALIZING || state.phase == SpeechPhase.QUEUED -> "正在准备…"
                else -> null
            }, showChevron = true, onClick = if (!enabled && !active) null else ({
                if (active) stop()
                else if (current() && onInitialize != null) initialize = true
                else if (current() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    error = ""
                    try {
                        val frozen = personalPreview ?: runtime.prepareSpeech(profile)
                        play(frozen)
                    } catch (_: Exception) { error = "请先选择模型和音色。" }
                }
            }))
    }
    if (embedded) previewRow() else VoiceSettingsCard { previewRow() }
    if (initialize) DaiyuConfirmDialog("启用系统语音", "将为此分组保存系统语音设置。保存后可点击试听，不会迁移其他分组的配置。",
        "启用", false, onConfirm = { initialize = false; if (current()) onInitialize?.invoke() },
        onDismiss = { initialize = false })
}
