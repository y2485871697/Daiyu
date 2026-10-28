package app.daiyu.android.mcp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Deliberately not a data class: neither URL nor credentials may appear in logs/toString. */
internal class McpServer(val id: String, val label: String, val endpoint: String, val bearer: String,
    val enabled: Boolean = true, catalog: List<McpRemoteTool> = emptyList(),
    allowedTools: Set<String> = catalog.map { it.name }.toSet()) {
    val catalog: List<McpRemoteTool> = java.util.Collections.unmodifiableList(catalog.toList())
    // Legacy envelope fields are retained for backwards-compatible storage only. The user's
    // exact task submission grants in-app capability, including for previously disabled entries.
    // A known catalog entry is still required: absent server/tool configuration is not permission.
    val allowedTools: Set<String> = java.util.Collections.unmodifiableSet(allowedTools.toSet())
    fun eligible(tool: String): Boolean = catalog.any { it.name == tool }
    fun withPolicy(enabled: Boolean = this.enabled, allowedTools: Set<String> = this.allowedTools,
        catalog: List<McpRemoteTool> = this.catalog) = McpServer(id, label, endpoint, bearer, enabled, catalog, allowedTools)

    init {
        require(id.matches(Regex("[a-z][a-z0-9_-]{0,31}")))
        require(label.isNotBlank() && label.length <= 80 && label.none { it < ' ' })
        require(endpoint.length <= 2048 && bearer.length <= 4096 && bearer.matches(Regex("[-A-Za-z0-9._~+/=]*")))
        require(catalog.size <= 64 && catalog.map { it.name }.distinct().size == catalog.size)
        require(allowedTools.all { name -> catalog.any { it.name == name } }) { "mcp_policy_unknown_tool" }
        val url = endpoint.toHttpUrl()
        require((url.isHttps || (url.scheme == "http" && url.host == "127.0.0.1")) &&
            url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
    }
    override fun toString() = "McpServer(redacted)"
}
internal class McpSettings(val revision: String, servers: List<McpServer>, internal val stamp: Any = Any()) {
    val servers = java.util.Collections.unmodifiableList(servers.toList())
    init { require(servers.size <= 8 && servers.map { it.id }.distinct().size == servers.size) }
    override fun toString() = "McpSettings(redacted)"
}
internal class McpSettingsOwner {
    private val lock = Any()
    @Volatile private var active = true
    private val observers = linkedSetOf<() -> Unit>()
    fun revoke() {
        val callbacks = synchronized(lock) { active = false; observers.toList().also { observers.clear() } }
        callbacks.forEach { runCatching(it) }
    }
    fun observeRevoked(callback: () -> Unit): () -> Unit {
        val revoked = synchronized(lock) { if (active) { observers.add(callback); false } else true }
        if (revoked) callback()
        return { synchronized(lock) { observers.remove(callback) }; Unit }
    }
    fun current() = active
}

/** Entire envelope encrypted, authenticated to this purpose, atomic and excluded from Android backup.
 * A settings revision is NOT a task token. Saving, restoring history or listing never executes
 * a tool; the original exact task submission still supplies the single-use scope.
 */
internal class McpSettingsStore(context: Context, private val testKey: (() -> SecretKey)? = null) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "mcp-private-v1.bin"))
    private val path = file.baseFile.absolutePath
    private data class Observer(val expected: McpSettings, val invalidate: () -> Unit)
    private fun <T> access(action: () -> T): T {
        val callbacks = mutableListOf<() -> Unit>()
        try {
            return synchronized(lock) {
                try { action() } finally {
                    observers[path]?.let { values ->
                        val stale = values.filterNot { revisions[path] == it.expected.revision && stamps[path] === it.expected.stamp }
                        values.removeAll(stale.toSet()); callbacks.addAll(stale.map { it.invalidate })
                        if (values.isEmpty()) observers.remove(path)
                    }
                }
            }
        } finally { callbacks.forEach { runCatching(it) } }
    }
    fun observeInvalidated(expected: McpSettings, callback: () -> Unit): () -> Unit {
        val observer = Observer(expected, callback)
        val stale = synchronized(lock) {
            if (isCurrent(expected)) { observers.getOrPut(path) { linkedSetOf() }.add(observer); false } else true
        }
        if (stale) callback()
        return { synchronized(lock) {
            observers[path]?.let { it.remove(observer); if (it.isEmpty()) observers.remove(path) }
        }; Unit }
    }
    fun snapshot(): McpSettings = access {
        try {
            val value = read()
            if (revisions[path] != value.revision || stamps[path] == null) stamps[path] = Any()
            revisions[path] = value.revision
            McpSettings(value.revision, value.servers, checkNotNull(stamps[path]))
        } catch (failure: Exception) {
            revisions[path] = UUID.randomUUID().toString(); stamps[path] = Any(); throw failure
        }
    }
    /** Pure in-memory admission check; all host saves rotate this value, including ABA edits. */
    fun isCurrent(snapshot: McpSettings): Boolean = synchronized(lock) {
        revisions[path] == snapshot.revision && stamps[path] === snapshot.stamp
    }
    fun save(servers: List<McpServer>, owner: McpSettingsOwner, expected: McpSettings): McpSettings = access {
        check(owner.current() && isCurrent(expected) && read().revision == expected.revision) { "mcp_settings_stale" }
        val value = McpSettings(UUID.randomUUID().toString(), servers)
        val array = JSONArray()
        value.servers.forEach { array.put(JSONObject().put("id", it.id).put("label", it.label)
            .put("endpoint", it.endpoint).put("bearer", it.bearer).put("enabled", it.enabled)
            .put("catalog", JSONArray().also { tools -> it.catalog.forEach { tool -> tools.put(tool.json()) } })
            .put("allowed_tools", JSONArray(it.allowedTools.sorted()))) }
        val bytes = JSONObject().put("revision", value.revision).put("servers", array).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= McpJson.Limit.SETTINGS.bytes)
        McpJson.objectValue(bytes.toString(Charsets.UTF_8), McpJson.Limit.SETTINGS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(aad)
        val encrypted = cipher.iv + cipher.doFinal(bytes)
        check(owner.current()) { "mcp_settings_stale" }
        revisions[path] = UUID.randomUUID().toString() // Fence old approvals even if atomic write is unconfirmed.
        val output = file.startWrite()
        try { output.write(encrypted); file.finishWrite(output) }
        catch (failure: Exception) { file.failWrite(output); throw IllegalStateException("mcp_settings_save_failed") }
        check(read().revision == value.revision) { "mcp_settings_save_unconfirmed" }
        revisions[path] = value.revision
        stamps[path] = value.stamp
        value
    }
    fun clear(owner: McpSettingsOwner): McpSettings = access {
        check(owner.current()) { "mcp_settings_stale" }
        revisions[path] = UUID.randomUUID().toString()
        file.delete()
        check(!file.baseFile.exists()) { "mcp_settings_clear_unconfirmed" }
        revisions[path] = "empty"
        McpSettings("empty", emptyList()).also { stamps[path] = it.stamp }
    }
    private fun read(): McpSettings {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return McpSettings("empty", emptyList())
        try {
            val payload = file.openRead().use { input ->
                McpHttpSession.boundedRead(input, McpJson.Limit.SETTINGS.bytes + 28).also { require(it.size >= 29) }
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload, 0, 12)); cipher.updateAAD(aad)
            val json = McpJson.objectValue(cipher.doFinal(payload, 12, payload.size - 12).toString(Charsets.UTF_8), McpJson.Limit.SETTINGS)
            val list = json.getJSONArray("servers"); require(list.length() <= 8)
            val revision = McpJson.text(json, "revision")
            require(revision.matches(Regex("[a-f0-9-]{36}")))
            return McpSettings(revision, (0 until list.length()).map { i ->
                val item = list.getJSONObject(i)
                val policyKeys = listOf("enabled", "catalog", "allowed_tools")
                val hasPolicy = policyKeys.any { item.has(it) }
                require(!hasPolicy || policyKeys.all { item.has(it) })
                val tools = if (hasPolicy) item.getJSONArray("catalog").let { values ->
                    require(values.length() <= 64)
                    (0 until values.length()).map { McpRemoteTool.fromJson(values.getJSONObject(it)) }
                } else emptyList()
                val allowed = if (hasPolicy) item.getJSONArray("allowed_tools").let { values ->
                    require(values.length() <= 64)
                    (0 until values.length()).map { require(values.get(it) is String); values.getString(it) }.also {
                        require(it.distinct().size == it.size)
                    }.toSet()
                } else emptySet()
                require(!hasPolicy || item.get("enabled") is Boolean)
                McpServer(McpJson.text(item, "id"), McpJson.text(item, "label"), McpJson.text(item, "endpoint"), McpJson.text(item, "bearer"),
                    hasPolicy && item.getBoolean("enabled"), tools, allowed)
            })
        } catch (_: Exception) { throw IllegalStateException("MCP 私有配置不可读；未执行，请修复服务器配置") }
    }
    private fun key(): SecretKey {
        testKey?.let { return it() }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build()); generateKey()
        }
    }
    companion object {
        private val lock = Any()
        private val revisions = mutableMapOf<String, String>()
        private val stamps = mutableMapOf<String, Any>()
        private val observers = mutableMapOf<String, MutableSet<Observer>>()
        private const val alias = "daiyu_mcp_private_v1"
        private val aad = "daiyu:mcp:private-settings:v1".toByteArray(Charsets.UTF_8)
    }
}
