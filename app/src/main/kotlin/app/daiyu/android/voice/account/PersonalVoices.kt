package app.daiyu.android.voice.account

import app.daiyu.android.voice.providers.DoubaoVoiceIdentity

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import android.util.Base64
import app.daiyu.android.voice.providers.DoubaoPersonalVoice
import app.daiyu.android.voice.providers.SpeechProviderConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

internal class PersonalVoiceQueryFailure(val safeMessage: String) : IllegalStateException(safeMessage)

/**
 * Account-scoped personal voice management. Instance-owned with explicit
 * suspend operations; no singleton scope, launch-on-load, polling, upload retries or cloud delete.
 */
internal class PersonalVoices(private val file: File, private val transport: AccountHttpTransport) {
    data class Voice(val id: String, val name: String, val account: String, val status: Int = -1,
        val models: Set<Int> = emptySet(), val demo: String = "", val error: String = "",
        val accepted: Boolean = false, val catalogState: String = "", val remaining: Int = -1,
        val uncertain: Boolean = false, val nameSyncPending: Boolean = false) {
        val canTrain get() = !uncertain && status != 1 && status != 4 &&
            catalogState !in setOf("Training", "Active", "Expired", "Reclaimed") && remaining != 0
        val unused get() = catalogState == "Unknown" && status !in 1..4 && !uncertain
        val showInMyVoices get() = !unused
        val ready get() = status == 2 || status == 4
        val tts get() = ready && models.any { it == 4 || it == 5 } && !uncertain
        val canImport get() = tts && catalogState in setOf("Success", "Active") && error.isBlank()
        fun providerVoice() = DoubaoPersonalVoice(id, account, if (uncertain) -1 else status, models.toSet(), accepted)
    }
    class Sample private constructor(internal val bytes: ByteArray, val format: String) {
        override fun toString() = "TrainingSample(redacted)"
        internal fun clear() = bytes.fill(0)
        companion object {
            fun of(bytes: ByteArray, format: String): Sample {
                require(bytes.isNotEmpty() && bytes.size <= MAX_SAMPLE_BYTES) { "样本需为 1 字节至 10 MiB" }
                require(format in setOf("wav", "mp3", "ogg", "m4a", "aac")) { "音频格式不支持" }
                return Sample(bytes.copyOf(), format)
            }
        }
    }
    private val mutable = MutableStateFlow<List<Voice>>(emptyList())
    val state = mutable.asStateFlow()
    private var loaded = false
    private val removed = mutableSetOf<Pair<String, String>>()
    private val running = mutableSetOf<Pair<String, String>>()

