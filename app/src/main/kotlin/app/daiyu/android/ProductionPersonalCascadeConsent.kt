package app.daiyu.android

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.daiyu.android.voice.personal.PersonalVoiceCascadeBinding
import app.daiyu.android.voice.personal.PersonalVoiceCascadeLimits
import kotlinx.coroutines.launch
import app.daiyu.android.ui.haptics.miuix.TextButton

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

/** A finite, exact-offer permission, distinct from voice selection or a frozen profile. */
internal class ExplicitPersonalCascadePermission private constructor(internal val offer: PersonalCascadeOffer) {
    internal val acceptedAtMs = System.nanoTime() / 1_000_000L
    override fun toString() = "ExplicitPersonalCascadePermission(redacted)"
    companion object {
        /** Trusted in-app default, only after the user requests this cascade; never on profile load. */
        fun forRequestedCascade(offer: PersonalCascadeOffer) = ExplicitPersonalCascadePermission(offer)
    }
}

/** Admit this freshly prepared offer, not a remembered offer or a new editor's callback. */
internal fun armRequestedPersonalCascade(
    host: ActivityCascadeVoiceHost,
    offer: PersonalCascadeOffer,
    isCurrent: () -> Boolean,
): Boolean {
    if (!isCurrent() || !host.consentCurrent(offer)) {
        host.cancelPersonalConsent(offer)
        return false
    }
    host.armPersonal(ExplicitPersonalCascadePermission.forRequestedCascade(offer))
    return true
}

/**
 * The button expresses intent; the trusted default issues only this request's finite permission.
 * Preparation, lifecycle and disposal still fence the ORIGINAL callback and host epoch. No effect
 * simulates a click, no profile change arms a cascade, and arming never sends a chat message.
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
    var generation by remember(host) { mutableLongStateOf(0L) }
    var preparing by remember(host) { mutableStateOf(false) }
    fun discard() {
        generation++
        val prior = pending
        pending = null; preparing = false
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
                preparing = true
                scope.launch {
                    var offer: PersonalCascadeOffer? = null
                    try {
                        val prepared = host.preparePersonalConsent()
                        offer = prepared
                        fun admitted() = request == generation && original() &&
                            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                        if (!admitted()) return@launch
                        if (prepared == null) host.arm() // The same explicit button, nonpersonal/system path.
                        else {
                            pending = prepared
                            armRequestedPersonalCascade(host, prepared, ::admitted)
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        if (request == generation) onFailure("级联未启用：检查独立会话语音配置、个人引用或账户状态")
                    } finally {
                        // After a successful arm the offer is consumed, so this cannot revoke it.
                        offer?.let(host::cancelPersonalConsent)
                        if (pending === offer) pending = null
                        if (request == generation) preparing = false
                    }
                }
            }
        })
}
