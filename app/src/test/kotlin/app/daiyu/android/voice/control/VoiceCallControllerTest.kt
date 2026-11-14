package app.daiyu.android.voice.control

import org.junit.Assert.*
import org.junit.Test

class VoiceCallControllerTest {
    private val profile = VoiceControlProfile("conversation", 7, "语音对话")
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
        var ready = true
        override fun submissionReady() = ready
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


    @Test fun microphoneStaysOpenWhileAnswerStreamsAndSpeechInterruptsExactRun() {
        val f = Fake(); val c = VoiceCallController(f)
        c.start(profile); f.finishRecognition("你好"); f.accept(owner)
        f.body(owner, "正在回答。")
        assertEquals(2, f.inputs.size)
        assertEquals("正在回答。", c.state.value.reply)
        val epoch = c.state.value.captionEpoch
        f.recognitions.last().speechStarted(); f.flush()
        assertEquals(listOf(owner), f.stopped)
        assertEquals(1, f.outputs.single().closes)
        assertTrue(f.observations.single().cancelled)
        assertEquals("", c.state.value.reply)
        assertEquals(epoch + 1, c.state.value.captionEpoch)
        f.recognitions.last().partial("下一句"); f.flush()
        assertEquals(epoch + 1, c.state.value.captionEpoch)
        assertEquals("下一句", c.state.value.transcript)
        f.body(owner, "过期内容")
        assertEquals("", c.state.value.reply)
    }
    @Test fun compressionClosesCaptureRejectsItsLateFinalAndResumesAutomatically() {
        val f = Fake(); val c = VoiceCallController(f)
        c.start(profile)
        val old = f.recognitions.single()
        c.setCompressing(true)
        assertEquals(1, f.inputs.single().closes)
        assertEquals(VoiceControlPhase.COMPRESSING, c.state.value.phase)
        old.finalResult("过期识别"); f.flush()
        assertTrue(f.sends.isEmpty())
        c.setCompressing(false)
        assertEquals(2, f.inputs.size)
        assertEquals(VoiceControlPhase.LISTENING, c.state.value.phase)
    }
    @Test fun pendingUtteranceDoesNotSubmitOrTimeoutWhileCompressing() {
        val f = Fake(); val c = VoiceCallController(f)
        c.start(profile); f.ready = false; f.finishRecognition()
        c.setCompressing(true); f.ready = true
        f.advance(60_000)
        assertTrue(c.state.value.active)
        assertTrue(f.sends.isEmpty())
        c.setCompressing(false); f.advance(50)
        assertEquals(1, f.sends.size)
    }
    @Test fun queuedResponseTimeoutCannotEndAnswerAfterFinalBody() {
        val f = Fake(); val c = VoiceCallController(f)
        c.start(profile, VoiceControlLimits(responseMillis = 1_000, drainMillis = 5_000))
        f.finishRecognition(); f.accept(owner)
        val oldTimer = f.timers.single { !it.cancelled && it.due == f.now + 1_000 }
        f.observations.last().body(VoiceAssistantBody(owner, "结束。", VoiceBodyStatus.COMPLETE))
        oldTimer.action(); f.flush()
        assertEquals(2, f.inputs.size)
        assertTrue(f.stopped.isEmpty())
        assertTrue(c.state.value.active)
        f.outputs.single().drained(); f.flush()
        assertEquals(VoiceControlPhase.LISTENING, c.state.value.phase)
        assertEquals("结束。", c.state.value.reply)
    }
    @Test fun queuedPollAcrossCompressionResumeUsesFreshDeadline() {
        val f = Fake(); val c = VoiceCallController(f)
        c.start(profile, VoiceControlLimits(receiptMillis = 1_000))
        f.ready = false; f.finishRecognition(); f.advance(950)
        val poll = f.timers.single { !it.cancelled }
        c.setCompressing(true); f.now += 50; poll.cancelled = true; poll.action()
        f.now += 10; c.setCompressing(false); f.ready = true; f.flush()
        assertTrue(c.state.value.active); assertEquals(1, f.sends.size)
    }
    @Test fun revokedAdmissionPreventsDeferredSubmission() {
        val f = Fake(); val c = VoiceCallController(f)
        c.start(profile); f.ready = false; f.finishRecognition()
        f.admissionValue = VoiceAdmission.CONFIGURATION_CHANGED; f.ready = true; f.advance(50)
        assertTrue(f.sends.isEmpty())
        assertEquals(VoiceControlStopReason.CONFIGURATION_CHANGED, c.state.value.reason)
    }
    @Test fun revokedAcceptedRunStopsWithoutOpeningOutput() {
        val f = Fake(); val c = VoiceCallController(f)
        c.start(profile); f.finishRecognition(); f.admissionValue = VoiceAdmission.REVOKED; f.accept(owner)
        assertTrue(f.outputs.isEmpty()); assertTrue(f.observations.isEmpty())
        assertEquals(listOf(owner), f.stopped)
    }
    @Test fun tailPartialCannotClaimCaptureRestarted() {
        val f = Fake(); val c = VoiceCallController(f)
        c.start(profile); val events = f.recognitions.single()
        events.captureStopped(); events.partial("尾部字幕"); events.listening(); f.flush()
        assertEquals(VoiceControlPhase.WAITING_FOR_FINAL, c.state.value.phase)
        assertEquals("尾部字幕", c.state.value.transcript); assertTrue(f.sends.isEmpty())
        events.finalResult("最终正文"); f.flush(); assertEquals(1, f.sends.size)
    }
    @Test fun lateAcceptedAfterHangupIsStoppedWithoutReplayingSend() {
        val f = Fake(); val c = VoiceCallController(f)
        val permit = c.start(profile)!!; f.finishRecognition()
        c.stop(permit); f.accept(owner)
        assertEquals(listOf(owner), f.stopped)
        assertEquals(1, f.sends.size)
        assertFalse(c.state.value.active)
        assertTrue(f.outputs.isEmpty())
    }
    @Test fun mutedCompressionDoesNotReopenUntilExplicitUnmute() {
        val f = Fake(); val c = VoiceCallController(f)
        c.start(profile); c.setMuted(true); c.setCompressing(true); c.setCompressing(false)
        assertEquals(1, f.inputs.size)
        assertEquals(VoiceControlPhase.MUTED, c.state.value.phase)
        c.setMuted(false)
        assertEquals(2, f.inputs.size)
    }
    @Test fun modelPickerSuspendsCaptureAndLateRecognitionCannotSend() {
        val f = Fake(); val c = VoiceCallController(f)
        c.start(profile); val old = f.recognitions.single()
        c.setModelPicker(true); old.finalResult("不能发送"); f.flush()
        assertTrue(f.sends.isEmpty())
        c.setModelPicker(false)
        assertEquals(2, f.inputs.size)
    }
    @Test fun continuousCallsAreNotLimitedByLegacyMaximumTurns() {
        val f = Fake(); val c = VoiceCallController(f)
        val permit = c.start(profile, VoiceControlLimits(maxTurns = 1))!!
        repeat(4) { i ->
            val answer = owner.copy(runId = "run-$i", messageId = "message-$i")
            f.finishRecognition("第$i 句"); f.accept(answer)
            f.body(answer, "回答。", VoiceBodyStatus.COMPLETE)
            f.outputs.last().drained(); f.flush()
            assertTrue(c.state.value.active)
        }
        assertEquals(4, f.sends.size)
        c.stop(permit)
    }
    @Test fun failedInputReleaseFencesFurtherCallsAndStillRevokes() {
        val f = Fake(); val c = VoiceCallController(f)
        val permit = c.start(profile)!!
        f.inputs.single().failClose = true; c.stop(permit)
        assertEquals(VoiceControlStopReason.RELEASE_FAILED, c.state.value.reason)
        assertEquals(1, f.revoked.size)
        assertNull(c.start(profile))
    }
}
