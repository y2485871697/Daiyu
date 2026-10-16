package app.daiyu.android.voice.personal

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.AtomicFile
import app.daiyu.android.voice.providers.PersonalVoiceReference
import app.daiyu.android.voice.providers.SpeechEngine
import app.daiyu.android.voice.providers.SpeechProviderConfig
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Retained/adapted from old f001fb82 voice/mimo/MimoPersonalVoices.kt.
 * One instance per app-private directory; no process singleton, settings or credential storage.
 * The parent owns this instance and explicitly authorizes sending a selected reference to MiMo.
 */
class MimoPersonalVoices(
    private val directory: File,
    private val durationReader: (File) -> Long = ::androidDuration,
) : PersonalVoiceReference {
    data class Voice(val id: String, val name: String, val providerId: String, val mime: String, val durationMs: Long)
    private var loaded = false
    private val mutable = MutableStateFlow<List<Voice>>(emptyList())
    val state = mutable.asStateFlow()

    @Synchronized fun load() {
        if (loaded) return
        check(directory.isDirectory || directory.mkdirs()) { "无法创建个人声音目录" }
        val index = AtomicFile(File(directory, "index.json"))
        // Keep failed loads retryable. In particular, imports MUST NOT replace a damaged index.
        val voices = if (!index.baseFile.exists() && !File(directory, "index.json.bak").exists()) emptyList() else {
            val text = index.openRead().use { stream ->
                val bytes = stream.readAtMost(MAX_INDEX_BYTES + 1)
                require(bytes.size <= MAX_INDEX_BYTES) { "个人声音索引过大" }
                bytes.toString(Charsets.UTF_8)
            }
            val items = JSONArray(text)
            (0 until items.length()).map { i ->
                val o = items.getJSONObject(i)
                val id = o.getString("id")
                require(validId(id)) { "个人声音标识无效" }
                val voice = Voice(id, o.getString("name"), o.getString("provider"), o.getString("mime"), o.getLong("duration"))
                require(voice.providerId.isNotBlank() && voice.name.isNotBlank() && voice.durationMs > 0 &&
                    voice.mime in setOf("audio/wav", "audio/mpeg")) { "个人声音索引无效" }
                voice
            }.also { voices -> require(voices.map { it.id }.toSet().size == voices.size) { "个人声音标识重复" } }
        }
        mutable.value = voices
        loaded = true
    }

    suspend fun import(context: Context, uri: Uri, name: String, providerId: String): Voice = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readAtMost(MAX_BYTES + 1) }
            ?: error("无法读取录音")
        importSample(bytes, name, providerId)
    }

    /** Duration is read from the stored media track, never trusted from UI metadata or MIME hints. */
    suspend fun importSample(bytes: ByteArray, name: String, providerId: String): Voice = withContext(Dispatchers.IO) {
        require(name.trim().isNotEmpty() && providerId.isNotBlank()) { "请选择提供商并填写声音名称" }
        val type = mime(bytes)
        synchronized(this@MimoPersonalVoices) {
            load()
            val id = PREFIX + UUID.randomUUID()
            val file = File(directory, "$id.audio")
            try {
                file.writeBytes(bytes)
                val duration = durationReader(file)
                require(duration > 0) { "录音无有效音轨或时长" }
                val voice = Voice(id, name.trim().take(80), providerId, type, duration)
                persist(mutable.value + voice)
                voice
            } catch (failure: Exception) { file.delete(); throw failure }
        }
    }

    @Synchronized private fun persist(voices: List<Voice>) {
        val json = JSONArray()
        voices.forEach { json.put(JSONObject().put("id", it.id).put("name", it.name).put("provider", it.providerId)
            .put("mime", it.mime).put("duration", it.durationMs)) }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_INDEX_BYTES) { "个人声音索引过大" }
        val atomic = AtomicFile(File(directory, "index.json"))
        val out = atomic.startWrite()
        try { out.write(bytes); atomic.finishWrite(out) }
        catch (failure: Exception) { atomic.failWrite(out); throw failure }
        mutable.value = voices
    }

    @Synchronized fun remove(id: String) {
        load()
        val voice = mutable.value.firstOrNull { it.id == id } ?: return
        val file = File(directory, "${voice.id}.audio")
        check(!file.exists() || file.delete()) { "录音删除失败" }
        persist(mutable.value.filterNot { it.id == id })
    }

    /** Local IDs never leave this class as provider voice IDs. Only the validated data URI is sent. */
    @Synchronized override fun reference(id: String, providerId: String): String {
        load()
        val voice = mutable.value.firstOrNull { it.id == id && it.providerId == providerId }
            ?: error("个人声音已删除或不属于此提供商")
        val bytes = File(directory, "${voice.id}.audio").inputStream().use { it.readAtMost(MAX_BYTES + 1) }
        val type = mime(bytes)
        require(type == voice.mime) { "个人声音录音格式已改变" }
        return "data:$type;base64," + Base64.getEncoder().encodeToString(bytes)
    }

    companion object {
        const val PREFIX = "mimo-local-"
        const val MODEL = "mimo-v2.5-tts-voiceclone"
        const val MAX_BASE64 = 10_000_000L
        const val MAX_BYTES = 7_500_000
        private const val MAX_INDEX_BYTES = 1_048_576
        private fun validId(id: String) = id.matches(Regex("mimo-local-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
        fun forContext(context: Context): MimoPersonalVoices =
            MimoPersonalVoices(File(context.applicationContext.filesDir, "mimo-personal-voices"))
        fun supports(config: SpeechProviderConfig, speechEndpointAllowed: Boolean): Boolean =
            speechEndpointAllowed && config.engine == SpeechEngine.MIMO && config.apiKey.isNotBlank()

        fun mime(bytes: ByteArray): String {
            require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES && ((bytes.size.toLong() + 2) / 3) * 4 <= MAX_BASE64) {
                "录音过大，Base64 编码后不能超过 10 MB"
            }
            fun tag(start: Int, value: String) = bytes.size >= start + value.length &&
                bytes.copyOfRange(start, start + value.length).toString(Charsets.US_ASCII) == value
            return when {
                tag(0, "RIFF") && tag(8, "WAVE") -> "audio/wav"
                tag(0, "ID3") || (bytes.size > 2 && bytes[0].toInt() and 255 == 255 && bytes[1].toInt() and 0xe0 == 0xe0 && bytes[1].toInt() and 6 != 0) -> "audio/mpeg"
                else -> error("仅支持有效的 MP3 或 WAV 录音")
            }
        }
    }
}

// InputStream.readNBytes(int) is not available on every supported Android API level.
private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    while (out.size() < limit) {
        val count = read(chunk, 0, minOf(chunk.size, limit - out.size()))
        if (count < 0) break
        if (count == 0) {
            val byte = read()
            if (byte < 0) break
            out.write(byte)
        } else out.write(chunk, 0, count)
    }
    return out.toByteArray()
}

private fun androidDuration(file: File): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } finally { retriever.release() }
}
