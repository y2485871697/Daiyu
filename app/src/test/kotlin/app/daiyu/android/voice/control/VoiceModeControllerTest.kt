package app.daiyu.android.voice.control

import org.junit.Assert.*
import org.junit.Test

/** Pure deterministic control-thread tests; no microphone, chat model, network or audio runtime. */
class VoiceModeControllerTest {
    private val profile = VoiceControlProfile("conversation", 7, "通用对话")
    private val owner = VoiceAssistantOwner("chat", "run-1", "assistant-1")
    private class Fake : VoiceControlPorts {
        var now = 100L
        var admissionValue = VoiceAdmission.READY
        var entry = VoiceEntrySnapshot("chat", "foreground-1", setOf(VoiceEntryMode.UNIVERSAL), true, true, microphoneGranted = true)
        val queue = ArrayDeque<() -> Unit>()
        data class Timer(val due: Long, val action: () -> Unit, var cancelled: Boolean = false)
        val timers = mutableListOf<Timer>()
        val permissions = mutableListOf<VoiceControlPermit>()
        val recognitions = mutableListOf<VoiceControlPorts.RecognitionEvents>()
        val inputs = mutableListOf<Input>()
        class Input : VoiceControlRecognition {
            var closes = 0
            var failClose = false
            override fun closeAndDrain() { closes++; check(!failClose) }
        }
        data class Send(val turn: VoiceControlTurn, val text: String, val result: (VoiceControlReceipt) -> Unit)
        val sends = mutableListOf<Send>()
        var throwSubmit = false
        data class Observation(val owner: VoiceAssistantOwner, val body: (VoiceAssistantBody) -> Unit, var cancelled: Boolean = false)
        val observations = mutableListOf<Observation>()
        class Output(val drained: () -> Unit, val failed: () -> Unit) : VoiceControlPlayback {
            val bodies = mutableListOf<Pair<String, Boolean>>()
            var closes = 0
            var failClose = false
            override fun body(text: String, complete: Boolean) { bodies += text to complete }
            override fun closeAndDrain() { closes++; check(!failClose) }
        }
        val outputs = mutableListOf<Output>()
        val stopped = mutableListOf<VoiceAssistantOwner>()
        val revoked = mutableListOf<VoiceControlPermit>()
        override fun dispatch(action: () -> Unit) { queue.addLast(action) }
        fun flush() { var count = 0; while (queue.isNotEmpty()) { check(++count < 1000); queue.removeFirst().invoke() } }
        fun advance(millis: Long) {
            now += millis
            timers.filter { !it.cancelled && it.due <= now }.toList().forEach { it.cancelled = true; it.action() }
            flush()
        }
        override fun nowMillis() = now
        override fun schedule(delayMillis: Long, action: () -> Unit): VoiceControlCancellation {
            val timer = Timer(now + delayMillis, action); timers += timer
            return VoiceControlCancellation { timer.cancelled = true }
        }
        override fun entrySnapshot(profile: VoiceControlProfile) = entry
        override fun admission(permit: VoiceControlPermit) = admissionValue
        override fun requestMicrophonePermission(permit: VoiceControlPermit) { permissions += permit }
        override fun recognize(turn: VoiceControlTurn, events: VoiceControlPorts.RecognitionEvents): VoiceControlRecognition {
            check(admissionValue == VoiceAdmission.READY)
            recognitions += events
            return Input().also { inputs += it }
        }
        override fun submit(turn: VoiceControlTurn, finalText: String, result: (VoiceControlReceipt) -> Unit) {
            sends += Send(turn, finalText, result)
            if (throwSubmit) error("not_for_state_or_logs")
        }
        override fun observe(owner: VoiceAssistantOwner, body: (VoiceAssistantBody) -> Unit): VoiceControlCancellation {
            val observation = Observation(owner, body); observations += observation
            return VoiceControlCancellation { observation.cancelled = true }
        }
        override fun playback(turn: VoiceControlTurn, owner: VoiceAssistantOwner, drained: () -> Unit,
            failed: () -> Unit): VoiceControlPlayback = Output(drained, failed).also { outputs += it }
        override fun stopAcceptedRun(owner: VoiceAssistantOwner) { stopped += owner }
        override fun revoke(permit: VoiceControlPermit) { revoked += permit }
        fun finishRecognition(text: String = "send exactly once") { recognitions.last().finalResult(text); flush() }
        fun accept(owner: VoiceAssistantOwner, index: Int = sends.lastIndex) {
            val send = sends[index]
            send.result(VoiceControlReceipt.Accepted(send.turn.requestId, "durable-${send.turn.requestId}", owner)); flush()
        }
        fun body(owner: VoiceAssistantOwner, text: String, status: VoiceBodyStatus = VoiceBodyStatus.STREAMING) {
            observations.last().body(VoiceAssistantBody(owner, text, status)); flush()
        }
    }

