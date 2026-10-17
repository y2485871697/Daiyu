package app.daiyu.android.voice.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Adapted frozen AES/GCM store: encrypt the entire AK/SK/project envelope, bound to TTS account. */
internal class VoiceCatalogSecretStore(context: Context, private val testKey: (() -> SecretKey)? = null) {
    private val preferences = context.applicationContext.getSharedPreferences("voice_account_control_secret_v1", Context.MODE_PRIVATE)
    @Synchronized fun read(account: String): String? {
        validate(account)
        val encoded = preferences.getString(account, null) ?: return null
        try {
            // Reject the stored representation before Base64 allocates its decoded payload.
            require(encoded.length <= MAX_ENCODED_CHARS)
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            // The payload includes a 12-byte IV and a 16-byte authentication tag.
            require(payload.size in (IV_BYTES + TAG_BYTES + 1)..MAX_ENCRYPTED_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BYTES * 8, payload, 0, IV_BYTES))
            cipher.updateAAD(account.toByteArray(Charsets.UTF_8))
            return cipher.doFinal(payload, IV_BYTES, payload.size - IV_BYTES).toString(Charsets.UTF_8)
        } catch (_: Exception) { throw IllegalStateException("控制面凭据不可读，请明确重新保存或清除") }
    }
    @Synchronized fun save(account: String, token: String) {
        validate(account)
        // Bound characters first, then UTF-8 bytes, before key access, encryption or a write.
        require(token.length <= MAX_PLAINTEXT_BYTES && token.isNotBlank()) { "控制面凭据大小无效" }
        val plaintext = token.toByteArray(Charsets.UTF_8)
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "控制面凭据过大" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey()); cipher.updateAAD(account.toByteArray(Charsets.UTF_8))
        check(cipher.iv.size == IV_BYTES)
        val payload = cipher.iv + cipher.doFinal(plaintext)
        check(payload.size <= MAX_ENCRYPTED_BYTES)
        val encoded = Base64.encodeToString(payload, Base64.NO_WRAP)
        check(encoded.length <= MAX_ENCODED_CHARS)
        check(preferences.edit().putString(account, encoded).commit()) { "控制面凭据保存失败" }
        check(read(account) == token) { "控制面凭据保存未确认" }
    }
    @Synchronized fun clear(account: String) {
        validate(account)
        check(preferences.edit().remove(account).commit()) { "控制面凭据删除失败" }
    }
    private fun validate(account: String) = require(account.matches(Regex("[a-f0-9]{64}")))
    private fun secretKey(): SecretKey {
        testKey?.let { return it() }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }
    companion object {
        private const val KEY_ALIAS = "daiyu_voice_account_control_v1"
        // A credential envelope holds only AK, SK and a project identifier, not bulk data.
        internal const val MAX_PLAINTEXT_BYTES = 4 * 1024
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        internal const val MAX_ENCRYPTED_BYTES = MAX_PLAINTEXT_BYTES + IV_BYTES + TAG_BYTES
        internal const val MAX_ENCODED_CHARS = ((MAX_ENCRYPTED_BYTES + 2) / 3) * 4
    }
}
