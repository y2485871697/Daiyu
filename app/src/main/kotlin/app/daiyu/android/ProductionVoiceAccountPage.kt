package app.daiyu.android

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.voice.account.VoiceAccountResources
import app.daiyu.android.voice.releaseOnMain
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/** One saved profile and one composition incarnation. No audio or request on page entry. */
@Composable
internal fun ProductionVoiceAccountPage(
    runtime: ControllerVoiceRuntime,
    resources: VoiceAccountResources,
    profile: VoiceProfile,
    frozen: VoiceConfiguration,
    isCurrent: () -> Boolean,
    installPreviewRevoke: (() -> Unit) -> (() -> Unit),
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestCurrent by rememberUpdatedState(isCurrent)
    val active = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var notice by remember { mutableStateOf("") }
    val preview = remember(frozen.revision, profile) {
        ActivityVoiceAccountPreviewHost.forContext(context, scope, frozen.speech()) { request ->
            active.get() && runtime.current(frozen, profile) && runCatching {
                val selected = resources.voices.selected(request.voice.id, request.configuration)
                selected != null && selected == request.voice
            }.getOrDefault(false)
        }
    }
    fun leave() { active.set(false); preview.close(); onBack() }
    BackHandler { leave() }
    DisposableEffect(preview, lifecycle) {
        val uninstall = installPreviewRevoke { active.set(false); preview.admissionChanged() }
        // Invalidation may originate from runtime.save's IO worker; drain on main before it writes.
        val unobserve = runtime.observeInvalidation {
            releaseOnMain(Handler(Looper.getMainLooper())) {
                check(preview.stopPreviewStrictly()) { "account_preview_release_unconfirmed" }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                active.set(false); preview.admissionChanged()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            active.set(false); uninstall(); unobserve(); lifecycle.removeObserver(observer); preview.close()
        }
    }
    // Each visit/resume needs another positive action. This authorizes preview only, never training.
    Column {
        Text("试听使用已保存的 ${profile.name} 账户，向豆包合成固定文本“你好，这是当前账户音色的试听。”；不是下载云端示范。每次点击可能消耗额度或费用，取消不保证免计费，失败不自动重试。")
        TextButton(text = "我同意本页显式试听使用上述固定文本并可能收费",
            modifier = Modifier.testTag("voice-account-preview-consent"), onClick = {
            if (latestCurrent() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && runtime.current(frozen, profile))
                active.set(true)
        })
        if (notice.isNotBlank()) Text(notice)
        VoiceAccountPanel(
            frozenConfiguration = frozen, resources = resources, previewPort = preview,
            stopPreviewStrictly = {
                if (!preview.stopPreviewStrictly()) false
                else {
                    runtime.invalidateSpeech()
                    app.daiyu.android.voice.ActivityVoiceAudio.gate.releaseHealthy()
                }
            },
            isCurrent = { runtime.current(it, profile) && latestCurrent() },
            onSelect = { original, personal ->
                if (latestCurrent() && runtime.current(original, profile)) {
                    active.set(false)
                    try {
                        check(preview.stopPreviewStrictly()) { "account_preview_release_unconfirmed" }
                        preview.close()
                        runtime.invalidateSpeech()
                        scope.launch {
                            try {
                                runtime.save(original.copy(voice = personal.id, model = "seed-tts-2.0"), profile)
                                onBack()
                            } catch (_: Exception) { notice = "音色未保存，请退出后重新打开；不会自动合成" }
                        }
                    } catch (_: Exception) { notice = "旧音频释放未确认，未更改音色；请退出本页" }
                }
            },
            onBack = { leave() },
        )
    }
}
