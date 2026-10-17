package app.daiyu.android.voice.account

import app.daiyu.android.voice.providers.DoubaoPersonalVoice
import app.daiyu.android.voice.providers.SpeechEngine
import app.daiyu.android.voice.providers.SpeechProviderConfig
import app.daiyu.android.voice.providers.headers

/** Derive from saved VoiceConfiguration.speech(), never provider label or chat credentials. */
internal class VoiceAccountIdentity private constructor(val key: String, val fingerprint: String) {
    override fun toString() = "VoiceAccountIdentity(redacted)"
    companion object {
        fun from(config: SpeechProviderConfig): VoiceAccountIdentity {
            require(config.engine == SpeechEngine.DOUBAO && config.apiKey.isNotBlank()) { "请保存豆包语音配置" }
            // Exactly the same case-insensitive custom-header override as DoubaoSpeech.request.
            val sent = headers(config, "X-Api-Key" to config.apiKey)
                .entries.first { it.key.equals("X-Api-Key", true) }.value
            require(sent.isNotBlank()) { "豆包语音凭据为空" }
            return VoiceAccountIdentity(sent, DoubaoPersonalVoice.accountFingerprint(sent))
        }
    }
}
