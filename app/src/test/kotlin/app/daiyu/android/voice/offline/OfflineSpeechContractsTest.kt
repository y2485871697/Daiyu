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

    @Test fun hostLiveTextAndFinalReplaceOnlyCapturedSelection() {
        val attempts = mutableListOf<Attempt>()
        var text = "before OLD after"
        var cursor = -1
        var commits = 0
        val liveDraft = SpeechDraft(text, 7, 10)
        val target = DictationDraftTarget("chat", 2, 3)
        val host = OfflineComposerHost(
            factory = { _, events -> Attempt(events).also { attempts += it } },
            enabled = { true }, permission = { true }, requestPermission = { fail("unexpected permission") },
            current = { it == target }, currentText = { text },
            applyPartialDraft = { _, recognized -> text = checkNotNull(liveDraft.accept(text, recognized)); cursor = liveDraft.cursor },
            applyDraft = { captured, next, position -> assertEquals(target, captured); text = next; cursor = position; commits++ })
        host.start(target, text, 7, 10)
        assertFalse(host.state.value.listening) // Loading a model / clicking is not capture.
        assertEquals(target, host.state.value.target)
        attempts.single().events.partial("preview")
        assertFalse(host.state.value.listening) // A partial alone is not the authoritative event.
        attempts.single().events.listening()
        assertTrue(host.state.value.listening)
        assertEquals("before preview after", text)
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

    @Test fun stopKeepsLiveTextAndNextAttemptAppendsAtTheCurrentCursor() {
        val attempts = mutableListOf<Attempt>()
        var text = "leftOLDtail"
        var cursor = 4
        var live = SpeechDraft(text, 7, 4)
        val target = DictationDraftTarget(null, 2, 3)
        val host = OfflineComposerHost(
            factory = { _, events -> Attempt(events).also { attempts += it } },
            enabled = { true }, permission = { true }, requestPermission = { fail("unexpected permission") },
            current = { true }, currentText = { text },
            applyPartialDraft = { _, recognized -> text = checkNotNull(live.accept(text, recognized)); cursor = live.cursor },
            applyDraft = { _, next, position -> text = next; cursor = position })
        host.start(target, text, 7, 4)
        val first = attempts.single()
        first.events.listening()
        first.events.partial("你")
        first.events.partial("你好")
        first.events.partial("您好")
        assertEquals("left您好tail", text)
        host.start(target, text, cursor, cursor) // Same explicit repeat-click cancellation as the host API.
        assertEquals("left您好tail", text)
        assertEquals(6, cursor)
        assertEquals(1, first.closes)
        assertNull(host.state.value.target)
        first.events.partial("late partial"); first.events.complete("late final")
        assertEquals("left您好tail", text)
        live = SpeechDraft(text, cursor, cursor)
        host.start(target, text, cursor, cursor) // Reuse target deliberately: serial must still fence first.
        val second = attempts.last()
        second.events.partial("世界")
        first.events.complete("stale after restart")
        assertEquals("left您好世界tail", text)
        second.events.complete("世界！")
        assertEquals("left您好世界！tail", text)
        assertEquals(9, cursor)
        assertEquals("", host.state.value.status)
        live = SpeechDraft(text, 8, 4) // A new reverse selection, not the previous recording region.
        host.start(target, text, 8, 4)
        attempts.last().events.partial("😀")
        assertEquals("left😀！tail", text)
        assertEquals(6, cursor)
        attempts.last().events.complete("😀好")
        assertEquals("left😀好！tail", text)
        assertEquals(7, cursor)
        host.close()
    }

    @Test fun cancellationFailureAndEmptyFinalKeepAlreadyRecognizedText() {
        for (terminal in listOf("cancel", "failure", "empty", "invalidate")) {
            var text = "已有😀"
            var live = SpeechDraft(text, text.length, text.length)
            lateinit var attempt: Attempt
            val target = DictationDraftTarget("chat", 0, 0)
            val host = OfflineComposerHost(
                factory = { _, events -> Attempt(events).also { attempt = it } },
                enabled = { true }, permission = { true }, requestPermission = { fail("unexpected permission") },
                current = { true }, currentText = { text },
                applyPartialDraft = { _, recognized -> text = checkNotNull(live.accept(text, recognized)) },
                applyDraft = { _, next, _ -> text = next })
            host.start(target, text, text.length, text.length)
            attempt.events.partial("第一轮")
            when (terminal) {
                "cancel" -> attempt.events.cancelled()
                "failure" -> attempt.events.failed()
                "empty" -> attempt.events.complete("")
                else -> host.invalidate()
            }
            assertEquals(terminal, "已有😀第一轮", text)
            if (terminal == "failure") assertTrue(host.state.value.status.contains("失败"))
            if (terminal == "empty") assertTrue(host.state.value.status.contains("未识别"))
            attempt.events.partial("late"); attempt.events.complete("late")
            assertEquals("已有😀第一轮", text)
            assertEquals(1, attempt.closes)
            host.close()
        }
    }

    @Test fun changedEditorAndFailedPartialSaveRetireTheAttempt() {
        var text = "original"
        lateinit var attempt: Attempt
        var writes = 0
        val host = OfflineComposerHost(
            factory = { _, events -> Attempt(events).also { attempt = it } },
            enabled = { true }, permission = { true }, requestPermission = {}, current = { true },
            currentText = { text }, applyPartialDraft = { _, _ -> writes++; error("save failed") },
            applyDraft = { _, _, _ -> fail("retired final") })
        val target = DictationDraftTarget(null, 0, 0)
        host.start(target, text, 0, text.length)
        attempt.events.partial("recognized")
        assertEquals(1, writes)
        assertEquals("original", text)
        assertTrue(host.state.value.status.contains("保存失败"))
        attempt.events.complete("late")
        host.start(target, text, 0, text.length)
        text = "user edited"
        attempt.events.partial("stale")
        assertEquals(1, writes)
        assertNull(host.state.value.target)
        host.close()
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
            current = { current }, currentText = { "draft" },
            applyPartialDraft = { _, _ -> fail("unexpected partial") }, applyDraft = { _, _, _ -> commits++ })
        host.start(target, "draft", 0, 5)
        assertEquals(1, requests)
        assertTrue(attempts.isEmpty())
        granted = true
        host.permissionResult(true)
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

    @Test fun pendingPermissionCannotRelaunchOrBorrowAnotherEditorsGrant() {
        val attempts = mutableListOf<Attempt>()
        var granted = false
        var requests = 0
        val target = DictationDraftTarget(null, 1, 2)
        var currentTarget = target
        val host = OfflineComposerHost(
            factory = { _, events -> Attempt(events).also { attempts += it } },
            enabled = { true }, permission = { granted }, requestPermission = { requests++ },
            current = { it == currentTarget }, currentText = { "draft" },
            applyPartialDraft = { _, _ -> fail("unexpected partial") },
            applyDraft = { _, _, _ -> fail("permission result cannot edit a draft") })
        host.start(target, "draft", 0, 5)
        host.start(target, "draft", 0, 5)
        assertEquals(1, requests)
        assertTrue(attempts.isEmpty())
        currentTarget = target.copy(sessionId = "other-editor")
        granted = true
        host.start(currentTarget, "draft", 0, 5)
        assertTrue(attempts.isEmpty())
        host.permissionResult(true)
        assertEquals(OfflineComposerHost.State(), host.state.value)
        assertTrue(attempts.isEmpty())
        host.start(currentTarget, "draft", 0, 5)
        assertEquals(1, attempts.single().starts)
        host.close()
    }

    @Test fun permissionLaunchFailureReleasesPendingAndGrantStillRequiresExplicitRetry() {
        var failLaunch = true
        var requests = 0
        var granted = false
        val attempts = mutableListOf<Attempt>()
        val target = DictationDraftTarget(null, 0, 0)
        val host = OfflineComposerHost(
            factory = { _, events -> Attempt(events).also { attempts += it } },
            enabled = { true }, permission = { granted }, requestPermission = {
                requests++
                check(!failLaunch) { "launcher unavailable" }
            }, current = { it == target }, currentText = { "draft" },
            applyPartialDraft = { _, _ -> fail("unexpected partial") },
            applyDraft = { _, _, _ -> fail("permission result cannot edit a draft") })
        host.start(target, "draft", 0, 5)
        assertTrue(host.state.value.status.contains("无法请求"))
        failLaunch = false
        host.start(target, "draft", 0, 5)
        assertEquals(2, requests)
        host.permissionResult(false)
        assertTrue(host.state.value.status.contains("被拒绝"))
        host.start(target, "draft", 0, 5)
        assertEquals(3, requests)
        granted = true
        host.permissionResult(true)
        assertTrue(host.state.value.status.contains("再次点击"))
        assertTrue(attempts.isEmpty())
        host.start(target, "draft", 0, 5)
        assertEquals(1, attempts.single().starts)
        host.close()
    }

    @Test fun latePermissionAfterInvalidationOrCloseDoesNotPublishOrStart() {
        var requests = 0
        val target = DictationDraftTarget(null, 0, 0)
        val host = OfflineComposerHost(
            factory = { _, _ -> error("late permission cannot create a session") },
            enabled = { true }, permission = { false }, requestPermission = { requests++ },
            current = { it == target }, currentText = { "draft" },
            applyPartialDraft = { _, _ -> fail("unexpected partial") },
            applyDraft = { _, _, _ -> fail("late permission cannot edit a draft") })
        host.start(target, "draft", 0, 5)
        host.invalidate()
        host.permissionResult(true)
        assertEquals(OfflineComposerHost.State(), host.state.value)
        host.start(target, "draft", 0, 5)
        assertEquals(2, requests)
        host.close()
        host.permissionResult(false)
        host.start(target, "draft", 0, 5)
        assertEquals(2, requests)
        assertEquals(OfflineComposerHost.State(), host.state.value)
    }

    private class Events : OfflineSpeechSession.Events {
        val values = mutableListOf<String>()
        override fun listening() { values += "listening" }
        override fun captureStopped() { values += "captureStopped" }
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
        var duringFinish: () -> Unit = {}
        var duringClose: () -> Unit = {}
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
                override fun finish() { finished = true; duringFinish() }
                override fun close() { closes++; duringClose() }
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
        assertEquals(listOf("captureStopped", "complete:你好"), events.values)
        val next = gate.acquire(owner("next")) {}
        assertTrue(gate.current(next))
    }

    @Test fun microphoneReleasedButDelayedDecoderStillRunningDoesNotKeepComposerListening() {
        val pending = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
        fun flush() { while (true) (pending.poll() ?: break).invoke() }
        val captured = java.util.concurrent.CountDownLatch(1)
        val endpointAllowed = java.util.concurrent.CountDownLatch(1)
        val finishing = java.util.concurrent.CountDownLatch(1)
        val finalAllowed = java.util.concurrent.CountDownLatch(1)
        val device = Device()
        device.duringAccept = {
            if (device.accepted.last().size != 4800) {
                captured.countDown()
                check(endpointAllowed.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        device.duringFinish = {
            finishing.countDown()
            check(finalAllowed.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        var worker: Thread? = null
        var commits = 0
        var text = "draft"
        val liveDraft = SpeechDraft(text, 0, 5)
        val target = DictationDraftTarget("chat", 0, 0)
        val gate = AudioOwnerGate()
        val host = OfflineComposerHost(
            factory = { owner, events -> OfflineSpeechSession(gate, owner, { true }, device, events,
                Executor { task -> worker = Thread(task, "delayed-decoder-test").also { it.start() } },
                { pending.add(it); Unit }) },
            enabled = { true }, permission = { true }, requestPermission = { fail("already granted") },
            current = { it == target }, currentText = { text },
            applyPartialDraft = { _, recognized -> text = checkNotNull(liveDraft.accept(text, recognized)) },
            applyDraft = { _, next, _ -> text = next; commits++ })
        try {
            host.start(target, "draft", 0, 5)
            assertTrue(captured.await(5, java.util.concurrent.TimeUnit.SECONDS))
            flush()
            assertTrue(host.state.value.listening)
            endpointAllowed.countDown()
            assertTrue(finishing.await(5, java.util.concurrent.TimeUnit.SECONDS))
            flush()
            assertTrue(checkNotNull(worker).isAlive) // Native finish has not returned; not a final.
            assertEquals(1, device.releases)
            assertEquals(0, device.closes)
            assertFalse(host.state.value.listening)
            assertEquals(target, host.state.value.target) // Keep ownership until the real final.
            assertEquals(0, commits)
            finalAllowed.countDown()
            checkNotNull(worker).join(5000)
            assertFalse(checkNotNull(worker).isAlive)
            flush()
            assertEquals(1, commits)
            assertFalse(host.state.value.listening)
        } finally {
            endpointAllowed.countDown(); finalAllowed.countDown()
            worker?.join(5000)
            host.close()
        }
    }

    @Test fun decoderFailureStopsListeningBeforeSlowNativeCleanupWithoutCommittingFinal() {
        val pending = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
        fun flush() { while (true) (pending.poll() ?: break).invoke() }
        val capturing = java.util.concurrent.CountDownLatch(1)
        val failNow = java.util.concurrent.CountDownLatch(1)
        val closing = java.util.concurrent.CountDownLatch(1)
        val releaseClose = java.util.concurrent.CountDownLatch(1)
        val device = Device().apply {
            duringAccept = { capturing.countDown(); check(failNow.await(5, java.util.concurrent.TimeUnit.SECONDS)); error("decoder failure") }
            duringClose = { closing.countDown(); check(releaseClose.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
        }
        val events = Events()
        var worker: Thread? = null
        val session = OfflineSpeechSession(AudioOwnerGate(), owner(), { true }, device, events,
            Executor { task -> worker = Thread(task).also { it.start() } }, { pending.add(it); Unit })
        try {
            session.start()
            assertTrue(capturing.await(5, java.util.concurrent.TimeUnit.SECONDS))
            flush(); assertEquals(listOf("listening"), events.values)
            failNow.countDown(); assertTrue(closing.await(5, java.util.concurrent.TimeUnit.SECONDS))
            flush()
            assertEquals(1, device.releases)
            assertEquals(listOf("listening", "captureStopped"), events.values)
            assertTrue(checkNotNull(worker).isAlive)
            releaseClose.countDown(); checkNotNull(worker).join(5000); flush()
            assertFalse(checkNotNull(worker).isAlive)
            assertEquals(listOf("listening", "captureStopped", "failed"), events.values)
            assertFalse(device.finished)
        } finally { failNow.countDown(); releaseClose.countDown(); worker?.join(5000); session.close() }
    }

    @Test fun queuedListeningCannotArriveAfterReleaseWhileDecoderStillHoldsLease() {
        val device = Device()
        val events = Events()
        val pending = java.util.ArrayDeque<() -> Unit>()
        fun flush() { while (pending.isNotEmpty()) pending.removeFirst().invoke() }
        device.duringAccept = {
            if (device.accepted.last().size == 4800) {
                flush() // The UI was busy until tail began, but the gate is not retired yet.
                assertFalse(events.values.contains("listening"))
                assertEquals("captureStopped", events.values.last())
                assertEquals(1, device.releases)
                assertEquals(0, device.closes)
                assertFalse(device.finished)
            }
        }
        OfflineSpeechSession(AudioOwnerGate(), owner(), { true }, device, events, directExecutor,
            { pending.addLast(it) }).start()
        flush()
        assertEquals("complete:你好", events.values.last())
        assertFalse(events.values.contains("listening"))
    }

    @Test fun stoppedAndListeningCallbacksFromSameTargetOldAttemptCannotChangeNewOwner() {
        val attempts = mutableListOf<Attempt>()
        val target = DictationDraftTarget("chat", 0, 0)
        var commits = 0
        var text = "draft"
        val liveDraft = SpeechDraft(text, 0, 5)
        val host = OfflineComposerHost(
            factory = { _, events -> Attempt(events).also { attempts += it } },
            enabled = { true }, permission = { true }, requestPermission = { fail("already granted") },
            current = { it == target }, currentText = { text },
            applyPartialDraft = { _, recognized -> text = checkNotNull(liveDraft.accept(text, recognized)) },
            applyDraft = { _, next, _ -> text = next; commits++ })
        host.start(target, "draft", 0, 5)
        val old = attempts.single().events
        old.listening()
        host.invalidate()
        host.start(target, "draft", 0, 5) // Deliberately reuse every target field.
        val fresh = attempts.last().events
        old.listening()
        assertFalse(host.state.value.listening)
        fresh.listening()
        assertTrue(host.state.value.listening)
        old.captureStopped(); old.cancelled(); old.failed(); old.complete("old"); old.partial("old")
        assertTrue(host.state.value.listening)
        assertEquals("", host.state.value.preview)
        assertEquals(0, commits)
        fresh.captureStopped()
        fresh.partial("still waiting"); fresh.listening()
        assertFalse(host.state.value.listening)
        assertEquals("still waiting", host.state.value.preview)
        assertEquals(target, host.state.value.target)
        fresh.complete("new")
        assertEquals(1, commits)
        host.close()
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
        val pending = mutableListOf<() -> Unit>()
        val session = OfflineSpeechSession(gate, owner(), { true }, device, events, directExecutor, { pending += it })
        session.start()
        pending.forEach { it() }
        assertEquals(listOf("failed"), events.values) // Release failure must not claim captureStopped.
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
                    override fun captureStopped() = events.captureStopped()
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
            device.duringFinish = {
                assertEquals(1, device.releases)
                assertEquals("captureStopped", events.values.last())
                assertFalse(events.values.any { it.startsWith("complete:") })
            }
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
