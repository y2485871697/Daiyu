package app.daiyu.android.voice.duplex

import app.daiyu.android.voice.AudioOwnerGate
import app.daiyu.android.voice.VoiceOwner
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic wire fixtures, not a claim of live-provider or physical audibility testing. */
class DoubaoDuplexFixtureTest {
    private class Wire : DuplexTransport {
        lateinit var listener: DuplexTransport.Listener
        lateinit var request: DuplexRequest
        val sent = mutableListOf<String>()
        var result = DuplexSendResult.ACCEPTED
        var drains = 0
        var aborts = 0
        override fun abort() { aborts++ }
        override fun connect(request: DuplexRequest, listener: DuplexTransport.Listener) {
            this.request = request; this.listener = listener
        }
        override fun send(json: String): DuplexSendResult { sent += json; return result }
        override fun closeAndDrain() { drains++ }
        fun receive(type: String, field: String = "", value: String = "", id: String = "") {
            val json = JSONObject().put("type", type)
            if (field.isNotEmpty()) json.put(field, value)
            if (id.isNotEmpty()) json.put("response_id", id)
            listener.message(json.toString())
        }
    }
    private class Audio : DuplexAudio {
        var capture: ((ByteArray) -> Unit)? = null
        var starts = 0
        var opens = 0
        var drains = 0
        var epoch = 0L
        var failDrain = false
        var stops = 0
        var routeAvailable = true
        private var routeSpeaker = true
        override val speaker get() = routeSpeaker
        override fun setSpeaker(speaker: Boolean): Boolean {
            if (!routeAvailable) return false
            routeSpeaker = speaker; return true
        }
        override fun stopImmediately() { stops++; pending.clear() }
        var onDrain: (() -> Unit)? = null
        val pending = mutableListOf<Pair<Long, ByteArray>>()
        val flushed = mutableListOf<Long>()
        override fun open(onFailure: () -> Unit) { opens++ }
        override fun startCapture(onPcm: (ByteArray) -> Unit) { starts++; capture = onPcm }
        override fun enqueue(pcm: ByteArray, epoch: Long) { pending += epoch to pcm }
        override fun flush(epoch: Long) { this.epoch = epoch; flushed += epoch; pending.clear() }
        override fun closeAndDrain() {
            drains++; pending.clear(); onDrain?.invoke()
            if (failDrain) error("fixture_release_failure")
        }
    }
    private class Call(val gate: AudioOwnerGate = AudioOwnerGate()) : AutoCloseable {
        val owner = VoiceOwner("conversation", "run")
        val wire = Wire()
        val audio = Audio()
        val session = DoubaoDuplexSession(owner, DoubaoDuplexConfig("fixture-key"), wire, audio, gate)
        init { session.start() }
        fun ready() { wire.listener.opened(); wire.receive("session.created") }
        override fun close() { session.close() }
    }

    @Test fun protocolMatchesFrozenSeedDuplexAndDoesNotUseLegacyOrOpenAiAuth() {
        val config = DoubaoDuplexConfig("fixture-key", "personal-explicit-voice", "instructions")
        val request = DoubaoDuplexProtocol.request(config)
        assertEquals("wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue", request.url)
        assertEquals(setOf("X-Api-Key", "X-Api-Connect-Id"), request.headers.keys)
        assertFalse(request.toString().contains("fixture-key"))
        assertFalse(config.toString().contains("fixture-key"))
        val event = DoubaoDuplexProtocol.sessionCreate(config)
        assertEquals("session.create", event.getString("type"))
        assertTrue(event.getJSONObject("extension").getJSONObject("asr").getJSONObject("extra")
            .getBoolean("enable_asr_twopass"))
        val session = event.getJSONObject("session")
        assertFalse(session.has("extension"))
        assertFalse(session.has("modalities"))
        assertEquals("1.2.6.1", session.getString("model"))
        val audio = session.getJSONObject("audio")
        assertEquals("pcm", audio.getJSONObject("input").getJSONObject("format").getString("type"))
        assertEquals(16_000, audio.getJSONObject("input").getJSONObject("format").getInt("rate"))
        assertEquals("pcm_s16le", audio.getJSONObject("output").getJSONObject("format").getString("type"))
        assertEquals(24_000, audio.getJSONObject("output").getJSONObject("format").getInt("rate"))
        assertEquals("personal-explicit-voice", audio.getJSONObject("output").getString("voice"))
    }

