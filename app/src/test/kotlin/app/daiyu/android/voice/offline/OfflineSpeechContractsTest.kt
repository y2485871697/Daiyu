package app.daiyu.android.voice.offline

import app.daiyu.android.voice.AudioOwnerGate
import app.daiyu.android.voice.DictationDraftTarget
import app.daiyu.android.voice.VoiceOwner
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class OfflineSpeechContractsTest {
    @Test fun immutableModelManifestAndDeadlines() {
        assertEquals("05945efc40afe4b572542f01104ca5c413a9f6e1", SpeechModelManifest.REVISION)
        assertTrue(SpeechModelManifest.BASE_URL.contains("/resolve/${SpeechModelManifest.REVISION}/"))
        assertEquals(7, SpeechModelManifest.assets.size)
        assertEquals(141346210L, SpeechModelManifest.totalBytes)
        assertEquals(7, SpeechModelManifest.assets.map { it.name }.toSet().size)
        assertTrue(SpeechModelManifest.assets.all { it.sha256.matches(Regex("[a-f0-9]{64}")) && it.bytes > 0 })
        assertFalse(SpeechInputPolicy.canEnable(false, false, false))
        assertFalse(SpeechInputPolicy.canEnable(true, true, false))
        assertFalse(SpeechInputPolicy.canEnable(true, false, true))
        assertTrue(SpeechInputPolicy.canEnable(true, false, false))
        assertFalse(SpeechInputPolicy.timedOut(7999, false))
        assertTrue(SpeechInputPolicy.timedOut(8000, false))
        assertFalse(SpeechInputPolicy.timedOut(59999, true))
        assertTrue(SpeechInputPolicy.timedOut(60000, true))
    }

    @Test fun selectionAndStaleEditorDoNotOverwriteExistingText() {
        val draft = SpeechDraft("prefix REPLACE suffix", 14, 7)
        assertEquals("prefix 你好 suffix", draft.accept("prefix REPLACE suffix", "你好"))
        assertEquals(9, draft.cursor)
        assertNull(draft.accept("user edited", "late text"))
        assertEquals("prefix 你好 suffix", draft.expectedText)
    }

    @Test fun modelFileRequiresBothExactLengthAndHash() {
        val file = File.createTempFile("speech-contract", ".bin")
        try {
            val bytes = "fixture".toByteArray()
            file.writeBytes(bytes)
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val asset = SpeechModelManifest.Asset("fixture", bytes.size.toLong(), sha)
            assertTrue(OfflineSpeechPack.valid(file, asset))
            file.writeText("fixturE")
            assertFalse(OfflineSpeechPack.valid(file, asset))
            file.writeText("fixture-too-long")
            assertFalse(OfflineSpeechPack.valid(file, asset))
        } finally { file.delete() }
    }

    private class Attempt(val events: OfflineSpeechSession.Events) : OfflineSpeechAttempt {
        var starts = 0
        var closes = 0
        override fun start() { starts++ }
        override fun close() { closes++ }
    }

    @Test fun hostPreviewsAreNotDraftsAndFinalReplacesOnlyCapturedSelection() {
        val attempts = mutableListOf<Attempt>()
        var text = "before OLD after"
        var cursor = -1
        var commits = 0
        val target = DictationDraftTarget("chat", 2, 3)
        val host = OfflineComposerHost(
            factory = { _, events -> Attempt(events).also { attempts += it } },
            enabled = { true }, permission = { true }, requestPermission = { fail("unexpected permission") },
            current = { it == target }, currentText = { text },
            applyDraft = { captured, next, position -> assertEquals(target, captured); text = next; cursor = position; commits++ })
        host.start(target, text, 7, 10)
        assertFalse(host.state.value.listening) // Loading a model / clicking is not capture.
        assertEquals(target, host.state.value.target)
        attempts.single().events.partial("preview")
        assertFalse(host.state.value.listening) // A partial alone is not the authoritative event.
        attempts.single().events.listening()
        assertTrue(host.state.value.listening)
        assertEquals("before OLD after", text)
        assertEquals("preview", host.state.value.preview)
        attempts.single().events.complete("你好")
        assertEquals("before 你好 after", text)
        assertEquals(9, cursor)
        assertEquals(1, commits)
        attempts.single().events.complete("duplicate")
        attempts.single().events.listening()
        assertFalse(host.state.value.listening) // Late capture cannot resurrect a retired owner.
        assertNull(host.state.value.target)
        assertEquals(1, commits)
        assertEquals(1, attempts.single().closes)
    }

    @Test fun permissionNeverAutoStartsAndInvalidatedOrOtherEditorResultsCannotCommit() {
        val attempts = mutableListOf<Attempt>()
        var granted = false
        var requests = 0
        var commits = 0
        var current = true
        val target = DictationDraftTarget(null, 0, 0)
        val host = OfflineComposerHost(
            factory = { _, events -> Attempt(events).also { attempts += it } },
            enabled = { true }, permission = { granted }, requestPermission = { requests++ },
            current = { current }, currentText = { "draft" }, applyDraft = { _, _, _ -> commits++ })
        host.start(target, "draft", 0, 5)
        assertEquals(1, requests)
        assertTrue(attempts.isEmpty())
        granted = true
        assertTrue(attempts.isEmpty())
        host.start(target, "draft", 0, 5)
        current = false // edit, send, another conversation, or edit-then-undo revision mismatch.
        attempts.single().events.complete("wrong editor")
        assertEquals(0, commits)
        host.invalidate()
        current = true
        attempts.single().events.complete("late")
        assertEquals(0, commits)
        host.start(target.copy(sessionId = "new-attempt"), "draft", 0, 5)
        host.close()
        attempts.last().events.complete("after destruction")
        assertEquals(0, commits)
    }

    private class Events : OfflineSpeechSession.Events {
        val values = mutableListOf<String>()
        override fun listening() { values += "listening" }
        override fun partial(text: String) { values += "partial:$text" }
        override fun complete(text: String) { values += "complete:$text" }
        override fun cancelled() { values += "cancelled" }
        override fun failed() { values += "failed" }
    }

    private class Device : OfflineSpeechDevice {
        var loads = 0
        var opens = 0
        var releases = 0
        var closes = 0
        var finished = false
        var clock = 0L
        var tick = 100L
        var endpoint = true
        var transcript = "你好"
        var failLoad = false
        var failRelease = false
        var duringLoad: () -> Unit = {}
        var duringAccept: () -> Unit = {}
        val accepted = mutableListOf<FloatArray>()
        override fun load(): OfflineSpeechDevice.Decoder {
            loads++
            duringLoad()
            if (failLoad) error("native load failed")
            return object : OfflineSpeechDevice.Decoder {
                override fun accept(samples: FloatArray) { accepted += samples; duringAccept() }
                override fun ready() = false
                override fun decode() = Unit
                override fun text() = transcript
                override fun endpoint() = endpoint
                override fun finish() { finished = true }
                override fun close() { closes++ }
            }
        }
        override fun microphone(): OfflineSpeechDevice.Microphone {
            opens++
            return object : OfflineSpeechDevice.Microphone {
                override fun start() = Unit
                override fun read(samples: ShortArray): Int {
                    samples[0] = Short.MIN_VALUE; samples[1] = 0; samples[2] = Short.MAX_VALUE
                    clock += tick
                    return 3
                }
                override fun release() { releases++; if (failRelease) error("unconfirmed release") }
            }
        }
        override fun now() = clock
        override fun idle() { clock += tick }
    }

    private val directExecutor = Executor { it.run() }
    private fun owner(id: String = "one") = VoiceOwner("chat", id, id)

    @Test fun realPipelineContractNormalizesPcmAndClosesBeforeFinalCallback() {
        val gate = AudioOwnerGate()
        val device = Device()
        val events = Events()
        val pending = mutableListOf<() -> Unit>()
        val session = OfflineSpeechSession(gate, owner(), { true }, device, events, directExecutor, { pending += it })
        session.start()
        assertEquals(-1f, device.accepted.first()[0], 0f)
        assertEquals(0f, device.accepted.first()[1], 0f)
        assertEquals(32767f / 32768f, device.accepted.first()[2], 0f)
        assertEquals(4800, device.accepted.last().size)
        assertTrue(device.finished)
        assertEquals(1, device.releases)
        assertEquals(1, device.closes)
        assertTrue(events.values.isEmpty())
        pending.forEach { it() }
        assertEquals(listOf("complete:你好"), events.values)
        val next = gate.acquire(owner("next")) {}
        assertTrue(gate.current(next))
    }

    @Test fun cancellationDuringNativeLoadNeverOpensMicrophoneAndDoesNotReleaseNextOwner() {
        val gate = AudioOwnerGate()
        val device = Device()
        val events = Events()
        var next: AudioOwnerGate.Lease? = null
        device.duringLoad = { next = gate.acquire(owner("next")) {} }
        val session = OfflineSpeechSession(gate, owner(), { true }, device, events, directExecutor, { it() })
        session.start()
        assertEquals(0, device.opens)
        assertEquals(1, device.closes)
        assertTrue(gate.current(checkNotNull(next)))
        assertEquals(listOf("cancelled"), events.values)
    }

    @Test fun preemptionReleasesAudioBeforeNextOwnerAndNeverFlushesCancelledDecoder() {
        val gate = AudioOwnerGate()
        val device = Device()
        val events = Events()
        device.duringAccept = {
            gate.acquire(owner("next")) {}
            assertEquals(1, device.releases)
        }
        OfflineSpeechSession(gate, owner(), { true }, device, events, directExecutor, { it() }).start()
        assertEquals(1, device.releases)
        assertEquals(1, device.closes)
        assertFalse(device.finished)
        assertFalse(events.values.any { it.startsWith("complete:") })
    }

    @Test fun lateFinalAfterExplicitCloseIsSuppressedEvenAfterNaturalGateRelease() {
        val events = Events()
        val pending = mutableListOf<() -> Unit>()
        val session = OfflineSpeechSession(AudioOwnerGate(), owner(), { true }, Device(), events, directExecutor, { pending += it })
        session.start()
        session.close()
        pending.forEach { it() }
        assertTrue(events.values.isEmpty())
    }

    @Test fun strictReleaseFailurePoisonsSharedGateRatherThanPretendingCleanupSucceeded() {
        val gate = AudioOwnerGate()
        val device = Device().apply { failRelease = true }
        val events = Events()
        OfflineSpeechSession(gate, owner(), { true }, device, events, directExecutor, { it() }).start()
        assertTrue(events.values.contains("failed"))
        assertEquals(1, device.closes)
        try { gate.acquire(owner("next")) {}; fail("poisoned gate admitted another owner") }
        catch (_: IllegalStateException) { }
    }

    @Test fun naturalEndpointReleaseFailureRemainsStrictAfterLeaseHasAlreadyRetired() {
        val gate = AudioOwnerGate()
        val device = Device().apply { failRelease = true }
        val events = Events()
        val session = OfflineSpeechSession(gate, owner(), { true }, device, events, directExecutor, { it() })
        session.start()
        assertEquals(listOf("failed"), events.values)
        assertEquals(1, device.releases)
        assertEquals(1, device.closes)
        repeat(2) {
            try { session.close(); fail("retired lease must not hide cached release failure") }
            catch (failure: IllegalStateException) { assertEquals("unconfirmed release", failure.message) }
        }
        assertEquals(1, device.releases) // No retry/reopen or second JNI close.
        assertEquals(1, device.closes)
        try { gate.acquire(owner("next")) {}; fail("must preserve gate poison") }
        catch (_: IllegalStateException) { }
    }

    @Test fun universalNaturalReleaseFailureEndsReleaseFailedAndCannotStartAgain() {
        val queue = java.util.ArrayDeque<() -> Unit>()
        val gate = AudioOwnerGate()
        val device = Device().apply { failRelease = true }
        val ports = object : app.daiyu.android.voice.control.VoiceControlPorts {
            override fun dispatch(action: () -> Unit) { queue.addLast(action) }
            override fun nowMillis() = 0L
            override fun schedule(delayMillis: Long, action: () -> Unit) = app.daiyu.android.voice.control.VoiceControlCancellation { }
            override fun entrySnapshot(profile: app.daiyu.android.voice.control.VoiceControlProfile) =
                app.daiyu.android.voice.control.VoiceEntrySnapshot("chat", "foreground", setOf(app.daiyu.android.voice.control.VoiceEntryMode.UNIVERSAL), true, true, microphoneGranted = true)
            override fun admission(permit: app.daiyu.android.voice.control.VoiceControlPermit) = app.daiyu.android.voice.control.VoiceAdmission.READY
            override fun requestMicrophonePermission(permit: app.daiyu.android.voice.control.VoiceControlPermit) = error("already granted fixture")
            override fun recognize(turn: app.daiyu.android.voice.control.VoiceControlTurn, events: app.daiyu.android.voice.control.VoiceControlPorts.RecognitionEvents): app.daiyu.android.voice.control.VoiceControlRecognition {
                val session = OfflineSpeechSession(gate, owner(), { true }, device, object : OfflineSpeechSession.Events {
                    override fun listening() = events.listening()
                    override fun partial(text: String) = events.partial(text)
                    override fun complete(text: String) = events.finalResult(text)
                    override fun cancelled() = events.failed()
                    override fun failed() = events.failed()
                }, directExecutor, { queue.addLast(it) })
                session.start()
                return object : app.daiyu.android.voice.control.VoiceControlRecognition {
                    override fun closeAndDrain() = session.close()
                }
            }
            override fun submit(turn: app.daiyu.android.voice.control.VoiceControlTurn, finalText: String, result: (app.daiyu.android.voice.control.VoiceControlReceipt) -> Unit) = error("release failure must never send")
            override fun observe(owner: app.daiyu.android.voice.control.VoiceAssistantOwner, body: (app.daiyu.android.voice.control.VoiceAssistantBody) -> Unit): app.daiyu.android.voice.control.VoiceControlCancellation = error("no receipt")
            override fun playback(turn: app.daiyu.android.voice.control.VoiceControlTurn, owner: app.daiyu.android.voice.control.VoiceAssistantOwner, drained: () -> Unit, failed: () -> Unit): app.daiyu.android.voice.control.VoiceControlPlayback = error("no receipt")
            override fun stopAcceptedRun(owner: app.daiyu.android.voice.control.VoiceAssistantOwner) = error("no receipt")
            override fun revoke(permit: app.daiyu.android.voice.control.VoiceControlPermit) = Unit
        }
        val controller = app.daiyu.android.voice.control.VoiceModeController(ports)
        val profile = app.daiyu.android.voice.control.VoiceControlProfile("offline", 0, "offline")
        assertNotNull(controller.start(profile))
        while (queue.isNotEmpty()) queue.removeFirst().invoke()
        assertEquals(app.daiyu.android.voice.control.VoiceControlStopReason.RELEASE_FAILED, controller.state.value.reason)
        assertNull(controller.start(profile))
        assertEquals(1, device.loads)
        assertEquals(1, device.releases)
    }

    @Test fun noTextAndMaximumTimeoutsAreFiniteAndNeverFallBackToSystemRecognizer() {
        for ((text, expectedMs) in listOf("" to 8000L, "speech" to 60000L)) {
            val device = Device().apply { endpoint = false; transcript = text; tick = 1000 }
            val events = Events()
            OfflineSpeechSession(AudioOwnerGate(), owner(), { true }, device, events, directExecutor, { it() }).start()
            assertEquals(expectedMs, device.clock)
            assertTrue(device.finished)
            assertEquals(1, device.releases)
            assertEquals("complete:$text", events.values.last())
        }
        val device = Device().apply { failLoad = true }
        val events = Events()
        OfflineSpeechSession(AudioOwnerGate(), owner(), { true }, device, events, directExecutor, { it() }).start()
        assertEquals(0, device.opens)
        assertEquals(listOf("failed"), events.values)
    }
}
