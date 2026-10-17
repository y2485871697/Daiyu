package app.daiyu.android

import android.content.Context
import android.content.ContextWrapper
import android.test.InstrumentationTestCase
import app.daiyu.android.domain.*
import app.daiyu.android.voice.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Source added for the parent's unified device batch. No network, microphone or paid calls. */
@Suppress("DEPRECATION")
class VoiceProductionHostDeviceTest : InstrumentationTestCase() {
    private lateinit var context: Context
    private lateinit var root: File
    override fun setUp() {
        super.setUp()
        val prefix = "voice-host-${UUID.randomUUID()}-"
        val isolated = testContext(instrumentation.targetContext, prefix)
        root = File(instrumentation.targetContext.cacheDir, prefix).apply { check(mkdirs()) }
        context = object : ContextWrapper(isolated) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = root
        }
    }
    override fun tearDown() {
        try {
            val prefs = context.getSharedPreferences("voice-credentials-v1", Context.MODE_PRIVATE)
            prefs.getString("key-alias", null)?.let { alias ->
                assertTrue(alias.startsWith("voice-api-key."))
                KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            }
            prefs.edit().clear().commit()
            root.deleteRecursively()
        } finally { super.tearDown() }
    }
    private fun configured() = VoiceConfiguration(engine = "OPENAI", endpoint = "https://speech.invalid/v1",
        key = "fixture-voice-only-key", model = "exact-model-" + "x".repeat(220), provider = "voice-account-A", voice = "exact-voice")

    fun testIndependentEncryptedEnvelopeAndFrozenRevision() = runBlocking {
        val runtime = ControllerVoiceRuntime(context)
        runtime.save(configured())
        val frozen = runtime.prepareSpeech()
        assertEquals(configured().model, frozen.configuration.model)
        val stored = context.getSharedPreferences("voice-credentials-v1", Context.MODE_PRIVATE).all
        assertFalse(stored.values.any { it.toString().contains(configured().key) })
        assertFalse(stored.values.any { it.toString().contains(configured().endpoint) })
        assertTrue(context.getSharedPreferences("model-settings", Context.MODE_PRIVATE).all.isEmpty())
        val reopened = ControllerVoiceRuntime(context)
        reopened.load()
        assertEquals(frozen.configuration, reopened.snapshot())
        runtime.save(configured().copy(key = "fixture-account-B"))
        assertFalse(runtime.current(frozen.configuration))
        assertFalse(runtime.snapshot().referenceOwner() == frozen.configuration.referenceOwner())
    }

    fun testLostCredentialDoesNotBecomeSystemFallbackAndReferenceNeedsCurrentAccount() = runBlocking {
        val runtime = ControllerVoiceRuntime(context)
        runtime.save(configured())
        val frozen = runtime.prepareSpeech()
        runtime.save(VoiceConfiguration())
        try { runtime.authorizeReference(frozen); fail("stale reference must fail before reading") }
        catch (_: IllegalStateException) { }
        val prefs = context.getSharedPreferences("voice-credentials-v1", Context.MODE_PRIVATE)
        check(prefs.edit().remove("key").commit())
        val reopened = ControllerVoiceRuntime(context)
        reopened.load()
        assertNull(reopened.configuration.value)
        try { reopened.prepareSpeech(); fail("missing credentials cannot silently select SYSTEM") }
        catch (_: IllegalStateException) { }
    }

    fun testVoiceEnvelopeRespectsTheCredentialVaultLimit() = runBlocking {
        val runtime = ControllerVoiceRuntime(context)
        runtime.save(VoiceConfiguration())
        val original = runtime.snapshot()
        try {
            runtime.save(original.copy(instructions = "x".repeat(8193)))
            fail("oversized credential envelope must be rejected before replacing the saved configuration")
        } catch (_: IllegalArgumentException) { }
        assertEquals(original, runtime.snapshot())
        val reopened = ControllerVoiceRuntime(context)
        reopened.load()
        assertEquals(original, reopened.snapshot())
    }

    fun testCascadeRequiresSameSendReceiptAndUsesOnlyAdmittedAssistantBody() = runBlocking {
        val runtime = ControllerVoiceRuntime(context)
        runtime.save(VoiceConfiguration(), VoicePlaybackProfile.CONVERSATION)
        val spoken = mutableListOf<String>()
        var stopped = 0
        val host = ActivityCascadeVoiceHost(context, runtime, gate = AudioOwnerGate(),
            outputFactory = { _, _ -> object : SpeechOutput {
                override fun speak(text: String, events: SpeechOutput.Events) {
                    spoken += text; events.started(); events.completed()
                }
                override fun stop() { stopped++ }
                override fun close() { stopped++ }
            } }, admission = { true })
        val ticket = Any()
        val assistant = Message(MessageId("answer"), ConversationId("conversation"), RunId("run"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING)
        host.arm(); host.reserve(ticket)
        host.accepted(Any(), assistant)
        host.update(assistant.copy(blocks = listOf(MessageBlock.Text("text", "wrong receipt."))), emptyList(), "conversation")
        assertTrue(spoken.isEmpty())
        host.accepted(ticket, assistant)
        val body = assistant.copy(blocks = listOf(
            MessageBlock.Tool(ToolInvocation(ToolCallId("tool"), "private-tool", "secret arguments"), "secret tool result"),
            MessageBlock.Text("answer", "Visible sentence.")), protocolItems = listOf("secret reasoning"))
        host.update(body.copy(runId = RunId("other-run")), emptyList(), "conversation")
        assertTrue(spoken.isEmpty())
        // Active owner retirement revokes synchronously; an old snapshot cannot revive it.
        host.update(body, emptyList(), "conversation")
        assertTrue(spoken.isEmpty())
        host.arm(); val nextTicket = Any(); host.reserve(nextTicket); host.accepted(nextTicket, assistant)
        host.update(body, emptyList(), "conversation")
        host.update(body.copy(status = RunStatus.COMPLETED), emptyList(), "conversation")
        assertEquals(listOf("Visible sentence."), spoken)
        host.close()
        host.update(body.copy(blocks = listOf(MessageBlock.Text("late", "Late callback."))), emptyList(), "conversation")
        assertEquals(1, spoken.size)
        assertTrue(stopped > 0)
    }

    fun testCascadeUnknownSendAndStopNeverStartOutput() = runBlocking {
        val runtime = ControllerVoiceRuntime(context)
        runtime.save(VoiceConfiguration(), VoicePlaybackProfile.CONVERSATION)
        var created = 0
        val host = ActivityCascadeVoiceHost(context, runtime, gate = AudioOwnerGate(),
            outputFactory = { _, _ -> created++; error("must not create output") }, admission = { true })
        val ticket = Any()
        host.arm(); host.reserve(ticket); host.rejected(ticket)
        host.accepted(ticket, Message(MessageId("a"), ConversationId("c"), RunId("r"),
            Message.ASSISTANT_ROLE, emptyList(), RunStatus.RUNNING))
        assertEquals(0, created)
        host.arm(); host.reserve(ticket); host.close()
        host.accepted(ticket, null)
        assertEquals(0, created)
    }
}
