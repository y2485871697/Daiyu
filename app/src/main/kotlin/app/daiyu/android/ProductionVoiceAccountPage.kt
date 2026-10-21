package app.daiyu.android

import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import app.daiyu.android.ui.screens.settings.SettingsCard
import app.daiyu.android.ui.screens.settings.SettingsRow
import app.daiyu.android.ui.screens.settings.SettingsNote
import app.daiyu.android.ui.components.DaiyuConfirmDialog
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
import app.daiyu.android.voice.providers.DoubaoPersonalVoice
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import app.daiyu.android.ui.haptics.miuix.TextButton

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
    var previewConsent by remember { mutableStateOf(false) }
    val preview = remember(frozen.revision, profile) {
        ActivityVoiceAccountPreviewHost.forContext(context, scope, frozen.speech()) { request ->
            active.get() && runtime.current(frozen, profile) && runCatching {
                val selected = resources.voices.selected(request.voice.id, request.configuration)
                accountPreviewVoiceMatches(selected, request.voice)
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
    Column {
        if (notice.isNotBlank()) SettingsNote(notice, error = true)
        VoiceAccountPanel(
            frozenConfiguration = frozen, resources = resources, previewPort = preview,
            previewConsent = {
                SettingsCard {
                    SettingsRow("试听授权", summary = "每次试听会在线合成固定文字，可能消耗额度或费用。",
                        tag = "voice-account-preview-consent", onClick = { previewConsent = true })
                }
            },
            onAccountKeyChange = { key ->
                check(latestCurrent() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && runtime.current(frozen, profile))
                runtime.save(frozen.copy(key = key), profile)
                if (latestCurrent() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) leave()
            },
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
                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (_: Exception) { notice = "音色未保存，请退出后重新打开；不会自动合成" }
                        }
                    } catch (_: Exception) { notice = "旧音频释放未确认，未更改音色；请退出本页" }
                }
            },
            onBack = { leave() },
        )
    }
    if (previewConsent) DaiyuConfirmDialog("试听授权", ACCOUNT_PREVIEW_CONFIRMATION, "允许", false,
        onConfirm = {
            previewConsent = false
            if (latestCurrent() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && runtime.current(frozen, profile)) active.set(true)
        }, onDismiss = { previewConsent = false })
}

internal fun accountPreviewVoiceMatches(
    selected: DoubaoPersonalVoice?,
    requested: DoubaoPersonalVoice,
): Boolean = selected != null && selected.id == requested.id && selected.account == requested.account &&
    selected.status == requested.status && selected.models == requested.models && selected.accepted == requested.accepted
