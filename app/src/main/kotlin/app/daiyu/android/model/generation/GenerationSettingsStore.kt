package app.daiyu.android.model.generation

import android.content.Context
import android.content.SharedPreferences
import app.daiyu.android.model.ProviderProtocol
import app.daiyu.android.model.ProviderRequestOptions
import app.daiyu.android.storage.ModelSettings
import app.daiyu.android.storage.ModelSettingsStore
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID

/** Configuration only: none of these values grants paid-run or download authorization. */
data class GenerationProfileSettings(
    val id: String? = null,
    val label: String,
    val kind: GenerationKind,
    val protocol: GenerationProtocol,
    val endpoint: String,
    val model: String,
    val apiKey: String,
) {
    override fun toString() = "GenerationProfileSettings(<redacted>)"
}

class GenerationSettingsValues(
    profiles: List<GenerationProfileSettings>,
    val maxPolls: Int,
    val downloadsEnabled: Boolean,
    downloadHosts: Set<String>,
) {
    val profiles: List<GenerationProfileSettings> = Collections.unmodifiableList(profiles.toList())
    /** Preserve reviewed entries even while disabled; effective download policy is then deny-all. */
    val downloadHosts: Set<String> = Collections.unmodifiableSet(downloadHosts.toSet())
    override fun toString() = "GenerationSettingsValues(<redacted>)"
}

/** Detached values for the parent. Keep this exact snapshot while preparing an approval. */
class GenerationSettingsSnapshot internal constructor(
    val accountScope: String,
    val revision: String,
    val values: GenerationSettingsValues,
) {
    val profiles: List<GenerationModelProfile> = Collections.unmodifiableList(values.profiles.map {
        GenerationModelProfile(requireNotNull(it.id), it.label, it.protocol, it.endpoint, it.apiKey, it.model)
    })
    val downloads = GenerationDownloadHosts(if (values.downloadsEnabled) values.downloadHosts else emptySet())
    val maxPolls: Int get() = values.maxPolls
    override fun toString() = "GenerationSettingsSnapshot(<redacted>)"
}

/** An unavailable key is empty ONLY in this editor result, never in a runnable snapshot.
 * Incomplete saves need a separate explicit recovery confirmation. Corrupt metadata is not repaired.
 */
class GenerationSettingsEditingSnapshot internal constructor(
    val accountScope: String,
    val revision: String,
    val values: GenerationSettingsValues,
    credentialUnavailableIds: Set<String>,
    val incompleteSave: Boolean,
) {
    val credentialUnavailableIds: Set<String> = Collections.unmodifiableSet(credentialUnavailableIds.toSet())
    override fun toString() = "GenerationSettingsEditingSnapshot(<redacted>)"
}

enum class GenerationSettingsFailure {
    MISSING, CORRUPT, INCOMPLETE_SAVE, CREDENTIAL_UNAVAILABLE, STALE_EDIT, INVALID_CONFIGURATION, SAVE_UNCONFIRMED,
}

class GenerationSettingsException(val reason: GenerationSettingsFailure) : IllegalStateException(
    when (reason) {
        GenerationSettingsFailure.MISSING -> "尚未配置独立生成模型；不会使用聊天模型"
        GenerationSettingsFailure.CORRUPT -> "生成配置已损坏；未加载默认模型，请显式处理存储故障"
        GenerationSettingsFailure.INCOMPLETE_SAVE -> "生成配置保存未完成；请检查全部原值并明确确认重新保存"
        GenerationSettingsFailure.CREDENTIAL_UNAVAILABLE -> "生成密钥不可用或绑定不符；请重新输入并保存"
        GenerationSettingsFailure.STALE_EDIT -> "生成配置已变化；请重新加载后编辑"
        GenerationSettingsFailure.INVALID_CONFIGURATION -> "生成配置无效；请检查类型、协议、完整 HTTPS endpoint、模型、密钥及下载 host"
        GenerationSettingsFailure.SAVE_UNCONFIRMED -> "生成配置未确认持久保存；禁止运行，请重新加载并检查存储"
    }
)