    @Test fun captureStartsOnlyAfterCreatedAndDuplicateOpenedDoesNotResendCreate() {
        Call().use { c ->
            assertEquals(0, c.audio.starts)
            c.wire.listener.opened(); c.wire.listener.opened()
            assertEquals(1, c.wire.sent.size)
            assertEquals(0, c.audio.starts)
            c.wire.receive("session.created"); c.wire.receive("session.created")
            assertEquals(1, c.audio.starts)
            c.audio.capture!!(byteArrayOf(0, 0, 1, 0))
            val append = JSONObject(c.wire.sent.last())
            assertEquals("input_audio_buffer.append", append.getString("type"))
            assertEquals("AAABAA==", append.getString("audio"))
            assertEquals(DuplexPhase.LISTENING, c.session.state.value.phase)
        }
    }

    @Test fun speculativeEchoDoesNotDropQueuedAudioButRealShortSpeechTruncatesOnce() {
        Call().use { c ->
            c.ready()
            c.wire.receive("response.output_audio.started", id = "old")
            c.wire.receive("response.output_text.delta", "text", "未播完", "old")
            c.wire.receive("response.output_audio.delta", "audio", "AAABAA==", "old")
            c.wire.receive("conversation.item.input_audio_transcription.started")
            c.wire.receive("conversation.item.input_audio_transcription.delta", "text", "…")
            c.wire.receive("conversation.item.input_audio_transcription.failed")
            assertEquals(1, c.audio.pending.size)
            assertEquals("未播完", c.session.state.value.reply)
            assertTrue(c.audio.flushed.isEmpty())
            c.wire.receive("conversation.item.input_audio_transcription.started")
            c.wire.receive("conversation.item.input_audio_transcription.delta", "text", "停")
            c.wire.receive("conversation.item.input_audio_transcription.delta", "text", "停一下")
            c.wire.receive("conversation.item.input_audio_transcription.completed", "text", "停一下。")
            assertEquals(listOf(1L), c.audio.flushed)
            assertTrue(c.audio.pending.isEmpty())
            assertEquals("停一下。", c.session.state.value.transcript)
            assertEquals("", c.session.state.value.reply)
            c.wire.receive("response.output_audio.delta", "audio", "AAABAA==", "old")
            c.wire.receive("response.output_text.delta", "text", "late", "old")
            assertTrue(c.audio.pending.isEmpty())
            assertEquals("", c.session.state.value.reply)
            c.wire.receive("response.output_audio.started", id = "new")
            c.wire.receive("response.output_audio.delta", "delta", "AAABAA==", "new")
            assertEquals(1L, c.audio.pending.single().first)
        }
    }

    @Test fun replacementAsrAndVerbatimTextWhitespaceAndDoneSnapshots() {
        Call().use { c ->
            c.ready()
            c.wire.receive("conversation.item.input_audio_transcription.started")
            for (s in listOf("你", "你好。", "你好"))
                c.wire.receive("conversation.item.input_audio_transcription.delta", "text", s)
            assertEquals("你好。", c.session.state.value.transcript)
            c.wire.receive("conversation.item.input_audio_transcription.completed", "text", "你好")
            assertEquals("你好", c.session.state.value.transcript)
            c.wire.receive("response.output_audio.started", id = "r1")
            for ((field, s) in listOf("text" to "Hello", "delta" to " ", "transcript" to "世", "content" to "界"))
                c.wire.receive("response.output_text.delta", field, s, "r1")
            assertEquals("Hello 世界", c.session.state.value.reply)
            c.wire.receive("response.output_text.done", "text", "Hello 世界！", "r1")
            assertEquals("Hello 世界！", c.session.state.value.reply)
        }
    }

