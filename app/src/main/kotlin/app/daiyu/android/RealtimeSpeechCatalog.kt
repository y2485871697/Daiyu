package app.daiyu.android

import app.daiyu.android.voice.network.OkHttpVoiceTransport
import app.daiyu.android.voice.providers.SpeechVoice
import app.daiyu.android.voice.providers.realtimeSpeechVoices
import java.util.concurrent.TimeUnit

internal fun parseRealtimeSpeechVoices(document: String): List<SpeechVoice> =
    realtimeSpeechVoices.filter { document.contains(it.id) }

internal suspend fun refreshRealtimeSpeechVoices(): List<SpeechVoice> {
    val calls = OkHttpVoiceTransport.client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
    val bytes = fetchVoiceResource(REALTIME_VOICE_SOURCE, calls)
    return try { parseRealtimeSpeechVoices(bytes.toString(Charsets.UTF_8)).also { check(it.isNotEmpty()) } }
    finally { bytes.fill(0) }
}

private const val REALTIME_VOICE_SOURCE =
    "https://docs.volcengine.com/api/doc/getDocDetail?type=online&LibraryCode=DoubaoVoice&DocumentCode=Tonelist-1"
