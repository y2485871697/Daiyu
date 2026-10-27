package app.daiyu.android

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.storage.ModelSettingsStore
import app.daiyu.android.voice.*
import app.daiyu.android.voice.providers.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Test source for the parent batch. Fake transport only; no provider/network/microphone calls. */
@Suppress("DEPRECATION")
class PersonalCascadeProductionDeviceTest : InstrumentationTestCase() {
    private lateinit var context: Context
    private lateinit var root: File
    override fun setUp() {
        super.setUp()
        val prefix = "personal-cascade-${UUID.randomUUID()}-"
        val isolated = testContext(instrumentation.targetContext, prefix)
        root = File(instrumentation.targetContext.cacheDir, prefix).apply { check(mkdirs()) }
        context = object : ContextWrapper(isolated) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = root
            override fun getCacheDir() = root
        }
    }
    override fun tearDown() {
        try {
            val prefs = context.getSharedPreferences("voice-credentials-v1", Context.MODE_PRIVATE)
            prefs.getString("key-alias", null)?.let { alias ->
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            }
            prefs.edit().clear().commit()
            root.deleteRecursively()
        } finally { super.tearDown() }
    }
    private fun config() = VoiceConfiguration(engine = "DOUBAO", endpoint = "https://openspeech.bytedance.com",
        key = "fixture-key", model = "seed-tts-2.0", provider = "fixture-account", voice = "S_fixture")
    private fun record(config: VoiceConfiguration) = DoubaoPersonalVoice(config.voice,
        DoubaoPersonalVoice.accountFingerprint(config.key), 2, setOf(4), true)
    private fun answer() = Message(MessageId("answer"), ConversationId("conversation"), RunId("run"),
        Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
    private fun silentOutput() = object : SpeechOutput {
        override fun speak(text: String, events: SpeechOutput.Events) { events.started(); events.completed() }
        override fun stop() { }
        override fun close() { }
    }

    fun testProfilesAreIndependentlyEncryptedAndMissingConversationNeverFallsBack() = runBlocking {
        val runtime = ControllerVoiceRuntime(context)
        runtime.save(config())
        val manual = runtime.prepareSpeech()
        try { runtime.prepareSpeech(VoicePlaybackProfile.CONVERSATION); fail("must not inherit read aloud") }
        catch (_: IllegalStateException) { }
        runtime.save(VoiceConfiguration(), VoicePlaybackProfile.CONVERSATION)
        assertTrue(runtime.current(manual))
        assertEquals("SYSTEM", runtime.prepareSpeech(VoicePlaybackProfile.CONVERSATION).configuration.engine)
        val reopened = ControllerVoiceRuntime(context)
        reopened.load()
        assertEquals(config().voice, reopened.prepareSpeech().configuration.voice)
        assertEquals("SYSTEM", reopened.prepareSpeech(VoicePlaybackProfile.CONVERSATION).configuration.engine)
        val raw = context.getSharedPreferences("voice-credentials-v1", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(raw.contains(config().key)); assertFalse(raw.contains(config().voice))
    }

    fun testLegacyRequiresExplicitPerDestinationMigration() = runBlocking {
        val vault = ModelSettingsStore(context, "voice-api-key", "voice-credentials-v1")
        vault.save(ModelSettings("https://voice-credentials.invalid/v1/responses", "voice-credential", config().encode(), false))
        val runtime = ControllerVoiceRuntime(context)
        runtime.load()
        assertNull(runtime.configuration.value); assertNull(runtime.conversationConfiguration.value)
        assertNotNull(runtime.legacyConfiguration.value)
        runtime.migrateLegacy(VoicePlaybackProfile.CONVERSATION)
        assertNull(runtime.configuration.value)
        assertEquals(config().voice, runtime.prepareSpeech(VoicePlaybackProfile.CONVERSATION).configuration.voice)
        assertNull(runtime.legacyConfiguration.value)
        try { runtime.prepareSpeech(); fail("the other profile must stay unconfigured") }
        catch (_: IllegalStateException) { }
        runtime.save(VoiceConfiguration(), VoicePlaybackProfile.READ_ALOUD)
        assertEquals("SYSTEM", runtime.prepareSpeech().configuration.engine)
        assertFalse(runtime.snapshot().revision == runtime.snapshot(VoicePlaybackProfile.CONVERSATION).revision)
    }

    fun testPlainArmRejectsPersonalAndUnknownNeverCreatesAnOutput() = runBlocking {
        val cfg = config()
        val runtime = ControllerVoiceRuntime(context) { record(it) }
        runtime.save(cfg, VoicePlaybackProfile.CONVERSATION)
        var created = 0
        val host = ActivityCascadeVoiceHost(context, runtime, AudioOwnerGate(),
            outputFactory = { _, _ -> created++; silentOutput() }, admission = { true })
        try {
            try { host.arm(); fail("ordinary permission cannot authorize personal uploads") }
            catch (_: IllegalArgumentException) { }
            val offer = checkNotNull(host.preparePersonalConsent())
            assertTrue(armRequestedPersonalCascade(host, offer) { true })
            val ticket = Any()
            host.reserve(ticket); host.rejected(ticket); host.accepted(ticket, answer())
            assertEquals(0, created)
            assertFalse(host.consentCurrent(offer))
        } finally { host.close() }
    }

    fun testRequestedDefaultUsesFreshFiniteOfferAndNeverCreatesOutputBeforeManualSend() = runBlocking {
        val runtime = ControllerVoiceRuntime(context) { record(it) }
        val cfg = config()
        runtime.save(cfg, VoicePlaybackProfile.CONVERSATION)
        var selected: FrozenVoiceSpeech? = null
        val host = ActivityCascadeVoiceHost(context, runtime, AudioOwnerGate(),
            outputFactory = { _, value -> selected = value; silentOutput() }, admission = { true })
        try {
            val offer = checkNotNull(host.preparePersonalConsent())
            assertEquals(8, offer.limits.maxSentences)
            assertEquals(1600, offer.limits.maxCharacters)
            assertEquals(120_000L, offer.limits.maxAudioDurationMs)
            assertEquals(300_000L, offer.limits.validForMs)
            assertEquals(15_000L, offer.perRequestAudioMs)
            assertTrue(armRequestedPersonalCascade(host, offer) { true })
            assertNull(selected) // Default permission does not enable output or send a message.
            assertFalse(host.consentCurrent(offer)) // This exact offer is already spent.
            val ticket = Any(); host.reserve(ticket)
            host.accepted(Any(), answer()); assertNull(selected)
            host.accepted(ticket, answer())
            val frozen = checkNotNull(selected)
            assertEquals(runtime.snapshot(VoicePlaybackProfile.CONVERSATION), frozen.configuration) // No substitute voice/model/account.
            assertEquals(15_000L, frozen.playbackLimitMs)
            val upload = checkNotNull(frozen.upload)
            repeat(8) { assertEquals(15_000L, upload.consume("request-$it", "x")) }
            try { upload.consume("request-9", "x"); fail("finite upload/audio budget must remain enforced") }
            catch (_: IllegalStateException) { }
        } finally { host.close() }
    }

    fun testDefaultPermissionDoesNotReuseOldOfferAndStopInvalidatesBoundUpload() = runBlocking {
        val runtime = ControllerVoiceRuntime(context) { record(it) }
        runtime.save(config(), VoicePlaybackProfile.CONVERSATION)
        var selected: FrozenVoiceSpeech? = null
        val host = ActivityCascadeVoiceHost(context, runtime, AudioOwnerGate(),
            outputFactory = { _, value -> selected = value; silentOutput() }, admission = { true })
        try {
            val stopped = checkNotNull(host.preparePersonalConsent())
            host.close()
            assertFalse(armRequestedPersonalCascade(host, stopped) { true })
            val abandoned = checkNotNull(host.preparePersonalConsent())
            assertFalse(armRequestedPersonalCascade(host, abandoned) { false }) // Original editor/lifecycle retired.
            val latest = checkNotNull(host.preparePersonalConsent())
            assertFalse(armRequestedPersonalCascade(host, abandoned) { true })
            assertTrue(host.consentCurrent(latest)) // A late old callback cannot revoke the new offer.
            assertTrue(armRequestedPersonalCascade(host, latest) { true })
            val ticket = Any(); host.reserve(ticket); host.accepted(ticket, answer())
            val upload = checkNotNull(checkNotNull(selected).upload)
            host.close()
            try { upload.consume("after-stop", "x"); fail("stop must revoke even default permission") }
            catch (_: IllegalStateException) { }
            assertFalse(armRequestedPersonalCascade(host, latest) { true })
        } finally { host.close() }
    }

    fun testDefaultPermissionStillRejectsOversizedCharactersBeforeUpload() = runBlocking {
        val runtime = ControllerVoiceRuntime(context) { record(it) }
        runtime.save(config(), VoicePlaybackProfile.CONVERSATION)
        var selected: FrozenVoiceSpeech? = null
        val host = ActivityCascadeVoiceHost(context, runtime, AudioOwnerGate(),
            outputFactory = { _, value -> selected = value; silentOutput() }, admission = { true })
        try {
            val offer = checkNotNull(host.preparePersonalConsent())
            assertTrue(armRequestedPersonalCascade(host, offer) { true })
            val ticket = Any(); host.reserve(ticket); host.accepted(ticket, answer())
            val upload = checkNotNull(checkNotNull(selected).upload)
            try { upload.consume("oversized", "x".repeat(1601)); fail("character limit must remain enforced") }
            catch (_: IllegalStateException) { }
            try { upload.consume("next", "x"); fail("denied budget must not revive") }
            catch (_: IllegalStateException) { }
        } finally { host.close() }
    }

    fun testActualTransportExecuteConsumesOnceAndUnknownFailureIsNotRetried() = runBlocking {
        val runtime = ControllerVoiceRuntime(context) { record(it) }
        runtime.save(config(), VoicePlaybackProfile.CONVERSATION)
        var frozen: FrozenVoiceSpeech? = null
        val host = ActivityCascadeVoiceHost(context, runtime, AudioOwnerGate(),
            outputFactory = { _, value -> frozen = value; silentOutput() }, admission = { true })
        val uploads = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var player: RemoteVoiceHostPlayer? = null
        try {
            val offer = checkNotNull(host.preparePersonalConsent())
            assertTrue(armRequestedPersonalCascade(host, offer) { true })
            val ticket = Any(); host.reserve(ticket)
            host.accepted(Any(), answer()); assertNull(frozen)
            host.accepted(ticket, answer())
            val selected = checkNotNull(frozen)
            assertEquals(15_000L, selected.playbackLimitMs)
            instrumentation.runOnMainSync {
                player = RemoteVoiceHostPlayer(context, scope, runtime, selected, VoiceHttpTransport { _, _ ->
                    uploads.incrementAndGet()
                    throw VoiceProviderException("fixture_unknown_result")
                }).also { it.bindOwner("test"); assertTrue(it.play("test", "request-1", "A sentence.")) }
            }
            withTimeout(5_000) { checkNotNull(player).state.first { it.phase == SpeechPhase.ERROR } }
            assertEquals(1, uploads.get())
            // Same request identity cannot be reused even after an unknown transport result.
            instrumentation.runOnMainSync { assertTrue(checkNotNull(player).play("test", "request-1", "A sentence.")) }
            withTimeout(5_000) { checkNotNull(player).state.first { it.phase == SpeechPhase.ERROR } }
            assertEquals(1, uploads.get())
        } finally {
            instrumentation.runOnMainSync { player?.closeStrict() }
            host.close(); scope.cancel()
        }
    }

    fun testEffectiveDoubaoKeyUsesTheSameCaseInsensitiveOverrideAsWireAuthorization() {
        val wire = SpeechProviderConfig(SpeechEngine.DOUBAO, "https://openspeech.bytedance.com", "fixture-base",
            "seed-tts-2.0", "fixture", mapOf("x-aPI-kEY" to "fixture-override"))
        val actual = effectiveDoubaoAuthenticationKey(wire)
        assertEquals("fixture-override", actual)
        val selected = DoubaoPersonalVoice("S_fixture", DoubaoPersonalVoice.accountFingerprint(actual), 2, setOf(4), true)
        assertTrue(selected.availableFor(actual))
        assertFalse(selected.availableFor(wire.apiKey))
        val request = DoubaoSpeech.request(wire, "fixture text", selected.id, selected)
        assertEquals(request.header("X-Api-Key"), actual)
        val noOverride = config().speech()
        assertEquals(config().key, effectiveDoubaoAuthenticationKey(noOverride))
    }

    fun testDisposePermanentlyUnsubscribesWhileStopRemainsReusable() = runBlocking {
        val runtime = ControllerVoiceRuntime(context)
        runtime.save(VoiceConfiguration(), VoicePlaybackProfile.CONVERSATION)
        val host = ActivityCascadeVoiceHost(context, runtime, AudioOwnerGate(),
            outputFactory = { _, _ -> silentOutput() }, admission = { true })
        host.arm(); host.close(); host.arm()
        host.dispose()
        val disposedToken = host.operationToken
        runtime.invalidateSpeech()
        assertEquals(disposedToken, host.operationToken) // No retained old-Activity callback.
        try { host.arm(); fail("disposed host must not arm") } catch (_: IllegalStateException) { }
    }

    fun testNativePreparedDurationRejectsLongTrackAndDeletesTemporaryAudio() = runBlocking {
        val runtime = ControllerVoiceRuntime(context)
        runtime.save(VoiceConfiguration(engine = "OPENAI", endpoint = "https://speech.invalid/v1",
            key = "fixture", model = "tts-1", provider = "fixture", voice = "alloy"))
        val selected = FrozenVoiceSpeech(runtime.snapshot(), playbackLimitMs = 100)
        val audio = DoubaoSpeech.pcmToWav(ByteArray(48_000)) // Real 1-second PCM16/24k/mono track.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var player: RemoteVoiceHostPlayer? = null
        try {
            instrumentation.runOnMainSync {
                player = RemoteVoiceHostPlayer(context, scope, runtime, selected, VoiceHttpTransport { _, _ ->
                    VoiceHttpResponse(200, "audio/wav", audio)
                }).also { it.bindOwner("duration"); assertTrue(it.play("duration", "duration-request", "x")) }
            }
            withTimeout(10_000) { checkNotNull(player).state.first { it.phase == SpeechPhase.ERROR } }
            assertTrue(root.listFiles().orEmpty().none { it.name.startsWith("voice-") && it.extension == "audio" })
        } finally {
            instrumentation.runOnMainSync { player?.closeStrict() }
            scope.cancel()
        }
    }

    fun testLiveAccountBindingRetiresRatherThanReturningTheFrozenRecord() = runBlocking {
        var selected: DoubaoPersonalVoice? = record(config())
        val runtime = ControllerVoiceRuntime(context) { selected }
        runtime.save(config(), VoicePlaybackProfile.CONVERSATION)
        val host = ActivityCascadeVoiceHost(context, runtime, AudioOwnerGate(),
            outputFactory = { _, _ -> silentOutput() }, admission = { true })
        try {
            val old = checkNotNull(host.preparePersonalConsent())
            selected = null // State-only resolver change is visible even before explicit notification.
            assertFalse(host.consentCurrent(old))
            selected = record(config())
            val current = checkNotNull(host.preparePersonalConsent())
            host.cancelPersonalConsent(old)
            assertTrue(host.consentCurrent(current)) // Old dismiss must not cancel current dialog.
            runtime.invalidateSpeech() // Account parent calls before changing/deleting its record.
            assertFalse(host.consentCurrent(current))
        } finally { host.close() }
    }
}
