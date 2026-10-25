package app.daiyu.android.voice.providers

import app.daiyu.android.model.catalog.ModelConnectionResult
import app.daiyu.android.model.catalog.RemoteModelFailure
import app.daiyu.android.storage.ModelSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Worker-side synthesis probe. It neither plays audio nor opens a microphone. */
internal class DoubaoSpeechConnection(private val transport: VoiceHttpTransport) {
    fun test(settings: ModelSettings, isCancelled: () -> Boolean): ModelConnectionResult {
        val worker = Thread.currentThread()
        if (worker.isInterrupted || isCancelled()) return ModelConnectionResult.Failed(RemoteModelFailure.CANCELLED)
        return try {
            settings.validateConnection()
            val custom = JSONObject(settings.customHeaders.encode()).let { json ->
                json.keys().asSequence().associateWith { json.getString(it) }
            }
            val request = DoubaoSpeech.request(SpeechProviderConfig(SpeechEngine.DOUBAO,
                settings.endpoint, settings.apiKey, "seed-tts-2.0", "connection-test", custom),
                "你好", DoubaoVoices.DEFAULT_ID)
            runBlocking {
                val synthesis = async {
                    if (worker.isInterrupted || isCancelled()) throw CancellationException("语音连接测试已取消")
                    val response = transport.execute(request, SpeechSynthesisClient.MAX_AUDIO_BYTES)
                    when (response.status) {
                        in 200..299 -> {
                            try {
                                speechCheck(response.bytes.isNotEmpty() && response.bytes.size <= SpeechSynthesisClient.MAX_AUDIO_BYTES) {
                                    "语音接口未返回有效音频"
                                }
                                val audio = DoubaoSpeech.decodeResponse(response.contentType, response.bytes)
                                speechCheck(audio.isNotEmpty() && audio.size <= SpeechSynthesisClient.MAX_AUDIO_BYTES + 44) {
                                    "语音接口未返回有效音频"
                                }
                                ModelConnectionResult.SpeechConnected
                            } catch (_: Exception) { ModelConnectionResult.Failed(RemoteModelFailure.PROTOCOL) }
                        }
                        401 -> ModelConnectionResult.Failed(RemoteModelFailure.AUTHENTICATION)
                        403 -> ModelConnectionResult.Failed(RemoteModelFailure.PERMISSION)
                        404 -> ModelConnectionResult.Failed(RemoteModelFailure.ENDPOINT)
                        429 -> ModelConnectionResult.Failed(RemoteModelFailure.RATE_LIMIT)
                        in 300..399 -> ModelConnectionResult.Failed(RemoteModelFailure.REDIRECT)
                        in 500..599 -> ModelConnectionResult.Failed(RemoteModelFailure.SERVER)
                        else -> ModelConnectionResult.Failed(RemoteModelFailure.REQUEST)
                    }
                }
                // The existing settings tester exposes a cancellation predicate, not a Job.
                val cancellation = launch {
                    while (synthesis.isActive) {
                        if (worker.isInterrupted || isCancelled()) { synthesis.cancel(); break }
                        delay(25)
                    }
                }
                try {
                    val result = synthesis.await()
                    if (worker.isInterrupted || isCancelled()) ModelConnectionResult.Failed(RemoteModelFailure.CANCELLED) else result
                } finally { cancellation.cancel() }
            }
        } catch (_: InterruptedException) {
            worker.interrupt()
            ModelConnectionResult.Failed(RemoteModelFailure.CANCELLED)
        } catch (_: CancellationException) {
            ModelConnectionResult.Failed(RemoteModelFailure.CANCELLED)
        } catch (_: Exception) {
            ModelConnectionResult.Failed(if (worker.isInterrupted || isCancelled()) RemoteModelFailure.CANCELLED else RemoteModelFailure.NETWORK)
        }
    }
}
