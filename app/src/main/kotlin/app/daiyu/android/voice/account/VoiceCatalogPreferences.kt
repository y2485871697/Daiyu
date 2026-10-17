package app.daiyu.android.voice.account

import org.json.JSONObject

/** Frozen preference semantics with no plaintext AK or SK; load/save/clear are local-only. */
internal class VoiceCatalogPreferences(private val secrets: VoiceCatalogSecretStore) {
    class Credentials(val keyId: String, val secret: String, val project: String) {
        override fun toString() = "CatalogCredentials(redacted)"
    }
    fun load(account: String): Credentials? = secrets.read(account)?.let {
        val json = JSONObject(it)
        Credentials(json.getString("ak"), json.getString("sk"), json.getString("project"))
    }
    fun save(account: String, keyId: String, secret: String, project: String) {
        require(keyId.isNotBlank() && secret.isNotBlank()) { "请填写 AK 和 SK" }
        require(listOf(keyId, secret).none { '\r' in it || '\n' in it })
        val text = JSONObject().put("ak", keyId.trim()).put("sk", secret.trim())
            .put("project", project.trim().ifBlank { "default" }).toString()
        secrets.save(account, text)
    }
    fun clear(account: String) = secrets.clear(account)
}
