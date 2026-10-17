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
internal class McpServer(val id: String, val label: String, val endpoint: String, val bearer: String) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9_-]{0,31}")))
        require(label.isNotBlank() && label.length <= 80 && label.none { it < ' ' })
        require(endpoint.length <= 2048 && bearer.length <= 4096 && bearer.matches(Regex("[-A-Za-z0-9._~+/=]*")))
        val url = endpoint.toHttpUrl()
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
    }
    override fun toString() = "McpServer(redacted)"
}
internal class McpSettings(val revision: String, servers: List<McpServer>, internal val stamp: Any = Any()) {
    val servers = java.util.Collections.unmodifiableList(servers.toList())
    init { require(servers.size <= 8 && servers.map { it.id }.distinct().size == servers.size) }
    override fun toString() = "McpSettings(redacted)"
}
internal class McpSettingsOwner {
    @Volatile private var active = true
    fun revoke() { active = false }
    fun current() = active
}

/** Entire envelope encrypted, authenticated to this purpose, atomic and excluded from Android backup.
 * A settings revision is NOT approval. Saving, restoring history, or listing never enables a tool.
 */
internal class McpSettingsStore(context: Context, private val testKey: (() -> SecretKey)? = null) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "mcp-private-v1.bin"))
    private val path = file.baseFile.absolutePath
    fun snapshot(): McpSettings = synchronized(lock) {
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
    fun save(servers: List<McpServer>, owner: McpSettingsOwner, expected: McpSettings): McpSettings = synchronized(lock) {
        check(owner.current() && isCurrent(expected) && read().revision == expected.revision) { "mcp_settings_stale" }
        val value = McpSettings(UUID.randomUUID().toString(), servers)
        val array = JSONArray()
        value.servers.forEach { array.put(JSONObject().put("id", it.id).put("label", it.label)
            .put("endpoint", it.endpoint).put("bearer", it.bearer)) }
        val bytes = JSONObject().put("revision", value.revision).put("servers", array).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 60000)
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
    fun clear(owner: McpSettingsOwner): McpSettings = synchronized(lock) {
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
                McpHttpSession.boundedRead(input, 60028).also { require(it.size >= 29) }
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload, 0, 12)); cipher.updateAAD(aad)
            val json = McpJson.objectValue(cipher.doFinal(payload, 12, payload.size - 12).toString(Charsets.UTF_8))
            val list = json.getJSONArray("servers"); require(list.length() <= 8)
            val revision = McpJson.text(json, "revision")
            require(revision.matches(Regex("[a-f0-9-]{36}")))
            return McpSettings(revision, (0 until list.length()).map { i ->
                val item = list.getJSONObject(i)
                McpServer(McpJson.text(item, "id"), McpJson.text(item, "label"), McpJson.text(item, "endpoint"), McpJson.text(item, "bearer"))
            })
        } catch (_: Exception) { throw IllegalStateException("MCP 私有配置不可读；未启用服务器，请明确修复") }
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
        private const val alias = "daiyu_mcp_private_v1"
        private val aad = "daiyu:mcp:private-settings:v1".toByteArray(Charsets.UTF_8)
    }
}
