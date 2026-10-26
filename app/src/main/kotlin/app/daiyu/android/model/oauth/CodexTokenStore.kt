package app.daiyu.android.model.oauth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** OAuth credentials never enter the model API-key field, custom headers, or backup metadata. */
internal class CodexTokenStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("codex-oauth-v1", Context.MODE_PRIVATE)
    private val keys get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun alias(auth: CodexAuth) = "codex-oauth.${auth.id}"
    fun read(auth: CodexAuth): CodexTokens? {
        val raw = prefs.getString(auth.id, null) ?: return null
        require(raw.length <= 192 * 1024)
        val parts = raw.split(':', limit = 2); require(parts.size == 2)
        val key = keys.getKey(alias(auth), null) as? SecretKey ?: error("OAuth 凭据不可用，请重新登录")
        val iv = Base64.decode(parts[0], Base64.NO_WRAP); require(iv.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)); cipher.updateAAD(auth.id.toByteArray())
        return CodexTokens.read(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
    }
    fun save(auth: CodexAuth, tokens: CodexTokens) {
        val key = (keys.getKey(alias(auth), null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias(auth), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(auth.id.toByteArray())
        val raw = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(tokens.encode().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString(auth.id, raw).commit()) { "OAuth 凭据保存失败" }
    }
    fun clear(auth: CodexAuth) {
        check(prefs.edit().remove(auth.id).commit()) { "OAuth 退出未确认" }
        keys.deleteEntry(alias(auth))
    }
}