    @Test fun explicitFinitePermitFinalReceiptBodyAndTrueDrainAreAllRequired() {
        val f = Fake(); val c = VoiceModeController(f)
        assertTrue(f.recognitions.isEmpty())
        val id = c.start(profile, VoiceControlLimits(maxTurns = 2))!!
        assertEquals(1, f.recognitions.size)
        f.recognitions[0].partial("partial must not send"); f.flush()
        assertTrue(f.sends.isEmpty())
        f.finishRecognition("final text")
        assertEquals("final text", f.sends.single().text)
        assertEquals(1, f.inputs.single().closes)
        assertTrue(f.outputs.isEmpty()) // Sending/queueing has not established ownership.
        f.accept(owner)
        assertEquals(owner, f.observations.single().owner)
        f.body(owner, "First sentence。")
        assertEquals(listOf("First sentence。" to false), f.outputs.single().bodies)
        f.body(owner, "First sentence。 tail", VoiceBodyStatus.COMPLETE)
        assertEquals(VoiceControlPhase.DRAINING, c.state.value.phase)
        assertEquals(1, f.recognitions.size) // Model final != device drain.
        f.outputs[0].drained(); f.flush()
        assertEquals(2, f.recognitions.size)
        assertEquals(1, f.outputs[0].closes)
        assertTrue(f.observations[0].cancelled)
        assertTrue(f.stopped.isEmpty())
        f.finishRecognition(); f.accept(owner.copy(runId = "run-2", messageId = "assistant-2"))
        f.body(owner.copy(runId = "run-2", messageId = "assistant-2"), "Done", VoiceBodyStatus.COMPLETE)
        f.outputs[1].drained(); f.flush()
        assertEquals(2, f.recognitions.size)
        assertEquals(VoiceControlStopReason.TURN_LIMIT, c.state.value.reason)
        assertEquals(id, f.revoked.single().id)
    }

    @Test fun noAudioBeforePermissionAndOldGrantCannotStartLaterPermit() {
        val f = Fake(); f.entry = f.entry.copy(microphoneGranted = false); f.admissionValue = VoiceAdmission.PERMISSION_REQUIRED
        val c = VoiceModeController(f)
        val old = c.start(profile)!!
        assertEquals(VoiceControlPhase.PERMISSION_REQUIRED, c.state.value.phase)
        assertEquals(old, f.permissions.single().id)
        assertTrue(f.recognitions.isEmpty()); assertTrue(f.outputs.isEmpty())
        c.stop(old)
        val fresh = c.start(profile)!!
        f.admissionValue = VoiceAdmission.READY
        c.permissionResult(old, true)
        assertTrue(f.recognitions.isEmpty())
        c.permissionResult(fresh, true)
        assertEquals(1, f.recognitions.size)
        c.permissionResult(fresh, true)
        assertEquals(1, f.recognitions.size)
        c.close()
    }

    @Test fun denialAndBackgroundGrantNeverOpenInput() {
        val f = Fake(); f.entry = f.entry.copy(microphoneGranted = false); f.admissionValue = VoiceAdmission.PERMISSION_REQUIRED
        val c = VoiceModeController(f); val id = c.start(profile)!!
        c.permissionResult(id, false)
        assertEquals(VoiceControlStopReason.PERMISSION_DENIED, c.state.value.reason)
        val next = c.start(profile)!!; f.admissionValue = VoiceAdmission.REVOKED
        c.permissionResult(next, true)
        assertEquals(VoiceControlStopReason.REVOKED, c.state.value.reason)
        assertTrue(f.recognitions.isEmpty())
    }

    @Test fun duplicateFinalPartialAndDuplicateReceiptNeverResubmitOrRebind() {
        val f = Fake(); val c = VoiceModeController(f); c.start(profile)
        f.finishRecognition("first")
        f.recognitions[0].partial("late"); f.recognitions[0].finalResult("duplicate"); f.flush()
        assertEquals(1, f.sends.size); assertEquals("first", c.state.value.transcript)
        f.accept(owner); f.accept(owner)
        assertEquals(1, f.outputs.size); assertEquals(1, f.observations.size)
        c.close()
    }