    @Test fun sourceTextFixturesKeepLateFirstIdAndRejectStructuredTranscriptValues() {
        val text = DuplexTextStream()
        fun e(field: String, value: Any, id: String = "", done: Boolean = false) = JSONObject()
            .put("type", if (done) "response.output_text.done" else "response.output_text.delta")
            .put(field, value).put("response_id", id)
        assertEquals("a", text.accept(e("text", "a")))
        assertEquals("ab", text.accept(e("delta", "b", "r1")))
        assertEquals("c", text.accept(e("content", "c", "r2")))
        assertEquals("corrected", text.accept(e("text", "corrected", "r2", true)))
        assertEquals("corrected", text.accept(e("text", "", "r2", true)))
        assertEquals("停", DoubaoDuplexProtocol.eventText(JSONObject()
            .put("text", JSONObject.NULL).put("delta", JSONObject()).put("transcript", "停")))
        val interruption = DuplexInterruptionGate()
        var confirmations = 0
        assertEquals("等一下", interruption.transcript("等一下", false) { confirmations++ })
        assertEquals("等一下", interruption.transcript("", true) { confirmations++ })
        assertEquals(1, confirmations)
    }

    @Test fun unknownSendAndUnknownExtensionAreNeverRetried() {
        Call().use { c ->
            c.wire.result = DuplexSendResult.UNKNOWN
            c.wire.listener.opened(); c.wire.listener.opened()
            assertEquals(DuplexPhase.UNKNOWN, c.session.state.value.phase)
            assertEquals(1, c.wire.sent.size)
            assertEquals(1, c.wire.drains)
            assertEquals(1, c.audio.drains)
        }
        Call().use { c ->
            c.ready()
            val before = c.wire.sent.size
            c.wire.receive("future.unrecognized.event")
            assertEquals(before, c.wire.sent.size)
            assertEquals(DuplexPhase.LISTENING, c.session.state.value.phase)
        }
    }

    @Test fun uploadUnknownAlsoDoesNotReplayPcmOrSendClose() {
        Call().use { c ->
            c.ready(); c.wire.result = DuplexSendResult.UNKNOWN
            c.audio.capture!!(byteArrayOf(1, 0))
            c.audio.capture!!(byteArrayOf(2, 0))
            assertEquals(DuplexPhase.UNKNOWN, c.session.state.value.phase)
            assertEquals(listOf("session.create", "input_audio_buffer.append"),
                c.wire.sent.map { JSONObject(it).getString("type") })
        }
    }

    @Test fun preemptionUsesSameGateDrainsOldAudioAndFencesAllLateCallbacks() {
        Call().use { c ->
            c.ready()
            c.wire.receive("response.output_audio.started")
            c.wire.receive("response.output_audio.delta", "audio", "AAABAA==")
            val next = VoiceOwner("other-conversation", "other-run")
            val token = c.gate.acquire(next) { }
            assertTrue(c.gate.current(token))
            assertEquals(DuplexPhase.CANCELLED, c.session.state.value.phase)
            assertTrue(c.audio.pending.isEmpty())
            assertEquals(1, c.audio.drains)
            val count = c.wire.sent.size
            c.wire.receive("response.output_text.delta", "text", "late")
            c.audio.capture!!(byteArrayOf(0, 0))
            c.wire.listener.failed(); c.wire.listener.opened()
            assertEquals(count, c.wire.sent.size)
            assertEquals("", c.session.state.value.reply)
            assertTrue(c.gate.current(token))
            c.gate.release(token)
        }
    }

