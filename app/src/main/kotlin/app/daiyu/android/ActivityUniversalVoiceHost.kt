package app.daiyu.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.daiyu.android.voice.*
import app.daiyu.android.voice.control.*
import app.daiyu.android.voice.personal.*
import app.daiyu.android.voice.duplex.DuplexRouteLease
import app.daiyu.android.voice.duplex.AndroidCommunicationRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 将连续语音通话接入 Activity；一次显式启动冻结识别与朗读配置，阶段等待有独立时限。
 * 构造、渲染和恢复时保持待命；编辑器、发送回执与原始 Message 仍由 Main 持有。
 *
 * 命令和父级回调在主线程运行；`current` 和准入检查还会在音频提交边界调用，需使用短小、线程安全的非阻塞快照读取。
 * 生命周期、编辑器、配置或提问变化时，父级需先使准入快照失效，再撤销许可。
 * 编辑器版本迁移只接受本次发送已受理的原始编辑器。
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
    private val submissionReady: () -> Boolean = { true },
    private val revokePendingTicket: (VoiceControlPermit) -> Unit,
) : AutoCloseable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val inputAudio = AudioOwnerGate()
    private val outputAudio = AudioOwnerGate()
    private val recognition = AndroidUniversalRecognition(app, gate = inputAudio, communication = true)
    private var callLease: AudioOwnerGate.Lease? = null
    private var route: DuplexRouteLease? = null
    private fun acquireCallAudio(permit: VoiceControlPermit) {
        if (callLease != null) { check(ActivityVoiceAudio.gate.current(checkNotNull(callLease))); return }
        val binding = checkNotNull(frozen)
        val owner = VoiceOwner(permit.conversationId, permit.id)
        val lease = ActivityVoiceAudio.gate.acquire(owner, { admitted(permit) }) {
            binding.revoked = true
            releaseOnMain(main) {
                callLease = null
                controller.revoke(permit.id)
                drainLanes()
            }
        }
        callLease = lease
        try {
            route = DuplexRouteLease(
                AndroidCommunicationRoute(app.getSystemService(android.media.AudioManager::class.java)))
            check(route!!.startPrivate()) { "voice_call_route_unavailable" }
        } catch (failure: Throwable) {
            releaseCallAudio(); throw failure
        }
    }
    private fun releaseCallAudio() {
        val lease = callLease; callLease = null
        val close = { drainLanes() }
        if (lease == null || !ActivityVoiceAudio.gate.releaseAfter(lease, close)) close()
    }
    private fun drainLanes() {
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) { try { action() } catch (problem: Throwable) { if (failure == null) failure = problem } }
        attempt { inputAudio.cancelAll(); check(inputAudio.releaseHealthy()) { "call_input_release_pending" } }
        attempt { outputAudio.cancelAll(); check(outputAudio.releaseHealthy()) { "call_output_release_pending" } }
        val previousRoute = route; route = null
        attempt { previousRoute?.close() }
        failure?.let { throw it }
    }
    private class Frozen(
        val profile: VoiceControlProfile,
        val entry: VoiceEntrySnapshot,
        val speech: FrozenVoiceSpeech,
        val recognition: UniversalRecognitionConfiguration,
    ) {
        @Volatile var permitId: String? = null
        @Volatile var revoked = false
        var personal: PersonalVoiceCallAuthorization? = null
        var expiry: Runnable? = null
    }
    private var startingLimits = VoiceControlLimits()
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
        if (callLease?.let { !ActivityVoiceAudio.gate.current(it) } == true) return VoiceAdmission.REVOKED
        return admission(permit) // Original editor/foreground/question/exclusion/permission snapshot.
    }
    private fun admitted(permit: VoiceControlPermit): Boolean = try {
        checkAdmission(permit) == VoiceAdmission.READY
    } catch (_: Exception) { false }

    private val ports: VoiceControlPorts = object : VoiceControlPorts {
        override fun dispatch(action: () -> Unit) = post(action)
        override fun submissionReady() = this@ActivityUniversalVoiceHost.submissionReady()
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
            val input = recognitionConfiguration()
            check(current() && recognition.available(input)) { "universal_configuration_unavailable" }
            val binding = Frozen(profile, entry, speech, input)
            frozen = binding
            if (speech.isPersonal) {
                check(!speech.configuration.voice.startsWith("mimo-local-") || speech.reference != null)
                val identity = checkNotNull(runtime.personalBinding(speech)) { "personal_call_voice_unavailable" }
                val limits = startingLimits
                binding.personal = PersonalVoiceCallAuthorization(identity,
                    PersonalVoiceCascadeLimits(8 * limits.maxTurns, 1600 * limits.maxTurns,
                        limits.durationMillis, limits.durationMillis),
                    currentBinding = { runtime.personalBinding(speech) },
                    callIsCurrent = { !closed && frozen === binding && !binding.revoked && current() },
                    nowMillis = SystemClock::elapsedRealtime)
                check(binding.personal!!.current()) { "personal_call_configuration_changed" }
            }
            return entry
        }
        override fun admission(permit: VoiceControlPermit): VoiceAdmission {
            // First admission follows entrySnapshot synchronously on control. Subsequent gate
            // admissions only read the established binding; they never mutate UI/controller state.
            val binding = frozen
            if (binding != null && binding.permitId == null && !binding.revoked) {
                onMain()
                binding.permitId = permit.id
                binding.personal?.let { personal ->
                    val expiry = Runnable {
                        if (frozen === binding && !binding.revoked) revoke(permit.id, VoiceControlStopReason.EXPIRED)
                    }
                    binding.expiry = expiry
                    check(main.postDelayed(expiry, personal.remainingMillis()))
                }
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
            acquireCallAudio(turn.permit)
            val binding = checkNotNull(frozen)
            return recognition.start(turn, binding.recognition, { admitted(turn.permit) }, events)
        }
        override fun submit(turn: VoiceControlTurn, finalText: String, result: (VoiceControlReceipt) -> Unit) {
            onMain()
            if (!admitted(turn.permit)) { result(VoiceControlReceipt.Rejected(turn.requestId)); return }
            // Never discard a late result on revoke. First late Accepted goes to the leaf; an
            // Accepted discovered AFTER an Unknown/Rejected needs separate exact cleanup because
            // the unchanged leaf intentionally ignores duplicate receipts.
            val personal = frozen?.personal
            personal?.reserve(turn)
            val receipts = AndroidUniversalReceiptFence(turn, { receipt ->
                if (receipt is VoiceControlReceipt.Accepted && admitted(turn.permit)) {
                    personal?.accepted(turn, PersonalVoiceCascadeOwner(receipt.assistant.conversationId,
                        receipt.assistant.runId, receipt.assistant.messageId))
                } else personal?.rejected(turn)
                result(receipt)
            }) { extra ->
                try { this@ActivityUniversalVoiceHost.stopAcceptedRun(extra) }
                catch (_: Exception) {
                    bridgeReleaseFailed = true
                    controller.acceptedReleaseFailed()
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
        override fun stopped(reason: VoiceControlStopReason, stage: VoiceControlFailureStage?) {
            app.getSharedPreferences("voice-call-diagnostic-v1", Context.MODE_PRIVATE).edit()
                .putString("reason", reason.name).putString("stage", stage?.name)
                .putLong("time_ms", System.currentTimeMillis()).apply()
        }
        override fun playback(turn: VoiceControlTurn, owner: VoiceAssistantOwner, drained: () -> Unit,
            failed: () -> Unit): VoiceControlPlayback {
            onMain()
            check(owner.conversationId == turn.permit.conversationId && admitted(turn.permit))
            val binding = checkNotNull(frozen)
            val identity = VoiceOwner(owner.conversationId, owner.runId) // Unique per output/turn.
            val personal = binding.personal?.answer(turn,
                PersonalVoiceCascadeOwner(owner.conversationId, owner.runId, owner.messageId))
            val speech = if (personal == null) binding.speech else binding.speech.withUpload(object : PersonalCascadeUpload {
                override fun consume(requestId: String, exactText: String): Long = personal.consume(requestId, exactText, 15_000)
                override fun current(): Boolean = personal.current()
            }, 15_000)
            val allowed = { admitted(turn.permit) && frozen === binding && personal?.current() != false }
            val base = speechOutput(identity, speech)
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
            val playback = try {
                AndroidUniversalPlayback(identity, guarded, allowed, ::post, drained, failed, outputAudio).also { it.start() }
            } catch (failure: Throwable) { personal?.close(); throw failure }
            return object : VoiceControlPlayback by playback {
                override fun closeAndDrain() { personal?.close(); playback.closeAndDrain() }
            }
        }
        override fun stopAcceptedRun(owner: VoiceAssistantOwner) {
            onMain()
            this@ActivityUniversalVoiceHost.stopAcceptedRun(owner) // Parent retained exact Message.
        }
        override fun revoke(permit: VoiceControlPermit) {
            onMain()
            frozen?.takeIf { it.permitId == permit.id }?.let { binding ->
                binding.revoked = true
                binding.personal?.revoke()
                binding.expiry?.let(main::removeCallbacks)
                binding.expiry = null
            } // Synchronous fence before draining audio.
            // Parent cancels ONLY original pending ticket; it must retain late result correlation.
            var failure: Throwable? = null
            try { revokePendingTicket(permit) } catch (problem: Throwable) { failure = problem }
            try { releaseCallAudio() } catch (problem: Throwable) {
                if (failure == null) failure = problem else failure.addSuppressed(problem)
            }
            failure?.let { bridgeReleaseFailed = true; throw it }
        }
    }
    private val controller = VoiceCallController(ports)
    val state get() = controller.state

    fun start(profile: VoiceControlProfile, limits: VoiceControlLimits = VoiceControlLimits()): String? {
        onMain()
        if (bridgeReleaseFailed) return null
        startingLimits = limits
        return try { controller.start(profile, limits) } finally { startingLimits = VoiceControlLimits() }
    }
    fun permissionResult(permitId: String, granted: Boolean) {
        onMain(); controller.permissionResult(permitId, granted)
    }
    fun setCompressing(value: Boolean) { onMain(); controller.setCompressing(value) }
    fun setMuted(value: Boolean) { onMain(); controller.setMuted(value) }
    fun setModelPicker(value: Boolean) { onMain(); controller.setModelPicker(value) }
    val speaker: Boolean get() = route?.speaker ?: false
    fun setSpeaker(value: Boolean): Boolean { onMain(); return route?.setSpeaker(value) == true }
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
            val player = RemoteVoiceHostPlayer(app, scope, runtime, speech, temporaryVoiceOwner = owner.conversationId)
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