/** Independent manifest + per-profile encrypted credentials. Never opens the chat namespace.
 *
 * ModelSettingsStore is used ONLY as a credential vault, through fixed inert adapter fields, not
 * as a provider/default resolver. The generation endpoint/model/protocol live in this manifest.
 * A length-delimited binding digest detects missing, stale or copied credentials. No plaintext
 * key is written to preferences. A durable 'saving' manifest precedes every credential write;
 * ready is published only after credential and manifest readback. All I/O belongs off the UI thread.
 */
class GenerationSettingsStore internal constructor(
    private val preferences: SharedPreferences,
    private val loadCredential: (String) -> String,
    private val saveCredential: (String, String) -> Unit,
    private val namespace: String = METADATA_NAMESPACE,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences(METADATA_NAMESPACE, Context.MODE_PRIVATE),
        { id -> credentialStore(context, id).load().let { value ->
            check(value.copy(apiKey = "") == credentialEnvelope(""))
            value.apiKey
        } },
        { id, key -> credentialStore(context, id).save(credentialEnvelope(key)) },
    )

    fun loadSnapshot(): GenerationSettingsSnapshot = synchronized(lock) {
        if (namespace in fenced) fail(GenerationSettingsFailure.SAVE_UNCONFIRMED)
        val record = readRecord() ?: fail(GenerationSettingsFailure.MISSING)
        if (record.state != "ready") fail(GenerationSettingsFailure.INCOMPLETE_SAVE)
        val editing = readEditing(record)
        if (editing.credentialUnavailableIds.isNotEmpty()) fail(GenerationSettingsFailure.CREDENTIAL_UNAVAILABLE)
        GenerationSettingsSnapshot(record.scope, record.revision, editing.values)
    }

    /** Does not synthesize defaults for missing/corrupt metadata. The host can offer a blank draft
     * only for MISSING. This preserves every saved field while asking for lost keys explicitly. */
    fun loadForEditing(): GenerationSettingsEditingSnapshot = synchronized(lock) {
        val record = readRecord() ?: fail(if (namespace in fenced) GenerationSettingsFailure.SAVE_UNCONFIRMED
            else GenerationSettingsFailure.MISSING)
        readEditing(record)
    }

    /** Call only for an explicit settings save, not on composition, selection, or run preparation.
     * expectedRevision is null ONLY for a first save. IDs and scope are minted once into the
     * durable manifest, then retained through edits, reopening and explicit incomplete-save recovery.
     */
    fun save(
        values: GenerationSettingsValues,
        expectedRevision: String?,
        confirmIncompleteSaveRecovery: Boolean = false,
    ): GenerationSettingsSnapshot = synchronized(lock) {
        validate(values, requireIds = false)
        val previous = readRecord()
        if (previous?.revision != expectedRevision) fail(GenerationSettingsFailure.STALE_EDIT)
        if ((previous?.state == "saving" || namespace in fenced) && !confirmIncompleteSaveRecovery)
            fail(GenerationSettingsFailure.INCOMPLETE_SAVE)
        val knownIds = previous?.values?.profiles?.mapNotNull { it.id }?.toSet().orEmpty()
        if (values.profiles.any { it.id != null && it.id !in knownIds }) fail(GenerationSettingsFailure.STALE_EDIT)
        val savedValues = GenerationSettingsValues(values.profiles.map {
            it.copy(id = it.id ?: "profile_${UUID.randomUUID().toString().replace("-", "")}")
        }, values.maxPolls, values.downloadsEnabled, values.downloadHosts)
        val scope = previous?.scope ?: "generation_${UUID.randomUUID().toString().replace("-", "")}"
        val revision = UUID.randomUUID().toString()
        val bindings = savedValues.profiles.associate { requireNotNull(it.id) to binding(it) }
        val saving = encode(Record(scope, revision, "saving", savedValues, bindings))
        val ready = encode(Record(scope, revision, "ready", savedValues, bindings))
        if (saving.length > 262144 || ready.length > 262144) fail(GenerationSettingsFailure.INVALID_CONFIGURATION)
        fenced.add(namespace)
        try {
            commitAndReadBack(saving)
            savedValues.profiles.forEach { profile ->
                val id = requireNotNull(profile.id)
                saveCredential(id, profile.apiKey)
                check(loadCredential(id) == profile.apiKey)
            }
            commitAndReadBack(ready)
            val readBack = readRecord() ?: error("manifest_missing")
            check(readBack.scope == scope && readBack.revision == revision && readBack.state == "ready")
            val verified = readEditing(readBack)
            check(verified.credentialUnavailableIds.isEmpty())
            fenced.remove(namespace)
            GenerationSettingsSnapshot(scope, revision, verified.values)
        } catch (_: Exception) {
            // commit(false)/throw may still have changed memory or disk. Never expose 'ready'.
            // Best effort durable fence plus a namespace-wide process fence until explicit save.
            try { commitAndReadBack(saving) } catch (_: Exception) { }
            fail(GenerationSettingsFailure.SAVE_UNCONFIRMED)
        }
    }

    private fun commitAndReadBack(raw: String) {
        check(preferences.edit().putString(KEY, raw).commit())
        check(preferences.getString(KEY, null) == raw)
    }

    private fun readEditing(record: Record): GenerationSettingsEditingSnapshot {
        val missing = mutableSetOf<String>()
        val profiles = record.values.profiles.map { profile ->
            val id = requireNotNull(profile.id)
            val key = try { loadCredential(id) } catch (_: Exception) { "" }
            val loaded = profile.copy(apiKey = key)
            val valid = try {
                validateProfile(loaded)
                binding(loaded) == record.bindings[id]
            } catch (_: Exception) { false }
            if (valid) loaded else { missing.add(id); profile.copy(apiKey = "") }
        }
        return GenerationSettingsEditingSnapshot(record.scope, record.revision,
            GenerationSettingsValues(profiles, record.values.maxPolls, record.values.downloadsEnabled,
                record.values.downloadHosts), missing, record.state != "ready" || namespace in fenced)
    }

    private class Record(val scope: String, val revision: String, val state: String,
        val values: GenerationSettingsValues, val bindings: Map<String, String>)

    private fun readRecord(): Record? = try {
        val raw = preferences.getString(KEY, null)
        if (raw == null) null else {
            require(raw.length <= 262144)
            val root = JSONObject(raw)
            root.fields("version", "state", "scope", "revision", "profiles", "maxPolls", "downloadsEnabled", "downloadHosts")
            require(root.get("version") == 1)
            val scope = root.text("scope").also { require(it.matches(Regex("generation_[a-f0-9]{32}"))) }
            val revision = root.text("revision").also { require(UUID.fromString(it).toString() == it) }
            val state = root.text("state").also { require(it == "saving" || it == "ready") }
            val bindings = linkedMapOf<String, String>()
            val array = root.getJSONArray("profiles")
            val profiles = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                item.fields("id", "label", "kind", "protocol", "endpoint", "model", "binding")
                val id = item.text("id").also { require(it.matches(Regex("profile_[a-f0-9]{32}"))) }
                require(bindings.put(id, item.text("binding").also { require(it.matches(Regex("[a-f0-9]{64}"))) }) == null)
                GenerationProfileSettings(id, item.text("label"), GenerationKind.valueOf(item.text("kind")),
                    GenerationProtocol.valueOf(item.text("protocol")), item.text("endpoint"), item.text("model"), "validation-only")
            }
            val hostsArray = root.getJSONArray("downloadHosts")
            val hosts = (0 until hostsArray.length()).map { hostsArray.get(it) as String }
            require(hosts.distinct().size == hosts.size)
            val values = GenerationSettingsValues(profiles, root.get("maxPolls") as Int,
                root.get("downloadsEnabled") as Boolean, hosts.toSet())
            validate(values, requireIds = true)
            Record(scope, revision, state, values, bindings)
        }
    } catch (_: Exception) { fail(GenerationSettingsFailure.CORRUPT) }

    private fun encode(record: Record): String = JSONObject().put("version", 1).put("state", record.state)
        .put("scope", record.scope).put("revision", record.revision).put("maxPolls", record.values.maxPolls)
        .put("downloadsEnabled", record.values.downloadsEnabled)
        .put("downloadHosts", JSONArray(record.values.downloadHosts.toList()))
        .put("profiles", JSONArray(record.values.profiles.map {
            JSONObject().put("id", it.id).put("label", it.label).put("kind", it.kind.name)
                .put("protocol", it.protocol.name).put("endpoint", it.endpoint).put("model", it.model)
                .put("binding", record.bindings[requireNotNull(it.id)])
        })).toString()

    companion object {
        internal const val METADATA_NAMESPACE = "generation-settings-v1"
        internal const val KEY = "manifest"
        internal const val CREDENTIAL_NAMESPACE_PREFIX = "generation-credential-"
        internal const val KEY_ALIAS_PREFIX = "generation-api-key"
        private val lock = Any()
        private val fenced = mutableSetOf<String>()

        private fun credentialStore(context: Context, id: String): ModelSettingsStore {
            require(id.matches(Regex("profile_[a-f0-9]{32}")))
            return ModelSettingsStore(context, "$KEY_ALIAS_PREFIX.$id", "$CREDENTIAL_NAMESPACE_PREFIX$id")
        }
        // Inert vault envelope bypasses chat-specific endpoint/model limits. Never a network target.
        private fun credentialEnvelope(key: String) = ModelSettings(
            "https://generation-credentials.invalid/v1/responses", "generation-credential", key, false,
            protocol = ProviderProtocol.RESPONSES, requestOptions = ProviderRequestOptions())

        private fun validate(values: GenerationSettingsValues, requireIds: Boolean) {
            try {
                require(values.profiles.size in 1..16 && values.maxPolls in 0..32 && values.downloadHosts.size <= 64)
                val ids = values.profiles.mapNotNull { it.id }
                require(ids.distinct().size == ids.size && (!requireIds || ids.size == values.profiles.size))
                values.profiles.forEach(::validateProfile)
                GenerationDownloadHosts(values.downloadHosts)
            } catch (_: Exception) { fail(GenerationSettingsFailure.INVALID_CONFIGURATION) }
        }

        private fun validateProfile(profile: GenerationProfileSettings) {
            require(profile.label.length <= 2000 && profile.endpoint.length <= 4096)
            require(profile.id == null || profile.id.matches(Regex("profile_[a-f0-9]{32}")))
            require((profile.kind == GenerationKind.VIDEO) == (profile.protocol == GenerationProtocol.OPENAI_VIDEO_MULTIPART))
            GenerationModelProfile(profile.id ?: "validation", profile.label, profile.protocol,
                profile.endpoint, profile.apiKey, profile.model)
        }

        private fun binding(profile: GenerationProfileSettings): String = MessageDigest.getInstance("SHA-256").run {
            listOf("generation-credential-binding-v1", requireNotNull(profile.id), profile.label,
                profile.kind.name, profile.protocol.name, profile.endpoint, profile.model, profile.apiKey).forEach {
                val bytes = it.toByteArray(Charsets.UTF_8)
                update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); update(bytes)
            }
            digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        private fun JSONObject.text(key: String): String = get(key) as String
        private fun JSONObject.fields(vararg names: String) {
            require(keys().asSequence().toSet() == names.toSet())
        }
        private fun fail(reason: GenerationSettingsFailure): Nothing = throw GenerationSettingsException(reason)
    }
}
