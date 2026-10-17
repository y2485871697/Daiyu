package app.daiyu.android.balance

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * P05-only AtomicFile + Android Keystore AES-GCM. Not shared with model settings or backups.
 * The whole record, including amount/error/account/URL/pointer, is encrypted. Reads never create keys.
 * Explicit saves use a fresh alias, so an unavailable old key can be recovered without deleting it first.
 */
class EncryptedBalancePersistence(
    context: Context,
    namespace: String = "provider-balance",
) : BalancePersistence {
    init { require(namespace.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) }
    private val file = AtomicFile(File(context.noBackupFilesDir, "$namespace.p05"))
    private val prefix = "daiyu.p05.$namespace."
    private val aad = "daiyu.balance.record.v1:$namespace".toByteArray(Charsets.UTF_8)

    @Synchronized
    override fun read(): ByteArray? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val envelope = file.openRead().use { input ->
            readBounded(input)
        }
        return DataInputStream(ByteArrayInputStream(envelope)).use { input ->
            check(input.readInt() == MAGIC)
            val alias = input.readUTF().also { requireAlias(it) }
            val ivSize = input.readInt().also { check(it == 12) }
            val iv = ByteArray(ivSize).also { input.readFully(it) }
            val size = input.readInt().also { check(it in 17..(ProviderBalanceStore.MAX_RECORD_BYTES + 17)) }
            val encrypted = ByteArray(size).also { input.readFully(it) }
            check(input.read() == -1)
            val key = keyStore().getKey(alias, null) as? SecretKey ?: error("Balance key unavailable")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            val plain = cipher.doFinal(encrypted)
            check(plain.isNotEmpty())
            when (plain[0].toInt()) {
                0 -> { check(plain.size == 1); null }
                1 -> plain.copyOfRange(1, plain.size)
                else -> error("Invalid balance envelope")
            }
        }
    }

    @Synchronized
    override fun write(record: ByteArray?) {
        require(record == null || record.size <= ProviderBalanceStore.MAX_RECORD_BYTES)
        // Best-effort old alias cleanup is only allowed within this dedicated namespace.
        val oldAlias = try {
            file.openRead().use { stream -> DataInputStream(stream).let { input ->
                check(input.readInt() == MAGIC)
                input.readUTF().also { requireAlias(it) }
            } }
        } catch (_: Exception) { null }
        val alias = prefix + UUID.randomUUID()
        val key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
        var committed = false
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad)
            val plain = if (record == null) byteArrayOf(0) else byteArrayOf(1) + record
            val encrypted = cipher.doFinal(plain)
            val bytes = ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use { output ->
                    output.writeInt(MAGIC)
                    output.writeUTF(alias)
                    output.writeInt(cipher.iv.size)
                    output.write(cipher.iv)
                    output.writeInt(encrypted.size)
                    output.write(encrypted)
                }
            }.toByteArray()
            val output = file.startWrite()
            try {
                output.write(bytes)
                file.finishWrite(output)
                check(file.openRead().use { readBounded(it) }.contentEquals(bytes))
                committed = true
            } catch (failure: Exception) {
                file.failWrite(output)
                throw failure
            }
        } finally {
            if (!committed) discard(alias)
        }
        if (oldAlias != null && oldAlias != alias) discard(oldAlias)
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            check(buffer.size() + count <= MAX_ENVELOPE)
            buffer.write(chunk, 0, count)
        }
        return buffer.toByteArray()
    }

    private fun requireAlias(alias: String) {
        require(alias.startsWith(prefix))
        UUID.fromString(alias.removePrefix(prefix))
    }
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun discard(alias: String) { try { keyStore().deleteEntry(alias) } catch (_: Exception) { /* no raw diagnostics */ } }
    companion object {
        private const val MAGIC = 0x50303501
        private const val MAX_ENVELOPE = ProviderBalanceStore.MAX_RECORD_BYTES + 1024
    }
}
