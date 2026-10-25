package app.daiyu.android.voice.providers

import java.net.URI

internal object SpeechModelCapabilities {
    private val synthesis = listOf("tts", "cosyvoice", "sambert", "speech-2", "orpheus", "playai",
        "speech-01", "speech_01", "fish-speech", "index-tts", "f5-tts", "openvoice", "bark-tts",
        "moss-ttsd", "moss_ttsd", "eleven_multilingual", "eleven_turbo", "eleven_flash", "eleven_v3")
    private val recognition = listOf("asr", "whisper", "paraformer", "sensevoice", "gummy", "transcribe", "stt")
    private val creation = listOf("seed-audio", "voiceclone", "voice-clone", "voice_clone",
        "voicedesign", "voice-design", "voice_design")

    fun readAloud(model: String): Boolean {
        val id = model.lowercase()
        return synthesis.any { it in id } && recognition.none { it in id } && creation.none { it in id }
    }

    fun builtInModels(endpoint: String): List<Pair<String, String>> {
        val host = host(endpoint)
        return when {
            host == "openspeech.bytedance.com" -> DoubaoSpeech.catalogModels.filter { readAloud(it.first) }
            "xiaomimimo" in host -> listOf("mimo-v2.5-tts" to "小米语音 2.5")
            "minimax" in host -> listOf("speech-2.8-hd" to "MiniMax Speech 2.8 HD", "speech-2.6-hd" to "MiniMax Speech 2.6 HD")
            "stepfun" in host -> listOf("step-tts-mini" to "Step TTS Mini", "stepaudio-2.5-tts" to "StepAudio 2.5 TTS")
            "dashscope" in host || "maas.aliyuncs.com" in host -> listOf(
                "qwen-audio-3.0-tts-flash" to "Qwen Audio 3.0 Flash", "qwen-audio-3.0-tts-plus" to "Qwen Audio 3.0 Plus")
            "groq.com" in host -> listOf("canopylabs/orpheus-v1-english" to "Orpheus English")
            else -> emptyList()
        }
    }

    fun host(endpoint: String): String = runCatching { URI(endpoint.trim()).host.orEmpty().lowercase() }.getOrDefault("")

    fun engine(endpoint: String, model: String): SpeechEngine {
        val host = host(endpoint)
        val id = model.lowercase()
        return when {
            host == "openspeech.bytedance.com" || (id.startsWith("seed-tts") &&
                !host.contains("volces.com") && !host.contains("volcengine.com")) -> SpeechEngine.DOUBAO
            host.contains("xiaomimimo") || id.startsWith("mimo") -> SpeechEngine.MIMO
            host.contains("minimax") || id.startsWith("speech-2") || id.startsWith("speech-01") -> SpeechEngine.MINIMAX
            host.contains("stepfun") || id.startsWith("step-tts") || id.startsWith("stepaudio") -> SpeechEngine.STEP
            host.contains("maas.aliyuncs.com") || (host.contains("dashscope") &&
                !endpoint.contains("compatible-mode", true)) || id.startsWith("qwen-audio") || id.startsWith("qwen3-tts") -> SpeechEngine.QWEN
            host.contains("groq.com") || id.contains("orpheus") || id.contains("playai") -> SpeechEngine.GROQ
            host == "api.x.ai" || host.endsWith(".x.ai") -> SpeechEngine.XAI
            host.contains("generativelanguage.googleapis.com") || ("gemini" in id && "tts" in id) -> SpeechEngine.GEMINI
            host.contains("elevenlabs") -> SpeechEngine.ELEVENLABS
            host.contains("fish.audio") || "fish-speech" in id -> SpeechEngine.FISH
            "cosyvoice" in id -> SpeechEngine.COSYVOICE
            "moss-ttsd" in id || "moss_ttsd" in id -> SpeechEngine.MOSS
            else -> SpeechEngine.OPENAI
        }
    }
}

internal val realtimeSpeechVoices = listOf(
    SpeechVoice("zh_female_vv_jupiter_bigtts", "Vivi"),
    SpeechVoice("zh_female_xiaohe_jupiter_bigtts", "小何"),
    SpeechVoice("zh_male_yunzhou_jupiter_bigtts", "云舟"),
    SpeechVoice("zh_male_xiaotian_jupiter_bigtts", "小天"),
)