    @Test fun rejectedUnknownAndSubmitExceptionStopWithoutReplay() {
        for (kind in 0..2) {
            val f = Fake(); val c = VoiceModeController(f); c.start(profile)
            f.throwSubmit = kind == 2; f.finishRecognition()
            val send = f.sends.single()
            if (kind < 2) { send.result(if (kind == 0) VoiceControlReceipt.Rejected(send.turn.requestId) else VoiceControlReceipt.Unknown(send.turn.requestId)); f.flush() }
            assertEquals(if (kind == 0) VoiceControlStopReason.REJECTED else VoiceControlStopReason.UNKNOWN, c.state.value.reason)
            f.advance(300_000)
            assertEquals(1, f.sends.size); assertEquals(1, f.recognitions.size); assertTrue(f.outputs.isEmpty())
        }
    }

    @Test fun staleAcceptedAfterStopStopsOnlyItsExactOwnerAndDoesNotTouchNewPermit() {
        val f = Fake(); val c = VoiceModeController(f); val first = c.start(profile)!!
        f.finishRecognition(); c.stop(first)
        val second = c.start(profile)!!
        f.accept(owner, 0)
        assertEquals(listOf(owner), f.stopped)
        assertEquals(second, c.state.value.permitId)
        assertTrue(c.state.value.active); assertTrue(f.outputs.isEmpty())
        c.stop(first) // A delayed old Stop cannot cancel new recognition.
        assertEquals(0, f.inputs[1].closes)
        c.close()
    }

    @Test fun unrelatedOwnerCannotSupplyBodyOrBecomeStopTarget() {
        val f = Fake(); val c = VoiceModeController(f); val id = c.start(profile)!!
        f.finishRecognition(); f.accept(owner)
        for (wrong in listOf(owner.copy(conversationId = "other"), owner.copy(runId = "other"), owner.copy(messageId = "other"))) {
            f.body(wrong, "secret other answer", VoiceBodyStatus.COMPLETE)
        }
        assertTrue(f.outputs.single().bodies.isEmpty())
        c.stop(id)
        assertEquals(listOf(owner), f.stopped)
    }

    @Test fun wrongReceiptCorrelationFailsClosedWithoutStoppingUnrelatedRun() {
        for (wrongRequest in listOf(false, true)) {
            val f = Fake(); val c = VoiceModeController(f); c.start(profile); f.finishRecognition()
            val send = f.sends.single()
            send.result(VoiceControlReceipt.Accepted(if (wrongRequest) "other" else send.turn.requestId,
                "receipt", if (wrongRequest) owner else owner.copy(conversationId = "other"))); f.flush()
            assertEquals(VoiceControlStopReason.INVALID_RECEIPT, c.state.value.reason)
            assertTrue(f.outputs.isEmpty()); assertTrue(f.stopped.isEmpty())
        }
    }

    @Test fun questionWaitRevokesExactRunInsteadOfOpeningNextRound() {
        val f = Fake(); val c = VoiceModeController(f); c.start(profile)
        f.finishRecognition(); f.accept(owner); f.body(owner, "Question?", VoiceBodyStatus.QUESTION_WAITING)
        assertEquals(VoiceControlStopReason.QUESTION_WAITING, c.state.value.reason)
        f.outputs.single().drained(); f.flush()
        assertEquals(1, f.recognitions.size); assertEquals(listOf(owner), f.stopped)
    }

    @Test fun admissionRevocationPreventsFinalSendAndDrainedReopen() {
        val f = Fake(); val c = VoiceModeController(f); c.start(profile)
        f.admissionValue = VoiceAdmission.CONFIGURATION_CHANGED; f.finishRecognition()
        assertTrue(f.sends.isEmpty()); assertEquals(VoiceControlStopReason.CONFIGURATION_CHANGED, c.state.value.reason)
        f.admissionValue = VoiceAdmission.READY; c.start(profile); f.finishRecognition(); f.accept(owner)
        f.body(owner, "Done", VoiceBodyStatus.COMPLETE)
        f.admissionValue = VoiceAdmission.PERMISSION_REQUIRED; f.outputs.single().drained(); f.flush()
        assertEquals(VoiceControlStopReason.PERMISSION_DENIED, c.state.value.reason)
        assertEquals(2, f.recognitions.size)
    }

