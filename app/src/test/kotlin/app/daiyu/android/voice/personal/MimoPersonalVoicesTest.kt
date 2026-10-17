package app.daiyu.android.voice.personal

import app.daiyu.android.testing.HostAtomicFileShadow
import android.app.Application
import app.daiyu.android.voice.providers.DoubaoSpeech
import app.daiyu.android.voice.providers.SpeechEngine
import app.daiyu.android.voice.providers.SpeechProviderConfig
import app.daiyu.android.voice.providers.SpeechSynthesisClient
import app.daiyu.android.voice.providers.VoiceHttpResponse
import app.daiyu.android.voice.providers.VoiceHttpTransport
import app.daiyu.android.voice.providers.VoiceProviderException
import java.io.File
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], shadows = [HostAtomicFileShadow::class])
class MimoPersonalVoicesTest {
    @get:Rule val folder = TemporaryFolder()
    private val wav get() = DoubaoSpeech.pcmToWav(byteArrayOf(1, 2, 3, 4))
    private fun config(engine: SpeechEngine = SpeechEngine.MIMO, key: String = "test-key") =
        SpeechProviderConfig(engine, "https://api.xiaomimimo.com/v1", key, "mimo-v2.5-tts", "provider-a")

    @Test fun importPersistsProviderBindingAndDeleteRemovesReference() = runBlocking {
        val root = folder.newFolder()
        val store = MimoPersonalVoices(root) { 1250L }
        val voice = store.importSample(wav, "  我的声音  ", "provider-a")
        assertTrue(voice.id.startsWith(MimoPersonalVoices.PREFIX))
        assertEquals("我的声音", voice.name); assertEquals(1250L, voice.durationMs)
        assertEquals("audio/wav", voice.mime)
        assertEquals(listOf(voice), store.state.value)
        val next = MimoPersonalVoices(root) { error("loading must not reread duration") }
        next.load(); assertEquals(listOf(voice), next.state.value)
        val reference = next.reference(voice.id, "provider-a")
        assertTrue(reference.startsWith("data:audio/wav;base64,"))
        assertArrayEquals(wav, Base64.getDecoder().decode(reference.substringAfter(',')))
        assertThrows(IllegalStateException::class.java) { next.reference(voice.id, "provider-b") }
        assertThrows(IllegalStateException::class.java) { next.reference("../../outside", "provider-a") }
        next.remove(voice.id)
        assertFalse(File(root, "${voice.id}.audio").exists()); assertTrue(next.state.value.isEmpty())
        assertThrows(IllegalStateException::class.java) { next.reference(voice.id, "provider-a") }
        val final = MimoPersonalVoices(root); final.load(); assertTrue(final.state.value.isEmpty())
        val index = File(root, "index.json").readText()
        assertFalse(index.contains("test-key")); assertFalse(index.contains("base64"))
    }

    @Test fun damagedIndexRemainsRetryableAndCannotBeOverwrittenByImport() {
        val root = folder.newFolder()
        val index = File(root, "index.json").apply { writeText("not-json") }
        val store = MimoPersonalVoices(root) { 1L }
        assertThrows(Exception::class.java) { store.load() }
        assertThrows(Exception::class.java) { runBlocking { store.importSample(wav, "name", "provider-a") } }
        assertEquals("not-json", index.readText())
        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".audio") })
        index.writeText("[]")
        store.load(); assertTrue(store.state.value.isEmpty())
    }

    @Test fun noDurationOrInvalidSampleCannotLeaveStoredVoice() {
        val root = folder.newFolder()
        val store = MimoPersonalVoices(root) { 0L }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.importSample(wav, "name", "p") } }
        assertTrue(store.state.value.isEmpty())
        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".audio") })
        assertEquals("audio/mpeg", MimoPersonalVoices.mime("ID3sample".toByteArray()))
        assertEquals("audio/wav", MimoPersonalVoices.mime(wav))
        assertThrows(Exception::class.java) { MimoPersonalVoices.mime("not audio".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { MimoPersonalVoices.mime(ByteArray(MimoPersonalVoices.MAX_BYTES + 1)) }
        assertEquals(10_000_000L, ((MimoPersonalVoices.MAX_BYTES.toLong() + 2) / 3) * 4)
    }

    @Test fun supportsNeedsExplicitMimoEndpointAdmissionAndCredential() {
        assertTrue(MimoPersonalVoices.supports(config(), speechEndpointAllowed = true))
        assertFalse(MimoPersonalVoices.supports(config(), speechEndpointAllowed = false))
        assertFalse(MimoPersonalVoices.supports(config(key = ""), speechEndpointAllowed = true))
        assertFalse(MimoPersonalVoices.supports(config(engine = SpeechEngine.OPENAI), speechEndpointAllowed = true))
    }

    @Test fun personalSelectionUsesReferenceNotLocalIdOnTheWireAndChecksOwner() = runBlocking {
        val store = MimoPersonalVoices(folder.newFolder()) { 1000L }
        val voice = store.importSample(wav, "name", "provider-a")
        var calls = 0
        val transport = VoiceHttpTransport { request, _ ->
            calls++
            val json = JSONObject(request.body)
            assertEquals(MimoPersonalVoices.MODEL, json.getString("model"))
            assertFalse(json.getBoolean("stream"))
            assertEquals(store.reference(voice.id, "provider-a"), json.getJSONObject("audio").getString("voice"))
            assertFalse(request.body.contains(voice.id))
            VoiceHttpResponse(200, "application/json", """{"choices":[{"message":{"audio":{"data":"${Base64.getEncoder().encodeToString(wav)}"}}}]}""".toByteArray())
        }
        val client = SpeechSynthesisClient(transport, store)
        assertArrayEquals(wav, client.synthesize(config(), "正文", voice.id))
        assertEquals(1, calls)
        try {
            client.synthesize(SpeechProviderConfig(SpeechEngine.MIMO, "https://api.xiaomimimo.com/v1", "test-key", "mimo-v2.5-tts", "provider-b"), "正文", voice.id)
            fail()
        } catch (_: VoiceProviderException) { }
        try { client.synthesize(config(engine = SpeechEngine.OPENAI), "正文", voice.id); fail() }
        catch (_: VoiceProviderException) { }
        assertEquals(1, calls)
    }
}