    @Test fun cancelPurgesAndReleaseFailurePoisonsTheExistingGate() {
        val c = Call(); c.ready(); c.audio.failDrain = true
        try { c.session.close(); fail("strict close should fail") } catch (_: IllegalStateException) { }
        assertEquals(1, c.wire.drains)
        try {
            c.gate.acquire(VoiceOwner("next", "next")) { }
            fail("failed audio release must block admission")
        } catch (expected: IllegalStateException) { assertEquals("audio_release_pending", expected.message) }
    }

    @Test fun newOwnerCannotCommitUntilSynchronousAudioDrainCompletes() {
        Call().use { c ->
            c.ready()
            val draining = java.util.concurrent.CountDownLatch(1)
            val allowDrain = java.util.concurrent.CountDownLatch(1)
            val committed = java.util.concurrent.CountDownLatch(1)
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            c.audio.onDrain = {
                draining.countDown()
                check(allowDrain.await(2, java.util.concurrent.TimeUnit.SECONDS))
            }
            val worker = Thread {
                try {
                    val token = c.gate.acquire(VoiceOwner("replacement", "run")) { }
                    committed.countDown(); c.gate.release(token)
                } catch (t: Throwable) { failure.set(t) }
            }
            worker.start()
            try {
                assertTrue(draining.await(2, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(1L, committed.count)
                // A late listener callback must return, not wait on the draining worker.
                c.wire.receive("response.output_text.delta", "text", "late")
                assertEquals("", c.session.state.value.reply)
            } finally { allowDrain.countDown(); worker.join(3_000) }
            assertFalse(worker.isAlive)
            assertNull(failure.get())
            assertEquals(0L, committed.count)
        }
    }

    @Test fun remoteCancellationFlushesAndIdlessLateAudioWaitsForANewBoundary() {
        Call().use { c ->
            c.ready()
            c.wire.receive("response.output_audio.started")
            c.wire.receive("response.output_audio.delta", "audio", "AAABAA==")
            c.wire.receive("response.canceled")
            assertTrue(c.audio.pending.isEmpty())
            c.wire.receive("response.output_audio.delta", "audio", "AAABAA==")
            assertTrue(c.audio.pending.isEmpty())
            c.wire.receive("response.output_audio.started")
            c.wire.receive("response.output_audio.delta", "audio", "AAABAA==")
            assertEquals(1L, c.audio.pending.single().first)
        }
    }

    @Test fun canceledResponseCannotReenterOrCancelTheFollowingTurn() {
        Call().use { c ->
            c.ready()
            c.wire.receive("response.output_audio.started", id = "A")
            c.wire.receive("response.output_audio.delta", "audio", "AAABAA==", "A")
            c.wire.receive("response.canceled", id = "A")
            c.wire.receive("response.output_audio.started", id = "B")
            c.wire.receive("response.output_text.delta", "text", "current", "B")
            c.wire.receive("response.output_audio.delta", "audio", "AAABAA==", "B")
            val currentEpoch = c.audio.epoch
            val flushes = c.audio.flushed.toList()
            c.wire.receive("response.output_audio.started", id = "A")
            c.wire.receive("response.output_audio.delta", "audio", "AAABAA==", "A")
            c.wire.receive("response.output_text.delta", "text", "stale", "A")
            c.wire.receive("response.output_text.done", "text", "stale final", "A")
            c.wire.receive("response.output_audio.done", id = "A")
            c.wire.receive("response.done", id = "A")
            c.wire.receive("response.canceled", id = "A")
            assertEquals("current", c.session.state.value.reply)
            assertEquals(DuplexPhase.SPEAKING, c.session.state.value.phase)
            assertEquals(flushes, c.audio.flushed)
            assertEquals(currentEpoch, c.audio.pending.single().first)
            c.wire.receive("response.output_audio.delta", "audio", "AAABAA==", "B")
            assertEquals(2, c.audio.pending.size)
        }
    }

    @Test fun muteDropsPcmImmediatelyAndUnmuteDoesNotReplay() {
        Call().use { c ->
            c.ready()
            assertTrue(c.session.setMuted(true))
            assertTrue(c.session.muted)
            val before = c.wire.sent.size
            repeat(4) { c.audio.capture!!(byteArrayOf(it.toByte(), 0)) }
            assertEquals(before, c.wire.sent.size)
            assertTrue(c.session.setMuted(false))
            assertFalse(c.session.muted)
            assertEquals(before, c.wire.sent.size)
            c.audio.capture!!(byteArrayOf(9, 0))
            assertEquals(before + 1, c.wire.sent.size)
            assertEquals("CQA=", JSONObject(c.wire.sent.last()).getString("audio"))
            c.session.close()
            assertFalse(c.session.setMuted(true))
        }
    }

    @Test fun speakerFailureIsNotReportedAsSuccessAndTerminalCannotRoute() {
        Call().use { c ->
            c.ready(); c.audio.routeAvailable = false
            assertFalse(c.session.setSpeaker(false))
            assertTrue(c.session.speaker)
            c.audio.routeAvailable = true
            assertTrue(c.session.setSpeaker(false))
            assertFalse(c.session.speaker)
            c.session.close()
            assertFalse(c.session.setSpeaker(true))
            assertFalse(c.session.speaker)
        }
    }

    @Test fun hangupStopsAndAbortsBeforeBlockedDrainAndRejectsRepeatedStop() {
        val c = Call(); c.ready()
        val draining = java.util.concurrent.CountDownLatch(1)
        val allowDrain = java.util.concurrent.CountDownLatch(1)
        c.audio.onDrain = {
            assertEquals(1, c.audio.stops)
            assertEquals(1, c.wire.aborts)
            draining.countDown()
            check(allowDrain.await(3, java.util.concurrent.TimeUnit.SECONDS))
        }
        try {
            c.session.stop() // Must return even though the drain worker is blocked.
            assertEquals(1, c.audio.stops)
            assertEquals(1, c.wire.aborts)
            assertTrue(draining.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val terminal = c.session.state.value
            val sent = c.wire.sent.size
            c.session.stop(); c.wire.listener.failed()
            c.audio.capture!!(byteArrayOf(0, 0))
            assertEquals(terminal, c.session.state.value)
            assertEquals(sent, c.wire.sent.size)
            assertEquals(1, c.audio.stops)
            try {
                c.gate.acquire(VoiceOwner("new", "new")) { }
                fail("draining lease must not admit a new owner")
            } catch (expected: IllegalStateException) {
                assertEquals("audio_release_pending", expected.message)
            }
        } finally { allowDrain.countDown(); c.session.close() }
        assertEquals(1, c.audio.drains)
        assertEquals(1, c.wire.drains)
    }

    @Test fun asynchronousDrainFailureStillPoisonsSharedGateAndWaitingCloseThrows() {
        val c = Call(); c.ready(); c.audio.failDrain = true
        c.session.stop()
        try { c.session.close(); fail("close must retain drain failure") }
        catch (_: IllegalStateException) { }
        try {
            c.gate.acquire(VoiceOwner("new", "new")) { }
            fail("failed release must remain poisoned")
        } catch (expected: IllegalStateException) { assertEquals("audio_release_pending", expected.message) }
        assertEquals(1, c.wire.drains)
    }

    @Test fun wireErrorIsSanitizedAndPrematureSocketCloseIsUnknown() {
        Call().use { c ->
            c.ready(); c.wire.receive("error", "message", "PRIVATE_PROVIDER_DETAILS")
            assertEquals(DuplexPhase.FAILED, c.session.state.value.phase)
            assertFalse(c.session.state.value.toString().contains("PRIVATE"))
        }
        Call().use { c ->
            c.ready(); c.wire.listener.closed()
            assertEquals(DuplexPhase.UNKNOWN, c.session.state.value.phase)
        }
    }
}
