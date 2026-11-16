package app.daiyu.android.voice.control

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
        var throwRecognition = false
        var throwSubmit = false
        var ready = true
        override fun submissionReady() = ready
        data class Observation(val owner: VoiceAssistantOwner, val body: (VoiceAssistantBody) -> Unit, var cancelled: Boolean = false)
        val observations = mutableListOf<Observation>()
        class Output(val drained: () -> Unit, val failed: () -> Unit) : VoiceControlPlayback {
            val bodies = mutableListOf<Pair<String, Boolean>>()
            override var progress = 0L
            override var drainTimeoutMillis = 0L
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
            check(!throwRecognition) { "private-recognition-error" }
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
        var failStop = false
        val terminalDiagnostics = mutableListOf<Pair<VoiceControlStopReason, VoiceControlFailureStage?>>()
        override fun stopped(reason: VoiceControlStopReason, stage: VoiceControlFailureStage?) { terminalDiagnostics += reason to stage }
        override fun stopAcceptedRun(owner: VoiceAssistantOwner) { stopped += owner; check(!failStop) }
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


    @Test fun telephoneOutputHasStablePrefixesAndDropsReferencesBeforeBothCaptionAndSpeech() {
        val raw = "明天晴。[天气](https://example.org/a_(b)) 可以出门。\n\n来源：\n\n1. [天气](<https://example.org/source>)"
        val expected = "明天晴。天气 可以出门。"
        var previous = ""
        for (end in 0..raw.length) {
            val projected = app.daiyu.android.voice.VoiceCallOutput.text(raw.take(end), false)
            assertTrue("Must not retract speech at $end: $previous -> $projected", projected.startsWith(previous))
            assertTrue("Only stable telephone text at $end: $projected", expected.startsWith(projected))
            previous = projected
        }
        assertEquals(expected, app.daiyu.android.voice.VoiceCallOutput.text(raw, true))
        assertEquals("来源：用户告诉我的。编号[甲]保留，2026年也保留。",
            app.daiyu.android.voice.VoiceCallOutput.text("来源：用户告诉我的。编号[甲]保留，2026年也保留。", true))
        for (reference in listOf("https://example.org", "http://example.org", "www.example.org",
            "<https://example.org>", "[1](https://example.org)", "【1†source】", "\uE200cite\uE202source\uE201")) {
            val source = "好。$reference 可以。"
            var emitted = ""
            for (end in 0..source.length) {
                val next = app.daiyu.android.voice.VoiceCallOutput.text(source.take(end), false)
                assertTrue("Reference split at $end: $reference", next.startsWith(emitted))
                assertTrue("Reference must never enter speech: $next", "好。 可以。".startsWith(next))
                emitted = next
            }
            assertEquals("好。 可以。", app.daiyu.android.voice.VoiceCallOutput.text(source, true))
        }
        for (footer in listOf("来源：\n1.\n[材料](https://example.org)",
            "来源：[材料](https://example.org)", "来源：1. [材料](https://example.org)",
            "来源：\n- [材料](https://example.org)")) {
            val source = "答复。\n" + footer
            for (end in 0..source.length) assertTrue("No footer prefix at $end: $footer",
                "答复。".startsWith(app.daiyu.android.voice.VoiceCallOutput.text(source.take(end), false)))
            assertEquals("答复。", app.daiyu.android.voice.VoiceCallOutput.text(source, true))
        }
        assertEquals("请看", app.daiyu.android.voice.VoiceCallOutput.text(
            "[请看 https://example.org](https://example.org)", true))
        val f = Fake(); val c = VoiceCallController(f)
        c.start(profile); f.finishRecognition(); f.accept(owner)
        for (end in 0..raw.length step 3) {
            f.body(owner, raw.take(end))
            assertEquals(c.state.value.reply, f.outputs.single().bodies.last().first)
        }
        f.body(owner, raw, VoiceBodyStatus.COMPLETE)
        assertEquals(expected, c.state.value.reply)
        assertEquals(expected to true, f.outputs.single().bodies.last())
        assertTrue(c.state.value.active)
        c.stop(c.state.value.permitId!!)
    }

    @Test fun referenceOnlyTelephoneAnswerDrainsEmptyAndTheNextAnswerHasAFreshProjection() {
        val f = Fake(); val c = VoiceCallController(f)
        c.start(profile); f.finishRecognition(); f.accept(owner)
        f.body(owner, "来源：\n\n1. [链接](https://example.org)", VoiceBodyStatus.COMPLETE)
        assertEquals("", c.state.value.reply)
        assertEquals("" to true, f.outputs.single().bodies.last())
        f.outputs.single().drained(); f.flush()
        assertTrue(c.state.value.active)
        f.finishRecognition("next turn")
        val next = owner.copy(runId = "run-2", messageId = "assistant-2")
        f.accept(next); f.body(next, "新的口语回答。", VoiceBodyStatus.COMPLETE)
        assertEquals("新的口语回答。", c.state.value.reply)
        assertEquals("新的口语回答。" to true, f.outputs.last().bodies.last())
        c.stop(c.state.value.permitId!!)
    }

    @Test fun lateAcceptedCleanupAndBridgeFailurePublishReleaseStageAndFenceRestart() {
        for (bridge in listOf(false, true)) {
            val f = Fake(); val c = VoiceCallController(f)
            val permit = c.start(profile)!!
            f.finishRecognition(); c.stop(permit)
            assertEquals(VoiceControlStopReason.USER to null, f.terminalDiagnostics.single())
            if (bridge) c.acceptedReleaseFailed() else { f.failStop = true; f.accept(owner) }
            assertEquals(VoiceControlStopReason.RELEASE_FAILED, c.state.value.reason)
            assertEquals(VoiceControlFailureStage.RELEASE, c.state.value.failureStage)
            assertEquals(VoiceControlStopReason.RELEASE_FAILED to VoiceControlFailureStage.RELEASE, f.terminalDiagnostics.last())
            assertEquals(2, f.terminalDiagnostics.size)
            assertNull(c.start(profile))
        }
    }
    @Test fun drainProgressEventuallyTimesOutAfterProgressStops() {
        val f = Fake(); val c = VoiceCallController(f)
        c.start(profile, VoiceControlLimits(drainMillis = 1_000))
        f.finishRecognition(); f.accept(owner); f.body(owner, "回答。", VoiceBodyStatus.COMPLETE)
        f.outputs.single().progress++
        f.advance(1_001)
        assertTrue(c.state.value.active)
        f.advance(1_001)
        assertEquals(VoiceControlStopReason.TIMEOUT, c.state.value.reason)
        assertEquals(VoiceControlFailureStage.DRAIN, c.state.value.failureStage)
        assertEquals(VoiceControlStopReason.TIMEOUT to VoiceControlFailureStage.DRAIN, f.terminalDiagnostics.single())
    }
    @Test fun realSearchProgressExtendsOnlyTheIdleResponseDeadlineAndFinalStillWaitsForDrain() {
        val f = Fake(); val c = VoiceCallController(f)
        c.start(profile, VoiceControlLimits(responseMillis = 1_000, drainMillis = 5_000))
        f.finishRecognition(); f.accept(owner)
        val original = f.timers.last { it.due == f.now + 1_000 }
        f.advance(600)
        f.observations.last().body(VoiceAssistantBody(owner, "", VoiceBodyStatus.STREAMING, searching = true)); f.flush()
        original.action(); f.flush()
        assertTrue(c.state.value.active)
        f.advance(600)
        assertTrue(c.state.value.searching)
        f.body(owner, "已查到。", VoiceBodyStatus.COMPLETE)
        assertTrue(c.state.value.replyTextComplete)
        assertTrue(c.state.value.active)
        f.outputs.single().drained(); f.flush()
        assertEquals(VoiceControlPhase.LISTENING, c.state.value.phase)
        assertEquals(1, f.sends.size)
    }
    @Test fun repeatedUnchangedSearchCannotKeepAStalledResponseAlive() {
        val f = Fake(); val c = VoiceCallController(f)
        c.start(profile, VoiceControlLimits(responseMillis = 1_000))
        f.finishRecognition(); f.accept(owner)
        val search = VoiceAssistantBody(owner, "", VoiceBodyStatus.STREAMING, searching = true)
        f.observations.last().body(search); f.flush()
        f.advance(600); f.observations.last().body(search); f.flush()
        f.advance(401)
        assertEquals(VoiceControlStopReason.TIMEOUT, c.state.value.reason)
        assertEquals(VoiceControlFailureStage.RESPONSE, c.state.value.failureStage)
    }
    @Test fun completedBodyUsesPlaybackBudgetAndOnlyRealProgressExtendsDrain() {
        val f = Fake(); val c = VoiceCallController(f)
        c.start(profile)
        f.finishRecognition(); f.accept(owner)
        f.outputs.single().drainTimeoutMillis = 450_000L
        f.body(owner, "长回答。", VoiceBodyStatus.COMPLETE)
        f.advance(60_001)
        assertTrue(c.state.value.active)
        f.outputs.single().progress++
        f.advance(390_000)
        assertTrue(c.state.value.active)
        f.outputs.single().drained(); f.flush()
        assertEquals(VoiceControlPhase.LISTENING, c.state.value.phase)
        assertEquals(1, f.sends.size)
    }

    @Test fun synchronousStopFromConnectingObserverDoesNotOpenRecognition() = runBlocking {
        val f = Fake(); val c = VoiceCallController(f)
        val observer = launch(Dispatchers.Unconfined) {
            c.state.collect { value ->
                if (value.phase == VoiceControlPhase.CONNECTING) c.stop(checkNotNull(value.permitId))
            }
        }
        try {
            val permit = c.start(profile)
            assertEquals(VoiceControlPhase.STOPPED, c.state.value.phase)
            assertEquals(VoiceControlStopReason.USER, c.state.value.reason)
            assertEquals(permit, c.state.value.permitId)
            assertTrue(f.recognitions.isEmpty()); assertTrue(f.inputs.isEmpty()); assertTrue(f.timers.isEmpty())
            assertEquals(listOf(permit), f.revoked.map { it.id })
        } finally { observer.cancel() }
    }

    @Test fun startPublishesConnectingBeforeAsynchronousListening() {
        val f = Fake(); val c = VoiceCallController(f)
        val permit = c.start(profile)
        assertNotNull(permit)
        assertEquals(VoiceControlPhase.CONNECTING, c.state.value.phase)
        assertTrue(c.state.value.active)
        assertEquals(permit, c.state.value.permitId)
        assertEquals(1, f.inputs.size)
        assertTrue(f.queue.isEmpty())
        f.recognitions.single().listening()
        assertEquals(VoiceControlPhase.CONNECTING, c.state.value.phase)
        f.flush()
        assertEquals(VoiceControlPhase.LISTENING, c.state.value.phase)
        assertEquals(permit, c.state.value.permitId)
    }
    @Test fun restartReplacesStoppedPermitBeforeListeningAndRejectsOldCallbacks() {
        val f = Fake(); val c = VoiceCallController(f)
        val first = checkNotNull(c.start(profile))
        val old = f.recognitions.single()
        c.stop(first)
        assertEquals(VoiceControlPhase.STOPPED, c.state.value.phase)
        val second = c.start(profile)
        assertNotNull(second); assertNotEquals(first, second)
        assertEquals(VoiceControlPhase.CONNECTING, c.state.value.phase)
        assertEquals(second, c.state.value.permitId)
        old.listening(); old.failed(); old.finalResult("stale"); f.flush()
        assertEquals(VoiceControlPhase.CONNECTING, c.state.value.phase)
        assertTrue(f.sends.isEmpty())
        f.recognitions.last().listening(); f.flush()
        assertEquals(VoiceControlPhase.LISTENING, c.state.value.phase)
        assertEquals(second, c.state.value.permitId)
    }
    @Test fun synchronousRecognitionFailureStopsTheNewPermitAndReleasesIt() {
        val f = Fake(); val c = VoiceCallController(f)
        f.throwRecognition = true
        val permit = c.start(profile)
        assertNotNull(permit)
        assertEquals(VoiceControlPhase.STOPPED, c.state.value.phase)
        assertFalse(c.state.value.active)
        assertEquals(permit, c.state.value.permitId)
        assertEquals(VoiceControlStopReason.RECOGNITION_FAILED, c.state.value.reason)
        assertEquals(listOf(permit), f.revoked.map { it.id })
        assertTrue(f.inputs.isEmpty()); assertTrue(f.timers.isEmpty())
        f.throwRecognition = false
        val next = c.start(profile)
        assertNotEquals(permit, next)
        assertEquals(VoiceControlPhase.CONNECTING, c.state.value.phase)
        assertEquals(next, c.state.value.permitId)
    }

    @Test fun speechInterruptsSearchAndRejectsLateSuccessWithoutClosingMicrophone() {
        val f = Fake(); val c = VoiceCallController(f)
        c.start(profile); f.finishRecognition("查一下"); f.accept(owner)
        val observation = f.observations.single()
        observation.body(VoiceAssistantBody(owner, "", VoiceBodyStatus.STREAMING, searching = true)); f.flush()
        assertTrue(c.state.value.searching); assertEquals(2, f.inputs.size)
        assertEquals(0, f.inputs.last().closes)
        f.recognitions.last().speechStarted(); f.flush()
        assertEquals(listOf(owner), f.stopped); assertTrue(observation.cancelled)
        assertFalse(c.state.value.searching); assertNull(c.state.value.searchCompletion)
        observation.body(VoiceAssistantBody(owner, "旧结果", VoiceBodyStatus.COMPLETE, searchCompletion = "late")); f.flush()
        assertFalse(c.state.value.searching); assertNull(c.state.value.searchCompletion)
        assertEquals("", c.state.value.reply)
    }
    @Test fun microphoneStaysOpenWhileAnswerStreamsAndSpeechInterruptsExactRun() {
        val f = Fake(); val c = VoiceCallController(f)
        c.start(profile); f.finishRecognition("你好"); f.accept(owner)
        assertEquals(VoiceControlPhase.WAITING_FOR_BODY, c.state.value.phase)
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
    @Test fun modelPickerCancellationClearsSearchAndRejectsLateSuccess() {
        val f = Fake(); val c = VoiceCallController(f)
        c.start(profile); f.finishRecognition("搜索"); f.accept(owner)
        f.observations.single().body(VoiceAssistantBody(owner, "", VoiceBodyStatus.STREAMING, true))
        f.flush(); assertTrue(c.state.value.searching)
        val late = f.observations.single().body
        c.setModelPicker(true)
        assertFalse(c.state.value.searching); assertNull(c.state.value.searchCompletion)
        c.setModelPicker(false)
        late(VoiceAssistantBody(owner, "旧结果", VoiceBodyStatus.COMPLETE, false, "late")); f.flush()
        assertFalse(c.state.value.searching); assertNull(c.state.value.searchCompletion)
        assertEquals(listOf(owner), f.stopped)
        assertTrue(c.state.value.active)
    }
    @Test fun hangUpClearsAllPublicCallContentBeforeAnyNewRecognitionArrives() {
        val f = Fake(); val c = VoiceCallController(f)
        val oldPermit = c.start(profile)!!
        f.finishRecognition("上一通识别"); f.accept(owner)
        f.observations.single().body(VoiceAssistantBody(owner, "上一通回答", VoiceBodyStatus.STREAMING, true))
        f.flush()
        assertEquals("上一通识别", c.state.value.transcript)
        assertEquals("上一通回答", c.state.value.reply)
        val lateBody = f.observations.single().body
        val lateInput = f.recognitions.first()
        c.stop(oldPermit)
        assertEquals(VoiceControlStopReason.USER, c.state.value.reason)
        assertEquals(oldPermit, c.state.value.permitId)
        assertEquals("", c.state.value.transcript); assertEquals("", c.state.value.reply)
        assertEquals(0L, c.state.value.captionEpoch)
        val nextPermit = c.start(profile)!!
        assertNotEquals(oldPermit, nextPermit)
        assertEquals(VoiceControlPhase.CONNECTING, c.state.value.phase)
        assertEquals("", c.state.value.transcript); assertEquals("", c.state.value.reply)
        lateInput.partial("晚到的旧识别"); lateInput.finalResult("晚到的旧最终识别")
        lateBody(VoiceAssistantBody(owner, "晚到回答", VoiceBodyStatus.COMPLETE, false, "old-success"))
        f.flush()
        assertEquals(nextPermit, c.state.value.permitId)
        assertEquals("", c.state.value.transcript); assertEquals("", c.state.value.reply)
        assertFalse(c.state.value.searching); assertNull(c.state.value.searchCompletion)
        assertEquals(1, f.sends.size)
    }
    @Test fun sameCallKeepsItsOwnTranscriptAcrossMuteAndCompression() {
        val f = Fake(); val c = VoiceCallController(f)
        c.start(profile); f.recognitions.last().partial("本通字幕"); f.flush()
        c.setMuted(true); c.setMuted(false)
        assertEquals("本通字幕", c.state.value.transcript)
        c.setCompressing(true); c.setCompressing(false)
        assertEquals("本通字幕", c.state.value.transcript)
    }
    @Test fun releaseFailureClearsContentButKeepsTheFailClosedLatch() {
        val f = Fake(); val c = VoiceCallController(f)
        val permit = c.start(profile)!!
        f.recognitions.single().partial("应清理"); f.flush()
        f.inputs.single().failClose = true
        c.stop(permit)
        assertEquals("", c.state.value.transcript); assertEquals("", c.state.value.reply)
        assertEquals(VoiceControlStopReason.RELEASE_FAILED, c.state.value.reason)
        assertNull(c.start(profile))
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

    @Test fun failedReasoningChoiceRemainsTurnLocalWithoutEndingTheCallOrSpeakingTheError() {
        val f = Fake(); val c = VoiceCallController(f)
        val permit = c.start(profile)!!; f.finishRecognition(); f.accept(owner)
        val oldBody = f.observations.single().body
        oldBody(VoiceAssistantBody(owner, "", VoiceBodyStatus.FAILED,
            unsupportedReasoning = app.daiyu.android.domain.RequestReasoningEffort.OFF))
        f.flush()
        assertTrue(c.state.value.active); assertTrue(c.state.value.responseFailed)
        assertEquals(permit, c.state.value.permitId); assertNull(c.state.value.reason)
        assertEquals(app.daiyu.android.domain.RequestReasoningEffort.OFF, c.state.value.unsupportedReasoning)
        assertEquals("", c.state.value.reply); assertTrue(f.outputs.single().bodies.isEmpty())
        assertEquals(1, f.outputs.single().closes); assertTrue(f.observations.single().cancelled)
        assertEquals(1, f.sends.size); assertTrue(f.stopped.isEmpty()); assertTrue(f.revoked.isEmpty())
        f.recognitions.last().speechStarted(); f.flush()
        assertFalse(c.state.value.responseFailed); assertNull(c.state.value.unsupportedReasoning)
        oldBody(VoiceAssistantBody(owner, "", VoiceBodyStatus.FAILED,
            unsupportedReasoning = app.daiyu.android.domain.RequestReasoningEffort.HIGH))
        f.flush()
        assertEquals(permit, c.state.value.permitId); assertTrue(c.state.value.active)
        assertNull(c.state.value.unsupportedReasoning); assertEquals(1, f.sends.size)
    }

    @Test fun failedOrCancelledAnswerKeepsItsBodyAndMicrophoneWithoutRetryOrSearchSuccess() {
        for (status in listOf(VoiceBodyStatus.FAILED, VoiceBodyStatus.CANCELLED)) {
            val f = Fake(); val c = VoiceCallController(f)
            val permit = c.start(profile)!!; f.finishRecognition(); f.accept(owner)
            val old = f.observations.single().body
            old(VoiceAssistantBody(owner, "已有的正文。", VoiceBodyStatus.STREAMING, true, "old-check")); f.flush()
            old(VoiceAssistantBody(owner, "已有的正文。更多内容", status, false, "late-check")); f.flush()
            assertTrue(c.state.value.active); assertTrue(c.state.value.responseFailed)
            assertEquals(permit, c.state.value.permitId); assertEquals("已有的正文。更多内容", c.state.value.reply)
            assertFalse(c.state.value.replyTextComplete); assertFalse(c.state.value.searching)
            assertNull(c.state.value.searchCompletion); assertEquals(1, f.sends.size)
            assertEquals(listOf("已有的正文。" to false), f.outputs.single().bodies)
            assertEquals(1, f.outputs.single().closes); assertTrue(f.observations.single().cancelled)
            assertTrue(f.revoked.isEmpty()); assertTrue(f.stopped.isEmpty()); assertEquals(0, f.inputs.last().closes)
            old(VoiceAssistantBody(owner, "迟到成功", VoiceBodyStatus.COMPLETE, searchCompletion = "late")); f.flush()
            assertEquals("已有的正文。更多内容", c.state.value.reply); assertNull(c.state.value.searchCompletion)
            f.finishRecognition("新的问题")
            assertEquals(2, f.sends.size); assertFalse(c.state.value.responseFailed)
            assertEquals(permit, c.state.value.permitId)
            c.stop(permit)
        }
    }

    @Test fun failedAnswerStillFencesTheCallWhenOutputReleaseCannotBeConfirmed() {
        val f = Fake(); val c = VoiceCallController(f)
        c.start(profile); f.finishRecognition(); f.accept(owner)
        f.outputs.single().failClose = true
        f.body(owner, "部分正文", VoiceBodyStatus.FAILED)
        assertFalse(c.state.value.active)
        assertEquals(VoiceControlStopReason.RELEASE_FAILED, c.state.value.reason)
        assertNull(c.start(profile)); assertEquals(1, f.sends.size)
    }

    @Test fun lateFailureCannotReplaceAnAlreadyCompleteAnswerWhileAudioDrains() {
        val f = Fake(); val c = VoiceCallController(f)
        c.start(profile); f.finishRecognition(); f.accept(owner)
        f.body(owner, "完整正文。", VoiceBodyStatus.COMPLETE)
        f.body(owner, "迟到失败", VoiceBodyStatus.FAILED)
        assertTrue(c.state.value.active); assertFalse(c.state.value.responseFailed)
        assertEquals("完整正文。", c.state.value.reply)
        f.outputs.single().drained(); f.flush()
        assertTrue(c.state.value.active); assertTrue(f.stopped.isEmpty())
    }


    @Test fun responseCauseAndBrowserActivityStayTurnLocalAndNeverBecomeSpeech() {
        val f = Fake(); val c = VoiceCallController(f)
        val permit = c.start(profile)!!; f.finishRecognition(); f.accept(owner)
        val old = f.observations.single().body
        old(VoiceAssistantBody(owner, "", VoiceBodyStatus.STREAMING, browsing = true)); f.flush()
        assertTrue(c.state.value.browsing); assertFalse(c.state.value.searching)
        val failure = "浏览器执行结果未确认；本轮已停止，未自动重试"
        old(VoiceAssistantBody(owner, "已有正文。", VoiceBodyStatus.FAILED,
            responseFailure = failure, browsing = true, searchCompletion = "late")); f.flush()
        assertTrue(c.state.value.active); assertTrue(c.state.value.responseFailed)
        assertEquals(failure, c.state.value.responseFailure); assertEquals("已有正文。", c.state.value.reply)
        assertFalse(c.state.value.browsing); assertNull(c.state.value.searchCompletion)
        assertTrue(f.outputs.single().bodies.none { failure in it.first })
        assertEquals(1, f.sends.size)
        f.recognitions.last().speechStarted(); f.flush()
        assertNull(c.state.value.responseFailure); assertFalse(c.state.value.responseFailed)
        old(VoiceAssistantBody(owner, "late", VoiceBodyStatus.FAILED, responseFailure = failure)); f.flush()
        assertNull(c.state.value.responseFailure); assertEquals(permit, c.state.value.permitId)
        c.stop(permit)
    }


    @Test fun browserResultsCanContinueWithoutACallCountLimitAndStreamSpeechBeforeFinal() {
        val f = Fake(); val c = VoiceCallController(f)
        val permit = c.start(profile)!!; f.finishRecognition(); f.accept(owner)
        val deliver = f.observations.single().body
        repeat(12) {
            deliver(VoiceAssistantBody(owner, "", VoiceBodyStatus.STREAMING, browsing = true)); f.flush()
            assertTrue(c.state.value.browsing)
            deliver(VoiceAssistantBody(owner, "", VoiceBodyStatus.STREAMING, browsing = false)); f.flush()
            assertFalse(c.state.value.browsing)
            assertTrue(c.state.value.active)
        }
        val first = "已经取得可读资料。"
        deliver(VoiceAssistantBody(owner, first, VoiceBodyStatus.STREAMING, browsing = true)); f.flush()
        assertEquals(first, c.state.value.reply)
        assertEquals(first to false, f.outputs.single().bodies.last())
        assertTrue(c.state.value.browsing)
        val final = first + "我先告诉你已经确认的部分。"
        deliver(VoiceAssistantBody(owner, final, VoiceBodyStatus.COMPLETE)); f.flush()
        assertEquals(final, c.state.value.reply)
        assertEquals(final to true, f.outputs.single().bodies.last())
        assertFalse(c.state.value.browsing)
        assertTrue(f.stopped.isEmpty())
        f.outputs.single().drained(); f.flush()
        assertTrue(c.state.value.active)
        assertEquals(permit, c.state.value.permitId)
        c.stop(permit)
    }

}
