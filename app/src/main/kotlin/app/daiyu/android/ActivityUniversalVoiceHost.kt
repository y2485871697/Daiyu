package app.daiyu.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.daiyu.android.voice.*
import app.daiyu.android.voice.control.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Activity production composition for the existing finite UNIVERSAL controller. Nothing starts on
 * construction/render/resume. Main retains its original editor/send receipt and exact Message;
 * this host owns only one explicitly started, frozen, bounded audio permission. No assistant
 * service, chat client, replacement gate, retry, latest-message inference or draft fallback.
 *
 * Public commands/parent hooks are control-thread (main) calls, except `current` and admission:
 * those MUST also be safe, short, nonblocking snapshot reads at the audio gate commit boundary.
 * Parent must invalidate its admission snapshot BEFORE revoke on lifecycle/editor/config/question
 * changes. It must allow its OWN accepted editor revision to migrate, not authorize another editor.
 */
internal class ActivityUniversalVoiceHost(
    context: Context,
    private val runtime: ControllerVoiceRuntime,
    private val entrySnapshot: (VoiceControlProfile) -> VoiceEntrySnapshot,
    private val admission: (VoiceControlPermit) -> VoiceAdmission,
    private val prepareConversationSpeech: () -> FrozenVoiceSpeech,
    private val current: () -> Boolean,
    private val recognitionConfiguration: () -> UniversalRecognitionConfiguration,
    private val requestMicrophonePermission: (VoiceControlPermit) -> Unit,
    private val submit: (VoiceControlTurn, String, (VoiceControlReceipt) -> Unit) -> Unit,
    private val observe: (VoiceAssistantOwner, (VoiceAssistantBody) -> Unit) -> VoiceControlCancellation,
    private val stopAcceptedRun: (VoiceAssistantOwner) -> Unit,
    private val revokePendingTicket: (VoiceControlPermit) -> Unit,
) : AutoCloseable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val recognition = AndroidUniversalRecognition(app)
    private class Frozen(
        val profile: VoiceControlProfile,
        val entry: VoiceEntrySnapshot,
        val speech: FrozenVoiceSpeech,
        val recognition: UniversalRecognitionConfiguration,
    ) {
        @Volatile var permitId: String? = null
        @Volatile var revoked = false
    }
    @Volatile private var frozen: Frozen? = null
    @Volatile private var closed = false
    @Volatile private var bridgeReleaseFailed = false
    private fun onMain() { check(Looper.myLooper() == Looper.getMainLooper()) { "universal_control_thread" } }
    private fun post(action: () -> Unit) { check(main.post { action() }) { "universal_dispatch_rejected" } }

    private fun checkAdmission(permit: VoiceControlPermit): VoiceAdmission {
        val binding = frozen
        if (closed || bridgeReleaseFailed || binding == null || binding.revoked || binding.permitId != permit.id ||
            binding.entry.conversationId != permit.conversationId || binding.entry.lifecycleKey != permit.lifecycleKey)
            return VoiceAdmission.REVOKED
        if (binding.profile != permit.profile || !current() || !recognition.available(binding.recognition))
            return VoiceAdmission.CONFIGURATION_CHANGED
        if (SystemClock.elapsedRealtime() >= permit.expiresAtMillis) return VoiceAdmission.REVOKED
        return admission(permit) // Original editor/foreground/question/exclusion/permission snapshot.
    }
    private fun admitted(permit: VoiceControlPermit): Boolean = try {
        checkAdmission(permit) == VoiceAdmission.READY
    } catch (_: Exception) { false }

    private val ports = object : VoiceControlPorts {
        override fun dispatch(action: () -> Unit) = post(action)
        override fun nowMillis() = SystemClock.elapsedRealtime()
        override fun schedule(delayMillis: Long, action: () -> Unit): VoiceControlCancellation {
            val task = Runnable { action() }
            check(main.postDelayed(task, delayMillis))
            return VoiceControlCancellation { main.removeCallbacks(task) }
        }
        override fun entrySnapshot(profile: VoiceControlProfile): VoiceEntrySnapshot {
            onMain()
            val entry = this@ActivityUniversalVoiceHost.entrySnapshot(profile)
            if (closed) return entry.copy(profileReady = false)
            if (VoiceEntryPolicy.startProblem(entry) != null) return entry
            val speech = prepareConversationSpeech()
            // A pre-existing one-request personal-reference grant is NOT a multi-turn/sentence grant.
            require(speech.reference == null && !speech.isPersonal) {
                "universal_personal_reference_requires_independent_bounded_grant"
            }
            val input = recognitionConfiguration()
            check(current() && recognition.available(input)) { "universal_configuration_unavailable" }
            frozen = Frozen(profile, entry, speech, input)
            return entry
        }
        override fun admission(permit: VoiceControlPermit): VoiceAdmission {
            // First admission follows entrySnapshot synchronously on control. Subsequent gate
            // admissions only read the established binding; they never mutate UI/controller state.
            val binding = frozen
            if (binding != null && binding.permitId == null && !binding.revoked) {
                onMain()
                binding.permitId = permit.id
            }
            return checkAdmission(permit)
        }
        override fun requestMicrophonePermission(permit: VoiceControlPermit) {
            onMain()
            when (checkAdmission(permit)) {
                VoiceAdmission.PERMISSION_REQUIRED, VoiceAdmission.READY ->
                    this@ActivityUniversalVoiceHost.requestMicrophonePermission(permit)
                else -> error("universal_permission_revoked")
            }
        }
        override fun recognize(turn: VoiceControlTurn, events: VoiceControlPorts.RecognitionEvents): VoiceControlRecognition {
            onMain()
            check(admitted(turn.permit))
            val binding = checkNotNull(frozen)
            return recognition.start(turn, binding.recognition, { admitted(turn.permit) }, events)
        }
        override fun submit(turn: VoiceControlTurn, finalText: String, result: (VoiceControlReceipt) -> Unit) {
            onMain()
            if (!admitted(turn.permit)) { result(VoiceControlReceipt.Rejected(turn.requestId)); return }
            // Never discard a late result on revoke. First late Accepted goes to the leaf; an
            // Accepted discovered AFTER an Unknown/Rejected needs separate exact cleanup because
            // the unchanged leaf intentionally ignores duplicate receipts.
            val receipts = AndroidUniversalReceiptFence(turn, result) { extra ->
                try { this@ActivityUniversalVoiceHost.stopAcceptedRun(extra) }
                catch (_: Exception) {
                    bridgeReleaseFailed = true
                    controller.state.value.permitId?.let {
                        this@ActivityUniversalVoiceHost.revoke(it, VoiceControlStopReason.RELEASE_FAILED)
                    }
                }
            }
            this@ActivityUniversalVoiceHost.submit(turn, finalText) { receipt -> post { receipts.accept(receipt) } }
        }
        override fun observe(owner: VoiceAssistantOwner, body: (VoiceAssistantBody) -> Unit): VoiceControlCancellation {
            onMain()
            val binding = checkNotNull(frozen)
            val permitId = checkNotNull(binding.permitId)
            val observing = java.util.concurrent.atomic.AtomicBoolean(true)
            val source = this@ActivityUniversalVoiceHost.observe(owner) { value ->
                // Parent observer subscribes THEN replays the exact owner, with DB reads off main.
                if (observing.get() && !binding.revoked && binding.permitId == permitId &&
                    value.owner == owner) body(value)
            }
            return VoiceControlCancellation { observing.set(false); source.cancel() }
        }
        override fun playback(turn: VoiceControlTurn, owner: VoiceAssistantOwner, drained: () -> Unit,
            failed: () -> Unit): VoiceControlPlayback {
            onMain()
            check(owner.conversationId == turn.permit.conversationId && admitted(turn.permit))
            val binding = checkNotNull(frozen)
            val identity = VoiceOwner(owner.conversationId, owner.runId) // Unique per output/turn.
            val allowed = { admitted(turn.permit) && frozen === binding }
            val base = speechOutput(identity, binding.speech)
            // Recheck every sentence and every real player event, not just first lease acquisition.
            val guarded = object : SpeechOutput by base {
                override fun speak(text: String, events: SpeechOutput.Events) {
                    check(allowed()) { "universal_output_revoked" }
                    base.speak(text, object : SpeechOutput.Events {
                        override fun started() { if (allowed()) events.started() else events.failed("universal_output_revoked") }
                        override fun completed() { if (allowed()) events.completed() else events.failed("universal_output_revoked") }
                        override fun failed(reason: String) = events.failed("universal_output_failed")
                    })
                }
            }
            return AndroidUniversalPlayback(identity, guarded, allowed, ::post, drained, failed).also { it.start() }
        }
        override fun stopAcceptedRun(owner: VoiceAssistantOwner) {
            onMain()
            this@ActivityUniversalVoiceHost.stopAcceptedRun(owner) // Parent retained exact Message.
        }
        override fun revoke(permit: VoiceControlPermit) {
            onMain()
            frozen?.takeIf { it.permitId == permit.id }?.revoked = true // Synchronous fence first.
            // Parent cancels ONLY original pending ticket; it must retain late result correlation.
            revokePendingTicket(permit)
        }
    }
    private val controller = VoiceModeController(ports)
    val state get() = controller.state

    fun start(profile: VoiceControlProfile, limits: VoiceControlLimits = VoiceControlLimits()): String? {
        onMain()
        if (bridgeReleaseFailed) return null
        return controller.start(profile, limits)
    }
    fun permissionResult(permitId: String, granted: Boolean) {
        onMain(); controller.permissionResult(permitId, granted)
    }
    fun stop(permitId: String) = revoke(permitId, VoiceControlStopReason.USER)
    fun revoke(permitId: String, reason: VoiceControlStopReason = VoiceControlStopReason.REVOKED) {
        onMain()
        frozen?.takeIf { it.permitId == permitId }?.revoked = true
        controller.revoke(permitId, reason)
    }
    override fun close() {
        onMain()
        if (closed) return
        closed = true; frozen?.revoked = true
        controller.close()
    }

    private fun speechOutput(owner: VoiceOwner, speech: FrozenVoiceSpeech): SpeechOutput {
        if (speech.configuration.engine == "SYSTEM") return ProductionSpeechOutput(app, owner)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val delegate = ProductionSpeechOutput(owner, {
            val player = RemoteVoiceHostPlayer(app, scope, runtime, speech)
            object : ProductionSpeechPlayer {
                override val state get() = player.state
                override fun bindOwner(owner: String) = player.bindOwner(owner)
                override fun play(owner: String, requestId: String, text: String) = player.play(owner, requestId, text)
                override fun close() = player.closeStrict()
            }
        }, scope, ::post, { action -> releaseOnMain(main, action) }, ::voiceEvent)
        return object : SpeechOutput by delegate { override val startedEvidence = PlaybackEvidence.MEDIA_PLAYER_START }
    }
}