    @Synchronized fun load() {
        if (loaded) return
        val atomic = AtomicFile(file)
        val list = if (!file.exists() && !File(file.path + ".bak").exists()) emptyList() else {
            val bytes = atomic.openRead().use { readBounded(it, MAX_INDEX_BYTES) }
            val array = JSONArray(bytes.toString(Charsets.UTF_8))
            (0 until array.length()).map { decode(array.getJSONObject(it)) }
        }
        require(list.distinctBy { it.account to it.id }.size == list.size) { "账户音色索引重复" }
        mutable.value = list; loaded = true
    }
    @Synchronized private fun persist(list: List<Voice>) {
        check(loaded) { "账户音色尚未加载" }
        file.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        val bytes = JSONArray().also { a -> list.forEach { a.put(encode(it)) } }.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_INDEX_BYTES) { "本机音色记录过大" }
        val atomic = AtomicFile(file); val out = atomic.startWrite()
        try { out.write(bytes); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out); throw e }
        mutable.value = list
    }
    @Synchronized private fun save(voice: Voice, preserveAccepted: Boolean = true) {
        if ((voice.account to voice.id) in removed) return
        val old = mutable.value.firstOrNull { it.id == voice.id && it.account == voice.account }
        val value = voice.copy(accepted = voice.accepted || (preserveAccepted && old?.accepted == true))
        persist(mutable.value.filterNot { it.account == value.account && it.id == value.id } + value)
    }
    @Synchronized fun removeLocal(voice: Voice) {
        require(mutable.value.none { it.account == voice.account && it.id == voice.id && it.uncertain }) {
            "提交结果未知，须保留原音色身份供查询，不能先移除再重新训练"
        }
        persist(mutable.value.filterNot { it.account == voice.account && it.id == voice.id })
        removed.add(voice.account to voice.id)
    }
    @Synchronized fun accept(voice: Voice) {
        val current = mutable.value.firstOrNull { it.account == voice.account && it.id == voice.id } ?: return
        require(current.tts) { "请先查询确认音色可用" }
        save(current.copy(accepted = true))
    }
    fun find(id: String, config: SpeechProviderConfig): Voice? {
        val account = VoiceAccountIdentity.from(config).fingerprint
        return state.value.firstOrNull { it.id == id && it.account == account }
    }
    fun selected(id: String, config: SpeechProviderConfig): DoubaoPersonalVoice? {
        if (!DoubaoVoiceIdentity.isPersonal(id)) return null
        val voice = find(id, config)
        require(voice != null && voice.tts && voice.accepted) { "个人音色不可用，请检查训练状态、账户和首次使用确认" }
        return voice.providerVoice()
    }
    @Synchronized private fun begin(account: String, id: String, importing: Boolean = false) {
        check(loaded)
        check(running.add(account to id)) { "此音色已有请求，请等待后手动查询" }
        if (importing) removed.remove(account to id)
    }
    @Synchronized private fun finish(account: String, id: String) { running.remove(account to id) }

    suspend fun importExisting(config: SpeechProviderConfig, id: String, name: String): Voice {
        val account = VoiceAccountIdentity.from(config); val valid = existingIdentity(id)
        begin(account.fingerprint, valid, true)
        try {
            val previous = find(valid, config)
            val voice = previous?.copy(name = name.trim().ifBlank { previous.name })
                ?: Voice(valid, name.trim().ifBlank { valid }, account.fingerprint)
            val result = updated(voice, request("get_voice", account.key, identity(voice)))
            currentCoroutineContext().ensureActive()
            require(result.canImport) { "此音色尚未复刻成功或不可用，未导入；请在云端确认后重试。" }
            save(result); return result
        } finally { finish(account.fingerprint, valid) }
    }
    suspend fun listImportable(config: SpeechProviderConfig, ak: String, sk: String, project: String): List<Voice> {
        val account = VoiceAccountIdentity.from(config)
        val slots = DoubaoVoiceCatalog(transport).list(ak, sk, project)
        return slots.filter { it.state in setOf("Success", "Active") }.mapNotNull { slot ->
            currentCoroutineContext().ensureActive()
            val candidate = Voice(slot.id, slot.name, account.fingerprint,
                catalogState = slot.state, remaining = slot.remaining)
            val verified = updated(candidate, request("get_voice", account.key, identity(candidate)))
            currentCoroutineContext().ensureActive()
            verified.takeIf { it.canImport }
        }
    }
    suspend fun findTrainingSlot(config: SpeechProviderConfig, id: String, ak: String, sk: String, project: String): Voice? {
        val account = VoiceAccountIdentity.from(config)
        val valid = trainingIdentity(id)
        val slot = DoubaoVoiceCatalog(transport).list(ak, sk, project).firstOrNull { it.id == valid } ?: return null
        currentCoroutineContext().ensureActive()
        return (find(valid, config) ?: Voice(valid, slot.name, account.fingerprint))
            .copy(catalogState = slot.state, remaining = slot.remaining)
    }
    suspend fun importPurchased(config: SpeechProviderConfig, ak: String, sk: String, project: String): Int {
        val account = VoiceAccountIdentity.from(config)
        val slots = DoubaoVoiceCatalog(transport).list(ak, sk, project)
        for (slot in slots) {
            currentCoroutineContext().ensureActive()
            begin(account.fingerprint, slot.id, true)
            try {
                val old = find(slot.id, config)
                val voice = (old ?: Voice(slot.id, slot.name, account.fingerprint))
                    .copy(catalogState = slot.state, remaining = slot.remaining)
                // Uncertain submissions are ALWAYS queried, even if catalog still reports Unknown.
                if (voice.unused) save(voice.copy(error = "", demo = "", models = emptySet(), accepted = false), false)
                else {
                    try { save(updated(voice, request("get_voice", account.key, identity(voice)))) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { save(voice.copy(error = "音色尚未通过语音账户查询校验")) }
                }
            } finally { finish(account.fingerprint, slot.id) }
        }
        return slots.size
    }
    suspend fun syncName(voice: Voice, config: SpeechProviderConfig, ak: String, sk: String, project: String) {
        val account = VoiceAccountIdentity.from(config)
        require(account.fingerprint == voice.account) { "请选择此音色所属账户" }
        begin(voice.account, voice.id)
        try {
            val current = find(voice.id, config) ?: error("本机记录已移除")
            if (!current.nameSyncPending) return
            require(!current.uncertain) { "请先查询原音色，确认制作提交状态" }
            DoubaoVoiceAliases(transport).rename(ak, sk, project, current.id, current.name)
            currentCoroutineContext().ensureActive()
            synchronized(this) {
                find(current.id, config)?.takeIf { it.name == current.name }?.let {
                    save(it.copy(nameSyncPending = false))
                }
            }
        } finally { finish(voice.account, voice.id) }
    }
    suspend fun refresh(voice: Voice, config: SpeechProviderConfig) {
        val account = VoiceAccountIdentity.from(config)
        require(account.fingerprint == voice.account) { "请选择此音色所属账户" }
        begin(voice.account, voice.id)
        try {
            val current = find(voice.id, config) ?: error("本机记录已移除")
            save(updated(current, request("get_voice", account.key, identity(current))))
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            find(voice.id, config)?.let { save(it.copy(error = "查询未确认；不会重新训练，请手动查询")) }
            throw IllegalStateException("查询未确认；不会重新训练，请手动查询")
        } finally { finish(voice.account, voice.id) }
    }
    /** Only call after a visible one-request upload/charge confirmation. No auto polling. */
    suspend fun create(config: SpeechProviderConfig, sample: Sample, name: String, slotId: String?, confirmed: Boolean): String {
        require(confirmed) { "上传和训练需要明确确认" }
        val account = VoiceAccountIdentity.from(config)
        require(name.isNotBlank()) { "请填写音色名称" }
        // A new custom ID must not be a disguised retry of an interrupted paid submission.
        require(state.value.none { it.account == account.fingerprint && it.uncertain }) {
            "此账户存在提交结果未知的音色，请先查询原音色，不能生成新 ID 重训"
        }
        val id = trainingIdentity(slotId)
        begin(account.fingerprint, id, true)
        try {
            val voice = synchronized(this) {
                require(state.value.none { it.account == account.fingerprint && it.uncertain }) { "请先查询原音色，不能重复训练" }
                val previous = find(id, config)
                require(previous?.canTrain != false) { "此音色训练中、结果未知、已锁定或额度耗尽，请先查询" }
                // Identity and uncertainty commit before ANY bytes can reach the network.
                val pending = (previous ?: Voice(id, name.trim(), account.fingerprint))
                    .copy(name = name.trim(), status = -1, accepted = false, uncertain = true, nameSyncPending = id.startsWith("S_"), models = emptySet(), demo = "", error = "提交结果未确认；仅可查询，不能重复训练")
                save(pending, false)
                pending
            }
            try {
                val json = request("voice_clone", account.key, identity(voice).put("audio", JSONObject()
                    .put("data", Base64.encodeToString(sample.bytes, Base64.NO_WRAP)).put("format", sample.format))
                    .put("language", 0).put("extra_params", JSONObject().put("demo_text", "你好，这是我的个人音色试听。")))
                save(updated(voice, json), false)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                // HTTP errors (including 4xx) do not prove the paid operation never executed.
                // No business rejection code has a verified non-execution contract here either.
                // Keep the durable identity until a successful query confirms its state.
                save(voice.copy(status = -1, uncertain = true,
                    error = "训练结果未知；仅查询原音色，不会重复训练"), false)
                throw IllegalStateException("训练提交未确认，请查询原音色 $id；不会自动重试")
            }
            return id
        } finally { sample.clear(); finish(account.fingerprint, id) }
    }
    private suspend fun request(path: String, key: String, body: JSONObject): JSONObject {
        check(path in setOf("get_voice", "voice_clone"))
        val request = Request.Builder().url("https://openspeech.bytedance.com/api/v3/tts/$path")
            .header("X-Api-Key", key).header("X-Api-Request-Id", UUID.randomUUID().toString())
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val response = transport.execute(request, 1024 * 1024)
        if (path == "get_voice" && response.status !in 200..299) throw queryFailure(response.status)
        val root = try { JSONObject(response.bytes.toString(Charsets.UTF_8)) }
        catch (failure: JSONException) {
            if (path == "get_voice") throw PersonalVoiceQueryFailure("音色查询响应格式无效，请重新查询。")
            throw failure
        }
        if (path == "get_voice") {
            val code = root.opt("code")
            val codedSuccess = (code is Int || code is Long) && (code as Number).toLong() in setOf(0L, 20000000L)
            // get_voice's documented HTTP 200 response can omit code; require the actual query payload instead.
            if (root.has("code")) {
                if (!codedSuccess) throw queryFailure(response.status)
            } else {
                val status = root.opt("status")
                val models = root.opt("speaker_status")
                val responseId = root.opt("speaker_id") as? String
                val expectedCustomId = body.opt("custom_speaker_id") as? String
                val identityMatches = if (expectedCustomId == null) responseId == body.opt("speaker_id")
                    else responseId == expectedCustomId || (responseId == "custom_speaker_id" &&
                        root.opt("custom_speaker_id") == expectedCustomId)
                val returnedIdentity = root.has("speaker_id") || root.has("custom_speaker_id")
                val validModels = models is JSONArray && (0 until models.length()).all {
                    val model = models.optJSONObject(it)?.opt("model_type")
                    model is Int || model is Long
                }
                if (response.status != 200 || (returnedIdentity && !identityMatches) || status !is Int || status !in 0..4 ||
                    (status in setOf(2, 4) && !validModels) ||
                    (root.has("speaker_status") && !validModels)) {
                    throw PersonalVoiceQueryFailure("音色查询响应不完整或音色 ID 不匹配，请重新查询。")
                }
            }
        } else if (response.status !in 200..299 || root.optInt("code", -1) !in setOf(0, 20000000)) {
            throw VoiceRequestException(response.status)
        }
        return root
    }
    private fun updated(voice: Voice, root: JSONObject): Voice {
        val status = root.optInt("status", -1)
        val items = root.optJSONArray("speaker_status") ?: JSONArray()
        val statuses = (0 until items.length()).map { items.getJSONObject(it) }
        val demo = statuses.firstOrNull { it.optInt("model_type") in setOf(4, 5) }?.optString("demo_audio").orEmpty()
        return voice.copy(status = status, models = statuses.map { it.optInt("model_type") }.toSet(),
            catalogState = when (status) { 1 -> "Training"; 2 -> "Success"; 4 -> "Active"; 3 -> ""; else -> voice.catalogState },
            remaining = root.optInt("available_training_times", voice.remaining), demo = demo,
            uncertain = voice.uncertain && status !in 1..4,
            error = if (voice.uncertain && status !in 1..4) "状态仍未知；请查询原音色，不会重复训练" else "")
    }
    private fun queryFailure(http: Int) = PersonalVoiceQueryFailure(when (http) {
        401 -> "音色查询鉴权失败：HTTP 401，请核对语音 API Key。"
        403 -> "音色查询权限不足：HTTP 403，请核对语音 API Key 与音色所属项目。"
        429 -> "音色查询过于频繁：HTTP 429，请稍后重试。"
        else -> "音色查询失败：HTTP $http，请稍后重新查询。"
    })
    private class VoiceRequestException(val http: Int) : IllegalStateException("豆包账户请求 HTTP $http")
    companion object {
        const val MAX_SAMPLE_BYTES = 10 * 1024 * 1024
        private const val MAX_INDEX_BYTES = 2 * 1024 * 1024
        fun forContext(context: Context, transport: AccountHttpTransport = OkHttpAccountTransport()) =
            PersonalVoices(File(context.applicationContext.filesDir, "voice-account/personal-voices.json"), transport)
        internal fun existingIdentity(id: String): String = id.trim().also {
            require(DoubaoVoiceIdentity.isPersonal(it)) { "请填写已有的 S_ 或 Daiyu 音色 ID" }
        }
        internal fun trainingIdentity(slotId: String?): String {
            if (slotId == null) return "Daiyu" + UUID.randomUUID().toString().replace("-", "")
            return slotId.trim().also { require(DoubaoVoiceIdentity.isSlot(it)) { "请填写控制台已有的 S_ 音色 ID，不能自行编造" } }
        }
        internal fun identity(voice: Voice): JSONObject {
            val id = existingIdentity(voice.id)
            return if (DoubaoVoiceIdentity.isSlot(id)) JSONObject().put("speaker_id", id)
                else JSONObject().put("speaker_id", "custom_speaker_id").put("custom_speaker_id", id)
        }
        suspend fun readSample(context: Context, uri: Uri): Sample = withContext(Dispatchers.IO) {
            val format = when (context.contentResolver.getType(uri)) {
                "audio/wav", "audio/x-wav" -> "wav"
                "audio/mpeg" -> "mp3"
                "audio/ogg" -> "ogg"
                "audio/mp4", "audio/x-m4a" -> "m4a"
                "audio/aac" -> "aac"
                else -> error("请选择 WAV、MP3、OGG、M4A 或 AAC 音频")
            }
            val bytes = context.contentResolver.openInputStream(uri)?.use { readBounded(it, MAX_SAMPLE_BYTES) } ?: error("无法读取音频")
            try { Sample.of(bytes, format) } finally { bytes.fill(0) }
        }
        private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
            val out = ByteArrayOutputStream(); val chunk = ByteArray(8192)
            while (true) { val n = input.read(chunk); if (n < 0) break; require(out.size() + n <= limit) { "文件超过大小限制" }; out.write(chunk, 0, n) }
            return out.toByteArray()
        }
        private fun encode(v: Voice) = JSONObject().put("id", v.id).put("name", v.name).put("account", v.account)
            .put("status", v.status).put("models", JSONArray(v.models.toList())).put("demo", v.demo)
            .put("error", v.error).put("accepted", v.accepted).put("catalogState", v.catalogState)
            .put("remaining", v.remaining).put("uncertain", v.uncertain).put("nameSyncPending", v.nameSyncPending)
        private fun decode(j: JSONObject): Voice {
            val id = existingIdentity(j.getString("id")); val account = j.getString("account")
            require(account.matches(Regex("[a-f0-9]{64}")))
            val models = j.optJSONArray("models") ?: JSONArray()
            return Voice(id, j.getString("name"), account, j.optInt("status", -1),
                (0 until models.length()).map { models.getInt(it) }.toSet(), j.optString("demo"), j.optString("error"),
                j.optBoolean("accepted"), j.optString("catalogState"), j.optInt("remaining", -1), j.optBoolean("uncertain"), j.optBoolean("nameSyncPending"))
        }
    }
}