    @Test fun staleInputBodyDrainAndFailureCannotAffectNewRun() {
        val f = Fake(); val c = VoiceModeController(f); val old = c.start(profile)!!
        f.finishRecognition(); f.accept(owner); c.stop(old)
        val next = c.start(profile)!!
        f.recognitions[0].finalResult("stale"); f.recognitions[0].failed()
        f.observations[0].body(VoiceAssistantBody(owner, "late", VoiceBodyStatus.COMPLETE))
        f.outputs[0].drained(); f.outputs[0].failed(); f.flush()
        assertEquals(next, c.state.value.permitId); assertTrue(c.state.value.active)
        assertEquals(1, f.sends.size); assertTrue(f.outputs[0].bodies.isEmpty())
        c.close()
    }

    @Test fun prematureDrainAndPlaybackFailureAreTerminal() {
        for (early in listOf(true, false)) {
            val f = Fake(); val c = VoiceModeController(f); c.start(profile); f.finishRecognition(); f.accept(owner)
            if (early) f.outputs.single().drained() else f.outputs.single().failed()
            f.flush()
            assertEquals(if (early) VoiceControlStopReason.UNKNOWN else VoiceControlStopReason.PLAYBACK_FAILED, c.state.value.reason)
            assertEquals(1, f.recognitions.size); assertEquals(listOf(owner), f.stopped)
        }
    }

    @Test fun finiteDurationAndEveryStageTimeoutEndPermissionWithoutRetry() {
        for (stage in 0..4) {
            val f = Fake(); val c = VoiceModeController(f)
            if (stage == 0) { f.entry = f.entry.copy(microphoneGranted = false); f.admissionValue = VoiceAdmission.PERMISSION_REQUIRED }
            val limits = VoiceControlLimits(durationMillis = 50_000, recognitionMillis = 1_000,
                receiptMillis = 1_000, responseMillis = 1_000, drainMillis = 1_000)
            c.start(profile, limits)
            if (stage >= 2) f.finishRecognition()
            if (stage >= 3) f.accept(owner)
            if (stage >= 4) f.body(owner, "Done", VoiceBodyStatus.COMPLETE)
            f.advance(1_000)
            assertEquals(VoiceControlStopReason.TIMEOUT, c.state.value.reason)
            assertEquals(1, f.revoked.size)
            assertTrue(f.recognitions.size <= 1)
        }
        val f = Fake(); val c = VoiceModeController(f); c.start(profile, VoiceControlLimits(durationMillis = 1_000))
        f.advance(1_000)
        assertEquals(VoiceControlStopReason.EXPIRED, c.state.value.reason)
        assertEquals(1, f.recognitions.size)
    }

    @Test fun cleanupFailurePoisonsFurtherStartsAndStillAttemptsOtherOwnedCleanup() {
        val f = Fake(); val c = VoiceModeController(f); val id = c.start(profile)!!
        f.finishRecognition(); f.accept(owner); f.outputs.single().failClose = true
        c.stop(id)
        assertEquals(VoiceControlStopReason.RELEASE_FAILED, c.state.value.reason)
        assertTrue(f.observations.single().cancelled); assertEquals(listOf(owner), f.stopped)
        assertNull(c.start(profile)); assertEquals(1, f.recognitions.size)
        c.close()
    }

    @Test fun recognitionReleaseFailureStopsBeforeSubmitAndEmptyFinalDoesNotLoop() {
        val f = Fake(); val c = VoiceModeController(f); c.start(profile)
        f.inputs.single().failClose = true; f.finishRecognition()
        assertEquals(VoiceControlStopReason.RELEASE_FAILED, c.state.value.reason)
        assertTrue(f.sends.isEmpty()); assertNull(c.start(profile))
        val other = Fake(); val d = VoiceModeController(other); d.start(profile); other.finishRecognition("   ")
        assertEquals(VoiceControlStopReason.EMPTY_FINAL, d.state.value.reason)
        assertTrue(other.sends.isEmpty()); assertEquals(1, other.recognitions.size)
    }

    @Test fun ordinaryDictationDuplexAndUnavailableEntriesCannotEnterThisLoop() {
        for (snapshot in listOf(
            Fake().entry.copy(enabledModes = setOf(VoiceEntryMode.DICTATION)),
            Fake().entry.copy(enabledModes = setOf(VoiceEntryMode.DOUBAO_DUPLEX)),
            Fake().entry.copy(profileReady = false), Fake().entry.copy(questionWaiting = true),
            Fake().entry.copy(foreground = false), Fake().entry.copy(conversationId = null))) {
            val f = Fake(); f.entry = snapshot; val c = VoiceModeController(f)
            assertNull(c.start(profile)); assertTrue(f.recognitions.isEmpty()); assertTrue(f.sends.isEmpty())
        }
    }
}
