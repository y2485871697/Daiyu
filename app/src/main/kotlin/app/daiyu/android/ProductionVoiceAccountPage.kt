package app.daiyu.android

import android.os.Handler
import android.os.Looper
import app.daiyu.android.ui.navigation.PageBackHandler as BackHandler
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
    standalone: Boolean = false,
    onAccountSaved: ((VoiceConfiguration) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestCurrent by rememberUpdatedState(isCurrent)
    val active = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var notice by remember { mutableStateOf("") }
    fun configurationCurrent() = if (standalone) runtime.accountCurrent(frozen) else runtime.current(frozen, profile)
    val preview = remember(frozen.revision, profile) {
        ActivityVoiceAccountPreviewHost.forContext(context, scope, frozen.speech()) { request ->
            active.get() && configurationCurrent() && runCatching {
                val selected = if (request.demoOnly) resources.voices.find(request.voice.id, request.configuration)?.providerVoice()
                    else resources.voices.selected(request.voice.id, request.configuration)
                accountPreviewVoiceMatches(selected, request.voice)
            }.getOrDefault(false)
        }
    }
    fun leave() { active.set(false); preview.close(); onBack() }
    BackHandler(previewEnabled = false) { leave() }
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
            onPreviewRequest = { if (latestCurrent() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && configurationCurrent()) active.set(true) },
            onAccountKeyChange = { key ->
                check(latestCurrent() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && configurationCurrent())
                if (standalone) runtime.saveAccount(frozen.copy(key = key.trim(), voice = app.daiyu.android.voice.providers.DoubaoVoices.DEFAULT_ID)) else runtime.save(frozen.copy(key = key), profile)
                if (latestCurrent() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    val updated = if (standalone) runtime.accountConfigurations.value[frozen.engine] else null
                    if (updated != null && runtime.accountCurrent(updated) && onAccountSaved != null) {
                        active.set(false); preview.close(); onAccountSaved(updated)
                    } else leave()
                }
            },
            stopPreviewStrictly = {
                if (!preview.stopPreviewStrictly()) false
                else {
                    runtime.invalidateSpeech()
                    app.daiyu.android.voice.ActivityVoiceAudio.gate.releaseHealthy()
                }
            },
            isCurrent = { (if (standalone) runtime.accountCurrent(it) else runtime.current(it, profile)) && latestCurrent() },
            onSelect = { original, personal ->
                if (latestCurrent() && (if (standalone) runtime.accountCurrent(original) else runtime.current(original, profile))) {
                    active.set(false)
                    try {
                        check(preview.stopPreviewStrictly()) { "account_preview_release_unconfirmed" }
                        preview.close()
                        runtime.invalidateSpeech()
                        scope.launch {
                            try {
                                val speech = original.copy(voice = personal.id, model = "seed-tts-2.0")
                                val current = runtime.profileConfiguration(profile).value ?: VoiceConfiguration()
                                check(configurationCurrent())
                                val next = current.withSpeech(speech)
                                runtime.save(next, profile)
                                VoiceSettingsPreferences(context).apply {
                                    rememberCloud(profile, next)
                                    setCloudEnabled(profile, true)
                                }
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

}

internal fun accountPreviewVoiceMatches(
    selected: DoubaoPersonalVoice?,
    requested: DoubaoPersonalVoice,
): Boolean = selected != null && selected.id == requested.id && selected.account == requested.account &&
    selected.status == requested.status && selected.models == requested.models && selected.accepted == requested.accepted
